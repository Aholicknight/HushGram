/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.text.Layout;
import android.text.TextPaint;
import android.view.animation.AnimationUtils;
import android.widget.TextView;

/**
 * The HushGram name over the settings, in Instagram's gradient. As the screen opens, and every
 * {@link #PERIOD_MS} after, the colours flow along the word and a white sheen sweeps over it, then
 * it holds still until the next sweep, so nothing redraws between sweeps. Any other title, such as
 * a search's, is plain white, and with Android's animations off the gradient stays still.
 */
final class ShimmerTitle extends TextView {
    /** One sweep and the still wait after it. */
    static final long PERIOD_MS = 6000;
    /** How long a sweep runs. */
    static final long SWEEP_MS = 1400;
    /**
     * Instagram's gradient, yellow through orange, pink and purple to blue, each stop lightened
     * where needed so it reads at 7:1 or better on the black bar.
     */
    static final int[] COLORS = {0xFFFEDA75, 0xFFFA7E1E, 0xFFFF5C9A, 0xFFC77DFF, 0xFF85A1FF};
    private static final int[] SHEEN = {0x00FFFFFF, 0xB3FFFFFF, 0x00FFFFFF};

    private final Matrix move = new Matrix();
    private LinearGradient colors;
    private LinearGradient sheen;
    private float shadedWidth = -1f;
    private float shadedHeight = -1f;
    /** When the first sweep started, so it plays as the screen opens. */
    private long startedAt = -1L;
    private boolean brand = true;

    ShimmerTitle(Context context) {
        super(context);
        setTextColor(Color.WHITE);
    }

    /** Shows [title], HushGram's own name when [brand] and so in the gradient, plain white otherwise. */
    void show(CharSequence title, boolean brand) {
        this.brand = brand;
        setText(title);
        invalidate();
    }

    /** Whether the title wears the gradient: HushGram's own name. */
    boolean wearsGradient() {
        return brand;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startedAt = -1L;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        TextPaint paint = getPaint();
        Layout layout = getLayout();
        if (!brand || layout == null || layout.getLineCount() == 0) {
            paint.setShader(null);
            super.onDraw(canvas);
            return;
        }
        float left = layout.getLineLeft(0);
        float width = Math.max(1f, layout.getLineRight(0) - left);
        float height = Math.max(1f, layout.getHeight());
        if (colors == null || width != shadedWidth || height != shadedHeight) {
            colors = new LinearGradient(0f, 0f, width, 0f, COLORS, null, Shader.TileMode.MIRROR);
            float band = height * 1.6f;
            sheen = new LinearGradient(0f, 0f, band, band * 0.35f, SHEEN, null, Shader.TileMode.CLAMP);
            shadedWidth = width;
            shadedHeight = height;
        }
        boolean moving = ValueAnimator.areAnimatorsEnabled();
        long now = AnimationUtils.currentAnimationTimeMillis();
        if (startedAt < 0L) startedAt = now;
        float sweep = moving ? sweep(now - startedAt) : -1f;

        // The text sits at the layout's line left inside the padding, so the shaders start there.
        float x = getCompoundPaddingLeft() + left;
        move.setTranslate(x + flow(sweep) * 2f * width, 0f);
        colors.setLocalMatrix(move);
        paint.setShader(colors);
        super.onDraw(canvas);

        if (sweep >= 0f) {
            float band = height * 1.6f;
            move.setTranslate(x - band + sweep * (width + band), getExtendedPaddingTop());
            sheen.setLocalMatrix(move);
            paint.setShader(sheen);
            super.onDraw(canvas);
            postInvalidateOnAnimation();
        } else if (moving) {
            long wait = PERIOD_MS - (now - startedAt) % PERIOD_MS;
            postInvalidateDelayed(Math.max(16L, wait));
        }
        paint.setShader(null);
    }

    /**
     * How far into its sweep the title is [sinceStart] ms after the first one began, 0 to 1, or
     * -1 while it waits for the next.
     */
    static float sweep(long sinceStart) {
        if (sinceStart < 0L) return -1f;
        long into = sinceStart % PERIOD_MS;
        return into < SWEEP_MS ? into / (float) SWEEP_MS : -1f;
    }

    /**
     * How far the gradient has flowed for [sweep], eased in and out. One whole sweep moves it a full
     * mirrored round, so it holds the same colours between sweeps as it had before the first.
     */
    static float flow(float sweep) {
        if (sweep <= 0f || sweep >= 1f) return 0f;
        return (float) ((1.0 - Math.cos(Math.PI * sweep)) / 2.0);
    }
}
