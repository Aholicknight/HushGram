/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.GraphicsMode;

/** The colour and size choices Glass tab bar makes from Instagram's bar colour and the screen's width. */
@RunWith(RobolectricTestRunner.class)
public class GlassTabBarTest {
    /**
     * A missing shadow or content view is looked for again only every RESEARCH_MS, and a run of misses
     * asks for a few redraws so a view that turns up after a screen switch is found without anything
     * else drawing the bar, never more than MISS_REDRAWS until both views are found again.
     */
    @Test
    public void missesAreLookedForAgainOnAScheduleWithAFewRedraws() {
        GlassTabBar.Misses misses = new GlassTabBar.Misses();
        long now = 1_000_000;
        assertTrue("nothing missed yet", misses.lookAgain(now));
        for (int i = 0; i < GlassTabBar.MISS_REDRAWS; i++) {
            assertTrue("miss " + i + " asks for a redraw", misses.looked(now, true, false));
            assertFalse("not again within RESEARCH_MS", misses.lookAgain(now + GlassTabBar.RESEARCH_MS - 1));
            // A draw in between that didn't look must not restart the count.
            assertFalse(misses.looked(now + 1, false, false));
            now += GlassTabBar.RESEARCH_MS;
            assertTrue(misses.lookAgain(now));
        }
        assertFalse("a bar that never has the view stops asking", misses.looked(now, true, false));
        assertFalse(misses.looked(now + 1, false, true));
        assertTrue("finding both starts a new run", misses.looked(now + GlassTabBar.RESEARCH_MS, true, false));
    }

    @Test
    public void blackAndWhiteBarsReadAsDarkAndLight() {
        assertTrue(GlassTabBar.isDark(Color.BLACK));
        assertTrue(GlassTabBar.isDark(0xff0c1014));
        assertFalse(GlassTabBar.isDark(Color.WHITE));
    }

    @Test
    public void darkGlassIsTheBarsOwnDarkUnderHalfOverTheBlurAndLiftedWithout() {
        int blurred = GlassTabBar.tint(Color.BLACK, true);
        assertEquals(0x70, Color.alpha(blurred));
        assertEquals(0, Color.red(blurred));
        int plain = GlassTabBar.tint(Color.BLACK, false);
        assertEquals(0xe6, Color.alpha(plain));
        assertTrue(Color.red(plain) > 0);
    }

    @Test
    public void lightGlassKeepsItsColourAndIsMoreOpaqueWithoutBlur() {
        assertEquals(0xffffff, GlassTabBar.tint(Color.WHITE, true) & 0xffffff);
        assertTrue(Color.alpha(GlassTabBar.tint(Color.WHITE, false)) > Color.alpha(GlassTabBar.tint(Color.WHITE, true)));
    }

    @Test
    public void highlightAndEdgeAreWhiteOnDarkAndGreyOrBlackOnLight() {
        assertEquals(0xff, Color.red(GlassTabBar.highlight(Color.BLACK)));
        assertEquals(0x78, Color.red(GlassTabBar.highlight(Color.WHITE)));
        assertEquals(0xff, Color.red(GlassTabBar.edge(Color.BLACK)));
        assertEquals(0x00, Color.red(GlassTabBar.edge(Color.WHITE)));
    }

    @Test
    public void theStandardOpacityIsExactlyTodaysTint() {
        for (int base : new int[] {Color.BLACK, Color.WHITE}) {
            for (boolean blurred : new boolean[] {true, false}) {
                assertEquals(GlassTabBar.tint(base, blurred), GlassTabBar.tint(base, blurred, GlassOpacity.STANDARD));
            }
        }
        assertEquals(0x70, Color.alpha(GlassTabBar.tint(Color.BLACK, true, GlassOpacity.STANDARD)));
        assertEquals(0xa6, Color.alpha(GlassTabBar.tint(Color.WHITE, true, GlassOpacity.STANDARD)));
        assertEquals(0xe6, Color.alpha(GlassTabBar.tint(Color.WHITE, false, GlassOpacity.STANDARD)));
    }

