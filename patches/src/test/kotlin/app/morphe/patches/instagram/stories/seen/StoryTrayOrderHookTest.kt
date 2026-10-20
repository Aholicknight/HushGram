/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.seen

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The list the reel store hands Home's feed adapter goes through StoryTrayOrder just before the
 * store's method returns, after it lets go of the store, and nothing else of the store changes (#241).
 */
class StoryTrayOrderHookTest {
    /** The hook the patch calls and the stub it fills are in the extension the bundle ships, static. */
    @Test
    fun theHookAndTheStubAreInTheExtension() {
        val methods = ExtensionDex.classDef(STORY_TRAY_ORDER).methods
        val hook = methods.single { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == TRAY_ORDER.substringAfter("->") }
        assertTrue("the hook isn't public and static", AccessFlags.PUBLIC.isSet(hook.accessFlags) && AccessFlags.STATIC.isSet(hook.accessFlags))
        val stub = methods.single { it.name == "seen" }
        assertEquals(listOf(OBJECT, OBJECT), stub.parameterTypes.map(Any::toString))
        assertEquals("Z", stub.returnType)
        assertTrue("the stub isn't static", AccessFlags.STATIC.isSet(stub.accessFlags))
    }

    /**
     * On each declared build the store's list for Home's feed adapter is found alone and hooked right
     * before its one return, whose monitor-exit stays ahead of the hook; the stub asks the reel's
     * own check; 
     */
    @Test
    fun eachDeclaredBuildOrdersTheTray() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val classes = trayClasses(bundle)
                val context = PatchContexts.of(classes)
                val rings = context.findStoryRings()
                val found = context.findStoryTray(rings.reelClass)
                assertEquals("${bundle.name}: the reel", rings.reelClass, found.reelClass)

                val store = classes.single { it.type == found.store }
                val before = store.methods.single { it.name == found.lister && it.parameterTypes.map(Any::toString) == found.parameters }.code()
                assertEquals("${bundle.name}: the monitor-exit", Opcode.MONITOR_EXIT, before[found.at - 1].opcode)
                assertEquals("${bundle.name}: the one return", 1, before.count { it.opcode == Opcode.RETURN_OBJECT })

                context.orderStoryTray(found)

                val after = context.mutableClassDefBy(found.store).methods.single {
                    it.name == found.lister && it.parameterTypes.map(Any::toString) == found.parameters
                }.code()
                fun shape(code: List<Instruction>) = code.filter { it.opcode != Opcode.NOP }.map { it.opcode to it.referenceText() }
                val hooked = after.indexOfFirst { it.referenceText() == TRAY_ORDER }
                assertTrue("${bundle.name}: the hook isn't in the list's method", hooked > 0)
                assertEquals("${bundle.name}: the hook sits after the monitor-exit", Opcode.MONITOR_EXIT, after[hooked - 1].opcode)
                assertEquals("${bundle.name}: the answer is taken back", Opcode.MOVE_RESULT_OBJECT, after[hooked + 1].opcode)
                assertEquals("${bundle.name}: the answer goes to the register returned", found.result, (after[hooked + 1] as OneRegisterInstruction).registerA)
                assertEquals("${bundle.name}: the answer is returned", Opcode.RETURN_OBJECT, after[hooked + 2].opcode)
                assertEquals("${bundle.name}: the hook's registers", listOf(found.result, found.self), (after[hooked] as FiveRegisterInstruction).registers())
                assertEquals("${bundle.name}: the rest of the method", shape(before), shape(after).filterIndexed { i, _ -> i !in hooked..hooked + 1 })
                assertEquals("${bundle.name}: one call of the hook", 1, context.calls(TRAY_ORDER))

