/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * What the feed tap hook answers: a tap on the playing video pauses it and the next one plays it, and off, paused, not
 * ready, an ad, any other post and a failure are all Instagram's own tap.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class FeedTapPauseTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final Object handler = new Object();
    private final Object state = new Object();
    private final Object media = "post";
    private Feed feed;

    /** A feed with one video playing: the post under the tap, on the page the controller shows. */
    private static final class Feed implements FeedTapPause.Videos {
        Object controller = new Object();
        Object shown = "post";
        Object page = "post";
        Object ad;
        Object player = new Object();
        Object playing = Boolean.TRUE;
        int pauses;
        int starts;
        Boolean marked;
        RuntimeException failReading;
        RuntimeException failMarking;

        @Override public Object controllerOf(Object handler) {
            if (failReading != null) throw failReading;
            return controller;
        }

        @Override public Object shownOf(Object controller) {
            return shown;
        }

        @Override public Object pageOf(Object media, Object state) {
            return page;
        }

        @Override public Object adDetailsOf(Object media) {
            return ad;
        }

        @Override public Object playerOf(Object controller) {
            return player;
        }

        @Override public Object playing(Object player) {
            return playing;
        }

        @Override public Object pause(Object controller) {
            pauses++;
            playing = Boolean.FALSE;
            return controller;
        }

        @Override public Object start(Object controller) {
            starts++;
            playing = Boolean.TRUE;
            return controller;
        }

        @Override public Object mark(Object state, boolean wasPlaying) {
            if (failMarking != null) throw failMarking;
            marked = wasPlaying;
            return state;
        }
    }

    @Before
    public void clean() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        feed = new Feed();
        FeedTapPause.videos = feed;
    }

    @After
    public void restore() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedTapPause.resetForTests();
    }

    private static String report() {
        List<String> lines = HookStatus.report();
        StringBuilder all = new StringBuilder();
        for (String line : lines) all.append(line).append('\n');
        return all.toString();
    }

    private boolean tap() {
        return FeedTapPause.tapped(handler, media, state);
    }

    /** Off to start: the tap is Instagram's, and only the call itself is counted. */
    @Test
    public void offItIsInstagramsTap() {
        assertFalse(Settings.TAP_FEED_VIDEO_TO_PAUSE.get());
        assertFalse(tap());
        assertEquals(0, feed.pauses + feed.starts);
        assertNull(feed.marked);
        String report = report();
        assertTrue(report, report.contains(FamilyNames.FEED_TAP_PAUSE + ": invoked 1"));
        assertFalse(report, report.contains(FeedTapPause.PAUSED));
        assertFalse(report, report.contains("taps left"));
    }

    /** On, a tap on the playing video pauses it through the controller, tells the post's state and ends the tap. */
    @Test
    public void aTapOnThePlayingVideoPausesIt() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        assertTrue(tap());
        assertEquals("pauses", 1, feed.pauses);
        assertEquals("plays", 0, feed.starts);
        assertEquals("the state hears it was playing", Boolean.TRUE, feed.marked);
        assertTrue(report(), report().contains(FeedTapPause.PAUSED + " 1"));
        assertTrue(HookStatus.missing(FamilyNames.FEED_TAP_PAUSE).toString(), HookStatus.missing(FamilyNames.FEED_TAP_PAUSE).isEmpty());
    }

    /** The next tap plays it again, and the one after pauses it. */
    @Test
    public void theNextTapPlaysItAgain() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        assertTrue("first tap", tap());
        assertTrue("second tap", tap());
        assertEquals("pauses", 1, feed.pauses);
        assertEquals("plays", 1, feed.starts);
        assertEquals("the state hears it wasn't playing", Boolean.FALSE, feed.marked);
        assertTrue("third tap", tap());
        assertEquals("pauses", 2, feed.pauses);
        String report = report();
        assertTrue(report, report.contains(FeedTapPause.PAUSED + " 2"));
        assertTrue(report, report.contains(FeedTapPause.PLAYED + " 1"));
    }

    /** A post that isn't the one playing, a photo say, keeps Instagram's tap and is counted under "another post". */
    @Test
    public void aPostThatIsNotPlayingIsInstagrams() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        feed.page = "another post";
        assertFalse(tap());
        feed.page = null;
        assertFalse("a post with no page", tap());
        assertEquals(0, feed.pauses + feed.starts);
        assertNull(feed.marked);
        assertTrue(report(), report().contains(FeedTapPause.LEFT_ANOTHER_POST + " 2"));
    }

    /** With nothing playing, no controller, no player or a player that doesn't say, each check counts on its own. */
    @Test
    public void withNothingToPauseItIsInstagrams() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        feed.shown = null;
        assertFalse("nothing playing", tap());
        feed.shown = "post";
        feed.controller = null;
        assertFalse("no controller", tap());
        feed.controller = new Object();
        feed.player = null;
        assertFalse("no player", tap());
        feed.player = new Object();
        feed.playing = null;
        assertFalse("a player that doesn't say", tap());
        assertEquals(0, feed.pauses + feed.starts);
        String report = report();
        assertTrue(report, report.contains(FeedTapPause.LEFT_NOTHING_PLAYING + " 1"));
        assertTrue(report, report.contains(FeedTapPause.LEFT_NO_CONTROLLER + " 1"));
        assertTrue(report, report.contains(FeedTapPause.LEFT_NO_PLAYER + " 1"));
        assertTrue(report, report.contains(FeedTapPause.LEFT_NOT_A_BOOLEAN + " 1"));
    }

    /** A sponsored post keeps Instagram's tap even while its video plays, counted under "an ad". */
    @Test
    public void anAdIsInstagrams() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        feed.ad = new Object();
        assertFalse(tap());
        assertEquals(0, feed.pauses + feed.starts);
        assertTrue(report(), report().contains(FeedTapPause.LEFT_AN_AD + " 1"));
    }

    /** Paused, a saved switch leaves the tap alone and reads nothing. */
    @Test
    public void pausedHushGramLeavesTheTap() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(tap());
        assertTrue("the saved choice is kept", Settings.TAP_FEED_VIDEO_TO_PAUSE.savedValue());
        assertEquals(0, feed.pauses + feed.starts);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(tap());
        assertEquals(1, feed.pauses);
    }

    /** Before the settings are ready the hook can't read the switch, so the tap is Instagram's. */
    @Test
    public void beforeTheSettingsAreReadyItIsInstagramsTap() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        SettingsContextRule.withoutContext(() -> assertFalse(tap()));
        assertEquals(0, feed.pauses + feed.starts);
    }

    /** A tap that comes without its handler, post or state is Instagram's. */
    @Test
    public void withoutItsArgumentsItSaysNo() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        assertFalse(FeedTapPause.tapped(null, media, state));
        assertFalse(FeedTapPause.tapped(handler, null, state));
        assertFalse(FeedTapPause.tapped(handler, media, null));
        assertEquals(0, feed.pauses + feed.starts);
    }

    /** The stubs as the build ships them, unfilled: the tap is Instagram's and the miss is reported once. */
    @Test
    public void unfilledStubsLeaveTheTapAndAreReported() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        FeedTapPause.resetForTests();
        assertFalse(tap());
        assertFalse(tap());
        List<String> missing = HookStatus.missing(FamilyNames.FEED_TAP_PAUSE);
        assertEquals(missing.toString(), 1, missing.size());
        assertTrue(missing.toString(), missing.get(0).contains("FeedPlayerReader"));
    }

    /** A failure before anything was paused leaves the tap to Instagram and is reported. */
    @Test
    public void aFailureBeforeThePauseLeavesTheTap() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        feed.failReading = new IllegalStateException("gone");
        assertFalse(tap());
        assertEquals(0, feed.pauses + feed.starts);
        String missing = HookStatus.missing(FamilyNames.FEED_TAP_PAUSE).toString();
        assertTrue(missing, missing.contains(FeedTapPause.TAP) && missing.contains("IllegalStateException"));
    }

    /** A failure after the video was paused doesn't hand the same tap to Instagram as well. */
    @Test
    public void aFailureAfterThePauseStillEndsTheTap() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        feed.failMarking = new IllegalStateException("gone");
        assertTrue(tap());
        assertEquals(1, feed.pauses);
        assertFalse(HookStatus.missing(FamilyNames.FEED_TAP_PAUSE).isEmpty());
    }
}
