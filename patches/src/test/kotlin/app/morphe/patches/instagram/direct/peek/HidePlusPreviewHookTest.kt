/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.peek

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
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
 * Hide Plus preview in chat menus (#249): the one flag that puts a chat's long press menu in peek
 * form goes through the extension right after Instagram unboxes it, and a build where the menu
 * builder or the flag can't be told apart fails the patch before an instruction changes.
 */
class HidePlusPreviewHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(PEEK_MODE.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$PEEK_MODE is not in the extension: $declared", PEEK_MODE.substringAfter("->") in declared)
    }

    @Test
    fun theFlagGoesThroughTheExtensionRightAfterItIsUnboxed() {
        val context = PatchContexts.of(listOf(builder()))

        val site = context.findPeekMode()
        context.hookPeekMode(site)

        assertEquals(4, site.moveResult)
        assertEquals(18, site.register)
        assertHooked("stand-in", context.mutableClassDefBy(BUILDER).methods.single(), site)
    }

    /** A menu with no branch over the row has no peek form to turn off. */
    @Test
    fun aMenuWithoutTheBranchFailsThePatch() {
        refuses("found 0") { PatchContexts.of(listOf(builder(branch = false))).findPeekMode() }
    }

    /** A flag that isn't a Boolean's unboxed value is some other test. */
    @Test
    fun aFlagNotUnboxedFromABooleanFailsThePatch() {
        refuses("found 0") { PatchContexts.of(listOf(builder(unboxes = false))).findPeekMode() }
    }

    @Test
    fun aMethodWithoutTheEventStringsIsNotTheBuilder() {
        refuses("found 0") { PatchContexts.of(listOf(builder(strings = listOf(OVERLAY_FAILED)))).findPeekMode() }
    }

    @Test
    fun twoBuildersFailThePatch() {
        refuses("found 2") { PatchContexts.of(listOf(builder(), builder("Lfixture/OtherBuilder;"))).findPeekMode() }
    }

    /** In each declared build: the one flag, unboxed from a Boolean, with the extension's answer taking its place. */
    @Test
    fun eachDeclaredBuildTurnsOffThePeekForm() {
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
    fun eachOtherBuildTurnsOffThePeekFormToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val kept = FixtureDex.withStringPools(bundle, FixtureDex.classesLoading(bundle, OVERLAY_FAILED).map { ImmutableClassDef.of(it) })
        val context = PatchContexts.of(kept)

        val site = context.findPeekMode()
        context.hookPeekMode(site)

        val method = context.mutableClassDefBy(site.type).methods
            .single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }
        assertHooked("$label ${site.type}->${site.name}", method, site)
    }

    /** The unboxed flag, the hook taking it as a range of one, and the answer back in the same register; one hook in all. */
    private fun assertHooked(what: String, method: Method, site: PeekSite) {
        val code = method.implementation!!.instructions.toList()
        assertEquals("$what: hooks", 1, code.count { (it as? ReferenceInstruction)?.reference?.toString() == PEEK_MODE })
        assertEquals("$what: the unboxed flag", Opcode.MOVE_RESULT, code[site.moveResult].opcode)
        assertEquals("$what: the flag's register", site.register, (code[site.moveResult] as OneRegisterInstruction).registerA)
        val unbox = (code[site.moveResult - 1] as ReferenceInstruction).reference as MethodReference
        assertEquals("$what: from a Boolean", "Ljava/lang/Boolean;->booleanValue()Z", unbox.toString())
        val call = code[site.moveResult + 1]
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals("$what: the hook", PEEK_MODE, (call as ReferenceInstruction).reference.toString())
        assertEquals("$what: the call's register", site.register, (call as RegisterRangeInstruction).startRegister)
        assertEquals("$what: one register", 1, call.registerCount)
        val back = code[site.moveResult + 2]
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, back.opcode)
        assertEquals("$what: into the same register", site.register, (back as OneRegisterInstruction).registerA)
        assertEquals("$what: the branch still reads the flag", Opcode.IF_EQZ, code.first { it.opcode == Opcode.IF_EQZ }.opcode)
    }

    /** The patch refuses, for the reason given. */
    private fun refuses(reason: String, search: () -> Unit) {
        val refusal = assertThrows(PatchException::class.java) { search() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
    }

    private companion object {
        const val BUILDER = "Lfixture/MenuBuilder;"
        const val POPUP = "Lfixture/Popup;"
        const val LISTS = "Lfixture/Lists;"

        /**
         * Shaped like 452's menu builder: the three strings, a Boolean unboxed into v18, a branch on it
         * over the one row appended to the list (a static Iterable and Collection call answering an
         * ArrayList) and the popup's one-list call, then the code showing the popup.
         */
        fun builder(
            type: String = BUILDER,
            strings: List<String> = listOf(OVERLAY_FAILED, MENU_CONTROLLER, LONG_PRESS),
            branch: Boolean = true,
            unboxes: Boolean = true,
        ): ClassDef {
            val code = MutableMethodImplementation(24)
            strings.forEachIndexed { i, s -> code.addInstruction(BuilderInstruction21c(Opcode.CONST_STRING, i, ImmutableStringReference(s))) }
            val unbox = ImmutableMethodReference("Ljava/lang/Boolean;", "booleanValue", emptyList(), "Z")
            val other = ImmutableMethodReference("Ljava/lang/Object;", "hashCode", emptyList(), "I")
            code.addInstruction(BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 3, 0, 0, 0, 0, if (unboxes) unbox else other))
            code.addInstruction(BuilderInstruction11x(Opcode.MOVE_RESULT, 18))
            val ifAt = code.instructions.size
            code.addInstruction(BuilderInstruction10x(Opcode.NOP))
            val append = ImmutableMethodReference(LISTS, "A1P", listOf("Ljava/lang/Iterable;", "Ljava/util/Collection;"), "Ljava/util/ArrayList;")
            code.addInstruction(BuilderInstruction35c(Opcode.INVOKE_STATIC, 2, 4, 5, 0, 0, 0, append))
            code.addInstruction(BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 4))
            val hand = ImmutableMethodReference(POPUP, "A09", listOf("Ljava/util/List;"), "V")
            code.addInstruction(BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 6, 4, 0, 0, 0, hand))
            code.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
            // The branch goes over the append and the popup call, to the return.
            if (branch) code.replaceInstruction(ifAt, BuilderInstruction21t(Opcode.IF_EQZ, 18, code.newLabelForIndex(ifAt + 4)))
            return ImmutableClassDef(
                type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(
                    ImmutableMethod(type, "A00", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, code),
                ),
            )
        }
    }
}
