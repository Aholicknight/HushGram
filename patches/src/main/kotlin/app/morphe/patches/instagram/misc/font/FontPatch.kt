/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.font

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Font"

internal const val FONTS = "$EXTENSION_PACKAGE/misc/Fonts;"
internal const val PRISM_TYPEFACE = "$FONTS->prismTypeface(Landroid/graphics/Typeface;)Landroid/graphics/Typeface;"
internal const val TEXT_VIEW = "$FONTS->textView(Landroid/widget/TextView;)V"

/** What Instagram's Prism font helper logs when a TextView won't take a text appearance. No other class holds it. */
internal const val PRISM_MARKER = "IgdsPrismFontHelper_setTextAppearance_UnsupportedOperationException"

/** Instagram's own TextView, a name its build keeps, and the static that sets each one up. */
internal const val IG_TEXT_VIEW = "Lcom/instagram/common/ui/base/IgTextView;"
internal const val INITIALIZE =
    "Lcom/instagram/common/ui/base/IgTextViewUtils;->initialize(Landroid/widget/TextView;Landroid/content/Context;Landroid/util/AttributeSet;)V"

private const val TYPEFACE = "Landroid/graphics/Typeface;"
private const val CONTEXT = "Landroid/content/Context;"

/**
 * Instagram's text draws in the phone's font or a font file picked in HushGram settings. Included
 * in the default selection with Instagram's font chosen, which changes nothing (#166, #5).
 *
 * Instagram's newer design draws its text in Prism Sans. Its font helper loads the regular,
 * semibold and bold weights once each, as it's first used or later on first ask, and keeps them in
 * three static fields that every Prism text reads. The patch hands each Typeface to the extension
 * before it's kept, and it answers the chosen font at the same weight and slant. Each IgTextView,
 * Instagram's own TextView, also goes to the extension right after Instagram sets it up, so text
 * outside Prism takes the chosen font too.
 */
@Suppress("unused")
val fontPatch = bytecodePatch(
    name = "Font",
    description = "Lets you pick the font Instagram's text uses: Instagram's own, your phone's, or a .ttf or .otf " +
        "file. Restart Instagram to see a change. Starts on Instagram's font. Pick one in HushGram settings > Layout.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("font")
        swapFonts()
        enableStatus("font")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")

/** One write of a Prism Sans Typeface into the helper's fields: where it is and the register written. */
internal class PrismWrite(val method: Method, val at: Int, val register: Int)

/** Every method the patch changes, found and checked before anything changes. */
internal class FontSites(val writes: List<PrismWrite>, val textViewInit: Method, val initializeAt: Int, val view: Int)

/**
 * Finds the helper and IgTextView's set-up and checks the extension.
 *
 * The helper is the one class holding [PRISM_MARKER]. Its three static Typeface fields that
 * aren't final are where it keeps Prism Sans, one per weight. Each is written twice, once in its
 * static initializer and once in the instance (Context) Typeface getter that loads it when it's
 * still empty, and nothing else writes them. Any other count means a build this wasn't written for.
 *
 * IgTextView's set-up is the one call in [IG_TEXT_VIEW] to [INITIALIZE], from an instance method,
 * with an instruction after it that nothing jumps to, so whatever runs next has the view in the
 * register the call was given.
 */
internal fun BytecodePatchContext.findFontSites(): FontSites {
    val helpers = classesHolding(PRISM_MARKER)
    val helper = helpers.singleOrNull() ?: refuse("expected one class holding \"$PRISM_MARKER\", found ${helpers.size}")
    val fields = helper.staticFields.filter { it.type == TYPEFACE && !AccessFlags.FINAL.isSet(it.accessFlags) }
        .map { it.name }.toSet()
    if (fields.size != 3) refuse("${helper.type} keeps ${fields.size} Typefaces that can change, not one per weight")

    val writes = helper.methods.flatMap { method ->
        method.code().withIndex().filter { (_, instruction) -> instruction.writes(helper.type, fields) }
            .map { (at, instruction) -> PrismWrite(method, at, (instruction as OneRegisterInstruction).registerA) }
    }
    for (field in fields) {
        val into = writes.filter { (it.method.code()[it.at] as ReferenceInstruction).field().name == field }
        val inStatic = into.count { it.method.name == "<clinit>" }
        val inGetter = into.count { it.method.isTypefaceGetter() }
        if (into.size != 2 || inStatic != 1 || inGetter != 1) {
            refuse("${helper.type}->$field is written ${into.size} times, not once as it starts and once by its getter")
        }
    }

    val textView = classDefByOrNull(IG_TEXT_VIEW) ?: refuse("this build has no $IG_TEXT_VIEW")
    val calls = textView.methods.flatMap { method ->
        method.code().withIndex().filter { (_, instruction) -> instruction.calls(INITIALIZE) }.map { (at, _) -> method to at }
    }
    val (init, at) = calls.singleOrNull()
        ?: refuse("expected one call to $INITIALIZE in $IG_TEXT_VIEW, found ${calls.size}")
    val where = "${init.definingClass}->${init.name}"
    if (AccessFlags.STATIC.isSet(init.accessFlags)) refuse("$where, which sets up the view, is static")
    val call = init.code()[at]
    if (call.opcode != Opcode.INVOKE_STATIC || call !is FiveRegisterInstruction || call.registerCount != 3) {
        refuse("$where doesn't hand $INITIALIZE the view, its Context and its attributes")
    }
    if (at + 1 >= init.code().size || at + 1 in init.jumpTargets()) {
        refuse("$where has no instruction after $INITIALIZE that only it leads to")
    }

    for (hook in listOf(PRISM_TYPEFACE, TEXT_VIEW)) {
        if (classDefByOrNull(FONTS)?.methods?.any {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook &&
                    AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
            } != true
        ) {
            refuse("the extension has no public static $hook")
        }
    }
    return FontSites(writes, init, at, call.registerC)
}

/**
 * Each Typeface the helper keeps goes through Fonts.prismTypeface first, at the write's own label,
 * since the helper's empty path jumps straight to the write with null. The latest write in each
 * method goes in first, so the earlier ones keep their places. Then Fonts.textView gets each
 * IgTextView right after Instagram sets it up. The range form takes any register.
 */
internal fun BytecodePatchContext.swapFonts() {
    val found = findFontSites()
    for ((method, writes) in found.writes.groupBy { it.method }) {
        val mutable = mutable(method)
        for (write in writes.sortedByDescending { it.at }) {
            mutable.addInstructionsAtControlFlowLabel(
                write.at,
                """
                    invoke-static/range { v${write.register} .. v${write.register} }, $PRISM_TYPEFACE
                    move-result-object v${write.register}
                """,
            )
        }
    }
    mutable(found.textViewInit).addInstructions(
        found.initializeAt + 1,
        "invoke-static/range { v${found.view} .. v${found.view} }, $TEXT_VIEW",
    )
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }

/** An instance (Context) Typeface: how the helper loads one weight when its field is still empty. */
private fun Method.isTypefaceGetter(): Boolean = !AccessFlags.STATIC.isSet(accessFlags) && returnType == TYPEFACE &&
    parameterTypes.map(Any::toString) == listOf(CONTEXT)

private fun Instruction.writes(type: String, fields: Set<String>): Boolean =
    opcode == Opcode.SPUT_OBJECT && (this as ReferenceInstruction).field().let { it.definingClass == type && it.name in fields }

private fun ReferenceInstruction.field(): FieldReference = reference as FieldReference

private fun Instruction.calls(reference: String): Boolean =
    ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == reference

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
