/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;

import app.hushgram.extension.instagram.direct.PinLock;
import app.hushgram.extension.instagram.direct.UnlockWith;
import app.hushgram.extension.shared.SettingsContextRule;

/** The HushGram PIN never leaves the phone in a Configuration backup, and a backup can't set it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class PinBackupTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();

    @Before
    public void setup() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        Settings.LOCK_MESSAGES.save(true);
        Settings.UNLOCK_WITH.save(UnlockWith.PIN);
        assertTrue(PinLock.set("4821"));
    }

    @After
    public void restore() {
        Settings.LOCK_MESSAGES.resetToDefault();
        Settings.UNLOCK_WITH.resetToDefault();
        Settings.LOCK_PIN_MISSES.resetToDefault();
        Settings.LOCK_PIN_WAIT_UNTIL.resetToDefault();
        Settings.LOCK_PIN.resetToDefault();
        PatchFamily.inBuildForTests = null;
    }

    @Test
    public void aBackupLeavesThePinItsChoiceAndItsCountsOut() throws Exception {
        Settings.LOCK_PIN_MISSES.save(3L);
        Settings.LOCK_PIN_WAIT_UNTIL.save(123L);

        String exported = new String(ConfigurationBackup.export(), StandardCharsets.UTF_8);

        for (String key : new String[]{Settings.LOCK_PIN.key, Settings.UNLOCK_WITH.key,
                Settings.LOCK_PIN_MISSES.key, Settings.LOCK_PIN_WAIT_UNTIL.key}) {
            assertFalse(key + " is in the backup", exported.contains(key));
        }
        String[] stored = Settings.LOCK_PIN.get().split("\\$");
        assertFalse("the salt is in the backup", exported.contains(stored[2]));
        assertFalse("the hash is in the backup", exported.contains(stored[3]));
        assertTrue("the lock's own switch still is", exported.contains(Settings.LOCK_MESSAGES.key));
        assertFalse(Settings.LOCK_PIN.includeWithImportExport);
        assertFalse(Settings.UNLOCK_WITH.includeWithImportExport);
    }

    @Test
    public void aBackupFileCannotSetThePinOrTheChoice() throws Exception {
        String hash = Settings.LOCK_PIN.get();
        String file = "{\"project\":\"HushGram\",\"schema\":1,\"settings\":{"
                + "\"" + Settings.LOCK_PIN.key + "\":{\"type\":\"string\",\"value\":\"pbkdf2-sha256$1$aa$bb\"},"
                + "\"" + Settings.UNLOCK_WITH.key + "\":{\"type\":\"string\",\"value\":\"PHONE_LOCK\"}}}";
        try {
            ConfigurationBackup.restore(file.getBytes(StandardCharsets.UTF_8));
        } catch (IOException refused) {
            // Refusing the file is as good: what matters is what stays.
        }
        assertEquals(hash, Settings.LOCK_PIN.get());
        assertEquals(UnlockWith.PIN, Settings.UNLOCK_WITH.get());
        assertTrue(PinLock.matches("4821"));
    }

    private static byte[] locksOff() {
        return ("{\"project\":\"HushGram\",\"schema\":1,\"settings\":{"
                + "\"" + Settings.LOCK_MESSAGES.key + "\":{\"type\":\"boolean\",\"value\":false},"
                + "\"" + Settings.LOCK_APP.key + "\":{\"type\":\"boolean\",\"value\":false}}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void anImportCannotSwitchALockOffWhileAPinIsChosen() throws Exception {
        Settings.LOCK_APP.save(true);

        ConfigurationBackup.Result result = ConfigurationBackup.restore(locksOff());

        assertTrue(Settings.LOCK_MESSAGES.savedValue());
        assertTrue(Settings.LOCK_APP.savedValue());
        assertEquals(0, result.applied);
        assertEquals(2, result.skipped);
        assertFalse("nothing to undo", ConfigurationBackup.canUndo());
        Settings.LOCK_APP.resetToDefault();
    }

    @Test
    public void anImportStillChangesTheLocksWithThePhoneLockChosen() throws Exception {
        Settings.UNLOCK_WITH.save(UnlockWith.PHONE_LOCK);
        Settings.LOCK_APP.save(true);

        ConfigurationBackup.restore(locksOff());

        assertFalse(Settings.LOCK_MESSAGES.savedValue());
        assertFalse(Settings.LOCK_APP.savedValue());
        ConfigurationBackup.forgetUndo();
    }

    @Test
    public void anUndoCannotSwitchALockOffEither() throws Exception {
        Settings.UNLOCK_WITH.save(UnlockWith.PHONE_LOCK);
        Settings.LOCK_MESSAGES.save(false);
        String on = "{\"project\":\"HushGram\",\"schema\":1,\"settings\":{\"" + Settings.LOCK_MESSAGES.key
                + "\":{\"type\":\"boolean\",\"value\":true}}}";
        ConfigurationBackup.restore(on.getBytes(StandardCharsets.UTF_8));
        long token = ConfigurationBackup.undoToken();
        assertTrue(token != 0);
        // A PIN is chosen before the Undo is tapped: Undo would switch the lock off.
        Settings.UNLOCK_WITH.save(UnlockWith.PIN);

        ConfigurationBackup.undo(token);

        assertTrue(Settings.LOCK_MESSAGES.savedValue());
        ConfigurationBackup.forgetUndo();
    }
}
