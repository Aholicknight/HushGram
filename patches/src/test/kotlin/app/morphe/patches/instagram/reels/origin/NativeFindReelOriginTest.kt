/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.origin

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.PURGE_MARKER
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.reels.origin.FindReelOriginHookTest.Companion.assertOpenHook
import app.morphe.patches.instagram.reels.origin.FindReelOriginHookTest.Companion.assertResumeHook
import app.morphe.patches.instagram.reels.origin.FindReelOriginHookTest.Companion.code
import app.morphe.patches.instagram.reels.origin.FindReelOriginHookTest.Companion.reference
import app.morphe.patches.instagram.reels.origin.FindReelOriginHookTest.Companion.snapshot
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.ClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Both hooks on each of 450's seven builds: the one profile opener and the one Reels tab onResume
 * they land in, nothing else changed, and the grid's binder still naming each tile by its reel, which
 * is what the extension's search goes by.
 */
class NativeFindReelOriginTest {
    @Test fun everyBuildHandsTheReelAndTheReelsTabToTheExtensionFirst() {
        val bundles = declared() + Fixtures.otherBuilds()
        val checked = mutableListOf<String>()
        for (bundle in bundles) {
            val name = bundle.absolutePath
            val classes = load(bundle) { types -> FixtureDex.classes(bundle, types) }
            val context = PatchContexts.of(classes.values + ExtensionDex.classDef(REEL_ORIGIN))
            val methods = classes.values.flatMap { it.methods }
            val open = methods.single { OPEN_PROFILE in it.markers() }
            val resume = methods.single { TAB_RESUME in it.markers() }
            val hooked = setOf(open.toString(), resume.toString())
            val media = classes.getValue(open.parameterTypes.single().toString()).fields.single { it.type == MEDIA }
            context.hookReelOrigin()

            assertOpenHook(context.mutableClassDefBy(open.definingClass).methods.single { it.toString() == open.toString() },
                open.code(), "${media.definingClass}->${media.name}:$MEDIA")
            assertResumeHook(context.mutableClassDefBy(resume.definingClass).methods.single { it.toString() == resume.toString() },
                resume.code())
            for (classDef in classes.values) {
                assertEquals("$name: ${classDef.type} changed", snapshot(classDef.methods.filter { it.toString() !in hooked }),
                    snapshot(context.mutableClassDefBy(classDef.type).methods.filter { it.toString() !in hooked }))
            }

            // Instagram names each tile of a profile's Reels grid by its reel as it binds it.
            val bind = methods.single { GRID_BIND in it.markers() }
            assertTrue("$name: the grid's binder sets each tile's transition name", bind.code().any { it.reference() == SET_TRANSITION_NAME })
            checked += name
        }
        for (code in BUILDS) assertTrue("no fixture of 450 build $code among $checked", checked.any { it.contains("-$code") })
    }

    @Test fun dexBackedInstructionReReadsResolveTheSameMethods() {
        val bundles = declared()
        assertTrue("no fixture of a declared build", bundles.isNotEmpty())
        for (bundle in bundles) {
            val classes = load(bundle) { types -> FixtureDex.classesAsRead(bundle, types) }
            val context = PatchContexts.of(classes.values + ExtensionDex.classDef(REEL_ORIGIN))
            val found = context.findReelOriginTargets()
            val open = found.openProfile.code()
            val resume = found.tabResume.code()
            context.hookReelOrigin()
            assertOpenHook(context.mutableClassDefBy(found.openProfile.definingClass).methods
                .single { it.toString() == found.openProfile.toString() }, open,
                "${found.media.definingClass}->${found.media.name}:$MEDIA")
            assertResumeHook(context.mutableClassDefBy(found.tabResume.definingClass).methods
                .single { it.toString() == found.tabResume.toString() }, resume)
        }
    }

    private companion object {
        val BUILDS = listOf(385611395, 385611400, 385611404, 385611431, 385611438, 385611439, 385611440)
        /** The binder of a tile in a profile's Reels grid. */
        const val GRID_BIND = "ClipsGridItemDefinition_bind"
        const val SET_TRANSITION_NAME = "Landroid/view/View;->setTransitionName(Ljava/lang/String;)V"
        val WANTED = setOf(OPEN_PROFILE, TAB_RESUME, TAB_CREATE, TILE_LOOKUP, GRID_BIND)

        /** The bundles of the builds the patches declare, as the other native tests read them. */
        fun declared(): List<File> {
            val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
            return versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        }

        /**
         * Every class holding one of the markers, what the opener is handed, Media, and the Reels tab's
         * superclasses up to Fragment.
         */
        fun load(bundle: File, read: (Set<String>) -> Map<String, ClassDef>): Map<String, ClassDef> {
            val supers = HashMap<String, String?>()
            val marked = mutableSetOf<String>()
            val destinations = mutableSetOf<String>()
            var tab: String? = null
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) supers.putIfAbsent(classDef.type, classDef.superclass)
                val holds = dex.stringSection.any { string ->
                    string.startsWith("android_purge_") && PURGE_MARKER.find(string)?.groupValues?.get(1) in WANTED
                }
                if (!holds) return@forEach
                for (classDef in dex.classes) {
                    for (method in classDef.methods) {
                        val markers = method.markers()
                        if (markers.none { it in WANTED }) continue
                        marked += classDef.type
                        if (OPEN_PROFILE in markers) destinations += method.parameterTypes.map(Any::toString)
                        if (TAB_RESUME in markers) tab = classDef.type
                    }
                }
            }
            val chain = mutableSetOf<String>()
            var next = supers[checkNotNull(tab) { "${bundle.name}: no Reels tab" }]
            while (next != null && next in supers && chain.add(next)) next = supers[next]
            assertTrue("${bundle.name}: the Reels tab is a Fragment", FRAGMENT in chain)
            val types = marked + destinations + MEDIA + chain
            val classes = read(types)
            assertEquals(bundle.name, types.size, classes.size)
            return classes
        }
    }
}
