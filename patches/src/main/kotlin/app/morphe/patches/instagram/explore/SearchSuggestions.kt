/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.explore

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.feed.requireOneKindField
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide the Explore grid"

internal const val SEARCH_SUGGESTIONS = "$EXTENSION_PACKAGE/explore/SearchSuggestions;"
internal const val HIDE_SECTION = "$SEARCH_SUGGESTIONS->hideSection()Z"
internal const val NULL_STATE_ITEMS = "$SEARCH_SUGGESTIONS->items(Ljava/util/List;)Ljava/util/List;"

/**
 * The analytics name of the Suggested for you accounts under the search bar before you type, and
 * the name of their follow chaining unit. Only the two builders of that screen load both.
 */
internal const val NULL_STATE_SUGGESTED = "null_state_suggested_users"
internal const val FOLLOW_CHAINING_UNIT = "search_follow_chaining_unit"

/** The Show more row of recent searches, which only the newer of the two builders draws. */
internal const val SHOW_MORE_RECENTS = "show_more_recents"

/**
 * The kinds of item the newer builder groups by, which the extension reads by name: recent
 * searches, and the suggested accounts it drops.
 */
internal val SEARCH_ITEM_KINDS = listOf("RECENTS", "SUGGESTED_USERS")

private const val LIST = "Ljava/util/List;"

/**
 * Instagram's two builders of what shows under the search bar before you type: the older one's
 * static method adding the Suggested for you section, and the newer one's static builder of the
 * whole screen, with the register of the list of items it's handed and those items' class.
 */
internal class SearchSuggestionSites(
    val sectionType: String,
    val sectionName: String,
    val sectionParameters: List<String>,
    val builderType: String,
    val builderName: String,
    val builderParameters: List<String>,
    val itemsRegister: Int,
    val itemType: String,
)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds the two methods loading both [NULL_STATE_SUGGESTED] and [FOLLOW_CHAINING_UNIT]. The one
 * answering nothing adds the older screen's Suggested for you section. The one also loading
 * [SHOW_MORE_RECENTS] and taking one list builds the newer screen out of that list's items, and
 * the items' class is the one class it casts to with an enum field naming [SEARCH_ITEM_KINDS].
 * The builder is static, and nothing jumps back to either's start. Fails before anything changes when
 * any of it isn't so, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findSearchSuggestions(): SearchSuggestionSites {
    val holders = classesLoadingString(NULL_STATE_SUGGESTED).flatMap { classDef ->
        classDef.methods.filter { loadsString(it, NULL_STATE_SUGGESTED) && loadsString(it, FOLLOW_CHAINING_UNIT) }
    }
    val sections = holders.filter { it.returnType == "V" }
    val section = sections.singleOrNull()
        ?: refuse("expected one method adding the search screen's $NULL_STATE_SUGGESTED section, found ${sections.size}")
    val builders = holders.filter { it.returnType != "V" && loadsString(it, SHOW_MORE_RECENTS) && it.parameters().count { type -> type == LIST } == 1 }
    val builder = builders.singleOrNull()
        ?: refuse("expected one builder of the search screen taking a list and holding $SHOW_MORE_RECENTS, found ${builders.size}")
    // The older screen's section is an instance method on 452 (it was static on 451); only the builder takes
    // its list from a parameter register the hook counts, so only the builder has to be static.
    if (!AccessFlags.STATIC.isSet(builder.accessFlags)) refuse("${builder.definingClass}->${builder.name} isn't static")
    for (method in listOf(section, builder)) {
        val where = "${method.definingClass}->${method.name}"
        if (0 in method.jumpTargets()) refuse("something in $where jumps back to its start")
    }

    val casts = builder.implementation?.instructions?.toList().orEmpty()
        .filter { it.opcode == Opcode.CHECK_CAST }
        .map { ((it as ReferenceInstruction).reference as TypeReference).type }
        .distinct()
    val items = casts.filter { type -> !type.startsWith(EXTENSION_ROOT) && namesSuggestedUsers(type) }
    val itemType = items.singleOrNull()
        ?: refuse("expected the search screen's builder to cast to one class with a kind naming SUGGESTED_USERS, found $items")
    requireOneKindField(PATCH, itemType, SEARCH_ITEM_KINDS)

    return SearchSuggestionSites(
        section.definingClass, section.name, section.parameters(),
        builder.definingClass, builder.name, builder.parameters(),
        builder.parameterRegisterNumber(builder.parameters().indexOf(LIST)), itemType,
    )
}

/**
 * The older screen's section method returns first thing while [HIDE_SECTION] says so, and the
 * newer screen's builder starts by passing its list of items through [NULL_STATE_ITEMS], which
 * hands back the list without the suggested accounts while the switch is on. Without them the
 * builder draws no Suggested for you header or See all row either, since it draws those for a
 * group of suggested accounts only.
 */
internal fun BytecodePatchContext.hideSearchSuggestions(sites: SearchSuggestionSites) {
    val section = mutableClassDefBy(sites.sectionType).methods.single {
        it.name == sites.sectionName && it.parameterTypes.map(CharSequence::toString) == sites.sectionParameters
    }
    section.requireLocals(PATCH, 1)
    section.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HIDE_SECTION
            move-result v0
            if-eqz v0, :instagram
            return-void
        """,
        ExternalLabel("instagram", section.getInstruction(0)),
    )

    val builder = mutableClassDefBy(sites.builderType).methods.single {
        it.name == sites.builderName && it.parameterTypes.map(CharSequence::toString) == sites.builderParameters
    }
    builder.addInstructions(
        0,
        """
            invoke-static/range { v${sites.itemsRegister} .. v${sites.itemsRegister} }, $NULL_STATE_ITEMS
            move-result-object v${sites.itemsRegister}
        """,
    )
}

/** Whether [type] has an instance field of an enum whose setup names SUGGESTED_USERS. */
private fun BytecodePatchContext.namesSuggestedUsers(type: String): Boolean {
    val classDef = classDefByOrNull(type) ?: return false
    return classDef.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.any { field ->
        val enum = classDefByOrNull(field.type)?.takeIf { it.superclass == "Ljava/lang/Enum;" } ?: return@any false
        enum.methods.filter { it.name == "<clinit>" }.any { setup ->
            setup.implementation?.instructions?.toList().orEmpty().any {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "SUGGESTED_USERS"
            }
        }
    }
}

private fun Method.parameters(): List<String> = parameterTypes.map(CharSequence::toString)
