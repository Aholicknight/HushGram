/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.ResourceIdCache;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for Rounded corners on Reels (#362), part of the Rounded post corners patch.
 *
 * <p>Each page of the Reels viewer, in the Reels tab and wherever a reel opens full screen, keeps its
 * video in a frame of its own, {@link #FRAME}. The seek bar, captions and buttons are drawn in layers
 * beside it, so cutting that frame's corners rounds the video and leaves them whole. Nothing of
 * Instagram's code changes: while the switch is on, a resumed activity gets one global-layout
 * listener, which notes that the screen was laid out again, and one pre-draw listener, which then
 * looks through the window once and rounds each video frame it finds that isn't rounded yet, at the
 * size Corner size picks. A new page is laid out as it comes in, so it's rounded before its first
 * frame shows.
 *
 * <p>With the switch off, HushGram paused or the settings not read, an activity gets no listener,
 * and one already listening puts every frame it rounded back as Instagram had it and drops both
 * listeners at its next frame. A resumed activity decides again, and so does the one under HushGram
 * settings when the switch is flipped, so the switch needs no restart.
 * Frames are held weakly. Anything thrown leaves the screen as Instagram drew it.
 */
public final class ReelCorners {
    /** The frame a reel's video sits in, alone, under the viewer's overlay. */
    static final String FRAME = "clips_viewer_video_layout";

    /** The most views looked at in one search, so a strange tree can't make a long one. */
    static final int MAX_VIEWS = 6000;

    static final String HOOK = "reel frame";
    static final String COUNT_ROUNDED = "reel frames rounded";

    private static final RoundedFrames rounded = new RoundedFrames();
    private static final ResourceIdCache ids = new ResourceIdCache();
    private static final WeakHashMap<Activity, Session> sessions = new WeakHashMap<>();
    private static volatile boolean registered;
    private static volatile boolean logged;

    private ReelCorners() {
    }

    /** Called as the application starts. Does nothing in a build without the patch. */
    public static void install(Context context) {
        try {
            if (registered || !SettingsStatus.roundedPostCorners() || !(context instanceof Application)) return;
            registered = true;
            ((Application) context).registerActivityLifecycleCallbacks(new Watcher());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ROUNDED_POST_CORNERS, HOOK + " start", failure);
        }
    }

    /** Whether reels are rounded now: the switch on, HushGram not paused, the settings read. */
    static boolean wanted() {
        return Utils.settingsReady() && Settings.ROUNDED_REEL_CORNERS.get();
    }

    /** Starts watching [activity] if the switch is on, or puts back what was rounded if it's off. */
    static void watch(Activity activity) {
        try {
            if (!wanted()) {
                if (!rounded.isEmpty()) rounded.putBack();
                return;
            }
            Session session;
            synchronized (sessions) {
                session = sessions.get(activity);
                if (session == null) {
                    session = new Session(activity);
                    sessions.put(activity, session);
                }
            }
            session.attach();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ROUNDED_POST_CORNERS, HOOK, failure);
        }
    }

    /**
     * The switch was flipped in HushGram settings. They open as a dialog over [activity], which stays
     * resumed, so it decides again now rather than at its next resume. Posted, so it reads the value
     * this change saves.
     */
    public static void switchChanged(@Nullable Activity activity) {
        if (activity != null) Utils.runOnMainThread(() -> watch(activity));
    }

    static void unwatch(Activity activity) {
        try {
            Session session;
            synchronized (sessions) {
                session = sessions.remove(activity);
            }
            if (session != null) session.detach();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ROUNDED_POST_CORNERS, HOOK, failure);
        }
    }

    /** Tests give the frame an id of their own, since a test app has none of Instagram's. */
    static void idForTests(Context context, int id) {
        ids.putForTests(context.getPackageName(), FRAME, id);
    }

    static int roundedForTests() {
        return rounded.size();
    }

    static boolean listeningForTests(Activity activity) {
        synchronized (sessions) {
            Session session = sessions.get(activity);
            return session != null && session.observer != null;
        }
    }

    static void resetForTests() {
        ids.clear();
        synchronized (sessions) {
            for (Session session : new ArrayList<>(sessions.values())) session.detach();
            sessions.clear();
        }
        rounded.clear();
        logged = false;
    }

    /** One activity's listeners. */
    private static final class Session implements ViewTreeObserver.OnGlobalLayoutListener,
            ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private final WeakReference<Activity> activity;
        @Nullable
        private ViewTreeObserver observer;
        private boolean waiting;
        private boolean dirty = true;

        Session(Activity activity) {
            this.activity = new WeakReference<>(activity);
        }

        void attach() {
            Activity owner = activity.get();
            if (owner == null || observer != null || waiting) return;
            HookStatus.invoked(FamilyNames.ROUNDED_POST_CORNERS);
            View decor = owner.getWindow().getDecorView();
            if (decor.isAttachedToWindow()) {
                listen(decor);
            } else {
                // An observer taken from a view that isn't attached is a stand-in thrown away on attach.
                waiting = true;
                decor.addOnAttachStateChangeListener(this);
            }
        }

        private void listen(View decor) {
            ViewTreeObserver tree = decor.getViewTreeObserver();
            tree.addOnGlobalLayoutListener(this);
            tree.addOnPreDrawListener(this);
            observer = tree;
            dirty = true;
            decor.invalidate();
        }

        void detach() {
            ViewTreeObserver tree = observer;
            observer = null;
            if (tree != null && tree.isAlive()) {
                tree.removeOnGlobalLayoutListener(this);
                tree.removeOnPreDrawListener(this);
            }
            Activity owner = activity.get();
            if (waiting && owner != null) owner.getWindow().getDecorView().removeOnAttachStateChangeListener(this);
            waiting = false;
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            if (!waiting) return;
            waiting = false;
            view.removeOnAttachStateChangeListener(this);
            if (observer == null && wanted()) listen(view);
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
        }

        @Override
        public void onGlobalLayout() {
            dirty = true;
        }

        @Override
        public boolean onPreDraw() {
            try {
                Activity owner = activity.get();
                if (owner == null || !wanted()) {
                    rounded.putBack();
                    detach();
                    return true;
                }
                if (dirty) {
                    dirty = false;
                    int id = ids.resolve(owner.getResources(), owner.getPackageName(), FRAME, false);
                    if (id == 0) {
                        HookStatus.missingViewId(FamilyNames.ROUNDED_POST_CORNERS, FRAME);
                        return true;
                    }
                    HookStatus.recoveredViewId(FamilyNames.ROUNDED_POST_CORNERS, FRAME);
                    float radius = Settings.ROUNDED_CORNER_SIZE.get().dp * owner.getResources().getDisplayMetrics().density;
                    walk(owner.getWindow().getDecorView(), id, radius, new int[] {MAX_VIEWS});
                }
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.ROUNDED_POST_CORNERS, HOOK, failure);
            }
            // The outline is set before this frame is drawn, so the frame already shows it.
            return true;
        }

        private void walk(View view, int id, float radius, int[] budget) {
            if (budget[0]-- <= 0) return;
            if (view.getId() == id) {
                if (rounded.round(view, radius)) {
                    HookStatus.counted(FamilyNames.ROUNDED_POST_CORNERS, COUNT_ROUNDED);
                    if (!logged) {
                        logged = true;
                        Logger.printDebug(() -> "Rounded corners on Reels: rounded a reel's video frame");
                    }
                }
                return;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), id, radius, budget);
            }
        }
    }

    private static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(@NonNull Activity activity) {
            watch(activity);
        }

        @Override public void onActivityPaused(@NonNull Activity activity) {
            unwatch(activity);
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) { }
        @Override public void onActivityDestroyed(@NonNull Activity activity) { }
    }
}
