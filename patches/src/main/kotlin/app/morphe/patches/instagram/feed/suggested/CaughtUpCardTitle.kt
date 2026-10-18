/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

private const val PATCH = "Hide suggested posts"

/**
 * Kotlin's messages for the end of feed card binder's casts of its view holder and of its card,
 * which keep the names Instagram's source gives those classes. Only that binder's bindView loads both.
 */
internal const val END_CARD_HOLDER =
    "null cannot be cast to non-null type com.instagram.mainfeed.unconnectedcontent.demarcator.EndOfFeedUnitDemarcatorHolder"
internal const val END_CARD_UNIT = "null cannot be cast to non-null type com.instagram.feed.feeditem.EndOfFeedDemarcatorUnit"

internal const val CAUGHT_UP_TITLE = "$FEED_SUGGESTIONS->caughtUpTitle(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;"

/** The extension's stub the patch fills to find the section an end of feed card shows under its title. */
internal const val CAUGHT_UP_SECTION_STUB = "caughtUpSection"

/** The extension's stub the patch fills to read a section's name, which the card's title has to be. */
internal const val SECTION_NAME_STUB = "sectionName"

private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"
private const val LIST = "Ljava/util/List;"
private val BIND_VIEW = listOf("I", "Landroid/view/View;", OBJECT, OBJECT)

/**
 * The end of feed card's title, found: its binder, the index of the holder's (title, subtitle) call
 * and that call, the registers holding the title and the card, and the walk from the card to the
 * section its group set has open. The walk takes the card's data, the data's group set and a new
 * sections view of that group set from the card's own section getter, then looks up the view's own
 * open section id, the section the Suggested for you header titles. The getter itself looks up fixed
 * ids, the Older posts link's section among them. [write] fills the extension's stubs with that walk
 * and the section's [name] field, and passes the title through FeedSuggestions.caughtUpTitle right
 * before the call, so a card titled with the name of an open section that shows nothing gets no
 * title. The subtitle and the Older posts link stay.
 */
internal class CaughtUpCardTitle(
    val binder: String,
    val titleAt: Int,
    val titleCall: String,
    val title: Int,
    val card: Int,
    val cardType: String,
    val data: String,
    val groupSet: String,
    val groupSetType: String,
    val sectionsType: String,
    val activeId: String,
    val lookup: String,
    /** The section's class, the same one the header's hook reads. */
    val sectionType: String,
    /** The section's list of items, for FeedSuggestions.sectionItems when the header's hook didn't fill it. */
    val items: String,
    /** The section's name, the field the header's title is read from. */
    val name: String,
) {
    /** [itemsFilled] when the header's hook already filled FeedSuggestions.sectionItems. */
    fun write(context: BytecodePatchContext, itemsFilled: Boolean) = with(context) {
        val bind = mutableClassDefBy(binder).methods.single { it.isBindView() }
        // Found before the patch's other steps wrote anything: if one moved the title's call, leave the card be.
        val now = bind.implementation?.instructions?.toList()?.getOrNull(titleAt)
        if (now?.methodReference()?.toString() != titleCall || (now as FiveRegisterInstruction).registerD != title) {
            patchLog.warning("$PATCH: the caught up card's title moved before it was hooked. " +
                "The card keeps its Suggested for you title when nothing is left under it.")
            return@with
        }
        val extension = mutableClassDefBy(FEED_SUGGESTIONS)
        val stub = staticStub(FEED_SUGGESTIONS, CAUGHT_UP_SECTION_STUB, OBJECT)
        extension.methods.remove(stub)
        // Three registers: the sections view and the open section's id next to the card in p0.
        extension.methods.add(
            ImmutableMethod(
                stub.definingClass, stub.name, stub.parameters, stub.returnType, stub.accessFlags,
                stub.annotations, stub.hiddenApiRestrictions, ImmutableMethodImplementation(3, emptyList(), null, null),
            ).toMutable().apply {
                addInstructionsWithLabels(
                    0,
                    """
                        check-cast p0, $cardType
                        iget-object p0, p0, $data
                        if-eqz p0, :none
                        iget-object p0, p0, $groupSet
                        if-eqz p0, :none
                        new-instance v0, $sectionsType
                        invoke-direct { v0, p0 }, $sectionsType-><init>($groupSetType)V
                        iget-object v1, v0, $activeId
                        invoke-virtual { v0, v1 }, $lookup
                        move-result-object p0
                        :none
                        return-object p0
                    """,
                )
            },
        )
        staticStub(FEED_SUGGESTIONS, SECTION_NAME_STUB, STRING).addInstructions(
            0,
            """
                check-cast p0, $sectionType
                iget-object p0, p0, $name
                return-object p0
            """,
        )
        if (!itemsFilled) {
            staticStub(FEED_SUGGESTIONS, SECTION_ITEMS_STUB, LIST).addInstructions(
                0,
                """
                    check-cast p0, $sectionType
                    iget-object p0, p0, $items
                    return-object p0
                """,
            )
        }
        bind.addInstructions(
            titleAt,
            """
                invoke-static { v$title, v$card }, $CAUGHT_UP_TITLE
                move-result-object v$title
            """,
        )
    }
}

