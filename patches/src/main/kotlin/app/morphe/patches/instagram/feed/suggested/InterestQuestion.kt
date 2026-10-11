/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.misc.analytics.stringLoadedAt
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Hide suggested posts"

internal const val FEED_INTEREST = "$EXTENSION_PACKAGE/feed/FeedInterest;"
internal const val HIDE_INTEREST = "$FEED_INTEREST->hide()Z"

/** The modules of Home's feed and of the Older Posts page, which Instagram adds the question's row for. */
internal const val HOME_MODULE = "feed_timeline"
internal const val OLDER_MODULE = "feed_timeline_older"

/**
 * Where Home's row builder adds the "Are you interested in this post?" row, found: the builder
 * (an instance method of the feed adapter taking the row list, a post and its state), the index of
 * the instruction that starts the add, the register that instruction writes (dead until then), and
 * the index of the builder's return. [write] puts a call to FeedInterest.hide() right before the add,
 * and a yes goes on to the return, so the row is never added and none is left blank.
 */
internal class InterestQuestion(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val rowAt: Int,
    val register: Int,
    val endAt: Int,
) {
    fun write(context: BytecodePatchContext) {
        val method = context.mutableClassDefBy(type).methods.single {
            it.name == name && it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == parameters
        }
        // Taken before the insert, so the label lands on the return itself.
        val end = method.getInstruction(endAt)
        // At the add's own label, so every way to it, Home's and Older Posts', passes the hook.
        method.addInstructionsAtControlFlowLabel(
            rowAt,
            """
                invoke-static {}, $HIDE_INTEREST
                move-result v$register
                if-nez v$register, :end
            """,
            ExternalLabel("end", end),
        )
    }
}

/**
 * Finds where Instagram's Home feed adapter adds the question's row. That adapter's row builder is
 * the one instance method of the shape (adapter list, Object, Object)V holding both feed module
 * names, [HOME_MODULE] and [OLDER_MODULE]. The row goes in at the end of the builder: a post's
 * question object (a getter on the post answering a Redex class) not being null, the feed's module
 * being [HOME_MODULE] or [OLDER_MODULE], and then the row's type constant and a jump to the shared
 * add. The question's absence and a module that isn't Home's both go straight to the builder's
 * return. The hook goes where the two module checks meet, so it only runs, and only counts, for a
 * row that was about to be added. On 450's 385611438 that's LX/02eE->buildRowViewTypes, Media.A2J()
 * and the add at instruction 442. Anything else is an update this patch hasn't seen, and it stops.
 */
