/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Watched stories go to the back of Home's tray only with views held back and the gray ring on (#241). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StoryTrayOrderTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier ON = () -> true;
    private static final BooleanSupplier OFF = () -> false;

    @After
    public void restore() {
        Settings.VIEW_STORIES_ANONYMOUSLY.resetToDefault();
        Settings.GRAY_OUT_WATCHED_STORIES.resetToDefault();
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static StoryTrayOrder.Seen seenAre(String... names) {
        Set<String> seen = new HashSet<>(Arrays.asList(names));
        return seen::contains;
    }

    @Test
    public void unseenComeFirstSeenAfterEachInServerOrder() {
        List<Object> tray = Arrays.<Object>asList("a", "b", "c", "d", "e", "f");
        List<Object> ordered = StoryTrayOrder.order(tray, ON, seenAre("a", "c", "d"));
        assertEquals(Arrays.<Object>asList("b", "e", "f", "a", "c", "d"), ordered);
        assertEquals("nothing added or dropped", new HashSet<>(tray), new HashSet<>(ordered));
        assertEquals(tray.size(), ordered.size());
    }

    @Test
    public void duplicatesAndNullsAreKept() {
        List<Object> tray = Arrays.<Object>asList("a", null, "a", "b");
        List<Object> ordered = StoryTrayOrder.order(tray, ON, seenAre("a"));
        assertEquals(Arrays.<Object>asList(null, "b", "a", "a"), ordered);
    }

    @Test
    public void anOrderedOrOneSidedTrayComesBackAsIs() {
        List<Object> sorted = Arrays.<Object>asList("b", "a");
        assertSame(sorted, StoryTrayOrder.order(sorted, ON, seenAre("a")));
        List<Object> allNew = Arrays.<Object>asList("a", "b");
        assertSame(allNew, StoryTrayOrder.order(allNew, ON, seenAre()));
        List<Object> allSeen = Arrays.<Object>asList("a", "b");
        assertSame(allSeen, StoryTrayOrder.order(allSeen, ON, seenAre("a", "b")));
    }

    @Test
    public void offLeavesTheListAlone() {
        List<Object> tray = Arrays.<Object>asList("a", "b");
        assertSame(tray, StoryTrayOrder.order(tray, OFF, seenAre("a")));
    }

    @Test
    public void aFailureLeavesTheListAlone() {
        List<Object> tray = Arrays.<Object>asList("a", "b");
        assertSame(tray, StoryTrayOrder.order(tray, ON, reel -> {
            throw new IllegalStateException("no");
        }));
        assertSame(tray, StoryTrayOrder.order(tray, () -> {
            throw new IllegalStateException("no");
        }, seenAre("a")));
    }

    @Test
    public void bothSwitchesAndAPauseDecide() {
        assertFalse("both off", StoryTrayOrder.grayedOut());
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        assertFalse("gray out off", StoryTrayOrder.grayedOut());
        Settings.GRAY_OUT_WATCHED_STORIES.save(true);
        assertTrue("both on", StoryTrayOrder.grayedOut());
        Settings.VIEW_STORIES_ANONYMOUSLY.save(false);
        assertFalse("anonymous off", StoryTrayOrder.grayedOut());
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", StoryTrayOrder.grayedOut());
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse("settings not ready", StoryTrayOrder.grayedOut()));
        assertTrue("the control: on again", StoryTrayOrder.grayedOut());
    }

    @Test
    public void theUnpatchedStubReportsNothingSeen() {
        List<Object> tray = Arrays.<Object>asList("a", "b");
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        Settings.GRAY_OUT_WATCHED_STORIES.save(true);
        assertSame(tray, StoryTrayOrder.order(tray, new Object()));
    }
}
