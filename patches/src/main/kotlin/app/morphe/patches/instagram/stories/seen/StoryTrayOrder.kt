/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.seen

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCallingInto
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val STORY_TRAY_ORDER = "$EXTENSION_PACKAGE/stories/StoryTrayOrder;"
internal const val TRAY_ORDER = "$STORY_TRAY_ORDER->order(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;"

private const val JAVA_LIST = "Ljava/util/List;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"
private const val OBJECT_TYPE = "Ljava/lang/Object;"

/** What the store's list for Home's top story row logs when the store has no reels. */
private const val MAIN_TRAY_EMPTY = "main feed tray reels is empty"

private fun refuseTray(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * One of the reel store's methods that hands out the tray's reels: [lister], taking [parameters]
 * and returning [JAVA_LIST]. It has one return, [at], of the list in register [result], after the
 * monitor-exit that lets go of the store (register [self] is the store).
 */
internal class TraySite(
    val lister: String,
    val parameters: List<String>,
    val at: Int,
    val result: Int,
    val self: Int,
)

/**
 * What ordering Home's story tray works on, found before anything changes.
 *
 * [sites] are the methods of the reel store [store] whose lists get ordered: the one Home's top
 * story row, the main feed and the story viewer read, then the one the stories unit inside the
 * feed reads. [seenCheck] is the reel's own check (on [reelClass]) that the account has seen it, the
 * one the store's sort uses, and [sessionField] is the store's field holding the account.
 * [isSeen] is the extension's stub that makes the check.
 */
internal class StoryTrayTargets(
    val store: String,
    val sites: List<TraySite>,
    val reelClass: String,
    val seenCheck: String,
    val sessionField: String,
    internal val isSeen: MutableMethod,
)

/**
 * Finds the reel store's list for Home's feed adapter.
 *
 * Instagram keeps the tray's reels in a store. It has a private sort that splits a list of reels
 * into the ones the account hasn't seen and the ones it has, each half in its order, and puts the
 * unseen ones first, asking each reel [reelClass] one question taking the account, "seen?". The
 * store's method that takes the tray's ids and returns a list of reels is the one the feed adapter
 * calls: it runs that sort only when a server flag says to, so with the flag off the list keeps the
 * server's order. That method is public and synchronized, takes a list and returns a list, calls the
 * sort and a static filter of `(account, list)` in its own class, and has one return, after the
 * monitor-exit. Other methods of the store that take no list, or sort alone, don't have the shape.
 *
 * That list only feeds the stories unit inside the feed and a short preview. Home's top story row
 * (StoriesTrayControllerImpl), the main feed and the story viewer read the store through another of
 * its methods, which re-sorts the store by the server's ranking and hands out a copy of it: public
 * and synchronized, two booleans in and a list out, logging [MAIN_TRAY_EMPTY] when the store is
 * empty, with one return after the monitor-exit. Ordering only the first left the top row as the
 * server ranked it, watched rings mixed in with new ones (#241), so both are hooked.
 */
internal fun BytecodePatchContext.findStoryTray(reelClass: String): StoryTrayTargets {
    val found = classesCallingInto(reelClass).flatMap { classDef ->
        classDef.methods.mapNotNull { method -> trayLister(classDef.type, classDef.methods, reelClass, method)?.let { classDef to it } }
    }
    val (storeDef, tray) = found.singleOrNull()
        ?: refuseTray("expected one reel store method handing the tray's reels to Home's feed adapter, found ${found.size}")
    val main = storeDef.methods.mapNotNull(::mainTraySite)
    val top = main.singleOrNull()
        ?: refuseTray("expected one method of ${tray.store} handing Home's top story row its reels, found ${main.size}")
    return StoryTrayTargets(tray.store, listOf(top, tray.site), tray.reelClass, tray.seenCheck, tray.sessionField, trayStub())
}

/** What [trayLister] finds in a method, before the extension's stub is looked up. */
private class TrayShape(
    val store: String,
    val site: TraySite,
    val reelClass: String,
    val seenCheck: String,
    val sessionField: String,
)

private fun trayLister(store: String, methods: Iterable<Method>, reelClass: String, method: Method): TrayShape? {
    if (AccessFlags.STATIC.isSet(method.accessFlags) || !AccessFlags.PUBLIC.isSet(method.accessFlags)) return null
    if (method.returnType != JAVA_LIST || method.parameterTypes.map(Any::toString) != listOf(JAVA_LIST)) return null
    val code = method.implementation?.instructions?.toList() ?: return null
    if (code.none { it.opcode == Opcode.MONITOR_ENTER }) return null
    val calls = code.mapNotNull { it.trayCall() }
    val sort = methods.filter { candidate ->
        !AccessFlags.STATIC.isSet(candidate.accessFlags) && candidate.returnType == ARRAY_LIST &&
            candidate.parameterTypes.map(Any::toString) == listOf(JAVA_LIST) &&
            calls.any { it.definingClass == store && it.name == candidate.name && it.parameterTypes.map(Any::toString) == listOf(JAVA_LIST) }
    }.singleOrNull() ?: return null
    val filter = calls.any {
        it.definingClass == store && it.returnType == ARRAY_LIST && it.parameterTypes.map(Any::toString) == listOf(USER_SESSION, JAVA_LIST)
    }
    if (!filter) return null

    val sortCode = sort.implementation?.instructions?.toList() ?: return null
    val checks = sortCode.filter { it.opcode == Opcode.INVOKE_VIRTUAL || it.opcode == Opcode.INVOKE_VIRTUAL_RANGE }
        .mapNotNull { it.trayCall() }
        .filter { it.definingClass == reelClass && it.returnType == "Z" && it.parameterTypes.map(Any::toString) == listOf(USER_SESSION) }
        .map { it.name }.distinct()
    // Only a virtual call: the stub makes the same call with invoke-virtual.
    val seenCheck = checks.singleOrNull() ?: return null
    val session = sortCode.mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
        .filter { it.definingClass == store && it.type == USER_SESSION }.map { it.name }.distinct().singleOrNull() ?: return null

    val site = returnSite(method, code) ?: return null
    return TrayShape(store = store, site = site, reelClass = reelClass, seenCheck = seenCheck, sessionField = session)
}

/** The store's list for Home's top story row, the one that logs [MAIN_TRAY_EMPTY] (see [findStoryTray]). */
private fun mainTraySite(method: Method): TraySite? {
    if (AccessFlags.STATIC.isSet(method.accessFlags) || !AccessFlags.PUBLIC.isSet(method.accessFlags)) return null
    if (!AccessFlags.DECLARED_SYNCHRONIZED.isSet(method.accessFlags)) return null
    if (method.returnType != JAVA_LIST || method.parameterTypes.map(Any::toString) != listOf("Z", "Z")) return null
    val code = method.implementation?.instructions?.toList() ?: return null
    if (code.none { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == MAIN_TRAY_EMPTY }) return null
    return returnSite(method, code)
}

/** [method]'s one return of a list, right after the monitor-exit that lets go of the store, or null. */
private fun returnSite(method: Method, code: List<Instruction>): TraySite? {
    val at = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }.singleOrNull() ?: return null
    if (at == 0 || code[at - 1].opcode != Opcode.MONITOR_EXIT) return null
    val result = (code[at] as OneRegisterInstruction).registerA
    val self = method.implementation!!.registerCount - 1 - method.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
    // The monitor-exit lets go of the store itself, so its register still holds the store at the return.
    if ((code[at - 1] as OneRegisterInstruction).registerA != self) return null
    if (result > 15 || self > 15 || result == self) return null
    return TraySite(method.name, method.parameterTypes.map(Any::toString), at, result, self)
}

