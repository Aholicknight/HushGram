/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.suggested

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
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hide Suggested for you on follow lists, Hide suggested people on profiles' second switch: the list's read, found and answered. */
class FollowListSuggestionsHookTest {
    private val arguments = "Landroid/os/Bundle;"
    private val list = "Lfixture/FollowList;"
    private val opener = "Lfixture/FollowListOpener;"
    private val getBoolean = "Landroid/os/BaseBundle;->getBoolean(Ljava/lang/String;Z)Z"

    /** The hook the patch writes is in the ProfileSuggestions the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(FOLLOW_LIST.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$FOLLOW_LIST is not in the extension: $declared", FOLLOW_LIST.substringAfter("->") in declared)
    }

    /** The list's read gets its answer passed through the extension, the other read and the opener left alone. */
    @Test
    fun theListsReadIsAnswered() {
        val context = PatchContexts.of(classes())
        val before = classes().single { it.type == list }.methods.single().code().size

        context.hideFollowListSuggestions(context.findFollowListSuggestions())

        val patched = context.mutableClassDefBy(list).methods.single()
        assertAnswered("stand-in", patched)
        assertEquals("what else was added", before + 2, patched.code().size)
        assertTrue("the opener", context.mutableClassDefBy(opener).methods.single().code().none { it.referenceText() == FOLLOW_LIST })
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(reads = 0) to "expected one read of the follow list's $FETCH_SUGGESTED, found 0",
            classes(reads = 2) to "expected one read of the follow list's $FETCH_SUGGESTED, found 2",
            classes(keyFrom = "v1") to "expected one read of the follow list's $FETCH_SUGGESTED, found 0",
            classes(kept = false) to "$list->onCreate reads $FETCH_SUGGESTED into nothing",
            classes(jumpPast = true) to "something in $list->onCreate jumps past its read of $FETCH_SUGGESTED",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) {
                context.hideFollowListSuggestions(context.findFollowListSuggestions())
            }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == FOLLOW_LIST } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /**
     * In each declared build the one read of the key is the follow list fragment's, in its
     * onCreate, and it's answered. On 450's 385611438 that's LX/0E6X (FollowListFragment).onCreate.
     */
    @Test
    fun eachDeclaredBuildHidesTheSection() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        // The declared build and the other builds of the version, each compiled on its own.
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("the seven 450 builds: ${bundles.map { it.name }}", 7, bundles.size)
        for (bundle in bundles) {
            val holders = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    if (classDef.methods.any { m -> m.code().any { it.string() == FETCH_SUGGESTED } }) holders += ImmutableClassDef.of(classDef)
                }
            }
            val context = PatchContexts.of(holders)

            val site = context.findFollowListSuggestions()
            context.hideFollowListSuggestions(site)

            assertEquals("${bundle.name}: the read", "onCreate($arguments)V", "${site.name}(${site.parameters.joinToString("")})V")
            val patched = context.mutableClassDefBy(site.type).methods.single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }
            assertAnswered(bundle.name, patched)
            assertEquals(
                "${bundle.name}: methods hooked", 1,
                holders.map { it.type }.distinct().sumOf { type -> context.mutableClassDefBy(type).methods.count { m -> m.code().any { it.referenceText() == FOLLOW_LIST } } },
            )
        }
    }

    /** Right after the read's move-result: the range call with that register, and its answer back in it. */
    private fun assertAnswered(what: String, method: Method) {
        val code = method.code()
        val key = code.indexOfFirst { it.string() == FETCH_SUGGESTED }
        assertTrue("$what: no load of $FETCH_SUGGESTED", key >= 0)
        val read = (key + 1 until code.size).first { code[it].referenceText() == getBoolean }
        val result = read + 1
        assertEquals("$what: the read's result", Opcode.MOVE_RESULT, code[result].opcode)
        val register = (code[result] as OneRegisterInstruction).registerA
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == FOLLOW_LIST })
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[result + 1].opcode)
        assertEquals("$what: the hook", FOLLOW_LIST, code[result + 1].referenceText())
        assertEquals("$what: what it's handed", register, (code[result + 1] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[result + 2].opcode)
        assertEquals("$what: the register", register, (code[result + 2] as OneRegisterInstruction).registerA)
    }

    /**
     * Shaped like 450's: the follow list's onCreate reads another argument and then the key out of
     * its arguments, keeping each in a field, and an opener puts the key into the arguments.
     */
    private fun classes(reads: Int = 1, keyFrom: String = "v0", kept: Boolean = true, jumpPast: Boolean = false): List<ClassDef> {
        val read = List(reads) {
            listOfNotNull(
                "const-string v0, \"$FETCH_SUGGESTED\"",
                "invoke-virtual {p1, $keyFrom, v2}, $getBoolean",
                if (kept) "move-result v0" else null,
                if (jumpPast && it == 0) ":after" else null,
                "iput-boolean v0, p0, $list->b$it:Z",
            ).joinToString("\n")
        }.joinToString("\n")
        val body = listOfNotNull(
            "const/4 v2, 0x0",
            "const-string v1, \"FollowListFragment.ShowActionBar\"",
            "invoke-virtual {p1, v1, v2}, $getBoolean",
            "move-result v0",
            if (jumpPast) "if-eqz v0, :after" else null,
            "iput-boolean v0, p0, $list->a:Z",
            read,
            "return-void",
        ).joinToString("\n")
        val onCreate = method(list, "onCreate", listOf(arguments), "V", 3, AccessFlags.PUBLIC.value, body)
        val open = method(
            opener, "A00", listOf(arguments), "V", 2, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            "const-string v0, \"$FETCH_SUGGESTED\"\nconst/4 v1, 0x1\ninvoke-virtual {p0, v0, v1}, Landroid/os/BaseBundle;->putBoolean(Ljava/lang/String;Z)V\nreturn-void",
        )
        return listOf(
            classDef(list, "Ljava/lang/Object;", listOf(onCreate)),
            classDef(opener, "Ljava/lang/Object;", listOf(open)),
        )
    }

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, flags: Int, body: String): Method {
        val total = registers + parameters.size + if (AccessFlags.STATIC.isSet(flags)) 0 else 1
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim() })
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, superclass: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, superclass, null, null, null, null, methods)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
}
