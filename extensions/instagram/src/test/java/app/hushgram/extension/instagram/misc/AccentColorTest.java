/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.res.ColorStateList;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Which colors Accent color swaps, into what, and when it leaves Instagram's blue alone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class AccentColorTest {
    /** Instagram's blue_5 and Prism's indigo, the two fills. */
    private static final int BLUE = 0xff0095f6;
    private static final int INDIGO = 0xff4a5df9;
    /** Prism's pressed indigo, its dim one and the bright links on a light and a dark screen. */
    private static final int PRESSED = 0xff3b52e7;
    private static final int DIM = 0xff232e67;
    private static final int LINK_LIGHT = 0xff3849da;
    private static final int LINK_DARK = 0xff8593ff;
    /** Prism's dark background, its pale blue and white: never touched. */
    private static final int PRISM_BLACK = 0xff0c1014;
    private static final int PALE_BLUE = 0xffe0f1ff;
    private static final int WHITE = 0xffffffff;

    private static final int[] ENABLED = {android.R.attr.state_enabled};
    private static final int[] PRESSED_STATE = {android.R.attr.state_enabled, android.R.attr.state_pressed};
    private static final int[] DISABLED = {-android.R.attr.state_enabled};
    private static final int[] SELECTED = {android.R.attr.state_enabled, android.R.attr.state_selected};

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void prepare() {
        Settings.ACCENT_COLOR.resetToDefault();
        Settings.ACCENT_HUE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        prepare();
    }

    /** Off to start, so a patched Instagram keeps its blue until the switch goes on. */
    @Test
    public void offToStartInstagramsBlueGoesThrough() {
        assertEquals(Boolean.FALSE, Settings.ACCENT_COLOR.defaultValue);
        assertEquals(AccentHue.PURPLE, Settings.ACCENT_HUE.defaultValue);
        assertTrue("colors on screen keep theirs until a restart", Settings.ACCENT_COLOR.rebootApp);
        assertTrue("colors on screen keep theirs until a restart", Settings.ACCENT_HUE.rebootApp);
        assertEquals(BLUE, AccentColor.color(BLUE));
        ColorStateList button = button();
        assertSame(button, AccentColor.list(button));
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 2, 0 found, 0 missing"), HookStatus.report());
    }

    /** Each accent blue becomes the shade playing its part, keeping its alpha, and each swap is counted. */
    @Test
    public void eachAccentBlueBecomesItsShade() {
        Settings.ACCENT_COLOR.save(true);
        AccentHue purple = AccentHue.PURPLE;
        assertEquals(0xff7638fa, AccentColor.color(BLUE));
        assertEquals(0xff7638fa, AccentColor.color(INDIGO));
        assertEquals("a see-through step keeps its alpha", 0x4d7638fa, AccentColor.color(0x4d0095f6));
        assertEquals(0xff000000 | purple.pressed, AccentColor.color(PRESSED));
        assertEquals(0xff000000 | purple.dim, AccentColor.color(DIM));
        assertEquals(0xff5806e0, AccentColor.color(LINK_LIGHT));
        assertEquals(0xff5806e0, AccentColor.color(0xff3143e3));
        assertEquals(0xffa07cfd, AccentColor.color(LINK_DARK));
        assertEquals(0xffa07cfd, AccentColor.color(0xff85a1ff));
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 9, 0 found, 0 missing. Counted: "
                + AccentColor.COLOR + " 9"), HookStatus.report());
    }

    /** No other color changes: text, backgrounds, grays and Instagram's pale blues stay as they are. */
    @Test
    public void everyOtherColorGoesThrough() {
        Settings.ACCENT_COLOR.save(true);
        for (int color : new int[] {WHITE, 0xff000000, PRISM_BLACK, PALE_BLUE, 0xffaddfff, 0xff0095f7, 0x00000000, 0xffed4956}) {
            assertEquals(Integer.toHexString(color), color, AccentColor.color(color));
        }
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 8, 0 found, 0 missing"), HookStatus.report());
    }

    /** Every color picked swaps, and Instagram blue picked leaves the stock blue. */
    @Test
    public void eachColorPickedAndInstagramBlue() {
        Settings.ACCENT_COLOR.save(true);
        for (AccentHue hue : AccentHue.values()) {
            Settings.ACCENT_HUE.save(hue);
            int swapped = AccentColor.color(INDIGO);
            if (hue == AccentHue.BLUE) {
                assertEquals(INDIGO, swapped);
                ColorStateList button = button();
                assertSame(button, AccentColor.list(button));
            } else {
                assertEquals(hue.name(), 0xff000000 | hue.fill, swapped);
            }
        }
    }

    /**
     * White text on each fill reads at least as well as on Instagram's own blue, and each link
     * shade reads well on its screen, white for a light one and Prism's black for a dark one.
     */
    @Test
    public void everyColorStaysReadable() {
        double stock = contrast(WHITE, BLUE);
        for (AccentHue hue : AccentHue.values()) {
            if (hue == AccentHue.BLUE) continue;
            assertTrue(hue + ": white on its fill", contrast(WHITE, 0xff000000 | hue.fill) >= 3.9);
            assertTrue(hue + ": white on its fill against stock", contrast(WHITE, 0xff000000 | hue.fill) > stock);
            assertTrue(hue + ": white on it held down", contrast(WHITE, 0xff000000 | hue.pressed) >= 3.9);
            assertTrue(hue + ": its link on white", contrast(WHITE, 0xff000000 | hue.linkOnLight) >= 5.0);
            assertTrue(hue + ": its link on black", contrast(PRISM_BLACK, 0xff000000 | hue.linkOnDark) >= 5.0);
            assertNotEquals(hue + ": a color of its own", BLUE & 0xffffff, hue.fill);
        }
    }

    /**
     * A button's list keeps each state: held down takes the pressed shade, disabled the faded fill
     * at the same alpha, selected keeps its white, and the rest the fill. The same list comes back
     * for the same list asked about again.
     */
    @Test
    public void aListKeepsItsStates() {
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.TEAL);
        ColorStateList stock = button();
        ColorStateList swapped = AccentColor.list(stock);

        assertNotEquals(stock, swapped);
        assertTrue(swapped.isStateful());
        assertEquals(0xff000000 | AccentHue.TEAL.pressed, swapped.getColorForState(PRESSED_STATE, 0));
        assertEquals(0x4d000000 | AccentHue.TEAL.fill, swapped.getColorForState(DISABLED, 0));
        assertEquals(WHITE, swapped.getColorForState(SELECTED, 0));
        assertEquals(0xff000000 | AccentHue.TEAL.fill, swapped.getColorForState(ENABLED, 0));
        assertEquals(0xff000000 | AccentHue.TEAL.fill, swapped.getDefaultColor());
        assertEquals("the stock list is left as it was", INDIGO, stock.getColorForState(ENABLED, 0));
        assertSame(swapped, AccentColor.list(stock));

        Settings.ACCENT_HUE.save(AccentHue.ORANGE);
        assertEquals("a new pick swaps it again", 0xff000000 | AccentHue.ORANGE.fill,
                AccentColor.list(stock).getColorForState(ENABLED, 0));
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 3, 0 found, 0 missing. Counted: "
                + AccentColor.LIST + " 3"), HookStatus.report());
    }

    /** A list with no accent blue in it, a plain color list and a subclass come back as they were. */
    @Test
    public void listsWithoutBlueComeBackAsTheyWere() {
        Settings.ACCENT_COLOR.save(true);
        ColorStateList gray = new ColorStateList(new int[][] {DISABLED, {}}, new int[] {0x4d737373, 0xff737373});
        assertSame(gray, AccentColor.list(gray));
        assertSame(gray, AccentColor.list(gray));
        ColorStateList plain = ColorStateList.valueOf(WHITE);
        assertSame(plain, AccentColor.list(plain));
        ColorStateList blue = ColorStateList.valueOf(BLUE);
        assertEquals(0xff7638fa, AccentColor.list(blue).getDefaultColor());
        ColorStateList subclass = new ColorStateList(new int[][] {{}}, new int[] {BLUE}) {
        };
        assertSame(subclass, AccentColor.list(subclass));
        assertSame(null, AccentColor.list(null));
    }

    /** Paused, not ready yet or Instagram blue picked: the stock blue, in a color and in a list. */
    @Test
    public void pausedUnreadyAndBlueLeaveInstagramsBlue() {
        Settings.ACCENT_COLOR.save(true);
        ColorStateList button = button();
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(BLUE, AccentColor.color(BLUE));
        assertSame(button, AccentColor.list(button));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertEquals(BLUE, AccentColor.color(BLUE));
            assertSame(button, AccentColor.list(button));
        });

        Settings.ACCENT_HUE.save(AccentHue.BLUE);
        assertEquals(BLUE, AccentColor.color(BLUE));
        assertSame(button, AccentColor.list(button));

        Settings.ACCENT_HUE.save(AccentHue.PINK);
        assertEquals(0xffd6005a, AccentColor.color(BLUE));
        assertFalse(button == AccentColor.list(button));
    }

    /** A Prism primary button's list: pressed, disabled, selected white and the fill for the rest. */
    private static ColorStateList button() {
        return new ColorStateList(new int[][] {PRESSED_STATE, DISABLED, SELECTED, {}},
                new int[] {PRESSED, 0x4d4a5df9, WHITE, INDIGO});
    }

    /** The WCAG contrast of two opaque colors. */
    private static double contrast(int first, int second) {
        double a = luminance(first);
        double b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(int color) {
        return 0.2126 * channel(color >> 16) + 0.7152 * channel(color >> 8) + 0.0722 * channel(color);
    }

    private static double channel(int value) {
        double c = (value & 0xff) / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
