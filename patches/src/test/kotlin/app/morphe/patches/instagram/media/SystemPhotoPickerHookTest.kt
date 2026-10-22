/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPhotoPickerHookTest {
    /** The hooks the patch writes are in the SystemPhotoPicker the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(GET_PICKER_INTENT, SHOW_ENTRY, PICKER_ICON, PICKER_LABEL)) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /**
     * In each declared build the Google Photos gallery-entry factory and the flag that shows the
     * entry are found and hooked: the factory asks the extension for the picker's intent first thing,
     * and the flag is routed through the extension where the creation flow reads it.
     */
    @Test
    fun eachDeclaredBuildGetsBothHooks() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val label = bundle.name
                val holders = (FixtureDex.classesHolding(bundle, PHOTOS_COMPONENT) +
                    FixtureDex.classesHolding(bundle, ENTRY_POINT_ARG)).distinctBy { it.type }
                val context = PatchContexts.of(FixtureDex.withStringPools(bundle, holders))

                context.injectSystemPhotoPicker()

                val types = holders.map { it.type }.distinct()
                val factories = types.flatMap { context.mutableClassDefBy(it).methods }
                    .filter { method -> method.code().any { it.referenceText() == GET_PICKER_INTENT } }
                assertEquals("$label: methods asking for the picker intent", 1, factories.size)
                val factory = factories.single()
                assertEquals("$label: the factory asks first", GET_PICKER_INTENT, factory.code()[0].referenceText())
                assertTrue("$label: the factory returns the built entry",
                    factory.code().any { it.referenceText() == PICKER_LABEL } && factory.code().any { it.referenceText() == PICKER_ICON })

                val configs = types.flatMap { context.mutableClassDefBy(it).methods }
                    .filter { method -> method.code().any { it.referenceText() == SHOW_ENTRY } }
                assertEquals("$label: methods routing the show-the-entry flag", 1, configs.size)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.let {
        if (it is FieldReference) "${it.definingClass}->${it.name}:${it.type}" else it.toString()
    }
}
