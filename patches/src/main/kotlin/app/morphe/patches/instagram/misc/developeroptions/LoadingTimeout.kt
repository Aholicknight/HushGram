/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.developeroptions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.patchLog
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/** What the runnable showing a module download's loading dialog logs, held by that runnable alone. */
internal const val SPINNER_TASK = "InstagramDownloadableModuleTask:displaySpinner"

/** The tag the loading dialog goes into the fragment manager under, and is found again by. */
internal const val SPINNER_TAG = "progressDialog"

/** Told the activity right before the long press hands it to the opener. */
internal const val OPENING = "$EXTENSION_PACKAGE/misc/DeveloperOptions;->opening(Ljava/lang/Object;)V"

/** Asked first in both answers of the opener's callback; 1 drops an answer that came too late. */
internal const val LOADED = "$EXTENSION_PACKAGE/misc/DeveloperOptions;->loaded()I"

private const val FRAGMENT_ACTIVITY = "Landroidx/fragment/app/FragmentActivity;"
private const val FRAGMENT = "Landroidx/fragment/app/Fragment;"
private const val OPENER_STATIC = "(Landroid/content/Context;Landroidx/fragment/app/FragmentActivity;" +
    "Lcom/instagram/common/session/UserSession;Ljava/util/concurrent/Callable;)V"

/**
 * The time limit on the long press's wait (#158): the two answers of the callback the opener hands
 * the module load, as method references, and the dismiss stub, assembled and not yet put in.
 */
internal class LoadingTimeout(val answers: List<String>, val stubs: PreparedStubs)

/**
 * The time limit, or null with a warning in the patch log when this build moved a piece of it. The
 * long press goes in either way and waits as Instagram does without it. Nothing here changes the app.
 */
internal fun BytecodePatchContext.loadingTimeoutOrWarn(opener: OptionsOpener): LoadingTimeout? = try {
    findLoadingTimeout(opener)
} catch (moved: PatchException) {
    patchLog.warning("${moved.message}. The long press goes in without its time limit on the loading dialog.")
    null
}

/**
 * Finds what lets a long press give up on Instagram's developer options when their module never
 * loads (#158). The opener's static asks Instagram's downloadable module task for the options with
 * a callback it makes, and the task puts up a loading dialog that can't be cancelled, until the
 * load answers. Nothing limits the wait, so a download that never answered left the dialog up for
 * good.
 *
 * The callback is the one class the opener's static makes, with its two answers, success and
 * failure: its only no-argument void methods. The dialog is put up by the one static the task's
 * spinner runnable (the method holding [SPINNER_TASK]) calls with a fragment manager, and the one
 * other static of that class taking the manager and holding [SPINNER_TAG] takes it down, the way the
 * task does once the load answers. The activity's manager comes from FragmentActivity's one method
 * answering that type. The stub reaches all of it through public members only. Nothing changes here.
 */
