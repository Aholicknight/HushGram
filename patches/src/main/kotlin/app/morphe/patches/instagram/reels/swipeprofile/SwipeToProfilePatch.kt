/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.swipeprofile

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.extension.typesMarked
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

private const val PATCH = "Swipe left to open the profile"
internal const val SWIPE_TO_PROFILE = "$EXTENSION_PACKAGE/reels/SwipeToProfile;"
internal const val SWIPE_ALLOWED_HOOK = "$SWIPE_TO_PROFILE->allow(Z)Z"
internal const val TAB_CHANGING_HOOK = "$SWIPE_TO_PROFILE->tabChanging(Ljava/lang/Object;)V"
internal const val TAB_CHANGED_HOOK = "$SWIPE_TO_PROFILE->tabChanged(Ljava/lang/Object;)V"
internal const val SWIPE_TARGETS = "$EXTENSION_PACKAGE/reels/SwipeToProfileTargets;"

/** The marker, less its release, of the Reels viewer's check of whether a swipe may go from a reel to its side panel. */
internal const val SWIPE_ALLOWED = "ClipsViewerSwipeNavigationDelegate_isSwipeFromReelsToActionAllowed"

/** The view that slides a tab's side panels in under a sideways swipe. Instagram keeps its name. */
internal const val SWIPE_CONTAINER = "Lcom/instagram/ui/swipenavigation/container/SwipeNavigationContainer;"

/** What the container's panel switch throws when it's asked to turn the middle panel off. */
internal const val CENTER_PANEL = "No support for disabling center panel"

private const val SESSION = "Lcom/instagram/common/session/UserSession;"
private const val MOTION_EVENT = "Landroid/view/MotionEvent;"
private const val ANY = "Ljava/lang/Object;"

/**
 * Lets a left swipe on a reel open its creator's profile, as older Instagram builds did. Kept out of
 * the default selection until a device shows the swipe opening the profile: on the accounts tried so
 * far the swipe container never starts its own drag, even with the panel on (#245).
 */
