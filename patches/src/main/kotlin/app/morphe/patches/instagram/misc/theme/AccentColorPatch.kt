/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.analytics.loadsString
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.filterEveryReturn
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
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

private const val PATCH = "Accent color"

internal const val ACCENT_COLOR = "$EXTENSION_PACKAGE/misc/AccentColor;"
internal const val SWAP_COLOR = "$ACCENT_COLOR->color(I)I"
internal const val SWAP_LIST = "$ACCENT_COLOR->list(Landroid/content/res/ColorStateList;)Landroid/content/res/ColorStateList;"

/** What AndroidX ResourcesCompat logs when it can't inflate a color state list itself. */
internal const val INFLATE_FAILED = "Failed to inflate ColorStateList, leaving it to the framework"

private const val CONTEXT = "Landroid/content/Context;"
private const val COLOR_STATE_LIST = "Landroid/content/res/ColorStateList;"
private const val THEME = "Landroid/content/res/Resources\$Theme;"
private const val TYPED_VALUE = "Landroid/util/TypedValue;"

/**
 * Instagram's blue turns into a color picked in HushGram settings, after a restart. Included in
 * the default selection with its switch off.
 *
 * Instagram reads its colors from the theme. Most reads go through one of two small statics taking
 * a Context and a theme attribute, which resolve the attribute to a color resource and answer
 * Context.getColor of it, and color state lists (a button's colors for each of its states) go
 * through AndroidX ResourcesCompat's loader. The patch hands each color those answer, and each list
 * the loader answers, to the extension. With the switch on it swaps only Instagram's exact accent
 * blues for the matching shade of the color picked, keeping each one's alpha, so a list's pressed,
 * disabled and selected states keep their own look. Every other color goes through untouched, and
 * no resource or color table changes.
 */
