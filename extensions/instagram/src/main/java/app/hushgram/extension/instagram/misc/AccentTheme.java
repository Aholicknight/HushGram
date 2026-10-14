/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.content.Context;
import android.content.res.Resources;
import android.util.TypedValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * The theme half of the "Accent color" patch (#8). Some of Instagram's blues never pass the color
 * reads {@link AccentColor} hooks: an XML style that takes a color from a theme attribute, such as a
 * bio link's or the Activity tab's Follow buttons', is read by the framework through TypedArray. So
 * the patch adds theme styles, and this lays the picked color's onto each activity's theme right
 * after Instagram lays its own overrides on.
 *
 * <p>The patch lists every theme attribute Instagram's styles set to one of its accent blues, and
 * adds a style for each blue it found there (an attribute, the part its blue plays and the blue's
 * alpha) that points the attribute at a shade attribute, plus a style per color setting every shade.
 * Here each listed attribute is resolved as the theme holds it now, so a dark theme's link takes the
 * dark link shade and a light one's the light shade, and the styles for the blues found go on along
 * with the picked color's. An attribute holding anything else is left alone.
 *
 * <p>Fails open: with the switch off, Instagram blue picked, HushGram paused, the resources missing or
 * anything thrown, the theme stays Instagram's. A change shows on screens opened after a restart.
 */
public final class AccentTheme {
    /** What's counted each time the picked color goes onto a theme. */
    static final String APPLIED = "themes given the accent color";

    /** What's counted when the patch's styles aren't in the app, so the theme is left alone. */
    static final String MISSING = "themes left blue, the accent styles are missing";

    /** The string resource the patch adds, naming every attribute it made styles for. */
    static final String ATTRIBUTES = "hushgram_accent_attributes";

    /** Resource numbers by type and name, 0 for none. They don't change while the app runs. */
    private static final Map<String, Integer> IDS = new HashMap<>();

    private AccentTheme() {
    }

    /** Injected at each return of the method laying Instagram's overrides over [context]'s theme. Never throws. */
    public static void apply(Context context) {
        try {
            AccentHue hue = AccentColor.picked();
            if (hue == null || context == null) return;
            HookStatus.invoked(FamilyNames.ACCENT_COLOR);
            Resources resources = context.getResources();
            Resources.Theme theme = context.getTheme();
            String app = context.getPackageName();
            int palette = id(resources, paletteStyle(hue), "style", app);
            int listed = id(resources, ATTRIBUTES, "string", app);
            if (palette == 0 || listed == 0) {
                HookStatus.counted(FamilyNames.ACCENT_COLOR, MISSING);
                return;
            }
            List<Integer> styles = new ArrayList<>();
            TypedValue value = new TypedValue();
            for (String attribute : resources.getString(listed).split(" ")) {
                int attr = attribute.startsWith("android:")
                        ? id(resources, attribute.substring("android:".length()), "attr", "android")
                        : id(resources, attribute, "attr", app);
                if (attr == 0 || !theme.resolveAttribute(attr, value, true)) continue;
                String style = styleFor(attribute, value);
                int styleId = style == null ? 0 : id(resources, style, "style", app);
                if (styleId != 0) styles.add(styleId);
            }
            if (styles.isEmpty()) return;
            theme.applyStyle(palette, true);
            for (int style : styles) theme.applyStyle(style, true);
            HookStatus.counted(FamilyNames.ACCENT_COLOR, APPLIED);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "theme", failure);
        }
    }

    /**
     * The name of the style pointing [attribute] at the shade for the blue [value] holds, or null when
     * it holds no color or no accent blue. The patch names its styles the same way.
     */
    static String styleFor(String attribute, TypedValue value) {
        if (value.type < TypedValue.TYPE_FIRST_COLOR_INT || value.type > TypedValue.TYPE_LAST_COLOR_INT) return null;
        AccentColor.Role role = AccentColor.role(value.data);
        if (role == AccentColor.Role.NONE) return null;
        return "HushGramAccent_" + attribute.replace(':', '_') + "_" + role.name().toLowerCase(Locale.ROOT)
                + String.format(Locale.ROOT, "_%02x", value.data >>> 24);
    }

    /** The style setting every shade attribute to [hue]'s colors. */
    static String paletteStyle(AccentHue hue) {
        return "HushGramAccentPalette_" + hue.name();
    }

    private static int id(Resources resources, String name, String type, String app) {
        String key = type + "/" + app + ":" + name;
        synchronized (IDS) {
            Integer known = IDS.get(key);
            if (known != null) return known;
        }
        int found = resources.getIdentifier(name, type, app);
        synchronized (IDS) {
            IDS.put(key, found);
        }
        return found;
    }
}
