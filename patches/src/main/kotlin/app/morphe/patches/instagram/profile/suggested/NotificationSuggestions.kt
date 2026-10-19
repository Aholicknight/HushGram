/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.suggested

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide suggested people on profiles"

internal const val NOTIFICATION_PARSED = "$PROFILE_SUGGESTIONS->notificationParsed(Ljava/lang/Object;Ljava/lang/String;)V"
internal const val KEEP_NOTIFICATION = "$PROFILE_SUGGESTIONS->keepNotification(Ljava/lang/Object;)Ljava/lang/Object;"

/**
 * The keys of a notification row's kind in the server's reply: the name of the kind, and its number.
 * They're field names of the reply, so Instagram keeps them, and only the row parser loads both.
 */
internal const val NOTIF_NAME_KEY = "notif_name"
internal const val STORY_TYPE_KEY = "story_type"

/** Keys only the reply holding the notification rows loads, in the parser that fills its lists. */
internal const val NEW_ROWS_KEY = "new_stories"
internal const val OLD_ROWS_KEY = "old_stories"

private const val PARSE = "unsafeParseFromJson"
private const val PARSE_PARSER = "parseFromJsonParser"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"

/** How far after the name key's load its value's read may land. */
private const val NAME_READ_REACH = 6

/**
 * The row parser's last steps: its class and parameters, where it hands the row back, the register
 * holding the row there, a free register next to it, and the field the parser keeps the kind's name in.
 */
internal class NotificationRowSite(
    val type: String,
    val parameters: List<String>,
    val returnAt: Int,
    val row: Int,
    val name: Int,
    val nameField: String,
)

/** The reply parser's adds of a parsed row to its lists: its class, parameters, and each null check's index and register. */
internal class NotificationListSite(val type: String, val parameters: List<String>, val checks: List<Pair<Int, Int>>)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds the notifications' row parser (`LX/02F9` on 385713073): the one [PARSE] answering an object
 * that loads both [NOTIF_NAME_KEY] and [STORY_TYPE_KEY]. After the name key it reads the value into a
 * register, which it later writes into a String field of the row it makes, and it hands that row back
 * with one return-object. The hook reads the field back right before that return, so the row's register
 * has to hold the row it made on every path there: nothing outside the stretch from the row's
 * new-instance to the return may jump into it, and nothing in it may write the register. Fails before
 * anything changes when any of that isn't so, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findNotificationRows(): NotificationRowSite {
    val parsers = classesLoadingString(NOTIF_NAME_KEY).flatMap { classDef ->
        classDef.methods.filter { method ->
            method.name == PARSE && method.returnType == OBJECT && method.parameterTypes.size == 1 &&
                loadsString(method, NOTIF_NAME_KEY) && loadsString(method, STORY_TYPE_KEY)
        }
    }
    val parser = parsers.singleOrNull()
        ?: refuse("expected one row parser loading \"$NOTIF_NAME_KEY\" and \"$STORY_TYPE_KEY\", found ${parsers.size}")
    val where = "${parser.definingClass}->$PARSE"
    val code = parser.implementation!!.instructions.toList()

    val key = code.indices.filter { stringLoadedAt(code, it) == NOTIF_NAME_KEY }.singleOrNull()
        ?: refuse("$where doesn't load \"$NOTIF_NAME_KEY\" once")
    val read = (key + 1 until minOf(code.size, key + NAME_READ_REACH)).firstOrNull {
        code[it].opcode == Opcode.MOVE_RESULT_OBJECT && (code[it - 1].reference() as? MethodReference)?.returnType == STRING
    } ?: refuse("$where doesn't read a String right after \"$NOTIF_NAME_KEY\"")
    val value = (code[read] as OneRegisterInstruction).registerA

    val keeps = code.indices.filter {
        val field = code[it].reference() as? FieldReference
        code[it].opcode == Opcode.IPUT_OBJECT && field?.type == STRING && (code[it] as TwoRegisterInstruction).registerA == value
    }
    val keep = keeps.singleOrNull() ?: refuse("expected one write of \"$NOTIF_NAME_KEY\" into the row in $where, found ${keeps.size}")
    val field = code[keep].reference() as FieldReference
    val row = (code[keep] as TwoRegisterInstruction).registerB

    val made = (keep downTo 0).firstOrNull { code[it].writes(row) }
        ?: refuse("$where doesn't make the row it keeps \"$NOTIF_NAME_KEY\" in")
    if (code[made].opcode != Opcode.NEW_INSTANCE || (code[made].reference() as TypeReference).type != field.definingClass) {
        refuse("$where keeps \"$NOTIF_NAME_KEY\" in something other than a new ${field.definingClass}")
    }
    val returns = code.indices.filter {
        code[it].opcode == Opcode.RETURN_OBJECT && (code[it] as OneRegisterInstruction).registerA == row && it > keep
    }
    val returnAt = returns.singleOrNull() ?: refuse("expected one return of the row after $where keeps \"$NOTIF_NAME_KEY\", found ${returns.size}")
    val flow = ControlFlow.of(parser)
    val stretch = made + 1..returnAt
    val from = code.indices.firstOrNull { at ->
        at !in made..returnAt && (flow.normal[at] + flow.exceptional[at]).any { it in stretch }
    }
    if (from != null) refuse("$where jumps from $from into the stretch between making the row and handing it back")
    if (stretch.any { it < returnAt && code[it].writes(row) }) refuse("$where writes v$row again before handing the row back")

    // Every register but the row's is free at a return: the next one up holds the name for the call.
    val name = row + 1
    if (name >= parser.implementation!!.registerCount || name > 15) refuse("$where has no free register next to v$row")
    return NotificationRowSite(
        parser.definingClass, parser.parameterTypes.map(CharSequence::toString), returnAt, row, name,
        "${field.definingClass}->${field.name}:${field.type}",
    )
}

