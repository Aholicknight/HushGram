/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.coexist

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.shared.compat.AppCompatibilities

/*
 * The patch runs in four steps, in the patcher's order. The manifest half's execute reads the
 * manifest and refuses one it can't rename whole. The code half's execute checks the options and
 * sorts every load of the package name, refusing before anything changes. Every other patch then
 * runs on Instagram's own strings, since some find their code by its package name. Finalize blocks
 * run in reverse, so the code half's comes next: it sorts again, refuses if the sort changed, and
 * writes the copy's names. Only then does it ask the manifest half's finalize to rename the
 * manifest, so a refusal anywhere leaves an ordinary build that installs over Instagram as before.
 */

private var manifestFacts: CloneManifestFacts? = null
private var checkedSort: Map<String, Int>? = null

/** The package and name the manifest takes, set once the code has moved. */
private var renameRequest: Pair<String, String>? = null

private val cloneManifestPatch = resourcePatch {
    execute {
        manifestFacts = null
        checkedSort = null
        renameRequest = null
        manifestFacts = document("AndroidManifest.xml").use(::readCloneManifest)
    }

    finalize {
        val (clone, label) = renameRequest ?: return@finalize
        renameRequest = null
        document("AndroidManifest.xml").use { renameManifest(it, clone, label) }
    }
}

/**
 * Installs a second copy of the patched Instagram beside the first under its own package and home
 * screen name, with its own data and sign-in (#34, #61, #73, #121). Morphe's own Clone app patch
 * turns Instagram down, so this one knows Instagram's providers, permissions and its code's uses
 * of its own name.
 */
@Suppress("unused")
val installNextToInstagramPatch = bytecodePatch(
    name = "Install next to Instagram",
    description = "Installs this build as a second Instagram beside your normal one, with its own data and sign-in. " +
        "Pick its package name and app name in the patch's options. Works as soon as you patch it in, with no switch.",
    default = false,
) {
    category("Updates")
    dependsOn(instagramExtensionPatch, cloneManifestPatch)
    compatibleWith(*AppCompatibilities.instagram())

    val packageName = stringOption(
        key = "packageName",
        default = DEFAULT_PACKAGE,
        title = "Package name",
        description = "The second Instagram's package. Each part starts with a letter, with dots between the parts, " +
            "and it can't be Instagram's own.",
        required = true,
    ) { clonePackageProblem(it) == null }
    val appName = stringOption(
        key = "appName",
        default = DEFAULT_LABEL,
        title = "App name",
        description = "The name under the second Instagram's icon. It starts with a letter and holds up to 50 characters.",
        required = true,
    ) { cloneLabelProblem(it) == null }

    fun chosen(): Pair<String, String> {
        val clone = packageName.value
        val label = appName.value
        clonePackageProblem(clone)?.let(::refuse)
        cloneLabelProblem(label)?.let(::refuse)
        return clone!! to label!!
    }

    execute {
        val (clone, _) = chosen()
        val facts = manifestFacts ?: refuse("the manifest half didn't read AndroidManifest.xml")
        val plan = planClone(clone, facts.authorities)
        plan.requireComplete()
        checkedSort = plan.summary()
    }

    finalize {
        val (clone, label) = chosen()
        val facts = manifestFacts ?: refuse("the manifest half didn't read AndroidManifest.xml")
        val plan = planClone(clone, facts.authorities)
        plan.requireComplete()
        val checked = checkedSort
        if (plan.summary() != checked) refuse("another patch changed Instagram's uses of its name: $checked became ${plan.summary()}")
        applyClone(plan)
        renameRequest = clone to label
    }
}
