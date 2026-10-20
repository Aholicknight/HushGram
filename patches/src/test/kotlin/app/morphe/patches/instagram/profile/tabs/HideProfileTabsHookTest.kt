/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.tabs

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HideProfileTabsHookTest {
    private val owner = "Lfixture/TabList;"
    private val user = "Lcom/instagram/user/model/User;"

    /** The hook the patch writes is in the ProfileTabs the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(TABS_HOOK.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$TABS_HOOK is not in the extension: $declared", TABS_HOOK.substringAfter("->") in declared)
    }

    /** The list goes through the hook right before the one return, and nothing else in the method moves. */
    @Test
    fun theListIsHandedToTheHookJustBeforeItReturns() {
        val context = PatchContexts.of(listOf(builder(), ExtensionDex.classDef(PROFILE_TABS)))
        val before = context.mutableClassDefBy(owner).methods.single { it.name == "build" }.code().map { it.shape() }

        context.hideProfileTabs(context.findTabList())

        val after = context.mutableClassDefBy(owner).methods.single { it.name == "build" }.code()
        assertEquals(before.size + 2, after.size)
        val at = after.indexOfFirst { it.reference() == TABS_HOOK }
        assertEquals(Opcode.INVOKE_STATIC_RANGE, after[at].opcode)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, after[at + 1].opcode)
        assertEquals(Opcode.RETURN_OBJECT, after[at + 2].opcode)
        assertEquals(1, after.count { it.reference() == TABS_HOOK })
        assertEquals("everything else stays as it was", before, after.filterIndexed { i, _ -> i != at && i != at + 1 }.map { it.shape() })
    }

    /** A build the patch can't read fails at patch time, saying what it found, with nothing changed. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            listOf(builder(keys = PROFILE_TAB_KEYS.dropLast(1))) to "expected one builder of a profile's tab keys, found 0",
            listOf(builder(), builder("Lfixture/Other;")) to "expected one builder of a profile's tab keys, found 2",
            listOf(builder(returns = "Ljava/lang/Object;")) to "expected one builder of a profile's tab keys, found 0",
            listOf(builder(secondReturn = true)) to "returns in 2 places",
            listOf(builder(jumpOntoReturn = true)) to "has a jump onto its return",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.findTabList() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
        }
    }

    /** On each declared build of the app, the builder is found once and the hook lands before its one return. */
    @Test
    fun everyDeclaredBuildTakesTheHookBeforeTheTabListReturns() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                takesTheHook(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun takesTheHook(bundle: java.io.File) {
        val holders = FixtureDex.classesLoading(bundle, PROFILE_TAB_KEYS.last())
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, holders) + ExtensionDex.classDef(PROFILE_TABS))
        val site = context.findTabList()
        fun MutableMethod.isSite() = name == site.name && parameterTypes.map(Any::toString) == site.parameters
        val before = context.mutableClassDefBy(site.type).methods.single { it.isSite() }.code().map { it.shape() }

        context.hideProfileTabs(site)

        val after = context.mutableClassDefBy(site.type).methods.single { it.isSite() }.code()
        val at = after.indexOfFirst { it.reference() == TABS_HOOK }
        assertEquals("${bundle.name}: the hook is in front of the return", Opcode.RETURN_OBJECT, after[at + 2].opcode)
        assertEquals("${bundle.name}: one return", 1, after.count { it.opcode == Opcode.RETURN_OBJECT })
        assertEquals("${bundle.name}: the rest is untouched", before, after.filterIndexed { i, _ -> i != at && i != at + 1 }.map { it.shape() })
    }

    private fun builder(
        type: String = owner,
        keys: List<String> = PROFILE_TAB_KEYS,
        returns: String = "Ljava/util/List;",
        secondReturn: Boolean = false,
        jumpOntoReturn: Boolean = false,
    ): ClassDef {
        val loads = keys.joinToString("\n") { "const-string v1, \"$it\"" }
        val body = buildString {
            appendLine("new-instance v0, Ljava/util/ArrayList;")
            appendLine("invoke-direct { v0 }, Ljava/util/ArrayList;-><init>()V")
            appendLine(loads)
            if (secondReturn) {
                appendLine("if-eqz p1, :early")
                appendLine("goto :done")
                appendLine(":early")
                appendLine("return-object v0")
                appendLine(":done")
            }
            if (jumpOntoReturn) {
                appendLine("if-eqz p1, :done")
                appendLine("invoke-virtual { v0, v1 }, Ljava/util/AbstractCollection;->add(Ljava/lang/Object;)Z")
                appendLine(":done")
            }
            appendLine("return-object v0")
        }
        val mutable = MutableMethod(
            ImmutableMethod(
                type, "build", listOf(ImmutableMethodParameter(user, null, null)), returns, AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(4, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body)
        return ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(ImmutableMethod.of(mutable)))
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.shape() = opcode to reference()
}