/**
 * Finds the end of feed card's title. The card's binder is the one bindView(int, View, Object, Object)
 * loading both [END_CARD_HOLDER] and [END_CARD_UNIT]. In it:
 * - the card's register and class come from the first cast after [END_CARD_UNIT] and the holder's
 *   class from the first cast after [END_CARD_HOLDER];
 * - the title is the one call on the holder taking two Strings, its (title, subtitle). The card has
 *   to stay in its register from its cast to that call, and nothing may jump onto the call;
 * - the card's section getter is its one call on the card taking nothing and answering a class of the app.
 * On 451's 385713073 that's LX/05ws's bindView: LX/0Bft->A07 at 34 with the title in v1, and the
 * card, LX/06hB, in v8 with its getter A00.
 *
 * In the getter, the one constructor taking one value, other than the section's, is the sections
 * view of the group set (LX/06LH from LX/06LE). The getter's read of a field of the group set's
 * class is the data's group set (LX/06LF->A05), and its read of a field of the data's class on the
 * card is the card's data (LX/06hB->A01). The sections view's call taking a String and answering
 * the getter's class is its lookup by id (LX/06LH->A00), and the one String its constructor keeps is
 * the open section's id (LX/06LH->A01), the id the header's binder matches its title's section by.
 * The section class (LX/06LI) has one list field, its items, and [sectionName] is the header's read
 * of its name (LX/06LI->A05). Every class and member the stubs reach has to be public, since the
 * extension calls them.
 *
 * Anything else is an update this patch hasn't seen, and it stops.
 */
