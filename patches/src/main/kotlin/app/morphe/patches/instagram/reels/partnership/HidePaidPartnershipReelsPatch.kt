/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.partnership

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.pandoGetter
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.filterEveryReturn
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.reels.suggested.ClipsItemConverterFingerprint
import app.morphe.patches.instagram.reels.suggested.TO_CLIPS_ITEM
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

private const val PATCH = "Hide paid partnership Reels"
internal const val PAID_PARTNERSHIP_REELS = "$EXTENSION_PACKAGE/reels/PaidPartnershipReels;"
internal const val PARTNERSHIP_PAGE_FILTER = "$PAID_PARTNERSHIP_REELS->page(Ljava/util/List;)Ljava/util/List;"

/** The Media field behind the Paid partnership label, which Instagram's Reels item reads to show it. */
internal const val PAID_PARTNERSHIP_FIELD = "is_paid_partnership"

/** The Media field holding where a promotion of the post stands, as Instagram's boost flows read it. */
internal const val BOOSTED_STATUS_FIELD = "boosted_status"

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val LIST = "Ljava/util/List;"
private const val OBJECT = "Ljava/lang/Object;"

/**
 * Leaves reels with a Paid partnership label, and ones Instagram marks as boosted, out of the
 * Reels viewer. In the default selection with its switch off: what's sponsored is still one of
 * Instagram's own reels, so leaving it out is the user's pick. Asked for in #12.
 */
