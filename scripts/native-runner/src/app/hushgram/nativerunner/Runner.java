/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.nativerunner;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.os.Process;

import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs one named case inside the installed target's own process and reports what it found.
 *
 * <p>Every result carries the facts the case ran against, measured here rather than taken on
 * trust: package, version, signer, the SHA-256 of the base APK this process loaded, the Android
 * user, the process name and whether this process has a network. scripts/native-runner.ps1
 * compares them with the local APK it was given and fails the run on any difference.
 *
 * <p>Instagram's Application.onCreate is never called, so the startup work it would start
 * (account restore, schedulers) doesn't run. Android still runs the target's attachBaseContext and
 * its content providers before this class starts, which is why the host turns the network off
 * first and every result reports the network this process sees.
 */
public final class Runner extends Instrumentation {
    static final String PREFIX = "hushgram.";
    private static final String HOST_CLASS = "com.instagram.mainactivity.InstagramMainActivity";
    private static final String EXTENSION_CLASS = "app.hushgram.extension.shared.Utils";
    // A name no patch defines. The negative control asks for it the way a real case asks for a
    // hook, so a lookup that can't fail closed shows up before any feature case trusts it.
    private static final String ABSENT_CLASS = "app.hushgram.extension.nativerunner.AbsentHook";

    private static final String SETTINGS_CLASS = "app.hushgram.extension.instagram.settings.Settings";
    private static final String BASE_SETTINGS_CLASS = "app.hushgram.extension.shared.settings.BaseSettings";
    private static final String PAUSE_CLASS = "app.hushgram.extension.shared.settings.HushgramPause";
    private static final String LAST_ACTIVE_CLASS = "app.hushgram.extension.instagram.direct.LastActive";
    // Meta's presence model keeps these names in every build. Hide your active status asks the
    // extension first in the write request's six-argument constructor.
    private static final String PRESENCE_MODEL = "com.facebook.presence.model.upi.";
    private static final String[] PRESENCE_STATUSES = {"ACTIVE", "IDLE", "OFFLINE", "DISABLED"};

    private Bundle arguments;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments;
        start();
    }

    // Left empty on purpose: the target's Application.onCreate stays out of every case. Its
    // providers have already run by now.
    @Override
    public void callApplicationOnCreate(Application app) {
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        String name = arguments == null ? "" : arguments.getString("case", "");
        result.putString(PREFIX + "case", name);
        try {
            Context target = getTargetContext();
            bind(target, result);
            boolean passed = runCase(name, target, result);
            result.putString(PREFIX + "verdict", passed ? "pass" : "fail");
            if (passed) code = Activity.RESULT_OK;
        } catch (NeedsSession needed) {
            result.putString(PREFIX + "verdict", "needs-session");
            result.putString(PREFIX + "prerequisite", needed.getMessage());
        } catch (Throwable error) {
            result.putString(PREFIX + "verdict", "error");
            result.putString(PREFIX + "error", error.getClass().getName() + ": " + error.getMessage());
        }
        finish(code, result);
    }

    static boolean runCase(String name, Context target, Bundle out) throws Exception {
        ClassLoader loader = target.getClassLoader();
        switch (name) {
            case "bind":
                return true;
            case "control-positive": {
                Class<?> host = requireClass(loader, HOST_CLASS, out);
                Class<?> extension = requireClass(loader, EXTENSION_CLASS, out);
                if (host == null || extension == null) return false;
                Method identity = extension.getMethod("getSourceBuildIdentity");
                Object answer = identity.invoke(null);
                out.putString(PREFIX + "buildIdentity", String.valueOf(answer));
                // An unpatched build, or a patch run that skipped the shared extension, answers
                // the stub's "Unknown".
                return answer instanceof String && ((String) answer).matches("hg1:[0-9a-f]{64}");
            }
            case "control-negative":
                return requireClass(loader, ABSENT_CLASS, out) != null;
            case "presence":
                return presence(target, out);
            case "presence-paused":
                return presencePaused(target, out);
            default:
                throw new IllegalArgumentException("Unknown case '" + name + "'");
        }
    }

    /**
     * Hide your active status on real write requests, built and read back, never sent: before the
     * settings are ready, with the switch off, then on. Only Active may become Idle, of the same
     * enum, and only while the switch is on. Kotlin's serializer constructor, which the hook leaves
     * out, keeps Active, and the incoming last-active text stays Instagram's own. Ends by saving
     * Pause and the switch on for presence-paused, which needs a new process to start paused.
     */
    private static boolean presence(Context target, Bundle out) throws Exception {
        ClassLoader loader = target.getClassLoader();
        Presence presence = Presence.find(loader, out);
        Class<?> utils = requireClass(loader, EXTENSION_CLASS, out);
        Class<?> settings = requireClass(loader, SETTINGS_CLASS, out);
        Class<?> base = requireClass(loader, BASE_SETTINGS_CLASS, out);
        Class<?> pause = requireClass(loader, PAUSE_CLASS, out);
        Class<?> lastActive = requireClass(loader, LAST_ACTIVE_CLASS, out);
        if (presence == null || utils == null || settings == null || base == null || pause == null || lastActive == null) return false;
        StringBuilder wrong = new StringBuilder();

        // Instagram's Application.onCreate gives the extension its context, and the runner skips it.
        boolean readyAtStart = (Boolean) utils.getMethod("settingsReady").invoke(null);
        out.putString(PREFIX + "settingsReadyAtStart", Boolean.toString(readyAtStart));
        if (readyAtStart) note(wrong, "the settings were ready before the case gave the extension its context");
        else out.putString(PREFIX + "presence.notReady", expect(wrong, "not ready", presence, presence.write("ACTIVE"), "ACTIVE", "ACTIVE"));

        utils.getMethod("setContext", Context.class).invoke(null, target);
        String reason = String.valueOf(pause.getMethod("reason").invoke(null));
        out.putString(PREFIX + "pause", reason);
        if (!"NONE".equals(reason)) note(wrong, "this start runs paused (" + reason + ")");
        Object hide = settings.getField("HIDE_ACTIVE_STATUS").get(null);
        Object showLastActive = settings.getField("SHOW_LAST_ACTIVE").get(null);
        Object paused = base.getField("PAUSED").get(null);

        save(hide, false);
        StringBuilder off = new StringBuilder();
        for (String status : PRESENCE_STATUSES) off.append(',').append(expect(wrong, "off", presence, presence.write(status), status, status));
        out.putString(PREFIX + "presence.off", off.substring(1));

        save(hide, true);
        StringBuilder on = new StringBuilder();
        for (String status : PRESENCE_STATUSES) {
            on.append(',').append(expect(wrong, "on", presence, presence.write(status), status, "ACTIVE".equals(status) ? "IDLE" : status));
        }
        out.putString(PREFIX + "presence.on", on.substring(1));
        out.putString(PREFIX + "presence.serializer", expect(wrong, "on, serializer", presence, presence.serialized("ACTIVE"), "ACTIVE", "ACTIVE"));

        // Someone last active an hour ago, with the switch still on. The presence formatter's hook
        // answers its own text only with Show when someone was last active on, and null, for
        // Instagram's own "Active 1h ago", with it off. The answer while on shows the null isn't a
        // broken path. Neither text is reported, only which one came back.
        Method exact = lastActive.getMethod("exact", long.class);
        long hourAgo = System.currentTimeMillis() - 60L * 60 * 1000;
        Object lastShown = showLastActive.getClass().getMethod("savedValue").invoke(showLastActive);
        save(showLastActive, true);
        Object replaced = exact.invoke(null, hourAgo);
        save(showLastActive, false);
        Object incoming = exact.invoke(null, hourAgo);
        save(showLastActive, (Boolean) lastShown);
        out.putString(PREFIX + "presence.incomingShown", replaced == null ? "native" : "replaced");
        out.putString(PREFIX + "presence.incoming", incoming == null ? "native" : "replaced");
        if (replaced == null) note(wrong, "on: the last-active hook gave no text with its own switch on, so its answer off proves nothing");
        if (incoming != null) note(wrong, "on: the last-active text was replaced with Show when someone was last active off");

        // The next process reads these at its start and runs paused with the switch saved on.
        save(paused, true);
        out.putString(PREFIX + "armed", "Pause and Hide your active status saved on for presence-paused");
        if (wrong.length() > 0) out.putString(PREFIX + "detail", wrong.toString());
        return wrong.length() == 0;
    }

    /**
     * A start paused by the switch, with Hide your active status saved on: Active stays Active.
     * presence leaves both saved on, and without that this case can't be judged and says so. Puts
     * both back off at the end.
     */
    private static boolean presencePaused(Context target, Bundle out) throws Exception {
        ClassLoader loader = target.getClassLoader();
        Presence presence = Presence.find(loader, out);
        Class<?> utils = requireClass(loader, EXTENSION_CLASS, out);
        Class<?> settings = requireClass(loader, SETTINGS_CLASS, out);
        Class<?> base = requireClass(loader, BASE_SETTINGS_CLASS, out);
        Class<?> pause = requireClass(loader, PAUSE_CLASS, out);
        if (presence == null || utils == null || settings == null || base == null || pause == null) return false;
        utils.getMethod("setContext", Context.class).invoke(null, target);
        Object hide = settings.getField("HIDE_ACTIVE_STATUS").get(null);
        Object paused = base.getField("PAUSED").get(null);
        try {
            String reason = String.valueOf(pause.getMethod("reason").invoke(null));
            Object saved = hide.getClass().getMethod("savedValue").invoke(hide);
            out.putString(PREFIX + "pause", reason);
            if (!"SWITCH".equals(reason) || !Boolean.TRUE.equals(saved)) {
                throw new IllegalStateException("presence-paused needs presence to run first and save Pause and the switch on (pause "
                        + reason + ", switch saved " + saved + ")");
            }
            StringBuilder wrong = new StringBuilder();
            out.putString(PREFIX + "presence.paused", expect(wrong, "paused", presence, presence.write("ACTIVE"), "ACTIVE", "ACTIVE"));
            if (wrong.length() > 0) out.putString(PREFIX + "detail", wrong.toString());
            return wrong.length() == 0;
        } finally {
            save(paused, false);
            save(hide, false);
        }
    }

    /** The write request's two constructors and status field, and the status constants. */
    static final class Presence {
        final Map<String, Object> statuses = new LinkedHashMap<>();
        Constructor<?> writer;
        Constructor<?> serializer;
        Field status;
        Object appState;
        Object pollingMode;
        Object requestType;

        static Presence find(ClassLoader loader, Bundle out) throws Exception {
            Class<?> request = requireClass(loader, PRESENCE_MODEL + "PresenceWriteRequest", out);
            Class<?> status = requireClass(loader, PRESENCE_MODEL + "PresenceStatus", out);
            Class<?> appState = requireClass(loader, PRESENCE_MODEL + "AppState", out);
            Class<?> polling = requireClass(loader, PRESENCE_MODEL + "PresencePollingMode", out);
            Class<?> type = requireClass(loader, PRESENCE_MODEL + "PresenceWriteRequestType", out);
            if (request == null || status == null || appState == null || polling == null || type == null) return null;
            Presence found = new Presence();
            Map<String, Object> constants = constants(status);
            for (String name : PRESENCE_STATUSES) {
                if (!constants.containsKey(name)) {
                    missing(out, status.getName() + "." + name);
                    return null;
                }
                found.statuses.put(name, constants.get(name));
            }
            Class<?>[] parameters = {appState, polling, status, type, Long.class, String.class};
            Class<?>[] masked = Arrays.copyOf(parameters, parameters.length + 1);
            masked[parameters.length] = int.class;
            try {
                found.writer = request.getDeclaredConstructor(parameters);
                found.serializer = request.getDeclaredConstructor(masked);
            } catch (NoSuchMethodException absent) {
                missing(out, request.getName() + ".<init>");
                return null;
            }
            for (Field field : request.getDeclaredFields()) {
                if (field.getType() == status && !Modifier.isStatic(field.getModifiers())) {
                    if (found.status != null) {
                        missing(out, request.getName() + " one status field");
                        return null;
                    }
                    found.status = field;
                }
            }
            if (found.status == null) {
                missing(out, request.getName() + " status field");
                return null;
            }
            found.writer.setAccessible(true);
            found.serializer.setAccessible(true);
            found.status.setAccessible(true);
            found.appState = first(appState);
            found.pollingMode = first(polling);
            found.requestType = first(type);
            return found;
        }

        /** The status a request built by Instagram's writer path holds. */
        Object write(String status) throws Exception {
            Object request = writer.newInstance(appState, pollingMode, statuses.get(status), requestType, 0L, "hushgram-native-runner");
            return this.status.get(request);
        }

        /**
         * The status a request built by Kotlin's serializer constructor holds. Its mask has a bit
         * for each of the six fields, set for a value given and clear for null, so 63 gives all.
         */
        Object serialized(String status) throws Exception {
            Object request = serializer.newInstance(appState, pollingMode, statuses.get(status), requestType, 0L, "hushgram-native-runner", 63);
            return this.status.get(request);
        }
    }

    /**
     * "GIVEN>STORED" for one request. A stored status that isn't the very constant expected, the
     * same object of the same enum, goes in wrong.
     */
    private static String expect(StringBuilder wrong, String step, Presence presence, Object stored, String given, String expected) {
        String storedName = stored instanceof Enum ? ((Enum<?>) stored).name() : String.valueOf(stored);
        if (stored != presence.statuses.get(expected)) note(wrong, step + ": " + given + " was stored as " + storedName + ", expected " + expected);
        return given + ">" + storedName;
    }

    private static void note(StringBuilder wrong, String problem) {
        if (wrong.length() > 0) wrong.append("; ");
        wrong.append(problem);
    }

    private static void save(Object setting, boolean value) throws Exception {
        Object saved = setting.getClass().getMethod("save", Object.class).invoke(setting, value);
        if (!Boolean.TRUE.equals(saved)) throw new IllegalStateException("Could not save " + setting + " as " + value);
    }

    /** Every constant of an enum by name, read from its static fields, which works without values(). */
    private static Map<String, Object> constants(Class<?> type) throws IllegalAccessException {
        Map<String, Object> found = new LinkedHashMap<>();
        for (Field field : type.getDeclaredFields()) {
            if (field.getType() != type || !Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof Enum) found.put(((Enum<?>) value).name(), value);
        }
        return found;
    }

    /** The enum's constant with ordinal 0. */
    private static Object first(Class<?> type) throws IllegalAccessException {
        for (Object value : constants(type).values()) {
            if (((Enum<?>) value).ordinal() == 0) return value;
        }
        throw new IllegalStateException(type.getName() + " has no constant");
    }

    private static void missing(Bundle out, String name) {
        String key = PREFIX + "missingHook";
        String earlier = out.getString(key);
        out.putString(key, earlier == null ? name : earlier + "," + name);
    }

    /**
     * Thrown by a case that can only be judged against a signed-in session. The runner's user is
     * always signed out, so such a case reports what it needs instead of passing or failing.
     */
    static final class NeedsSession extends Exception {
        NeedsSession(String prerequisite) {
            super(prerequisite);
        }
    }

    /** The class from the target's loader, or null with its name added to the missing hooks. */
    static Class<?> requireClass(ClassLoader loader, String name, Bundle out) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException absent) {
            missing(out, name);
            return null;
        }
    }

    private static void bind(Context target, Bundle out) throws Exception {
        String packageName = target.getPackageName();
        PackageInfo info = target.getPackageManager()
                .getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
        out.putString(PREFIX + "package", packageName);
        out.putString(PREFIX + "versionName", info.versionName);
        out.putString(PREFIX + "versionCode", Long.toString(info.getLongVersionCode()));
        Signature[] signers = info.signingInfo == null ? new Signature[0]
                : info.signingInfo.getApkContentsSigners();
        String[] digests = new String[signers.length];
        for (int i = 0; i < signers.length; i++) {
            digests[i] = hex(MessageDigest.getInstance("SHA-256").digest(signers[i].toByteArray()));
        }
        Arrays.sort(digests);
        out.putString(PREFIX + "signerSha256", String.join(",", digests));
        out.putString(PREFIX + "baseApkSha256", sha256(target.getApplicationInfo().sourceDir));
        out.putString(PREFIX + "user", Integer.toString(Process.myUid() / 100000));
        out.putString(PREFIX + "process", Application.getProcessName());
        ConnectivityManager connectivity = target.getSystemService(ConnectivityManager.class);
        Network active = connectivity == null ? null : connectivity.getActiveNetwork();
        out.putString(PREFIX + "network", active == null ? "none" : "up");
    }

    private static String sha256(String path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = new FileInputStream(path)) {
            for (int read; (read = in.read(buffer)) > 0; ) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format("%02x", value & 0xff));
        return text.toString();
    }
}