    @Test
    public void eachOpacityStepIsStrictlyMoreSolidAndStaysInRange() {
        for (int base : new int[] {Color.BLACK, Color.WHITE}) {
            for (boolean blurred : new boolean[] {true, false}) {
                int last = -1;
                for (GlassOpacity step : GlassOpacity.values()) {
                    int alpha = Color.alpha(GlassTabBar.tint(base, blurred, step));
                    assertTrue(alpha >= 0 && alpha <= 255);
                    assertTrue(step + " " + blurred, alpha >= last);
                    last = alpha;
                }
                assertTrue(Color.alpha(GlassTabBar.tint(base, blurred, GlassOpacity.CLEAR))
                        < Color.alpha(GlassTabBar.tint(base, blurred, GlassOpacity.STANDARD)));
            }
        }
        // Frosted never overflows the alpha byte, and a missing step reads as the standard one.
        assertEquals(255, Color.alpha(GlassTabBar.tint(Color.WHITE, false, GlassOpacity.FROSTED)));
        assertEquals(GlassTabBar.tint(Color.BLACK, true), GlassTabBar.tint(Color.BLACK, true, null));
    }

    @Test
    public void theStandardHeightChangesNothing() {
        float density = 2.625f;
        int bar = Math.round(52 * density);
        assertEquals(0, GlassTabBar.pillInsetPx(GlassHeight.STANDARD, bar, 3, density));
        assertEquals(0, GlassTabBar.pillInsetPx(GlassHeight.TALL, bar, 3, density));
        assertEquals(bar, GlassTabBar.barHeightPx(GlassHeight.STANDARD, bar, density));
        assertEquals(bar, GlassTabBar.barHeightPx(GlassHeight.COMPACT, bar, density));
    }

    @Test
    public void aCompactPillIsSlimmerButNeverShorterThanFortyDp() {
        float density = 3f;
        int gap = 3;
        // A 52dp bar has room to lose 4dp a side: the pill goes from 50dp to 42dp.
        int bar = Math.round(52 * density);
        int inset = GlassTabBar.pillInsetPx(GlassHeight.COMPACT, bar, gap, density);
        assertEquals(Math.round(4 * density), inset);
        assertTrue(bar - 2 * gap - 2 * inset >= 40 * density);
        // A 44dp bar can only lose what keeps the pill at 40dp, and a 40dp one loses nothing.
        for (int dp = 20; dp <= 90; dp++) {
            int height = Math.round(dp * density);
            int slim = GlassTabBar.pillInsetPx(GlassHeight.COMPACT, height, gap, density);
            assertTrue(dp + "dp", slim >= 0 && slim <= Math.round(4 * density));
            int pill = height - 2 * gap - 2 * slim;
            assertTrue(dp + "dp", slim == 0 || pill >= 40 * density - 1);
        }
        assertEquals(0, GlassTabBar.pillInsetPx(GlassHeight.COMPACT, Math.round(40 * density), gap, density));
    }

    @Test
    public void aTallBarGrowsBy8dpUpToSeventyTwo() {
        float density = 2f;
        assertEquals(Math.round(60 * density), GlassTabBar.barHeightPx(GlassHeight.TALL, Math.round(52 * density), density));
        assertEquals(Math.round(72 * density), GlassTabBar.barHeightPx(GlassHeight.TALL, Math.round(68 * density), density));
        // A bar already taller than the limit is left as Instagram made it.
        assertEquals(Math.round(80 * density), GlassTabBar.barHeightPx(GlassHeight.TALL, Math.round(80 * density), density));
        for (int dp = 30; dp <= 100; dp++) {
            int original = Math.round(dp * density);
            int tall = GlassTabBar.barHeightPx(GlassHeight.TALL, original, density);
            assertTrue(tall >= original && tall <= Math.max(original, Math.round(72 * density)));
        }
    }

    /** A 411dp phone (1080px at 2.625) keeps 5% clear each side: a pill about 90% as wide as the screen. */
    @Test
    public void aPhoneKeepsFivePercentClearEachSide() {
        int outer = GlassTabBar.outerPx(1080, 2.625f);
        assertEquals(54, outer, 1);
        assertEquals(0.90f, (1080 - 2f * outer) / 1080f, 0.01f);
    }

    @Test
    public void aNarrowScreenKeepsAtLeastSixteenDp() {
        assertEquals(Math.round(16 * 3f), GlassTabBar.outerPx(200, 3f));
    }

