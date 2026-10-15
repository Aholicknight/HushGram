/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The glass bar, its scroll hide and the Saved tab keep no timer or listener running once there's
 * nothing for it to do: with the switch off, while HushGram is paused, when the screen goes away and
 * once what it waited for has happened (#129). The glass bar's wait on Reels (#170) is held to the same.
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w1080dp-h1920dp")
public class TimerLifecycleTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int BAR_ID = 0x7f0b0101;
    private static final int REELS_ID = 0x7f0b0170;

    /** A list whose position the test sets. A group, like every list a scroll moves: a lone view is never taken for one. */
    static final class FakeList extends FrameLayout {
        boolean awayFromTop = true;

        FakeList(Context context) {
            super(context);
        }

        @Override public boolean canScrollVertically(int direction) {
            return direction < 0 ? awayFromTop : true;
        }
    }

    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private FakeList list;
    private View bar;
    private final AtomicBoolean running = new AtomicBoolean(true);
    /** How many times the scroll hide asked whether it may run: once a touch, and once each look of its watch. */
    private final AtomicInteger asked = new AtomicInteger();
    private GlassScrollHide hide;
    private long clock;

    @Before public void setUp() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        root = new FrameLayout(activity);
        list = new FakeList(activity);
        bar = new View(activity);
        root.addView(list, new FrameLayout.LayoutParams(1080, 1800));
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(1080, 150);
        barParams.topMargin = 1770;
        root.addView(bar, barParams);
        activity.setContentView(root, new FrameLayout.LayoutParams(1080, 1920));
        layout();
        hide = new GlassScrollHide(bar, () -> {
            asked.incrementAndGet();
            return running.get();
        });
        clock = SystemClock.uptimeMillis();
    }

    @After public void tearDown() {
        Map<String, Integer> ids = ReflectionHelpers.getStaticField(GlassTabBar.class, "IDS");
        synchronized (ids) {
            ids.remove(GlassTabBar.TAB_BAR);
            ids.remove(GlassTabBar.REELS_ROOT);
        }
        Settings.GLASS_TAB_BAR.resetToDefault();
        Settings.GLASS_TAB_BAR_REELS_HIDE.resetToDefault();
        Settings.SAVED_ON_PROFILE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        controller.close();
    }

    private void layout() {
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 1920);
    }

    private void touch(int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(clock, clock, action, x, y, 0);
        clock += 16;
        try {
            hide.onTouch(event);
        } finally {
            event.recycle();
        }
    }

    /** Scrolls the list down with a finger, so the bar slides away and its watch starts. */
    private void hideTheBar() {
        touch(MotionEvent.ACTION_DOWN, 500, 900);
        for (int i = 1; i <= 10; i++) touch(MotionEvent.ACTION_MOVE, 500, 900 - 30 * i);
        touch(MotionEvent.ACTION_UP, 500, 600);
        assertTrue(hide.isHidden());
        int before = asked.get();
        idle(1000);
        assertTrue("the watch looks at the list while the bar is away", asked.get() > before + 3);
    }

    /** Lets [millis] pass on the main thread and says how many times the scroll hide asked whether it may run. */
    private int askedOver(long millis) {
        int before = asked.get();
        idle(millis);
        return asked.get() - before;
    }

    private static void idle(long millis) {
        ShadowLooper.idleMainLooper(millis, TimeUnit.MILLISECONDS);
    }

    /** How many listeners of the kind [field] names the window's observer holds, from the observer's own list. */
    private int windowListeners(String field) {
        Object listeners = ReflectionHelpers.getField(activity.getWindow().getDecorView().getViewTreeObserver(), field);
        if (listeners == null) return 0;
        Integer size = ReflectionHelpers.callInstanceMethod(listeners, "size");
        return size;
    }

    @Test public void theScrollHideWatchStopsOnceTheListIsBackAtItsTop() {
        hideTheBar();
        list.awayFromTop = false;
        idle(GlassScrollHide.WATCH_MS * 2);
        assertFalse(hide.isHidden());
        assertEquals("no look after the bar came back", 0, askedOver(5000));
    }

    @Test public void theScrollHideWatchStopsWhenHushGramIsPaused() {
        hideTheBar();
        running.set(false);
        idle(GlassScrollHide.WATCH_MS * 2);
        assertFalse(hide.isHidden());
        assertEquals("no look while paused", 0, askedOver(5000));
    }

    @Test public void theScrollHideWatchStopsWhenTheBarLeavesTheWindow() {
        hideTheBar();
        root.removeView(bar);
        idle(GlassScrollHide.WATCH_MS * 2);
        assertFalse(hide.isHidden());
        assertEquals("no look at a bar that left", 0, askedOver(5000));
    }

    @Test public void theScrollHideWatchStopsWhenTheScreenGoesToTheBackground() {
        hideTheBar();
        // What the glass bar does when its activity pauses.
        hide.restoreNow();
        assertFalse(hide.isHidden());
        assertEquals("no look once restored", 0, askedOver(5000));
    }

    /**
     * The bar's frame listener, which also runs its 500 ms search for a missing view, is never put on
     * a window with the switch off or HushGram paused, and comes off with the bar.
     */
    @Test public void theGlassBarListensToNoFrameWhileOffOrPausedAndStopsWithTheBar() {
        Map<String, Integer> ids = ReflectionHelpers.getStaticField(GlassTabBar.class, "IDS");
        synchronized (ids) {
            ids.put(GlassTabBar.TAB_BAR, BAR_ID);
        }
        root.removeView(bar);
        LinearLayout tabs = new LinearLayout(activity);
        tabs.setId(BAR_ID);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1080, 150);
        params.topMargin = 1770;
        root.addView(tabs, params);
        layout();
        int frames = windowListeners("mOnPreDrawListeners");

        Settings.GLASS_TAB_BAR.resetToDefault();
        assertFalse(Settings.GLASS_TAB_BAR.get());
        GlassTabBar.apply(activity);
        assertEquals("off", frames, windowListeners("mOnPreDrawListeners"));

        Settings.GLASS_TAB_BAR.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        GlassTabBar.apply(activity);
        assertEquals("paused", frames, windowListeners("mOnPreDrawListeners"));

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        GlassTabBar.apply(activity);
        assertEquals("on, the bar listens to the window's frames", frames + 1, windowListeners("mOnPreDrawListeners"));

        root.removeView(tabs);
        assertEquals("and stops when the bar leaves the window", frames, windowListeners("mOnPreDrawListeners"));
    }

    /** Puts a tab bar Instagram's id names where the plain bar was, for the glass to restyle. */
    private LinearLayout namedTabBar() {
        Map<String, Integer> ids = ReflectionHelpers.getStaticField(GlassTabBar.class, "IDS");
        synchronized (ids) {
            ids.put(GlassTabBar.TAB_BAR, BAR_ID);
        }
        root.removeView(bar);
        LinearLayout tabs = new LinearLayout(activity);
        tabs.setId(BAR_ID);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1080, 150);
        params.topMargin = 1770;
        root.addView(tabs, params);
        layout();
        return tabs;
    }

    /** Puts a Reels viewer over the list, down to the bar's top. */
    private void showReels() {
        Map<String, Integer> ids = ReflectionHelpers.getStaticField(GlassTabBar.class, "IDS");
        synchronized (ids) {
            ids.put(GlassTabBar.REELS_ROOT, REELS_ID);
        }
        FrameLayout viewer = new FrameLayout(activity);
        viewer.setId(REELS_ID);
        root.addView(viewer, 1, new FrameLayout.LayoutParams(1080, 1770));
        layout();
    }

    /**
     * With a time picked for Reels and the scroll switch off, the glass bar reads the window's touches
     * and slides away once a reel has gone that long untouched. Its screen going to the background puts it
     * back with nothing left waiting, and coming to the front starts the wait over, since a reel that plays
     * on untouched would never start another.
     */
    @Test public void theGlassBarsWaitOnReelsEndsWithItsScreenAndStartsOverWhenItComesBack() {
        LinearLayout tabs = namedTabBar();
        showReels();
        Window.Callback before = activity.getWindow().getCallback();
        Settings.GLASS_TAB_BAR.save(true);
        assertFalse(Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.get());
        Settings.GLASS_TAB_BAR_REELS_HIDE.save(GlassReelsHide.TWO_SECONDS);
        GlassTabBar.apply(activity);
        assertNotSame("the wait needs the window's touches", before, activity.getWindow().getCallback());
        idle(1900);
        assertEquals("not before the wait is up", 0f, tabs.getTranslationY(), 0f);
        idle(600);
        assertTrue("the bar slid away", tabs.getTranslationY() > 50f);

        // What the glass bar does when its activity pauses.
        GlassTabBar.restoreAll();
        assertEquals(0f, tabs.getTranslationY(), 0f);
        idle(10_000);
        assertEquals("nothing waits in the background", 0f, tabs.getTranslationY(), 0f);

        // And what it does when the activity resumes with the bar it already restyled.
        GlassTabBar.apply(activity);
        idle(2500);
        assertTrue("the wait started over", tabs.getTranslationY() > 50f);
        GlassTabBar.restoreAll();
        assertEquals(0f, tabs.getTranslationY(), 0f);
    }

    /** With the scroll switch off and the wait on Never, the glass bar reads no touches and waits for nothing. */
    @Test public void theGlassBarReadsNoTouchesWithNoHidePicked() {
        LinearLayout tabs = namedTabBar();
        showReels();
        Window.Callback before = activity.getWindow().getCallback();
        Settings.GLASS_TAB_BAR.save(true);
        assertFalse(Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.get());
        assertEquals(GlassReelsHide.NEVER, Settings.GLASS_TAB_BAR_REELS_HIDE.get());
        GlassTabBar.apply(activity);
        assertSame("no watcher over the window's callback", before, activity.getWindow().getCallback());
        idle(10_000);
        assertEquals(0f, tabs.getTranslationY(), 0f);
    }

    /** The Saved tab puts no layout listener on a screen with the switch off or HushGram paused. */
    @Test public void theSavedTabWatchesNoScreenWhileOffOrPaused() {
        int layouts = windowListeners("mOnGlobalLayoutListeners");

        Settings.SAVED_ON_PROFILE.resetToDefault();
        assertFalse(Settings.SAVED_ON_PROFILE.get());
        ProfileSaved.watch(activity);
        assertEquals("off", layouts, windowListeners("mOnGlobalLayoutListeners"));

        Settings.SAVED_ON_PROFILE.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ProfileSaved.watch(activity);
        assertEquals("paused", layouts, windowListeners("mOnGlobalLayoutListeners"));

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        ProfileSaved.watch(activity);
        assertEquals("on, the screen is watched", layouts + 1, windowListeners("mOnGlobalLayoutListeners"));
        ProfileSaved.watch(activity);
        assertEquals("once", layouts + 1, windowListeners("mOnGlobalLayoutListeners"));
    }
}