@Suppress("unused")
val accentColorPatch = bytecodePatch(
    name = "Accent color",
    description = "Swaps Instagram's blue on Follow buttons, links and other highlights for purple, pink, red, orange, " +
        "green or teal. Restart Instagram to see a change. Starts off. Turn it on in HushGram settings > Layout.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("accentColor")
        swapAccentColors()
        enableStatus("accentColor")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")

/** ResourcesCompat's color state list loader, and the two statics that read a theme attribute's color. */
internal class AccentReads(val loader: MutableMethod, val colorReads: List<MutableMethod>)

/**
 * Finds every method the patch changes and checks the extension before anything changes.
 *
 * The loader is the one static `(Context, int)ColorStateList` loading [INFLATE_FAILED]. The color
 * reads are the statics taking a Context and an attribute that end by calling an attribute
 * resolver (a static of that shape calling Theme.resolveAttribute and reading TypedValue's
 * resourceId, with no getColor of its own) on their own two parameters, calling getColor on the
 * Context with its answer and returning that. A const and a static check may come first. 450 has
 * two of them in every build, and any other count means a build this wasn't written for.
 */
internal fun BytecodePatchContext.findAccentReads(): AccentReads {
    val loaders = classesLoadingString(INFLATE_FAILED).flatMap { classDef ->
        classDef.methods.filter { it.isStaticContextAnd(COLOR_STATE_LIST) && loadsString(it, INFLATE_FAILED) }
    }
    val loader = loaders.singleOrNull()
        ?: refuse("expected one static (Context, int) ColorStateList loading \"$INFLATE_FAILED\", found ${loaders.size}")
    if (loader.code().none { it.opcode == Opcode.RETURN_OBJECT }) refuse("${loader.where()} returns no object")

    val resolvers = classesCalling(THEME, "resolveAttribute").flatMap { classDef ->
        classDef.methods.filter { it.resolvesAttribute() }
    }
    if (resolvers.isEmpty()) refuse("found no static (Context, int) int resolving a theme attribute to its resource")
    val reads = resolvers.flatMap { resolver ->
        classesCalling(resolver.definingClass, resolver.name).flatMap { classDef ->
            classDef.methods.filter { it.readsColorThrough(resolver) }
        }
    }.distinctBy { it.where() }
    if (reads.size != 2) {
        refuse("expected two statics reading a theme attribute's color, found ${reads.size}: ${reads.joinToString { it.where() }}")
    }

    for (hook in listOf(SWAP_COLOR, SWAP_LIST)) {
        if (classDefByOrNull(ACCENT_COLOR)?.methods?.any {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook &&
                    AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
            } != true
        ) {
            refuse("the extension has no public static $hook")
        }
    }
    return AccentReads(mutable(loader), reads.map { mutable(it) })
}

/**
 * Every list the loader returns goes through AccentColor.list, and the color each read returns
 * through AccentColor.color, at the return's own label so a branch to the return passes through it
 * too. The range form takes any register, and move-result writes the answer back over the value.
 */
internal fun BytecodePatchContext.swapAccentColors() {
    val found = findAccentReads()
    found.loader.filterEveryReturn(PATCH, SWAP_LIST)
    for (read in found.colorReads) {
        val at = read.code().indexOfLast { it.opcode == Opcode.RETURN }
        val register = (read.code()[at] as OneRegisterInstruction).registerA
        read.addInstructionsAtControlFlowLabel(
            at,
            """
                invoke-static/range { v$register .. v$register }, $SWAP_COLOR
                move-result v$register
            """,
        )
    }
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }

private fun Method.isStaticContextAnd(returns: String): Boolean =
    AccessFlags.STATIC.isSet(accessFlags) && returnType == returns &&
        parameterTypes.map(Any::toString) == listOf(CONTEXT, "I") && implementation != null

/** A static (Context, int) int calling Theme.resolveAttribute, reading TypedValue's resourceId and no color. */
private fun Method.resolvesAttribute(): Boolean {
    if (!isStaticContextAnd("I")) return false
    val code = code()
    return code.any { it.calls(THEME, "resolveAttribute") } && code.none { it.calls(CONTEXT, "getColor") } &&
        code.any { instruction ->
            instruction.opcode == Opcode.IGET &&
                ((instruction as ReferenceInstruction).reference as FieldReference).let {
                    it.definingClass == TYPED_VALUE && it.name == "resourceId"
                }
        }
}

/**
 * Whether this is a static (Context, int) int ending `invoke-static {p0, p1}, resolver`,
 * `move-result vA`, `invoke-virtual {p0, vA}, Context->getColor(I)I`, `move-result vB`, `return
 * vB`, after at most a const and a static call that write neither parameter.
 */
private fun Method.readsColorThrough(resolver: Method): Boolean {
    if (!isStaticContextAnd("I")) return false
    val code = code()
    if (code.size !in 5..7) return false
    val context = parameterRegisterNumber(0)
    val attribute = parameterRegisterNumber(1)
    val tail = code.takeLast(5)
    val prefix = code.dropLast(5)
    if (prefix.any { it.opcode != Opcode.CONST_4 && it.opcode != Opcode.INVOKE_STATIC }) return false
    if (prefix.any { it.opcode == Opcode.CONST_4 && (it as OneRegisterInstruction).registerA in setOf(context, attribute) }) return false

    val resolve = tail[0]
    val called = (resolve as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    if (resolve.opcode != Opcode.INVOKE_STATIC || called.definingClass != resolver.definingClass || called.name != resolver.name ||
        called.parameterTypes.map(Any::toString) != listOf(CONTEXT, "I") || called.returnType != "I"
    ) {
        return false
    }
    val passed = resolve as FiveRegisterInstruction
    if (passed.registerCount != 2 || passed.registerC != context || passed.registerD != attribute) return false
    if (tail[1].opcode != Opcode.MOVE_RESULT) return false
    val resource = (tail[1] as OneRegisterInstruction).registerA

    val getColor = tail[2]
    if (getColor.opcode != Opcode.INVOKE_VIRTUAL || !getColor.calls(CONTEXT, "getColor")) return false
    val asked = getColor as FiveRegisterInstruction
    if (asked.registerCount != 2 || asked.registerC != context || asked.registerD != resource) return false
    if (tail[3].opcode != Opcode.MOVE_RESULT || tail[4].opcode != Opcode.RETURN) return false
    return (tail[3] as OneRegisterInstruction).registerA == (tail[4] as OneRegisterInstruction).registerA
}

private fun Instruction.calls(definingClass: String, name: String): Boolean =
    ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let {
        it.definingClass == definingClass && it.name == name
    } == true

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.where(): String = "$definingClass->$name"
