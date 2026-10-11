/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.ads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.function.ToIntFunction;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Hide ads' answer to the ad-insert method (audit A04). Only an on switch, read with settings ready
 * and HushGram not paused, hides an ad. Every other state leaves Instagram's own insert running.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class AdsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private HookStatus.Snapshot saved;

    @Before
    public void setUp() {
        saved = HookStatus.snapshotAndClear();
        PauseForTests.resume();
        Settings.HIDE_ADS.save(true);
    }

    @After
    public void tearDown() {
        PauseForTests.resume();
        Settings.HIDE_ADS.resetToDefault();
        HookStatus.restore(saved);
    }

    @Test
    public void anOnSwitchHidesTheAd() {
        assertTrue(Ads.hide());
        assertFalse("the call wasn't counted", HookStatus.snapshot().isEmpty());
    }

    @Test
    public void anOffSwitchLetsInstagramInsertIt() {
        Settings.HIDE_ADS.save(false);
        assertFalse(Ads.hide());
    }

    @Test
    public void pauseLetsInstagramInsertIt() {
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Ads.hide());
    }

    @Test
    public void unreadySettingsLetInstagramInsertIt() {
        SettingsContextRule.withoutContext(() -> assertFalse("no context", Ads.hide()));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertFalse("pause undecided", Ads.hide()));
        assertTrue("ready again", Ads.hide());
    }

    /** A feed item, as the parse helper answers it. */
    private static final Object ITEM = new Object();

    /** Says every item it's asked about is sponsored, and counts the asks. */
    private static final class Sponsored implements ToIntFunction<Object> {
        int asked;

        @Override
        public int applyAsInt(Object item) {
            asked++;
            return 1;
        }
    }

    /**
     * Older Posts' page (S25, 450) came with sponsored posts the ad insert never saw. With the
     * switch on, an item whose post carries Instagram's ad details goes, and Diagnostics counts it on
     * its own line and on Hide ads' line.
     */
    @Test
    public void aSponsoredPostInAFeedsPageIsTakenOut() {
        FeedFilterCounters.snapshotAndClear();
        assertNull(Ads.feedItem(ITEM, item -> 1));
        String route = String.join("\n", FeedFilterCounters.report());
        assertTrue(route, route.contains(Ads.ROUTE + ": "));
        assertTrue(route, route.contains("Removed: " + Ads.SPONSORED + " 1"));
        String family = String.join("\n", HookStatus.report());
        assertTrue(family, family.contains(Ads.REMOVED + " 1"));
    }

    @Test
    public void aPostWithoutAdDetailsStays() {
        FeedFilterCounters.snapshotAndClear();
        assertSame(ITEM, Ads.feedItem(ITEM, item -> 0));
        String route = String.join("\n", FeedFilterCounters.report());
        assertTrue(route, route.contains(Ads.NOT_SPONSORED + " 1"));
        assertFalse(route, route.contains("Removed"));
    }

    /** Off, paused or not ready, the page is Instagram's own, and the post isn't even read. */
    @Test
    public void offPausedOrUnreadyKeepsEveryItem() {
        Sponsored sponsored = new Sponsored();
        Settings.HIDE_ADS.save(false);
        assertSame("off", ITEM, Ads.feedItem(ITEM, sponsored));
        Settings.HIDE_ADS.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame("paused", ITEM, Ads.feedItem(ITEM, sponsored));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertSame("no context", ITEM, Ads.feedItem(ITEM, sponsored)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertSame("pause undecided", ITEM, Ads.feedItem(ITEM, sponsored)));
        assertEquals("the post was read", 0, sponsored.asked);
        assertNull("on and ready again", Ads.feedItem(ITEM, sponsored));
    }

    /** A read that throws keeps the item and says where on Hide ads' line. */
    @Test
    public void aReadThatThrowsKeepsTheItem() {
        assertSame(ITEM, Ads.feedItem(ITEM, item -> {
            throw new IllegalStateException("moved");
        }));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("feed item"));
    }

    /** An item another filter already took out stays out, uncounted. */
    @Test
    public void nullStaysNull() {
        Sponsored sponsored = new Sponsored();
        assertNull(Ads.feedItem(null, sponsored));
        assertEquals(0, sponsored.asked);
    }

    /** As built, before the patch writes the stub, nothing is sponsored and every item stays. */
    @Test
    public void theUnwrittenStubKeepsEveryItem() {
        assertEquals(0, Ads.sponsored(ITEM));
        assertSame(ITEM, Ads.feedItem(ITEM));
    }
}