@Suppress("unused")
val hidePaidPartnershipReelsPatch = bytecodePatch(
    name = "Hide paid partnership Reels",
    description = "Leaves out reels with a Paid partnership label, and reels Instagram marks as boosted. Ads have " +
        "their own switch. Starts off. Turn it on in HushGram settings > Reels.",
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("paidPartnershipReels")
        val sites = findPaidPartnershipReels()
        hidePaidPartnershipReels(sites)
        enableStatus("paidPartnershipReels")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * What the patch changes: each page of the Reels viewer's items (class, method name), the Reels
 * item type and its Media field, and the Media getters for [PAID_PARTNERSHIP_FIELD] and
 * [BOOSTED_STATUS_FIELD].
 */
internal class PaidPartnershipSites(
    val pages: List<Pair<String, String>>,
    val pageInterface: String,
    val itemType: String,
    val mediaField: String,
    val partnershipGetter: Method,
    val boostedGetter: Method,
)

/**
 * Finds the converter that makes each Reels item by [TO_CLIPS_ITEM], the item's one Media field,
 * and the pages of items: the instance methods taking the UserSession and answering a List that
 * call the converter, or call a static helper that does (the helper converts a page's server items
 * into the list it's handed). Every page has to be one interface's method, the same for all of
 * them, since that's the viewer asking a source for its items. The Media getters are found by the
 * hash of the field name they read. Fails before anything changes when any of it isn't there, or
 * isn't public for the extension to reach, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findPaidPartnershipReels(): PaidPartnershipSites {
    val converter = uniqueMethod(PATCH, "Reels item converter holding \"$TO_CLIPS_ITEM\"", ClipsItemConverterFingerprint)
    val itemType = converter.returnType
    val item = classDefByOrNull(itemType) ?: refuse("the Reels item converter answers $itemType, which isn't a class of the app")
    if (!AccessFlags.PUBLIC.isSet(item.accessFlags)) refuse("$itemType isn't public, so the extension can't reach it")
    val mediaFields = item.fields.filter { it.type == MEDIA && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val mediaField = mediaFields.singleOrNull()
        ?: refuse("expected $itemType to keep its post in one Media field, found ${mediaFields.map { it.name }}")
    if (!AccessFlags.PUBLIC.isSet(mediaField.accessFlags)) refuse("$itemType's post field ${mediaField.name} isn't public")

    val pages = linkedSetOf<Method>()
    callersOf(converter).forEach { caller ->
        when {
            caller.isPage() -> pages += caller
            AccessFlags.STATIC.isSet(caller.accessFlags) && caller.returnType == "V" ->
                callersOf(caller).filter { it.isPage() }.forEach { pages += it }
        }
    }
    if (pages.isEmpty()) refuse("nothing answering a page of Reels items from a UserSession calls the Reels item converter")
    val names = pages.map { it.name }.distinct()
    val name = names.singleOrNull() ?: refuse("expected every page of Reels items to share one name, found $names")
    val declared = pages.map { declaringInterfaces(it) }.distinct()
    val pageInterface = declared.singleOrNull()?.singleOrNull()
        ?: refuse("expected every page of Reels items to be one interface's $name, found $declared")
    pages.firstOrNull { page -> page.implementation?.instructions?.none { it.opcode == Opcode.RETURN_OBJECT } ?: true }
        ?.let { refuse("${it.definingClass}->$name returns no list") }

    val partnership = publicGetter(PAID_PARTNERSHIP_FIELD, "Ljava/lang/Boolean;")
    val boosted = publicGetter(BOOSTED_STATUS_FIELD, "Ljava/lang/String;")
    classDefByOrNull(PAID_PARTNERSHIP_REELS)?.let { extension ->
        if (extension.methods.none { it.isStatic("page", LIST, LIST) }) refuse("the extension has no static page(List)List")
        listOf("media" to OBJECT, "paidPartnership" to "Ljava/lang/Boolean;", "boostedStatus" to "Ljava/lang/String;")
            .filter { (stub, returns) -> extension.methods.none { it.isStatic(stub, OBJECT, returns) } }
            .forEach { (stub, returns) -> refuse("the extension has no static $stub(Object)$returns to fill") }
    } ?: refuse("the extension has no $PAID_PARTNERSHIP_REELS")

    return PaidPartnershipSites(
        pages.map { it.definingClass to it.name }, pageInterface, itemType, mediaField.name, partnership, boosted,
    )
}

/**
 * Fills the extension's three stubs, the item's post and the post's two fields, then passes every
 * list each page returns through [PARTNERSHIP_PAGE_FILTER], at the return's own label so a branch
 * straight to a return passes through it too. The filter answers the list it was handed unless it
 * leaves something out, and a List either way, so nothing is cast.
 */
internal fun BytecodePatchContext.hidePaidPartnershipReels(sites: PaidPartnershipSites) {
    val extension = mutableClassDefBy(PAID_PARTNERSHIP_REELS)
    fun fill(name: String, returns: String, registers: Int, body: String) {
        val stub = extension.methods.single { it.isStatic(name, OBJECT, returns) }
        extension.methods.remove(stub)
        extension.methods.add(replaced(stub, registers, body))
    }
    // Answers an Object: null for anything that isn't a Reels item, else the item's post, which can be null.
    fill(
        "media", OBJECT, 2,
        """
            instance-of v0, p0, ${sites.itemType}
            if-nez v0, :item
            const/4 v0, 0x0
            return-object v0
            :item
            check-cast p0, ${sites.itemType}
            iget-object v0, p0, ${sites.itemType}->${sites.mediaField}:$MEDIA
            return-object v0
        """,
    )
    fill("paidPartnership", "Ljava/lang/Boolean;", 1, getterBody(sites.partnershipGetter))
    fill("boostedStatus", "Ljava/lang/String;", 1, getterBody(sites.boostedGetter))

    sites.pages.forEach { (type, name) ->
        mutableClassDefBy(type).methods.single { it.isPageOf(name) }.filterEveryReturn(PATCH, PARTNERSHIP_PAGE_FILTER)
    }
}

/** A stub's body that hands its argument, a Media, to [getter] and answers what it reads. */
private fun getterBody(getter: Method) = """
    check-cast p0, $MEDIA
    invoke-virtual { p0 }, $MEDIA->${getter.name}()${getter.returnType}
    move-result-object p0
    return-object p0
"""

/** Media's getter for [field], which the extension calls, so it and Media have to be public. */
private fun BytecodePatchContext.publicGetter(field: String, returns: String): Method {
    val getter = pandoGetter(PATCH, MEDIA, field, returns)
    if (!AccessFlags.PUBLIC.isSet(classDefBy(MEDIA).accessFlags) || !AccessFlags.PUBLIC.isSet(getter.accessFlags)) {
        refuse("$MEDIA->${getter.name} isn't public, so the extension can't reach it")
    }
    return getter
}

/** The methods of the app that call [target] by its class, name and parameters. */
private fun BytecodePatchContext.callersOf(target: Method): List<Method> {
    val parameters = target.parameterTypes.map(Any::toString)
    return classesCalling(target.definingClass, target.name).flatMap { classDef ->
        classDef.methods.filter { method ->
            method.implementation?.instructions?.any { instruction ->
                val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                called != null && called.definingClass == target.definingClass && called.name == target.name &&
                    called.parameterTypes.map(Any::toString) == parameters && called.returnType == target.returnType
            } == true
        }
    }
}

/** An instance method with a body, taking the UserSession and answering a List. */
private fun Method.isPage() = implementation != null && !AccessFlags.STATIC.isSet(accessFlags) &&
    !AccessFlags.ABSTRACT.isSet(accessFlags) && returnType == LIST &&
    parameterTypes.map(Any::toString) == listOf(USER_SESSION)

private fun Method.isPageOf(name: String) = this.name == name && isPage()

private fun Method.isStatic(name: String, parameter: String, returns: String) =
    this.name == name && AccessFlags.STATIC.isSet(accessFlags) && returnType == returns &&
        parameterTypes.map(Any::toString) == listOf(parameter)

/** The app's interfaces, over [method]'s class and its superclasses, that declare [method]. */
private fun BytecodePatchContext.declaringInterfaces(method: Method): Set<String> {
    val found = sortedSetOf<String>()
    val parameters = method.parameterTypes.map(Any::toString)
    val seen = mutableSetOf<String>()
    fun visit(type: String) {
        if (!seen.add(type)) return
        val declaring = classDefByOrNull(type) ?: return
        if (declaring.methods.any { it.name == method.name && it.returnType == method.returnType && it.parameterTypes.map(Any::toString) == parameters }) {
            found += type
        }
        declaring.interfaces.forEach(::visit)
    }
    var holder = classDefByOrNull(method.definingClass)
    while (holder != null) {
        holder.interfaces.forEach(::visit)
        holder = holder.superclass?.let { classDefByOrNull(it) }
    }
    return found
}

/** [stub] with its body replaced by [body], in [registers] registers. */
private fun replaced(stub: Method, registers: Int, body: String): MutableMethod =
    ImmutableMethod(
        stub.definingClass, stub.name, stub.parameters, stub.returnType, stub.accessFlags, null,
        stub.hiddenApiRestrictions, ImmutableMethodImplementation(registers, emptyList(), null, null),
    ).toMutable().apply { addInstructionsWithLabels(0, body) }
