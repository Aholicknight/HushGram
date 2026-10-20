/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import static org.junit.Assert.assertEquals;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * What Hide profile tabs does to the list of tab keys Instagram builds for a profile: it drops the
 * keys the setting names, never the grid, never by position, and gives Instagram's list back
 * untouched when nothing is picked, HushGram is paused or the list isn't one it knows.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ProfileTabsTest {
    private static final String GRID = "profile_media_grid";
    private static final String REELS = "profile_clips";
    private static final String REPOSTS = "profile_reposts";
    private static final String TAGGED = "profile_tagged_media_photos_of_you";

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDDEN_PROFILE_TABS.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDDEN_PROFILE_TABS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static List<String> all() {
        return Arrays.asList(GRID, REELS, REPOSTS, TAGGED);
    }

    /** Nothing picked is the start, and Instagram's own list goes back as the same object. */
    @Test
    public void nothingPickedLeavesInstagramsListAlone() {
        assertEquals("", Settings.HIDDEN_PROFILE_TABS.get());
        List<String> tabs = all();
        assertSame(tabs, ProfileTabs.tabs(tabs));
        assertTrue(HookStatus.missing(FamilyNames.PROFILE_TABS).toString(), HookStatus.missing(FamilyNames.PROFILE_TABS).isEmpty());
    }

    /** Each tab goes by its key, and the others keep Instagram's order. */
    @Test
    public void eachPickedTabGoesByItsKey() {
        Settings.HIDDEN_PROFILE_TABS.save(REELS);
        assertEquals(Arrays.asList(GRID, REPOSTS, TAGGED), ProfileTabs.tabs(all()));
        Settings.HIDDEN_PROFILE_TABS.save(REPOSTS);
        assertEquals(Arrays.asList(GRID, REELS, TAGGED), ProfileTabs.tabs(all()));
        Settings.HIDDEN_PROFILE_TABS.save(TAGGED);
        assertEquals(Arrays.asList(GRID, REELS, REPOSTS), ProfileTabs.tabs(all()));
    }

    /** All three picked leaves the grid alone, which Instagram draws without a strip. */
    @Test
    public void allThreePickedLeavesTheGrid() {
        Settings.HIDDEN_PROFILE_TABS.save(ProfileTabs.join(new LinkedHashSet<>(ProfileTabs.OFFERED)));
        assertEquals(Collections.singletonList(GRID), ProfileTabs.tabs(all()));
    }

    /** Someone else's profile often has fewer tabs, and a tab it lacks is never added or missed. */
    @Test
    public void aProfileWithFewerTabsWorksToo() {
        Settings.HIDDEN_PROFILE_TABS.save(REELS + "," + REPOSTS);
        assertEquals(Arrays.asList(GRID, TAGGED), ProfileTabs.tabs(Arrays.asList(GRID, REELS, TAGGED)));
        List<String> gridOnly = Collections.singletonList(GRID);
        assertSame("nothing to drop", gridOnly, ProfileTabs.tabs(gridOnly));
    }

    /** Tabs Instagram has that this doesn't offer stay where they are, and position never decides. */
    @Test
    public void otherTabsAndOrderDontMatter() {
        Settings.HIDDEN_PROFILE_TABS.save(REELS);
        List<String> shuffled = Arrays.asList("profile_longform", TAGGED, GRID, REELS, "profile_products");
        assertEquals(Arrays.asList("profile_longform", TAGGED, GRID, "profile_products"), ProfileTabs.tabs(shuffled));
    }

    /** The grid can't be named, and a list without one isn't thinned. */
    @Test
    public void theGridIsNeverDropped() {
        Settings.HIDDEN_PROFILE_TABS.save(GRID + "," + REELS);
        assertEquals(Collections.singleton(REELS), ProfileTabs.parse(Settings.HIDDEN_PROFILE_TABS.get()));
        assertEquals(Arrays.asList(GRID, REPOSTS, TAGGED), ProfileTabs.tabs(all()));
        List<String> noGrid = Arrays.asList(REELS, REPOSTS);
        assertSame(noGrid, ProfileTabs.tabs(noGrid));
    }

    /** Paused, Instagram's list goes back untouched whatever is saved. */
    @Test
    public void pausedReadsAsNothingPicked() {
        Settings.HIDDEN_PROFILE_TABS.save(REELS + "," + REPOSTS + "," + TAGGED);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        List<String> tabs = all();
        assertSame(tabs, ProfileTabs.tabs(tabs));
        assertEquals("the choice is kept", REELS + "," + REPOSTS + "," + TAGGED, Settings.HIDDEN_PROFILE_TABS.savedValue());
    }

    /** A null list and a list that won't copy come back as they were, and nothing throws. */
    @Test
    public void nothingThrowsIntoInstagram() {
        Settings.HIDDEN_PROFILE_TABS.save(REELS);
        assertNull(ProfileTabs.tabs(null));
        List<String> broken = new ArrayList<String>(all()) {
            @Override public boolean contains(Object o) {
                throw new IllegalStateException("no");
            }
        };
        assertSame(broken, ProfileTabs.tabs(broken));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("threw"));
    }

    /** A dropped tab is counted once per profile it was dropped on. */
    @Test
    public void aDroppedTabIsCounted() {
        Settings.HIDDEN_PROFILE_TABS.save(REELS);
        ProfileTabs.tabs(all());
        ProfileTabs.tabs(all());
        ProfileTabs.tabs(Collections.singletonList(GRID));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains("Counted: " + ProfileTabs.LEFT_OFF + " 2"));
    }

    /** Saved text keeps only the keys offered, once each, in the order the dialog lists them. */
    @Test
    public void savedTextIsTidied() {
        Set<String> parsed = ProfileTabs.parse(" " + TAGGED + ", ,profile_clips,profile_clips,nonsense," + GRID);
        assertEquals(new LinkedHashSet<>(Arrays.asList(TAGGED, REELS)), parsed);
        assertEquals(REELS + "," + TAGGED, ProfileTabs.join(parsed));
        assertEquals("", ProfileTabs.join(Collections.<String>emptySet()));
        assertTrue(ProfileTabs.parse(null).isEmpty());
    }
}
