/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
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
        final Set<Object> pagers = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<Object, Boolean> inputs = new IdentityHashMap<>();
        boolean stockAnswer;
        int stockWalks;

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

        @Override
        public boolean stockScrolls(View view, int dx, int x, int y, boolean checkView, boolean skipOff) {
            stockWalks++;
            return stockAnswer;
        }

        @Override
        public boolean pager(Object view) {
            if (pagerFailure != null) throw pagerFailure;
            return view != null && pagers.contains(view);
        }

        @Override public boolean inputOn(Object pager) { return !Boolean.FALSE.equals(inputs.get(pager)); }
        @Override public void input(Object pager, boolean value) { inputs.put(pager, value); }

        RuntimeException pagerFailure;

        /** A view group standing in for a sideways pager, filling its parent. */
        FrameLayout addPager(ViewGroup parent) {
            FrameLayout pager = add(parent, new SidewaysGroup(parent.getContext()), 0, 100);
            pagers.add(pager);
            return pager;
        }

        /** A view group standing in for an up-and-down pager like the reels' own, filling its parent. */
        FrameLayout addUprightPager(ViewGroup parent) {
            FrameLayout pager = add(parent, new FrameLayout(parent.getContext()), 0, 100);
            pagers.add(pager);
            return pager;
        }
    }

    /** A view that can always scroll sideways, like a pager's list or a carousel. */
    private static final class Sideways extends View {
        Sideways(Context context) {
            super(context);
        }

        @Override
        public boolean canScrollHorizontally(int direction) {
            return true;
        }
    }

    /** A view group that can always scroll sideways, like a sideways pager. */
    private static final class SidewaysGroup extends FrameLayout {
        SidewaysGroup(Context context) {
            super(context);
        }

        @Override
        public boolean canScrollHorizontally(int direction) {
            return true;
        }
    }

    /** Adds [child] to [parent], laid out from [left] to [right] across and the full 100 down. */
    private static <T extends View> T add(ViewGroup parent, T child, int left, int right) {
        parent.addView(child);
        child.layout(left, 0, right, 100);
        return child;
    }

    private static FrameLayout container() {
        FrameLayout container = new FrameLayout(RuntimeEnvironment.getApplication());
        container.layout(0, 0, 100, 100);
        return container;
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
        assertTrue(SwipeToProfile.allow(0));
        assertTrue(SwipeToProfile.allow(1));
        assertTrue(HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString(), HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).isEmpty());
    }

    @Test
    public void offToStartAndOffLeaveTheAnswerToInstagram() {
        Settings.REEL_SWIPE_TO_PROFILE.resetToDefault();
        assertFalse(Settings.REEL_SWIPE_TO_PROFILE.get());
        assertFalse(SwipeToProfile.allow(0));
        assertTrue(SwipeToProfile.allow(1));
        Settings.REEL_SWIPE_TO_PROFILE.save(false);
        assertFalse(SwipeToProfile.allow(0));
        assertTrue(SwipeToProfile.allow(1));
    }

    @Test
    public void pausedAndUnreadyLeaveTheAnswerToInstagram() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(SwipeToProfile.allow(0));
        assertTrue(SwipeToProfile.allow(1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(SwipeToProfile.allow(0)));

        assertTrue(SwipeToProfile.allow(0));
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
        SwipeToProfile.pagerInput(new Object(), 1);
        assertFalse(SwipeToProfile.childScrolls(container(), 10, 50, 50, 0, 0));
        assertTrue(HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString(), HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).isEmpty());
    }

    /** The tabs' pager and the list inside it can scroll, but with the switch on the swipe stays the container's. */
    @Test
    public void withTheSwitchOnThePagerAndItsListAreWalkedPastAndThePagerIsHeld() {
        FakePanels panels = new FakePanels();
        FrameLayout container = container();
        FrameLayout pager = panels.addPager(container);
        add(pager, new Sideways(container.getContext()), 0, 100);

        assertFalse(SwipeToProfile.childScrolls(container, 10, 50, 50, false, true, panels, () -> true));

        assertEquals(Boolean.FALSE, panels.inputs.get(pager));
        assertEquals(0, panels.stockWalks);
        SwipeToProfile.tabChanging(panels);
        assertEquals(Boolean.TRUE, panels.inputs.get(pager));
        assertTrue(HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString(), HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).isEmpty());
    }

    /** Inside a pager's page, a carousel under the finger still takes the swipe, and the pager above it isn't held. */
    @Test
    public void aSidewaysViewInsideAPageStillTakesTheSwipe() {
        FakePanels panels = new FakePanels();
        FrameLayout container = container();
        FrameLayout pager = panels.addPager(container);
        FrameLayout list = add(pager, new FrameLayout(container.getContext()), 0, 100);
        FrameLayout page = add(list, new FrameLayout(container.getContext()), 0, 100);
        add(page, new Sideways(container.getContext()), 0, 100);

        assertTrue(SwipeToProfile.childScrolls(container, 10, 50, 50, false, true, panels, () -> true));
        assertFalse(panels.inputs.containsKey(pager));
    }

    /** Only the views under the finger count, in each one's own coordinates, and a hidden one doesn't. */
    @Test
    public void onlyVisibleViewsUnderTheFingerCount() {
        FakePanels panels = new FakePanels();
        FrameLayout container = container();
        FrameLayout half = add(container, new FrameLayout(container.getContext()), 50, 100);
        View carousel = add(half, new Sideways(container.getContext()), 10, 50);

        assertFalse(SwipeToProfile.childScrolls(container, 10, 40, 50, false, true, panels, () -> true));
        assertFalse(SwipeToProfile.childScrolls(container, 10, 55, 50, false, true, panels, () -> true));
        assertTrue(SwipeToProfile.childScrolls(container, 10, 70, 50, false, true, panels, () -> true));
        carousel.setVisibility(View.INVISIBLE);
        assertFalse(SwipeToProfile.childScrolls(container, 10, 70, 50, false, true, panels, () -> true));
    }

    /** A pager Instagram turned off stays Instagram's: not held, and not turned on at the next tab change. */
    @Test
    public void aPagerInstagramTurnedOffIsLeftAlone() {
        FakePanels panels = new FakePanels();
        FrameLayout container = container();
        FrameLayout pager = panels.addPager(container);
        panels.inputs.put(pager, false);

        assertFalse(SwipeToProfile.childScrolls(container, 10, 50, 50, false, true, panels, () -> true));
        SwipeToProfile.tabChanging(panels);

        assertEquals(Boolean.FALSE, panels.inputs.get(pager));
    }

    /** The reels' own up-and-down pager and what's inside it get Instagram's walk, and that pager is never held. */
    @Test
    public void theReelsUprightPagerGoesToInstagramsWalkAndIsNeverHeld() {
        FakePanels panels = new FakePanels();
        FrameLayout container = container();
        FrameLayout outer = panels.addPager(container);
        FrameLayout reels = panels.addUprightPager(outer);
        add(reels, new Sideways(container.getContext()), 0, 100);
        panels.stockAnswer = true;

        assertTrue(SwipeToProfile.childScrolls(container, 10, 50, 50, false, true, panels, () -> true));
        assertEquals(1, panels.stockWalks);
        assertFalse(panels.inputs.containsKey(reels));
        assertFalse(panels.inputs.containsKey(outer));

        panels.stockAnswer = false;
        assertFalse(SwipeToProfile.childScrolls(container, 10, 50, 50, false, true, panels, () -> true));
        assertFalse(panels.inputs.containsKey(reels));
        assertEquals(Boolean.FALSE, panels.inputs.get(outer));
    }

    /** A walk that throws is reported, gives a held pager back and leaves the swipe to Instagram. */
    @Test
    public void aThrowingWalkGivesHeldPagersBackAndIsReported() {
        FakePanels panels = new FakePanels();
        FrameLayout container = container();
        FrameLayout pager = panels.addPager(container);
        SwipeToProfile.childScrolls(container, 10, 50, 50, false, true, panels, () -> true);
        assertEquals(Boolean.FALSE, panels.inputs.get(pager));
        panels.pagerFailure = new IllegalStateException("boom");
        panels.stockAnswer = true;

        assertTrue(SwipeToProfile.childScrolls(container, 10, 50, 50, false, true, panels, () -> true));

        assertEquals(1, panels.stockWalks);
        assertEquals(Boolean.TRUE, panels.inputs.get(pager));
        String missing = HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString();
        assertTrue(missing, missing.contains("'" + SwipeToProfile.PAGER + "'"));
    }

    /** The walk and the pager hold are open only after a tab change to Reels, with the switch on. */
    @Test
    public void onlyATabChangeToReelsOpensTheWalk() {
        FakePanels panels = new FakePanels();
        assertFalse(SwipeToProfile.reelsOpen(() -> true));

        SwipeToProfile.tabChanged(HOST, panels, () -> false);
        assertTrue(SwipeToProfile.reelsOpen(() -> true));
        assertFalse(SwipeToProfile.reelsOpen(() -> false));

        SwipeToProfile.tabChanging(panels);
        assertFalse(SwipeToProfile.reelsOpen(() -> true));

        panels.tab = panels.home;
        SwipeToProfile.tabChanged(HOST, panels, () -> true);
        assertFalse(SwipeToProfile.reelsOpen(() -> true));
    }

    /** With the switch off Instagram walks the views, and the pagers HushGram held get their input back. */
    @Test
    public void offLeavesTheWalkToInstagramAndGivesHeldPagersBack() {
        FakePanels panels = new FakePanels();
        FrameLayout container = container();
        FrameLayout pager = panels.addPager(container);
        add(pager, new Sideways(container.getContext()), 0, 100);
        SwipeToProfile.childScrolls(container, 10, 50, 50, false, true, panels, () -> true);
        panels.stockAnswer = true;

        assertTrue(SwipeToProfile.childScrolls(container, 10, 50, 50, false, true, panels, () -> false));

        assertEquals(1, panels.stockWalks);
        assertEquals(Boolean.TRUE, panels.inputs.get(pager));
    }

    @Test
    public void aThrowingSwitchLeavesTheWalkToInstagramAndIsReported() {
        FakePanels panels = new FakePanels();
        panels.stockAnswer = true;

        assertTrue(SwipeToProfile.childScrolls(container(), 10, 50, 50, false, true, panels, THROWS));

        assertEquals(1, panels.stockWalks);
        String missing = HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString();
        assertTrue(missing, missing.contains("'" + SwipeToProfile.SWITCH + "'"));
    }

    /** The tabs' pager stays off while the switch is on, until the next tab change. */
    @Test
    public void theTabsPagerStaysOffUntilTheNextTabChange() {
        FakePanels panels = new FakePanels();
        Object pager = new Object();

        SwipeToProfile.pagerInput(pager, true, panels, () -> true);
        assertEquals(Boolean.FALSE, panels.inputs.get(pager));
        SwipeToProfile.pagerInput(pager, true, panels, () -> true);
        assertEquals(Boolean.FALSE, panels.inputs.get(pager));

        SwipeToProfile.tabChanging(panels);
        assertEquals(Boolean.TRUE, panels.inputs.get(pager));
    }

    /** What Instagram asks for goes through with the switch off, or when it turns the input off itself, which the next tab change keeps. */
    @Test
    public void offOrInstagramTurningItOffPassesThrough() {
        FakePanels panels = new FakePanels();
        Object pager = new Object();

        SwipeToProfile.pagerInput(pager, true, panels, () -> false);
        assertEquals(Boolean.TRUE, panels.inputs.get(pager));

        SwipeToProfile.pagerInput(pager, true, panels, () -> true);
        SwipeToProfile.pagerInput(pager, false, panels, () -> true);
        assertEquals(Boolean.FALSE, panels.inputs.get(pager));
        SwipeToProfile.tabChanging(panels);
        assertEquals(Boolean.FALSE, panels.inputs.get(pager));

        SwipeToProfile.pagerInput(null, true, panels, () -> true);
        assertFalse(panels.inputs.containsKey(null));
    }

    @Test
    public void aThrowingSwitchPassesThePagerInputThroughAndIsReported() {
        FakePanels panels = new FakePanels();
        Object pager = new Object();

        SwipeToProfile.pagerInput(pager, true, panels, THROWS);

        assertEquals(Boolean.TRUE, panels.inputs.get(pager));
        String missing = HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString();
        assertTrue(missing, missing.contains("'" + SwipeToProfile.SWITCH + "'"));
    }
}
