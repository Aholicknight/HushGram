/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Burn-in guard (#162): on the Reels screen a reel's overlay, the top bar and the tab bar dim after
 * five seconds without a touch, a touch brings them back, and the shift moves the content a step
 * every two minutes. Off, Pause, another screen and the activity leaving the front put everything
 * back the way Instagram had it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class BurnInGuardTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int VIEWER = 7201, BAR = 7202, REELS_TAB = 7203, HOME_TAB = 7204;
    private static final int OVERLAY = 7301, UFI = 7302, TOP_BAR = 7303;

    private int W, H;
    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private FrameLayout viewer;
    private View video;
    private FrameLayout overlay;
    private View ufi;
    private View topBar;
    private LinearLayout bar;
    private View reelsTab, homeTab;

    @Before public void prepare() {
        Settings.BURN_IN_GUARD.resetToDefault();
        PauseForTests.resume();
        BurnInGuard.resetForTests();
        FullScreenBars.idForTests(FullScreenBars.VIEWER, VIEWER);
        FullScreenBars.idForTests(FullScreenBars.TAB_BAR, BAR);
        FullScreenBars.idForTests(FullScreenBars.REELS_TAB, REELS_TAB);
        FullScreenBars.idForTests(FullScreenBars.HOME_TAB, HOME_TAB);
        for (String name : BurnInGuard.DIMMED_IDS) BurnInGuard.idForTests(name, 0);
        BurnInGuard.idForTests("clips_item_overlay_component", OVERLAY);
        BurnInGuard.idForTests("clips_ufi_component", UFI);
        BurnInGuard.idForTests("clips_viewer_action_bar", TOP_BAR);
        BurnInGuard.idForTests("tab_bar", BAR);
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        W = activity.getWindow().getDecorView().getWidth();
        H = activity.getWindow().getDecorView().getHeight();
        assertTrue("the test window has a size", W > 0 && H > 0);

        root = new FrameLayout(activity);
        activity.setContentView(root);
        viewer = new FrameLayout(activity);
        viewer.setId(VIEWER);
        video = new View(activity);
        viewer.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = new FrameLayout(activity);
        overlay.setId(OVERLAY);
        ufi = new View(activity);
        ufi.setId(UFI);
        overlay.addView(ufi, new FrameLayout.LayoutParams(60, 300));
        viewer.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        topBar = new View(activity);
        topBar.setId(TOP_BAR);
        viewer.addView(topBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 60));
        root.addView(viewer, new FrameLayout.LayoutParams(W, H));
        bar = new LinearLayout(activity);
        bar.setId(BAR);
        reelsTab = tab(REELS_TAB);
        homeTab = tab(HOME_TAB);
        reelsTab.setSelected(true);
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 50));
        layout();
    }

    @After public void close() {
        BurnInGuard.resetForTests();
        controller.close();
        Settings.BURN_IN_GUARD.resetToDefault();
        PauseForTests.resume();
    }

    private View tab(int id) {
        View tab = new View(activity);
        tab.setId(id);
        bar.addView(tab, new LinearLayout.LayoutParams(10, 40));
        return tab;
    }

    private void layout() {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
    }

    /** Lets [millis] go by, running whatever the main thread had due in them. */
    private static void after(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    /**
     * A finger event the way the system delivers it: to the decor view, which hands it to the window's
     * callback. It lands in the middle of the screen's content, clear of the action bar a plain test
     * activity has, which on API 35 and newer is drawn over the top of the content.
     */
    private void send(int action) {
        int[] at = new int[2];
        root.getLocationInWindow(at);
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, at[0] + root.getWidth() / 2f, at[1] + root.getHeight() / 2f, 0);
        try {
            activity.getWindow().getDecorView().dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private void tap() {
        send(MotionEvent.ACTION_DOWN);
        send(MotionEvent.ACTION_UP);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void focusBack() {
        ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
        ReflectionHelpers.callInstanceMethod(observer, "dispatchOnWindowFocusChange",
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void relayout() {
        layout();
        activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnGlobalLayout();
        after(BurnInGuard.LOOK_MS + 10);
    }

    private void assertDimmed(String why) {
        assertEquals(why + ": the overlay", BurnInGuard.IDLE_LEVEL, overlay.getAlpha(), 0f);
        assertEquals(why + ": the top bar", BurnInGuard.IDLE_LEVEL, topBar.getAlpha(), 0f);
        assertEquals(why + ": the tab bar", BurnInGuard.IDLE_LEVEL, bar.getAlpha(), 0f);
        assertEquals(why + ": inside the overlay it isn't dimmed twice", 1f, ufi.getAlpha(), 0f);
        assertEquals(why + ": the reel itself", 1f, video.getAlpha(), 0f);
    }

    private void assertUp(String why) {
        assertEquals(why + ": the overlay", 1f, overlay.getAlpha(), 0f);
        assertEquals(why + ": the top bar", 1f, topBar.getAlpha(), 0f);
        assertEquals(why + ": the tab bar", 1f, bar.getAlpha(), 0f);
    }

    @Test public void offToStartAndNothingDimsOrListens() {
        assertEquals(BurnInMode.OFF, Settings.BURN_IN_GUARD.get());
        Object before = activity.getWindow().getCallback();
        BurnInGuard.resumed(activity);
        after(BurnInGuard.IDLE_AFTER_MS * 3);
        assertUp("off");
        assertFalse("no layout listener while off", BurnInGuard.watchingLayoutsForTests());
        assertSame("no touch watcher while off", before, activity.getWindow().getCallback());
        assertEquals(-1L, BurnInGuard.nextPassAtForTests());
        assertEquals(0f, root.getTranslationX(), 0f);
    }

    @Test public void theControlsDimAfterFiveSecondsWithoutATouch() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        assertTrue(BurnInGuard.watchingLayoutsForTests());
        after(BurnInGuard.IDLE_AFTER_MS - 100);
        assertUp("before five seconds");
        after(200);
        assertDimmed("after five seconds");
        assertEquals("Dim alone never moves the screen", 0f, root.getTranslationX(), 0f);
        assertEquals(0f, root.getTranslationY(), 0f);
    }

    @Test public void aTouchBringsThemBackAndAHeldFingerKeepsThemUpUntilItLifts() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(BurnInGuard.IDLE_AFTER_MS + 100);
        assertDimmed("idle");

        send(MotionEvent.ACTION_DOWN);
        assertUp("the finger went down");
        after(BurnInGuard.IDLE_AFTER_MS * 3);
        assertUp("a held finger is a touch");

        send(MotionEvent.ACTION_UP);
        after(BurnInGuard.IDLE_AFTER_MS - 100);
        assertUp("the wait starts at the lift");
        after(200);
        assertDimmed("five seconds after the lift");
    }

    @Test public void instagramsOwnAlphaIsWhatTheDimIsTakenFromAndAChangeItMakesIsKept() {
        overlay.setAlpha(0.8f);
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(BurnInGuard.IDLE_AFTER_MS + 100);
        assertEquals(0.8f * BurnInGuard.IDLE_LEVEL, overlay.getAlpha(), 0f);
        tap();
        assertEquals("back to Instagram's own", 0.8f, overlay.getAlpha(), 0f);

        after(BurnInGuard.IDLE_AFTER_MS + 100);
        overlay.setAlpha(0.5f);
        tap();
        assertEquals("Instagram's change while dimmed stays", 0.5f, overlay.getAlpha(), 0f);
    }

    @Test public void onlyTheReelsScreenDimsAndLeavingItBringsTheControlsBack() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(BurnInGuard.IDLE_AFTER_MS + 100);
        assertDimmed("on Reels");

        // Home: the viewer goes and Home's tab is selected, with no touch reaching this window.
        root.removeView(viewer);
        reelsTab.setSelected(false);
        homeTab.setSelected(true);
        relayout();
        assertEquals("the tab bar comes back off Reels", 1f, bar.getAlpha(), 0f);
        assertEquals(1f, overlay.getAlpha(), 0f);
        after(BurnInGuard.IDLE_AFTER_MS * 3);
        assertEquals("Home never dims", 1f, bar.getAlpha(), 0f);
    }

    @Test public void dimAndShiftMovesTheScreenAStepEveryTwoMinutesAndPutsItBack() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM_AND_SHIFT);
        float step = BurnInGuard.STEP_DP * activity.getResources().getDisplayMetrics().density;
        // Start right at a place's first moment, so the next one is two minutes away.
        long now = SystemClock.uptimeMillis();
        after(BurnInGuard.SHIFT_EVERY_MS - now % BurnInGuard.SHIFT_EVERY_MS);
        BurnInGuard.resumed(activity);
        View content = activity.findViewById(android.R.id.content);
        int[] at = BurnInGuard.placeAt(SystemClock.uptimeMillis());
        assertEquals(at[0] * step, content.getTranslationX(), 0.01f);
        assertEquals(at[1] * step, content.getTranslationY(), 0.01f);

        after(BurnInGuard.SHIFT_EVERY_MS);
        int[] next = BurnInGuard.placeAt(SystemClock.uptimeMillis());
        assertTrue("a different place", next[0] != at[0] || next[1] != at[1]);
        assertEquals(next[0] * step, content.getTranslationX(), 0.01f);
        assertEquals(next[1] * step, content.getTranslationY(), 0.01f);
        assertDimmed("two minutes untouched");

        BurnInGuard.paused(activity);
        assertEquals("leaving the front puts it back", 0f, content.getTranslationX(), 0f);
        assertEquals(0f, content.getTranslationY(), 0f);
        assertUp("leaving the front");
        assertFalse(BurnInGuard.watchingLayoutsForTests());
        assertEquals(-1L, BurnInGuard.nextPassAtForTests());
    }

    @Test public void theShiftKeepsATranslationSomethingElseWrote() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM_AND_SHIFT);
        View content = activity.findViewById(android.R.id.content);
        BurnInGuard.resumed(activity);
        content.setTranslationY(40f);
        BurnInGuard.paused(activity);
        assertEquals("not HushGram's to put back", 40f, content.getTranslationY(), 0f);
    }

    @Test public void pauseHushGramPutsEverythingBack() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM_AND_SHIFT);
        BurnInGuard.resumed(activity);
        after(BurnInGuard.SHIFT_EVERY_MS);
        assertDimmed("idle");

        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        focusBack();
        assertUp("paused");
        View content = activity.findViewById(android.R.id.content);
        assertEquals(0f, content.getTranslationX(), 0f);
        assertEquals(0f, content.getTranslationY(), 0f);
        assertFalse(BurnInGuard.watchingLayoutsForTests());
        after(BurnInGuard.SHIFT_EVERY_MS);
        assertUp("still paused");
    }

    @Test public void turningItOnInTheSettingsIsSeenWhenTheWindowGetsFocusBack() {
        BurnInGuard.resumed(activity);
        assertFalse(BurnInGuard.watchingLayoutsForTests());
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        focusBack();
        assertTrue(BurnInGuard.watchingLayoutsForTests());
        assertNotSame("touches are watched now", activity, activity.getWindow().getCallback());
        after(BurnInGuard.IDLE_AFTER_MS + 100);
        assertDimmed("on after the settings closed");

        Settings.BURN_IN_GUARD.save(BurnInMode.OFF);
        focusBack();
        assertUp("off again");
        assertFalse(BurnInGuard.watchingLayoutsForTests());
    }

    /**
     * Turned off in the settings, the focus coming back still asks for a pass, and a layout before
     * that pass runs (the settings screen closing brings one) doesn't put it off. Then the watcher
     * stays on the window, but a finger no longer asks for a pass.
     */
    @Test public void aTouchWithTheGuardOffAsksForNothing() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
        observer.dispatchOnGlobalLayout();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(BurnInGuard.watchingLayoutsForTests());

        Settings.BURN_IN_GUARD.save(BurnInMode.OFF);
        ReflectionHelpers.callInstanceMethod(observer, "dispatchOnWindowFocusChange",
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
        observer.dispatchOnGlobalLayout();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse("a layout put the focus pass off", BurnInGuard.watchingLayoutsForTests());
        assertTrue(Shadows.shadowOf(Looper.getMainLooper()).isIdle());

        // Not tap(), which runs what the main thread has due.
        send(MotionEvent.ACTION_DOWN);
        send(MotionEvent.ACTION_UP);
        assertTrue("no pass posted for a lift", Shadows.shadowOf(Looper.getMainLooper()).isIdle());
        assertEquals(-1L, BurnInGuard.nextPassAtForTests());
    }

    @Test public void theWatcherPassesTouchesOnToTheActivity() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        int[] seen = new int[1];
        root.setOnTouchListener((view, event) -> {
            seen[0]++;
            return true;
        });
        BurnInGuard.resumed(activity);
        tap();
        assertEquals("the down and the up both reached the screen", 2, seen[0]);
    }
}