private fun Instruction.trayCall(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun BytecodePatchContext.trayStub(): MutableMethod {
    val order = classDefByOrNull(STORY_TRAY_ORDER)?.let { mutableClassDefBy(STORY_TRAY_ORDER) }
        ?: refuseTray("$STORY_TRAY_ORDER isn't in the extension")
    order.methods.singleOrNull {
        "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == TRAY_ORDER.substringAfter("->") &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
    } ?: refuseTray("$STORY_TRAY_ORDER has no public static hook ${TRAY_ORDER.substringAfter("->")}")
    return order.methods.singleOrNull {
        it.name == "seen" && it.returnType == "Z" && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == listOf(OBJECT_TYPE, OBJECT_TYPE)
    } ?: refuseTray("$STORY_TRAY_ORDER has no static Z seen(${OBJECT_TYPE}${OBJECT_TYPE})")
}

/**
 * Hands each list the reel store gives out for the tray, just before it returns, to the extension,
 * which moves the reels the account has watched behind the ones it hasn't while views are held back
 * and watched stories are grayed (#241). The stub asks Instagram's own check.
 */
internal fun BytecodePatchContext.orderStoryTray(found: StoryTrayTargets) {
    val store = mutableClassDefBy(found.store)
    val listers = found.sites.map { site ->
        site to store.methods.single {
            it.name == site.lister && it.parameterTypes.map(Any::toString) == site.parameters && it.returnType == JAVA_LIST
        }
    }
    // Every site is checked before any changes, so a moved one leaves the store as it was.
    for ((site, lister) in listers) {
        val code = lister.implementation!!.instructions.toList()
        if (code.getOrNull(site.at)?.opcode != Opcode.RETURN_OBJECT || code.getOrNull(site.at - 1)?.opcode != Opcode.MONITOR_EXIT) {
            throw PatchException("$PATCH: ${found.store}->${site.lister} changed before its list was hooked")
        }
    }
    for ((site, lister) in listers) {
        lister.addInstructionsAtControlFlowLabel(
            site.at,
            """
                invoke-static { v${site.result}, v${site.self} }, $TRAY_ORDER
                move-result-object v${site.result}
            """,
        )
    }
    // p0 and p1 are the store and a reel. The reel is checked for its own class first; every path returns its own value.
    found.isSeen.addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${found.store}
            iget-object p0, p0, ${found.store}->${found.sessionField}:$USER_SESSION
            check-cast p1, ${found.reelClass}
            invoke-virtual { p1, p0 }, ${found.reelClass}->${found.seenCheck}(${USER_SESSION})Z
            move-result p0
            return p0
        """,
    )
}