@Suppress("unused")
val swipeToProfilePatch = bytecodePatch(
    name = "Swipe left to open the profile",
    description = "Makes a left swipe on a reel open the profile of the person who posted it, like Instagram used " +
        "to. Tap Friends at the top to reach your friends' reels. Starts off. Turn it on in HushGram settings > Reels.",
    default = false,
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("swipeToProfile")
        val gate = findSwipeGate()
        val panel = findProfilePanel()
        hookSwipeGate(gate)
        hookProfilePanel(panel)
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

/**
 * The Reels tab's profile panel and what reaches it. On 452: the container's onInterceptTouchEvent
 * hands the panel switch (A05(ZF)V) the answer of the panel gate (07xk.A08) at [gateResult]; the
 * panel sits in the container's [panel] field (A0F, a 00VQ), which keeps its on flag and its
 * position. The tab host's tab-change method (00M2.A0O) is the gate's one other caller; the host
 * answers its tab with [currentTab] (A09), compares it with [reelsTab] (08kM.A08), and reaches the
 * container through its [controller] field (A03, a 004N) and that one's [container] field (A0E).
 */
internal class ProfilePanel(
    val touch: Method,
    val gateResult: Int,
    val gateRegister: Int,
    val toggle: MethodReference,
    val panel: FieldReference,
    val panelOn: FieldReference,
    val position: FieldReference,
    val tabChange: Method,
    val currentTab: MethodReference,
    val reelsTab: FieldReference,
    val controller: FieldReference,
    val container: FieldReference,
)

/**
 * Finds the Reels tab's profile panel. Instagram turns it on only for accounts its server picks: the
 * tab host turns it on when you reach Reels if the panel gate says yes, and the container's touch
 * check turns it off again on each touch if the gate says no. Everything here is checked against
 * something else that reads it, and has to be public, since the extension's stubs read it.
 *
 * - The panel switch is the container's one (boolean, float) method saying [CENTER_PANEL].
 * - The gate is the one call in the container's onInterceptTouchEvent taking only the session and
 *   answering a boolean whose move-result the panel switch takes, straight after, as its boolean.
 * - The panel is the container field read last before the gate whose type's boolean field is read
 *   just after it; the switch reads the same field. The panel keeps its position in its one float
 *   field, which the switch reads too.
 * - The tab-change method is the one other method calling the gate. It is an instance method
 *   answering nothing whose first two parameters are a tab. Before the gate it asks the host's tab
 *   (a call of the host taking nothing and answering a tab) and loads the tab it's compared with,
 *   which the touch check loads as well. After the gate it fetches the container with a static call
 *   taking the controller, which the host keeps in one field and which keeps the container in one.
 */
internal fun BytecodePatchContext.findProfilePanel(): ProfilePanel {
    val container = classDefByOrNull(SWIPE_CONTAINER) ?: refuse("this Instagram build has no $SWIPE_CONTAINER")
    requirePublic(container)

    val toggles = container.methods.filter {
        it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf("Z", "F") && it.loads(CENTER_PANEL)
    }
    val toggleMethod = toggles.singleOrNull()
        ?: refuse("expected one (boolean, float) method of $SWIPE_CONTAINER saying \"$CENTER_PANEL\", found ${toggles.size}")
    requirePublicInstance(toggleMethod)
    val toggle = ImmutableMethodReference(toggleMethod.definingClass, toggleMethod.name, toggleMethod.parameterTypes, "V")

    val touch = container.methods.singleOrNull {
        it.name == "onInterceptTouchEvent" && it.returnType == "Z" &&
            it.parameterTypes.map(CharSequence::toString) == listOf(MOTION_EVENT) && it.implementation != null
    } ?: refuse("$SWIPE_CONTAINER has no onInterceptTouchEvent")
    val code = touch.implementation!!.instructions.toList()
    val gates = code.indices.filter { at ->
        val result = code.getOrNull(at + 1) as? OneRegisterInstruction
        val handed = code.getOrNull(at + 2)
        code[at].isSessionGate() && result?.opcode == Opcode.MOVE_RESULT && handed?.opcode == Opcode.INVOKE_VIRTUAL &&
            handed.called()?.matches(toggle) == true && (handed as FiveRegisterInstruction).registerD == result.registerA
    }
    val gate = gates.singleOrNull()
        ?: refuse("expected $SWIPE_CONTAINER's onInterceptTouchEvent to hand the panel switch one session gate's answer, found ${gates.size}")
    if (gate + 2 in touch.jumpTargets()) refuse("something in $SWIPE_CONTAINER's onInterceptTouchEvent jumps between the gate's answer and the panel switch")
    val gateCall = code[gate].called()!!

    val onRead = (0 until gate).lastOrNull { code[it].opcode == Opcode.IGET_BOOLEAN }
        ?: refuse("$SWIPE_CONTAINER's onInterceptTouchEvent reads no panel's on flag before its gate")
    val panelOn = code[onRead].field()!!
    val panelRead = (0 until onRead).lastOrNull { at ->
        code[at].opcode == Opcode.IGET_OBJECT && code[at].field()?.let { it.definingClass == SWIPE_CONTAINER && it.type == panelOn.definingClass } == true
    } ?: refuse("$SWIPE_CONTAINER's onInterceptTouchEvent reads no panel of type ${panelOn.definingClass} before its on flag")
    val panel = code[panelRead].field()!!
    val toggleCode = toggleMethod.implementation!!.instructions.toList()
    if (toggleCode.none { it.opcode == Opcode.IGET_OBJECT && it.field()?.matches(panel) == true }) {
        refuse("the panel switch ${toggleMethod.name} doesn't read the panel $panel the touch check reads")
    }
    val panelClass = classDefByOrNull(panelOn.definingClass) ?: refuse("the panel type ${panelOn.definingClass} isn't in the app")
    val positions = panelClass.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == "F" }
    val positionField = positions.singleOrNull()
        ?: refuse("expected the panel ${panelClass.type} to keep its position in one float field, found ${positions.size}")
    val position = ImmutableFieldReference(panelClass.type, positionField.name, "F")
    if (toggleCode.none { it.opcode == Opcode.IGET && it.field()?.matches(position) == true }) {
        refuse("the panel switch ${toggleMethod.name} doesn't read the panel's position $position")
    }
    requirePublic(panel)
    requirePublic(panelOn)
    requirePublic(position)

    val callers = classesCalling(gateCall.definingClass, gateCall.name).flatMap { holder ->
        holder.methods.filter { method -> method.implementation?.instructions?.any { it.called()?.matches(gateCall) == true } == true }
    }.filterNot { it.definingClass == SWIPE_CONTAINER && it.name == touch.name }
    val tabChange = callers.singleOrNull() ?: refuse("expected the panel gate $gateCall to have one caller besides the touch check, found ${callers.size}")
    val host = "${tabChange.definingClass}->${tabChange.name}"
    val parameters = tabChange.parameterTypes.map(CharSequence::toString)
    if (tabChange.returnType != "V" || parameters.size < 2 || parameters[0] != parameters[1] || !parameters[0].startsWith("L")) {
        refuse("$host, the gate's other caller, doesn't take two tabs first and answer nothing")
    }
    requirePublicInstance(tabChange)
    val tab = parameters[0]
    val body = tabChange.implementation!!.instructions.toList()
    val ask = body.indexOfFirst { it.called()?.matches(gateCall) == true }
    val currentTab = (0 until ask).reversed().firstNotNullOfOrNull { at ->
        body[at].takeIf { it.opcode == Opcode.INVOKE_VIRTUAL }?.called()
            ?.takeIf { it.definingClass == tabChange.definingClass && it.parameterTypes.isEmpty() && it.returnType == tab }
    } ?: refuse("$host doesn't ask the host's tab before the gate")
    val reelsTab = (0 until ask).reversed().firstNotNullOfOrNull { at ->
        body[at].takeIf { it.opcode == Opcode.SGET_OBJECT }?.field()?.takeIf { it.definingClass == tab && it.type == tab }
    } ?: refuse("$host loads no tab to compare with before the gate")
    if (code.none { it.opcode == Opcode.SGET_OBJECT && it.field()?.matches(reelsTab) == true }) {
        refuse("$SWIPE_CONTAINER's touch check doesn't compare with the tab $reelsTab that $host does")
    }
    val fetch = (ask + 1 until body.size).firstNotNullOfOrNull { at ->
        body[at].takeIf { it.opcode == Opcode.INVOKE_STATIC }?.called()
            ?.takeIf { it.returnType == SWIPE_CONTAINER && it.parameterTypes.size == 1 }
    } ?: refuse("$host doesn't fetch the container after the gate")
    val controllerType = fetch.parameterTypes.single().toString()
    val controllers = instanceFields(classDefBy(tabChange.definingClass)).filter { it.type == controllerType }
    val controller = controllers.singleOrNull()
        ?: refuse("expected ${tabChange.definingClass} to keep its $controllerType in one field, found ${controllers.size}")
    val controllerClass = classDefByOrNull(controllerType) ?: refuse("the container's holder $controllerType isn't in the app")
    val containers = instanceFields(controllerClass).filter { it.type == SWIPE_CONTAINER }
    val containerField = containers.singleOrNull()
        ?: refuse("expected $controllerType to keep the container in one field, found ${containers.size}")
    val controllerRef = ImmutableFieldReference(controller.definingClass, controller.name, controller.type)
    val containerRef = ImmutableFieldReference(containerField.definingClass, containerField.name, containerField.type)
    requirePublic(controllerRef)
    requirePublic(containerRef)
    requirePublic(reelsTab)
    requirePublicMethod(currentTab)

    val returns = body.indices.filter { body[it].opcode == Opcode.RETURN_VOID }
    if (returns.isEmpty()) refuse("$host never returns")
    if (0 in tabChange.jumpTargets()) refuse("something in $host jumps to its first instruction, where the hook goes")
    tabChange.requireThisIntact(PATCH, returns)

    return ProfilePanel(
        touch, gate + 1, (code[gate + 1] as OneRegisterInstruction).registerA, toggle, panel, panelOn, position,
        tabChange, currentTab, reelsTab, controllerRef, containerRef,
    )
}

