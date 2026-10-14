/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.fullscreen

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.misc.glass.glassTabBarPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #147: Instagram's window insets listener asks the extension about each bar height it keeps, the
 * status bar's through barHeight and the navigation bar's through navBarHeight (#144).
 */
class FullScreenBarsHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(FULL_SCREEN_BARS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(BAR_HEIGHT_HOOK, NAV_BAR_HEIGHT_HOOK)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /** Full screen and Glass tab bar share the one routing, so the listener asks once per height whichever is in (#144). */
    @Test
    fun bothPatchesShareTheRouting() {
        assertTrue(barHeightsPatch in fullScreenBarsPatch.dependencies)
        assertTrue(barHeightsPatch in glassTabBarPatch.dependencies)
    }

    /** In each declared build both stores ask first, whether the classes are copied or read as the patcher reads an APK. */
    @Test
    fun eachDeclaredBuildAsksAboutBothBarHeights() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildAsksToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val types = FixtureDex.classesHolding(bundle, STATUS_BAR_CHANGED).map { it.type }.toSet()
        for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
            val what = "$label ($read)"
            val classes = classesOf(bundle, types).values
            assertEquals("$what: the listener's class", types, classes.map { it.type }.toSet())
            val context = PatchContexts.of(classes)
            val listener = context.findBarHeights()
            val before = listener.code()
            val native = NeutralNativePath(listener)
            val stores = listener.barHeightStores()

            routeBarHeights(listener)

            val code = listener.code()
            assertEquals("$what: two instructions added per store", before.size + 4, code.size)
            val hooks = setOf(BAR_HEIGHT_HOOK, NAV_BAR_HEIGHT_HOOK)
            val asks = code.indices.filter { code[it].referenceText() in hooks }
            assertEquals("$what: asks once per height", 2, asks.size)
            for ((ask, store) in asks.zip(stores.sortedBy { it.index })) {
                val hook = if (store == stores[0]) BAR_HEIGHT_HOOK else NAV_BAR_HEIGHT_HOOK
                assertEquals("$what: the status bar asks barHeight, the navigation bar navBarHeight", hook, code[ask].referenceText())
                assertEquals("$what: the ask is an invoke-static", Opcode.INVOKE_STATIC, code[ask].opcode)
                assertEquals("$what: on the height", listOf(store.register), code[ask].arguments())
                assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[ask + 1].opcode)
                assertEquals("$what: in the height's register", store.register, (code[ask + 1] as OneRegisterInstruction).registerA)
                assertEquals("$what: stored", Opcode.SPUT, code[ask + 2].opcode)
                assertEquals("$what: from the answer", store.register, (code[ask + 2] as OneRegisterInstruction).registerA)
                assertEquals("$what: to the height's field", store.field.toString(), (code[ask + 2] as ReferenceInstruction).reference.toString())
            }
            val heights = stores.map { it.field.toString() }.toSet()
            assertEquals("$what: the status bar's and the navigation bar's fields", 2, heights.size)
            val unasked = code.indices.filter { at ->
                code[at].opcode == Opcode.SPUT && (code[at].reference() as FieldReference).toString() in heights &&
                    code.getOrNull(at - 2)?.referenceText() !in hooks
            }
            assertEquals("$what: a height stored without asking", emptyList<Int>(), unasked)
            val added = asks.flatMap { listOf(it + 1, it + 2) }.toSet()
            native.assertPreserved(what, listener, added, asks.toSet())
        }
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()

    private fun Instruction.arguments(): List<Int> {
        val five = this as FiveRegisterInstruction
        return listOf(five.registerC, five.registerD, five.registerE, five.registerF, five.registerG).take(five.registerCount)
    }
}
