/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.content.Context;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;

/**
 * Helper for the Hide the comment emoji row patch (#248): keeps the row of quick emoji Instagram
 * shows above the comment box out of the comment sheet.
 *
 * <p>The row is inflated from a ViewStub into the composer, and the view it inflates has its own
 * id, as does the strip of emoji inside it. {@link ViewHider} sets whichever is on screen gone
 * before the frame that would show it, so the composer closes up around it, and never touches the
 * stub. The comment box, its Post button and the sheet's other parts are not named and stay.
 */
public final class CommentEmojiRow {
    /** The row the composer inflates from its stub, and the strip of emoji in it. */
    static final String PARENT = "comment_emoji_picker_v1_parent";
    static final String CONTAINER = "comment_emoji_picker_v1_emoji_container";

    static final String HOOK = "comment emoji row";
    static final String COUNT_HIDDEN = "comment emoji rows hidden";

    private static final ViewHider HIDER = new ViewHider(FamilyNames.COMMENT_EMOJI_ROW, HOOK, COUNT_HIDDEN,
            () -> Settings.HIDE_COMMENT_EMOJI_ROW.get(), null, PARENT, CONTAINER);

    private CommentEmojiRow() {
    }

    /** Called as the application starts. Does nothing in a build without the patch. */
    public static void install(Context context) {
        HIDER.install(context, SettingsStatus.commentEmojiRow());
    }

    static ViewHider hider() {
        return HIDER;
    }
}