    @Test
    public void aWideScreenNeverGrowsThePillPastFourHundredTwentyDp() {
        float density = 2f;
        int width = 2400;
        int outer = GlassTabBar.outerPx(width, density);
        assertEquals(420 * density, width - 2f * outer, 1f);
    }

    /** With the phone's animations off, a slide that starts now is already over, so the capsule jumps. */
    @Test
    public void withAnimationsOffTheCapsuleJumpsToTheNewTab() {
        try {
            GlassTabBar.animationsOnForTests = false;
            assertEquals(1f, GlassTabBar.slideProgress(1000L - GlassTabBar.slideStartAt(1000L)), 0f);
            GlassTabBar.animationsOnForTests = true;
            assertEquals(0f, GlassTabBar.slideProgress(1000L - GlassTabBar.slideStartAt(1000L)), 0f);
        } finally {
            GlassTabBar.animationsOnForTests = null;
        }
    }

    @Test
    public void theCapsuleSlideIsTimedNotCountedAndEasesOut() {
        assertEquals(0f, GlassTabBar.slideProgress(0), 0f);
        assertEquals(0f, GlassTabBar.slideProgress(-5), 0f);
        assertEquals(1f, GlassTabBar.slideProgress(GlassTabBar.SLIDE_MS), 0f);
        assertEquals(1f, GlassTabBar.slideProgress(GlassTabBar.SLIDE_MS * 3), 0f);
        float half = GlassTabBar.slideProgress(GlassTabBar.SLIDE_MS / 2);
        // Eased out: more than halfway at half time, and always moving forward.
        assertTrue(half > 0.8f && half < 1f);
        float last = 0f;
        for (long t = 0; t <= GlassTabBar.SLIDE_MS; t += 7) {
            float now = GlassTabBar.slideProgress(t);
            assertTrue(now >= last);
            last = now;
        }
    }

    @Test
    public void aLateFrameGetsNoRecordingUntilTheBlurIsTooOld() {
        // The first recording is always made.
        assertTrue(GlassTabBar.shouldRecord(false, 0, 40));
        // Not yet due.
        assertFalse(GlassTabBar.shouldRecord(true, GlassTabBar.RECORD_EVERY_MS - 1, 0));
        // Due, and the frame is on time.
        assertTrue(GlassTabBar.shouldRecord(true, GlassTabBar.RECORD_EVERY_MS, GlassTabBar.LATE_FRAME_MS));
        // Due, but the frame is late: it waits.
        assertFalse(GlassTabBar.shouldRecord(true, GlassTabBar.RECORD_EVERY_MS + 20, GlassTabBar.LATE_FRAME_MS + 1));
        // Too old to keep waiting, so it records even in a late frame.
        assertTrue(GlassTabBar.shouldRecord(true, GlassTabBar.MAX_STALE_MS, 60));
    }

    @Test
    public void hapticTicksCloserThanTheMinimumGapAreDropped() {
        assertTrue(GlassTabBar.Haptics.farEnough(1000, 1000 - GlassTabBar.Haptics.MIN_TICK_GAP_MS));
        assertFalse(GlassTabBar.Haptics.farEnough(1000, 1000 - GlassTabBar.Haptics.MIN_TICK_GAP_MS + 1));
        assertTrue(GlassTabBar.Haptics.SOFT_SCALE > 0.2f && GlassTabBar.Haptics.SOFT_SCALE < 0.7f);
        // The short pulse is short and clearly felt, not a long weak buzz.
        assertTrue(GlassTabBar.Haptics.SHORT_MS >= 3 && GlassTabBar.Haptics.SHORT_MS <= 10);
        assertTrue(GlassTabBar.Haptics.SHORT_AMPLITUDE > 100 && GlassTabBar.Haptics.SHORT_AMPLITUDE <= 255);
    }

