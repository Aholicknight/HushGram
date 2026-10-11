/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package androidx.recyclerview.widget;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Stands in for AndroidX's RecyclerView as a profile's Reels grid uses it, by the name it keeps:
 * rows of tiles, of which only the rows on screen are bound, moved by a finger's drag or by
 * scrollBy. Like 450's grid on a phone, it asks Instagram for more only at the end of a drag: a
 * scrollBy to the end loads nothing.
 */
public class RecyclerView extends LinearLayout {
    /** How tall a row is, how many tiles it holds, and how many rows show at once. */
    public static final int ROW = 400;
    public static final int PER_ROW = 3;
    public static final int SHOWN_ROWS = 2;

    /** The reels Instagram has loaded, in the grid's order. */
    public final List<String> reels = new ArrayList<>();
    /** Every scrollBy asked for, in pixels. */
    public final List<Integer> scrolls = new ArrayList<>();
    /** How far each drag pulled the grid on, in pixels, down positive. */
    public final List<Integer> drags = new ArrayList<>();
    /** What Instagram does when a drag ends at the grid's last loaded row, such as loading the next page. */
    public Runnable atEnd;
    /** Makes the view of a tile for a reel. */
    public Function<String, View> tiles;
    private int offset;
    private float lastY;
    private int pulled;

    public RecyclerView(Context context) {
        super(context);
        setOrientation(VERTICAL);
    }

    /** The first row on screen. */
    public int firstRow() {
        return offset / ROW;
    }

    /** Binds the rows on screen, a tile named by its reel for each, as Instagram's binder does. */
    public void bind() {
        removeAllViews();
        int last = (offset + SHOWN_ROWS * ROW - 1) / ROW;
        for (int row = firstRow(); row <= last; row++) {
            if (row * PER_ROW >= reels.size()) break;
            LinearLayout line = new LinearLayout(getContext());
            for (int at = row * PER_ROW; at < Math.min(reels.size(), (row + 1) * PER_ROW); at++) {
                View tile = tiles.apply(reels.get(at));
                tile.setTransitionName(reels.get(at));
                line.addView(tile, new LinearLayout.LayoutParams(360, ROW));
            }
            addView(line, new LinearLayout.LayoutParams(1080, ROW));
        }
    }

    @Override public boolean canScrollVertically(int direction) {
        return direction > 0 ? offset < end() : offset > 0;
    }

    @Override public int computeVerticalScrollOffset() {
        return offset;
    }

    @Override public void scrollBy(int x, int y) {
        scrolls.add(y);
        move(y);
    }

    /** A finger's drag: the grid follows it, and letting go at the loaded end asks for more. */
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastY = event.getY();
                pulled = 0;
                break;
            case MotionEvent.ACTION_MOVE:
                int by = Math.round(lastY - event.getY());
                lastY = event.getY();
                pulled += by;
                move(by);
                break;
            case MotionEvent.ACTION_UP:
                drags.add(pulled);
                if (!canScrollVertically(1) && atEnd != null) atEnd.run();
                break;
            default:
                break;
        }
        return true;
    }

    private void move(int by) {
        offset = Math.max(0, Math.min(end(), offset + by));
        bind();
    }

    /** The furthest the grid scrolls with the reels loaded so far. */
    private int end() {
        int rows = (reels.size() + PER_ROW - 1) / PER_ROW;
        return Math.max(0, (rows - SHOWN_ROWS) * ROW);
    }
}
