/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Open developer options" patch.
 *
 * <p>A long press on the Home tab asks {@link #open} first. While the switch is on, the patch opens
 * Instagram's own developer options instead, through the opener its settings link and debug button
 * use. Those options hold Instagram's server flag screens (MetaConfig and quick experiments), where
 * a flag can be looked at and overridden on this phone.
 *
 * <p>The opener first downloads the options module and keeps a loading dialog up, one that can't be
 * cancelled, until the download answers. Nothing limits that wait, so a download that never
 * answered left the dialog up for good (#158). The press gives it {@link #LOAD_TIMEOUT_MS}: past
 * that, it takes the dialog down and opens the MetaConfig editor in its place, or says why not.
 */
public final class DeveloperOptions {
    private DeveloperOptions() {
    }

    /** How long a long press waits on the developer options before taking the loading dialog down. */
    static final long LOAD_TIMEOUT_MS = 12_000L;

    /** The Diagnostics count of presses that gave up on the load. */
    static final String TIMED_OUT = "loading timed out";

    /** The latest press's load: nothing waiting, waiting, or given up on with its answer still to come. */
    private static final int IDLE = 0;
    private static final int LOADING = 1;
    private static final int GAVE_UP = 2;

    private static final Object PRESS_LOCK = new Object();
    /** The latest long press, counted from 1, and where its load is. Both under PRESS_LOCK. */
    private static int press;
    private static int pressState = IDLE;

    /** What a press's time limit does, through Instagram's own code once the patch fills the stubs. */
    interface Host {
        /** Runs [check] on the main thread after [delayMs]. */
        void later(Runnable check, long delayMs);

        /** 1 when the loading dialog was up and is now down, 0 when it wasn't there. */
        int dismissSpinner(Activity activity);

        /** Whether the MetaConfig editor opened. */
        boolean openEditor(Activity activity);

        void toast(String text);
    }

    private static final Host INSTAGRAM = new Host() {
        @Override
        public void later(Runnable check, long delayMs) {
            new Handler(Looper.getMainLooper()).postDelayed(check, delayMs);
        }

        @Override
        public int dismissSpinner(Activity activity) {
            return dismissSpinnerNative(activity);
        }

        @Override
        public boolean openEditor(Activity activity) {
            return openOverrides(activity);
        }

        @Override
        public void toast(String text) {
            Utils.showToastLong(text);
        }
    };

    /**
     * Injected in the long press right before it hands [activity] to Instagram's opener, so the load
     * starts after this. Starts the press's time limit. Never throws, and reads no setting.
     */
    public static void opening(Object activity) {
        opening(activity, INSTAGRAM);
    }

    static void opening(Object activity, Host host) {
        try {
            if (!(activity instanceof Activity)) return;
            int id;
            synchronized (PRESS_LOCK) {
                id = ++press;
                pressState = LOADING;
            }
            // Held weakly: the check mustn't keep a closed activity alive for the wait.
            WeakReference<Activity> held = new WeakReference<>((Activity) activity);
            host.later(() -> timeUp(id, held.get(), host), LOAD_TIMEOUT_MS);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DEVELOPER_OPTIONS, "loading time limit", failure);
        }
    }

    /**
     * Injected first in both answers of the callback the opener hands the load, its success and its
     * failure. Answers 1 when the press already gave up on this load and opened the editor, and the
     * answer returns before Instagram opens the options or toasts over it. Otherwise 0, and the
     * answer runs as Instagram wrote it. Never throws, and never waits for the settings.
     */
    public static int loaded() {
        try {
            synchronized (PRESS_LOCK) {
                boolean late = pressState == GAVE_UP;
                pressState = IDLE;
                return late ? 1 : 0;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DEVELOPER_OPTIONS, "loading answer", failure);
            return 0;
        }
    }

    /**
     * The press's time is up. While its load hasn't answered and the switch is still on, takes
     * Instagram's loading dialog down, counts it, and opens the editor, or toasts when the editor
     * can't open. A load that answered in time, a newer press, a closed activity or no dialog to
     * take down leave everything as Instagram has it.
     */
    static void timeUp(int id, Activity activity, Host host) {
        try {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            if (!Utils.settingsReady() || !Settings.OPEN_DEVELOPER_OPTIONS.get()) return;
            synchronized (PRESS_LOCK) {
                if (id != press || pressState != LOADING) return;
                // Instagram's dismiss allows state loss, so this works with the activity in the background.
                if (host.dismissSpinner(activity) != 1) return;
                pressState = GAVE_UP;
            }
            HookStatus.counted(FamilyNames.DEVELOPER_OPTIONS, TIMED_OUT);
            Logger.printInfo(() -> "Developer options: the load didn't answer in " + LOAD_TIMEOUT_MS + " ms");
            if (host.openEditor(activity)) {
                host.toast(L10n.t(activity, "Instagram's developer options didn't load, so MetaConfig opened instead."));
            } else {
                host.toast(L10n.t(activity, "Instagram's developer options didn't load. Try the long press again later."));
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DEVELOPER_OPTIONS, "loading time limit", failure);
        }
    }

    /** Forgets the latest press, for tests. */
    static void forgetPress() {
        synchronized (PRESS_LOCK) {
            press = 0;
            pressState = IDLE;
        }
    }

    /** A deliberate settings action; it doesn't enable the Home long-press switch or any flag. */
    public static boolean openOverrides(Activity activity) {
        if (!canHost(activity)) return false;
        try {
            HookStatus.invoked(FamilyNames.DEVELOPER_OPTIONS);
            return openOverridesNative(activity) == 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DEVELOPER_OPTIONS, "override editor", failure);
            return false;
        }
    }

    /**
     * A deliberate settings action that opens Instagram's own Whitehat settings screen. Nothing
     * changes until the switch on that screen is turned on there, and the trust it gives the
     * phone's installed certificates lasts the 24 hours Instagram gives it.
     */
    public static boolean openWhitehat(Activity activity) {
        if (!canHost(activity)) return false;
        try {
            HookStatus.invoked(FamilyNames.DEVELOPER_OPTIONS);
            return openWhitehatNative(activity) == 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DEVELOPER_OPTIONS, "whitehat settings", failure);
            return false;
        }
    }

    /** A host that can still take a new screen. */
    private static boolean canHost(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && !activity.getFragmentManager().isStateSaved();
    }

    /** Filled by the patch with verified host/session checks and Instagram's native navigation. */
    static int openOverridesNative(Object activity) {
        return 0;
    }

    /** Filled by the patch with the same checks and navigation, to Instagram's Whitehat screen. */
    static int openWhitehatNative(Object activity) {
        return 0;
    }

    /**
     * Filled by the patch with Instagram's own dismiss of the module loading dialog on the activity's
     * fragment manager. 1 when the dialog was there, 0 when it wasn't or the stub is unfilled.
     */
    static int dismissSpinnerNative(Object activity) {
        return 0;
    }

    /** Read-only boundaries filled from the current host's signed-in manager and typed schema. */
    static Object getOverrideStoreNative(Object activity) { return null; }
    static java.io.File getOverrideFileNative(Object manager) { return null; }
    static java.util.List<?> getOverrideSchemaNative(Object manager) { return null; }
    static OverrideExchange.Parameter getOverrideParameterNative(Object parameter) { return null; }

    /**
     * Typed writer boundaries filled from Instagram's own override editor. The table is the signed-in
     * manager's native table or null. Each setter answers 1 only after the typed native call it
     * makes, and 0 while unfilled or for anything that isn't the native table. Nothing here reaches
     * a string import, a whole-table wipe or a reload.
     */
    static Object getOverrideTableNative(Object manager) { return null; }
    static int setOverrideBooleanNative(Object table, long id, int value) { return 0; }
    static int setOverrideLongNative(Object table, long id, long value) { return 0; }
    static int setOverrideDoubleNative(Object table, long id, double value) { return 0; }
    static int setOverrideStringNative(Object table, long id, String value) { return 0; }
    static int removeOverrideNative(Object table, long id) { return 0; }
    /** The value type Instagram's own decoder reads from a parameter ID, or 0 while unfilled. */
    static int getOverrideTypeNative(long id) { return 0; }

    /**
     * The config number and parameter index of one of MetaConfig's schema entries, filled by the
     * patch with plain field reads, for {@link FlagNames}. -1 while unfilled.
     */
    static int getFlagConfigNative(Object entry) { return -1; }
    static int getFlagIndexNative(Object entry) { return -1; }

    /**
     * Injected first thing in the Home tab's long press. Answers 1 while the switch is on, and the
     * patch opens the developer options and ends the press. Otherwise 0, and the long press does
     * what it did. Never throws, and never waits for the settings: before they're ready it's 0.
     */
    public static int open() {
        try {
            HookStatus.invoked(FamilyNames.DEVELOPER_OPTIONS);
            if (!Utils.settingsReady() || !Settings.OPEN_DEVELOPER_OPTIONS.get()) return 0;
            Logger.printDebug(() -> "Developer options: opened from a long press of Home");
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DEVELOPER_OPTIONS, "long press", failure);
            return 0;
        }
    }
}
