/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.swipeprofile

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.typesMarked
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Swipe left to open the profile"
internal const val SWIPE_ALLOWED_HOOK = "$EXTENSION_PACKAGE/reels/SwipeToProfile;->allow(Z)Z"

/** The marker, less its release, of the Reels viewer's check of whether a swipe may go from a reel to its side panel. */
internal const val SWIPE_ALLOWED = "ClipsViewerSwipeNavigationDelegate_isSwipeFromReelsToActionAllowed"

private const val SESSION = "Lcom/instagram/common/session/UserSession;"

/**
 * Lets a left swipe on a reel open its creator's profile, as older Instagram builds did. Included in
 * the default selection with its switch initially off, so Instagram's own swipe is what you get
 * until it is turned on.
 */
@Suppress("unused")
val swipeToProfilePatch = bytecodePatch(
    name = "Swipe left to open the profile",
    description = "Makes a left swipe on a reel open the profile of the person who posted it, like Instagram used " +
        "to. Tap Friends at the top to reach your friends' reels. Starts off. Turn it on in HushGram settings > Reels.",
    default = true,
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("swipeToProfile")
        hookSwipeGate(findSwipeGate())
        enableStatus("swipeToProfile")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")

/** Where the check reads its last gate: its class, name, the move-result of the gate's answer and the register it fills. */
internal class SwipeGate(
    val type: String,
    val name: String,
    val moveResult: Int,
    val register: Int,
)

/**
 * Finds the gate that decides, for an ordinary reel, whether a swipe may go on to the side panel.
 * The Reels viewer's check is the one instance method marked [SWIPE_ALLOWED], taking nothing and
 * answering a boolean. Its last gate asks one call taking just the user session and answering a
 * boolean, then branches on the answer with an if-nez to the "allowed" return. The check has to hold
 * exactly one such call, its move-result has to come straight after it and the branch straight after
 * that, on the same register, with nothing jumping between them.
 */
internal fun BytecodePatchContext.findSwipeGate(): SwipeGate {
    val holders = typesMarked(SWIPE_ALLOWED)
    val checks = holders.flatMap { type ->
        classDefBy(type).methods.filter { SWIPE_ALLOWED in it.markers() }
    }
    val check = checks.singleOrNull() ?: refuse("expected one check marked $SWIPE_ALLOWED, found ${checks.size}")
    val where = "${check.definingClass}->${check.name}"
    if (AccessFlags.STATIC.isSet(check.accessFlags) || check.returnType != "Z" || check.parameterTypes.isNotEmpty()) {
        refuse("$where isn't an instance method without parameters answering a boolean")
    }
    val code = check.implementation!!.instructions.toList()
    val asks = code.indices.filter { code[it].isSessionGate() }
    val ask = asks.singleOrNull() ?: refuse("expected $where to make one call taking only the user session and answering a boolean, found ${asks.size}")
    val result = code.getOrNull(ask + 1) as? OneRegisterInstruction
    val branch = code.getOrNull(ask + 2) as? OneRegisterInstruction
    if (result == null || branch == null || result.opcode != Opcode.MOVE_RESULT || branch.opcode != Opcode.IF_NEZ ||
        branch.registerA != result.registerA
    ) {
        refuse("$where doesn't branch on the answer of that call right after it at instruction $ask")
    }
    if (result.registerA > 255) refuse("$where keeps the answer past v255, out of reach of a move-result")
    if (ask + 2 in check.jumpTargets()) refuse("something in $where jumps between the gate's answer and the branch on it")
    return SwipeGate(check.definingClass, check.name, ask + 1, result.registerA)
}

/** Whether this is an invoke-virtual of a method taking only the user session and answering a boolean. */
private fun Instruction.isSessionGate(): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL) return false
    val call = (this as ReferenceInstruction).reference as? MethodReference ?: return false
    return call.returnType == "Z" && call.parameterTypes.map(CharSequence::toString) == listOf(SESSION)
}

/**
 * Hands the gate's answer to the extension right after it is read, and branches on what comes back.
 * The extension answers true while the switch is on, which has Instagram take the profile panel for
 * an ordinary reel, and Instagram's own answer otherwise. The call takes the register as a range
 * because it can sit above v15.
 */
internal fun BytecodePatchContext.hookSwipeGate(gate: SwipeGate) {
    val method: MutableMethod = mutableClassDefBy(gate.type).methods.single {
        it.name == gate.name && SWIPE_ALLOWED in it.markers()
    }
    val register = "v${gate.register}"
    method.addInstructions(
        gate.moveResult + 1,
        """
            invoke-static/range { $register .. $register }, $SWIPE_ALLOWED_HOOK
            move-result $register
        """,
    )
}
