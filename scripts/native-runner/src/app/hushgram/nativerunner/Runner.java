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
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.Arrays;

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
            boolean passed = runCase(name, target.getClassLoader(), result);
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

    static boolean runCase(String name, ClassLoader loader, Bundle out) throws Exception {
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
            default:
                throw new IllegalArgumentException("Unknown case '" + name + "'");
        }
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
        } catch (ClassNotFoundException missing) {
            String key = PREFIX + "missingHook";
            String earlier = out.getString(key);
            out.putString(key, earlier == null ? name : earlier + "," + name);
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
