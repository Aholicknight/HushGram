/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.preference.ListPreference;
import android.preference.PreferenceGroup;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.EnumSet;

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
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import app.hushgram.extension.instagram.misc.FontChoice;
import app.hushgram.extension.instagram.misc.FontsForTests;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Font row: under Layout, Instagram's font to start, and a file chosen only once it loads as a font. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class FontSettingsTest {
    private static final Uri DOCUMENT = Uri.parse("content://test-documents/font.ttf");
    private static final byte[] TRUE_TYPE = {0x00, 0x01, 0x00, 0x00, 0x00, 0x0c, 0x00, (byte) 0x80};

    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.FONT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        FontsForTests.reset();
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.FONT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        FontsForTests.reset();
        File kept = FontsForTests.kept();
        if (kept.exists()) kept.delete();
    }

    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    private ListPreference row() {
        return (ListPreference) page.getPreferenceScreen().findPreference(Settings.FONT.key);
    }

    @Test public void missingPatchHasNoFontRow() throws Exception {
        open(EnumSet.of(PatchFamily.EMOJI_STYLE));
        assertNull(row());
    }

    /** The row alone opens Layout, first there, with the three choices and Instagram's own to start. */
    @Test public void theRowStartsOnInstagramsFontUnderLayout() throws Exception {
        open(EnumSet.of(PatchFamily.FONT));
        ListPreference row = row();
        assertNotNull(row);
        PreferenceGroup layout = row.getParent();
        assertEquals("Layout", layout.getTitle().toString());
        assertEquals(Settings.FONT.key, layout.getPreference(0).getKey());
        assertEquals("Font", row.getTitle().toString());
        assertEquals("Instagram's font", row.getEntries()[0].toString());
        assertEquals("Your phone's font", row.getEntries()[1].toString());
        assertEquals("A font file", row.getEntries()[2].toString());
        assertEquals(FontChoice.INSTAGRAM.name(), row.getValue());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("Instagram's own font."));
        assertEquals("Font", PatchFamily.FONT.patchName);
        assertTrue(PatchFamily.FONT.switches.isEmpty());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.FONT.key));
    }

    /** With its neighbors, the row sits after Remove the empty space at the bottom and before the emoji switch. */
    @Test public void theRowSitsBeforeTheEmojiSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.BOTTOM_SPACE, PatchFamily.FONT, PatchFamily.EMOJI_STYLE));
        PreferenceGroup layout = row().getParent();
        String[] keys = new String[layout.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = layout.getPreference(i).getKey();
        assertArrayEquals(new String[] {Settings.REMOVE_BOTTOM_SPACE.key, Settings.FONT.key, Settings.NOTO_EMOJI.key}, keys);
    }

    /** The phone's font is an ordinary choice. Paused, Instagram's own answers and the saved choice stays. */
    @Test public void thePhonesFontIsAChoiceThatPauseSetsAside() throws Exception {
        open(EnumSet.of(PatchFamily.FONT));
        ListPreference row = row();
        assertTrue(row.getOnPreferenceChangeListener().onPreferenceChange(row, FontChoice.SYSTEM.name()));
        row.setValue(FontChoice.SYSTEM.name());
        ShadowLooper.idleMainLooper();
        assertEquals(FontChoice.SYSTEM, Settings.FONT.savedValue());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("your phone's font"));
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(FontChoice.INSTAGRAM, Settings.FONT.get());
        assertEquals(FontChoice.SYSTEM, Settings.FONT.savedValue());
    }

    /** A font file opens the picker and changes nothing until a file that loads comes back. */
    @Test public void aFontFileIsChosenOnlyOnceItLoads() throws Exception {
        FontsForTests.loadEveryFile(Typeface.SERIF);
        open(EnumSet.of(PatchFamily.FONT));
        ListPreference row = row();

        assertFalse(row.getOnPreferenceChangeListener().onPreferenceChange(row, FontChoice.FILE.name()));
        ShadowActivity.IntentForResult picked = Shadows.shadowOf(controller.get()).getNextStartedActivityForResult();
        assertNotNull(picked);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picked.intent.getAction());
        assertTrue(picked.intent.hasCategory(Intent.CATEGORY_OPENABLE));
        assertEquals(FontChoice.INSTAGRAM, Settings.FONT.savedValue());

        Shadows.shadowOf(controller.get().getContentResolver()).registerInputStream(DOCUMENT,
                new ByteArrayInputStream("not a font".getBytes()));
        Shadows.shadowOf(controller.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(DOCUMENT));
        finish();
        assertEquals(FontChoice.INSTAGRAM, Settings.FONT.savedValue());
        assertEquals(FontChoice.INSTAGRAM.name(), row.getValue());
        assertEquals("That file isn't a font HushGram can use. Your font hasn't changed.", ShadowToast.getTextOfLatestToast());
        assertFalse(FontsForTests.kept().exists());

        row.getOnPreferenceChangeListener().onPreferenceChange(row, FontChoice.FILE.name());
        picked = Shadows.shadowOf(controller.get()).getNextStartedActivityForResult();
        Shadows.shadowOf(controller.get().getContentResolver()).registerInputStream(DOCUMENT, new ByteArrayInputStream(TRUE_TYPE));
        Shadows.shadowOf(controller.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(DOCUMENT));
        finish();
        assertEquals(FontChoice.FILE, Settings.FONT.savedValue());
        assertEquals(FontChoice.FILE.name(), row.getValue());
        assertArrayEquals(TRUE_TYPE, Files.readAllBytes(FontsForTests.kept().toPath()));
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("font file you picked"));
    }

    /** A canceled picker changes nothing and says nothing. */
    @Test public void aCanceledPickerChangesNothing() throws Exception {
        open(EnumSet.of(PatchFamily.FONT));
        ListPreference row = row();
        row.getOnPreferenceChangeListener().onPreferenceChange(row, FontChoice.FILE.name());
        ShadowActivity.IntentForResult picked = Shadows.shadowOf(controller.get()).getNextStartedActivityForResult();
        Shadows.shadowOf(controller.get()).receiveResult(picked.intent, Activity.RESULT_CANCELED, null);
        finish();
        assertEquals(FontChoice.INSTAGRAM, Settings.FONT.savedValue());
        assertNull(ShadowToast.getTextOfLatestToast());
    }

    private static void finish() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }
}
