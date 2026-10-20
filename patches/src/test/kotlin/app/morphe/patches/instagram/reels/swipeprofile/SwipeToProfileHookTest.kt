/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.swipeprofile

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Swipe left to open the profile (#245): the answer of the Reels viewer's last swipe gate goes
 * through the extension right after Instagram reads it, and a build where the check or the gate
 * can't be told apart fails the patch before an instruction changes.
 */
class SwipeToProfileHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(SWIPE_ALLOWED_HOOK.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SWIPE_ALLOWED_HOOK is not in the extension: $declared", SWIPE_ALLOWED_HOOK.substringAfter("->") in declared)
    }

    @Test
    fun theGateGoesThroughTheExtensionRightAfterItIsRead() {
        val context = PatchContexts.of(listOf(check()))

        val gate = context.findSwipeGate()
        context.hookSwipeGate(gate)

        assertEquals(2, gate.moveResult)
        assertEquals(0, gate.register)
        assertHooked("stand-in", context.mutableClassDefBy(CHECK).methods.single(), gate)
    }

    /** The register is whichever the move-result fills, and a high one is reached by the range form. */
    @Test
    fun aGateAnswerAboveV15IsHandedOverInItsOwnRegister() {
        val context = PatchContexts.of(listOf(check(register = 20)))

        val gate = context.findSwipeGate()
        context.hookSwipeGate(gate)

        assertEquals(20, gate.register)
        assertHooked("stand-in", context.mutableClassDefBy(CHECK).methods.single(), gate)
    }

    @Test
    fun theWholePatchHooksTheGateAndThePanelAndTurnsTheStatusOn() {
        val context = PatchContexts.of(listOf(check(), ExtensionDex.classDef(SETTINGS_STATUS), ExtensionDex.classDef(SWIPE_TARGETS)) + panelClasses())

        swipeToProfilePatch.execute(context)

        val method = context.mutableClassDefBy(CHECK).methods.single()
        assertEquals(1, method.implementation!!.instructions.count { (it as? ReferenceInstruction)?.reference?.toString() == SWIPE_ALLOWED_HOOK })
        val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "swipeToProfile" }
        assertEquals(Opcode.CONST_4, status.implementation!!.instructions.first().opcode)
    }

    @Test
    fun theTabHooksAndTheStubsAreInTheExtension() {
        val declared = ExtensionDex.classDef(SWIPE_TO_PROFILE).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(TAB_CHANGING_HOOK, TAB_CHANGED_HOOK, CHILD_SCROLLS_HOOK, PAGER_INPUT_HOOK)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
        val stubs = ExtensionDex.classDef(SWIPE_TARGETS).methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) }.map { it.name }.toSet()
        assertEquals(setOf("controller", "container", "tab", "reelsTab", "panel", "panelOn", "turn", "childScrolls", "pager", "inputOn", "input"), stubs)
    }

    /** The walk and the tabs' pager input call go through the extension on the registers they read, and the stubs reach the walk and the pager. */
    @Test
    fun theWalkAndThePagerInputGoThroughTheExtension() {
        val context = PatchContexts.of(panelClasses() + ExtensionDex.classDef(SWIPE_TARGETS))

        val panel = context.findProfilePanel()
        val pagers = context.findPagerHandling(panel.touch)
        context.hookPagerHandling(panel.touch, pagers)
        context.hookProfilePanel(panel)

        assertEquals(WALK_REF, pagers.walk.toString())
        assertEquals("$VIEW_PAGER->A09:Z", pagers.pagerInput.toString())
        assertPagersHooked("stand-in", context, panel, pagers)
        assertPanelHooked("stand-in", context, panel, returns = 2)
    }

    @Test
    fun aContainerWithoutThePagerFlagSetterFailsThePatch() {
        refuses("has no $PAGER_CHECK_SETTER") { PatchContexts.of(panelClasses(pagerSetter = false)).let { it.findPagerHandling(it.findProfilePanel().touch) } }
    }

    @Test
    fun aWalkNotHandedTheFlagFailsThePatch() {
        refuses("doesn't hand the walk") { PatchContexts.of(panelClasses(walkHanded = false)).let { it.findPagerHandling(it.findProfilePanel().touch) } }
    }

    @Test
    fun aWideWriteOverTheHandedFlagFailsThePatch() {
        refuses("doesn't hand the walk") {
            PatchContexts.of(panelClasses(afterMove = "const-wide/16 v6, 0x0")).let { it.findPagerHandling(it.findProfilePanel().touch) }
        }
    }

    @Test
    fun twoPagerInputCallsFailThePatch() {
        refuses("found 2") { PatchContexts.of(panelClasses(inputCalls = 2)).let { it.findPagerHandling(it.findProfilePanel().touch) } }
    }

    /** The touch check's gate answer goes through the extension, each tab change is bracketed by the two calls, and the stubs read the panel. */
    @Test
    fun thePanelIsTurnedOnForReelsAndKeptOnUnderATouch() {
        val context = PatchContexts.of(panelClasses() + ExtensionDex.classDef(SWIPE_TARGETS))

        val panel = context.findProfilePanel()
        context.hookProfilePanel(panel)

        assertEquals("$SWIPE_CONTAINER->A0F:$PANEL_TYPE", panel.panel.toString())
        assertEquals("$PANEL_TYPE->A00:Z", panel.panelOn.toString())
        assertEquals("$PANEL_TYPE->A01:F", panel.position.toString())
        assertEquals("$TAB->A08:$TAB", panel.reelsTab.toString())
        assertEquals("$HOST->A03:$CONTROLLER", panel.controller.toString())
        assertEquals("$CONTROLLER->A0E:$SWIPE_CONTAINER", panel.container.toString())
        assertPanelHooked("stand-in", context, panel, returns = 2)
        val turn = context.mutableClassDefBy(SWIPE_TARGETS).methods.single { it.name == "turn" }.implementation!!.instructions.toList()
        assertEquals(panel.toggle.toString(), (turn[3] as ReferenceInstruction).reference.toString())
        val reels = context.mutableClassDefBy(SWIPE_TARGETS).methods.single { it.name == "reelsTab" }.implementation!!.instructions.first()
        assertEquals(panel.reelsTab.toString(), (reels as ReferenceInstruction).reference.toString())
    }

    @Test
    fun aBuildWithoutTheContainerFailsThePatch() {
        refuses("has no $SWIPE_CONTAINER") { PatchContexts.of(panelClasses().filterNot { it.type == SWIPE_CONTAINER }).findProfilePanel() }
    }

    @Test
    fun aTouchCheckHandingTheSwitchTwoGatesFailsThePatch() {
        refuses("found 2") { PatchContexts.of(panelClasses(gates = 2)).findProfilePanel() }
    }

    @Test
    fun aSecondCallerOfTheGateFailsThePatch() {
        refuses("found 2") { PatchContexts.of(panelClasses(secondCaller = true)).findProfilePanel() }
    }

    @Test
    fun aPanelFlagTheExtensionCantReadFailsThePatch() {
        refuses("isn't public") { PatchContexts.of(panelClasses(publicFlag = false)).findProfilePanel() }
    }

    @Test
    fun aBuildWithoutTheCheckFailsThePatch() {
        refuses("found 0") { PatchContexts.of(listOf(check(marker = false))).findSwipeGate() }
    }

    @Test
    fun twoChecksFailThePatch() {
        refuses("found 2") { PatchContexts.of(listOf(check(), check("Lfixture/OtherCheck;"))).findSwipeGate() }
    }

    @Test
    fun aCheckThatIsNotAnInstanceMethodWithoutParametersFailsThePatch() {
        refuses("isn't an instance method") { PatchContexts.of(listOf(check(static = true))).findSwipeGate() }
    }

    @Test
    fun aCheckWithoutTheSessionGateFailsThePatch() {
        refuses("found 0") { PatchContexts.of(listOf(check(gates = 0))).findSwipeGate() }
    }

    @Test
    fun aCheckWithTwoSessionGatesFailsThePatch() {
        refuses("found 2") { PatchContexts.of(listOf(check(gates = 2))).findSwipeGate() }
    }

    @Test
    fun aGateNotBranchedOnWithIfNezFailsThePatch() {
        refuses("doesn't branch on the answer") { PatchContexts.of(listOf(check(branch = Opcode.IF_EQZ))).findSwipeGate() }
    }

    @Test
    fun aBranchLandingOnTheGateBranchFailsThePatch() {
        refuses("jumps between") { PatchContexts.of(listOf(check(jumpIn = true))).findSwipeGate() }
    }

    /** In each declared build: the one gate, read in the one check, with the extension's answer taking its place. */
    @Test
    fun eachDeclaredBuildHooksTheGate() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The same hook on every other build of the version, each compiled on its own. */
    @Test
    fun eachOtherBuildHooksTheGateToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.stringSection.none { it.endsWith("_$SWIPE_ALLOWED") }) return@forEach
            for (classDef in dex.classes) {
                if (classDef.methods.any { SWIPE_ALLOWED in it.markers() }) found += ImmutableClassDef.of(classDef)
            }
        }
        val context = PatchContexts.of(
            FixtureDex.withStringPools(bundle, found + fixturePanelClasses(bundle)) +
                ExtensionDex.classDef(SETTINGS_STATUS) + ExtensionDex.classDef(SWIPE_TARGETS),
        )

        val gate = context.findSwipeGate()
        val panel = context.findProfilePanel()
        val pagers = context.findPagerHandling(panel.touch)
        val returns = panel.tabChange.implementation!!.instructions.count { it.opcode == Opcode.RETURN_VOID }
        swipeToProfilePatch.execute(context)

        val method = context.mutableClassDefBy(gate.type).methods.single { it.name == gate.name && SWIPE_ALLOWED in it.markers() }
        assertHooked("$label ${gate.type}->${gate.name}", method, gate)
        assertPanelHooked("$label ${panel.tabChange.definingClass}->${panel.tabChange.name}", context, panel, returns)
        assertPagersHooked("$label $SWIPE_CONTAINER->onInterceptTouchEvent", context, panel, pagers)
    }

    /**
     * The container, the classes of the methods calling a session gate its touch check calls, the
     * classes those and the container keep in fields or take as parameters, the walk's class and
     * the pager, as [bundle] has them.
     */
    private fun fixturePanelClasses(bundle: File): List<ClassDef> {
        val container = FixtureDex.classes(bundle, setOf(SWIPE_CONTAINER)).values.single()
        val touch = container.methods.single { it.name == "onInterceptTouchEvent" && it.implementation != null }
        val walks = touch.implementation!!.instructions.mapNotNull { it.called() }
            .filter { it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == WALK_SHAPE }
            .map { it.definingClass }
        val gates = touch.implementation!!.instructions.mapNotNull { it.called() }
            .filter { it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(SESSION) }
        val owners = gates.map { it.definingClass }.toSet()
        val asked = gates.map { it.toString() }.toSet()
        val callers = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it in owners } }) { method ->
            method.implementation?.instructions?.any { it.called()?.toString() in asked } == true
        }
        val hosts = FixtureDex.classes(bundle, callers.map { it.definingClass }.toSet() - SWIPE_CONTAINER).values
        val kept = (hosts + container).flatMap { holder -> holder.fields.map { it.type } } +
            callers.flatMap { method -> method.parameterTypes.map(CharSequence::toString) }
        val have = hosts.map { it.type }.toSet() + SWIPE_CONTAINER
        val wanted = kept.filter { it.startsWith("L") }.toSet() + walks + VIEW_PAGER
        return listOf(container) + hosts + FixtureDex.classes(bundle, wanted - have).values
    }

    /**
     * The walk and the tabs' pager input call replaced, in their places, by [CHILD_SCROLLS_HOOK] and
     * [PAGER_INPUT_HOOK] on the registers they read, and the stubs reaching the walk and the pager.
     */
    private fun assertPagersHooked(what: String, context: BytecodePatchContext, panel: ProfilePanel, pagers: PagerHandling) {
        val before = panel.touch.implementation!!.instructions.toList()
        val code = context.mutated(panel.touch).implementation!!.instructions.toList()
        val setInput = "$VIEW_PAGER->setUserInputEnabled(Z)V"
        assertEquals("$what: walks left", 0, code.count { it.names(pagers.walk.toString()) })
        assertEquals("$what: input calls left", 0, code.count { it.names(setInput) })

        val walks = code.indices.filter { code[it].names(CHILD_SCROLLS_HOOK) }
        assertEquals("$what: the walk in its place", listOf(pagers.walkCall), walks)
        val walk = code[pagers.walkCall] as RegisterRangeInstruction
        assertEquals("$what: the walk's registers", (before[pagers.walkCall] as RegisterRangeInstruction).startRegister, walk.startRegister)
        assertEquals("$what: six of them", 6, walk.registerCount)

        val inputs = code.indices.filter { code[it].names(PAGER_INPUT_HOOK) }
        assertEquals("$what: one input hook", 1, inputs.size)
        val input = code[inputs.single()] as FiveRegisterInstruction
        val asked = before[pagers.inputCall] as FiveRegisterInstruction
        assertEquals("$what: a static call", Opcode.INVOKE_STATIC, input.opcode)
        assertEquals("$what: two registers", 2, input.registerCount)
        assertEquals("$what: the pager", asked.registerC, input.registerC)
        assertEquals("$what: the input", asked.registerD, input.registerD)

        val stubs = context.mutableClassDefBy(SWIPE_TARGETS).methods
        fun at(name: String, index: Int) = stubs.single { it.name == name }.implementation!!.instructions.toList()[index] as ReferenceInstruction
        assertEquals("$what: the stub walks as Instagram does", pagers.walk.toString(), at("childScrolls", 0).reference.toString())
        assertEquals("$what: the stub asks for a pager", VIEW_PAGER, at("pager", 0).reference.toString())
        assertEquals("$what: the stub reads the pager's input", pagers.pagerInput.toString(), at("inputOn", 1).reference.toString())
        assertEquals("$what: the stub turns the pager's input", setInput, at("input", 1).reference.toString())
    }

    /**
     * The touch check's gate answer handed to [SWIPE_ALLOWED_HOOK] and on to the panel switch; the
     * tab change opening with [TAB_CHANGING_HOOK] on this and each of its [returns] right after
     * [TAB_CHANGED_HOOK] on this, with nothing jumping past the call.
     */
    private fun assertPanelHooked(what: String, context: BytecodePatchContext, panel: ProfilePanel, returns: Int) {
        val code = context.mutated(panel.touch).implementation!!.instructions.toList()
        assertEquals("$what: touch hooks", 1, code.count { it.names(SWIPE_ALLOWED_HOOK) })
        assertEquals("$what: the gate's answer", Opcode.MOVE_RESULT, code[panel.gateResult].opcode)
        val call = code[panel.gateResult + 1]
        assertEquals("$what: the touch call", Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertTrue("$what: the touch hook", call.names(SWIPE_ALLOWED_HOOK))
        assertEquals("$what: the touch call's register", panel.gateRegister, (call as RegisterRangeInstruction).startRegister)
        assertEquals("$what: one register", 1, call.registerCount)
        val back = code[panel.gateResult + 2]
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, back.opcode)
        assertEquals("$what: into the same register", panel.gateRegister, (back as OneRegisterInstruction).registerA)
        val handed = code[panel.gateResult + 3]
        assertTrue("$what: the panel switch", handed.names(panel.toggle.toString()))
        assertEquals("$what: handed the same register", panel.gateRegister, (handed as FiveRegisterInstruction).registerD)

        val change = context.mutated(panel.tabChange)
        val body = change.implementation!!.instructions.toList()
        val self = panel.tabChange.localRegisterCount()
        assertEquals("$what: tab hooks first", 1, body.count { it.names(TAB_CHANGING_HOOK) })
        assertTrue("$what: the first instruction", body[0].names(TAB_CHANGING_HOOK))
        assertEquals("$what: on this", self, (body[0] as RegisterRangeInstruction).startRegister)
        val ends = body.indices.filter { body[it].opcode == Opcode.RETURN_VOID }
        assertEquals("$what: returns", returns, ends.size)
        assertEquals("$what: tab hooks last", returns, body.count { it.names(TAB_CHANGED_HOOK) })
        val targets = change.jumpTargets()
        for (end in ends) {
            assertTrue("$what: the call before the return at $end", body[end - 1].names(TAB_CHANGED_HOOK))
            assertEquals("$what: on this before the return at $end", self, (body[end - 1] as RegisterRangeInstruction).startRegister)
            assertFalse("$what: something jumps past the call to the return at $end", end in targets)
        }
    }

    private fun BytecodePatchContext.mutated(method: Method) = mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }

    private fun Instruction.called() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.names(reference: String) = (this as? ReferenceInstruction)?.reference?.toString() == reference

    /** The gate read, the hook taking its answer as a range of one, the answer back in the same register and the branch still on it; one hook in all. */
    private fun assertHooked(what: String, method: Method, gate: SwipeGate) {
        val code = method.implementation!!.instructions.toList()
        assertEquals("$what: hooks", 1, code.count { (it as? ReferenceInstruction)?.reference?.toString() == SWIPE_ALLOWED_HOOK })
        assertEquals("$what: the gate's answer", Opcode.MOVE_RESULT, code[gate.moveResult].opcode)
        assertEquals("$what: the answer's register", gate.register, (code[gate.moveResult] as OneRegisterInstruction).registerA)
        val read = (code[gate.moveResult - 1] as ReferenceInstruction).reference as MethodReference
        assertEquals("$what: read from the session gate", listOf(SESSION), read.parameterTypes.map(CharSequence::toString))
        assertEquals("$what: returning a boolean", "Z", read.returnType)
        val call = code[gate.moveResult + 1]
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals("$what: the hook", SWIPE_ALLOWED_HOOK, (call as ReferenceInstruction).reference.toString())
        assertEquals("$what: the call's register", gate.register, (call as RegisterRangeInstruction).startRegister)
        assertEquals("$what: one register", 1, call.registerCount)
        val back = code[gate.moveResult + 2]
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, back.opcode)
        assertEquals("$what: into the same register", gate.register, (back as OneRegisterInstruction).registerA)
        val branch = code[gate.moveResult + 3]
        assertEquals("$what: the branch", Opcode.IF_NEZ, branch.opcode)
        assertEquals("$what: still on the same register", gate.register, (branch as OneRegisterInstruction).registerA)
    }

    /** The patch refuses, for the reason given. */
    private fun refuses(reason: String, search: () -> Unit) {
        val refusal = assertThrows(PatchException::class.java) { search() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
    }

    private companion object {
        const val CHECK = "Lfixture/SwipeCheck;"
        const val SESSION = "Lcom/instagram/common/session/UserSession;"
        const val PANEL_TYPE = "Lfixture/Panel;"
        const val TAB = "Lfixture/Tab;"
        const val HOST = "Lfixture/Host;"
        const val CONTROLLER = "Lfixture/Controller;"
        const val PANEL_GATE = "Lfixture/PanelGate;->A08($SESSION)Z"
        const val TOGGLE = "$SWIPE_CONTAINER->A05(ZF)V"
        const val WALK_TYPE = "Lfixture/Walk;"
        const val WALK_REF = "$WALK_TYPE->A00(Landroid/view/View;IIIZZ)Z"
        val WALK_SHAPE = listOf("Landroid/view/View;", "I", "I", "I", "Z", "Z")

        /** A method whose body is [smali], assembled the way the patches assemble what they add. */
        fun assembled(owner: String, name: String, parameters: List<String>, returns: String, flags: Int, registers: Int, smali: String): ImmutableMethod =
            ImmutableMethod.of(
                ImmutableMethod(
                    owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                    ImmutableMethodImplementation(registers, emptyList(), null, null),
                ).toMutable().apply { addInstructions(0, smali) },
            )

        fun field(owner: String, name: String, type: String, flags: Int = AccessFlags.PUBLIC.value) =
            ImmutableField(owner, name, type, flags, null, null, null)

        /**
         * Shaped like 452's: the container's panel switch saying [CENTER_PANEL] and reading the
         * panel's position, its touch check handing the switch the panel gate's answer for the
         * panel at -1.0 while it's on, and the tab host's tab change asking the same gate when it
         * reaches the Reels tab and fetching the container after, its two returns both jumped to.
         * [gates] repeats the touch check's gate, [secondCaller] gives the host another method
         * asking the gate and [publicFlag] false hides the panel's on flag. Before the panel, the
         * touch check reads the container's pager flag and hands it last, through a move, to its
         * walk of the views under the finger, then turns the tabs' pager input on [inputCalls]
         * times after the panel switch; [pagerSetter] false drops the flag's setter and
         * [walkHanded] false hands the walk a constant instead. [afterMove] goes right after the
         * move.
         */
        fun panelClasses(
            gates: Int = 1,
            secondCaller: Boolean = false,
            publicFlag: Boolean = true,
            pagerSetter: Boolean = true,
            walkHanded: Boolean = true,
            inputCalls: Int = 1,
            afterMove: String = "",
        ): List<ClassDef> {
            val public = AccessFlags.PUBLIC.value
            val publicFinal = public or AccessFlags.FINAL.value
            val handOver = """
                const/4 v4, 0x0
                const/4 v5, 0x0
                invoke-virtual { v4, v5 }, $PANEL_GATE
                move-result v2
                invoke-virtual { p0, v2, v3 }, $TOGGLE
            """
            val inputOn = """
                const/4 v2, 0x0
                const/4 v1, 0x1
                invoke-virtual { v2, v1 }, $VIEW_PAGER->setUserInputEnabled(Z)V
            """
            val container = ImmutableClassDef(
                SWIPE_CONTAINER, public, "Landroid/view/ViewGroup;", null, null, null,
                listOf(field(SWIPE_CONTAINER, "A0F", PANEL_TYPE), field(SWIPE_CONTAINER, "A0R", "Z")),
                listOfNotNull(
                    assembled(
                        SWIPE_CONTAINER, "A05", listOf("Z", "F"), "V", public, 5,
                        """
                            const-string v1, "$CENTER_PANEL"
                            iget-object v0, p0, $SWIPE_CONTAINER->A0F:$PANEL_TYPE
                            iget v1, v0, $PANEL_TYPE->A01:F
                            iput-boolean p1, v0, $PANEL_TYPE->A00:Z
                            return-void
                        """,
                    ),
                    if (pagerSetter) {
                        assembled(
                            SWIPE_CONTAINER, PAGER_CHECK_SETTER, listOf("Z"), "V", public, 2,
                            """
                                iput-boolean p1, p0, $SWIPE_CONTAINER->A0R:Z
                                return-void
                            """,
                        )
                    } else {
                        null
                    },
                    assembled(
                        SWIPE_CONTAINER, "onInterceptTouchEvent", listOf("Landroid/view/MotionEvent;"), "Z", public, 12,
                        """
                            iget-boolean v0, p0, $SWIPE_CONTAINER->A0R:Z
                            const/4 v3, 0x1
                            const/4 v4, 0x0
                            const/4 v5, 0x0
                            const/4 v6, 0x0
                            ${if (walkHanded) "move v7, v0" else "const/4 v7, 0x0"}
                            $afterMove
                            move-object v2, p0
                            invoke-static/range { v2 .. v7 }, $WALK_REF
                            move-result v0
                            if-nez v0, :done
                            sget-object v0, $TAB->A08:$TAB
                            iget-object v1, p0, $SWIPE_CONTAINER->A0F:$PANEL_TYPE
                            if-eqz v1, :done
                            iget-boolean v1, v1, $PANEL_TYPE->A00:Z
                            if-eqz v1, :done
                            const/high16 v3, -0x40800000
                        """ + handOver.repeat(gates) + inputOn.repeat(inputCalls) + """
                            :done
                            const/4 v0, 0x0
                            return v0
                        """,
                    ),
                ),
            )
            val walk = ImmutableClassDef(
                WALK_TYPE, public, "Ljava/lang/Object;", null, null, null, emptyList(),
                listOf(
                    assembled(
                        WALK_TYPE, "A00", WALK_SHAPE, "Z", public or AccessFlags.STATIC.value, 8,
                        """
                            instance-of v0, p0, $VIEW_PAGER
                            if-eqz v0, :plain
                            move-object v1, p0
                            check-cast v1, $VIEW_PAGER
                            iget-boolean v0, v1, $VIEW_PAGER->A09:Z
                            :plain
                            invoke-static/range { p0 .. p5 }, $WALK_REF
                            move-result v0
                            return v0
                        """,
                    ),
                ),
            )
            val pager = ImmutableClassDef(
                VIEW_PAGER, public, "Landroid/view/ViewGroup;", null, null, null,
                listOf(field(VIEW_PAGER, "A09", "Z")),
                listOf(
                    assembled(
                        VIEW_PAGER, "setUserInputEnabled", listOf("Z"), "V", public, 2,
                        """
                            iput-boolean p1, p0, $VIEW_PAGER->A09:Z
                            return-void
                        """,
                    ),
                ),
            )
            val panel = ImmutableClassDef(
                PANEL_TYPE, publicFinal, "Ljava/lang/Object;", null, null, null,
                listOf(
                    field(PANEL_TYPE, "A00", "Z", if (publicFlag) public else 0),
                    field(PANEL_TYPE, "A01", "F", publicFinal),
                    field(PANEL_TYPE, "A02", "Landroid/view/View;"),
                ),
                emptyList(),
            )
            val tab = ImmutableClassDef(
                TAB, publicFinal, "Ljava/lang/Enum;", null, null, null,
                listOf(field(TAB, "A08", TAB, publicFinal or AccessFlags.STATIC.value)),
                emptyList(),
            )
            val controller = ImmutableClassDef(
                CONTROLLER, publicFinal, "Ljava/lang/Object;", null, null, null,
                listOf(field(CONTROLLER, "A0E", SWIPE_CONTAINER)),
                listOf(
                    assembled(
                        CONTROLLER, "A05", listOf(CONTROLLER), SWIPE_CONTAINER, public or AccessFlags.STATIC.value, 1,
                        """
                            iget-object v0, p0, $CONTROLLER->A0E:$SWIPE_CONTAINER
                            return-object v0
                        """,
                    ),
                ),
            )
            val host = ImmutableClassDef(
                HOST, publicFinal, "Ljava/lang/Object;", null, null, null,
                listOf(field(HOST, "A03", CONTROLLER)),
                listOfNotNull(
                    assembled(
                        HOST, "A09", emptyList(), TAB, publicFinal, 2,
                        """
                            sget-object v0, $TAB->A08:$TAB
                            return-object v0
                        """,
                    ),
                    assembled(
                        HOST, "A0O", listOf(TAB, TAB, "Ljava/lang/Integer;"), "V", publicFinal, 8,
                        """
                            if-eq p1, p2, :same
                            sget-object v0, $TAB->A08:$TAB
                            invoke-virtual { p0 }, $HOST->A09()$TAB
                            move-result-object v1
                            if-ne v1, v0, :done
                            const/4 v2, 0x0
                            const/4 v3, 0x0
                            invoke-virtual { v2, v3 }, $PANEL_GATE
                            move-result v2
                            iget-object v3, p0, $HOST->A03:$CONTROLLER
                            invoke-static { v3 }, $CONTROLLER->A05($CONTROLLER)$SWIPE_CONTAINER
                            move-result-object v3
                            :done
                            return-void
                            :same
                            return-void
                        """,
                    ),
                    if (secondCaller) {
                        assembled(
                            HOST, "A0P", emptyList(), "V", publicFinal, 3,
                            """
                                const/4 v0, 0x0
                                const/4 v1, 0x0
                                invoke-virtual { v0, v1 }, $PANEL_GATE
                                move-result v0
                                return-void
                            """,
                        )
                    } else {
                        null
                    },
                ),
            )
            return listOf(container, panel, tab, controller, host, walk, pager)
        }

        /**
         * Shaped like 452's check: its marker loaded first, then [gates] calls taking the session and
         * answering a boolean, each followed by a move-result into v[register], an if-nez on the last
         * answer over a `return false` to a `return true`. [jumpIn] adds a branch that lands on the if-nez.
         */
        fun check(
            type: String = CHECK,
            marker: Boolean = true,
            gates: Int = 1,
            register: Int = 0,
            branch: Opcode = Opcode.IF_NEZ,
            static: Boolean = false,
            jumpIn: Boolean = false,
        ): ClassDef {
            val code = MutableMethodImplementation(24)
            if (marker) {
                code.addInstruction(
                    BuilderInstruction21c(Opcode.CONST_STRING, 3, ImmutableStringReference("android_purge_26_q3_$SWIPE_ALLOWED")),
                )
            }
            val jumpAt = code.instructions.size
            if (jumpIn) code.addInstruction(BuilderInstruction10x(Opcode.NOP))
            val gate = ImmutableMethodReference("Lfixture/Gate;", "A0U", listOf(SESSION), "Z")
            repeat(gates) {
                code.addInstruction(BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 1, 2, 0, 0, 0, gate))
                code.addInstruction(BuilderInstruction11x(Opcode.MOVE_RESULT, register))
            }
            val ifAt = code.instructions.size
            code.addInstruction(BuilderInstruction10x(Opcode.NOP))
            code.addInstruction(BuilderInstruction11n(Opcode.CONST_4, 3, 0))
            code.addInstruction(BuilderInstruction11x(Opcode.RETURN, 3))
            val allowed = code.instructions.size
            code.addInstruction(BuilderInstruction11n(Opcode.CONST_4, 3, 1))
            code.addInstruction(BuilderInstruction11x(Opcode.RETURN, 3))
            code.replaceInstruction(ifAt, BuilderInstruction21t(branch, register, code.newLabelForIndex(allowed)))
            if (jumpIn) code.replaceInstruction(jumpAt, BuilderInstruction21t(Opcode.IF_EQZ, 1, code.newLabelForIndex(ifAt)))
            val flags = AccessFlags.PUBLIC.value or if (static) AccessFlags.STATIC.value else 0
            return ImmutableClassDef(
                type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(ImmutableMethod(type, "A00", emptyList(), "Z", flags, null, null, code)),
            )
        }
    }
}
