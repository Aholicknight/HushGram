/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.Preference;
import android.widget.ListAdapter;
import android.widget.ListView;

import org.json.JSONObject;
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
import org.robolectric.shadows.ShadowToast;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * A long press on Quick setups takes the row off the settings home, and the Quick setups on the
 * home page row on Settings backup brings it back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class HideQuickSetupsSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        Settings.CATEGORY_PAGES.save(true);
        Settings.HIDE_QUICK_SETUPS.resetToDefault();
        ShadowToast.reset();
    }

    @After public void restore() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_QUICK_SETUPS.resetToDefault();
        Settings.CATEGORY_PAGES.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        for (BooleanSetting setting : QuickSetupKeys.QUIET) setting.resetToDefault();
        PauseForTests.resume();
        SettingsEntry.onClosedByUser();
    }

    private HushgramPreferenceFragment open(ActivityController<Activity> controller) throws Exception {
        HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        return page;
    }

    private static Preference quick(HushgramPreferenceFragment page) {
        return page.getPreferenceScreen().findPreference(HushgramPreferenceFragment.QUICK_SETUPS_ROW_KEY);
    }

    /** What a long press on the row with this key does, or null when the home has no such row. */
    private static Boolean longPress(HushgramPreferenceFragment page, String key) {
        ListView list = page.getView().findViewById(android.R.id.list);
        ListAdapter shown = list.getAdapter();
        for (int i = 0; i < shown.getCount(); i++) {
            Object item = shown.getItem(i);
            if (item instanceof Preference && key.equals(((Preference) item).getKey())) {
                boolean took = list.getOnItemLongClickListener().onItemLongClick(list, null, i, shown.getItemId(i));
                ShadowLooper.idleMainLooper();
                return took;
            }
        }
        return null;
    }

    @Test public void theHomeRowSaysToPressAndHold() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = open(controller);
            Preference quick = quick(page);
            assertNotNull(quick);
            assertEquals("Turn on a set of switches in one tap, or put every switch back the way it started."
                    + " Press and hold to hide this row.", String.valueOf(quick.getSummary()));
        }
    }

    @Test public void aLongPressHidesTheRowStoresTheFlagAndSaysWhereItWent() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = open(controller);
            assertEquals("another row took the long press", Boolean.FALSE,
                    longPress(page, HushgramPreferenceFragment.REPORT_ROW_KEY));
            assertFalse(Settings.HIDE_QUICK_SETUPS.savedValue());
            assertNotNull(quick(page));

            assertEquals(Boolean.TRUE, longPress(page, HushgramPreferenceFragment.QUICK_SETUPS_ROW_KEY));
            assertTrue(Settings.HIDE_QUICK_SETUPS.savedValue());
            assertNull("the row is still on the home", quick(page));
            assertEquals("Quick setups hidden. Bring it back from Settings backup.", ShadowToast.getTextOfLatestToast());
            // A search doesn't bring it back either.
            page.searchSettings("quick setups");
            ShadowLooper.idleMainLooper();
            assertNull(quick(page));
        }
    }

    @Test public void aHiddenRowStaysOffTheNextTimeSettingsOpen() throws Exception {
        Settings.HIDE_QUICK_SETUPS.save(true);
        PauseForTests.pause(app.hushgram.extension.shared.settings.HushgramPause.Reason.SWITCH);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = open(controller);
            assertNull("a pause brought the row back", quick(page));
        }
    }

    @Test public void theRestoreRowOnSettingsBackupBringsItBackAndHidesItAgain() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = open(controller);
            page.searchSettings("on the home page");
            ShadowLooper.idleMainLooper();
            Preference restore = page.getPreferenceScreen().findPreference(HushgramPreferenceFragment.RESTORE_QUICK_SETUPS_ROW_KEY);
            assertNotNull("search lost the restore row", restore);
            assertEquals("Quick setups on the home page", String.valueOf(restore.getTitle()));
            assertEquals("Settings backup", String.valueOf(restore.getParent().getTitle()));
            assertEquals("Shown. Tap to hide it.", String.valueOf(restore.getSummary()));
            assertFalse("the row stores nothing of its own", restore.isPersistent());

            page.searchSettings("");
            ShadowLooper.idleMainLooper();
            longPress(page, HushgramPreferenceFragment.QUICK_SETUPS_ROW_KEY);
            assertEquals("Hidden. Tap to show it again.", String.valueOf(restore.getSummary()));

            page.searchSettings("on the home page");
            ShadowLooper.idleMainLooper();
            assertTrue(restore.getOnPreferenceClickListener().onPreferenceClick(restore));
            assertEquals("Shown. Tap to hide it.", String.valueOf(restore.getSummary()));
            assertFalse(Settings.HIDE_QUICK_SETUPS.savedValue());
            page.searchSettings("");
            ShadowLooper.idleMainLooper();
            assertNotNull("the home didn't get its row back", quick(page));

            page.searchSettings("on the home page");
            ShadowLooper.idleMainLooper();
            assertTrue(restore.getOnPreferenceClickListener().onPreferenceClick(restore));
            assertEquals("Hidden. Tap to show it again.", String.valueOf(restore.getSummary()));
            assertTrue(Settings.HIDE_QUICK_SETUPS.savedValue());
            page.searchSettings("");
            ShadowLooper.idleMainLooper();
            assertNull(quick(page));
        }
    }

    @Test public void theFlagStaysOutOfSettingsFilesAndQuickSetups() throws Exception {
        Settings.HIDE_QUICK_SETUPS.save(true);
        JSONObject values = new JSONObject(new String(ConfigurationBackup.export(), StandardCharsets.UTF_8)).getJSONObject("settings");
        assertFalse(values.has(Settings.HIDE_QUICK_SETUPS.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.HIDE_QUICK_SETUPS.key));
        for (Map<BooleanSetting, Boolean> preset : Arrays.asList(
                QuickSetupKeys.quiet(), QuickSetupKeys.privacy(), QuickSetupKeys.defaults())) {
            assertFalse(preset.containsKey(Settings.HIDE_QUICK_SETUPS));
        }
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = open(controller);
            page.applyQuickSetup(QuickSetupKeys.defaults());
            ShadowLooper.idleMainLooper();
            assertTrue(Settings.HIDE_QUICK_SETUPS.savedValue());
        }
    }
}