/**
 * Hands the row and its kind's name to [NOTIFICATION_PARSED] right before the parser gives the row
 * back, on the return's own label so the branches that skip the last optional fields run it too.
 */
internal fun BytecodePatchContext.noteNotificationRows(site: NotificationRowSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == PARSE && it.returnType == OBJECT && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    val at = method.implementation!!.instructions[site.returnAt]
    if (at.opcode != Opcode.RETURN_OBJECT || (at as OneRegisterInstruction).registerA != site.row) {
        refuse("${site.type}->$PARSE changed before its rows could be hooked")
    }
    method.addInstructionsAtControlFlowLabel(
        site.returnAt,
        """
            iget-object v${site.name}, v${site.row}, ${site.nameField}
            invoke-static/range { v${site.row} .. v${site.name} }, $NOTIFICATION_PARSED
        """,
    )
}

/**
 * Finds the reply parser that fills the notifications' lists (`LX/01DS` on 385713073): the one [PARSE]
 * answering an object that loads [NEW_ROWS_KEY] and [OLD_ROWS_KEY]. Each list's loop reads the row
 * parser's instance, parses a row with it, moves the answer into a register and skips it when it's
 * null. Every read of the row parser's instance there has to be one of those, with nothing jumping to
 * the null check, or it's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findNotificationLists(rows: NotificationRowSite): NotificationListSite {
    val replies = classesLoadingString(OLD_ROWS_KEY).flatMap { classDef ->
        classDef.methods.filter { method ->
            method.name == PARSE && method.returnType == OBJECT && method.parameterTypes.size == 1 &&
                loadsString(method, NEW_ROWS_KEY) && loadsString(method, OLD_ROWS_KEY)
        }
    }
    val reply = replies.singleOrNull()
        ?: refuse("expected one reply parser loading \"$NEW_ROWS_KEY\" and \"$OLD_ROWS_KEY\", found ${replies.size}")
    val where = "${reply.definingClass}->$PARSE"
    val code = reply.implementation!!.instructions.toList()
    val targets = reply.jumpTargets()
    val reads = code.indices.filter {
        code[it].opcode == Opcode.SGET_OBJECT && (code[it].reference() as FieldReference).let { field ->
            field.definingClass == rows.type && field.type == rows.type
        }
    }
    if (reads.size < 2) refuse("$where reads the row parser ${reads.size} times, expected one for each list")
    val checks = reads.map { at ->
        val instance = (code[at] as OneRegisterInstruction).registerA
        val parse = code.getOrNull(at + 1)
        val call = parse?.reference() as? MethodReference
        val parsed = parse?.opcode == Opcode.INVOKE_VIRTUAL && call?.name == PARSE_PARSER && call.returnType == OBJECT &&
            (parse as FiveRegisterInstruction).registerC == instance
        val moved = code.getOrNull(at + 2)
        val check = code.getOrNull(at + 3)
        if (!parsed || moved?.opcode != Opcode.MOVE_RESULT_OBJECT || check?.opcode != Opcode.IF_EQZ ||
            (check as OneRegisterInstruction).registerA != (moved as OneRegisterInstruction).registerA
        ) {
            refuse("$where's read of the row parser at $at isn't a parse with a null check after it")
        }
        if (at + 3 in targets) refuse("$where jumps to the null check at ${at + 3}")
        at + 3 to (moved as OneRegisterInstruction).registerA
    }
    return NotificationListSite(reply.definingClass, reply.parameterTypes.map(CharSequence::toString), checks)
}

/**
 * Hands each parsed row to [KEEP_NOTIFICATION] right before the list's null check, the answer back in
 * the row's register. A null from it takes the list's own way for a row that didn't parse, so the
 * row is never added and nothing is left for a gap. Later checks first, so the earlier indexes hold.
 */
internal fun BytecodePatchContext.keepNotificationRows(site: NotificationListSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == PARSE && it.returnType == OBJECT && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    for ((at, register) in site.checks.sortedByDescending { it.first }) {
        val check = method.implementation!!.instructions[at]
        if (check.opcode != Opcode.IF_EQZ || (check as OneRegisterInstruction).registerA != register) {
            refuse("${site.type}->$PARSE changed before its lists could be hooked")
        }
        method.addInstructions(
            at,
            """
                invoke-static/range { v$register .. v$register }, $KEEP_NOTIFICATION
                move-result-object v$register
            """,
        )
    }
}

/**
 * The row parser and the reply's lists, or null with a warning in the patch log when the build isn't
 * one they can be read in, so the rest of the patch still goes in. Both or neither: a row marked with
 * nothing to drop it, or a drop with nothing marked, would only count.
 */
internal fun BytecodePatchContext.findNotificationSuggestionsOrWarn(): Pair<NotificationRowSite, NotificationListSite>? = try {
    val rows = findNotificationRows()
    rows to findNotificationLists(rows)
} catch (refusal: PatchException) {
    patchLog.warning(
        "${refusal.message}. $PATCH goes in without hiding suggestions in notifications.",
    )
    null
}

private fun BytecodePatchContext.loadsString(method: Method, value: String): Boolean {
    val code = method.implementation?.instructions?.toList().orEmpty()
    return code.indices.any { stringLoadedAt(code, it) == value }
}

private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

/** Whether this instruction writes [register], a wide write into the one below it included. */
private fun Instruction.writes(register: Int): Boolean {
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    if (this is RegisterRangeInstruction) return false
    return opcode.setsRegister() && (destination == register || (opcode.setsWideRegister() && destination + 1 == register))
}