internal fun BytecodePatchContext.findCaughtUpCardTitle(sectionName: FieldReference): CaughtUpCardTitle {
    val binds = mutableListOf<Pair<ClassDef, Method>>()
    classesHolding(END_CARD_HOLDER, END_CARD_UNIT).forEach { classDef ->
        classDef.methods.filter { it.isBindView() && it.loads(END_CARD_HOLDER) && it.loads(END_CARD_UNIT) }
            .forEach { binds += classDef to it }
    }
    val (binder, bind) = binds.singleOrNull()
        ?: refuse("expected one bindView loading both end of feed card casts, found ${binds.size}")
    val code = bind.implementation?.instructions?.toList() ?: refuse("${binder.type}->bindView has no code")

    val (cardAt, cardType) = castAfter(code, END_CARD_UNIT) ?: refuse("bindView doesn't cast the card after naming it")
    val card = (code[cardAt] as OneRegisterInstruction).registerA
    val holderType = castAfter(code, END_CARD_HOLDER)?.second ?: refuse("bindView doesn't cast the holder after naming it")

    val titles = code.indices.filter { at ->
        val call = code[at].methodReference() ?: return@filter false
        code[at].opcode == Opcode.INVOKE_VIRTUAL && call.definingClass == holderType &&
            call.parameterTypes.map(CharSequence::toString) == listOf(STRING, STRING) && call.returnType == "V"
    }
    val titleAt = titles.singleOrNull()
        ?: refuse("expected one (String, String) call on the holder $holderType, found ${titles.size}")
    val title = (code[titleAt] as FiveRegisterInstruction).registerD
    if (card > 15 || title > 15) refuse("bindView keeps the card or the title past v15")
    if (titleAt <= cardAt) refuse("bindView sets the title before it casts the card")
    if ((cardAt + 1 until titleAt).any { code[it].writes(card) }) refuse("bindView writes over the card before the title")
    val flow = ControlFlow.of(bind)
    val jumpsIn = flow.instructions.indices.any { from ->
        (from < cardAt || from >= titleAt) && (flow.normal[from] + flow.exceptional[from]).any { it in cardAt + 1..titleAt }
    }
    if (jumpsIn || titleAt in bind.jumpTargets()) refuse("something jumps between the card's cast and the title")

    val getters = code.mapNotNull { it.methodReference() }.filter {
        it.definingClass == cardType && it.parameterTypes.isEmpty() && it.returnType.startsWith("L") &&
            !it.returnType.startsWith("Ljava/")
    }.map { it.toString() }.distinct()
    val getterRef = getters.singleOrNull()
        ?: refuse("expected one section getter called on the card $cardType, found ${getters.size}")
    val cardClass = classDefByOrNull(cardType) ?: refuse("$cardType isn't in this build")
    val getter = cardClass.methods.singleOrNull { it.reference() == getterRef } ?: refuse("$getterRef isn't in $cardType")
    val sectionType = getter.returnType
    val walk = getter.implementation?.instructions?.toList() ?: refuse("$getterRef has no code")

    val inits = walk.filter { it.opcode == Opcode.INVOKE_DIRECT }.mapNotNull { it.methodReference() }
        .filter { it.name == "<init>" && it.parameterTypes.size == 1 && it.definingClass != sectionType }
        .map { it.definingClass to it.parameterTypes.single().toString() }.distinct()
    val (sectionsType, groupSetType) = inits.singleOrNull()
        ?: refuse("expected the getter to make one view of the group set, found ${inits.size}")
    val reads = walk.filter { it.opcode == Opcode.IGET_OBJECT }.mapNotNull { (it as ReferenceInstruction).reference as? FieldReference }
    val groupSet = reads.filter { it.type == groupSetType }.distinctBy { it.smali() }.singleOrNull()
        ?: refuse("expected the getter to read one $groupSetType field")
    val data = reads.filter { it.definingClass == cardType && it.type == groupSet.definingClass }.distinctBy { it.smali() }.singleOrNull()
        ?: refuse("expected the getter to read the card's ${groupSet.definingClass} once")
    val lookups = walk.mapNotNull { it.methodReference() }.filter {
        it.definingClass == sectionsType && it.parameterTypes.map(CharSequence::toString) == listOf(STRING) &&
            it.returnType == sectionType
    }.map { it.toString() }.distinct()
    val lookupRef = lookups.singleOrNull() ?: refuse("expected one lookup by id on $sectionsType, found ${lookups.size}")
    val sections = classDefByOrNull(sectionsType) ?: refuse("$sectionsType isn't in this build")
    val lookup = sections.methods.singleOrNull { it.reference() == lookupRef } ?: refuse("$lookupRef isn't in $sectionsType")
    val constructor = sections.methods.singleOrNull {
        it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf(groupSetType)
    } ?: refuse("$sectionsType has no constructor from $groupSetType")
    val ids = constructor.implementation?.instructions?.toList().orEmpty()
        .filter { it.opcode == Opcode.IPUT_OBJECT }
        .mapNotNull { (it as ReferenceInstruction).reference as? FieldReference }
        .filter { it.definingClass == sectionsType && it.type == STRING }
        .distinctBy { it.smali() }
    val activeId = ids.singleOrNull() ?: refuse("expected $sectionsType's constructor to keep one id, found ${ids.size}")

    val sectionClass = classDefByOrNull(sectionType) ?: refuse("$sectionType isn't in this build")
    val lists = sectionClass.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == LIST }
    val items = lists.singleOrNull() ?: refuse("expected one list field in $sectionType, found ${lists.size}")
    if (sectionName.definingClass != sectionType || sectionName.type != STRING) {
        refuse("the header's section name ${sectionName.smali()} isn't a String of $sectionType")
    }

    for (type in listOf(cardType, data.definingClass, groupSetType, sectionsType, sectionType)) {
        val classDef = classDefByOrNull(type) ?: refuse("$type isn't in this build")
        if (!AccessFlags.PUBLIC.isSet(classDef.accessFlags)) refuse("$type isn't public, so the extension can't reach it")
    }
    for (field in listOf(data, groupSet, activeId, items, sectionName)) {
        val declared = classDefByOrNull(field.definingClass)?.fields?.singleOrNull { it.name == field.name && it.type == field.type }
            ?: refuse("${field.smali()} isn't declared where it's read")
        if (!AccessFlags.PUBLIC.isSet(declared.accessFlags) || AccessFlags.STATIC.isSet(declared.accessFlags)) {
            refuse("${field.smali()} isn't a public instance field")
        }
    }
    for (method in listOf(constructor, lookup)) {
        if (!AccessFlags.PUBLIC.isSet(method.accessFlags) || AccessFlags.STATIC.isSet(method.accessFlags)) {
            refuse("${method.reference()} isn't a public instance method")
        }
    }
    // Every stub the write fills has to be there.
    staticStub(FEED_SUGGESTIONS, CAUGHT_UP_SECTION_STUB, OBJECT)
    staticStub(FEED_SUGGESTIONS, SECTION_ITEMS_STUB, LIST)
    staticStub(FEED_SUGGESTIONS, SECTION_NAME_STUB, STRING)

    return CaughtUpCardTitle(
        binder.type, titleAt, code[titleAt].methodReference().toString(), title, card, cardType,
        data.smali(), groupSet.smali(), groupSetType, sectionsType, activeId.smali(), lookupRef, sectionType, items.smali(),
        sectionName.smali(),
    )
}

