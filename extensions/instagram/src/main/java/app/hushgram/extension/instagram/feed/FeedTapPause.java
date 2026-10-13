/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Tap a feed video to pause it" patch.
 *
 * <p>Instagram 450 sends every tap on a feed post through one handler, which decides between
 * opening the post in the Reels viewer, showing its tags, turning its sound on and a few more. Only
 * with TalkBack on does the feed video controller it ends in pause and play the video. The patch
 * asks {@link #tapped} first thing in that handler, and a yes ends the handler there.
 *
 * <p>The answer is yes when the post that was tapped is the one the feed video controller has
 * playing, which is the test Instagram's own tap makes: the page of the tapped post its state is
 * on equals the post the controller's playing item shows. Then this runs what Instagram's TalkBack
 * tap runs. A playing video is paused through the controller with Instagram's own tap reason, one
 * that isn't playing is played through the controller with "start", and the post's state is told
 * which it was.
 *
 * <p>Everything else is left to Instagram: a sponsored post, a photo, a carousel on a photo, a video
 * the controller doesn't have playing, a feed with no video controller. So is every tap while the
 * switch is off, HushGram is paused or the settings aren't ready, and any tap this throws on before
 * it has paused or played anything.
 */
public final class FeedTapPause {
    /** What {@link HookStatus} counts: taps that paused the playing video, and taps that played it again. */
    static final String PAUSED = "feed videos paused by a tap";
    static final String PLAYED = "feed videos played by a tap";

    /** What {@link HookStatus} counts: taps with the switch on that weren't on the playing video, or were on an ad. */
    static final String LEFT = "taps left to Instagram";

    /** The hook's name in a failure report. */
    static final String TAP = "feed tap";

    /** What {@link FeedPlayerReader}'s stubs return until the patch fills them in. */
    static final Object NOT_PATCHED = new Object();

    /** The feed's video controller and player, as the patch's stubs reach them. Tests put their own in. */
    interface Videos {
        /** The feed video controller behind the tap [handler], or null. */
        @Nullable
        Object controllerOf(Object handler);

        /** The post the controller's playing item shows, or null while nothing is playing. */
        @Nullable
        Object shownOf(Object controller);

        /** The page of [media] its [state] is on. */
        @Nullable
        Object pageOf(Object media, Object state);

        /** The post's ad details, null for a post that isn't sponsored. */
        @Nullable
        Object adDetailsOf(Object media);

        /** The controller's player, or null. */
        @Nullable
        Object playerOf(Object controller);

        /** A Boolean, whether the player is playing. */
        @Nullable
        Object playing(Object player);

        /** Pauses the controller's video the way Instagram's own tap does. */
        @Nullable
        Object pause(Object controller);

        /** Plays the controller's video the way Instagram's own tap does. */
        @Nullable
        Object start(Object controller);

        /** Tells the post's state whether its video was playing when it was tapped. */
        @Nullable
        Object mark(Object state, boolean wasPlaying);
    }

    /** {@link FeedPlayerReader}'s stubs, which the patch fills in. */
    private static final class Patched implements Videos {
        @Override public Object controllerOf(Object handler) {
            return FeedPlayerReader.controllerOf(handler);
        }

        @Override public Object shownOf(Object controller) {
            return FeedPlayerReader.shownOf(controller);
        }

        @Override public Object pageOf(Object media, Object state) {
            return FeedPlayerReader.pageOf(media, state);
        }

        @Override public Object adDetailsOf(Object media) {
            return FeedPlayerReader.adDetailsOf(media);
        }

        @Override public Object playerOf(Object controller) {
            return FeedPlayerReader.playerOf(controller);
        }

        @Override public Object playing(Object player) {
            return FeedPlayerReader.playing(player);
        }

        @Override public Object pause(Object controller) {
            return FeedPlayerReader.pause(controller, null);
        }

        @Override public Object start(Object controller) {
            return FeedPlayerReader.start(controller, null, null, null);
        }

        @Override public Object mark(Object state, boolean wasPlaying) {
            return FeedPlayerReader.mark(state, Boolean.valueOf(wasPlaying));
        }
    }

    /**
     * Reaches the reader only through this, from inside {@link #tapped}'s try, so a reader the phone
     * refuses to load costs this tap its pause and never the tap itself.
     */
    static Videos videos = new Patched();

    private FeedTapPause() {
    }

    /**
     * Asked first thing in the feed's tap handler, with the handler, the post that was tapped and
     * that post's state. True means the tap paused or played the video and the handler is done.
     * False leaves the tap to Instagram: the switch is off, HushGram is paused, the settings aren't
     * ready, the post is sponsored or isn't the video the controller has playing, the patch's stubs
     * are unfilled, or something in here threw before the video was paused or played. Never throws.
     */
    public static boolean tapped(@Nullable Object handler, @Nullable Object media, @Nullable Object state) {
        boolean handled = false;
        try {
            HookStatus.invoked(FamilyNames.FEED_TAP_PAUSE);
            if (handler == null || media == null || state == null) return false;
            if (!Utils.settingsReady() || !Settings.TAP_FEED_VIDEO_TO_PAUSE.get()) return false;
            HookStatus.bound(FamilyNames.FEED_TAP_PAUSE, TAP);
            Videos feed = videos;

            Object controller = feed.controllerOf(handler);
            if (unfilled(controller, "the feed video controller")) return false;
            if (controller == null) return left();
            Object shown = feed.shownOf(controller);
            if (unfilled(shown, "the playing post")) return false;
            if (shown == null) return left();
            Object page = feed.pageOf(media, state);
            if (unfilled(page, "the tapped post's page")) return false;
            if (page == null || !page.equals(shown)) return left();
            Object ad = feed.adDetailsOf(media);
            if (unfilled(ad, "the post's ad details")) return false;
            if (ad != null) return left();
            Object player = feed.playerOf(controller);
            if (unfilled(player, "the player")) return false;
            if (player == null) return left();
            Object playing = feed.playing(player);
            if (unfilled(playing, "the player's state")) return false;
            if (!(playing instanceof Boolean)) return left();

            boolean wasPlaying = (Boolean) playing;
            Object done = wasPlaying ? feed.pause(controller) : feed.start(controller);
            if (unfilled(done, wasPlaying ? "the controller's pause" : "the controller's play")) return false;
            handled = true;
            HookStatus.counted(FamilyNames.FEED_TAP_PAUSE, wasPlaying ? PAUSED : PLAYED);
            Logger.printDebug(() -> "Feed video: a tap " + (wasPlaying ? "paused it" : "played it"));
            unfilled(feed.mark(state, wasPlaying), "the post state's note");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_TAP_PAUSE, TAP, failure);
            return handled;
        }
    }

    /** Counts a tap that goes to Instagram with the switch on, and answers no. */
    private static boolean left() {
        HookStatus.counted(FamilyNames.FEED_TAP_PAUSE, LEFT);
        return false;
    }

    /** Whether [value] is a stub's unfilled answer, which is reported as the member the build is missing. */
    private static boolean unfilled(@Nullable Object value, String what) {
        if (value != NOT_PATCHED) return false;
        HookStatus.missingMember(FamilyNames.FEED_TAP_PAUSE, "method", "FeedPlayerReader", what);
        return true;
    }

    /** Puts the patched reader back. For tests. */
    static void resetForTests() {
        videos = new Patched();
    }
}