internal fun BytecodePatchContext.findInterestQuestion(): InterestQuestion {
    val found = mutableListOf<Pair<Method, List<Instruction>>>()
    classesHolding(HOME_MODULE, OLDER_MODULE).forEach { classDef ->
        classDef.methods.forEach { method ->
            val parameters = method.parameterTypes.map(CharSequence::toString)
            if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "V" || parameters.size != 3 ||
                parameters[1] != OBJECT || parameters[2] != OBJECT
            ) {
                return@forEach
            }
            val code = method.implementation?.instructions?.toList() ?: return@forEach
            val names = code.indices.mapNotNull { stringLoadedAt(code, it) }.toSet()
            if (HOME_MODULE in names && OLDER_MODULE in names) found += method to code
        }
    }
    val (method, code) = found.singleOrNull()
        ?: refuse("expected one row builder holding $HOME_MODULE and $OLDER_MODULE, found ${found.size}")
    val where = "${method.definingClass}->${method.name}"
    val addresses = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
    fun targetOf(at: Int): Int {
        val target = addresses[at] + (code[at] as OffsetInstruction).codeOffset
        return addresses.indexOf(target)
    }

    val named = code.indices.filter { stringLoadedAt(code, it) == HOME_MODULE }
    val nameAt = named.singleOrNull() ?: refuse("$where loads $HOME_MODULE ${named.size} times, expected once")
    if (nameAt < 5) refuse("$where reads $HOME_MODULE before it could check a post's question")
    // post.<question>() -> move-result -> if-eqz (no question) -> module.getModuleName() -> move-result -> "feed_timeline"
    val asked = (code.getOrNull(nameAt - 5) as? ReferenceInstruction)?.reference as? MethodReference
    val module = (code.getOrNull(nameAt - 2) as? ReferenceInstruction)?.reference as? MethodReference
    val questionIn = code.getOrNull(nameAt - 4) as? OneRegisterInstruction
    val checkOn = code.getOrNull(nameAt - 3) as? OneRegisterInstruction
    if (code[nameAt - 5].opcode != Opcode.INVOKE_VIRTUAL || asked?.definingClass != MEDIA || asked.parameterTypes.isNotEmpty() ||
        !asked.returnType.startsWith("L") || code[nameAt - 4].opcode != Opcode.MOVE_RESULT_OBJECT ||
        code[nameAt - 3].opcode != Opcode.IF_EQZ || questionIn == null || checkOn == null ||
        questionIn.registerA != checkOn.registerA
    ) {
        refuse("$where doesn't check a post's question before it reads the feed's module")
    }
    if (code[nameAt - 2].opcode != Opcode.INVOKE_INTERFACE || module?.returnType != STRING || module.parameterTypes.isNotEmpty() ||
        code[nameAt - 1].opcode != Opcode.MOVE_RESULT_OBJECT
    ) {
        refuse("$where doesn't read the feed's module name before comparing it")
    }
    val endAt = targetOf(nameAt - 3)
    if (endAt < 0 || code[endAt].opcode != Opcode.RETURN_VOID) refuse("$where doesn't return when a post has no question")

    // equals -> move-result -> if-nez (Home) -> "feed_timeline_older" -> equals -> move-result -> if-eqz (neither) -> add
    val register = (code[nameAt] as OneRegisterInstruction).registerA
    val equalsHome = code.getOrNull(nameAt + 1)
    val homeAt = nameAt + 3
    val olderAt = nameAt + 4
    val addAt = nameAt + 8
    if (!equalsHome.callsEquals() || code.getOrNull(nameAt + 2)?.opcode != Opcode.MOVE_RESULT ||
        code.getOrNull(homeAt)?.opcode != Opcode.IF_NEZ || stringLoadedAt(code, olderAt) != OLDER_MODULE ||
        !code.getOrNull(nameAt + 5).callsEquals() || code.getOrNull(nameAt + 6)?.opcode != Opcode.MOVE_RESULT ||
        code.getOrNull(nameAt + 7)?.opcode != Opcode.IF_EQZ
    ) {
        refuse("$where doesn't compare the module with $HOME_MODULE and $OLDER_MODULE one after the other")
    }
    if (targetOf(homeAt) != addAt) refuse("$where doesn't add its row when the module is $HOME_MODULE")
    if (targetOf(nameAt + 7) != endAt) refuse("$where doesn't return when the module is neither")

    // The add: the row type constant into the register the comparison used, then a jump to the shared add.
    val add = code.getOrNull(addAt)
    val jump = code.getOrNull(addAt + 1)
    if (add?.opcode != Opcode.SGET_OBJECT || (add as OneRegisterInstruction).registerA != register) {
        refuse("$where doesn't load its row type into v$register where it adds the row")
    }
    if (jump?.opcode != Opcode.GOTO && jump?.opcode != Opcode.GOTO_16 && jump?.opcode != Opcode.GOTO_32) {
        refuse("$where doesn't jump to a shared add after loading its row type")
    }
    val shared = targetOf(addAt + 1)
    val ordinal = (code.getOrNull(shared) as? ReferenceInstruction)?.reference as? MethodReference
    if (shared < 0 || code[shared].opcode != Opcode.INVOKE_VIRTUAL || ordinal?.name != "ordinal" || ordinal.returnType != "I") {
        refuse("$where doesn't go on to take the row type's ordinal")
    }
    if (register > 255) refuse("$where keeps the module in v$register, past v255")
    // Nothing between the hook's write and the add may read the register: the add starts with its overwrite.
    return InterestQuestion(
        method.definingClass, method.name, method.parameterTypes.map(CharSequence::toString), addAt, register, endAt,
    )
}

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"

private fun Instruction?.callsEquals(): Boolean {
    val called = (this as? ReferenceInstruction)?.reference as? MethodReference
    return this?.opcode == Opcode.INVOKE_VIRTUAL && called?.definingClass == STRING && called.name == "equals" &&
        called.returnType == "Z" && called.parameterTypes.map(CharSequence::toString) == listOf(OBJECT)
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: the interest question: $detail")
