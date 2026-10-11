/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accent color: every list AndroidX's color state list loader returns goes through
 * AccentColor.list, the color each of the two theme color reads returns goes through
 * AccentColor.color, and anything the patch can't find exactly fails it before a change.
 */
class AccentColorHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(ACCENT_COLOR).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SWAP_COLOR is not in the extension: $declared", SWAP_COLOR in declared)
        assertTrue("$SWAP_LIST is not in the extension: $declared", SWAP_LIST in declared)
    }

    @Test
    fun everyReturnGoesThroughTheExtension() {
        val context = PatchContexts.of(listOf(loader(), themes(), colors(), extension()))

        val found = context.findAccentReads()
        assertEquals("$LOADER->A01", "${found.loader.definingClass}->${found.loader.name}")
        assertEquals(setOf("$THEMES->A0Y", "$COLORS->A03"), found.colorReads.map { "${it.definingClass}->${it.name}" }.toSet())
        context.swapAccentColors()

        assertListsSwapped("stand-in", context.loaderMethod(), 2)
        assertColorSwapped("stand-in with a check first", context.method(THEMES, "A0Y"))
        assertColorSwapped("stand-in", context.method(COLORS, "A03"))
        assertEquals("the resolver itself is left alone", 0, context.method(THEMES, "A0b").hooks(SWAP_COLOR).size)
    }

    @Test
    fun anythingItCantFindExactlyFailsThePatch() {
        for ((case, classes) in listOf(
            "no loader" to listOf(themes(), colors(), extension()),
            "two loaders" to listOf(loader(), loader(type = "Lfixture/OtherCompat;"), themes(), colors(), extension()),
            "a loader that only throws" to listOf(loader(throws = true), themes(), colors(), extension()),
            "no attribute resolver" to listOf(loader(), themes(resolver = false), colors(), extension()),
            "one color read" to listOf(loader(), themes(), extension()),
            "three color reads" to listOf(loader(), themes(), colors(), colors(type = "Lfixture/MoreColors;"), extension()),
            "a read asking about another attribute" to listOf(loader(), themes(), colors(attribute = "v0"), extension()),
            "a read answering the resource, not its color" to listOf(loader(), themes(), colors(getColor = false), extension()),
            "no hook in the extension" to listOf(loader(), themes(), colors()),
        )) {
            val failure = assertThrows(case, PatchException::class.java) { PatchContexts.of(classes).findAccentReads() }
            assertTrue("$case: ${failure.message}", failure.message!!.startsWith("Accent color:"))
        }
    }

    /** A refusal comes before any change: the loader keeps every instruction it had. */
    @Test
    fun aRefusalChangesNothing() {
        val stub = loader()
        val context = PatchContexts.of(listOf(stub, themes(), extension()))
        val before = stub.methods.single().code().map { it.opcode }
        assertThrows(PatchException::class.java) { context.swapAccentColors() }
        assertEquals(before, context.classDefBy(LOADER).methods.single().code().map { it.opcode })
    }

    /** The declared build: the loader's three returns and both color reads go through the extension. */
    @Test
    fun theDeclaredBuildSwapsAtEveryRead() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                checkBuild(bundle.name, bundle)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /**
     * The same in the other six builds of 450 (#77, #95). The loader, the two reads and the
     * resolver they call move class and name from build to build, and each is found by its shape.
     */
    @Test
    fun everyOtherBuildSwapsAtEveryRead() {
        val checked = Fixtures.otherBuilds().map { bundle ->
            checkBuild(bundle.parentFile.name, bundle)
            bundle.parentFile.name
        }
        assertEquals("the other builds: $checked", 6, checked.size)
    }

    /** Patches the slice of [bundle] the finder reads and checks each change. */
    private fun checkBuild(label: String, bundle: java.io.File) {
        val holders = FixtureDex.classesHolding(bundle, INFLATE_FAILED)
        val resolvers = FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.definingClass == THEME && it.name == "resolveAttribute" } }) {
            it.isStaticContextAndInt() && it.code().any { instruction -> instruction.calls(THEME, "resolveAttribute") }
        }
        val reads = FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.definingClass == CONTEXT && it.name == "getColor" } }) {
            it.isStaticContextAndInt() && it.code().size <= 7 && it.code().any { instruction -> instruction.calls(CONTEXT, "getColor") }
        }
        val types = (resolvers + reads).mapTo(HashSet()) { it.definingClass }
        val context = PatchContexts.of((holders + FixtureDex.classes(bundle, types).values).distinctBy { it.type } + extension())

        val found = context.findAccentReads()
        val loader = found.loader.definingClass to found.loader.name
        val colorReads = found.colorReads.map { it.definingClass to it.name }
        val returns = found.loader.code().count { it.opcode == Opcode.RETURN_OBJECT }
        assertEquals("$label: the loader's returns", 3, returns)
        context.swapAccentColors()

        assertListsSwapped("$label ${loader.first}->${loader.second}", context.method(loader.first, loader.second), returns)
        assertEquals("$label: the color reads", 2, colorReads.size)
        for ((type, name) in colorReads) assertColorSwapped("$label $type->$name", context.method(type, name))
    }

    /** [count] returns, each right after the list hook on its own register, and no jump past the hook. */
    private fun assertListsSwapped(what: String, method: MutableMethod, count: Int) {
        val code = method.code()
        val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
        assertEquals("$what: returns", count, returns.size)
        assertEquals("$what: hooks", count, method.hooks(SWAP_LIST).size)
        val targets = method.jumpTargets()
        for (at in returns) {
            val register = (code[at] as OneRegisterInstruction).registerA
            assertHook("$what at $at", code, at, register, SWAP_LIST, Opcode.MOVE_RESULT_OBJECT)
            assertFalse("$what: a jump lands past the hook before $at", at in targets || at - 1 in targets)
        }
    }

    /** One hook, right before the one return, passing and getting back the color returned. */
    private fun assertColorSwapped(what: String, method: MutableMethod) {
        val code = method.code()
        val returns = code.indices.filter { code[it].opcode == Opcode.RETURN }
        assertEquals("$what: returns", 1, returns.size)
        assertEquals("$what: hooks", 1, method.hooks(SWAP_COLOR).size)
        val at = returns.single()
        val register = (code[at] as OneRegisterInstruction).registerA
        assertHook(what, code, at, register, SWAP_COLOR, Opcode.MOVE_RESULT)
        assertEquals("$what: getColor's answer is what's swapped", Opcode.MOVE_RESULT, code[at - 3].opcode)
        assertEquals("$what: getColor's answer is what's swapped", register, (code[at - 3] as OneRegisterInstruction).registerA)
        assertTrue("$what: getColor right before", code[at - 4].calls(CONTEXT, "getColor"))
    }

    private fun assertHook(what: String, code: List<Instruction>, at: Int, register: Int, hook: String, move: Opcode) {
        val call = code[at - 2]
        assertEquals("$what: the hook", Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals("$what: the hook", hook, (call as ReferenceInstruction).reference.toString())
        assertEquals("$what: the value passed", register, (call as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the value alone", 1, call.registerCount)
        assertEquals("$what: the answer", move, code[at - 1].opcode)
        assertEquals("$what: the answer replaces the value", register, (code[at - 1] as OneRegisterInstruction).registerA)
    }

    private fun BytecodePatchContext.loaderMethod(): MutableMethod = method(LOADER, "A01")

    private fun BytecodePatchContext.method(type: String, name: String): MutableMethod =
        mutableClassDefBy(type).methods.single { it.name == name && it.parameterTypes.map(Any::toString) == listOf(CONTEXT, "I") }

    private fun Method.hooks(hook: String) = code().filter { (it as? ReferenceInstruction)?.reference?.toString() == hook }

    private fun MutableMethod.jumpTargets(): Set<Int> =
        implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }.toSet()

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.isStaticContextAndInt() = AccessFlags.STATIC.isSet(accessFlags) && returnType == "I" &&
        parameterTypes.map(Any::toString) == listOf(CONTEXT, "I") && implementation != null

    private fun Instruction.calls(definingClass: String, name: String) =
        ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let { it.definingClass == definingClass && it.name == name } == true

    private companion object {
        const val LOADER = "Lfixture/ResourcesCompat;"
        const val THEMES = "Lfixture/Themes;"
        const val COLORS = "Lfixture/Colors;"
        const val CONTEXT = "Landroid/content/Context;"
        const val THEME = "Landroid/content/res/Resources\$Theme;"
        const val LIST = "Landroid/content/res/ColorStateList;"

        fun extension(): ClassDef = ExtensionDex.classDef(ACCENT_COLOR)

        /**
         * A static (Context, int)ColorStateList shaped like ResourcesCompat's loader: it holds the
         * log line, and its second return is where a branch lands, as the third is on 450.
         */
        fun loader(type: String = LOADER, throws: Boolean = false): ClassDef = classOf(
            type,
            static(
                type, "A01", LIST, 4,
                if (throws) {
                    """
                        const-string v0, "$INFLATE_FAILED"
                        new-instance v1, Ljava/lang/IllegalStateException;
                        invoke-direct { v1 }, Ljava/lang/IllegalStateException;-><init>()V
                        throw v1
                    """
                } else {
                    """
                        const-string v0, "$INFLATE_FAILED"
                        const/4 v1, 0x0
                        if-eqz p1, :none
                        invoke-virtual { p0, p1 }, $CONTEXT->getColorStateList(I)$LIST
                        move-result-object v1
                        return-object v1
                        :none
                        return-object v1
                    """
                },
            ),
        )

        /**
         * The resolver, shaped like 450's: it resolves the attribute against the Context's theme and
         * answers the resource id. With it, the read that runs a check first and then calls it.
         */
        fun themes(resolver: Boolean = true): ClassDef = classOf(
            THEMES,
            static(
                THEMES, "A0b", "I", 5,
                """
                    new-instance v0, Landroid/util/TypedValue;
                    invoke-direct { v0 }, Landroid/util/TypedValue;-><init>()V
                    invoke-virtual { p0 }, $CONTEXT->getTheme()$THEME
                    move-result-object v1
                    const/4 v2, 0x1
                    ${if (resolver) "invoke-virtual { v1, p1, v0, v2 }, $THEME->resolveAttribute(ILandroid/util/TypedValue;Z)Z" else ""}
                    iget v0, v0, Landroid/util/TypedValue;->resourceId:I
                    return v0
                """,
            ),
            static(
                THEMES, "A0Y", "I", 3,
                """
                    const/4 v0, 0x0
                    invoke-static { p0, v0 }, $THEMES->A0W(Ljava/lang/Object;I)V
                    invoke-static { p0, p1 }, $THEMES->A0b($CONTEXT I)I
                    move-result v0
                    invoke-virtual { p0, v0 }, $CONTEXT->getColor(I)I
                    move-result v0
                    return v0
                """.replace("$CONTEXT I", "${CONTEXT}I"),
            ),
        )

        /** The read with nothing before its call to the resolver, or one broken as each flag says. */
        fun colors(type: String = COLORS, attribute: String = "p1", getColor: Boolean = true): ClassDef = classOf(
            type,
            static(
                type, "A03", "I", 3,
                """
                    const/4 v0, 0x0
                    invoke-static { p0, $attribute }, $THEMES->A0b(${CONTEXT}I)I
                    move-result v0
                    ${if (getColor) "invoke-virtual { p0, v0 }, $CONTEXT->getColor(I)I" else "invoke-static { v0 }, $THEMES->A0W(I)I"}
                    move-result v0
                    return v0
                """,
            ),
        )

        fun static(type: String, name: String, returns: String, registers: Int, body: String): Method = ImmutableMethod.of(
            MutableMethod(
                ImmutableMethod(
                    type, name, listOf(CONTEXT, "I").map { ImmutableMethodParameter(it, null, null) }, returns,
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                    ImmutableMethodImplementation(registers, emptyList(), null, null),
                ),
            ).apply { addInstructionsWithLabels(0, body.trimIndent().lines().filter { it.isNotBlank() }.joinToString("\n")) },
        )

        fun classOf(type: String, vararg methods: Method): ClassDef = ImmutableClassDef(
            type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
            null, null, null, null, methods.toList(),
        )
    }
}
