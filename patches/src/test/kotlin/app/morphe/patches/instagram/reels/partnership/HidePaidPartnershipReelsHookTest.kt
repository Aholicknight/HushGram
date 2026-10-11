/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.partnership

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.reels.suggested.TO_CLIPS_ITEM
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HidePaidPartnershipReelsHookTest {
    private val converter = "Lfixture/Converter;"
    private val item = "Lfixture/ClipsItem;"
    private val source = "Lfixture/Source;"
    private val otherSource = "Lfixture/OtherSource;"
    private val direct = "Lfixture/DirectSource;"
    private val helped = "Lfixture/HelpedSource;"
    private val helpers = "Lfixture/Helpers;"
    private val other = "Lfixture/Other;"
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val list = "Ljava/util/List;"
    private val convert = "$converter->convert(Lfixture/ServerItem;$session)$item"
    private val fill = "$helpers->fill(${session}Ljava/util/AbstractCollection;Ljava/util/Iterator;)V"

    /** The hook and the three stubs the patch fills are in the PaidPartnershipReels the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(PAID_PARTNERSHIP_REELS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (wanted in listOf(
            PARTNERSHIP_PAGE_FILTER.substringAfter("->"),
            "media(Ljava/lang/Object;)Ljava/lang/Object;",
            "paidPartnership(Ljava/lang/Object;)Ljava/lang/Boolean;",
            "boostedStatus(Ljava/lang/Object;)Ljava/lang/String;",
        )) {
            assertTrue("$wanted is not in the extension: $declared", wanted in declared)
        }
    }

    /**
     * Both pages, the one calling the converter and the one calling its helper, pass every list they
     * return through the filter, a branch straight to a return included. Nothing else is hooked, and
     * the stubs read the item's post and the post's two fields.
     */
    @Test
    fun everyPageReturnIsFilteredAndTheStubsFilled() {
        val context = PatchContexts.of(classes())

        val sites = context.findPaidPartnershipReels()
        context.hidePaidPartnershipReels(sites)

        assertEquals(source, sites.pageInterface)
        val directPage = context.mutableClassDefBy(direct).methods.single { it.name == "page" }
        assertEveryReturnFiltered("the direct page", directPage)
        val code = directPage.code()
        val done = code.indices.single { code[it].opcode == Opcode.RETURN_OBJECT }
        assertEquals("the branch to the return lands on its filter", done - 2, directPage.targetOf(1))
        assertEveryReturnFiltered("the helped page", context.mutableClassDefBy(helped).methods.single { it.name == "page" })

        val unhooked = listOf(direct to "older", converter to "convert", helpers to "fill", other to "list")
        for ((type, name) in unhooked) {
            val method = context.mutableClassDefBy(type).methods.single { it.name == name }
            assertTrue("$type->$name was hooked", method.code().none { it.referenceText() == PARTNERSHIP_PAGE_FILTER })
        }
        assertStubsFilled("the stand-in", context, item, "post", "partnership", "boost")
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(converters = 0) to "Reels item converter holding \"$TO_CLIPS_ITEM\" in this Instagram build, found none",
            classes(converters = 2) to "Reels item converter holding \"$TO_CLIPS_ITEM\" in this Instagram build, found Lfixture/",
            classes(itemPublic = false) to "$item isn't public, so the extension can't reach it",
            classes(mediaFields = listOf("cover", "post")) to "expected $item to keep its post in one Media field, found [cover, post]",
            classes(mediaFields = emptyList()) to "expected $item to keep its post in one Media field, found []",
            classes(mediaFieldPublic = false) to "$item's post field post isn't public",
            classes(pageAnswers = "Ljava/util/Collection;") to "nothing answering a page of Reels items from a UserSession calls the Reels item converter",
            classes(helpedName = "items") to "expected every page of Reels items to share one name, found [page, items]",
            classes(helpedInterface = otherSource) to "expected every page of Reels items to be one interface's page, found",
            classes(helpedInterface = null) to "expected every page of Reels items to be one interface's page, found",
            classes(partnershipField = "is_paid_partnership_v2") to "expected one getter on $MEDIA answering Ljava/lang/Boolean; for is_paid_partnership, found none",
            classes(boostedPublic = false) to "$MEDIA->boost isn't public, so the extension can't reach it",
            classes(extension = false) to "the extension has no $PAID_PARTNERSHIP_REELS",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) {
                context.hidePaidPartnershipReels(context.findPaidPartnershipReels())
            }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == PARTNERSHIP_PAGE_FILTER || it.referenceText()?.startsWith(MEDIA) == true } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /**
     * On the declared build and each other build of 450, the patch finds twelve pages of one
     * interface and filters every return of each, and fills the stubs from the item's one Media
     * field and Media's getters for the two fields. The pages are checked against the ones this test
     * finds itself by scanning the whole build for callers of the converter and of its helpers.
     */
    @Test
    fun eachBuildFiltersEveryPage() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        var checked = 0
        for (bundle in bundles) {
            val converterClasses = FixtureDex.classesHolding(bundle, TO_CLIPS_ITEM)
            val converterMethod = converterClasses.flatMap { it.methods }.single { method -> method.code().any { it.string() == TO_CLIPS_ITEM } }
            val itemType = converterMethod.returnType

            val parents = mutableMapOf<String, List<String>>()
            val callers = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    parents.putIfAbsent(classDef.type, listOfNotNull(classDef.superclass) + classDef.interfaces)
                }
                if (dex.methodSection.none { it.calls(converterMethod) }) return@forEach
                for (classDef in dex.classes) {
                    if (classDef.methods.any { method -> method.code().any { (it.reference() as? MethodReference)?.calls(converterMethod) == true } }) {
                        callers += ImmutableClassDef.of(classDef)
                    }
                }
            }
            val helperMethods = callers.flatMap { it.methods }.filter { method ->
                AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
                    method.code().any { (it.reference() as? MethodReference)?.calls(converterMethod) == true }
            }
            val helped = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                if (dex.methodSection.none { reference -> helperMethods.any { reference.calls(it) } }) return@forEach
                for (classDef in dex.classes) {
                    val callsHelper = classDef.methods.any { method ->
                        method.code().any { instruction -> (instruction.reference() as? MethodReference)?.let { ref -> helperMethods.any { ref.calls(it) } } == true }
                    }
                    if (callsHelper) helped += ImmutableClassDef.of(classDef)
                }
            }
            val pageClasses = (callers + helped).filter { classDef -> classDef.methods.any { it.isPageShaped() && it.callsAny(listOf(converterMethod) + helperMethods) } }
                .map { it.type }.toSet()
            // The page classes' superclasses and interfaces, all the way up, for the patch to find the one declaring the page.
            val ancestors = mutableSetOf<String>()
            val next = ArrayDeque(pageClasses)
            while (next.isNotEmpty()) parents[next.removeFirst()].orEmpty().filter { it in parents && ancestors.add(it) }.forEach(next::addLast)
            val kept = (converterClasses + callers + helped + FixtureDex.classes(bundle, ancestors + itemType + MEDIA).values)
                .distinctBy { it.type } + ExtensionDex.classDef(PAID_PARTNERSHIP_REELS)
            val context = PatchContexts.of(kept)

            val sites = context.findPaidPartnershipReels()
            context.hidePaidPartnershipReels(sites)

            val hooked = kept.filter { it.type != PAID_PARTNERSHIP_REELS }.flatMap { context.mutableClassDefBy(it.type).methods }
                .filter { method -> method.code().any { it.referenceText() == PARTNERSHIP_PAGE_FILTER } }
            assertEquals("${bundle.name}: pages hooked", 12, hooked.size)
            assertEquals("${bundle.name}: the pages this test found", pageClasses, hooked.map { it.definingClass }.toSet())
            assertEquals("${bundle.name}: the pages' one name", 1, hooked.map { it.name }.distinct().size)
            for (page in hooked) {
                assertTrue("${bundle.name}: ${page.definingClass}->${page.name} isn't a page", page.isPageShaped())
                assertTrue("${bundle.name}: ${page.definingClass} doesn't come to ${sites.pageInterface}", page.definingClass.reaches(sites.pageInterface, parents))
                assertEveryReturnFiltered("${bundle.name}: ${page.definingClass}->${page.name}", page)
            }
            assertEquals("${bundle.name}: the item", itemType, sites.itemType)
            assertStubsFilled(bundle.name, context, itemType, sites.mediaField, sites.partnershipGetter.name, sites.boostedGetter.name)
            assertTrue("${bundle.name}: the partnership getter's field", sites.partnershipGetter.loads(PAID_PARTNERSHIP_FIELD.hashCode()))
            assertTrue("${bundle.name}: the boost getter's field", sites.boostedGetter.loads(BOOSTED_STATUS_FIELD.hashCode()))
            checked++
        }
        assertEquals("the seven builds of 450 weren't all read", 7, checked)
    }

    /**
     * Each return is the end of the filter call and its answer, in the return's register, the filter
     * is called once per return, and nothing jumps between the filter and its return.
     */
    private fun assertEveryReturnFiltered(what: String, method: MutableMethod) {
        val code = method.code()
        val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
        assertTrue("$what: no returns", returns.isNotEmpty())
        assertEquals("$what: filter calls", returns.size, code.count { it.referenceText() == PARTNERSHIP_PAGE_FILTER })
        val targets = method.jumpTargets()
        for (at in returns) {
            val register = (code[at] as OneRegisterInstruction).registerA
            assertEquals("$what: the filter before return $at", PARTNERSHIP_PAGE_FILTER, code[at - 2].referenceText())
            assertEquals("$what: the filter's register at $at", listOf(register), code[at - 2].rangeRegisters())
            assertEquals("$what: the answer at $at", Opcode.MOVE_RESULT_OBJECT, code[at - 1].opcode)
            assertEquals("$what: the answer's register at $at", register, (code[at - 1] as OneRegisterInstruction).registerA)
            assertTrue("$what: a jump skips the filter of return $at", (at - 1..at).none { it in targets })
        }
    }

    /** The media stub answers the item's post for an item, null otherwise, and the two getter stubs read the post's fields. */
    private fun assertStubsFilled(what: String, context: BytecodePatchContext, itemType: String, field: String, partnership: String, boosted: String) {
        val methods = context.mutableClassDefBy(PAID_PARTNERSHIP_REELS).methods
        val media = methods.single { it.name == "media" }
        assertEquals("$what: the media stub's registers", 2, media.implementation!!.registerCount)
        assertEquals(
            "$what: the media stub",
            listOf(
                "instance-of $itemType", "if-nez", "const/4", "return-object", "check-cast $itemType",
                "iget-object $itemType->$field:$MEDIA", "return-object",
            ),
            media.code().map { listOfNotNull(it.opcode.name, it.referenceText()).joinToString(" ") },
        )
        for ((stub, getter, returns) in listOf(
            Triple("paidPartnership", partnership, "Ljava/lang/Boolean;"),
            Triple("boostedStatus", boosted, "Ljava/lang/String;"),
        )) {
            val method = methods.single { it.name == stub }
            assertEquals("$what: the $stub stub's registers", 1, method.implementation!!.registerCount)
            assertEquals(
                "$what: the $stub stub",
                listOf("check-cast $MEDIA", "invoke-virtual $MEDIA->$getter()$returns", "move-result-object", "return-object"),
                method.code().map { listOfNotNull(it.opcode.name, it.referenceText()).joinToString(" ") },
            )
        }
    }

    // ---- stand-ins shaped like Instagram 450's -------------------------------------------------

    /**
     * The converter; the item with its post; Media with its getters; the source interface; a page
     * calling the converter, with a branch straight to its return and a sibling that doesn't call
     * it; a page calling the static helper that calls the converter; a static method answering a
     * List that isn't a page; and the extension.
     */
    private fun classes(
        converters: Int = 1,
        itemPublic: Boolean = true,
        mediaFields: List<String> = listOf("post"),
        mediaFieldPublic: Boolean = true,
        pageAnswers: String = list,
        helpedName: String = "page",
        helpedInterface: String? = source,
        partnershipField: String = PAID_PARTNERSHIP_FIELD,
        boostedPublic: Boolean = true,
        extension: Boolean = true,
    ): List<ClassDef> {
        val converterClasses = (0 until converters).map { copy ->
            val type = if (copy == 0) converter else "Lfixture/OtherConverter;"
            classDef(type, listOf(method(type, "convert", listOf("Lfixture/ServerItem;", session), item, 1, static = true, body = """
                const-string v0, "$TO_CLIPS_ITEM"
                new-instance v0, $item
                invoke-direct { v0 }, $item-><init>()V
                return-object v0
            """)))
        }
        val itemClass = classDef(
            item, emptyList(), public = itemPublic,
            fields = mediaFields.map { it to MEDIA }, fieldsPublic = mediaFieldPublic,
        )
        val mediaClass = classDef(MEDIA, listOf(
            getter("partnership", partnershipField, "Ljava/lang/Boolean;"),
            getter("boost", BOOSTED_STATUS_FIELD, "Ljava/lang/String;", public = boostedPublic),
            getter("caption", "caption", "Ljava/lang/String;"),
        ))
        val interfaces = listOf(source, otherSource).map { type ->
            interfaceDef(type, listOf("page", "older", "items").map { abstractMethod(type, it, listOf(session), pageAnswers) })
        }
        val directSource = classDef(direct, listOf(
            method(direct, "page", listOf(session), pageAnswers, 2, body = """
                iget-object v0, p0, $direct->items:$pageAnswers
                if-nez v0, :done
                const/4 v1, 0x0
                invoke-static { v1, p1 }, $convert
                move-result-object v1
                new-instance v0, Ljava/util/ArrayList;
                invoke-direct { v0 }, Ljava/util/ArrayList;-><init>()V
                :done
                return-object v0
            """),
            method(direct, "older", listOf(session), pageAnswers, 1, body = """
                iget-object v0, p0, $direct->items:$pageAnswers
                return-object v0
            """),
        ), fields = listOf("items" to pageAnswers), interfaces = listOf(source))
        val helperClass = classDef(helpers, listOf(
            method(helpers, "fill", listOf(session, "Ljava/util/AbstractCollection;", "Ljava/util/Iterator;"), "V", 1, static = true, body = """
                const/4 v0, 0x0
                invoke-static { v0, p0 }, $convert
                return-void
            """),
        ))
        val helpedSource = classDef(helped, listOf(
            method(helped, helpedName, listOf(session), pageAnswers, 2, body = """
                new-instance v0, Ljava/util/ArrayList;
                invoke-direct { v0 }, Ljava/util/ArrayList;-><init>()V
                const/4 v1, 0x0
                invoke-static { p1, v0, v1 }, $fill
                return-object v0
            """),
        ), interfaces = listOfNotNull(helpedInterface))
        val otherClass = classDef(other, listOf(
            method(other, "list", listOf(session), list, 2, static = true, body = """
                const/4 v0, 0x0
                invoke-static { v0, p0 }, $convert
                new-instance v1, Ljava/util/ArrayList;
                invoke-direct { v1 }, Ljava/util/ArrayList;-><init>()V
                return-object v1
            """),
        ))
        return converterClasses + itemClass + mediaClass + interfaces + directSource + helperClass + helpedSource + otherClass +
            if (extension) listOf(ExtensionDex.classDef(PAID_PARTNERSHIP_REELS)) else emptyList()
    }

    /** A Media getter reading [field] the way 450's do: by the hash of its name, loaded as a literal. */
    private fun getter(name: String, field: String, returns: String, public: Boolean = true): Method =
        method(MEDIA, name, emptyList(), returns, 1, public = public, body = """
            const v0, ${field.hashCode()}
            const/4 v0, 0x0
            return-object v0
        """)

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        static: Boolean = false,
        public: Boolean = true,
        body: String,
    ): Method {
        var flags = if (public) AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value
        if (static) flags = flags or AccessFlags.STATIC.value
        val total = registers + (if (static) 0 else 1) + parameters.size
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun abstractMethod(owner: String, name: String, parameters: List<String>, returns: String): Method =
        ImmutableMethod(
            owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
            AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null,
        )

    private fun classDef(
        type: String,
        methods: List<Method>,
        public: Boolean = true,
        fields: List<Pair<String, String>> = emptyList(),
        fieldsPublic: Boolean = true,
        interfaces: List<String> = emptyList(),
    ): ClassDef = ImmutableClassDef(
        type, (if (public) AccessFlags.PUBLIC.value else 0) or AccessFlags.FINAL.value, "Ljava/lang/Object;", interfaces, null, null,
        fields.map { (name, fieldType) ->
            ImmutableField(type, name, fieldType, if (fieldsPublic) AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value, null, null, null)
        },
        methods,
    )

    private fun interfaceDef(type: String, methods: List<Method>): ClassDef = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value, "Ljava/lang/Object;",
        null, null, null, null, methods,
    )

    // ---- reading code ---------------------------------------------------------------------------

    private fun Method.isPageShaped() = !AccessFlags.STATIC.isSet(accessFlags) && returnType == list &&
        parameterTypes.map(Any::toString) == listOf(session)

    private fun Method.callsAny(targets: List<Method>) =
        code().any { instruction -> (instruction.reference() as? MethodReference)?.let { ref -> targets.any { ref.calls(it) } } == true }

    private fun MethodReference.calls(target: Method) = definingClass == target.definingClass && name == target.name &&
        returnType == target.returnType && parameterTypes.map(Any::toString) == target.parameterTypes.map(Any::toString)

    /** Whether [this] class is [type], or extends or implements it, by the build's [parents]. */
    private fun String.reaches(type: String, parents: Map<String, List<String>>): Boolean {
        val seen = mutableSetOf<String>()
        val next = ArrayDeque(listOf(this))
        while (next.isNotEmpty()) {
            val at = next.removeFirst()
            if (at == type) return true
            if (seen.add(at)) next.addAll(parents[at].orEmpty())
        }
        return false
    }

    /** Whether a getter loads [value], the hash of the field it reads. */
    private fun Method.loads(value: Int): Boolean = code().any { (it as? NarrowLiteralInstruction)?.narrowLiteral == value }

    private fun MutableMethod.jumpTargets(): Set<Int> =
        implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }.toSet()

    private fun MutableMethod.targetOf(index: Int): Int =
        (implementation!!.instructions[index] as BuilderOffsetInstruction).target.location.index

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = when (val reference = reference()) {
        is TypeReference -> reference.type
        is FieldReference, is MethodReference -> reference.toString()
        else -> null
    }

    private fun Instruction.string(): String? = (reference() as? StringReference)?.string

    private fun Instruction.rangeRegisters(): List<Int> =
        (this as RegisterRangeInstruction).let { (it.startRegister until it.startRegister + it.registerCount).toList() }
}
