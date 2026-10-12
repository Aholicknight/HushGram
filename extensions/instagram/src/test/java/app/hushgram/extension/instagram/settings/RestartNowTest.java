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
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.preference.Preference;
import android.preference.TwoStatePreference;

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowToast;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.preference.ExportStatus;

/**
 * #146: a change Instagram only reads as it starts turns the status card into Restart now, which
 * opens Instagram again in a fresh task and ends the process. Only on a tap, and not while a save
 * or an export would be cut off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class RestartNowTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private final AtomicInteger ended = new AtomicInteger();
    private ComponentName launcher;

    @Before
    public void prepare() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.FOLLOWING_FEED);
        RestartNow.endProcessForTests = ended::incrementAndGet;
        launcher = new ComponentName(RuntimeEnvironment.getApplication().getPackageName(),
                "com.instagram.mainactivity.LauncherActivity");
        IntentFilter main = new IntentFilter(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        ShadowPackageManager packages = Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager());
        packages.addActivityIfNotPresent(launcher);
        packages.addIntentFilterForActivity(launcher, main);
        ShadowToast.reset();
    }

    @After
    public void restore() {
        RestartNow.endProcessForTests = null;
        PatchFamily.inBuildForTests = null;
        Settings.START_ON_FOLLOWING.resetToDefault();
    }

    @Test
    public void aChangeThatWaitsOnARestartMakesTheCardRestartInstagram() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            Preference card = page.getPreferenceScreen().getPreference(0);
            assertEquals("HushGram is on", String.valueOf(card.getTitle()));
            assertFalse("nothing owed, so the card only reports", card.isSelectable());

            TwoStatePreference following = (TwoStatePreference) page.findPreference(Settings.START_ON_FOLLOWING.key);
            following.setChecked(!following.isChecked());
            ShadowLooper.idleMainLooper();
            String summary = String.valueOf(card.getSummary());
            assertTrue(summary, summary.endsWith("\n\nSome changes apply when Instagram restarts. Tap to restart it now."));
            assertTrue(card.isSelectable());
            assertEquals("nothing restarts without the tap", 0, ended.get());

            assertTrue(card.getOnPreferenceClickListener().onPreferenceClick(card));
            Intent started = Shadows.shadowOf(controller.get()).getNextStartedActivity();
            assertNotNull("Instagram wasn't opened again", started);
            assertEquals(launcher, started.getComponent());
            assertEquals(launcher.getPackageName(), started.getPackage());
            assertTrue("a fresh task", (started.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0);
            assertEquals(1, ended.get());

            // Back where it started, nothing is owed and the card only reports again.
            following.setChecked(!following.isChecked());
            ShadowLooper.idleMainLooper();
            assertFalse(card.isSelectable());
            assertFalse(String.valueOf(card.getSummary()).contains("restart it now"));
        }
    }

    @Test
    public void aRunningExportKeepsInstagramUpAndSaysSo() {
        String token = ExportStatus.CONFIGURATION.begin("Exporting", false);
        assertNotNull(token);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            TwoStatePreference following = (TwoStatePreference) page.findPreference(Settings.START_ON_FOLLOWING.key);
            following.setChecked(!following.isChecked());
            ShadowLooper.idleMainLooper();
            Preference card = page.getPreferenceScreen().getPreference(0);
            assertTrue(card.getOnPreferenceClickListener().onPreferenceClick(card));

            assertNull("Instagram was opened again mid-export", Shadows.shadowOf(controller.get()).getNextStartedActivity());
            assertEquals(0, ended.get());
            assertEquals("A save or an export is still running. Restart once it's done.", ShadowToast.getTextOfLatestToast());
        } finally {
            ExportStatus.CONFIGURATION.finish(token, "done");
        }
    }
}
