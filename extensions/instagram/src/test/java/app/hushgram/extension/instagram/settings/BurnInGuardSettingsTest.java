/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
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
import org.robolectric.shadows.ShadowLooper;
import app.hushgram.extension.instagram.reels.BurnInMode;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Burn-in guard (#162) is a list after Clean up Reels' switches under Reels, starts off, says what each choice does and is backed up. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class BurnInGuardSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.BURN_IN_GUARD.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.BURN_IN_GUARD.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void itFollowsCleanUpReelsUnderReelsAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_DECLUTTER));
        Preference found = page.getPreferenceScreen().findPreference(Settings.BURN_IN_GUARD.key);
        assertTrue(found instanceof HushgramPreferenceFragment.BurnInGuardRow);
        ListPreference row = (ListPreference) found;
        assertEquals("Burn-in guard", row.getTitle().toString());
        PreferenceGroup reels = (PreferenceGroup) row.getParent();
        assertEquals("Reels", reels.getTitle().toString());
        int save = -1;
        int guard = -1;
        for (int i = 0; i < reels.getPreferenceCount(); i++) {
            String key = reels.getPreference(i).getKey();
            if (Settings.HIDE_REEL_SAVE_BUTTON.key.equals(key)) save = i;
            if (Settings.BURN_IN_GUARD.key.equals(key)) guard = i;
        }
        assertEquals("right after Hide the Save button on reels", save + 1, guard);

        List<String> entries = new ArrayList<>();
        for (CharSequence entry : row.getEntries()) entries.add(entry.toString());
        assertEquals(Arrays.asList("Off", "Dim when idle", "Dim when idle and shift"), entries);
        assertEquals(BurnInMode.OFF, Settings.BURN_IN_GUARD.get());
        assertEquals("OFF", row.getValue());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("Off."));

        row.setValue("DIM_AND_SHIFT");
        ShadowLooper.idleMainLooper();
        assertEquals(BurnInMode.DIM_AND_SHIFT, Settings.BURN_IN_GUARD.savedValue());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("every 2 minutes"));
        row.setValue("DIM");
        assertFalse(row.getSummary().toString(), row.getSummary().toString().contains("every 2 minutes"));
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("5 seconds"));

        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.BURN_IN_GUARD.key));
    }
    @Test public void withoutCleanUpReelsThereIsNoRowAndNothingToBackUp() throws Exception {
        open(EnumSet.noneOf(PatchFamily.class));
        assertNull(page.getPreferenceScreen().findPreference(Settings.BURN_IN_GUARD.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.BURN_IN_GUARD.key));
    }
}
