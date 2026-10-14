/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.suggested

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.analytics.stringLoadedAt
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.readsAfter
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide suggested people on profiles"

internal const val FOLLOW_BACKS = "$PROFILE_SUGGESTIONS->followBacks(Ljava/util/List;)Ljava/util/List;"

/**
 * The key of the People you don't follow back accounts in the reply your notifications load. Instagram
 * keeps it: it's the name of the field in the server's answer, and only the notifications' parser reads it.
 */
internal const val FOLLOW_BACKS_KEY = "follow_backs"

private const val PARSE = "unsafeParseFromJson"
private const val OBJECT = "Ljava/lang/Object;"
private const val LIST = "Ljava/util/List;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"

/** The parser's write of the accounts: its method, the iput-object, the register it writes and the field. */
internal class FollowBacksSite(val type: String, val parameters: List<String>, val store: Int, val register: Int, val field: String)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds where the notifications' parser (`LX/081e` on 385611438) keeps the accounts it reads under
 * [FOLLOW_BACKS_KEY]: the one [PARSE] answering an object that loads the key, and after the key the
 * new ArrayList it fills and the iput-object writing that list into a [LIST] field, before the next
 * key loads. Nothing after the write may read the list's register, since the hook can swap what it
 * holds. Fails before anything changes when there isn't exactly one such write, since that's an
 * update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findFollowBacks(): FollowBacksSite {
    val loads = classesLoadingString(FOLLOW_BACKS_KEY).flatMap { classDef ->
        classDef.methods.filter { it.name == PARSE && it.returnType == OBJECT && it.parameterTypes.size == 1 }
            .flatMap { method ->
                val code = method.implementation?.instructions?.toList().orEmpty()
                code.indices.filter { stringLoadedAt(code, it) == FOLLOW_BACKS_KEY }.map { method to it }
            }
    }
    val (parser, at) = loads.singleOrNull()
        ?: refuse("expected one parser loading \"$FOLLOW_BACKS_KEY\" once, found ${loads.size} loads")
    val where = "${parser.definingClass}->$PARSE"
    val code = parser.implementation!!.instructions.toList()
    val end = (at + 1 until code.size).firstOrNull { stringLoadedAt(code, it) != null } ?: code.size

    val made = (at + 1 until end).firstOrNull {
        code[it].opcode == Opcode.NEW_INSTANCE && ((code[it] as ReferenceInstruction).reference as TypeReference).type == ARRAY_LIST
    } ?: refuse("$where doesn't make a list for \"$FOLLOW_BACKS_KEY\"")
    val register = (code[made] as OneRegisterInstruction).registerA
    val stores = (made + 1 until end).filter {
        val field = (code[it] as? ReferenceInstruction)?.reference as? FieldReference
        code[it].opcode == Opcode.IPUT_OBJECT && field?.type == LIST && (code[it] as TwoRegisterInstruction).registerA == register
    }
    val store = stores.singleOrNull()
        ?: refuse("expected one write of the \"$FOLLOW_BACKS_KEY\" list in $where, found ${stores.size}")
    if (parser.readsAfter(store, register).isNotEmpty()) refuse("$where reads v$register after keeping \"$FOLLOW_BACKS_KEY\"")
    val field = (code[store] as ReferenceInstruction).reference as FieldReference
    return FollowBacksSite(
        parser.definingClass, parser.parameterTypes.map(CharSequence::toString), store, register,
        "${field.definingClass}->${field.name}:${field.type}",
    )
}

/**
 * Hands the list to [FOLLOW_BACKS] right before the parser keeps it, on the write's own label, since
 * the parser's loop over the accounts branches straight there. A null answer leaves the field as it
 * is when the reply has no such key, and the notifications then build no People you don't follow
 * back section at all. Notifications about new followers come under other keys and stay.
 */
internal fun BytecodePatchContext.hideFollowBacks(site: FollowBacksSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == PARSE && it.returnType == OBJECT && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    val store = method.getInstruction(site.store)
    val field = (store as? ReferenceInstruction)?.reference as? FieldReference
    if (store.opcode != Opcode.IPUT_OBJECT || "${field?.definingClass}->${field?.name}:${field?.type}" != site.field) {
        refuse("${site.type}->$PARSE changed before its \"$FOLLOW_BACKS_KEY\" list could be hooked")
    }
    method.addInstructionsAtControlFlowLabel(
        site.store,
        """
            invoke-static/range { v${site.register} .. v${site.register} }, $FOLLOW_BACKS
            move-result-object v${site.register}
        """,
    )
}
