/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import java.util.Arrays;
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
import app.hushgram.extension.instagram.feed.CornerSize;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Rounded corners on posts: a Feed switch that starts off, with a Corner size list under it, both kept in a backup, and off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class RoundedCornersSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.ROUNDED_POST_CORNERS.resetToDefault();
        Settings.ROUNDED_CORNER_SIZE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.ROUNDED_POST_CORNERS.resetToDefault();
        Settings.ROUNDED_CORNER_SIZE.resetToDefault();
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
    @Test public void theSwitchAndItsListAreUnderFeedAfterTheZoomAndStartOff() throws Exception {
        open(EnumSet.of(PatchFamily.LONG_PRESS_ZOOM, PatchFamily.ROUNDED_POST_CORNERS));
        Preference row = page.getPreferenceScreen().findPreference(Settings.ROUNDED_POST_CORNERS.key);
        assertNotNull(row);
        assertEquals("Rounded corners on posts", row.getTitle().toString());
        assertEquals("Feed", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(Settings.ROUNDED_POST_CORNERS.get());
        PreferenceGroup feed = row.getParent();
        String[] keys = new String[feed.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = feed.getPreference(i).getKey();
        int at = Arrays.asList(keys).indexOf(Settings.ROUNDED_POST_CORNERS.key);
        // The zoom's own rows, Long press a photo to zoom and then Keep the zoom open, come right before.
        assertEquals(Arrays.toString(keys), Settings.LONG_PRESS_TO_ZOOM.key, keys[at - 2]);
        assertEquals(Arrays.toString(keys), Settings.LONG_PRESS_ZOOM_STAYS_OPEN.key, keys[at - 1]);
        assertEquals(Arrays.toString(keys), Settings.ROUNDED_CORNER_SIZE.key, keys[at + 1]);

        HushgramPreferenceFragment.CornerSizeRow size = (HushgramPreferenceFragment.CornerSizeRow) feed.getPreference(at + 1);
        assertEquals("Corner size", size.getTitle().toString());
        assertEquals(Arrays.asList("Small", "Large"), Arrays.asList(size.getEntries()[0].toString(), size.getEntries()[1].toString()));
        assertEquals("SMALL", size.getValue());
        assertEquals("Small", size.getSummary().toString());
        assertFalse("the list waits for the switch", size.isEnabled());
        ((android.preference.SwitchPreference) row).setChecked(true);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertTrue(size.isEnabled());
        size.setValue("LARGE");
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(CornerSize.LARGE, Settings.ROUNDED_CORNER_SIZE.savedValue());
        assertEquals("Large", size.getSummary().toString());

        assertEquals("Rounded post corners", PatchFamily.ROUNDED_POST_CORNERS.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.ROUNDED_POST_CORNERS.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.ROUNDED_CORNER_SIZE.key));
    }
    @Test public void theSwitchAloneStillMakesTheFeedSection() throws Exception {
        open(EnumSet.of(PatchFamily.ROUNDED_POST_CORNERS));
        Preference row = page.getPreferenceScreen().findPreference(Settings.ROUNDED_POST_CORNERS.key);
        assertNotNull(row);
        assertEquals("Feed", ((PreferenceGroup) row.getParent()).getTitle().toString());
    }
    @Test public void aSavedSwitchAnswersOffWhilePaused() throws Exception {
        open(EnumSet.of(PatchFamily.ROUNDED_POST_CORNERS));
        Settings.ROUNDED_POST_CORNERS.save(true);
        assertTrue(Settings.ROUNDED_POST_CORNERS.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.ROUNDED_POST_CORNERS.get());
        assertTrue(Settings.ROUNDED_POST_CORNERS.savedValue());
    }
    @Test public void missingPatchHasNeitherRowNorBackupKey() throws Exception {
        open(EnumSet.of(PatchFamily.LONG_PRESS_ZOOM));
        assertNull(page.getPreferenceScreen().findPreference(Settings.ROUNDED_POST_CORNERS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.ROUNDED_CORNER_SIZE.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.ROUNDED_POST_CORNERS.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.ROUNDED_CORNER_SIZE.key));
    }
}
