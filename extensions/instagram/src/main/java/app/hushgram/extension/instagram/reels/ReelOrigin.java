/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Find the reel you came from" patch (#30).
 *
 * <p>Tapping a reel's author opens their profile in an activity of its own. The patch calls
 * {@link #opening} first in Instagram's navigateToNormalProfile with the reel's media, and the reel's
 * id (its {@code getId()}, the same string Instagram puts on the reel's tile in a profile's grid as
 * its transition name) is held, one id at a time. The next activity to be created within a few
 * seconds is taken as that profile's, and the id is dropped when it's destroyed, so Back from the
 * profile forgets it.
 *
 * <p>The patch also calls {@link #reelsTabResumed} first in the onResume of a profile's Reels tab
 * (ClipsProfileTabFragment). When that tab is in the profile's activity and shows the reel's author
 * (the id ends with the author's user id), the tab's grid is scrolled a screen at a time until a tile
 * with the reel's id is bound, letting Instagram load more as it would for a finger. Found, the tile
 * is brought fully on screen and outlined for a moment, and the id is dropped. Not found after a few
 * screens or when the grid ends, the grid goes back to where it was, a toast says so, and the id is
 * dropped. Leaving the tab first stops the search and keeps the id for the next time the tab opens.
 *
 * <p>The hooks fail open: with the switch off, HushGram paused, the settings not read yet, a member
 * missing or anything thrown, nothing is held and the grid stays as Instagram shows it.
 */
public final class ReelOrigin {
    /** The Reels tab's argument holding whose profile it is. */
    static final String USER_KEY = "ClipsProfileTabFragment.ARGS_PROFILE_USER_ID";
    /** The type of the Reels tab's one grid field. */
    static final String GRID_TYPE = "androidx.recyclerview.widget.RecyclerView";

    /** The steps a failure is reported under. */
    static final String OPENING = "profile opened from a reel";
    static final String RESUME = "reels tab resumed";
    static final String LOOK = "looking for the reel";
    static final String WATCH = "profile screen watched";
    /** Counted when a reel's id is held, found in the grid, not found, and when the tab was left first. */
    static final String REMEMBERED = "reel remembered";
    static final String FOUND = "reel found";
    static final String NOT_FOUND = "reel not found";
    static final String LEFT = "tab left first";

    /** How long after the tap the profile's activity may take to be created. */
    static final long HOST_WINDOW_MS = 5000;
    /** How often the grid moves on while the reel is looked for. */
    static final long STEP_MS = 150;
    /** How often a tab whose grid isn't on screen yet is looked at again. */
    static final long WAIT_MS = 300;
    /** How many screens the grid moves at most, and how long the search runs at most once the grid shows. */
    static final int MAX_STEPS = 20;
    static final long GIVE_UP_MS = 15000;
    /** How long the end of the grid may stay the end before no more is coming. */
    static final long END_WAIT_MS = 4000;
    /** How long the found tile stays outlined. */
    static final long OUTLINE_MS = 1500;

    private static final Object LOCK = new Object();
    private static final Watcher WATCHER = new Watcher();
    /** The one reel id held, and when. */
    private static String held;
    private static long heldAt;
    /** The activity the profile opened in, once it's created. */
    private static WeakReference<Activity> host;
    /** Where the watcher is registered while an id is held. */
    private static Application watched;
    /** The search running, at most one. */
    private static Look looking;

    private ReelOrigin() {
    }

    /** Called first in navigateToNormalProfile with the reel's media. Never throws. */
    public static void opening(Object media) {
        opening(media, ReelOrigin::switchedOn);
    }

    static void opening(Object media, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.REEL_ORIGIN);
            if (media == null || !on.getAsBoolean()) return;
            String id = mediaId(media);
            if (id == null || id.isEmpty()) return;
            Application application = application();
            if (application == null) return;
            synchronized (LOCK) {
                looking = null;
                held = id;
                heldAt = SystemClock.uptimeMillis();
                host = null;
                if (watched == null) {
                    application.registerActivityLifecycleCallbacks(WATCHER);
                    watched = application;
                }
            }
            HookStatus.counted(FamilyNames.REEL_ORIGIN, REMEMBERED);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_ORIGIN, OPENING, failure);
            forget();
        }
    }

    /** Called first in the Reels tab's onResume with the tab. Never throws. */
    public static void reelsTabResumed(Object tab) {
        reelsTabResumed(tab, ReelOrigin::switchedOn);
    }

    static void reelsTabResumed(Object tab, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.REEL_ORIGIN);
            if (tab == null || !on.getAsBoolean()) return;
            String id;
            Activity profile;
            synchronized (LOCK) {
                id = held;
                profile = host == null ? null : host.get();
                if (id != null && profile == null && SystemClock.uptimeMillis() - heldAt > HOST_WINDOW_MS) {
                    id = null;
                    forget();
                }
            }
            if (id == null || profile == null) return;
            Method activity = method(tab, "getActivity");
            if (activity == null || activity.invoke(tab) != profile) return;
            Method arguments = method(tab, "getArguments");
            if (arguments == null) return;
            Object found = arguments.invoke(tab);
            String user = found instanceof Bundle ? ((Bundle) found).getString(USER_KEY) : null;
            if (user == null || user.isEmpty() || !id.endsWith("_" + user)) return;
            Method resumed = method(tab, "isResumed");
            Field grid = gridField(tab);
            if (resumed == null || grid == null) return;
            Look look = new Look(tab, resumed, grid, id, on);
            synchronized (LOCK) {
                if (!id.equals(held)) return;
                looking = look;
            }
            main().post(look);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_ORIGIN, RESUME, failure);
        }
    }

    /** The id held, for tests. */
    static String heldForTests() {
        synchronized (LOCK) {
            return held;
        }
    }

    /** Drops the id and anything looking for it, and stops watching activities. */
    static void forget() {
        synchronized (LOCK) {
            held = null;
            host = null;
            looking = null;
            if (watched != null) {
                watched.unregisterActivityLifecycleCallbacks(WATCHER);
                watched = null;
            }
        }
    }

    /**
     * Moves the tab's grid on a screen at a time until the reel's tile is bound, the grid ends, or
     * the steps or the time run out. Stops without a word when the tab is left, and keeps the id.
     */
    private static final class Look implements Runnable {
        private final WeakReference<Object> tab;
        private final Method resumed;
        private final Field grid;
        private final String id;
        private final BooleanSupplier on;
        private final Rect area = new Rect();
        private long started;
        private long endSince;
        private int steps;
        private int moved;

        Look(Object tab, Method resumed, Field grid, String id, BooleanSupplier on) {
            this.tab = new WeakReference<>(tab);
            this.resumed = resumed;
            this.grid = grid;
            this.id = id;
            this.on = on;
        }

        @Override public void run() {
            try {
                synchronized (LOCK) {
                    if (looking != this) return;
                }
                Object tab = this.tab.get();
                if (tab == null || !on.getAsBoolean() || !Boolean.TRUE.equals(resumed.invoke(tab))) {
                    left();
                    return;
                }
                Object found = grid.get(tab);
                if (!(found instanceof ViewGroup) || !onScreen((ViewGroup) found)) {
                    main().postDelayed(this, WAIT_MS);
                    return;
                }
                ViewGroup list = (ViewGroup) found;
                long now = SystemClock.uptimeMillis();
                if (started == 0) started = now;
                View tile = tile(list, id);
                if (tile != null) {
                    show(tile);
                    HookStatus.counted(FamilyNames.REEL_ORIGIN, FOUND);
                    forget();
                    return;
                }
                if (steps >= MAX_STEPS || now - started >= GIVE_UP_MS) {
                    giveUp(list);
                    return;
                }
                if (list.canScrollVertically(1)) {
                    int by = Math.max(1, list.getHeight() * 4 / 5);
                    list.scrollBy(0, by);
                    moved += by;
                    steps++;
                    endSince = 0;
                } else if (list.getChildCount() > 0) {
                    if (endSince == 0) {
                        endSince = now;
                    } else if (now - endSince >= END_WAIT_MS) {
                        giveUp(list);
                        return;
                    }
                }
                main().postDelayed(this, STEP_MS);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_ORIGIN, LOOK, failure);
                forget();
            }
        }

        private void left() {
            synchronized (LOCK) {
                if (looking == this) looking = null;
            }
            HookStatus.counted(FamilyNames.REEL_ORIGIN, LEFT);
        }

        /** Not found: the grid goes back to where it was, a toast says so, and the id is dropped. */
        private void giveUp(ViewGroup list) {
            if (moved > 0) list.scrollBy(0, -moved);
            HookStatus.counted(FamilyNames.REEL_ORIGIN, NOT_FOUND);
            forget();
            Utils.showToastShort(L10n.t(list.getContext(), "Couldn't find the reel you came from"));
        }

        private boolean onScreen(ViewGroup list) {
            return list.isShown() && list.getGlobalVisibleRect(area) && !area.isEmpty();
        }
    }

    /** The first view under [view] Instagram named for the reel, or null while none is bound. */
    static View tile(View view, String id) {
        if (id.equals(view.getTransitionName())) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = tile(group.getChildAt(i), id);
            if (found != null) return found;
        }
        return null;
    }

    /** Brings [tile] fully on screen, the header folding away if it must, and outlines it for a moment. */
    static void show(View tile) {
        tile.requestRectangleOnScreen(new Rect(0, 0, tile.getWidth(), tile.getHeight()), false);
        float density = tile.getResources().getDisplayMetrics().density;
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(0x33FFFFFF);
        outline.setStroke(Math.max(1, Math.round(3 * density)), Color.WHITE);
        outline.setBounds(0, 0, tile.getWidth(), tile.getHeight());
        tile.getOverlay().add(outline);
        main().postDelayed(() -> tile.getOverlay().remove(outline), OUTLINE_MS);
    }

    /** The profile's activity is the first one created after the tap; it going drops the id. */
    static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityCreated(Activity activity, Bundle saved) {
            try {
                synchronized (LOCK) {
                    if (held == null || host != null) return;
                    if (SystemClock.uptimeMillis() - heldAt <= HOST_WINDOW_MS) {
                        host = new WeakReference<>(activity);
                    } else {
                        forget();
                    }
                }
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_ORIGIN, WATCH, failure);
                forget();
            }
        }

        @Override public void onActivityDestroyed(Activity activity) {
            try {
                synchronized (LOCK) {
                    Activity profile = host == null ? null : host.get();
                    if (host != null && (profile == null || profile == activity)) forget();
                }
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_ORIGIN, WATCH, failure);
                forget();
            }
        }

        @Override public void onActivityStarted(Activity activity) {
        }

        @Override public void onActivityResumed(Activity activity) {
        }

        @Override public void onActivityPaused(Activity activity) {
        }

        @Override public void onActivityStopped(Activity activity) {
        }

        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {
        }
    }

    /** The reel's id, the string Instagram names its grid tile by, or null after reporting it missing. */
    private static String mediaId(Object media) throws ReflectiveOperationException {
        Method id;
        try {
            id = media.getClass().getMethod("getId");
        } catch (NoSuchMethodException missing) {
            HookStatus.missingMember(FamilyNames.REEL_ORIGIN, "method", media.getClass().getName(), "getId");
            return null;
        }
        Object value = id.invoke(media);
        return value instanceof String ? (String) value : null;
    }

    /** The tab's one grid field, or null after reporting it missing. */
    private static Field gridField(Object tab) {
        Field found = null;
        int candidates = 0;
        for (Field field : tab.getClass().getDeclaredFields()) {
            if (!GRID_TYPE.equals(field.getType().getName())) continue;
            found = field;
            candidates++;
        }
        if (candidates > 1) {
            HookStatus.ambiguous(FamilyNames.REEL_ORIGIN, "field", tab.getClass().getName(), GRID_TYPE, candidates);
            return null;
        }
        if (found == null) {
            HookStatus.missingMember(FamilyNames.REEL_ORIGIN, "field", tab.getClass().getName(), GRID_TYPE);
            return null;
        }
        found.setAccessible(true);
        return found;
    }

    /** A public method of the tab's, taking nothing, or null after reporting it missing. */
    private static Method method(Object tab, String name) {
        try {
            return tab.getClass().getMethod(name);
        } catch (NoSuchMethodException missing) {
            HookStatus.missingMember(FamilyNames.REEL_ORIGIN, "method", tab.getClass().getName(), name);
            return null;
        }
    }

    private static Application application() {
        Context context = Utils.getContext();
        if (context instanceof Application) return (Application) context;
        Context app = context == null ? null : context.getApplicationContext();
        return app instanceof Application ? (Application) app : null;
    }

    private static Handler main() {
        return new Handler(Looper.getMainLooper());
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.FIND_REEL_ORIGIN.get();
    }
}
