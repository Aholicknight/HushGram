/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.preference.Preference;
import android.widget.ListView;

import java.util.Collections;
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
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Tabs on profiles row: under Profiles on its own, every tab ticked to start, the grid never offered. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ProfileTabsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.HIDDEN_PROFILE_TABS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDDEN_PROFILE_TABS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.PROFILE_TABS) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    private static void tap(ListView list, int at) {
        assertTrue(list.performItemClick(list.getAdapter().getView(at, null, list), at, list.getItemIdAtPosition(at)));
        ShadowLooper.idleMainLooper();
    }

    private Preference row() {
        return page.getPreferenceScreen().findPreference(Settings.HIDDEN_PROFILE_TABS.key);
    }

    @Test public void missingPatchHasNoTabsRow() throws Exception {
        open(false);
        assertNull(row());
    }

    /** The row alone opens Profiles, offers the three tabs all ticked, and never the grid. */
    @Test public void everyTabStartsTickedAndTheGridIsNotOffered() throws Exception {
        open(true);
        Preference row = row();
        assertNotNull(row);
        assertEquals("Profiles", row.getParent().getTitle().toString());
        assertEquals("Tabs on profiles", row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("Every tab shows"));
        assertEquals(Collections.singletonList(Settings.HIDDEN_PROFILE_TABS), PatchFamily.PROFILE_TABS.choices());
        assertEquals("", Settings.HIDDEN_PROFILE_TABS.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDDEN_PROFILE_TABS.key));

        row.getOnPreferenceClickListener().onPreferenceClick(row);
        ShadowLooper.idleMainLooper();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        ListView list = dialog.getListView();
        assertEquals(3, list.getCount());
        assertEquals("Reels", list.getItemAtPosition(0).toString());
        assertEquals("Reposts", list.getItemAtPosition(1).toString());
        assertEquals("Tagged", list.getItemAtPosition(2).toString());
        for (int i = 0; i < 3; i++) assertTrue("tab " + i + " ticked", list.isItemChecked(i));
    }

    /** Unticking saves at once under the tab's key, and ticking it again puts it back. */
    @Test public void aChangeSavesAtOnceByKey() throws Exception {
        open(true);
        Preference row = row();
        row.getOnPreferenceClickListener().onPreferenceClick(row);
        ShadowLooper.idleMainLooper();
        ListView list = ShadowAlertDialog.getLatestAlertDialog().getListView();

        tap(list, 1);
        assertEquals("profile_reposts", Settings.HIDDEN_PROFILE_TABS.get());
        tap(list, 0);
        assertEquals("profile_clips,profile_reposts", Settings.HIDDEN_PROFILE_TABS.get());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("Left off profiles: "));
        tap(list, 1);
        assertEquals("profile_clips", Settings.HIDDEN_PROFILE_TABS.get());
        tap(list, 0);
        assertEquals("", Settings.HIDDEN_PROFILE_TABS.get());
    }

    /** A saved choice shows unticked, and Pause answers Instagram's own without touching the choice. */
    @Test public void aSavedChoiceShowsAndPauseKeepsIt() throws Exception {
        Settings.HIDDEN_PROFILE_TABS.save("profile_tagged_media_photos_of_you");
        open(true);
        Preference row = row();
        row.getOnPreferenceClickListener().onPreferenceClick(row);
        ShadowLooper.idleMainLooper();
        ListView list = ShadowAlertDialog.getLatestAlertDialog().getListView();
        assertTrue(list.isItemChecked(0));
        assertTrue(list.isItemChecked(1));
        assertFalse(list.isItemChecked(2));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("", Settings.HIDDEN_PROFILE_TABS.get());
        assertEquals("profile_tagged_media_photos_of_you", Settings.HIDDEN_PROFILE_TABS.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertEquals("profile_tagged_media_photos_of_you", Settings.HIDDEN_PROFILE_TABS.get());
    }
}
