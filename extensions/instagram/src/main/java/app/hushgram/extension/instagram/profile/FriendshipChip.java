/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableContainer;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.RippleDrawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.Space;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.profile.FriendshipStatus.Relation;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * The chip Show it as a chip puts under the posts, followers and following counts on someone's
 * profile: an outlined pill with a person icon that says Follows you, Following each other or
 * Doesn't follow you.
 *
 * <p>The counts are a block of their own in the profile header. The chip isn't a view put in among
 * Instagram's: the block gets room for it as padding at its bottom, and the chip is drawn there in
 * the block's overlay. Whatever Instagram lays out under the counts then moves down with them,
 * whichever layout holds the block. The block is the one holding the followers count, under the
 * nearest view that holds both it and the pronouns slot the hooks are handed.
 *
 * <p>Small, under the picture (#157) draws a smaller pill centered under the profile picture. The
 * picture's column, the child holding Instagram's fixed-size picture frame of the nearest view that
 * holds the counts too, gets the room as padding at its bottom, which it can only take when it's as
 * tall as what it holds. The pill is drawn in that shared view's overlay, since it's wider than the
 * picture. Instagram's layouts can't be read offline, so each layout is checked: a column that
 * isn't sized to its content, or a pill that would touch anything the shared view's other children
 * show, puts the small pill under the counts instead.
 *
 * <p>Runs on the main thread, where Instagram binds the header.
 */
final class FriendshipChip {
    /** The number of followers, inside the counts block. */
    static final String FOLLOWERS_COUNT = "profile_header_familiar_followers_value";

    /** The fixed-size frame Instagram puts the profile picture in, by name. */
    static final String PICTURE_FRAME = "row_profile_header_imageview_frame_layout";

    /** What a chip put under the picture, or moved under the counts for want of room there, is counted under. */
    static final String UNDER_PICTURE = "Follow chip under the picture";
    static final String NO_PICTURE_ROOM = "Follow chip under the counts, no room under the picture";
    /**
     * Counted beside NO_PICTURE_ROOM with why: no picture column, the height Instagram holds the
     * column at, or what the small pill would have run into. 451's header builds in code, so the
     * report is where a phone tells which it was (#157).
     */
    static final String NO_COLUMN = "no picture column";
    static final String COLUMN_HELD = "picture column held at ";
    static final String TOO_WIDE = "small chip wider than the header";
    static final String WOULD_TOUCH = "small chip would touch ";

    /** The icons, Instagram's own, by name. A build without them gets a chip without an icon. */
    static final String FOLLOWING_ICON = "instagram_user_following_outline_24";
    static final String NOT_FOLLOWING_ICON = "instagram_user_unfollow_outline_24";

    /** How far up from the pronouns slot the counts are looked for. */
    private static final int MAX_DEPTH = 8;

    /** Set by tests that have no Instagram resources: the followers count's id. */
    static int followersCountIdForTests;
    /** The followers count's id in this app, or -1 before the first lookup. */
    private static volatile int followersCountId = -1;

    /** Set by tests that have no Instagram resources: the picture frame's id. */
    static int pictureFrameIdForTests;
    /** The picture frame's id in this app, or -1 before the first lookup. */
    private static volatile int pictureFrameId = -1;

    /**
     * The chips showing, by the counts block they're under. Blocks go when their screen does: a
     * chip holds its block only weakly, so the entry doesn't keep the key, the header or the
     * Activity alive, and goes with them.
     */
    private static final Map<View, Chip> SHOWN = new WeakHashMap<>();

    private FriendshipChip() {
    }

    /** Whether any header has a chip, so one may need taking away. */
    static boolean anyShown() {
        return !SHOWN.isEmpty();
    }

    /**
     * Shows [relation]'s chip on the profile header [inside] is part of, in its own color and the
     * typeface of [style] when there is one: under the counts, or small under the picture as
     * [place] says. False when the counts can't be found.
     */
    static boolean show(View inside, Relation relation, @Nullable TextView style, FollowLabelPlace place) {
        View block = counts(inside);
        if (block == null) return false;
        boolean small = place == FollowLabelPlace.UNDER_PICTURE;
        Chip chip = SHOWN.get(block);
        boolean blocked = small && chip != null && chip.pictureBlocked;
        View column = small && !blocked ? pictureColumn(block) : null;
        if (chip != null && !chip.fits(small, column)) {
            SHOWN.remove(block);
            chip.remove();
            chip = null;
        }
        if (chip == null) {
            chip = new Chip(block, small, column);
            chip.pictureBlocked = blocked;
            if (small && !chip.underPicture) {
                HookStatus.counted(FamilyNames.FRIENDSHIP_STATUS, NO_PICTURE_ROOM);
                if (!blocked) HookStatus.counted(FamilyNames.FRIENDSHIP_STATUS, noColumnReason(block));
            }
            SHOWN.put(block, chip);
        }
        chip.show(relation, style);
        return true;
    }

    /** Takes the chip off the header [inside] is part of, giving its counts their room back. */
    static void clear(View inside) {
        if (SHOWN.isEmpty()) return;
        View block = counts(inside);
        if (block == null) return;
        Chip chip = SHOWN.remove(block);
        if (chip != null) chip.remove();
    }

    /** The chip under [block], or null. */
    @Nullable
    static Chip shownUnder(View block) {
        return SHOWN.get(block);
    }

    /**
     * The counts block of the header [inside] is part of: going up from [inside], the first view
     * holding the followers count, and in it the child the count is in. Null when there's none, or
     * it isn't on screen.
     */
    @Nullable
    static View counts(View inside) {
        int id = followersCountId(inside.getContext());
        if (id == 0) return null;
        View at = inside;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            ViewParent parent = at.getParent();
            if (!(parent instanceof ViewGroup)) return null;
            ViewGroup group = (ViewGroup) parent;
            View count = group.findViewById(id);
            if (count != null && count != group) {
                View block = childHolding(group, count);
                return block != null && block.getVisibility() == View.VISIBLE ? block : null;
            }
            at = group;
        }
        return null;
    }

    /** The child of [group] that [view] is, or is inside. */
    @Nullable
    private static View childHolding(ViewGroup group, View view) {
        View at = view;
        while (at.getParent() != group) {
            ViewParent parent = at.getParent();
            if (!(parent instanceof View)) return null;
            at = (View) parent;
        }
        return at;
    }

    private static int followersCountId(Context context) {
        if (followersCountIdForTests != 0) return followersCountIdForTests;
        if (followersCountId == -1) {
            followersCountId = context.getResources().getIdentifier(FOLLOWERS_COUNT, "id", context.getPackageName());
        }
        return followersCountId;
    }

    /**
     * The picture's column of the header the counts [block] is in: going up from [block], the first
     * view holding the picture frame, and in it the child the frame is in. Null when there's none,
     * the counts are in that child too, or it isn't on screen or isn't sized to its content, so
     * room at its bottom would squeeze the picture rather than grow the column.
     */
    @Nullable
    static View pictureColumn(View block) {
        View column = columnHoldingPicture(block);
        if (column == null) return null;
        ViewGroup.LayoutParams params = column.getLayoutParams();
        return params != null && params.height == ViewGroup.LayoutParams.WRAP_CONTENT ? column : null;
    }

    /** Why [block]'s header has no picture column to grow: none found, or the height its column is held at. */
    static String noColumnReason(View block) {
        View column = columnHoldingPicture(block);
        if (column == null) return NO_COLUMN;
        ViewGroup.LayoutParams params = column.getLayoutParams();
        if (params == null) return COLUMN_HELD + "no layout params";
        if (params.height == ViewGroup.LayoutParams.MATCH_PARENT) return COLUMN_HELD + "its parent's height";
        if (params.height == 0) return COLUMN_HELD + "0, sized by its constraints";
        return COLUMN_HELD + params.height + " px";
    }

    /** The picture's column of [block]'s header whatever its size, or null when there's none. */
    @Nullable
    private static View columnHoldingPicture(View block) {
        int id = pictureFrameId(block.getContext());
        if (id == 0) return null;
        View at = block;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            ViewParent parent = at.getParent();
            if (!(parent instanceof ViewGroup)) return null;
            ViewGroup group = (ViewGroup) parent;
            View frame = group.findViewById(id);
            if (frame != null && frame != group) {
                View column = childHolding(group, frame);
                if (column == null || column == at || column.getVisibility() != View.VISIBLE) return null;
                return column;
            }
            at = group;
        }
        return null;
    }

    private static int pictureFrameId(Context context) {
        if (pictureFrameIdForTests != 0) return pictureFrameIdForTests;
        if (pictureFrameId == -1) {
            pictureFrameId = context.getResources().getIdentifier(PICTURE_FRAME, "id", context.getPackageName());
        }
        return pictureFrameId;
    }

    /**
     * Blue for Follows you, green for Following each other and red for Doesn't follow you. A light
     * profile gets darker shades, since the bright ones are hard to read on white.
     */
    static int color(Relation relation, boolean light) {
        switch (relation) {
            case FOLLOWING_EACH_OTHER:
                return light ? Color.rgb(0x18, 0x80, 0x38) : Color.rgb(0x2E, 0xCC, 0x71);
            case DOESNT_FOLLOW_YOU:
                return light ? Color.rgb(0xD9, 0x30, 0x25) : Color.rgb(0xED, 0x49, 0x56);
            default:
                return light ? Color.rgb(0x1A, 0x73, 0xE8) : Color.rgb(0x37, 0x97, 0xEF);
        }
    }

    /**
     * Whether the profile is light: the pronouns line's words are dark (Instagram's gray there is
     * well under a quarter luminance on light and well over on dark), or with no line to go by, the
     * phone isn't in dark mode.
     */
    static boolean light(Context context, @Nullable TextView style) {
        if (style != null) return Color.luminance(style.getCurrentTextColor()) < 0.25f;
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night != Configuration.UI_MODE_NIGHT_YES;
    }

    /** The chip's words for [relation]. */
    static String text(Relation relation) {
        switch (relation) {
            case FOLLOWING_EACH_OTHER:
                return L10n.t("Following each other");
            case DOESNT_FOLLOW_YOU:
                return L10n.t("Doesn't follow you");
            default:
                return L10n.t("Follows you");
        }
    }

    /**
     * One counts block's chip: the room it took, under the counts or the picture, and the drawing in
     * an overlay. It's the value [SHOWN] keeps for the block, so it holds every view weakly: a
     * strong hold would keep the key alive and the entry forever.
     */
    static final class Chip implements View.OnLayoutChangeListener {
        /** The counts block, which the chip is kept by. */
        final WeakReference<View> block;
        /** The view the room is made in: the counts block, or the picture's column. */
        WeakReference<View> host;
        /** The view whose overlay the pill is drawn in: the counts block, or the one holding the column and the counts. */
        WeakReference<View> canvas;
        /** Whether the pill is small, as Small, under the picture draws it, wherever it went. */
        final boolean small;
        /** Whether the pill went under the picture (or is waiting for a layout to say if it fits there). */
        boolean underPicture;
        /** Whether this header had no room under the picture, so a small pill went under the counts. */
        boolean pictureBlocked;
        /** Whether the pill under the picture has been counted in the diagnostics, which it is once. */
        private boolean counted;
        /** Why the pill didn't fit under the picture, counted when it moves under the counts. */
        @Nullable private String blockedBy;
        final Pill pill;
        /** The host's own bottom padding, which the chip's room goes on top of. */
        int ownBottom;
        final int room;
        private final int countsRoom;

        Chip(View block, boolean small, @Nullable View column) {
            this.block = new WeakReference<>(block);
            this.small = small;
            pill = new Pill(block.getContext(), small);
            countsRoom = pill.getIntrinsicHeight() + pill.dp(small ? 6 : 8);
            if (column != null && column.getParent() instanceof View) {
                underPicture = true;
                room = pill.getIntrinsicHeight() + pill.dp(6);
                attach(column, (View) column.getParent());
            } else {
                room = countsRoom;
                attach(block, block);
            }
        }

        private void attach(View host, View canvas) {
            this.host = new WeakReference<>(host);
            this.canvas = new WeakReference<>(canvas);
            ownBottom = host.getPaddingBottom();
            canvas.getOverlay().add(pill);
            canvas.addOnLayoutChangeListener(this);
        }

        /** Whether this chip is the size [small] asks for, under the picture's [column] or under the counts with none. */
        boolean fits(boolean small, @Nullable View column) {
            if (this.small != small) return false;
            if (column == null) return !underPicture;
            return underPicture && host.get() == column;
        }

        void show(Relation relation, @Nullable TextView style) {
            View host = this.host.get();
            if (host == null) return;
            Context context = host.getContext();
            boolean follows = relation != Relation.DOESNT_FOLLOW_YOU;
            Drawable icon = icon(context, follows ? FOLLOWING_ICON : NOT_FOLLOWING_ICON);
            int color = color(relation, light(context, style));
            pill.set(text(relation), icon, color, style != null ? style.getTypeface() : null);
            makeRoom(host);
            place();
        }

        /** Puts the chip's room on top of [host]'s own bottom padding, or Instagram's newer padding, which becomes its own. */
        private void makeRoom(View host) {
            int bottom = host.getPaddingBottom();
            if (bottom != ownBottom + roomNow()) {
                ownBottom = bottom;
                host.setPaddingRelative(host.getPaddingStart(), host.getPaddingTop(), host.getPaddingEnd(), ownBottom + roomNow());
            }
        }

        /** The room the chip takes where it is now: a pill moved under the counts takes the counts' room. */
        int roomNow() {
            return underPicture ? room : countsRoom;
        }

        void remove() {
            View canvas = this.canvas.get();
            if (canvas != null) {
                canvas.getOverlay().remove(pill);
                canvas.removeOnLayoutChangeListener(this);
            }
            View host = this.host.get();
            if (host != null && host.getPaddingBottom() == ownBottom + roomNow()) {
                host.setPaddingRelative(host.getPaddingStart(), host.getPaddingTop(), host.getPaddingEnd(), ownBottom);
            }
        }

        @Override
        public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                   int oldLeft, int oldTop, int oldRight, int oldBottom) {
            place();
        }

        void place() {
            View host = this.host.get();
            View canvas = this.canvas.get();
            if (host == null || canvas == null) return;
            pill.setLayoutDirection(canvas.getLayoutDirection());
            if (underPicture) placeUnderPicture(host, (ViewGroup) canvas);
            else placeUnderCounts(host);
        }

        /** Puts the pill at the start of the room under the counts of [block], lined up with them. */
        private void placeUnderCounts(View block) {
            int width = pill.getIntrinsicWidth();
            int height = pill.getIntrinsicHeight();
            boolean rtl = block.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            int left = rtl ? block.getWidth() - block.getPaddingEnd() - width : block.getPaddingStart();
            int bottom = block.getHeight() - ownBottom;
            pill.setBounds(left, bottom - height, left + width, bottom);
        }

        /**
         * Centers the pill under the picture in [column]'s room, within [row], the view holding the
         * column and the counts. Until [column] is laid out with its room nothing shows. A pill that won't fit in
         * [row] or would touch another of its children shows nothing, and the chip moves under the
         * counts once the layout pass is over.
         */
        private void placeUnderPicture(View column, ViewGroup row) {
            int width = pill.getIntrinsicWidth();
            int height = pill.getIntrinsicHeight();
            if (row.getWidth() == 0 || column.getHeight() == 0 || column.isLayoutRequested()) {
                pill.setBounds(0, 0, 0, 0);
                return;
            }
            View frame = column.findViewById(pictureFrameId(column.getContext()));
            View centered = frame != null ? frame : column;
            int middle = leftIn(centered, row) + centered.getWidth() / 2;
            int start = row.getPaddingLeft();
            int end = row.getWidth() - row.getPaddingRight();
            int left = Math.max(start, Math.min(middle - width / 2, end - width));
            int bottom = column.getTop() + column.getHeight() - ownBottom;
            Rect bounds = new Rect(left, bottom - height, left + width, bottom);
            View touched = width > end - start ? null : touching(row, column, bounds);
            if (width > end - start || touched != null) {
                blockedBy = touched == null ? TOO_WIDE : WOULD_TOUCH + name(touched);
                pill.setBounds(0, 0, 0, 0);
                row.post(this::moveUnderCounts);
                return;
            }
            pill.setBounds(bounds);
            if (!counted) {
                counted = true;
                HookStatus.counted(FamilyNames.FRIENDSHIP_STATUS, UNDER_PICTURE);
            }
        }

        /** The view under [row]'s children but [column] that shows something [bounds] touch, or null. */
        @Nullable
        private static View touching(ViewGroup row, View column, Rect bounds) {
            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i);
                if (child == column) continue;
                View shown = shown(child, bounds, child.getLeft(), child.getTop());
                if (shown != null) return shown;
            }
            return null;
        }

        /**
         * The view in [view], at [left] and [top] in the row, that shows something inside [bounds], or
         * null: a group counts by its background when that draws at rest, else by its children. 451
         * stretches the counts block and its tappable counts to the picture's height, empty and with
         * only a press ripple under the numbers, and a pill wider than the picture reaching into that
         * room covers nothing (#157, S22 2026-10-14).
         */
        @Nullable
        private static View shown(View view, Rect bounds, int left, int top) {
            if (view.getVisibility() != View.VISIBLE || view.getWidth() == 0 || view.getHeight() == 0) return null;
            if (!Rect.intersects(bounds, new Rect(left, top, left + view.getWidth(), top + view.getHeight()))) return null;
            if (drawsAtRest(view.getBackground())) return view;
            if (!(view instanceof ViewGroup)) return drawsContent(view) ? view : null;
            ViewGroup group = (ViewGroup) view;
            if (group.getChildCount() == 0) return group.willNotDraw() ? null : group;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                View shown = shown(child, bounds, left + child.getLeft() - group.getScrollX(), top + child.getTop() - group.getScrollY());
                if (shown != null) return shown;
            }
            return null;
        }

        /** Whether a view with no background of its own draws anything: not a spacer, an empty text or an empty image. */
        private static boolean drawsContent(View view) {
            if (view instanceof Space || view.getClass() == View.class) return false;
            if (view instanceof TextView) {
                TextView text = (TextView) view;
                if (text.length() > 0) return true;
                for (Drawable side : text.getCompoundDrawablesRelative()) {
                    if (side != null) return true;
                }
                return false;
            }
            if (view instanceof ImageView) return ((ImageView) view).getDrawable() != null;
            return true;
        }

        /**
         * Whether [background] shows anything while nothing is pressed: not a ripple with nothing but
         * its mask under it, nor a clear color.
         */
        private static boolean drawsAtRest(@Nullable Drawable background) {
            if (background == null || background.getAlpha() == 0) return false;
            if (background instanceof ColorDrawable) return Color.alpha(((ColorDrawable) background).getColor()) != 0;
            if (background instanceof RippleDrawable) {
                RippleDrawable ripple = (RippleDrawable) background;
                for (int i = 0; i < ripple.getNumberOfLayers(); i++) {
                    if (ripple.getId(i) != android.R.id.mask && drawsAtRest(ripple.getDrawable(i))) return true;
                }
                return false;
            }
            if (background instanceof DrawableContainer) return drawsAtRest(background.getCurrent());
            if (background instanceof DrawableWrapper) return drawsAtRest(((DrawableWrapper) background).getDrawable());
            return true;
        }

        /** [view]'s id name, or its class's name when it has none Instagram named. */
        private static String name(View view) {
            int id = view.getId();
            if (id != View.NO_ID) {
                try {
                    return view.getResources().getResourceEntryName(id);
                } catch (RuntimeException unnamed) {
                    // An id made in code has no name.
                }
            }
            return view.getClass().getSimpleName();
        }

        /** [view]'s left edge in [ancestor]'s coordinates. */
        private static int leftIn(View view, View ancestor) {
            int left = 0;
            View at = view;
            while (at != ancestor) {
                left += at.getLeft() - at.getScrollX();
                ViewParent parent = at.getParent();
                if (!(parent instanceof View)) return left;
                at = (View) parent;
            }
            return left;
        }

        /** Gives the picture's column its room back and puts the small pill under the counts, for good on this header. */
        void moveUnderCounts() {
            if (!underPicture) return;
            View block = this.block.get();
            if (block == null || SHOWN.get(block) != this) return;
            remove();
            underPicture = false;
            pictureBlocked = true;
            HookStatus.counted(FamilyNames.FRIENDSHIP_STATUS, NO_PICTURE_ROOM);
            if (blockedBy != null) HookStatus.counted(FamilyNames.FRIENDSHIP_STATUS, blockedBy);
            attach(block, block);
            makeRoom(block);
            place();
        }

        @Nullable
        private static Drawable icon(Context context, String name) {
            int id = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
            if (id == 0) return null;
            try {
                Drawable icon = context.getDrawable(id);
                return icon == null ? null : icon.mutate();
            } catch (RuntimeException missing) {
                return null;
            }
        }
    }

    /** The outlined pill: an icon at the start, then the words, in the relation's color. */
    static final class Pill extends Drawable {
        private final Paint words = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final DisplayMetrics metrics;
        private final RectF shape = new RectF();
        private final int height;
        private final int padding;
        private final int iconSize;
        private final int iconGap;
        String text = "";
        @Nullable Drawable icon;

        Pill(Context context) {
            this(context, false);
        }

        /** The usual pill, or with [small] the one Small, under the picture draws: smaller words, icon and padding. */
        Pill(Context context, boolean small) {
            metrics = context.getResources().getDisplayMetrics();
            words.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, small ? 12 : 13, metrics));
            outline.setStyle(Paint.Style.STROKE);
            outline.setStrokeWidth(Math.max(1, dp(1)));
            // 28dp (24dp small) at the usual text size, and taller when the phone's font scale makes
            // the words taller than that, so the outline never clips them.
            height = small ? Math.max(dp(24), textHeight() + dp(6)) : Math.max(dp(28), textHeight() + dp(8));
            padding = dp(small ? 8 : 12);
            iconSize = dp(small ? 14 : 16);
            iconGap = dp(small ? 4 : 6);
        }

        int dp(float value) {
            return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, metrics));
        }

        /** How tall the words are at the current text size, ascent to descent. */
        int textHeight() {
            Paint.FontMetrics font = words.getFontMetrics();
            return (int) Math.ceil(font.descent - font.ascent);
        }

        void set(String text, @Nullable Drawable icon, int color, @Nullable Typeface typeface) {
            this.text = text;
            this.icon = icon;
            words.setColor(color);
            words.setTypeface(typeface);
            outline.setColor(Color.argb(Color.alpha(color) * 2 / 5, Color.red(color), Color.green(color), Color.blue(color)));
            if (icon != null) icon.setTint(color);
            invalidateSelf();
        }

        int color() {
            return words.getColor();
        }

        @Override
        public int getIntrinsicWidth() {
            int iconWidth = icon != null ? iconSize + iconGap : 0;
            return padding * 2 + iconWidth + (int) Math.ceil(words.measureText(text));
        }

        @Override
        public int getIntrinsicHeight() {
            return height;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            float inset = outline.getStrokeWidth() / 2;
            shape.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset);
            float radius = shape.height() / 2;
            canvas.drawRoundRect(shape, radius, radius, outline);

            boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            int middle = bounds.centerY();
            float textWidth = words.measureText(text);
            int iconStart = rtl ? bounds.right - padding - iconSize : bounds.left + padding;
            if (icon != null) {
                icon.setBounds(iconStart, middle - iconSize / 2, iconStart + iconSize, middle + iconSize / 2);
                icon.draw(canvas);
            }
            int iconWidth = icon != null ? iconSize + iconGap : 0;
            float textStart = rtl ? bounds.right - padding - iconWidth - textWidth : bounds.left + padding + iconWidth;
            Paint.FontMetrics font = words.getFontMetrics();
            canvas.drawText(text, textStart, middle - (font.ascent + font.descent) / 2, words);
        }

        @Override
        public void setAlpha(int alpha) {
            words.setAlpha(alpha);
            outline.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            words.setColorFilter(filter);
            outline.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
