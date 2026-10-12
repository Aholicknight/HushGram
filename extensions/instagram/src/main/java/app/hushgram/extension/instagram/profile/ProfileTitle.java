/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Center the name on profiles (#140): the username at the top of a profile, yours or someone else's,
 * sits in the middle of the bar.
 *
 * <p>Instagram 450's profile bar ({@link #BAR_ID}) is a row of three: the buttons on the left, the
 * username's container ({@link #CONTAINER_ID}) and the buttons on the right. The name and its arrow
 * sit inside the container wherever its row puts them, so with more buttons on one side than the
 * other they end up off the middle. After each layout the container's shown children are moved
 * sideways together, by a translation, until their middle is the bar's, as far as the container
 * lets them go, so the name never runs under a button. The buttons don't move. A translation
 * changes no layout, and a tap on the name lands where it's drawn.
 *
 * <p>No layout listener runs for nothing: with the switch off, HushGram paused or the settings not
 * read, an activity gets only a window focus listener, which hears the switch turned on once the
 * settings close. Off, a name is put back where Instagram had it, if nothing else moved it since.
 * Anything thrown leaves the bar as it is.
 */
public final class ProfileTitle {
    /** The profile's top bar, the same on all seven 450 builds. */
    static final String BAR_ID = "profile_action_bar";
    /** The username's container inside it. */
    static final String CONTAINER_ID = "action_bar_username_container";

    /** The most views looked at for bars in one layout. */
    static final int MAX_VIEWS = 3000;

    static final String COUNT_CENTERED = "Profile name centered";

    private static final HashMap<String, Integer> IDS = new HashMap<>();
    private static volatile boolean registered;
    /** Views this class moved: the translation they had, then the one written here. */
    private static final Map<View, float[]> MOVED = new WeakHashMap<>();
    private static final Focus FOCUS = new Focus();
    private static final Layout LAYOUT = new Layout();

    /** The decor view whose observer has the listeners. */
    private static WeakReference<View> observed = new WeakReference<>(null);
    private static boolean layoutWatched;

    private ProfileTitle() {
    }

    /** Called as the application starts. Does nothing in a build without the tab bar patch. */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application) || !SettingsStatus.reelsTab()) return;
            registered = true;
            ((Application) context).registerActivityLifecycleCallbacks(new Lifecycle());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_TAB, "profile name start", failure);
        }
    }

    /** Whether names are centered now: the switch on, HushGram not paused, the settings read. */
    static boolean wanted() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.CENTER_PROFILE_NAME.get();
    }

    static int id(Context context, String name) {
        synchronized (IDS) {
            Integer found = IDS.get(name);
            if (found == null) {
                found = context.getResources().getIdentifier(name, "id", context.getPackageName());
                IDS.put(name, found);
            }
            return found;
        }
    }

    /** Tests give the bar and container ids of their own, since a test app has none of Instagram's. */
    static void idForTests(String name, int id) {
        synchronized (IDS) {
            IDS.put(name, id);
        }
    }

    /** As [activity] comes to the front: listens to it and looks once. */
    static void resumed(Activity activity) {
        try {
            leave();
            View decor = activity.getWindow() == null ? null : activity.getWindow().peekDecorView();
            if (decor == null || !decor.getViewTreeObserver().isAlive()) return;
            decor.getViewTreeObserver().addOnWindowFocusChangeListener(FOCUS);
            observed = new WeakReference<>(decor);
            refresh();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_TAB, "profile name watch", failure);
        }
    }

    /** As [activity] leaves the front: takes its listeners off. Names stay where they are until it's back. */
    static void paused(Activity activity) {
        try {
            View decor = observed.get();
            if (decor != null && decor == peek(activity)) leave();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_TAB, "profile name release", failure);
        }
    }

    @Nullable
    private static View peek(Activity activity) {
        return activity.getWindow() == null ? null : activity.getWindow().peekDecorView();
    }

    private static void leave() {
        watchLayouts(false);
        View decor = observed.get();
        if (decor != null && decor.getViewTreeObserver().isAlive()) {
            decor.getViewTreeObserver().removeOnWindowFocusChangeListener(FOCUS);
        }
        observed = new WeakReference<>(null);
    }

    /** Centers the names in the window in front while wanted, else puts them back and stops listening to layouts. */
    static void refresh() {
        View decor = observed.get();
        if (decor == null) return;
        try {
            if (!wanted()) {
                watchLayouts(false);
                putBack();
                return;
            }
            watchLayouts(true);
            centerAll(decor);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_TAB, "profile name", failure);
        }
    }

    /** Centers the name in each profile bar shown in [root]. */
    static void centerAll(View root) {
        int barId = id(root.getContext(), BAR_ID);
        int containerId = id(root.getContext(), CONTAINER_ID);
        // Most layouts are in windows with no profile bar at all, which this answers quickly.
        if (barId == 0 || containerId == 0 || root.findViewById(barId) == null) return;
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(root);
        int seen = 0;
        while (!pending.isEmpty() && seen++ < MAX_VIEWS) {
            View view = pending.poll();
            if (view.getVisibility() != View.VISIBLE) continue;
            if (view.getId() == barId) {
                View container = view.findViewById(containerId);
                if (container instanceof ViewGroup) center(view, (ViewGroup) container);
                continue;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) pending.add(group.getChildAt(i));
            }
        }
    }

    /**
     * How far [container]'s shown children move so their middle is [bar]'s middle, kept inside the
     * container. Worked out from where the layout put them, not where they're drawn, so it's the
     * same however often it's asked.
     */
    static int offset(View bar, ViewGroup container) {
        int first = Integer.MAX_VALUE;
        int last = Integer.MIN_VALUE;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child.getVisibility() == View.GONE || child.getWidth() <= 0) continue;
            first = Math.min(first, child.getLeft());
            last = Math.max(last, child.getRight());
        }
        if (first > last || bar.getWidth() <= 0) return 0;
        int at = leftIn(container, bar);
        if (at == Integer.MIN_VALUE) return 0;
        int wanted = bar.getWidth() / 2 - (at + (first + last) / 2);
        return Math.max(-first, Math.min(container.getWidth() - last, wanted));
    }

    private static void center(View bar, ViewGroup container) {
        int offset = offset(bar, container);
        boolean moved = false;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            float x = child.getTranslationX();
            float[] held = MOVED.get(child);
            if (held == null) {
                held = new float[]{x, x};
                MOVED.put(child, held);
            } else if (x != held[1]) {
                // Something else moved it since; that's where it is now.
                held[0] = x;
            }
            held[1] = held[0] + offset;
            if (x != held[1]) {
                child.setTranslationX(held[1]);
                moved = true;
            }
        }
        if (moved) HookStatus.counted(FamilyNames.REELS_TAB, COUNT_CENTERED);
    }

    /** Puts back each name moved here, if it's still where it was put. */
    private static void putBack() {
        if (MOVED.isEmpty()) return;
        for (Map.Entry<View, float[]> entry : MOVED.entrySet()) {
            View view = entry.getKey();
            float[] held = entry.getValue();
            if (view != null && view.getTranslationX() == held[1]) view.setTranslationX(held[0]);
        }
        MOVED.clear();
    }

    /** [view]'s left edge in [bar]'s coordinates, or MIN_VALUE when it isn't inside [bar]. */
    private static int leftIn(View view, View bar) {
        int left = 0;
        View at = view;
        while (at != bar) {
            left += at.getLeft();
            ViewParent parent = at.getParent();
            if (!(parent instanceof View)) return Integer.MIN_VALUE;
            at = (View) parent;
        }
        return left;
    }

    private static void watchLayouts(boolean on) {
        if (on == layoutWatched) return;
        View decor = observed.get();
        if (on && decor == null) return;
        layoutWatched = on;
        if (decor == null || !decor.getViewTreeObserver().isAlive()) return;
        if (on) decor.getViewTreeObserver().addOnGlobalLayoutListener(LAYOUT);
        else decor.getViewTreeObserver().removeOnGlobalLayoutListener(LAYOUT);
    }

    /** The window getting focus back: the settings closing, where the switch may have changed. */
    private static final class Focus implements ViewTreeObserver.OnWindowFocusChangeListener {
        @Override public void onWindowFocusChanged(boolean hasFocus) {
            if (hasFocus) refresh();
        }
    }

    /** A layout: a profile bar shown, or its buttons changed. */
    private static final class Layout implements ViewTreeObserver.OnGlobalLayoutListener {
        @Override public void onGlobalLayout() {
            refresh();
        }
    }

    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(@NonNull Activity activity) {
            resumed(activity);
        }

        @Override public void onActivityPaused(@NonNull Activity activity) {
            paused(activity);
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
        @Override public void onActivityDestroyed(@NonNull Activity activity) { }
    }

    static boolean watchingLayoutsForTests() {
        return layoutWatched;
    }

    static void resetForTests() {
        leave();
        MOVED.clear();
    }
}
