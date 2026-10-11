/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package androidx.recyclerview.widget;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Stands in for AndroidX's RecyclerView as a profile's Reels grid uses it, by the name it keeps:
 * rows of tiles, of which only the rows on screen are bound, moved a row at a time by scrollBy.
 */
public class RecyclerView extends LinearLayout {
    /** How tall a row is, how many tiles it holds, and how many rows show at once. */
    public static final int ROW = 400;
    public static final int PER_ROW = 3;
    public static final int SHOWN_ROWS = 2;

    /** The reels Instagram has loaded, in the grid's order. */
    public final List<String> reels = new ArrayList<>();
    /** Every scroll asked for, in pixels. */
    public final List<Integer> scrolls = new ArrayList<>();
    /** What Instagram does when the grid's last loaded row shows, such as loading the next page. */
    public Runnable atEnd;
    /** Makes the view of a tile for a reel. */
    public Function<String, View> tiles;
    private int firstRow;

    public RecyclerView(Context context) {
        super(context);
        setOrientation(VERTICAL);
    }

    /** The first row on screen. */
    public int firstRow() {
        return firstRow;
    }

    /** Binds the rows on screen, a tile named by its reel for each, as Instagram's binder does. */
    public void bind() {
        removeAllViews();
        for (int row = firstRow; row < firstRow + SHOWN_ROWS; row++) {
            if (row * PER_ROW >= reels.size()) break;
            LinearLayout line = new LinearLayout(getContext());
            for (int at = row * PER_ROW; at < Math.min(reels.size(), (row + 1) * PER_ROW); at++) {
                View tile = tiles.apply(reels.get(at));
                tile.setTransitionName(reels.get(at));
                line.addView(tile, new LinearLayout.LayoutParams(360, ROW));
            }
            addView(line, new LinearLayout.LayoutParams(1080, ROW));
        }
        if (!canScrollVertically(1) && atEnd != null) atEnd.run();
    }

    @Override public boolean canScrollVertically(int direction) {
        return direction > 0 ? (firstRow + SHOWN_ROWS) * PER_ROW < reels.size() : firstRow > 0;
    }

    @Override public void scrollBy(int x, int y) {
        scrolls.add(y);
        int rows = y / ROW;
        if (rows == 0 && y != 0) rows = y > 0 ? 1 : -1;
        int lastFirst = Math.max(0, (reels.size() + PER_ROW - 1) / PER_ROW - SHOWN_ROWS);
        firstRow = Math.max(0, Math.min(lastFirst, firstRow + rows));
        bind();
    }
}