    /**
     * Where the tabs swipe sideways, Home's pager is the content's sibling with the bar's height as its
     * bottom margin. Floating, it runs down behind the pill like the content's own screens; a sibling
     * that isn't the pager, and a margin that isn't the bar's height, are left alone.
     */
    @Test
    public void homesSwipePagerBesideTheContentRunsBehindThePillToo() {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout holder = new FrameLayout(context);
        FrameLayout content = new FrameLayout(context);
        View screen = withMargin(new View(context), 126);
        View hiddenBar = withMargin(new View(context), 0);
        content.addView(screen);
        content.addView(hiddenBar);
        View pager = withMargin(new View(context), 126);
        pager.setId(0x7f0b0001);
        View other = withMargin(new View(context), 126);
        other.setId(0x7f0b0002);
        holder.addView(content);
        holder.addView(pager);
        holder.addView(other);

        GlassTabBar.screensAboveTheBar(content, 0x7f0b0001, 126, 0);

        assertEquals("the content's screen", 0, margin(screen));
        assertEquals("a screen Instagram already let run down", 0, margin(hiddenBar));
        assertEquals("Home's swipe pager", 0, margin(pager));
        assertEquals("another sibling", 126, margin(other));

        View tallerPager = withMargin(new View(context), 126);
        tallerPager.setId(0x7f0b0003);
        holder.addView(tallerPager);
        GlassTabBar.screensAboveTheBar(content, 0x7f0b0003, 126, 150);
        assertEquals("not floating, the pager stops at a taller pill's top", 150, margin(tallerPager));
        assertEquals("a margin already changed stays", 0, margin(pager));

        View noPager = withMargin(new View(context), 126);
        holder.addView(noPager);
        GlassTabBar.screensAboveTheBar(content, 0, 126, 0);
        assertEquals("no pager id, no sibling touched", 126, margin(noPager));
    }

    /**
     * #136 and #144: the glass finds each SurfaceView under the bar and the views on the way down to it, and
     * nothing else. A TextureView draws like any view, so it isn't one, and the bar's own branch isn't looked at.
     */
    @Test
    public void theGlassFindsEachSurfaceViewUnderTheBarAndTheWayDownToIt() {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout holder = new FrameLayout(context);
        FrameLayout pager = new FrameLayout(context);
        FrameLayout page = new FrameLayout(context);
        FrameLayout media = new FrameLayout(context);
        SurfaceView video = new SurfaceView(context);
        View cover = new View(context);
        View caption = new View(context);
        FrameLayout other = new FrameLayout(context);
        TextureView texture = new TextureView(context);
        FrameLayout bar = new FrameLayout(context);
        SurfaceView inBar = new SurfaceView(context);
        media.addView(video);
        media.addView(cover);
        page.addView(media);
        page.addView(caption);
        pager.addView(page);
        other.addView(texture);
        bar.addView(inBar);
        holder.addView(pager);
        holder.addView(other);
        holder.addView(bar);
        Set<View> paths = new HashSet<>();
        paths.add(caption);

        assertTrue(GlassTabBar.surfacePaths(holder, 2, paths));
        assertEquals(new HashSet<>(java.util.Arrays.asList(pager, page, media, video)), paths);

        assertFalse("only the views under the bar count", GlassTabBar.surfacePaths(holder, 0, paths));
        assertTrue(paths.isEmpty());
        pager.removeView(page);
        assertFalse("a TextureView isn't left out", GlassTabBar.surfacePaths(holder, 2, paths));
        assertTrue(paths.isEmpty());
    }

