/* Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import app.hushgram.extension.instagram.misc.SettingsRows;
import app.hushgram.extension.shared.SettingsContextRule;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ConfigurationSelectionBackupTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before public void prepare() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SETTINGS_ROWS);
        ConfigurationBackup.forgetUndo();
        Settings.HIDDEN_SETTINGS_ROWS.resetToDefault();
    }

    @After public void restore() {
        ConfigurationBackup.forgetUndo();
        Settings.HIDDEN_SETTINGS_ROWS.resetToDefault();
        PatchFamily.inBuildForTests = null;
    }

    @Test public void everyCurrentNativeRowChoiceSurvivesExportRestoreAndUndo() throws Exception {
        JSONArray ids;
        try (InputStream input = getClass().getResourceAsStream("/settings-row-ids-452.json")) {
            assertNotNull(input);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int count; (count = input.read(buffer)) != -1;) bytes.write(buffer, 0, count);
            ids = new JSONArray(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
        Set<String> checked = new TreeSet<>();
        for (int at = 0; at < ids.length(); at++) checked.add(ids.getString(at));
        String picked = SettingsRows.join(checked);
        assertTrue("real row choices exceed the ordinary text ceiling", picked.length() > ConfigurationBackup.MAX_TEXT);
        assertTrue("the existing bounded JSON reader still accepts them", picked.length() <= ConfigurationBackup.MAX_WORDS_TEXT);
        assertEquals(picked, SettingsRows.join(SettingsRows.parse(picked)));
        Settings.HIDDEN_SETTINGS_ROWS.save(picked);
        byte[] exported = ConfigurationBackup.export();
        Settings.HIDDEN_SETTINGS_ROWS.save("ARCHIVE");
        ConfigurationBackup.Result imported = ConfigurationBackup.restore(exported);
        assertEquals(0, imported.skipped);
        assertEquals(picked, Settings.HIDDEN_SETTINGS_ROWS.savedValue());
        assertTrue(imported.before.containsKey(Settings.HIDDEN_SETTINGS_ROWS));
        assertNotNull(ConfigurationBackup.undo());
        assertEquals("ARCHIVE", Settings.HIDDEN_SETTINGS_ROWS.savedValue());
    }
}
