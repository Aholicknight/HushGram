/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.peek

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Hide Plus preview in chat menus"
internal const val PEEK_MODE = "$EXTENSION_PACKAGE/direct/PlusPreview;->peek(I)Z"

/** The strings only the method building a chat's long press menu holds, and the event it logs when its peek overlay fails. */
internal const val MENU_CONTROLLER = "DirectInboxThreadDialogController"
internal const val OVERLAY_FAILED = "long_press_overlay_creation_failed"
internal const val LONG_PRESS = "long_press"

@Suppress("unused")
val hidePlusPreviewPatch = bytecodePatch(
    name = "Hide Plus preview in chat menus",
    description = "Takes the See preview and Instagram Plus rows out of the menu you get from long pressing a chat " +
        "in your messages. Your chat options stay. Nothing Plus offers is unlocked. Starts off. Turn it on in " +
        "HushGram settings > Messages.",
    default = true,
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("plusPreview")
        hookPeekMode(findPeekMode())
        enableStatus("plusPreview")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")

/** Where the chat menu's builder unboxes the flag that puts the menu in peek mode: its class, name, parameters, the move-result and the register it fills. */
internal class PeekSite(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val moveResult: Int,
    val register: Int,
)

/**
 * Finds the flag that puts a chat's long press menu in peek mode, the form with the preview overlay
 * and the Instagram Plus row. One method builds the menu: it holds [MENU_CONTROLLER], [LONG_PRESS] and
 * [OVERLAY_FAILED]. Near its end it appends one row to the menu's list (a call to a static method
 * taking an Iterable and a Collection and answering an ArrayList, then the popup's one-list method)
 * only when the peek flag is on, and a branch on that flag jumps over it to the code showing the
 * popup. The flag is a register filled by a Boolean's booleanValue(), and the hook goes right after
 * that move-result. Fails unless exactly one branch jumps over exactly one such append and the flag
 * is filled that way with nothing else writing it in between.
 */
internal fun BytecodePatchContext.findPeekMode(): PeekSite {
    val builders = classesLoadingString(OVERLAY_FAILED).flatMap { classDef ->
        classDef.methods.filter { method ->
            loadsString(method, OVERLAY_FAILED) && loadsString(method, MENU_CONTROLLER) && loadsString(method, LONG_PRESS)
        }.map { classDef.type to it }
    }
    val (type, method) = builders.singleOrNull() ?: refuse("expected one chat menu builder, found ${builders.size}")
    val where = "$type->${method.name}"
    val code = method.implementation!!.instructions.toList()
    val flow = ControlFlow.of(method)

    val appends = code.indices.filter { at -> code[at].isMenuHand() && code.getOrNull(at - 2)?.isRowAppend() == true }
    val sites = mutableListOf<PeekSite>()
    for (at in appends) {
        val over = at + 1
        code.indices.filter { code[it].opcode == Opcode.IF_EQZ && flow.normal[it].firstOrNull() == over }.forEach { branch ->
            val flag = (code[branch] as OneRegisterInstruction).registerA
            val take = flagTaken(code, branch, flag) ?: return@forEach
            sites += PeekSite(type, method.name, method.parameterTypes.map(CharSequence::toString), take, flag)
        }
    }
    val site = sites.singleOrNull() ?: refuse("expected one peek flag in $where, found ${sites.size}")
    if (site.register > 255) refuse("$where keeps the peek flag past v255, out of reach of a move-result")
    if (site.moveResult + 1 in method.jumpTargets()) refuse("a branch enters $where right after it takes the peek flag")
    return site
}

/** The move-result filling [flag] from a Boolean's booleanValue() that the branch at [branch] reads, or null when it isn't one. */
private fun flagTaken(code: List<Instruction>, branch: Int, flag: Int): Int? {
    var at = branch - 1
    while (at >= 0) {
        val instruction = code[at]
        val register = (instruction as? OneRegisterInstruction)?.registerA
        if (register == flag && instruction.opcode.writesRegister()) {
            val call = (code.getOrNull(at - 1) as? ReferenceInstruction)?.reference as? MethodReference
            return at.takeIf {
                instruction.opcode == Opcode.MOVE_RESULT && call != null && call.name == "booleanValue" &&
                    call.definingClass == "Ljava/lang/Boolean;" && call.returnType == "Z"
            }
        }
        at--
    }
    return null
}

/** Whether the opcode fills its first register; the reads that name a register first (branches, puts, returns) don't. */
private fun Opcode.writesRegister(): Boolean {
    val name = name
    return !(name.startsWith("if-") || name.startsWith("iput") || name.startsWith("sput") || name.startsWith("aput") ||
        name.startsWith("return") || name == "throw" || name.startsWith("monitor"))
}

/** The popup's call taking just the finished list of rows. */
private fun Instruction.isMenuHand(): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL) return false
    val call = (this as ReferenceInstruction).reference as? MethodReference ?: return false
    return call.returnType == "V" && call.parameterTypes.map(CharSequence::toString) == listOf("Ljava/util/List;")
}

/** The static call that adds the one row: an Iterable and a Collection in, an ArrayList out. */
private fun Instruction.isRowAppend(): Boolean {
    if (opcode != Opcode.INVOKE_STATIC) return false
    val call = (this as ReferenceInstruction).reference as? MethodReference ?: return false
    return call.returnType == "Ljava/util/ArrayList;" &&
        call.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Iterable;", "Ljava/util/Collection;")
}

/**
 * Lets the extension turn the peek flag off while Hide Plus preview is on, so the menu takes the
 * plain form every account without the experiment gets: the options alone, in order, with no
 * overlay and no extra row. The flag is read once per menu, so the switch needs no restart. The
 * call takes the register as a range because the flag sits above v15.
 */
internal fun BytecodePatchContext.hookPeekMode(site: PeekSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    val register = "v${site.register}"
    method.addInstructions(
        site.moveResult + 1,
        """
            invoke-static/range { $register .. $register }, $PEEK_MODE
            move-result $register
        """,
    )
}
