/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.swipeprofile

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
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
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
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
    fun theWholePatchHooksTheGateAndTurnsTheStatusOn() {
        val context = PatchContexts.of(listOf(check(), ExtensionDex.classDef(SETTINGS_STATUS)))

        swipeToProfilePatch.execute(context)

        val method = context.mutableClassDefBy(CHECK).methods.single()
        assertEquals(1, method.implementation!!.instructions.count { (it as? ReferenceInstruction)?.reference?.toString() == SWIPE_ALLOWED_HOOK })
        val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "swipeToProfile" }
        assertEquals(Opcode.CONST_4, status.implementation!!.instructions.first().opcode)
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
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, found) + ExtensionDex.classDef(SETTINGS_STATUS))

        val gate = context.findSwipeGate()
        swipeToProfilePatch.execute(context)

        val method = context.mutableClassDefBy(gate.type).methods.single { it.name == gate.name && SWIPE_ALLOWED in it.markers() }
        assertHooked("$label ${gate.type}->${gate.name}", method, gate)
    }

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