    /**
     * #136 and #144: the glass draws the views under the bar without their SurfaceViews, everything else where
     * it sits. Drawn into the glass a second time, a SurfaceView had its video moved under the pill and left
     * black where it played, so the glass gave up its blur whenever a video was on screen anywhere, and the
     * pill went flat and nearly opaque as a feed of videos scrolled. Drawing the views whole, the old way,
     * draws the SurfaceView, so the count below proves it's seen.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void theGlassDrawsTheViewsUnderTheBarWithoutTheirSurfaceViews() {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout holder = new FrameLayout(context);
        FrameLayout feed = new FrameLayout(context);
        feed.setBackground(new ColorDrawable(Color.RED));
        CountingSurface video = new CountingSurface(context);
        View caption = new View(context);
        caption.setBackground(new ColorDrawable(Color.GREEN));
        View faded = new View(context);
        faded.setBackground(new ColorDrawable(Color.BLUE));
        faded.setAlpha(0f);
        feed.addView(video);
        feed.addView(caption);
        feed.addView(faded);
        holder.addView(feed);
        holder.layout(0, 0, 100, 100);
        feed.layout(0, 10, 100, 100);
        video.layout(0, 0, 100, 50);
        caption.layout(0, 60, 100, 80);
        faded.layout(0, 0, 100, 20);
        feed.scrollTo(0, 5);
        Set<View> paths = new HashSet<>();
        assertTrue(GlassTabBar.surfacePaths(holder, 1, paths));

        Bitmap old = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Canvas oldCanvas = new Canvas(old);
        oldCanvas.translate(feed.getLeft(), feed.getTop());
        feed.draw(oldCanvas);
        assertTrue("drawn whole, the feed draws its SurfaceView", video.drawn > 0);

        video.drawn = 0;
        Bitmap glass = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        GlassTabBar.drawLeavingOutSurfaces(new Canvas(glass), feed, paths, 0);

        assertEquals("the SurfaceView is left out", 0, video.drawn);
        assertEquals("the feed's own background still shows", Color.RED, glass.getPixel(50, 30));
        assertEquals("a child draws where it sits, scrolled", Color.GREEN, glass.getPixel(50, 66));
        assertEquals(Color.RED, glass.getPixel(50, 86));
        assertEquals("a see-through child isn't drawn", Color.RED, glass.getPixel(50, 12));
        assertEquals("nothing above the feed", 0, glass.getPixel(50, 5));
    }

    private static final class CountingSurface extends SurfaceView {
        int drawn;

        CountingSurface(Context context) {
            super(context);
        }

        @Override public void draw(Canvas canvas) {
            drawn++;
            super.draw(canvas);
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            drawn++;
            super.dispatchDraw(canvas);
        }
    }

    /**
     * A floating pill keeps each Reels viewer above it, so its seek bar, caption and the touch strip
     * Instagram lays over the bar's top don't sit under the pill and take its taps: the Reels tab's in the
     * swipe pager and one opened over another tab get the bar's height as their margin. A margin Instagram
     * set stays, the bar's own branch is never touched, a taller pill moves them with it, and a hidden bar
     * gives the room back.
     */
    @Test
    public void reelsViewersStopAtAFloatingBarsTopAndGetTheRoomBackWhenItHides() {
        Context context = RuntimeEnvironment.getApplication();
        int reels = 0x7f0b0010;
        FrameLayout holder = new FrameLayout(context);
        FrameLayout pager = new FrameLayout(context);
        FrameLayout page = new FrameLayout(context);
        View tab = withMargin(new FrameLayout(context), 0);
        tab.setId(reels);
        page.addView(tab);
        pager.addView(page);
        View overHome = withMargin(new FrameLayout(context), 0);
        overHome.setId(reels);
        pager.addView(overHome);
        View instagrams = withMargin(new View(context), 40);
        instagrams.setId(reels);
        pager.addView(instagrams);
        FrameLayout bar = new FrameLayout(context);
        View insideBar = withMargin(new View(context), 0);
        insideBar.setId(reels);
        bar.addView(insideBar);
        holder.addView(pager);
        holder.addView(bar);
        Map<View, Integer> given = new HashMap<>();

        assertEquals(2, GlassTabBar.reelsAboveTheBar(holder, bar, reels, 126, given));
        assertEquals("the Reels tab", 126, margin(tab));
        assertEquals("a reel opened over Home", 126, margin(overHome));
        assertEquals("Instagram's own margin", 40, margin(instagrams));
        assertEquals("the bar's own branch", 0, margin(insideBar));
        assertEquals("settled, nothing changes", 0, GlassTabBar.reelsAboveTheBar(holder, bar, reels, 126, given));

        assertEquals(2, GlassTabBar.reelsAboveTheBar(holder, bar, reels, 150, given));
        assertEquals("a taller pill", 150, margin(tab));
        assertEquals(2, GlassTabBar.reelsAboveTheBar(holder, bar, reels, 0, given));
        assertEquals("a hidden bar", 0, margin(tab));
        assertEquals(40, margin(instagrams));
        assertTrue(given.isEmpty());
        assertEquals("no id, nothing found", 0, GlassTabBar.reelsAboveTheBar(holder, bar, 0, 126, given));
    }

    private static View withMargin(View view, int bottom) {
        ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        params.bottomMargin = bottom;
        view.setLayoutParams(params);
        return view;
    }

    private static int margin(View view) {
        return ((ViewGroup.MarginLayoutParams) view.getLayoutParams()).bottomMargin;
    }
}
