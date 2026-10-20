/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.SwitchPreference;
import java.util.EnumSet;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hide Plus preview's switch: under Messages, off to start, off while paused, applied with no restart. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class PlusPreviewSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.HIDE_PLUS_PREVIEW.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_PLUS_PREVIEW.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.NOTES_ROW));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_PLUS_PREVIEW.key));
    }
    @Test public void aloneItStillGetsMessages() throws Exception {
        open(EnumSet.of(PatchFamily.PLUS_PREVIEW));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_PLUS_PREVIEW.key);
        assertNotNull(row);
        assertEquals("Messages", row.getParent().getTitle().toString());
    }
    @Test public void switchStartsOffUnderMessagesAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.NOTES_ROW, PatchFamily.INSTANTS, PatchFamily.PLUS_PREVIEW));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_PLUS_PREVIEW.key);
        assertNotNull(row);
        assertEquals("Hide See preview and Plus in chat menus", row.getTitle().toString());
        assertEquals("Messages", row.getParent().getTitle().toString());
        assertFalse(row.isChecked());
        assertFalse(Settings.HIDE_PLUS_PREVIEW.get());
        assertFalse("read each time a menu opens, so no restart", Settings.HIDE_PLUS_PREVIEW.rebootApp);
        assertEquals(java.util.Collections.singletonList(Settings.HIDE_PLUS_PREVIEW), PatchFamily.PLUS_PREVIEW.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_PLUS_PREVIEW.key));

        Settings.HIDE_PLUS_PREVIEW.save(true);
        assertTrue(Settings.HIDE_PLUS_PREVIEW.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.HIDE_PLUS_PREVIEW.get());
        assertTrue(Settings.HIDE_PLUS_PREVIEW.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.HIDE_PLUS_PREVIEW.get());
    }
}
