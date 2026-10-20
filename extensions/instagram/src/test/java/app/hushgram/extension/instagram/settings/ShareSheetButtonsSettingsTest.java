/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.EnumSet;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import app.hushgram.extension.shared.SettingsContextRule;

/** The share sheet's button choice rides in a backup only in a build with the share sheet patch (#252). */
@RunWith(RobolectricTestRunner.class)
public class ShareSheetButtonsSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void putBack() {
        PatchFamily.inBuildForTests = null;
        Settings.HIDDEN_SHARE_SHEET_BUTTONS.resetToDefault();
    }

    @Test public void everyButtonShowsToStart() {
        assertEquals("", Settings.HIDDEN_SHARE_SHEET_BUTTONS.get());
        assertTrue(PatchFamily.SHARE_SHEET.choices().contains(Settings.HIDDEN_SHARE_SHEET_BUTTONS));
    }

    @Test public void theChoiceIsInABackupOnlyWithThePatch() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SHARE_SHEET);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDDEN_SHARE_SHEET_BUTTONS.key));
        PatchFamily.inBuildForTests = EnumSet.noneOf(PatchFamily.class);
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.HIDDEN_SHARE_SHEET_BUTTONS.key));
    }

    @Test public void onlyAKnownListIsTakenFromABackup() {
        assertTrue(ConfigurationBackup.textAllowed(Settings.HIDDEN_SHARE_SHEET_BUTTONS, "add_to_your_story,whatsapp"));
        assertTrue(ConfigurationBackup.textAllowed(Settings.HIDDEN_SHARE_SHEET_BUTTONS, ""));
        assertFalse(ConfigurationBackup.textAllowed(Settings.HIDDEN_SHARE_SHEET_BUTTONS, "whatsapp,nonsense"));
        assertFalse(ConfigurationBackup.textAllowed(Settings.HIDDEN_SHARE_SHEET_BUTTONS, "whatsapp,add_to_your_story"));
    }
}
