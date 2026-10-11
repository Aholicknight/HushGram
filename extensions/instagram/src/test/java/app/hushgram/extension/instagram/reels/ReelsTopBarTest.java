/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import com.instagram.clips.intf.ClipsViewerSource;

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
import java.util.ArrayList;
import java.util.List;

import app.hushgram.extension.instagram.settings.PatchFamily;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;
import instagram.features.clips.viewer.actionbar.ClipsViewerActionBar;

/**
 * Hide the top bar on the Reels tab (#14): the bar of the Reels tab's own viewer goes invisible,
 * keeping its place so a tap there reaches the reel, while a reel opened from anywhere else keeps
 * its bar and camera, and no frame or layout listener runs while the switch is off, while HushGram
 * is paused, or once the screen goes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelsTopBarTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int BAR_ID = 7201, CAMERA_ID = 7202;
    private static final int BAR_HEIGHT = 100, CAMERA_SIZE = 50;

    private int W, H;
    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private View reel;
    private final List<String> touched = new ArrayList<>();
    private int baseFrames, baseLayouts, baseFocus;

    @Before public void prepare() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_REELS_TOP_BAR.resetToDefault();
        HookStatus.clear();
        ReelsTopBar.idForTests(ReelsTopBar.BAR_ID, BAR_ID);
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        W = activity.getWindow().getDecorView().getWidth();
        H = activity.getWindow().getDecorView().getHeight();
        assertTrue("the test window has a size", W > 0 && H > 0);
        root = new FrameLayout(activity);
        activity.setContentView(root);
        reel = new View(activity);
        reel.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) touched.add("reel");
            return true;
        });
        root.addView(reel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layout();
        baseFrames = listeners("mOnPreDrawListeners");
        baseLayouts = listeners("mOnGlobalLayoutListeners");
        baseFocus = listeners("mOnWindowFocusListeners");
    }

    @After public void close() {
        ReelsTopBar.forget(activity);
        controller.pause().stop().destroy();
        Settings.HIDE_REELS_TOP_BAR.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** A bar like the Reels viewer's, its camera button in the corner, laid over the reel. */
    private ClipsViewerActionBar bar(ClipsViewerSource source) {
        ClipsViewerActionBar bar = new ClipsViewerActionBar(activity);
        bar.setId(BAR_ID);
        if (source != null) bar.bind(source);
        View camera = new View(activity);
        camera.setId(CAMERA_ID);
        camera.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) touched.add("camera");
            return true;
        });
        bar.addView(camera, new RelativeLayout.LayoutParams(CAMERA_SIZE, CAMERA_SIZE));
        return bar;
    }

    private ClipsViewerActionBar show(ClipsViewerSource source) {
        ClipsViewerActionBar bar = bar(source);
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BAR_HEIGHT));
        layout();
        return bar;
    }

    private void layout() {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
    }

    private ViewTreeObserver observer() {
        return activity.getWindow().getDecorView().getViewTreeObserver();
    }

    /** A frame of the window, as far as its pre-draw listeners can tell. */
    private void frame() {
        observer().dispatchOnPreDraw();
    }

    /** A layout pass of the window, as far as its layout listeners can tell. */
    private void laidOut() {
        layout();
        observer().dispatchOnGlobalLayout();
    }

    /** The window getting focus back, as when the settings dialog over it closes. */
    private void focusBack() {
        ReflectionHelpers.callInstanceMethod(observer(), "dispatchOnWindowFocusChange",
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
    }

    private void idle(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    /** How many listeners of a kind the window's observer holds, from its own list. */
    private int listeners(String field) {
        Object list = ReflectionHelpers.getField(observer(), field);
        if (list == null) return 0;
        Integer size = ReflectionHelpers.callInstanceMethod(list, "size");
        return size;
    }

    private void assertWatching(String why, boolean watching) {
        assertEquals(why + ": frame listeners", baseFrames + (watching ? 1 : 0), listeners("mOnPreDrawListeners"));
        assertEquals(why + ": layout listeners", baseLayouts + (watching ? 1 : 0), listeners("mOnGlobalLayoutListeners"));
    }

    private ReelsTopBar.Session track() {
        ReelsTopBar.track(activity);
        ReelsTopBar.Session session = ReelsTopBar.sessionFor(activity);
        assertNotNull(session);
        return session;
    }

    private String tapTopLeft() {
        touched.clear();
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, CAMERA_SIZE / 2f, CAMERA_SIZE / 2f, 0);
        root.dispatchTouchEvent(down);
        down.recycle();
        return touched.isEmpty() ? "nothing" : touched.get(0);
    }

    private static boolean counted(String count, int times) {
        return HookStatus.report().toString().contains(count + " " + times);
    }

    @Test public void offIsStockAndWatchesNoFrames() {
        assertFalse("starts off", Settings.HIDE_REELS_TOP_BAR.get());
        ClipsViewerActionBar bar = show(ClipsViewerSource.CLIPS_TAB);
        ReelsTopBar.Session session = track();
        frame();
        laidOut();
        frame();
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertTrue(bar.findViewById(CAMERA_ID).isShown());
        assertEquals("camera", tapTopLeft());
        assertFalse(session.isWatching());
        assertWatching("off", false);
        assertEquals("off, only the focus listener that hears the switch turn on", baseFocus + 1, listeners("mOnWindowFocusListeners"));
        assertEquals("never searched", 0, session.looks);
        assertEquals(List.of(), HookStatus.report());
    }

    @Test public void onHidesTheReelsTabBarAndItsCameraAndKeepsItsPlace() {
        ClipsViewerActionBar bar = show(ClipsViewerSource.CLIPS_TAB);
        int top = bar.getTop(), height = bar.getHeight(), width = bar.getWidth();
        int reelTop = reel.getTop(), reelHeight = reel.getHeight();
        Settings.HIDE_REELS_TOP_BAR.save(true);
        ReelsTopBar.Session session = track();
        assertEquals("invisible, not gone", View.INVISIBLE, bar.getVisibility());
        assertFalse("the camera button isn't shown", bar.findViewById(CAMERA_ID).isShown());
        layout();
        assertEquals("the bar keeps its place", top, bar.getTop());
        assertEquals(height, bar.getHeight());
        assertEquals(width, bar.getWidth());
        assertEquals("the reel isn't laid out again", reelTop, reel.getTop());
        assertEquals(reelHeight, reel.getHeight());
        assertTrue(session.isWatching());
        assertWatching("on", true);
        assertEquals(1, session.hiddenCount());
        assertTrue(HookStatus.report().toString(), counted(ReelsTopBar.COUNT_HID, 1));
    }

    @Test public void aTapWhereTheBarWasReachesTheReel() {
        show(ClipsViewerSource.CLIPS_TAB);
        assertEquals("with the bar, the camera button takes the tap", "camera", tapTopLeft());
        Settings.HIDE_REELS_TOP_BAR.save(true);
        track();
        assertEquals("the reel under the hidden bar takes it", "reel", tapTopLeft());
    }

    @Test public void theHomecomingReelsTabCountsToo() {
        ClipsViewerActionBar bar = show(ClipsViewerSource.CLIPS_TAB_HOMECOMING);
        Settings.HIDE_REELS_TOP_BAR.save(true);
        track();
        assertEquals(View.INVISIBLE, bar.getVisibility());
    }

    @Test public void theCameraGoesOnlyOnTheReelsTab() {
        Settings.HIDE_REELS_TOP_BAR.save(true);
        ClipsViewerSource[] elsewhere = {ClipsViewerSource.PROFILE_CLIPS, ClipsViewerSource.FEED_TIMELINE,
                ClipsViewerSource.DIRECT_THREAD, ClipsViewerSource.CLIPS_FRIEND_LANE};
        List<ClipsViewerActionBar> kept = new ArrayList<>();
        for (ClipsViewerSource source : elsewhere) kept.add(show(source));
        ClipsViewerActionBar mixed = show(null);
        mixed.A01 = new ClipsViewerActionBar.Config(ClipsViewerSource.CLIPS_TAB);
        mixed.A02 = new ClipsViewerActionBar.State(ClipsViewerSource.PROFILE_CLIPS);
        kept.add(mixed);
        ClipsViewerActionBar tab = show(ClipsViewerSource.CLIPS_TAB);
        ReelsTopBar.Session session = track();
        for (int i = 0; i < kept.size(); i++) {
            ClipsViewerActionBar bar = kept.get(i);
            assertEquals("bar " + i + " keeps showing", View.VISIBLE, bar.getVisibility());
            assertTrue("bar " + i + " keeps its camera", bar.findViewById(CAMERA_ID).isShown());
        }
        assertEquals(View.INVISIBLE, tab.getVisibility());
        assertFalse(tab.findViewById(CAMERA_ID).isShown());
        assertEquals(1, session.hiddenCount());
    }

    @Test public void pausedIsStockWithNoListener() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        Settings.HIDE_REELS_TOP_BAR.save(true);
        ClipsViewerActionBar bar = show(ClipsViewerSource.CLIPS_TAB);
        ReelsTopBar.track(activity);
        frame();
        assertNull("paused, the window isn't watched at all", ReelsTopBar.sessionFor(activity));
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertWatching("paused", false);
        assertEquals("paused, no focus listener either", baseFocus, listeners("mOnWindowFocusListeners"));
        assertEquals(List.of(), HookStatus.report());
    }

    @Test public void aResumeWhilePausedPutsTheBarBackAndStopsWatching() {
        Settings.HIDE_REELS_TOP_BAR.save(true);
        ClipsViewerActionBar bar = show(ClipsViewerSource.CLIPS_TAB);
        track();
        assertEquals(View.INVISIBLE, bar.getVisibility());
        ReelsTopBar.release(activity);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ReelsTopBar.track(activity);
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertWatching("paused", false);
        assertEquals(baseFocus, listeners("mOnWindowFocusListeners"));
    }

    @Test public void withNoBarNothingHidesAndNothingThrows() {
        Settings.HIDE_REELS_TOP_BAR.save(true);
        ReelsTopBar.Session session = track();
        laidOut();
        idle(ReelsTopBar.LOOK_MS * 2);
        frame();
        assertTrue("searched", session.looks >= 1);
        assertEquals(0, session.hiddenCount());
        assertEquals(View.VISIBLE, reel.getVisibility());
        assertFalse(HookStatus.report().toString(), HookStatus.report().toString().contains("threw"));
        assertFalse(counted(ReelsTopBar.COUNT_HID, 1));
    }

    @Test public void aBarNotBoundYetIsHiddenOnceItIs() {
        Settings.HIDE_REELS_TOP_BAR.save(true);
        ClipsViewerActionBar bar = show(null);
        ReelsTopBar.Session session = track();
        assertEquals("its viewer isn't known yet, so it's left alone", View.VISIBLE, bar.getVisibility());
        bar.bind(ClipsViewerSource.CLIPS_TAB);
        idle(ReelsTopBar.LOOK_MS * 2);
        frame();
        idle(ReelsTopBar.LOOK_MS * 2);
        assertEquals("bound with no layout, it's still found", View.INVISIBLE, bar.getVisibility());
        assertEquals(1, session.hiddenCount());
    }

    @Test public void aBarThatNeverBindsStopsBeingLookedFor() {
        Settings.HIDE_REELS_TOP_BAR.save(true);
        ClipsViewerActionBar bar = show(null);
        ReelsTopBar.Session session = track();
        for (int i = 0; i < ReelsTopBar.UNKNOWN_TRIES + 5; i++) {
            idle(ReelsTopBar.LOOK_MS * 2);
            frame();
        }
        idle(ReelsTopBar.LOOK_MS * 2);
        int looks = session.looks;
        assertTrue("a few more searches, not one a frame forever: " + looks, looks <= ReelsTopBar.UNKNOWN_TRIES + 2);
        for (int i = 0; i < 5; i++) {
            idle(ReelsTopBar.LOOK_MS * 2);
            frame();
        }
        assertEquals("no more searches without a layout", looks, session.looks);
        assertEquals(View.VISIBLE, bar.getVisibility());
    }

    @Test public void aWrapperWearingTheBarsIdIsSearchedForTheBarInside() {
        Settings.HIDE_REELS_TOP_BAR.save(true);
        FrameLayout wrapper = new FrameLayout(activity);
        wrapper.setId(BAR_ID);
        ClipsViewerActionBar bar = bar(ClipsViewerSource.CLIPS_TAB);
        bar.setId(View.NO_ID);
        wrapper.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BAR_HEIGHT));
        root.addView(wrapper, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BAR_HEIGHT));
        layout();
        track();
        assertEquals("the wrapper holds no source and stays", View.VISIBLE, wrapper.getVisibility());
        assertEquals(View.INVISIBLE, bar.getVisibility());
    }

    @Test public void aBarInstagramShowsAgainIsHiddenBeforeTheNextFrame() {
        Settings.HIDE_REELS_TOP_BAR.save(true);
        ClipsViewerActionBar bar = show(ClipsViewerSource.CLIPS_TAB);
        track();
        bar.setVisibility(View.VISIBLE);
        frame();
        assertEquals(View.INVISIBLE, bar.getVisibility());
        assertTrue(HookStatus.report().toString(), counted(ReelsTopBar.COUNT_HID_AGAIN, 1));
        bar.setVisibility(View.GONE);
        frame();
        assertEquals("Instagram's own hide stays", View.GONE, bar.getVisibility());
    }

    @Test public void turningTheSwitchOffPutsTheBarBackAndStopsTheListenersAtTheNextFrame() {
        Settings.HIDE_REELS_TOP_BAR.save(true);
        ClipsViewerActionBar bar = show(ClipsViewerSource.CLIPS_TAB);
        ReelsTopBar.Session session = track();
        assertWatching("on", true);
        Settings.HIDE_REELS_TOP_BAR.save(false);
        frame();
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertTrue(bar.findViewById(CAMERA_ID).isShown());
        assertFalse(session.isWatching());
        assertFalse(session.lookQueued());
        assertWatching("off", false);
        assertTrue(HookStatus.report().toString(), counted(ReelsTopBar.COUNT_PUT_BACK, 1));
        int looks = session.looks;
        laidOut();
        idle(ReelsTopBar.LOOK_MS * 2);
        frame();
        assertEquals("off, no more searches", looks, session.looks);
        assertEquals(0, session.hiddenCount());
    }

    @Test public void aBarInstagramHidItselfIsLeftHiddenWhenTheSwitchGoesOff() {
        Settings.HIDE_REELS_TOP_BAR.save(true);
        ClipsViewerActionBar bar = show(ClipsViewerSource.CLIPS_TAB);
        track();
        bar.setVisibility(View.GONE);
        Settings.HIDE_REELS_TOP_BAR.save(false);
        frame();
        assertEquals(View.GONE, bar.getVisibility());
    }

    @Test public void turningTheSwitchOnIsSeenWhenTheSettingsDialogCloses() {
        ClipsViewerActionBar bar = show(ClipsViewerSource.CLIPS_TAB);
        ReelsTopBar.Session session = track();
        assertWatching("off", false);
        Settings.HIDE_REELS_TOP_BAR.save(true);
        focusBack();
        assertTrue(session.isWatching());
        assertWatching("on", true);
        assertEquals(View.INVISIBLE, bar.getVisibility());
        focusBack();
        assertWatching("listeners added once", true);
    }

    @Test public void aPausedActivityStopsListeningAndAGoneOneIsForgotten() {
        Settings.HIDE_REELS_TOP_BAR.save(true);
        ClipsViewerActionBar bar = show(ClipsViewerSource.CLIPS_TAB);
        ReelsTopBar.Session session = track();
        ReelsTopBar.release(activity);
        assertWatching("paused activity", false);
        assertEquals("paused activity", baseFocus, listeners("mOnWindowFocusListeners"));
        assertFalse(session.lookQueued());
        assertEquals("no flash as the screen goes", View.INVISIBLE, bar.getVisibility());
        int looks = session.looks;
        laidOut();
        frame();
        idle(ReelsTopBar.LOOK_MS * 2);
        assertEquals(looks, session.looks);

        Settings.HIDE_REELS_TOP_BAR.save(false);
        assertEquals("the same session again", session, track());
        assertEquals("resumed with the switch off, the bar is back", View.VISIBLE, bar.getVisibility());
        assertWatching("resumed off", false);
        assertEquals(baseFocus + 1, listeners("mOnWindowFocusListeners"));

        ReelsTopBar.forget(activity);
        assertNull(ReelsTopBar.sessionFor(activity));
        assertWatching("gone", false);
        assertEquals("gone", baseFocus, listeners("mOnWindowFocusListeners"));
    }

    @Test public void aBarAddedLaterIsFoundAfterTheLayoutThatBringsIt() {
        Settings.HIDE_REELS_TOP_BAR.save(true);
        ReelsTopBar.Session session = track();
        ClipsViewerActionBar bar = bar(ClipsViewerSource.CLIPS_TAB);
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BAR_HEIGHT));
        laidOut();
        frame();
        if (bar.getVisibility() == View.VISIBLE) {
            assertTrue("a search waits out the throttle", session.lookQueued());
            idle(ReelsTopBar.LOOK_MS);
        }
        assertEquals(View.INVISIBLE, bar.getVisibility());
        assertEquals(1, session.hiddenCount());
    }

    @Test public void framesWithoutALayoutDontSearch() {
        Settings.HIDE_REELS_TOP_BAR.save(true);
        show(ClipsViewerSource.CLIPS_TAB);
        ReelsTopBar.Session session = track();
        idle(ReelsTopBar.LOOK_MS * 2);
        int looks = session.looks;
        for (int i = 0; i < 10; i++) frame();
        idle(ReelsTopBar.LOOK_MS * 2);
        frame();
        assertEquals(looks, session.looks);
        assertFalse(session.lookQueued());
    }

    @Test public void theSwitchStartsOffAndIsInTheCleanUpReelsFamily() {
        assertFalse(Settings.HIDE_REELS_TOP_BAR.defaultValue);
        assertTrue(PatchFamily.REEL_DECLUTTER.switches.contains(Settings.HIDE_REELS_TOP_BAR));
    }
}