/**
 * Hands the panel gate's answer in the touch check to [SWIPE_ALLOWED_HOOK], so a touch doesn't turn
 * the panel off while the switch is on. Calls [TAB_CHANGING_HOOK] first thing in the tab-change
 * method, to turn off what HushGram turned on before Instagram picks the next tab's panels, and
 * [TAB_CHANGED_HOOK] at each of its returns, to turn the panel on when the tab is Reels. A return
 * can be a jump target, so each is replaced by the call, which keeps the return's label, and a new
 * return follows it. Then writes the stubs the extension reaches Instagram's pieces through.
 */
internal fun BytecodePatchContext.hookProfilePanel(found: ProfilePanel) {
    val touch = mutable(found.touch)
    val register = "v${found.gateRegister}"
    touch.addInstructions(
        found.gateResult + 1,
        """
            invoke-static/range { $register .. $register }, $SWIPE_ALLOWED_HOOK
            move-result $register
        """,
    )

    val change = mutable(found.tabChange)
    val returns = change.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
    for (at in returns.reversed()) {
        change.replaceInstruction(at, "invoke-static/range { p0 .. p0 }, $TAB_CHANGED_HOOK")
        change.addInstruction(at + 1, "return-void")
    }
    change.addInstructions(0, "invoke-static/range { p0 .. p0 }, $TAB_CHANGING_HOOK")

    val host = found.tabChange.definingClass
    stub("controller", listOf(ANY), ANY, "check-cast p0, $host\niget-object p0, p0, ${found.controller}\nreturn-object p0")
    stub("container", listOf(ANY), ANY, "check-cast p0, ${found.controller.type}\niget-object p0, p0, ${found.container}\nreturn-object p0")
    stub("tab", listOf(ANY), ANY, "check-cast p0, $host\ninvoke-virtual { p0 }, ${found.currentTab}\nmove-result-object p0\nreturn-object p0")
    stub("reelsTab", emptyList(), ANY, "sget-object v0, ${found.reelsTab}\nreturn-object v0")
    stub("panel", listOf(ANY), ANY, "check-cast p0, $SWIPE_CONTAINER\niget-object p0, p0, ${found.panel}\nreturn-object p0")
    stub("panelOn", listOf(ANY), "Z", "check-cast p0, ${found.panelOn.definingClass}\niget-boolean p0, p0, ${found.panelOn}\nreturn p0")
    stub(
        "turn", listOf(ANY, ANY, "Z"), "V",
        """
            check-cast p0, $SWIPE_CONTAINER
            check-cast p1, ${found.position.definingClass}
            iget p1, p1, ${found.position}
            invoke-virtual { p0, p2, p1 }, ${found.toggle}
            return-void
        """,
    )
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }

