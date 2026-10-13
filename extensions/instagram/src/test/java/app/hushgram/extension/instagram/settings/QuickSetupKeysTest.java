/*
 * Copyright 2026 HushPinterest contributors
 * Taken from HushPinterest's
 * extensions/pinterest/src/test/java/app/hushpinterest/extension/pinterest/settings/QuickSetupKeysTest.java
 * (https://github.com/SysAdminDoc/HushPinterest, commit 44fb93384a319d8e5d324f51b416efdc3d3147e6).
 * Modified for HushGram (Instagram), 2026: the lists are checked against the switches a settings
 * file carries, and a choice is applied to a page.
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.Preference;
import android.preference.SwitchPreference;
import android.preference.TwoStatePreference;

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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.shared.settings.Setting;
import app.hushgram.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.hushgram.extension.shared.settings.preference.QuickSetups;

/**
 * Quick setups' lists name real switches, none twice, and only switches a settings file carries;
 * a choice changes the switches a category page keeps off the screen too, and says how many.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class QuickSetupKeysTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        ShadowToast.reset();
    }

    @After
    public void restore() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        for (BooleanSetting setting : QuickSetupKeys.QUIET) setting.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        SettingsEntry.onClosedByUser();
    }

    @Test
    public void everyKeyIsASwitchInSettingsAndNoListIsEmpty() throws Exception {
        Set<BooleanSetting> declared = new HashSet<>();
        for (Field field : Settings.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && Modifier.isPublic(field.getModifiers())
                    && field.getType() == BooleanSetting.class) {
                declared.add((BooleanSetting) field.get(null));
            }
        }
        assertTrue("found too few switches to compare against: " + declared.size(), declared.size() > 100);
        Set<String> carried = ConfigurationBackup.eligible().keySet();
        for (List<BooleanSetting> list : Arrays.asList(QuickSetupKeys.QUIET, QuickSetupKeys.PRIVACY,
                QuickSetupKeys.defaultSwitches())) {
            assertFalse(list.isEmpty());
            assertEquals("a switch is listed twice: " + list, list.size(), new HashSet<>(list).size());
            for (BooleanSetting setting : list) {
                assertTrue(setting.key + " isn't one of Settings' switches",
                        declared.contains(setting) || setting == BaseSettings.DEBUG);
                assertSame(setting, Setting.getSettingFromPath(setting.key));
                assertTrue(setting.key + " isn't a switch a settings file carries", carried.contains(setting.key));
            }
        }
        for (Map<BooleanSetting, Boolean> choice : Arrays.asList(QuickSetupKeys.quiet(), QuickSetupKeys.privacy())) {
            for (Boolean value : choice.values()) assertTrue(value);
        }
        Map<BooleanSetting, Boolean> defaults = QuickSetupKeys.defaults();
        for (Map.Entry<BooleanSetting, Boolean> value : defaults.entrySet()) {
            assertEquals(value.getKey().key, value.getKey().defaultValue, value.getValue());
        }
        // The locks, Pause, Debug logging and the category view are nobody's quick setup.
        for (BooleanSetting kept : Arrays.asList(Settings.LOCK_MESSAGES, Settings.LOCK_APP, BaseSettings.PAUSED,
                BaseSettings.DEBUG, Settings.CATEGORY_PAGES)) {
            assertFalse(kept.key, defaults.containsKey(kept));
        }
    }

    @Test
    public void quietTurnsOnSwitchesOffTheScreenAndSaysHowMany() throws Exception {
        Settings.CATEGORY_PAGES.save(true);
        for (BooleanSetting setting : QuickSetupKeys.QUIET) setting.save(false);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertNull("Hide ads is on a category's page, not the home",
                    page.getPreferenceScreen().findPreference(Settings.HIDE_ADS.key));

            int changed = page.applyQuickSetup(QuickSetupKeys.quiet());
            ShadowLooper.idleMainLooper();
            assertEquals(QuickSetupKeys.QUIET.size(), changed);
            for (BooleanSetting setting : QuickSetupKeys.QUIET) {
                assertTrue(setting.key, setting.savedValue());
                assertTrue(setting.key, ((TwoStatePreference) page.findPreference(setting.key)).isChecked());
            }
            assertEquals("Changed " + changed + " settings", ShadowToast.getTextOfLatestToast());

            // Again, nothing is left to change, and it says so.
            assertEquals(0, page.applyQuickSetup(QuickSetupKeys.quiet()));
            assertEquals("Changed 0 settings", ShadowToast.getTextOfLatestToast());
        }
    }

    /** A row whose change listener says no keeps what it shows, as a tap on it would, and isn't counted. */
    @Test
    public void aRowThatRefusesTheChangeIsLeftAsItIs() {
        SwitchPreference refusing = new SwitchPreference(RuntimeEnvironment.getApplication());
        refusing.setOnPreferenceChangeListener((row, value) -> false);
        List<Object> heard = new ArrayList<>();
        SwitchPreference asked = new SwitchPreference(RuntimeEnvironment.getApplication());
        asked.setOnPreferenceChangeListener((row, value) -> heard.add(value));
        SwitchPreference plain = new SwitchPreference(RuntimeEnvironment.getApplication());
        Map<String, Preference> rows = new HashMap<>();
        rows.put(Settings.HIDE_ADS.key, refusing);
        rows.put(Settings.HIDE_INBOX_SUGGESTIONS.key, asked);
        rows.put(Settings.HIDE_HOME_CREATE_BUTTON.key, plain);

        assertEquals(2, QuickSetups.apply(rows::get, QuickSetups.allOn(Arrays.asList(
                Settings.HIDE_ADS, Settings.HIDE_INBOX_SUGGESTIONS, Settings.HIDE_HOME_CREATE_BUTTON))));
        assertFalse(refusing.isChecked());
        assertTrue(asked.isChecked());
        assertEquals(Arrays.asList((Object) Boolean.TRUE), heard);
        assertTrue(plain.isChecked());
    }

    /** A choice that flips several switches needing a restart says so once, not once a switch. */
    @Test
    public void severalRestartsAreAnnouncedOnce() throws Exception {
        // Quiet has one such switch on 450, so the choice here is three of them.
        List<BooleanSetting> restarting = Arrays.asList(Settings.HIDE_INBOX_SUGGESTIONS,
                Settings.HIDE_HOME_CREATE_BUTTON, Settings.HIDE_HOME_NOTIFICATIONS_BUTTON);
        for (BooleanSetting setting : restarting) {
            assertTrue(setting.key, setting.rebootApp);
            setting.save(false);
        }
        List<CharSequence> notes = new ArrayList<>();
        AbstractPreferenceFragment.RestartFeedbackPresenter counting = (context, message) -> notes.add(message);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            AbstractPreferenceFragment.setRestartFeedbackPresenter(counting);

            assertEquals(restarting.size(), page.applyQuickSetup(QuickSetups.allOn(restarting)));
            ShadowLooper.idleMainLooper();
            for (BooleanSetting setting : restarting) {
                assertTrue(setting.key, AbstractPreferenceFragment.restartPending.contains(setting.key));
            }
            assertEquals(notes.toString(), 1, notes.size());

            // Held only for the choice: a switch changed on its own after it says so at once.
            AbstractPreferenceFragment.showRestartDialog(controller.get());
            assertEquals(notes.toString(), 2, notes.size());
        } finally {
            AbstractPreferenceFragment.clearRestartFeedbackPresenter(counting);
            AbstractPreferenceFragment.restartPending.clear();
            for (BooleanSetting setting : restarting) setting.resetToDefault();
        }
    }
}
