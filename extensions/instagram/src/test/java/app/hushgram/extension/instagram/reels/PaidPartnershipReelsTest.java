/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Which reels a page keeps, and that the page comes back as it came whenever the hook can't decide. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class PaidPartnershipReelsTest {
    /** A reel's post: its is_paid_partnership and boosted_status, as Instagram parsed them. */
    static final class Post {
        final Boolean partnership;
        final String boost;

        Post(Boolean partnership, String boost) {
            this.partnership = partnership;
            this.boost = boost;
        }
    }

    /** A Reels item carrying a post, which can be null. */
    static final class Clip {
        final Post post;

        Clip(Post post) {
            this.post = post;
        }
    }

    /** Reads a Clip the way the patch-filled stubs read Instagram's item, counting each read. */
    static class Reader implements PaidPartnershipReels.Reader {
        final AtomicInteger reads = new AtomicInteger();

        @Override
        public Object media(Object item) {
            reads.incrementAndGet();
            return item instanceof Clip ? ((Clip) item).post : null;
        }

        @Override
        public Boolean paidPartnership(Object media) {
            return ((Post) media).partnership;
        }

        @Override
        public String boostedStatus(Object media) {
            return ((Post) media).boost;
        }
    }

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final Reader reader = new Reader();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_PAID_PARTNERSHIP_REELS.save(true);
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_PAID_PARTNERSHIP_REELS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    private List<?> page(List<?> items) {
        return PaidPartnershipReels.page(items, reader, PaidPartnershipReels::switchedOn);
    }

    private static Clip clip(Boolean partnership, String boost) {
        return new Clip(new Post(partnership, boost));
    }

    /** On, a paid partnership and a reel whose boost is running or ran go, in any case, and are counted by reason. */
    @Test
    public void paidPartnershipAndBoostedReelsAreTakenOut() {
        Clip plain = clip(false, null);
        Clip partnership = clip(true, null);
        Clip running = clip(null, "boosted");
        Clip ran = clip(false, "FINISHED");
        Clip both = clip(true, "Boosted");
        Clip last = clip(null, "not_boosted");
        List<Clip> items = Arrays.asList(plain, partnership, running, ran, both, last);

        List<?> kept = page(items);

        assertEquals(Arrays.asList(plain, last), kept);
        assertNotSame(items, kept);
        assertEquals("the page handed in is left as it was", 6, items.size());
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains("Paid partnership reels: 1 lists, 6 items, 4 removed"));
        assertTrue(report, report.contains("paid partnership 2"));
        assertTrue(report, report.contains("boosted 2"));
        assertTrue(HookStatus.missing(FamilyNames.PAID_PARTNERSHIP_REELS).toString(),
                HookStatus.missing(FamilyNames.PAID_PARTNERSHIP_REELS).isEmpty());
    }

    /** Only boosted and finished say a boost delivered; every other state, and anything unknown, stays. */
    @Test
    public void otherBoostStatesStay() {
        for (String status : new String[]{"boosted", "BOOSTED", "Boosted", "finished", "Finished"}) {
            assertTrue(status, PaidPartnershipReels.boostRan(status));
        }
        List<Object> items = new ArrayList<>();
        for (String status : new String[]{"not_boosted", "pending", "boosted_eligible", "boosted_eligible_paused",
                "not_approved", "unavailable", "draft", "paused", "not_delivering", "something_new", "",
                " boosted ", "boosted_v2", null}) {
            assertFalse(String.valueOf(status), PaidPartnershipReels.boostRan(status));
            items.add(clip(false, status));
        }
        Clip partnership = clip(true, "pending");
        items.add(3, partnership);

        List<?> kept = page(items);

        List<Object> expected = new ArrayList<>(items);
        expected.remove(partnership);
        assertEquals(expected, kept);
        assertTrue(FeedFilterCounters.report().toString(), FeedFilterCounters.report().toString().contains("1 removed"));
    }

    /** A post that doesn't say, a Reels item with no post, something that isn't a Reels item and a null all stay. */
    @Test
    public void whatDoesntSayStays() {
        Clip unsaid = clip(null, null);
        Clip noPost = new Clip(null);
        Object other = "a header";
        Clip partnership = clip(true, null);
        List<Object> items = Arrays.asList(unsaid, noPost, other, null, partnership);

        assertEquals(Arrays.asList(unsaid, noPost, other, null), page(items));

        List<Object> nothingMatches = Arrays.asList(unsaid, noPost, other, null);
        assertSame(nothingMatches, page(nothingMatches));
    }

    /** A page where every reel would go comes back whole, so the viewer has a reel to show, and that's counted. */
    @Test
    public void aPageWhereEveryReelMatchesIsKeptWhole() {
        List<Clip> items = Arrays.asList(clip(true, null), clip(false, "boosted"), clip(null, "finished"));

        assertSame(items, page(items));
        List<Clip> one = Collections.singletonList(clip(true, null));
        assertSame(one, page(one));

        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains("Paid partnership reels: 2 lists, 4 items, 0 removed"));
        assertTrue(report, report.contains("page kept whole 2"));
    }

    /** Off, paused or before the settings are read, the page comes back as it came, read by nothing. */
    @Test
    public void offPausedAndUnreadyKeepEveryReel() {
        List<Clip> items = Arrays.asList(clip(true, null), clip(false, null));

        Settings.HIDE_PAID_PARTNERSHIP_REELS.save(false);
        assertSame(items, page(items));
        Settings.HIDE_PAID_PARTNERSHIP_REELS.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(items, page(items));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame(items, page(items)));

        assertEquals("a reel was read while the switch was off", 0, reader.reads.get());
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains("Paid partnership reels: 3 lists, 6 items, 0 removed"));
        assertEquals(Collections.singletonList(items.get(1)), page(items));
    }

    /** A reader or a switch that throws leaves the page as it came and says which hook threw. */
    @Test
    public void aThrowingReaderOrSwitchKeepsThePageAndIsReported() {
        List<Clip> items = Arrays.asList(clip(true, null), clip(false, null));
        PaidPartnershipReels.Reader broken = new Reader() {
            @Override
            public Boolean paidPartnership(Object media) {
                throw new ClassCastException("Post cannot be cast to Media");
            }
        };

        assertSame(items, PaidPartnershipReels.page(items, broken, () -> true));
        assertSame(items, PaidPartnershipReels.page(items, reader, () -> {
            throw new IllegalStateException("settings went away");
        }));

        String missing = HookStatus.missing(FamilyNames.PAID_PARTNERSHIP_REELS).toString();
        assertTrue(missing, missing.contains("'reels page'"));
        assertTrue(missing, missing.contains(ClassCastException.class.getName()));
        assertEquals(FeedFilterCounters.report().toString(), 0, reader.reads.get());
    }

    /** A null page stays null, an empty one comes back as it came, and the stubs as built keep every reel. */
    @Test
    public void nullEmptyAndUnpatchedPagesStay() {
        assertNull(PaidPartnershipReels.page(null));
        List<Clip> empty = Collections.emptyList();
        assertSame(empty, page(empty));
        assertEquals("an empty page reads nothing", 0, reader.reads.get());

        List<Clip> items = Arrays.asList(clip(true, "boosted"), clip(false, null));
        assertSame(items, PaidPartnershipReels.page(items));
        assertNull(PaidPartnershipReels.media(items.get(0)));
        assertNull(PaidPartnershipReels.paidPartnership(items.get(0).post));
        assertNull(PaidPartnershipReels.boostedStatus(items.get(0).post));
    }
}
