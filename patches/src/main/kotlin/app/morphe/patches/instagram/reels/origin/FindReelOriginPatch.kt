/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.origin

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.Method

internal const val REEL_ORIGIN_PATCH = "Find the reel you came from"

/**
 * Takes you back to the reel you were watching from its creator's profile (#30). Opening a profile
 * from a reel remembers the reel, and the profile's Reels tab scrolls its grid to that reel's tile
 * and outlines it. In the default selection with its switch off.
 */
@Suppress("unused")
val findReelOriginPatch = bytecodePatch(
    name = "Find the reel you came from",
    description = "Open a creator's profile from a reel, then their Reels tab, and it scrolls to that reel and " +
        "outlines it for a moment. Starts off. Turn it on in HushGram settings > Reels.",
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("reelOrigin")
        hookReelOrigin()
        enableStatus("reelOrigin")
    }
}

/**
 * Two calls, each first in its method and reading only what it hands over. The profile opener hands
 * the reel's media, read from its one argument into v0, a local nothing has written yet. The Reels
 * tab hands itself from onResume. Neither returns anything, so no register or branch of Instagram's
 * changes, and the extension does its work after onResume has returned.
 */
internal fun BytecodePatchContext.hookReelOrigin() {
    val targets = findReelOriginTargets()
    val media = targets.media
    mutable(targets.openProfile).addInstructions(
        0,
        """
            move-object/from16 v0, p1
            iget-object v0, v0, ${media.definingClass}->${media.name}:${media.type}
            invoke-static/range { v0 .. v0 }, $OPENING
        """,
    )
    mutable(targets.tabResume).addInstructions(0, "invoke-static/range { p0 .. p0 }, $TAB_RESUMED")
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }
