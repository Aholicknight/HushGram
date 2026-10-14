/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.extension.typesMarked
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val KEEP = "Clean up Reels: Keep clear mode on"

internal const val CLEAR_MODE = "$EXTENSION_PACKAGE/reels/ReelClearMode;"
internal const val CLEAR_MODE_ENTERED = "$CLEAR_MODE->entered(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val CLEAR_MODE_LEFT = "$CLEAR_MODE->left(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val CLEAR_MODE_CURRENT = "$CLEAR_MODE->current(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val CLEAR_MODE_PAGED = "$CLEAR_MODE->paged()V"
internal const val CLEAR_MODE_BUILT = "$CLEAR_MODE->built(Ljava/lang/Object;)V"
internal const val CLEAR_MODE_ENTER_STUB = "enter"
internal const val CLEAR_MODE_ID_STUB = "reelId"

/**
 * The markers, after Instagram's release prefix, of the methods Keep clear mode on changes.
 * Instagram 450 calls clear mode its fullscreen view. The action handler's two methods turn it on
 * and off, each through an action the Reels viewer hands it, and the viewer's turn-on action is the
 * one that reads the reel the pager is on. The exit button a reel in clear mode draws is a
 * component of its own, and the pager's listener reports each page it lands on.
 */
internal const val CLEAR_MODE_ENABLE = "FullscreenViewActionHandler_handleFullscreenViewEnable"
internal const val CLEAR_MODE_DISMISS = "FullscreenViewActionHandler_handleFullscreenViewDismiss"
internal const val CLEAR_MODE_ENABLED = "ClipsViewerNavigatorProvider_handleFullscreenViewEnable"
internal const val CLEAR_MODE_BUTTON = "ClipsFullscreenViewComponent_render"
internal const val REEL_PAGE_SELECTED = "ClipsViewerViewPagerListener_onPageSelected"

internal val CLEAR_MODE_MARKERS = listOf(CLEAR_MODE_ENABLE, CLEAR_MODE_DISMISS, CLEAR_MODE_ENABLED, CLEAR_MODE_BUTTON, REEL_PAGE_SELECTED)

/** The reason Instagram turns clear mode off with when a reel leaves the screen, which its enum has to name. */
internal const val CLEAR_MODE_SWIPED_AWAY = "REELS_VIEWER_SWIPE_OR_EXIT"

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"

/**
 * Clear mode stays on from reel to reel. See the extension's ReelClearMode for the rule.
 *
 * The handler's turn-on and turn-off each tell the extension right after their action ran, with
 * what they were handed. A handler with no action set branches past its call, and the hooks go in
 * after the call, where that branch doesn't land, so they report only what happened. The viewer's
 * turn-on passes the reel it read off the pager through the extension before testing it, so a
 * turn-on the extension asked for can be held back by answering no reel, which Instagram's own test
 * takes as nothing to do. The pager's listener tells the extension first thing about each page, and
 * the exit button's render hands over its reel first thing. The extension's two stubs are filled
 * with the handler's turn-on and the reel's getId.
 *
 * Everything is found by Instagram's own markers and checked before anything changes, so a build
 * that differs stops the patch saying what it couldn't find, and nothing is half done.
 */
internal fun BytecodePatchContext.keepClearMode() {
    val found = findClearMode()
    val stubs = clearModeStubs()
    applyClearMode(found)
    stubs.fill(found)
}

/** Every place Keep clear mode on changes, by method and instruction index, and what the stubs reach. */
internal class ClearModeHooks(
    /** The handler's turn-on, and the index after its action's call. */
    val enable: Method,
    val entered: Int,
    /** The handler's turn-off, and the index after its action's call. */
    val dismiss: Method,
    val left: Int,
    /** The viewer's turn-on, the index of its test of the pager's reel, and the register the reel is in. */
    val enabled: Method,
    val current: Int,
    val reel: Int,
    /** The pager listener's page report. */
    val paged: Method,
    /** The exit button's render, and the field of its class holding the reel. */
    val button: Method,
    val buttonReel: String,
    /** The handler, the reason's enum and the reel, as types. */
    val handler: String,
    val reason: String,
    val item: String,
    /** How many instructions the turn-on, the turn-off and the viewer's turn-on had when their places were found. */
    val lengths: List<Int>,
)

internal fun BytecodePatchContext.findClearMode(): ClearModeHooks {
    val wanted = CLEAR_MODE_MARKERS.toSet()
    val marked = mutableMapOf<String, MutableList<Method>>()
    val holders = typesMarked(*CLEAR_MODE_MARKERS.toTypedArray())
    classDefForEach { classDef ->
        if (classDef.type !in holders) return@classDefForEach
        classDef.methods.forEach { method ->
            method.markers().filter { it in wanted }.distinct().forEach { marked.getOrPut(it) { mutableListOf() } += method }
        }
    }
    fun single(marker: String): Method {
        val found = marked[marker].orEmpty()
        return found.singleOrNull() ?: stop("expected one method marked $marker, found ${found.size}")
    }

    // The handler's turn-on: a public instance method taking the reason, which runs one action with it.
    val enable = single(CLEAR_MODE_ENABLE)
    val handler = enable.definingClass
    val handlerClass = classDefByOrNull(handler) ?: stop("the handler $handler isn't in the app")
    if (enable.isStatic() || !enable.isPublic() || enable.returnType != "V" || enable.parameterTypes.size != 1 ||
        !AccessFlags.PUBLIC.isSet(handlerClass.accessFlags)
    ) {
        stop("${enable.describe()}, marked $CLEAR_MODE_ENABLE, isn't a public instance method of a public class taking the reason")
    }
    val reason = enable.parameterTypes.single().toString()
    val reasonClass = classDefByOrNull(reason) ?: stop("the reason $reason isn't in the app")
    if (reasonClass.superclass != "Ljava/lang/Enum;" || !AccessFlags.PUBLIC.isSet(reasonClass.accessFlags)) {
        stop("the reason $reason isn't a public enum, so the extension can't tell a swipe from your own way out")
    }
    val named = reasonClass.methods.filter { it.name == "<clinit>" }.flatMap { initializer ->
        initializer.body().mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
    }
    if (CLEAR_MODE_SWIPED_AWAY !in named) stop("the reason $reason doesn't name $CLEAR_MODE_SWIPED_AWAY")
    val entered = enable.afterAction(listOf(0))

    // The handler's turn-off: the same class, taking the reel and the reason, which runs one action with both.
    val dismiss = single(CLEAR_MODE_DISMISS)
    if (dismiss.definingClass != handler) stop("$CLEAR_MODE_DISMISS isn't in $handler")
    if (dismiss.isStatic() || dismiss.returnType != "V" || dismiss.parameterTypes.size != 2 ||
        dismiss.parameterTypes[1].toString() != reason
    ) {
        stop("${dismiss.describe()}, marked $CLEAR_MODE_DISMISS, isn't an instance (reel, reason) method")
    }
    val item = dismiss.parameterTypes[0].toString()
    val itemClass = classDefByOrNull(item) ?: stop("the reel $item isn't in the app")
    if (!AccessFlags.PUBLIC.isSet(itemClass.accessFlags)) stop("the reel $item isn't public, so the extension can't reach it")
    val ids = itemClass.methods.filter {
        it.name == "getId" && it.parameterTypes.isEmpty() && it.returnType == STRING && it.isPublic() && !it.isStatic()
    }
    if (ids.size != 1) stop("the reel $item has ${ids.size} public getId() answering a string, expected one")
    val left = dismiss.afterAction(listOf(0, 1))

    // The viewer's turn-on: reads the reel the pager is on once, keeps it and tests it for null right away.
    // The read asks the pager for its reel, or, where Redex folded the pager lookup into it (451's
    // x86 385713117 has LX/03ST->A01), is a static call handed the one thing the pager comes from.
    val enabled = single(CLEAR_MODE_ENABLED)
    val code = enabled.body()
    val reads = code.indices.filter { at ->
        val call = code[at].methodReference() ?: return@filter false
        call.returnType == item &&
            if (code[at].isStaticCall()) call.parameterTypes.size == 1 else call.parameterTypes.isEmpty()
    }
    val read = reads.singleOrNull()
        ?: stop("${enabled.describe()} reads the reel the pager is on ${reads.size} times, expected once")
    val kept = code.getOrNull(read + 1) as? OneRegisterInstruction
    val test = code.getOrNull(read + 2) as? OneRegisterInstruction
    if (kept == null || test == null || kept.opcode != Opcode.MOVE_RESULT_OBJECT || test.opcode != Opcode.IF_EQZ ||
        test.registerA != kept.registerA
    ) {
        stop("${enabled.describe()} doesn't test the reel for null right after reading it off the pager")
    }
    val current = read + 2
    if (current in enabled.jumpTargets()) stop("${enabled.describe()} jumps to its test of the reel, so the hook would be skipped")
    enabled.requireNothingWithoutReel(current)

    // The pager listener's page report.
    val paged = single(REEL_PAGE_SELECTED)
    if (paged.isStatic() || paged.returnType != "V" || paged.parameterTypes.map(Any::toString) != listOf("I", "I") ||
        paged.implementation == null
    ) {
        stop("${paged.describe()}, marked $REEL_PAGE_SELECTED, isn't an instance (int, int) method returning nothing")
    }

    // The exit button's render: an instance method taking the scope, in a class holding the reel once.
    val button = single(CLEAR_MODE_BUTTON)
    if (button.isStatic() || button.parameterTypes.size != 1 || !button.returnType.startsWith("L") || button.implementation == null) {
        stop("${button.describe()}, marked $CLEAR_MODE_BUTTON, isn't a render taking one scope and answering a component")
    }
    val reels = classDefBy(button.definingClass).fields.filter { it.type == item && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val buttonReel = reels.singleOrNull()
        ?: stop("the exit button ${button.definingClass} holds the reel in ${reels.size} fields, expected one")
    if (button.localRegisterCount() < 1) stop("${button.describe()} has no local register for the hook")

    val every = listOf(enable, dismiss, enabled, paged, button).map { it.describe() }
    if (every.toSet().size != every.size) stop("two hooks would go in one method: $every")

    return ClearModeHooks(
        enable, entered, dismiss, left, enabled, current, kept.registerA, paged, button,
        "${buttonReel.definingClass}->${buttonReel.name}:${buttonReel.type}", handler, reason, item,
        listOf(enable, dismiss, enabled).map { it.body().size },
    )
}

/**
 * The index after the one action [this] handler method runs: an interface call named invoke that
 * takes objects and is handed each of the method's [parameters], whose registers and `this` still
 * hold what the method was called with there.
 */
private fun Method.afterAction(parameters: List<Int>): Int {
    val code = body()
    val actions = code.indices.filter { at ->
        val call = code[at].methodReference() ?: return@filter false
        (code[at].opcode == Opcode.INVOKE_INTERFACE || code[at].opcode == Opcode.INVOKE_INTERFACE_RANGE) &&
            call.name == "invoke" && call.returnType == OBJECT && call.parameterTypes.size == parameters.size &&
            call.parameterTypes.all { it.toString() == OBJECT }
    }
    val action = actions.singleOrNull() ?: stop("${describe()} runs ${actions.size} actions, expected one")
    val handed = code[action].registers().drop(1).toSet()
    if (handed != parameters.mapTo(HashSet()) { parameterRegisterNumber(it) }) {
        stop("${describe()} doesn't hand its action what it was called with")
    }
    val after = action + 1
    if (after >= code.size) stop("${describe()} ends at its action's call")
    // A hook between a call and the move that keeps its result leaves a class that won't verify.
    if (code[after].opcode == Opcode.MOVE_RESULT_OBJECT) {
        stop("${describe()} keeps what its action answers, so no hook can go right after the call")
    }
    requireThisIntact(KEEP, listOf(after))
    parameters.forEach { requireParameterIntact(KEEP, it, listOf(after)) }
    return after
}

/**
 * Throws unless the branch [this] takes at instruction [test] when there's no reel only returns:
 * nothing is called and nothing branches on the way. That is what lets the extension hold a
 * turn-on back by answering no reel.
 */
private fun Method.requireNothingWithoutReel(test: Int) {
    val code = body()
    var at = ControlFlow.of(this).normal[test].first()
    while (at < code.size && code[at].opcode !in RETURNS) {
        if (code[at].methodReference() != null || code[at] is OffsetInstruction || code[at].opcode in THROWS) {
            stop("${describe()} does more than return when the pager has no reel, at instruction $at")
        }
        at++
    }
    if (at >= code.size) stop("${describe()} doesn't return when the pager has no reel")
}

private val RETURNS = setOf(Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_OBJECT, Opcode.RETURN_WIDE)
private val THROWS = setOf(Opcode.THROW)

/**
 * Each hook reads registers the find phase checked and borrows none but the exit button's, which
 * goes in first where no local holds anything yet. The two handler hooks and the viewer's go in
 * with a plain insert on purpose: the handler's own branch around a missing action keeps landing
 * past the hook. Those three go in by the index the find phase read, which holds only while each
 * method is as long as it was, so one that changed in between stops the patch before any hook is in.
 */
internal fun BytecodePatchContext.applyClearMode(found: ClearModeHooks) {
    val enable = changing(found.enable)
    val dismiss = changing(found.dismiss)
    val enabled = changing(found.enabled)
    listOf(enable, dismiss, enabled).forEachIndexed { at, method ->
        if (method.body().size != found.lengths[at]) stop("${method.describe()} changed after its hook's place was found")
    }
    enable.addInstructions(found.entered, "invoke-static/range { p0 .. p1 }, $CLEAR_MODE_ENTERED")
    dismiss.addInstructions(found.left, "invoke-static/range { p0 .. p2 }, $CLEAR_MODE_LEFT")
    val reel = found.reel
    enabled.addInstructions(
        found.current,
        """
            invoke-static/range { v$reel .. v$reel }, $CLEAR_MODE_CURRENT
            move-result-object v$reel
            check-cast v$reel, ${found.item}
        """,
    )
    changing(found.paged).addInstructions(0, "invoke-static { }, $CLEAR_MODE_PAGED")
    changing(found.button).addInstructions(
        0,
        """
            move-object/from16 v0, p0
            iget-object v0, v0, ${found.buttonReel}
            invoke-static { v0 }, $CLEAR_MODE_BUILT
        """,
    )
}

/** The extension's stubs, found before anything changes, and the step that fills them. */
internal class ClearModeStubs(val enter: MutableMethod, val reelId: MutableMethod) {
    fun fill(found: ClearModeHooks) {
        enter.addInstructions(
            0,
            """
                check-cast p0, ${found.handler}
                check-cast p1, ${found.reason}
                invoke-virtual { p0, p1 }, ${found.handler}->${found.enable.name}(${found.reason})V
                return-void
            """,
        )
        reelId.addInstructions(
            0,
            """
                check-cast p0, ${found.item}
                invoke-virtual { p0 }, ${found.item}->getId()$STRING
                move-result-object p0
                return-object p0
            """,
        )
    }
}

internal fun BytecodePatchContext.clearModeStubs(): ClearModeStubs {
    val extension = mutableClassDefBy(CLEAR_MODE)
    fun stub(name: String, parameters: List<String>, returns: String): MutableMethod = extension.methods.singleOrNull {
        it.name == name && it.returnType == returns && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == parameters
    } ?: stop("$CLEAR_MODE has no static $returns $name(${parameters.joinToString("")})")
    return ClearModeStubs(
        enter = stub(CLEAR_MODE_ENTER_STUB, listOf(OBJECT, OBJECT), "V"),
        reelId = stub(CLEAR_MODE_ID_STUB, listOf(OBJECT), STRING),
    )
}

private fun stop(detail: String): Nothing = throw PatchException("$KEEP: $detail")

private fun BytecodePatchContext.changing(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }

private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.isStatic(): Boolean = AccessFlags.STATIC.isSet(accessFlags)

private fun Method.isPublic(): Boolean = AccessFlags.PUBLIC.isSet(accessFlags)

private fun Method.describe(): String = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.isStaticCall(): Boolean = opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE

/** The registers an invoke names, in order. */
private fun Instruction.registers(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}
