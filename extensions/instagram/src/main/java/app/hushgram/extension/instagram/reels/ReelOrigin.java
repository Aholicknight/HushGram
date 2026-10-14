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
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.ScreenColors;
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
 * (the id ends with the author's user id), the tab's grid is dragged up most of a screen at a time,
 * the way a finger drags it, until a tile with the reel's id is bound. It's a drag and not a scroll
 * because Instagram loads the grid's next page only for a drag: scrolled, the grid stopped at the
 * first page's end and never got more. At the loaded end it's dragged again now and then while the
 * next page comes. Found, the tile is brought fully on screen and outlined for a moment, and the id
 * is dropped. Not found after a few screens or when the grid ends, the grid goes back to where it
 * was, a toast says so, and the id is dropped. Leaving the tab first stops the search and keeps the
 * id for the next time the tab opens.
 *
 * <p>Nothing is dragged and no end is waited for until the grid holds a tile Instagram named. A
 * profile can open straight on its Reels tab (451 does for some reels), and then the tab resumes
 * before its first page: the grid shows nine grey placeholder tiles with no names, as many as fill
 * the screen, so it can't scroll. Taken for the loaded end, they made the search give up four
 * seconds in with the grid unmoved (#30). Each time more named tiles come in at the end, as when
 * Instagram fills a screen its first page left short, the wait for the end starts again.
 *
 * <p>While the grid is dragged, a Stop looking button sits near the bottom of the profile's screen,
 * and a tap on it ends the search where the grid is and drops the id. With Wait for a tap on, a
 * reel that isn't on the first screen isn't looked for until the button, reading Find the reel
 * then, is tapped; until then a reel scrolled to by hand is still outlined.
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
    /** Counted when the Find the reel button is put up for a tap, and when Stop looking ends a search. */
    static final String OFFERED = "find button shown";
    static final String STOPPED = "search stopped";
    /** Counted once a search when it waits for the grid's first named tiles, as behind the placeholders. */
    static final String WAITED = "waited for the first page";
    /**
     * Why a search gave up, counted beside reel not found: the grid ended with nothing more coming,
     * the screens or the time ran out while it still went on, or no tile was ever named.
     */
    static final String ENDED = "grid ended";
    static final String RAN_OUT = "screens ran out";
    static final String NO_TILES = "no named tiles";
    /** Counted when the Reels tab in the profile's screen is another profile's than the reel's author. */
    static final String OTHER_PROFILE = "other profile's tab";

    /** How long after the tap the profile's activity may take to be created. */
    static final long HOST_WINDOW_MS = 5000;
    /** How often the grid moves on while the reel is looked for. */
    static final long STEP_MS = 150;
    /**
     * How long after its window's next frame a tab whose grid isn't on screen yet is looked at again,
     * or how often while the grid isn't in a window at all.
     */
    static final long WAIT_MS = 300;
    /** How often the grid is looked at while Find the reel waits for its tap, for the tab being left or the reel scrolled to by hand. */
    static final long TAP_WAIT_MS = 500;
    /** How many times the grid is dragged on at most, and how long the search runs at most once the grid shows. */
    static final int MAX_STEPS = 20;
    static final long GIVE_UP_MS = 15000;
    /** How long the end of the grid may stay the end before no more is coming. */
    static final long END_WAIT_MS = 4000;
    /** How often the grid is dragged again while it sits at its loaded end. */
    static final long NUDGE_MS = 1000;
    /** How many moves a drag takes, how far apart in time, and how long it holds still before letting go. */
    static final int DRAG_MOVES = 8;
    static final long MOVE_MS = 10;
    static final long HOLD_MS = 100;
    /** How long the found tile stays outlined. */
    static final long OUTLINE_MS = 1500;
    /** How far above the bottom of the profile's screen, past the navigation bar, the button sits, clear of a tab bar. */
    static final int BUTTON_ABOVE_DP = 72;

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
    /** View's computeVerticalScrollOffset, read once. */
    private static Method scrollOffset;

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
                stopLooking();
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
        reelsTabResumed(tab, on, ReelOrigin::waitsForTap);
    }

    static void reelsTabResumed(Object tab, BooleanSupplier on, BooleanSupplier waits) {
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
            if (user == null || user.isEmpty()) return;
            if (!id.endsWith("_" + user)) {
                HookStatus.counted(FamilyNames.REEL_ORIGIN, OTHER_PROFILE);
                return;
            }
            Method resumed = method(tab, "isResumed");
            Field grid = gridField(tab);
            if (resumed == null || grid == null) return;
            Look look = new Look(tab, resumed, grid, id, on, profile, !waits.getAsBoolean());
            synchronized (LOCK) {
                if (!id.equals(held)) return;
                stopLooking();
                looking = look;
            }
            look.handler.post(look);
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
            stopLooking();
            if (watched != null) {
                watched.unregisterActivityLifecycleCallbacks(WATCHER);
                watched = null;
            }
        }
    }

    /** Ends the search running, if any, and takes its button down. Called holding LOCK. */
    private static void stopLooking() {
        Look was = looking;
        looking = null;
        if (was == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            was.takeButtonDown();
        } else {
            was.handler.post(was::takeButtonDown);
        }
    }

    /** The search's button, for tests: null while none is up. */
    static TextView buttonForTests() {
        synchronized (LOCK) {
            return looking == null ? null : looking.button;
        }
    }

    /**
     * Drags the tab's grid on most of a screen at a time until the reel's tile is bound, the grid
     * ends, the steps or the time run out, or Stop looking is tapped. Made not going, it waits for a
     * tap on Find the reel first. Stops without a word when the tab is left, and keeps the id.
     */
    private static final class Look implements Runnable {
        final Handler handler = main();
        private final WeakReference<Object> tab;
        private final Method resumed;
        private final Field grid;
        private final String id;
        private final BooleanSupplier on;
        private final WeakReference<Activity> screen;
        private final Rect area = new Rect();
        /** Whether the grid is being dragged, from the start or since Find the reel was tapped. */
        private boolean going;
        /** The button on the profile's screen while it's up, touched only on the main thread. */
        private TextView button;
        private long started;
        private long endSince;
        private long dragged;
        private int steps;
        /** Where the grid was when the search started. */
        private int start;
        /** Since when the search has waited for the grid's first named tiles, and how many were bound at the end last time. */
        private long unnamedSince;
        private int endNamed;
        /** Whether the last drag left the grid where it was, though the grid said more lay below. */
        private boolean stuck;

        Look(Object tab, Method resumed, Field grid, String id, BooleanSupplier on, Activity screen, boolean going) {
            this.tab = new WeakReference<>(tab);
            this.resumed = resumed;
            this.grid = grid;
            this.id = id;
            this.on = on;
            this.screen = new WeakReference<>(screen);
            this.going = going;
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
                    takeButtonDown();
                    // A grid already in its window comes on screen only with a frame, so it's looked
                    // at again after the window's next one rather than every WAIT_MS while nothing
                    // changes, as when Instagram keeps the tab resumed beside the one showing.
                    if (!(found instanceof View) || !afterNextFrame((View) found)) handler.postDelayed(this, WAIT_MS);
                    return;
                }
                ViewGroup list = (ViewGroup) found;
                View tile = tile(list, id);
                if (tile != null) {
                    show(tile);
                    HookStatus.counted(FamilyNames.REEL_ORIGIN, FOUND);
                    forget();
                    return;
                }
                int named = named(list);
                long now = SystemClock.uptimeMillis();
                if (named == 0) {
                    // No reel bound yet, only the placeholders Instagram shows while the first page
                    // loads, or nothing: no button, no drag and no end until named tiles come.
                    if (going) {
                        if (unnamedSince == 0) {
                            unnamedSince = now;
                            HookStatus.counted(FamilyNames.REEL_ORIGIN, WAITED);
                        } else if (now - unnamedSince >= GIVE_UP_MS) {
                            giveUp(list, NO_TILES);
                            return;
                        }
                    }
                    handler.postDelayed(this, WAIT_MS);
                    return;
                }
                if (!going) {
                    // Up once the grid has named tiles, while the tab shows; a screen with no place
                    // for it looks straight away.
                    if (putButtonUp(list)) {
                        handler.postDelayed(this, TAP_WAIT_MS);
                        return;
                    }
                    going = true;
                }
                if (started == 0) {
                    started = now;
                    start = offset(list);
                }
                putButtonUp(list);
                if (steps >= MAX_STEPS || now - started >= GIVE_UP_MS) {
                    giveUp(list, RAN_OUT);
                    return;
                }
                if (list.canScrollVertically(1) && !stuck) {
                    // On 451 the grid can say more lies below at its loaded end while the next page
                    // loads (its range is an estimate), and a drag there moves nothing. Such a drag is
                    // the end, not a step: counted, the steps ran out in three seconds (#30).
                    int before = offset(list);
                    drag(list);
                    dragged = now;
                    if (offset(list) != before) {
                        steps++;
                        endSince = 0;
                    } else {
                        stuck = true;
                    }
                } else {
                    // At the end: more named tiles coming in, as Instagram fills a screen its page
                    // left short, start the wait again.
                    if (endSince == 0) {
                        endSince = now;
                        endNamed = named;
                    } else if (named != endNamed) {
                        endSince = now;
                        endNamed = named;
                        stuck = false;
                    } else if (now - endSince >= END_WAIT_MS) {
                        giveUp(list, ENDED);
                        return;
                    }
                    if (now - dragged >= NUDGE_MS) {
                        int before = offset(list);
                        drag(list);
                        dragged = now;
                        if (offset(list) != before) {
                            stuck = false;
                            endSince = 0;
                        }
                    }
                }
                handler.postDelayed(this, STEP_MS);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_ORIGIN, LOOK, failure);
                forget();
            }
        }

        /** Find the reel starts the search; Stop looking ends it where the grid is and drops the id. */
        private void tapped() {
            try {
                synchronized (LOCK) {
                    if (looking != this) return;
                }
                if (going) {
                    HookStatus.counted(FamilyNames.REEL_ORIGIN, STOPPED);
                    forget();
                    return;
                }
                going = true;
                handler.removeCallbacks(this);
                handler.post(this);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_ORIGIN, LOOK, failure);
                forget();
            }
        }

        private void left() {
            synchronized (LOCK) {
                if (looking == this) looking = null;
            }
            takeButtonDown();
            HookStatus.counted(FamilyNames.REEL_ORIGIN, LEFT);
        }

        /**
         * Puts the button up near the bottom of the profile's screen, reading Find the reel while the
         * search waits and Stop looking while it goes, or relabels it. False when the screen's content
         * has no place for it.
         */
        private boolean putButtonUp(View list) {
            Activity activity = screen.get();
            View content = activity == null ? null : activity.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout)) return false;
            Context context = list.getContext();
            String label = going ? L10n.t(context, "Stop looking") : L10n.t(context, "Find the reel");
            if (button == null) {
                button = button(context, this::tapped);
                ((FrameLayout) content).addView(button, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
                if (!going) HookStatus.counted(FamilyNames.REEL_ORIGIN, OFFERED);
            }
            if (!label.contentEquals(button.getText())) {
                button.setText(label);
                ScreenColors.styleAction(button, !going);
            }
            // Again each pass, so a turned screen or a navigation bar that came or went moves it too.
            int margin = buttonMargin(content);
            ViewGroup.LayoutParams place = button.getLayoutParams();
            if (place instanceof ViewGroup.MarginLayoutParams && ((ViewGroup.MarginLayoutParams) place).bottomMargin != margin) {
                ((ViewGroup.MarginLayoutParams) place).bottomMargin = margin;
                button.setLayoutParams(place);
            }
            return true;
        }

        /** Takes the button off the profile's screen, on the main thread. */
        void takeButtonDown() {
            TextView up = button;
            button = null;
            if (up != null && up.getParent() instanceof ViewGroup) ((ViewGroup) up.getParent()).removeView(up);
        }

        /**
         * Not found, for [why]: the grid goes back to where it was, a toast says so, and the id is
         * dropped.
         */
        private void giveUp(ViewGroup list, String why) throws ReflectiveOperationException {
            int back = started == 0 ? 0 : start - offset(list);
            if (back != 0) list.scrollBy(0, back);
            HookStatus.counted(FamilyNames.REEL_ORIGIN, NOT_FOUND);
            HookStatus.counted(FamilyNames.REEL_ORIGIN, why);
            forget();
            Utils.showToastShort(L10n.t(list.getContext(), "Couldn't find the reel you came from"));
        }

        private boolean onScreen(ViewGroup list) {
            return list.isShown() && list.getGlobalVisibleRect(area) && !area.isEmpty();
        }

        /**
         * Looks again WAIT_MS after the next frame of [grid]'s window, once. False when the grid isn't
         * in a window yet, which no frame of a window would tell.
         */
        private boolean afterNextFrame(View grid) {
            if (!grid.isAttachedToWindow()) return false;
            ViewTreeObserver observer = grid.getViewTreeObserver();
            if (!observer.isAlive()) return false;
            observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                    handler.postDelayed(Look.this, WAIT_MS);
                    return true;
                }
            });
            return true;
        }
    }

    /** The first view under [view] Instagram named for the reel, or null while none is bound. */
    static View tile(View view, String id) {
        if (matches(view.getTransitionName(), id)) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = tile(group.getChildAt(i), id);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Whether a tile Instagram named [name] is the reel [id]. Instagram names a tile by its reel's
     * id, the reel's own number then its author's ({@code 3100_7}), so the two are equal. The reel's
     * own number alone is enough either way, for a tile or an id that drops the author.
     */
    static boolean matches(String name, String id) {
        if (name == null || id == null || name.isEmpty() || id.isEmpty()) return false;
        if (name.equals(id)) return true;
        String reel = reelNumber(id);
        return reel != null && reel.equals(reelNumber(name));
    }

    /** The reel's own number at the start of [id], up to the author's, or null when it doesn't start with one. */
    private static String reelNumber(String id) {
        int end = id.indexOf('_');
        if (end < 0) end = id.length();
        if (end == 0) return null;
        for (int i = 0; i < end; i++) {
            if (id.charAt(i) < '0' || id.charAt(i) > '9') return null;
        }
        return id.substring(0, end);
    }

    /** How many views under [view] Instagram named, its reels' tiles: none while only placeholders show. */
    static int named(View view) {
        String name = view.getTransitionName();
        int found = name == null || name.isEmpty() ? 0 : 1;
        if (!(view instanceof ViewGroup)) return found;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) found += named(group.getChildAt(i));
        return found;
    }

    /**
     * Drags [list] up from near its bottom to near its top as a finger would, holding still before
     * letting go so it doesn't fling on. The grid takes it like any drag, folding the profile's header
     * away first and asking Instagram for the next page near the end.
     */
    static void drag(View list) {
        float x = list.getWidth() / 2f;
        float from = list.getHeight() * 9 / 10f;
        float to = list.getHeight() / 10f;
        long down = SystemClock.uptimeMillis();
        touch(list, down, down, MotionEvent.ACTION_DOWN, x, from);
        for (int move = 1; move <= DRAG_MOVES; move++) {
            touch(list, down, down + move * MOVE_MS, MotionEvent.ACTION_MOVE, x, from + (to - from) * move / DRAG_MOVES);
        }
        long still = down + DRAG_MOVES * MOVE_MS + HOLD_MS;
        touch(list, down, still, MotionEvent.ACTION_MOVE, x, to);
        touch(list, down, still, MotionEvent.ACTION_UP, x, to);
    }

    private static void touch(View view, long down, long at, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, at, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            view.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    /** How far down [list] is scrolled, as its scroll bar sees it. */
    static int offset(View list) throws ReflectiveOperationException {
        Method found = scrollOffset;
        if (found == null) {
            found = View.class.getDeclaredMethod("computeVerticalScrollOffset");
            found.setAccessible(true);
            scrollOffset = found;
        }
        return (Integer) found.invoke(list);
    }

    /**
     * The same 12 dp action used in HushGram's dialogs, with a spoken update when the search starts.
     */
    static TextView button(Context context, Runnable tapped) {
        float density = context.getResources().getDisplayMetrics().density;
        TextView button = new Button(context);
        ScreenColors.styleAction(button, true);
        button.setElevation(6 * density);
        button.setFocusable(true);
        button.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        button.setOnClickListener(view -> tapped.run());
        return button;
    }

    /** How far above the bottom of [content] the button sits: BUTTON_ABOVE_DP past the navigation bar. */
    @SuppressWarnings("deprecation")
    static int buttonMargin(View content) {
        WindowInsets insets = content.getRootWindowInsets();
        int bar = insets == null ? 0 : insets.getSystemWindowInsetBottom();
        return Math.round(BUTTON_ABOVE_DP * content.getResources().getDisplayMetrics().density) + bar;
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

    private static boolean waitsForTap() {
        return Utils.settingsReady() && Settings.FIND_REEL_ORIGIN_ON_TAP.get();
    }
}