/** Writes [smali] first in the static stub [name] of [SWIPE_TARGETS], which takes [parameters] and answers [returns]. */
private fun BytecodePatchContext.stub(name: String, parameters: List<String>, returns: String, smali: String) {
    val stub = mutableClassDefBy(SWIPE_TARGETS).methods.singleOrNull {
        it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == returns &&
            it.parameterTypes.map(CharSequence::toString) == parameters
    } ?: refuse("$SWIPE_TARGETS has no static $name(${parameters.joinToString("")})$returns")
    stub.addInstructions(0, smali)
}

private fun instanceFields(classDef: ClassDef) = classDef.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }

private fun Instruction.called(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun MethodReference.matches(other: MethodReference) =
    definingClass == other.definingClass && name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

private fun FieldReference.matches(other: FieldReference) =
    definingClass == other.definingClass && name == other.name && type == other.type

private fun Method.loads(string: String) = implementation?.instructions?.any {
    (it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO) &&
        ((it as ReferenceInstruction).reference as StringReference).string == string
} == true

private fun requirePublic(classDef: ClassDef) {
    if (!AccessFlags.PUBLIC.isSet(classDef.accessFlags)) refuse("${classDef.type} isn't public, so the extension can't reach it")
}

private fun requirePublicInstance(method: Method) {
    if (!AccessFlags.PUBLIC.isSet(method.accessFlags) || AccessFlags.STATIC.isSet(method.accessFlags)) {
        refuse("${method.definingClass}->${method.name} isn't a public instance method")
    }
}

/** Throws unless [field]'s class and the field itself are public, so a stub in the extension's package can read it. */
private fun BytecodePatchContext.requirePublic(field: FieldReference) {
    val owner = classDefByOrNull(field.definingClass) ?: refuse("${field.definingClass} isn't in the app")
    val declared = owner.fields.singleOrNull { it.name == field.name && it.type == field.type }
        ?: refuse("${field.definingClass} doesn't declare $field")
    requirePublic(owner)
    if (!AccessFlags.PUBLIC.isSet(declared.accessFlags)) refuse("$field isn't public, so the extension can't read it")
}

private fun BytecodePatchContext.requirePublicMethod(method: MethodReference) {
    val owner = classDefByOrNull(method.definingClass) ?: refuse("${method.definingClass} isn't in the app")
    val declared = owner.methods.singleOrNull { it.matches(method) } ?: refuse("${method.definingClass} doesn't declare $method")
    requirePublic(owner)
    requirePublicInstance(declared)
}
