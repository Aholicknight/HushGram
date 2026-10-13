/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.content.res.ColorStateList;
import android.os.Parcel;

import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Accent color" patch: Instagram's blue turns into the color picked in HushGram
 * settings.
 *
 * <p>Instagram reads most of its colors from its theme, through two small methods that resolve a
 * theme attribute and answer the color, and reads a button's colors for each of its states as a
 * color state list through AndroidX's loader. The patch hands every color those two answer to
 * {@link #color} and every list the loader answers to {@link #list}.
 *
 * <p>Only Instagram's exact accent blues change, each into the shade of the picked color that plays
 * the same part (see {@link #swap}), and each keeps its alpha. So a list keeps its states: a disabled
 * button's faded blue becomes the faded new color, and a gray or white state stays gray or white. No
 * other color is touched, so text on the buttons stays Instagram's own white. Colors already on
 * screen keep theirs, so a change shows after a restart.
 *
 * <p>Both hooks fail open: with the switch off, Instagram blue picked, HushGram paused, the settings
 * not read yet or anything thrown, Instagram's own color goes through.
 */
public final class AccentColor {
    /** What's counted when a color read from the theme is swapped. */
    static final String COLOR = "accent colors swapped";

    /** What's counted when a color state list from AndroidX's loader is swapped. */
    static final String LIST = "accent color lists swapped";

    /** What's counted when a list didn't read back as states and colors, so it was left alone. */
    static final String UNREAD = "color lists it couldn't read";

    /** The most states a list is read with. Instagram's have a handful. */
    private static final int MOST_STATES = 64;

    /**
     * Each list swapped, by the list the loader answered, for the color it was swapped to. The
     * loader keeps its own lists, so the same one comes back, and the swap is worked out once. Weak
     * keys let a list go when Instagram drops it.
     */
    private static final Map<ColorStateList, Swapped> LISTS = new WeakHashMap<>();

    private AccentColor() {
    }

    /**
     * Injected before each theme color read returns. Answers the color to use for [color]: the
     * picked color's shade for an accent blue, [color] itself for anything else. Never throws.
     */
    public static int color(int color) {
        try {
            HookStatus.invoked(FamilyNames.ACCENT_COLOR);
            if (role(color) == Role.NONE) return color;
            AccentHue hue = picked();
            if (hue == null) return color;
            HookStatus.counted(FamilyNames.ACCENT_COLOR, COLOR);
            return swap(color, hue);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "theme color", failure);
            return color;
        }
    }

    /**
     * Injected before each return of AndroidX's color state list loader. Answers [list] with its
     * accent blues swapped, or [list] itself when it holds none. Never throws.
     */
    public static ColorStateList list(ColorStateList list) {
        try {
            HookStatus.invoked(FamilyNames.ACCENT_COLOR);
            // A subclass may draw its own way, so only a plain list is rebuilt.
            if (list == null || list.getClass() != ColorStateList.class) return list;
            AccentHue hue = picked();
            if (hue == null) return list;
            Swapped known;
            synchronized (LISTS) {
                known = LISTS.get(list);
            }
            if (known == null || known.hue != hue) {
                known = new Swapped(hue, swap(list, hue));
                synchronized (LISTS) {
                    LISTS.put(list, known);
                }
            }
            if (known.list == null) return list;
            HookStatus.counted(FamilyNames.ACCENT_COLOR, LIST);
            return known.list;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "color list", failure);
            return list;
        }
    }

    /** The color picked, or null for Instagram's own: switch off, blue picked, paused or not ready. */
    static AccentHue picked() {
        if (!Utils.settingsReady() || !Settings.ACCENT_COLOR.get()) return null;
        AccentHue hue = Settings.ACCENT_HUE.get();
        return hue == null || hue == AccentHue.BLUE ? null : hue;
    }

    /** The part an accent blue plays, read from its red, green and blue alone. */
    enum Role {
        NONE, FILL, PRESSED, DIM, LINK_ON_LIGHT, LINK_ON_DARK, PALE
    }

    /**
     * Instagram's accent blues on 450. Its older blue (blue_5, #0095f6, with its see-through steps)
     * and its Prism indigo (#4a5df9) fill buttons and highlights. Prism's indigo_06 is the pressed
     * shade, indigo_08 the dim one, and the bright text links take #3143e3 or #3849da on a light
     * screen and #85a1ff or #8593ff on a dark one. The older theme, which accounts outside Prism
     * get, draws links (mentions, hashtags, bio links) in blue_8 (#00376b) on a light screen and in
     * blue_1 (#e0f1ff) on a dark one, and fills a selected pill or comment with that same pale blue.
     */
    static Role role(int color) {
        switch (color & 0xffffff) {
            case 0x0095f6:
            case 0x4a5df9:
                return Role.FILL;
            case 0x3b52e7:
                return Role.PRESSED;
            case 0x232e67:
                return Role.DIM;
            case 0x3143e3:
            case 0x3849da:
            case 0x00376b:
                return Role.LINK_ON_LIGHT;
            case 0x85a1ff:
            case 0x8593ff:
                return Role.LINK_ON_DARK;
            case 0xe0f1ff:
                return Role.PALE;
            default:
                return Role.NONE;
        }
    }

    /** [color] as [hue]'s shade for the part it plays, with its own alpha, or [color] for no part. */
    static int swap(int color, AccentHue hue) {
        int rgb;
        switch (role(color)) {
            case FILL:
                rgb = hue.fill;
                break;
            case PRESSED:
                rgb = hue.pressed;
                break;
            case DIM:
                rgb = hue.dim;
                break;
            case LINK_ON_LIGHT:
                rgb = hue.linkOnLight;
                break;
            case LINK_ON_DARK:
                rgb = hue.linkOnDark;
                break;
            case PALE:
                rgb = hue.pale;
                break;
            default:
                return color;
        }
        return (color & 0xff000000) | rgb;
    }

    /**
     * A new list with [list]'s states and its colors swapped, or null when none of its colors is an
     * accent blue or it can't be read. A list has no public getter for its states, so it's read back
     * from the parcel it writes, which is the states' count, each state as an int array and then the
     * colors as one.
     */
    static ColorStateList swap(ColorStateList list, AccentHue hue) {
        Parcel parcel = Parcel.obtain();
        try {
            list.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            int count = parcel.readInt();
            int[][] states = count >= 0 && count <= MOST_STATES ? new int[count][] : null;
            for (int i = 0; states != null && i < count; i++) {
                states[i] = parcel.createIntArray();
                if (states[i] == null) states = null;
            }
            int[] colors = states == null ? null : parcel.createIntArray();
            if (colors == null || colors.length != count || parcel.dataAvail() != 0) {
                HookStatus.counted(FamilyNames.ACCENT_COLOR, UNREAD);
                return null;
            }
            boolean changed = false;
            for (int i = 0; i < colors.length; i++) {
                int swapped = swap(colors[i], hue);
                changed |= swapped != colors[i];
                colors[i] = swapped;
            }
            return changed ? new ColorStateList(states, colors) : null;
        } finally {
            parcel.recycle();
        }
    }

    /** A list's swap for one color, null when it had nothing to swap. */
    private static final class Swapped {
        final AccentHue hue;
        final ColorStateList list;

        Swapped(AccentHue hue, ColorStateList list) {
            this.hue = hue;
            this.list = list;
        }
    }
}
