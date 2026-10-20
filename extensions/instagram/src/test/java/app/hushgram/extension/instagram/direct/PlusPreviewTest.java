/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

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

/** What Hide Plus preview tells a chat menu about its peek form, and when it leaves the flag to Instagram. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class PlusPreviewTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_PLUS_PREVIEW.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_PLUS_PREVIEW.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void withTheSwitchOnThePeekFormIsOff() {
        assertFalse(PlusPreview.peek(1));
        assertTrue(HookStatus.missing(FamilyNames.PLUS_PREVIEW).toString(), HookStatus.missing(FamilyNames.PLUS_PREVIEW).isEmpty());
    }

    @Test
    public void aMenuInstagramGivesNoPeekFormStaysPlain() {
        assertFalse(PlusPreview.peek(0));
        Settings.HIDE_PLUS_PREVIEW.save(false);
        assertFalse(PlusPreview.peek(0));
    }

    @Test
    public void offToStartAndOffLeaveTheFlagToInstagram() {
        Settings.HIDE_PLUS_PREVIEW.resetToDefault();
        assertFalse(Settings.HIDE_PLUS_PREVIEW.get());
        assertTrue(PlusPreview.peek(1));
        Settings.HIDE_PLUS_PREVIEW.save(false);
        assertTrue(PlusPreview.peek(1));
    }

    @Test
    public void pausedAndUnreadyLeaveTheFlagToInstagram() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue(PlusPreview.peek(1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertTrue(PlusPreview.peek(1)));

        assertFalse(PlusPreview.peek(1));
    }

    @Test
    public void aThrowingSwitchLeavesTheFlagToInstagramAndIsReported() {
        assertTrue(PlusPreview.peek(1, THROWS));

        String missing = HookStatus.missing(FamilyNames.PLUS_PREVIEW).toString();
        assertTrue(missing, missing.contains("'" + PlusPreview.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
