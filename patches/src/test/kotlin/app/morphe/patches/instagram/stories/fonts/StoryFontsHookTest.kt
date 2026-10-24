/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.fonts

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
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StoryFontsHookTest {
    private val format = "Lfixture/Format;"
    private val formats = "Lfixture/Formats;"
    private val kindType = "Lfixture/FileKind;"
    private val controller = "Lfixture/Controller;"
    private val applier = "Lfixture/Apply;"
    private val string = "Ljava/lang/String;"
    private val named = "$formats->named($string$string)$format"
    private val copy = "$format->copy(Lfixture/Look;Lfixture/Kind;$format$string$string$string$string${string}I)$format"
    private val kind = "$kindType->A00:$kindType"
    private val byName = "$formats->byName($string)$format"
    private val apply = "$applier->apply(Landroid/widget/EditText;$format)V"
    private val list = "$controller->formats()Ljava/util/ArrayList;"
    private val mask = 0x7ffff7f
    private val labelId = "$format->labelId:Ljava/lang/Integer;"
    private val descriptionId = "$format->descriptionId:Ljava/lang/Integer;"
    private val labelText = "$format->labelText:$string"
    private val descriptionText = "$format->descriptionText:$string"

    /** The hooks the patch writes and the stub it fills are in the StoryFonts the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(STORY_FONTS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(FORMATS, NAMED, APPLIED, "$STORY_FONTS->$IMPORTED_FONT(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /**
     * The stub makes a format the way the import does and names it after the file, every return of
     * the editor's list hands it over first, the rebuild by name asks first, and the apply says what
     * it applied.
     */
    @Test
    fun theStubAndTheHooksGoIn() {
        val context = PatchContexts.of(classes())

        context.addStoryFonts()

        val stub = context.mutableClassDefBy(STORY_FONTS).methods.single { it.name == IMPORTED_FONT }.code()
        assertEquals(listOf(named, copy), stub.mapNotNull { it.call()?.toString() })
        assertEquals(kind, stub.firstNotNullOf { it.field() }.toString())
        assertEquals(IMPORT_FONT, stub[1].referenceText())
        assertEquals(mask, stub.filterIsInstance<NarrowLiteralInstruction>().last().narrowLiteral)
        assertEquals(Opcode.RETURN_OBJECT, stub.last().opcode)
        assertNamesTheFile(context, stub, listOf(labelId, descriptionId, labelText, descriptionText), "the stub")

        val listed = context.method(list).code()
        val returns = listed.indices.filter { listed[it].opcode == Opcode.RETURN_OBJECT }
        assertEquals(2, returns.size)
        for (at in returns) {
            assertEquals(FORMATS, listed[at - 1].referenceText())
            assertEquals((listed[at] as OneRegisterInstruction).registerA, (listed[at - 1] as RegisterRangeInstruction).startRegister)
        }
        assertEquals("the branch to the second return goes through the hook", returns[1] - 1, listed.targetOf(2))

        val rebuilt = context.method(byName).code()
        assertEquals(NAMED, rebuilt[0].referenceText())
        assertEquals("the name it's handed", 1, (rebuilt[0] as RegisterRangeInstruction).startRegister)
        assertEquals(Opcode.IF_EQZ, rebuilt[2].opcode)
        assertEquals(format, rebuilt[3].referenceText())
        assertEquals(Opcode.RETURN_OBJECT, rebuilt[4].opcode)
        assertEquals("Instagram's own body follows", Opcode.CONST_4, rebuilt[5].opcode)

        val applied = context.method(apply).code()
        assertEquals(APPLIED, applied[0].referenceText())
        assertEquals("the format it's handed", 2, (applied[0] as RegisterRangeInstruction).startRegister)
        assertEquals(3, applied.size)
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(importLoads = "OTHER_FONT") to "expected Instagram's one font import making an $IMPORT_FONT format, found 0",
            classes(imports = 2) to "expected Instagram's one font import making an $IMPORT_FONT format, found 2",
            classes(logs = "TextController") to "expected one story text editor reading its formats and logging \"$TEXT_FORMAT_CONTROLLER\", found 0",
            classes(listPrivate = false) to "$list is not the editor's own private list of formats",
            classes(rebuilds = 0) to "expected the editor to make a format from its saved name in one place, found 0",
            classes(rebuilds = 2) to "expected the editor to make a format from its saved name in one place, found 2",
            classes(applies = 0) to "expected the editor to apply a format to its text through one call, found 0",
            classes(applies = 2) to "expected the editor to apply a format to its text through one call, found 2",
            classes(copyPublic = false) to "$copy is not public and static",
            classes(kindPublic = false) to "$kind is not a public static field",
            classes(labels = false) to "expected the editor to show the current font's name from one string id, found 0",
            classes(labelTexts = 2) to "expected the editor to show the current font's name from one text, found 2",
            classes(descriptions = false) to "expected the editor's fonts to read one more string id for a screen reader, found 0",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.addStoryFonts() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().filter { it != STORY_FONTS }.flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() in setOf(FORMATS, NAMED, APPLIED) } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
            val stub = context.mutableClassDefBy(STORY_FONTS).methods.single { it.name == IMPORTED_FONT }.code()
            assertEquals("$expected: the stub was filled", listOf(Opcode.CONST_4, Opcode.RETURN_OBJECT), stub.map { it.opcode })
        }
    }

    /**
     * In each declared build Instagram's one font import, the editor's list, its rebuild by name and
     * its apply are found, and the mask the import copies with changes the kind and nothing else.
     */
    @Test
    fun eachDeclaredBuildTakesTheFonts() {
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
    fun eachOtherBuildTakesTheFontsToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val holders = (FixtureDex.classesLoading(bundle, IMPORT_FONT) + FixtureDex.classesLoading(bundle, TEXT_FORMAT_CONTROLLER))
            .distinctBy { it.type }
        val have = holders.mapTo(HashSet()) { it.type }
        val referenced = holders.asSequence().flatMap { it.methods.asSequence() }.flatMap { it.code().asSequence() }
            .flatMap { instruction ->
                when {
                    instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE ->
                        listOfNotNull(instruction.call()?.definingClass)
                    instruction.opcode == Opcode.SGET_OBJECT -> listOfNotNull(instruction.field()?.definingClass, instruction.field()?.type)
                    else -> emptyList()
                }
            }.filter { it.startsWith("LX/") && it !in have }.toSet()
        val classes = FixtureDex.withStringPools(bundle, holders + FixtureDex.classes(bundle, referenced).values)
        val context = PatchContexts.of(classes + ExtensionDex.classDef(STORY_FONTS))

        val found = context.findStoryFontTargets()
        val kept = found.mask.inv() and (Integer.highestOneBit(found.mask) * 2 - 1)
        assertEquals("$label: the import's copy changes one field: ${found.mask}", 1, Integer.bitCount(kept))

        context.addStoryFonts()

        val stub = context.mutableClassDefBy(STORY_FONTS).methods.single { it.name == IMPORTED_FONT }.code()
        assertEquals("$label: the stub", listOf(found.named.toString(), found.copy.toString()), stub.mapNotNull { it.call()?.toString() })
        assertNamesTheFile(context, stub, found.labels.map { it.toString() }, label)
        val listed = context.method(found.list.toString()).code()
        val returns = listed.indices.filter { listed[it].opcode == Opcode.RETURN_OBJECT }
        assertTrue("$label: the list returns", returns.isNotEmpty())
        for (at in returns) assertEquals("$label: before the list's return", FORMATS, listed[at - 1].referenceText())
        assertEquals("$label: the rebuild by name", NAMED, context.method(found.byName.toString()).code()[0].referenceText())
        assertEquals("$label: the apply", APPLIED, context.method(found.apply.toString()).code()[0].referenceText())
    }

    /**
     * After the copy the stub clears both string ids and writes the name it's handed to both texts,
     * and those four fields can be written from the extension.
     */
    private fun assertNamesTheFile(context: BytecodePatchContext, stub: List<Instruction>, labels: List<String>, label: String) {
        val writes = stub.filter { it.opcode == Opcode.IPUT_OBJECT }
        assertEquals("$label: the label fields written", labels, writes.map { it.field().toString() })
        val written = writes.map { (it as TwoRegisterInstruction).registerA }
        assertEquals("$label: null ids, the name handed in for both texts", listOf(3, 3, 10, 10), written)
        val fields = context.mutableClassDefBy(labels.first().substringBefore("->")).fields
        for (field in labels) {
            val declared = fields.single { "${it.definingClass}->${it.name}:${it.type}" == field }
            assertTrue("$label: $field is public", AccessFlags.PUBLIC.isSet(declared.accessFlags))
            assertTrue("$label: $field is not final or private", !AccessFlags.FINAL.isSet(declared.accessFlags) &&
                !AccessFlags.PRIVATE.isSet(declared.accessFlags))
        }
    }

    /**
     * Instagram 452's shape in small: an import that loads [IMPORT_FONT], names a format for a path
     * and copies it with a file font's kind; an editor whose current format read logs
     * "TextFormatController", reads a private list of formats, rebuilds a sticker's format from its
     * saved name, applies one to its text, labels the current font from a string id or a text, and
     * reads one more of each for a screen reader in its row of fonts.
     */
    private fun classes(
        importLoads: String = IMPORT_FONT,
        imports: Int = 1,
        logs: String = TEXT_FORMAT_CONTROLLER,
        listPrivate: Boolean = true,
        rebuilds: Int = 1,
        applies: Int = 1,
        copyPublic: Boolean = true,
        kindPublic: Boolean = true,
        labels: Boolean = true,
        labelTexts: Int = 1,
        descriptions: Boolean = true,
    ): List<ClassDef> {
        val public = AccessFlags.PUBLIC.value
        val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val copyFlags = if (copyPublic) static else AccessFlags.STATIC.value
        val importBody = """
            const/4 v3, 0x0
            const-string v0, "$importLoads"
            invoke-static {v0, v12}, $named
            move-result-object v5
            sget-object v4, $kind
            const v11, $mask
            move-object v6, v3
            move-object v7, v3
            move-object v8, v3
            move-object v9, v3
            move-object v10, v3
            invoke-static/range {v3 .. v11}, $copy
            move-result-object v0
            return-void
        """
        val importers = List(imports) { n ->
            classDef("Lfixture/Import$n;", listOf(method("Lfixture/Import$n;", "run", listOf(string), "V", 12, static, importBody)))
        }
        val hidden = AccessFlags.PRIVATE.value or AccessFlags.FINAL.value
        val formatFields = listOf("name" to string, "labelId" to "Ljava/lang/Integer;", "descriptionId" to "Ljava/lang/Integer;",
            "labelText" to string, "descriptionText" to string, "other" to string)
            .map { (name, type) -> ImmutableField(format, name, type, hidden, null, null, null) }
        val formatClass = ImmutableClassDef(format, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null,
            formatFields, listOf(
                method(format, "copy", listOf("Lfixture/Look;", "Lfixture/Kind;", format, string, string, string, string, string, "I"), format, 1, copyFlags,
                    "const/4 v0, 0x0\nreturn-object v0"),
            ))
        val formatsClass = classDef(formats, listOf(
            method(formats, "named", listOf(string, string), format, 1, static, "const/4 v0, 0x0\nreturn-object v0"),
            method(formats, "byName", listOf(string), format, 1, static, "const/4 v0, 0x0\nreturn-object v0"),
            method(formats, "byName2", listOf(string), format, 1, static, "const/4 v0, 0x0\nreturn-object v0"),
            method(formats, "saved", listOf(format, "Z"), string, 1, static, "const/4 v0, 0x0\nreturn-object v0"),
        ))
        val kindClass = ImmutableClassDef(kindType, public, "Ljava/lang/Object;", listOf("Lfixture/Kind;"), null, null,
            listOf(ImmutableField(kindType, "A00", kindType, if (kindPublic) static else AccessFlags.STATIC.value, null, null, null)), emptyList())
        val rebuild = { target: String ->
            """
                const/4 v0, 0x0
                const/4 v1, 0x0
                invoke-static {v0, v1}, $formats->saved(${format}Z)$string
                move-result-object v0
                if-eqz v0, :done
                invoke-static {v0}, $target
                move-result-object v0
                :done
                return-void
            """
        }
        val listFlags = if (listPrivate) AccessFlags.PRIVATE.value or AccessFlags.FINAL.value else AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val controllerMethods = listOf(
            method(controller, "current", emptyList(), format, 2, public, """
                invoke-direct {v2}, $list
                move-result-object v0
                const-string v1, "$logs"
                const/4 v0, 0x0
                return-object v0
            """),
            method(controller, "formats", emptyList(), "Ljava/util/ArrayList;", 1, listFlags, """
                new-instance v0, Ljava/util/ArrayList;
                invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V
                if-eqz v0, :other
                return-object v0
                :other
                return-object v0
            """),
        ) + List(rebuilds) { n ->
            method(controller, "edit$n", listOf("Lfixture/Sticker;"), "V", 2, public, rebuild(if (n == 0) byName else "$formats->byName2($string)$format"))
        } + listOfNotNull(
            method(controller, "showFont", listOf(format, "Landroid/widget/TextView;"), "V", 1, public, """
                iget-object v0, p1, $labelId
                if-eqz v0, :text
                invoke-virtual {v0}, Ljava/lang/Integer;->intValue()I
                move-result v0
                invoke-virtual {p2, v0}, Landroid/widget/TextView;->setText(I)V
                return-void
                :text
                iget-object v0, p1, ${if (labelTexts == 1) labelText else "$format->other:$string"}
                invoke-virtual {p2, v0}, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V
                return-void
            """).takeIf { labels },
            method(controller, "showOther", listOf(format, "Landroid/widget/TextView;"), "V", 1, public, """
                iget-object v0, p1, $labelText
                invoke-virtual {p2, v0}, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V
                return-void
            """).takeIf { labelTexts == 2 },
            method(controller, "row", listOf(format), "V", 1, public, """
                iget-object v0, p1, $format->name:$string
                iget-object v0, p1, $labelId
                iget-object v0, p1, $labelText
                invoke-virtual {v0}, $string->length()I
                ${if (descriptions) "iget-object v0, p1, $descriptionId" else "nop"}
                iget-object v0, p1, $descriptionText
                invoke-virtual {v0}, $string->length()I
                return-void
            """),
        ) + List(applies) { n ->
            method(controller, "apply$n", emptyList(), "V", 2, public, """
                const/4 v0, 0x0
                const/4 v1, 0x0
                invoke-static {v0, v1}, ${if (n == 0) apply else "$applier->apply2(Landroid/widget/EditText;$format)V"}
                return-void
            """)
        }
        val applyClass = classDef(applier, listOf(
            method(applier, "apply", listOf("Landroid/widget/EditText;", format), "V", 1, static, "const/4 v0, 0x0\nreturn-void"),
            method(applier, "apply2", listOf("Landroid/widget/EditText;", format), "V", 1, static, "const/4 v0, 0x0\nreturn-void"),
        ))
        return importers + listOf(formatClass, formatsClass, kindClass, classDef(controller, controllerMethods), applyClass,
            ExtensionDex.classDef(STORY_FONTS))
    }

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, flags: Int, body: String): Method {
        val self = if (AccessFlags.STATIC.isSet(flags)) 0 else 1
        val total = registers + self + parameters.size
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim() })
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null, methods)

    private fun BytecodePatchContext.method(reference: String): MutableMethod {
        val type = reference.substringBefore("->")
        val signature = reference.substringAfter("->")
        return mutableClassDefBy(type).methods.single { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == signature }
    }

    /** The index the branch at [at] jumps to. */
    private fun List<Instruction>.targetOf(at: Int): Int {
        val address = IntArray(size + 1)
        forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
        return address.indexOf(address[at] + (this[at] as OffsetInstruction).codeOffset)
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    private fun Instruction.call() = (this as? ReferenceInstruction)?.reference as? MethodReference
    private fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference
    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.let {
        (it as? com.android.tools.smali.dexlib2.iface.reference.StringReference)?.string ?: it.toString()
    }
}
