/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Typeface;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowToast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Font: Instagram's Prism Sans and its text views take the phone's font or a picked file at the
 * weight and slant they had, and Instagram's own font stays whenever that can't be done.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FontsTest {
    /** The first four bytes of a TrueType font, then a little of the rest. */
    private static final byte[] TRUE_TYPE = {0x00, 0x01, 0x00, 0x00, 0x00, 0x0c, 0x00, (byte) 0x80};

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void prepare() {
        Settings.FONT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        Fonts.resetForTests();
        removeKept();
    }

    @After
    public void restore() {
        Settings.FONT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        Fonts.resetForTests();
        removeKept();
    }

    /** Instagram's font to start, with a restart owed for a change, and every font goes through as it was. */
    @Test
    public void instagramsFontToStartChangesNothing() {
        assertEquals(FontChoice.INSTAGRAM, Settings.FONT.defaultValue);
        assertTrue("fonts are kept once loaded, so a change takes a restart", Settings.FONT.rebootApp);
        Typeface bold = Typeface.create(Typeface.SERIF, 700, false);
        assertSame(bold, Fonts.prismTypeface(bold));
        TextView view = view(bold);
        Fonts.textView(view);
        assertSame(bold, view.getTypeface());
        assertEquals(List.of(FamilyNames.FONT + ": invoked 2, 0 found, 0 missing"), HookStatus.report());
    }

    /** The phone's font, at the weight and slant of each Prism Sans it replaces. */
    @Test
    public void thePhonesFontKeepsWeightAndSlant() {
        Settings.FONT.save(FontChoice.SYSTEM);
        Typeface semibold = Fonts.prismTypeface(Typeface.create(Typeface.SERIF, 600, false));
        assertSame(Typeface.create(Typeface.DEFAULT, 600, false), semibold);
        assertEquals(600, semibold.getWeight());
        Typeface boldItalic = Fonts.prismTypeface(Typeface.create(Typeface.SERIF, 700, true));
        assertEquals(700, boldItalic.getWeight());
        assertTrue(boldItalic.isItalic());
        assertSame(Typeface.create(Typeface.DEFAULT, 700, true), boldItalic);
        assertEquals(List.of(FamilyNames.FONT + ": invoked 2, 0 found, 0 missing. Counted: " + Fonts.PHONE + " 2"),
                HookStatus.report());
    }

    /** A text view takes the chosen font once, and a view already in it is left alone. */
    @Test
    public void aTextViewTakesTheChosenFontOnce() {
        Settings.FONT.save(FontChoice.SYSTEM);
        TextView view = view(Typeface.create(Typeface.MONOSPACE, 700, false));
        Fonts.textView(view);
        assertSame(Typeface.create(Typeface.DEFAULT, 700, false), view.getTypeface());
        Fonts.textView(view);
        TextView plain = view(null);
        Fonts.textView(plain);
        assertEquals(400, plain.getTypeface().getWeight());
        assertFalse(plain.getTypeface().isItalic());
        assertEquals(List.of(FamilyNames.FONT + ": invoked 3, 0 found, 0 missing. Counted: " + Fonts.PHONE + " 2"),
                HookStatus.report());
    }

    /** A picked file that loads is the font, read once for the process. */
    @Test
    public void aPickedFileIsTheFont() throws Exception {
        keep(TRUE_TYPE);
        int[] loads = {0};
        Fonts.loader = file -> {
            loads[0]++;
            return file.isFile() ? Typeface.SERIF : null;
        };
        Settings.FONT.save(FontChoice.FILE);
        assertSame(Typeface.create(Typeface.SERIF, 700, false), Fonts.prismTypeface(Typeface.create(Typeface.DEFAULT, 700, false)));
        assertSame(Typeface.create(Typeface.SERIF, 400, false), Fonts.prismTypeface(Typeface.DEFAULT));
        assertEquals("the file loads once", 1, loads[0]);
        assertEquals(List.of(FamilyNames.FONT + ": invoked 2, 0 found, 0 missing. Counted: " + Fonts.PICKED + " 2"),
                HookStatus.report());
    }

    /** A file that's gone or won't load leaves Instagram's font, with one toast however often it's asked. */
    @Test
    public void aFileThatWontLoadLeavesInstagramsFontWithOneToast() {
        Settings.FONT.save(FontChoice.FILE);
        Typeface bold = Typeface.create(Typeface.SERIF, 700, false);
        assertSame(bold, Fonts.prismTypeface(bold));
        assertSame(bold, Fonts.prismTypeface(bold));
        assertEquals(1, ShadowToast.shownToastCount());
        assertTrue(ShadowToast.getTextOfLatestToast(), ShadowToast.getTextOfLatestToast().startsWith("Couldn't load your font file"));

        Fonts.resetForTests();
        keep(TRUE_TYPE);
        Fonts.loader = file -> null;
        assertSame(bold, Fonts.prismTypeface(bold));
        assertEquals(2, ShadowToast.shownToastCount());
    }

    /** Paused, before the settings are read, or with no font loaded yet, Instagram's own goes through. */
    @Test
    public void pausedUnreadyAndEmptyLeaveInstagramsFont() {
        Settings.FONT.save(FontChoice.SYSTEM);
        Typeface bold = Typeface.create(Typeface.SERIF, 700, false);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(bold, Fonts.prismTypeface(bold));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Fonts.resetForTests();

        SettingsContextRule.withoutContext(() -> assertSame(bold, Fonts.prismTypeface(bold)));
        assertNull(Fonts.prismTypeface(null));
        Fonts.textView(null);

        assertSame(Typeface.create(Typeface.DEFAULT, 700, false), Fonts.prismTypeface(bold));
    }

    /** A font file is copied in once it loads, and anything else keeps the font kept before. */
    @Test
    public void onlyAFontThatLoadsIsKept() throws Exception {
        Fonts.loader = file -> Typeface.SERIF;
        assertTrue(Fonts.store(RuntimeEnvironment.getApplication(), new ByteArrayInputStream(TRUE_TYPE)));
        File kept = Fonts.kept(RuntimeEnvironment.getApplication());
        assertArrayEquals(TRUE_TYPE, Files.readAllBytes(kept.toPath()));

        assertFalse("not a font", Fonts.store(RuntimeEnvironment.getApplication(), new ByteArrayInputStream("hello there".getBytes())));
        assertFalse("too short to be one", Fonts.store(RuntimeEnvironment.getApplication(), new ByteArrayInputStream(new byte[] {0, 1})));
        Fonts.loader = file -> null;
        byte[] open = {'O', 'T', 'T', 'O', 0, 1};
        assertFalse("Android won't load it", Fonts.store(RuntimeEnvironment.getApplication(), new ByteArrayInputStream(open)));
        assertArrayEquals("the font kept before stays", TRUE_TYPE, Files.readAllBytes(kept.toPath()));
        assertFalse("no partial copy is left", new File(kept.getParentFile(), Fonts.FILE + ".tmp").exists());

        Fonts.loader = file -> Typeface.SERIF;
        assertTrue(Fonts.store(RuntimeEnvironment.getApplication(), new ByteArrayInputStream(open)));
        assertArrayEquals(open, Files.readAllBytes(kept.toPath()));
    }

    @Test
    public void theFourWaysAFontFileStarts() {
        assertTrue(Fonts.startsLikeAFont(new byte[] {0x00, 0x01, 0x00, 0x00}));
        assertTrue(Fonts.startsLikeAFont(new byte[] {'O', 'T', 'T', 'O'}));
        assertTrue(Fonts.startsLikeAFont(new byte[] {'t', 'r', 'u', 'e'}));
        assertTrue(Fonts.startsLikeAFont(new byte[] {'t', 't', 'c', 'f'}));
        assertFalse(Fonts.startsLikeAFont(new byte[] {'w', 'O', 'F', 'F'}));
        assertFalse(Fonts.startsLikeAFont(new byte[] {'P', 'K', 3, 4}));
    }

    private static TextView view(Typeface typeface) {
        TextView view = new TextView(RuntimeEnvironment.getApplication());
        if (typeface != null) view.setTypeface(typeface);
        return view;
    }

    private static void keep(byte[] bytes) {
        File kept = Fonts.kept(RuntimeEnvironment.getApplication());
        kept.getParentFile().mkdirs();
        try (FileOutputStream output = new FileOutputStream(kept)) {
            output.write(bytes);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void removeKept() {
        File kept = Fonts.kept(RuntimeEnvironment.getApplication());
        if (kept.exists()) kept.delete();
    }
}
