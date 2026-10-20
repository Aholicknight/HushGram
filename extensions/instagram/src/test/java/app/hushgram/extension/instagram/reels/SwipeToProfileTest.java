/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertFalse;
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

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.REEL_SWIPE_TO_PROFILE.save(true);
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
}
