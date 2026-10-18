/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.emojirow

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

internal const val COMMENT_EMOJI_ROW_STATUS = "commentEmojiRow"

/**
 * Takes the row of quick emoji off above the comment box (#248). See CommentEmojiRow in the extension.
 *
 * It changes no Instagram code. The row is inflated from a ViewStub into the composer and the
 * extension sets the view it inflates gone before a frame shows it, found by the ids Instagram's own
 * layout gives it. The application-start hook the settings patch already writes is what starts the
 * watching, so this patch only switches the status method on, and the settings screen then offers
 * the switch. In the default selection with its switch off.
 */
@Suppress("unused")
val hideCommentEmojiRowPatch = bytecodePatch(
    name = "Hide the comment emoji row",
    description = "Takes the row of quick emoji off above the comment box. The box itself stays. Starts off. " +
        "Turn it on in HushGram settings > Comments.",
) {
    category("Interaction")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(COMMENT_EMOJI_ROW_STATUS)
        enableStatus(COMMENT_EMOJI_ROW_STATUS)
    }
}
