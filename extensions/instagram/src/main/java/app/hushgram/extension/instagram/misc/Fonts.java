/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Change the font" patch: Instagram's text draws in the phone's font or a font
 * file picked in HushGram settings instead of Instagram's own (#166, #5).
 *
 * <p>Instagram's newer design draws its text in Prism Sans, which its font helper loads once per
 * weight (regular, semibold and bold) and keeps in static fields. The patch hands each Typeface
 * the helper loads to {@link #prismTypeface} before it's kept, and every IgTextView to
 * {@link #textView} once Instagram has set it up. Each answers the chosen font at the same weight
 * and slant as the one it replaces, so bold stays bold and italic stays italic. Letters the chosen
 * font lacks still draw from the phone's fonts, emoji included.
 *
 * <p>The fonts are kept for the life of the process, so a change shows after a restart. Every hook
 * fails open: with Instagram's font chosen, HushGram paused, the settings not read yet, a font
 * file that won't load (one toast says so) or anything thrown, Instagram's own font goes through.
 */
public final class Fonts {
    /** The folder in Instagram's own files where the picked font is kept, and its name there. */
    static final String DIRECTORY = "hushgram-font";
    static final String FILE = "font";

    /** The largest font file kept: 64 MiB, room for a font covering Chinese, Japanese and Korean. */
    static final long MOST_BYTES = 64L * 1024 * 1024;

    /** What's counted for each font swapped. */
    static final String PHONE = "Instagram's font swapped for the phone's";
    static final String PICKED = "Instagram's font swapped for the picked file";

    /** The weight a TextView with no Typeface of its own draws in. */
    static final int REGULAR = 400;

    /** Loads a font file, or answers null when it isn't one. Tests put their own in. */
    interface Loader {
        @Nullable
        Typeface load(File file);
    }

    static volatile Loader loader = file -> new Typeface.Builder(file).build();

    /** The chosen font for this process, null for Instagram's own, or {@link #UNREAD} until read. */
    private static final Object UNREAD = new Object();
    private static volatile Object chosen = UNREAD;

    /** The Typefaces this class answered, so a view already in the chosen font is left alone. */
    private static final Set<Typeface> OURS = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    private Fonts() {
    }

    /**
     * Injected before Instagram's font helper keeps each Prism Sans Typeface it loads. Answers the
     * Typeface to keep in place of [loaded], which may be null when Instagram's font loader isn't
     * up yet. Never throws.
     */
    public static Typeface prismTypeface(Typeface loaded) {
        try {
            HookStatus.invoked(FamilyNames.FONT);
            if (loaded == null || !Utils.settingsReady() || OURS.contains(loaded)) return loaded;
            Typeface base = chosen();
            if (base == null) return loaded;
            Typeface swapped = styled(base, loaded);
            HookStatus.counted(FamilyNames.FONT, counted());
            return swapped;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FONT, "Prism Sans", failure);
            return loaded;
        }
    }

    /**
     * Injected first in AndroidX's ResourcesCompat.getFont(Context, int), which Compose text (a
     * profile's bio on 451, #166) and Instagram's own font loader read Prism Sans through. Answers
     * the chosen font at the weight of the Prism Sans resource [id] names, or null for any other
     * font, with Instagram's font chosen, or before the settings are read, and the resource then
     * loads as before. Never throws.
     */
    @Nullable
    public static Typeface resourceFont(Context context, int id) {
        try {
            HookStatus.invoked(FamilyNames.FONT);
            if (context == null || !Utils.settingsReady()) return null;
            Typeface base = chosen();
            if (base == null) return null;
            int weight = prismWeight(context, id);
            if (weight <= 0) return null;
            Typeface swapped = Typeface.create(base, weight, false);
            OURS.add(swapped);
            HookStatus.counted(FamilyNames.FONT, counted());
            return swapped;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FONT, "font resource", failure);
            return null;
        }
    }

    /** Each font resource looked at, by id: its Prism Sans weight, or 0 for a font that isn't Prism Sans. */
    static final Map<Integer, Integer> WEIGHTS = new ConcurrentHashMap<>();

    /** The weight of the Prism Sans file or family [id] names, or 0 when it names another font. */
    static int prismWeight(Context context, int id) {
        Integer known = WEIGHTS.get(id);
        if (known != null) return known;
        String name;
        try {
            name = context.getResources().getResourceEntryName(id);
        } catch (Exception notFound) {
            name = null;
        }
        int weight = prismWeight(name);
        WEIGHTS.put(id, weight);
        return weight;
    }

    /**
     * The weight a font resource named [name] draws Prism Sans in: Instagram keeps its files as
     * IGUIBetav9_Regular, _SemiBold and _Bold, and the three as the family prism_sans. 0 for any other.
     */
    static int prismWeight(@Nullable String name) {
        if (name == null) return 0;
        if (name.equals(PRISM_FAMILY)) return REGULAR;
        if (!name.startsWith(PRISM_FILE)) return 0;
        switch (name.substring(PRISM_FILE.length()).toLowerCase(Locale.ROOT)) {
            case "thin": return 100;
            case "extralight": return 200;
            case "light": return 300;
            case "regular": return REGULAR;
            case "medium": return 500;
            case "semibold": return 600;
            case "bold": return 700;
            case "extrabold": return 800;
            case "black": return 900;
            default: return 0;
        }
    }

    /** The names Instagram gives Prism Sans' font files (with the weight after) and its family. */
    static final String PRISM_FILE = "IGUIBetav9_";
    static final String PRISM_FAMILY = "prism_sans";

    /**
     * Injected right after IgTextView's set-up. Sets [view]'s Typeface to the chosen font at the
     * weight and slant it already has. Never throws.
     */
    public static void textView(TextView view) {
        try {
            HookStatus.invoked(FamilyNames.FONT);
            if (view == null || !Utils.settingsReady()) return;
            Typeface base = chosen();
            if (base == null) return;
            Typeface current = view.getTypeface();
            if (current != null && OURS.contains(current)) return;
            view.setTypeface(styled(base, current));
            HookStatus.counted(FamilyNames.FONT, counted());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FONT, "text view", failure);
        }
    }

    /** [base] at the weight and slant of [like], or regular and upright without one. */
    static Typeface styled(Typeface base, @Nullable Typeface like) {
        int weight = like == null ? REGULAR : like.getWeight();
        boolean italic = like != null && like.isItalic();
        Typeface styled = Typeface.create(base, weight, italic);
        OURS.add(styled);
        return styled;
    }

    private static String counted() {
        return Settings.FONT.get() == FontChoice.FILE ? PICKED : PHONE;
    }

    /**
     * The font chosen for this process, read once: Typeface.DEFAULT for the phone's, the kept file
     * for a file, null for Instagram's. A file that won't load answers null and says so once.
     */
    @Nullable
    static Typeface chosen() {
        Object known = chosen;
        if (known != UNREAD) return (Typeface) known;
        synchronized (Fonts.class) {
            if (chosen != UNREAD) return (Typeface) chosen;
            FontChoice choice = Settings.FONT.get();
            Typeface base = null;
            if (choice == FontChoice.SYSTEM) {
                base = Typeface.DEFAULT;
            } else if (choice == FontChoice.FILE) {
                base = loadKept();
                if (base == null) Utils.showToastLong(L10n.t(
                        "Couldn't load your font file, so Instagram's font shows. Pick it again in HushGram settings > Layout."));
            }
            chosen = base;
            return base;
        }
    }

    @Nullable
    private static Typeface loadKept() {
        Context context = Utils.getContext();
        if (context == null) return null;
        File file = kept(context);
        if (!file.isFile()) return null;
        try {
            return loader.load(file);
        } catch (Exception failure) {
            Logger.printInfo(() -> "Font: the kept file didn't load", failure);
            return null;
        }
    }

    /** Where the picked font is kept. */
    static File kept(Context context) {
        return new File(new File(context.getFilesDir(), DIRECTORY), FILE);
    }

    /**
     * Copies a picked file into Instagram's own storage if it is a font that loads, in place of the
     * one kept before. Answers false, keeping what was there, for a file too big, one that doesn't
     * start like a TrueType or OpenType font or collection, or one Android won't load. Off the
     * main thread.
     */
    public static boolean store(Context context, InputStream input) {
        File target = kept(context);
        File directory = target.getParentFile();
        File temporary = new File(directory, FILE + ".tmp");
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Couldn't make " + DIRECTORY);
            byte[] start = new byte[4];
            int read = 0;
            long total = 0;
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MOST_BYTES) return false;
                    for (int i = 0; i < count && read < start.length; i++) start[read++] = buffer[i];
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            if (read < start.length || !startsLikeAFont(start)) return false;
            Typeface loaded = loader.load(temporary);
            if (loaded == null) return false;
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception failure) {
            Logger.printInfo(() -> "Font: the picked file wasn't kept", failure);
            return false;
        } finally {
            if (temporary.exists() && !temporary.delete()) Logger.printInfo(() -> "Font: couldn't remove the partial copy");
        }
    }

    /** TrueType (0x00010000 or 'true'), OpenType with CFF outlines ('OTTO') or a collection ('ttcf'). */
    static boolean startsLikeAFont(byte[] start) {
        int tag = ((start[0] & 0xff) << 24) | ((start[1] & 0xff) << 16) | ((start[2] & 0xff) << 8) | (start[3] & 0xff);
        return tag == 0x00010000 || tag == 0x4f54544f || tag == 0x74727565 || tag == 0x74746366;
    }

    /** Takes back what this process read, for a test. */
    static void resetForTests() {
        chosen = UNREAD;
        OURS.clear();
        WEIGHTS.clear();
        loader = file -> new Typeface.Builder(file).build();
    }
}
