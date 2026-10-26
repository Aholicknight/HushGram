/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * The settings title in Instagram's gradient: every stop reads on the black bar, a sweep runs as
 * the screen opens and once each period, the gradient ends a sweep where it began, the name is
 * drawn in more than one colour, and a search's title stays plain white.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 30, qualifiers = "w411dp-h891dp-xxhdpi")
public class ShimmerTitleTest {
    @Test
    public void everyGradientStopReadsOnTheBlackBar() {
        for (int color : ShimmerTitle.COLORS) {
            assertTrue(Integer.toHexString(color), contrast(color, ScreenColors.DEFAULT.background) >= 7.0);
        }
    }

    @Test
    public void aSweepRunsAsTheScreenOpensAndOnceEachPeriod() {
        assertEquals(0f, ShimmerTitle.sweep(0L), 0f);
        assertEquals(0.5f, ShimmerTitle.sweep(ShimmerTitle.SWEEP_MS / 2), 0.001f);
        assertEquals(-1f, ShimmerTitle.sweep(ShimmerTitle.SWEEP_MS), 0f);
        assertEquals(-1f, ShimmerTitle.sweep(ShimmerTitle.PERIOD_MS - 1), 0f);
        assertEquals(0f, ShimmerTitle.sweep(ShimmerTitle.PERIOD_MS), 0f);
        assertEquals(-1f, ShimmerTitle.sweep(-5L), 0f);
    }

    @Test
    public void theGradientEndsASweepWhereItBegan() {
        assertEquals(0f, ShimmerTitle.flow(0f), 0f);
        assertEquals(0f, ShimmerTitle.flow(1f), 0f);
        assertEquals(0f, ShimmerTitle.flow(-1f), 0f);
        assertEquals(0.5f, ShimmerTitle.flow(0.5f), 0.001f);
        float before = 0f;
        for (int step = 1; step < 20; step++) {
            float now = ShimmerTitle.flow(step / 20f);
            assertTrue(now > before);
            before = now;
        }
    }

    @Test
    public void theNameIsDrawnInTheGradientAndASearchTitleInWhite() {
        ShimmerTitle title = new ShimmerTitle(RuntimeEnvironment.getApplication());
        // As in the bar: a laid-out TextView reads its layout params when its text changes.
        title.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.show("HushGram", true);
        assertTrue(title.wearsGradient());
        Bitmap named = draw(title);
        assertTrue("warm letters", anyPixel(named, true));
        assertTrue("cool letters", anyPixel(named, false));
        assertNull("the shader doesn't stay on the paint", title.getPaint().getShader());

        title.show("Results for blur", false);
        assertFalse(title.wearsGradient());
        Bitmap search = draw(title);
        int lit = 0;
        for (int x = 0; x < search.getWidth(); x++) {
            for (int y = 0; y < search.getHeight(); y++) {
                int pixel = search.getPixel(x, y);
                if (Color.alpha(pixel) == 0) continue;
                lit++;
                assertTrue(Integer.toHexString(pixel), Math.abs(Color.red(pixel) - Color.blue(pixel)) <= 2);
            }
        }
        assertTrue(lit > 0);
    }

    private static Bitmap draw(ShimmerTitle title) {
        title.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        title.layout(0, 0, title.getMeasuredWidth(), title.getMeasuredHeight());
        Bitmap bitmap = Bitmap.createBitmap(title.getWidth(), title.getHeight(), Bitmap.Config.ARGB_8888);
        title.draw(new Canvas(bitmap));
        return bitmap;
    }

    /** Whether a solid letter pixel is clearly warm (red over blue) or, for not [warm], clearly cool. */
    private static boolean anyPixel(Bitmap bitmap, boolean warm) {
        for (int x = 0; x < bitmap.getWidth(); x++) {
            for (int y = 0; y < bitmap.getHeight(); y++) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.alpha(pixel) < 250) continue;
                int lean = Color.red(pixel) - Color.blue(pixel);
                if (warm ? lean > 60 : lean < -60) return true;
            }
        }
        return false;
    }

    private static double contrast(int one, int other) {
        double a = Color.luminance(one) + 0.05, b = Color.luminance(other) + 0.05;
        return Math.max(a, b) / Math.min(a, b);
    }
}