internal fun BytecodePatchContext.findLoadingTimeout(opener: OptionsOpener): LoadingTimeout {
    fun clazz(type: String) = classDefByOrNull(type) ?: refuse("the build has no $type")
    val type = opener.open.substringBefore("->")
    val static = clazz(type).methods.singleOrNull {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.shape() == OPENER_STATIC
    } ?: refuse("$type has no single static asking for the options with a callback")
    val made = static.code().mapNotNull { it.typeMade() }.distinct()
    val callback = made.singleOrNull() ?: refuse("expected one callback made by $type's static, found ${made.size}")
    val answers = clazz(callback).methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.name != "<init>" && it.shape() == "()V" && it.implementation != null
    }
    if (answers.size != 2) refuse("expected the callback $callback to answer twice, found ${answers.size}")
    // The answer's first instruction asks loaded() into v0, below `this`.
    answers.forEach { if (it.implementation!!.registerCount < 2) refuse("$callback->${it.name} has no local for the answer") }

    val tasks = classesHolding(SPINNER_TASK).flatMap { clazz -> clazz.methods.filter { SPINNER_TASK in it.strings() } }
    val task = tasks.singleOrNull() ?: refuse("expected one module task holding $SPINNER_TASK, found ${tasks.size}")
    val shows = task.code().mapNotNull { instruction ->
        instruction.call(Opcode.INVOKE_STATIC)?.takeIf { it.returnType == "V" && it.parameterTypes.size == 1 && it.parameterTypes[0].startsWith("L") }
    }.distinctBy { it.toString() }
    val show = shows.singleOrNull() ?: refuse("expected one call putting up the loading dialog, found ${shows.size}")
    val manager = show.parameterTypes[0].toString()
    val shower = clazz(show.definingClass)
    val showing = shower.methods.singleOrNull { it.name == show.name && it.shape() == "($manager)V" }
    if (showing == null || SPINNER_TAG !in showing.strings()) refuse("${show.definingClass}->${show.name} doesn't tag the dialog $SPINNER_TAG")
    val dialogs = showing.code().mapNotNull { it.typeMade() }.distinct()
    val dialog = dialogs.singleOrNull() ?: refuse("expected one loading dialog made by ${show.name}, found ${dialogs.size}")
    val dismisses = shower.methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.name != show.name && it.shape() == "($manager)V" && SPINNER_TAG in it.strings()
    }
    val dismiss = dismisses.singleOrNull() ?: refuse("expected one method taking the loading dialog down, found ${dismisses.size}")
    val finds = dismiss.code().mapNotNull { instruction ->
        instruction.call(Opcode.INVOKE_VIRTUAL)?.takeIf {
            it.definingClass == manager && it.shape() == "(Ljava/lang/String;)$FRAGMENT"
        }
    }.distinctBy { it.toString() }
    val find = finds.singleOrNull() ?: refuse("the dialog's dismiss finds no fragment by its tag")
    val getters = clazz(FRAGMENT_ACTIVITY).methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.shape() == "()$manager"
    }
    val getter = getters.singleOrNull() ?: refuse("expected one fragment manager getter on the activity, found ${getters.size}")

    for (owner in listOf(shower.type, manager, dialog)) {
        if (!AccessFlags.PUBLIC.isSet(clazz(owner).accessFlags)) refuse("$owner isn't public")
    }
    val reached = listOf(
        dismiss,
        clazz(manager).methods.singleOrNull { it.name == find.name && it.shape() == "(Ljava/lang/String;)$FRAGMENT" },
        getter,
    )
    if (reached.any { it == null || !AccessFlags.PUBLIC.isSet(it.accessFlags) }) refuse("the dialog's dismiss isn't public")

    val stub = clazz(OVERRIDE_BRIDGE).methods.singleOrNull {
        it.name == "dismissSpinnerNative" && it.shape() == "(Ljava/lang/Object;)I" && AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("extension has no dismiss stub")
    val stubs = prepareStubs(listOf(stub), listOf(3 to """
        instance-of v0, p0, $FRAGMENT_ACTIVITY
        if-eqz v0, :none
        check-cast p0, $FRAGMENT_ACTIVITY
        invoke-virtual { p0 }, $FRAGMENT_ACTIVITY->${getter.name}()$manager
        move-result-object v0
        if-eqz v0, :none
        const-string v1, "$SPINNER_TAG"
        invoke-virtual { v0, v1 }, $find
        move-result-object v1
        instance-of v1, v1, $dialog
        if-eqz v1, :none
        invoke-static { v0 }, ${dismiss.definingClass}->${dismiss.name}($manager)V
        const/4 v0, 0x1
        return v0
        :none
        const/4 v0, 0x0
        return v0
    """.trimIndent()), ::refuse)
    return LoadingTimeout(answers.map { "$callback->${it.name}()V" }, stubs)
}

/**
 * Puts the dismiss stub in and asks [LOADED] first in both answers. On a 1 the answer returns
 * before Instagram's own code, since the long press already gave up on it and opened the editor.
 */
internal fun BytecodePatchContext.answerLoads(timeout: LoadingTimeout) {
    putStubs(timeout.stubs)
    for (answer in timeout.answers) {
        val name = answer.substringAfter("->").substringBefore("(")
        val method = mutableClassDefBy(answer.substringBefore("->")).methods.single { it.name == name && it.shape() == "()V" }
        method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $LOADED
                move-result v0
                if-eqz v0, :answer
                return-void
            """,
            ExternalLabel("answer", method.getInstruction(0)),
        )
    }
}

private fun Method.shape() = "(${parameterTypes.joinToString("")})$returnType"

private fun MethodReference.shape() = "(${parameterTypes.joinToString("")})$returnType"

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.strings(): Set<String> = code().mapNotNull { instruction ->
    if (instruction.opcode != Opcode.CONST_STRING && instruction.opcode != Opcode.CONST_STRING_JUMBO) null
    else ((instruction as ReferenceInstruction).reference as StringReference).string
}.toSet()

private fun Instruction.typeMade(): String? =
    if (opcode == Opcode.NEW_INSTANCE) ((this as ReferenceInstruction).reference as TypeReference).type else null

private fun Instruction.call(kind: Opcode): MethodReference? =
    if (opcode == kind) (this as ReferenceInstruction).reference as? MethodReference else null

private fun refuse(detail: String): Nothing = throw PatchException("Open developer options: $detail")
