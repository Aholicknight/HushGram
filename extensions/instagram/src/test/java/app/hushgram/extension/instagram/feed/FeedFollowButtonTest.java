/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewStub;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hide Follow on feed posts: the buttons beside the author go, the name, time and menu stay. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class FeedFollowButtonTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Activity activity;
    private LinearLayout screen;
    private LinearLayout header;
    private TextView name;
    private TextView time;
    private View menu;
    private View inline;
    private View inHeader;
    private View subscribe;
    private ViewStub stub;
    private View storyFollow;

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        FeedFollowButton.hider().resetForTests();
        int headerId = View.generateViewId();
        int inlineId = View.generateViewId();
        int inHeaderId = View.generateViewId();
        int subscribeId = View.generateViewId();
        FeedFollowButton.hider().idForTests(activity, FeedFollowButton.HEADER, headerId);
        FeedFollowButton.hider().idForTests(activity, FeedFollowButton.INLINE_FOLLOW, inlineId);
        FeedFollowButton.hider().idForTests(activity, FeedFollowButton.HEADER_FOLLOW, inHeaderId);
        FeedFollowButton.hider().idForTests(activity, FeedFollowButton.SUBSCRIBE, subscribeId);

        screen = new LinearLayout(activity);
        screen.setOrientation(LinearLayout.VERTICAL);
        header = new LinearLayout(activity);
        header.setId(headerId);
        name = new TextView(activity);
        time = new TextView(activity);
        menu = new View(activity);
        inline = new View(activity);
        inline.setId(inlineId);
        inHeader = new View(activity);
        inHeader.setId(inHeaderId);
        subscribe = new View(activity);
        subscribe.setId(subscribeId);
        stub = new ViewStub(activity);
        stub.setId(View.generateViewId());
        header.addView(name);
        header.addView(time);
        header.addView(inline);
        header.addView(inHeader);
        header.addView(subscribe);
        header.addView(stub);
        header.addView(menu);
        storyFollow = new View(activity);
        storyFollow.setId(inlineId);
        screen.addView(header);
        screen.addView(storyFollow);
        FrameLayout root = new FrameLayout(activity);
        root.addView(screen);
        activity.setContentView(root);
        shadowOf(Looper.getMainLooper()).idle();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_FEED_FOLLOW.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        FeedFollowButton.hider().resetForTests();
        Settings.HIDE_FEED_FOLLOW.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private boolean frame() {
        return screen.getViewTreeObserver().dispatchOnPreDraw();
    }

    @Test
    public void offToStartLeavesTheButtonsAlone() {
        assertFalse(Settings.HIDE_FEED_FOLLOW.get());

        FeedFollowButton.hider().watch(activity);

        assertFalse(FeedFollowButton.hider().listeningForTests(activity));
        assertFalse(frame());
        assertEquals(View.VISIBLE, inline.getVisibility());
        assertEquals(View.VISIBLE, subscribe.getVisibility());
    }

    @Test
    public void onTakesTheButtonsOffAndLeavesTheNameTimeAndMenu() {
        Settings.HIDE_FEED_FOLLOW.save(true);

        FeedFollowButton.hider().watch(activity);

        assertTrue("the frame that would show the buttons is canceled", frame());
        assertEquals(View.GONE, inline.getVisibility());
        assertEquals(View.GONE, inHeader.getVisibility());
        assertEquals(View.GONE, subscribe.getVisibility());
        assertEquals("the name stays", View.VISIBLE, name.getVisibility());
        assertEquals("the time stays", View.VISIBLE, time.getVisibility());
        assertEquals("the menu stays", View.VISIBLE, menu.getVisibility());
        assertEquals("the stub is not touched", header, stub.getParent());
        assertEquals("nothing is taken out", 7, header.getChildCount());
        assertTrue(HookStatus.missing("Hide Follow on feed posts").toString(),
                HookStatus.missing("Hide Follow on feed posts").isEmpty());
    }

    @Test
    public void theSameIdOutsideAFeedPostHeaderStays() {
        Settings.HIDE_FEED_FOLLOW.save(true);

        FeedFollowButton.hider().watch(activity);
        frame();

        assertEquals("a story's Follow button is not a feed post's", View.VISIBLE, storyFollow.getVisibility());
    }

    @Test
    public void pausedReadsAsOff() {
        Settings.HIDE_FEED_FOLLOW.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(app.hushgram.extension.shared.settings.HushgramPause.Reason.SWITCH);

        FeedFollowButton.hider().watch(activity);

        assertFalse(FeedFollowButton.hider().listeningForTests(activity));
        assertEquals(View.VISIBLE, inline.getVisibility());
    }
}
