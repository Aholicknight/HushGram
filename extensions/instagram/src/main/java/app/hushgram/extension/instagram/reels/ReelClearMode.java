/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedHashSet;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Keep clear mode on (#138): once you turn Instagram's clear mode on for a reel, the next reels you
 * swipe to start in it too, until you leave it yourself.
 *
 * <p>Instagram 450 calls clear mode its fullscreen view and keeps it per reel. Turning it on goes
 * through one handler, whose action reads the reel the pager is on, sets that reel's own flag and
 * hides its buttons. A reel in clear mode draws an exit button, and when that button leaves the
 * screen Instagram turns clear mode off for its reel through the same handler, naming
 * {@link #SWIPED_AWAY} as the reason. That is why a swipe to the next reel ends it, and why every
 * reel has to be put in clear mode by hand.
 *
 * <p>The patch tells this class each time the handler turned clear mode on ({@link #entered}) and
 * off ({@link #left}), which reel Instagram's turn-on is about to act on ({@link #current}), each
 * page the Reels pager lands on ({@link #paged}) and each reel whose exit button Instagram built
 * ({@link #built}). When a reel you put in clear mode leaves it because it left the screen, this
 * class asks the handler to turn clear mode on again, with the reason your own turn-on gave, so
 * Instagram does to the next reel exactly what your tap would have done. It hides nothing itself.
 *
 * <p>Instagram's turn-on acts on whatever reel the pager is on, so {@link #current} looks at that
 * reel first and holds the turn-on back when it would be the wrong one: the reel that just left
 * clear mode, which is what the pager still names when the viewer is closing or the swipe hasn't
 * landed, or a page with no exit button yet, which could never leave clear mode again. A swipe
 * that hasn't landed, or a page whose button isn't built yet, gets one more try when the pager
 * reports its next page or the button is built, within {@link #RETRY_WINDOW_MS} (#233). Anything else ends the carrying until you turn clear mode on again.
 *
 * <p>Leaving clear mode yourself, by its exit button or the menu, forgets it, and the next reels
 * show their buttons. What is kept is small and holds no view: whether clear mode is wanted, the
 * reason your turn-on gave (an enum constant), a few reel ids, and for the length of one retry the
 * handler, weakly. It lives in memory only. Nothing is carried while the switch is off, HushGram is
 * paused, the settings aren't ready, or anything here fails, and Instagram's own turn-on and
 * turn-off always run as they would.
 *
 * <p>The hooks run on Instagram's main thread, where it handles taps and page changes. The one that
 * doesn't, {@link #built}, keeps to a lock of its own.
 */
public final class ReelClearMode {
    /** The reason Instagram gives when a reel leaves clear mode because it left the screen. */
    static final String SWIPED_AWAY = "REELS_VIEWER_SWIPE_OR_EXIT";

    /** Counted under the patch's name for each reel clear mode was carried to. */
    static final String CARRIED = "reels started in clear mode";

    /** How long after a reel left clear mode the pager's next page still gets it. */
    static final long RETRY_WINDOW_MS = 3_000;

    /** How many reels are remembered as having an exit button of their own. */
    static final int REMEMBERED = 64;

    private static final String FAMILY = FamilyNames.REEL_DECLUTTER;

    /** What Instagram's turn-on found on the pager while this class asked for it. */
    private static final int NOTHING = 0;
    private static final int SAME_REEL = 1;
    private static final int NO_BUTTON = 2;
    private static final int REACHED = 3;
    /** A reel Instagram hasn't built an exit button for yet: its button may still come (#233). */
    private static final int NOT_BUILT = 4;

    /** What this class does to Instagram and reads from it. {@link #PATCHED} is the patch's; tests stand in. */
    interface Viewer {
        /** Turns clear mode on through Instagram's own handler, with the reason given. */
        void enter(Object handler, Object source);

        /** A reel's id. */
        @Nullable
        String id(Object item);

        /** Runs the work on the main thread once what's running there now is done. */
        void post(Runnable work);

        /** Milliseconds that only go forward. */
        long now();
    }

    static final Viewer PATCHED = new Viewer() {
        @Override
        public void enter(Object handler, Object source) {
            ReelClearMode.enter(handler, source);
        }

        @Override
        public String id(Object item) {
            return reelId(item);
        }

        @Override
        public void post(Runnable work) {
            Utils.runOnMainThread(work);
        }

        @Override
        public long now() {
            return SystemClock.uptimeMillis();
        }
    };

    static volatile Viewer access = PATCHED;

    /** You turned clear mode on, and haven't turned it off. */
    private static volatile boolean wanted;

    /** The reason your own turn-on gave, handed back to Instagram for each next reel. */
    @Nullable
    private static volatile Object source;

    /** The reel in clear mode now, by id, or null when none is. */
    @Nullable
    private static volatile String shown;

    /** The reel Instagram's turn-on last acted on, read by {@link #entered} right after. */
    @Nullable
    private static volatile String enabling;

    /** True while this class is asking the handler to turn clear mode on. */
    private static volatile boolean carrying;

    /** The reel that just left clear mode, which a carried turn-on must not land on again. */
    @Nullable
    private static volatile String from;

    private static volatile int outcome = NOTHING;

    @Nullable
    private static volatile String reached;

    /** The handler to ask again on the pager's next page, until {@link #retryUntil}. */
    @Nullable
    private static volatile WeakReference<Object> waiting;

    /** When the wait for the pager's next page ends, or 0 when nothing waits. */
    private static volatile long retryUntil;

    private static volatile boolean retryPosted;

    private static final Object BUILT_LOCK = new Object();

    /** The ids of the reels whose exit button Instagram built last, oldest first. */
    private static final LinkedHashSet<String> BUILT = new LinkedHashSet<>();

    @Nullable
    private static volatile String lastBuilt;

    private ReelClearMode() {
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: the handler's own turn-on. Only the handler and its reason may be passed. */
    public static void enter(Object handler, Object source) {
    }

    /** Filled in by the patch: the reel's own getId. Only a reel may be passed. */
    public static String reelId(Object item) {
        return null;
    }

    // ------------------------------------------------------------------ hooks

    /**
     * The hook, in the handler's turn-on right after its action ran. Your own turn-on is remembered
     * with the reel it acted on. One this class asked for comes through here too and is left alone.
     */
    public static void entered(@Nullable Object handler, @Nullable Object reason) {
        try {
            HookStatus.invoked(FAMILY);
            if (carrying) return;
            String id = enabling;
            enabling = null;
            if (!on() || handler == null || reason == null || id == null) {
                drop();
                return;
            }
            HookStatus.bound(FAMILY, "clear mode");
            wanted = true;
            source = reason;
            shown = id;
            clearRetry();
            Logger.printDebug(() -> "Clear mode: turned on, the next reels start in it");
        } catch (Throwable failure) {
            drop();
            HookStatus.threw(FAMILY, "clear mode on", failure);
        }
    }

    /**
     * The hook, in the handler's turn-off right after its action ran, with the reel and the reason.
     * Any reason but {@link #SWIPED_AWAY} is you leaving clear mode, which forgets it. A reel that
     * left the screen in the clear mode you asked for hands it to the reel the pager is on now.
     */
    public static void left(@Nullable Object handler, @Nullable Object item, @Nullable Object reason) {
        try {
            HookStatus.invoked(FAMILY);
            if (carrying) return;
            if (!swipedAway(reason)) {
                if (wanted) Logger.printDebug(() -> "Clear mode: turned off, the next reels show their buttons");
                drop();
                return;
            }
            if (!wanted) return;
            if (!on()) {
                drop();
                return;
            }
            String id = item == null ? null : access.id(item);
            String was = shown;
            if (handler == null || id == null || !id.equals(was)) return;
            shown = null;
            clearRetry();
            carry(handler, id, false);
        } catch (Throwable failure) {
            drop();
            HookStatus.threw(FAMILY, "clear mode off", failure);
        }
    }

    /**
     * The hook, in Instagram's turn-on where it has read the reel the pager is on and before it
     * tests it. Your own turn-on gets its reel back as it is. One this class asked for gets the reel
     * only when it's another reel than the one that just left clear mode and Instagram built it an
     * exit button. Otherwise it gets null, which Instagram's own test takes as no reel and does
     * nothing.
     */
    @Nullable
    public static Object current(@Nullable Object item) {
        boolean ours = carrying;
        try {
            HookStatus.invoked(FAMILY);
            if (!ours) {
                // Cleared first, so a reel whose id can't be read leaves no older reel's behind.
                enabling = null;
                if (item != null) enabling = access.id(item);
                return item;
            }
            String id = item == null ? null : access.id(item);
            if (id == null) {
                outcome = NO_BUTTON;
                return null;
            }
            if (id.equals(from)) {
                outcome = SAME_REEL;
                return null;
            }
            if (!hasButton(id)) {
                outcome = NOT_BUILT;
                return null;
            }
            outcome = REACHED;
            reached = id;
            return item;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "clear mode reel", failure);
            if (!ours) return item;
            outcome = NO_BUTTON;
            return null;
        }
    }

    /**
     * The hook, first thing when the Reels pager reports the page it landed on. A reel that left
     * clear mode before its swipe landed gets its one more try, once Instagram is done with the page.
     */
    public static void paged() {
        try {
            HookStatus.invoked(FAMILY);
            long until = retryUntil;
            if (until == 0) return;
            if (!on() || access.now() > until) {
                drop();
                return;
            }
            if (retryPosted) return;
            retryPosted = true;
            access.post(ReelClearMode::retry);
        } catch (Throwable failure) {
            retryPosted = false;
            drop();
            HookStatus.threw(FAMILY, "clear mode page", failure);
        }
    }

    /**
     * The hook, first thing where Instagram builds a reel's exit button, with the reel. Only a reel
     * seen here can leave clear mode again, so only such a reel is put in it.
     */
    public static void built(@Nullable Object item) {
        try {
            HookStatus.invoked(FAMILY);
            if (item == null) return;
            String id = access.id(item);
            if (id == null || id.equals(lastBuilt)) return;
            synchronized (BUILT_LOCK) {
                BUILT.remove(id);
                BUILT.add(id);
                if (BUILT.size() > REMEMBERED) {
                    Iterator<String> oldest = BUILT.iterator();
                    oldest.next();
                    oldest.remove();
                }
                lastBuilt = id;
            }
            wakeIfWaiting();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "clear mode exit button", failure);
        }
    }

    // ------------------------------------------------------------------ the rule

    /**
     * A reel that was waiting for its exit button may have it now: the one more try runs once Instagram
     * is done with the button. Built can run off the main thread, so the try is posted (#233).
     */
    private static void wakeIfWaiting() {
        long until = retryUntil;
        if (until == 0 || retryPosted || access.now() > until) return;
        retryPosted = true;
        access.post(ReelClearMode::retry);
    }

    /** The pager's next page, once Instagram has handled it: the one more try. */
    private static void retry() {
        try {
            retryPosted = false;
            WeakReference<Object> held = waiting;
            String left = from;
            long until = retryUntil;
            clearRetry();
            if (until == 0 || !wanted) return;
            Object handler = held == null ? null : held.get();
            if (!on() || handler == null || left == null) {
                drop();
                return;
            }
            carry(handler, left, true);
        } catch (Throwable failure) {
            drop();
            HookStatus.threw(FAMILY, "clear mode retry", failure);
        }
    }

    /**
     * Asks the handler to turn clear mode on for the reel the pager is on, after the reel
     * {@code left} left it. {@link #current} says what Instagram found there. A pager still on that
     * reel waits for its next page, once. Anything else that isn't a reel put in clear mode forgets it.
     */
    private static void carry(Object handler, String left, boolean lastTry) {
        Object reason = source;
        if (reason == null) {
            drop();
            return;
        }
        from = left;
        outcome = NOTHING;
        reached = null;
        carrying = true;
        try {
            access.enter(handler, reason);
        } finally {
            carrying = false;
        }
        int found = outcome;
        String id = reached;
        outcome = NOTHING;
        reached = null;
        if (found == REACHED && id != null) {
            from = null;
            shown = id;
            HookStatus.counted(FAMILY, CARRIED);
            Logger.printDebug(() -> "Clear mode: carried to the next reel");
        } else if ((found == SAME_REEL || found == NOT_BUILT) && !lastTry) {
            waiting = new WeakReference<>(handler);
            retryUntil = access.now() + RETRY_WINDOW_MS;
        } else {
            Logger.printDebug(() -> "Clear mode: nothing to carry it to, the next reels show their buttons");
            drop();
        }
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.KEEP_REEL_CLEAR_MODE.get();
    }

    private static boolean swipedAway(@Nullable Object reason) {
        return reason instanceof Enum && SWIPED_AWAY.equals(((Enum<?>) reason).name());
    }

    private static boolean hasButton(String id) {
        synchronized (BUILT_LOCK) {
            return BUILT.contains(id);
        }
    }

    private static void clearRetry() {
        retryUntil = 0;
        waiting = null;
    }

    /** Off, paused, turned off by you or failed: nothing is wanted, so nothing comes back later. */
    private static void drop() {
        wanted = false;
        source = null;
        shown = null;
        enabling = null;
        from = null;
        clearRetry();
    }

    /** Whether clear mode is wanted for the next reels. For tests. */
    static boolean wanted() {
        return wanted;
    }

    /** Whether a reel that left clear mode waits for the pager's next page. For tests. */
    static boolean waiting() {
        return retryUntil != 0;
    }

    /** Forgets everything, the reels with an exit button too. For tests. */
    static void forget() {
        drop();
        carrying = false;
        outcome = NOTHING;
        reached = null;
        retryPosted = false;
        synchronized (BUILT_LOCK) {
            BUILT.clear();
            lastBuilt = null;
        }
        access = PATCHED;
    }
}