                val stub = context.mutableClassDefBy(STORY_TRAY_ORDER).methods.single { it.name == "seen" }.code()
                assertEquals(
                    "${bundle.name}: the stub",
                    listOf(
                        Opcode.CHECK_CAST to found.store, Opcode.IGET_OBJECT to "${found.store}->${found.sessionField}:$USER_SESSION",
                        Opcode.CHECK_CAST to found.reelClass, Opcode.INVOKE_VIRTUAL to "${found.reelClass}->${found.seenCheck}(${USER_SESSION})Z",
                        Opcode.MOVE_RESULT to null, Opcode.RETURN to null,
                    ),
                    stub.take(6).map { it.opcode to it.referenceText() },
                )

                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** A build whose reel store isn't there is refused, saying so. */
    @Test
    fun aBuildWithoutTheStoreFails() {
        for (version in AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val classes = trayClasses(bundle)
                val trimmed = PatchContexts.of(classes.filter { it.type != storeOf(classes) })
                val refusal = assertThrows(PatchException::class.java) { trimmed.findStoryTray(trimmed.findStoryRings().reelClass) }
                assertTrue(refusal.message, "found 0" in refusal.message!!)
            }
        }
    }

    private fun storeOf(classes: List<ClassDef>): String {
        val context = PatchContexts.of(classes)
        val reel = context.findStoryRings().reelClass
        return context.findStoryTray(reel).store
    }

    /**
     * The classes the finders read on [bundle]: those the ring finder reads, the reel store (the
     * class holding the list method the tray finder looks for) and the extension's classes.
     */
    private fun trayClasses(bundle: File): List<ClassDef> {
        val stores = FixtureDex.classesHolding(bundle, LOCAL_SEEN_STORE)
        val state = stores.flatMap { it.methods }.single {
            it.name == "<init>" && it.parameterTypes.size == 2 && it.parameterTypes[0].toString() == USER_SESSION
        }.parameterTypes[1].toString()
        val stateClass = FixtureDex.classes(bundle, setOf(state)).getValue(state)
        val stateWrite = stateClass.methods.single {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.map(Any::toString) == listOf(state, STRING, "J")
        }.key()
        val stateWriters = callersOf(bundle, stateWrite)
        val reelWrite = stateWriters.single { it.definingClass != state && stores.none { store -> store.type == it.definingClass } }.key()
        val reelWriters = callersOf(bundle, reelWrite)
        val reel = reelWrite.substringBefore("->")
        val trayStores = FixtureDex.methodsWhere(bundle, { true }) { method -> isListMethod(method, reel) }.map { it.definingClass }.toSet()
        val types = (stores.map { it.type } + state + stateWriters.map { it.definingClass } + reelWriters.map { it.definingClass } + trayStores).toSet()
        return (
            FixtureDex.withStringPools(bundle, FixtureDex.classes(bundle, types).values) +
                ExtensionDex.classDef(STORY_SEEN_RINGS) + ExtensionDex.classDef(STORY_TRAY_ORDER)
            ).map { ImmutableClassDef.of(it) }.distinctBy { it.type }
    }

    /** A cheap look for the store's list method: public, a list in and out, with a monitor and a call of the reel's seen check somewhere in its class. */
    private fun isListMethod(method: Method, reel: String): Boolean {
        if (AccessFlags.STATIC.isSet(method.accessFlags) || !AccessFlags.PUBLIC.isSet(method.accessFlags)) return false
        if (method.returnType != "Ljava/util/List;" || method.parameterTypes.map(Any::toString) != listOf("Ljava/util/List;")) return false
        val code = method.code()
        if (code.none { it.opcode == Opcode.MONITOR_ENTER }) return false
        return code.any {
            val call = (it as? ReferenceInstruction)?.reference as? MethodReference
            it.opcode == Opcode.INVOKE_DIRECT && call?.definingClass == method.definingClass && call.returnType == "Ljava/util/ArrayList;"
        } && reel.isNotEmpty()
    }

    private fun callersOf(bundle: File, key: String): List<Method> =
        FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.toString() == key } }) { method ->
            method.code().any { it.referenceText() == key }
        }


    /** Calls in Instagram's own classes (the extension's are left out). */
    private fun app.morphe.patcher.patch.BytecodePatchContext.calls(reference: String): Int {
        var count = 0
        classDefForEach { classDef ->
            if (!classDef.type.startsWith("Lapp/hushgram/")) classDef.methods.forEach { method -> count += method.code().count { it.referenceText() == reference } }
        }
        return count
    }

    private fun FiveRegisterInstruction.registers(): List<Int> =
        listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)

    private fun Method.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private companion object {
        const val OBJECT = "Ljava/lang/Object;"
        const val STRING = "Ljava/lang/String;"
    }
}
