/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What Swipe left to open the profile answers a reel's swipe gate, and when it leaves the answer to Instagram. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class SwipeToProfileTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    private static final Object HOST = new Object();

    /** A tab host, its container and the profile panel, standing in for Instagram's. */
    private static final class FakePanels implements SwipeToProfile.Panels {
        final Object reels = new Object();
        final Object home = new Object();
        final Object controller = new Object();
        final Object container = new Object();
        final Object panel = new Object();
        Object tab = reels;
        boolean on;
        int turns;
        RuntimeException failure;

        @Override public Object controller(Object host) { return host == HOST ? controller : null; }
        @Override public Object container(Object of) { return of == controller ? container : null; }
        @Override public Object tab(Object host) { return tab; }
        @Override public Object reelsTab() { return reels; }
        @Override public Object panel(Object of) { return of == container ? panel : null; }
        @Override public boolean panelOn(Object of) { return on; }

        @Override
        public void turn(Object of, Object which, boolean value) {
            if (failure != null) throw failure;
            assertSame(container, of);
            assertSame(panel, which);
            on = value;
            turns++;
        }
    }

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.REEL_SWIPE_TO_PROFILE.save(true);
        SwipeToProfile.tabChanging(new FakePanels());
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.REEL_SWIPE_TO_PROFILE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void withTheSwitchOnTheSwipeIsAllowed() {
        assertTrue(SwipeToProfile.allow(false));
        assertTrue(SwipeToProfile.allow(true));
        assertTrue(HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString(), HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).isEmpty());
    }

    @Test
    public void offToStartAndOffLeaveTheAnswerToInstagram() {
        Settings.REEL_SWIPE_TO_PROFILE.resetToDefault();
        assertFalse(Settings.REEL_SWIPE_TO_PROFILE.get());
        assertFalse(SwipeToProfile.allow(false));
        assertTrue(SwipeToProfile.allow(true));
        Settings.REEL_SWIPE_TO_PROFILE.save(false);
        assertFalse(SwipeToProfile.allow(false));
        assertTrue(SwipeToProfile.allow(true));
    }

    @Test
    public void pausedAndUnreadyLeaveTheAnswerToInstagram() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(SwipeToProfile.allow(false));
        assertTrue(SwipeToProfile.allow(true));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(SwipeToProfile.allow(false)));

        assertTrue(SwipeToProfile.allow(false));
    }

    @Test
    public void aThrowingSwitchLeavesTheAnswerToInstagramAndIsReported() {
        assertFalse(SwipeToProfile.allow(false, THROWS));

        String missing = HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString();
        assertTrue(missing, missing.contains("'" + SwipeToProfile.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    @Test
    public void reachingReelsTurnsThePanelOnAndTheNextTabChangeTurnsItOff() {
        FakePanels panels = new FakePanels();

        SwipeToProfile.tabChanged(HOST, panels, () -> true);
        assertTrue(panels.on);

        panels.tab = panels.home;
        SwipeToProfile.tabChanging(panels);
        assertFalse(panels.on);
        SwipeToProfile.tabChanged(HOST, panels, () -> true);
        assertFalse(panels.on);
        assertEquals(2, panels.turns);
        assertTrue(HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString(), HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).isEmpty());
    }

    /** Instagram turned it on for this account, so it stays Instagram's to turn off. */
    @Test
    public void aPanelInstagramTurnedOnIsLeftAlone() {
        FakePanels panels = new FakePanels();
        panels.on = true;

        SwipeToProfile.tabChanged(HOST, panels, () -> true);
        SwipeToProfile.tabChanging(panels);

        assertTrue(panels.on);
        assertEquals(0, panels.turns);
    }

    @Test
    public void offTurnsNothingOnButStillTurnsOffWhatItTurnedOn() {
        FakePanels panels = new FakePanels();

        SwipeToProfile.tabChanged(HOST, panels, () -> false);
        assertFalse(panels.on);

        SwipeToProfile.tabChanged(HOST, panels, () -> true);
        assertTrue(panels.on);
        SwipeToProfile.tabChanging(panels);
        assertFalse(panels.on);
        SwipeToProfile.tabChanging(panels);
        assertEquals(2, panels.turns);
    }

    @Test
    public void aThrowingTurnIsReportedAndLeavesNothingToTurnOff() {
        FakePanels panels = new FakePanels();
        panels.failure = new IllegalStateException("container went away");

        SwipeToProfile.tabChanged(HOST, panels, () -> true);

        String missing = HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString();
        assertTrue(missing, missing.contains("'" + SwipeToProfile.PANEL + "'"));
        panels.failure = null;
        SwipeToProfile.tabChanging(panels);
        assertEquals(0, panels.turns);
    }

    /** Unpatched, the stubs answer nothing and the hooks do nothing. */
    @Test
    public void unpatchedStubsLeaveTheTabsAlone() {
        SwipeToProfile.tabChanged(HOST);
        SwipeToProfile.tabChanging(HOST);
        assertTrue(HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString(), HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).isEmpty());
    }
}