/**
 * [findCaughtUpCardTitle], or null after the patch log says why, and the caught up card keeps its
 * title. [header] is the Suggested for you header's hook. Without it there's no section name to match
 * the card's title to, and a card titled anything else ("You're all caught up" over the header) keeps
 * its title, so the card is left be. Its section class has to be the card's, since both read their
 * items through the same stub.
 */
internal fun BytecodePatchContext.caughtUpCardTitleOrWarn(header: SuggestedSectionHeader?): CaughtUpCardTitle? = try {
    if (header == null) refuse("the Suggested for you header wasn't found, so there's no section name to match it to")
    findCaughtUpCardTitle(header.name)
} catch (moved: PatchException) {
    patchLog.warning("${moved.message}. The caught up card keeps its Suggested for you title when nothing is left under it.")
    null
}

/** The first cast after the one load of [message] in [code]: its index and type. */
private fun castAfter(code: List<Instruction>, message: String): Pair<Int, String>? {
    val named = code.indexOfFirst { it.loadsString(message) }
    if (named < 0) return null
    val at = (named + 1 until code.size).firstOrNull { code[it].opcode == Opcode.CHECK_CAST } ?: return null
    return at to ((code[at] as ReferenceInstruction).reference as TypeReference).type
}

private fun FieldReference.smali() = "$definingClass->$name:$type"

private fun Method.reference() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Method.loads(value: String) = implementation?.instructions?.any { it.loadsString(value) } == true

private fun Instruction.loadsString(value: String) =
    (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
        ((this as ReferenceInstruction).reference as StringReference).string == value

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Method.isBindView(): Boolean =
    name == "bindView" && returnType == "V" && !AccessFlags.STATIC.isSet(accessFlags) &&
        parameterTypes.map(CharSequence::toString) == BIND_VIEW

/** Whether this instruction writes [register], as the low or the high half of a wide value too. */
private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val written = when (this) {
        is OneRegisterInstruction -> registerA
        is TwoRegisterInstruction -> registerA
        else -> return false
    }
    return written == register || (opcode.setsWideRegister() && written + 1 == register)
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: the caught up card's title: $detail")
