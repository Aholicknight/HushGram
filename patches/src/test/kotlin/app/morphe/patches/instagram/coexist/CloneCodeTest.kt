/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.coexist

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The code half on every build of Instagram 450. Each build names its own package by the same
 * uses, Redex's names aside, and every use that means the running app moves while the rest stay.
 */
class CloneCodeTest {
    private val clone = "com.hush.second"

    /** The const-string loads of an authority each build has, and the loads of Instagram's package that stay. */
    private val authorityLoads = mapOf(
        "385611395" to 31, "385611400" to 37, "385611404" to 37, "385611431" to 37,
        "385611438" to 37, "385611439" to 34, "385611440" to 38,
    )
    private val keptLoads = mapOf(
        "385611395" to 88, "385611400" to 89, "385611404" to 87, "385611431" to 87,
        "385611438" to 87, "385611439" to 88, "385611440" to 87,
    )
    private val uses = mapOf(
        SelfUse.INLINED to 4, SelfUse.PACKAGE_MANAGER to 6, SelfUse.OWN_INTENT to 8, SelfUse.GOOGLE_PLAY to 10,
        SelfUse.NAME_CHECK to 6, SelfUse.CALLER_CHECK to 3, SelfUse.PROVIDER_OWNER to 4, SelfUse.RELEASE_AND_DEBUG to 4,
    )

    /** The uses in classes Redex leaves named, the same in every build. */
    private val namedUses = listOf(
        "Lcom/facebook/rti/mqtt/manager/MqttBackgroundServiceDelegate; NAME_CHECK",
        "Lcom/google/android/gms/internal/play_billing/zzan; GOOGLE_PLAY",
        "Lcom/google/android/gms/internal/play_billing/zzan; GOOGLE_PLAY",
        "Lcom/google/android/gms/internal/play_billing/zzan; GOOGLE_PLAY",
        "Lcom/google/android/gms/internal/play_billing/zzaw; GOOGLE_PLAY",
        "Lcom/instagram/common/resources/downloadable/impl/WaitingForStringsActivity; OWN_INTENT",
        "Lcom/instagram/inappbrowser/service/BrowserLiteCallbackService\$BrowserLiteCallbackImpl; CALLER_CHECK",
        "Lcom/instagram/process/instagram/Ig4aAppComponentFactory; RELEASE_AND_DEBUG",
        "Lcom/instagram/share/handleractivity/BaseShareHandlerActivity; CALLER_CHECK",
        "Linstagram/features/stories/fragment/ReelViewerFragment; CALLER_CHECK",
    )

    private fun ownPackage() = ExtensionDex.classDef(OWN_PACKAGE_CLASS)

    @Test fun everyBuildMovesItsOwnUsesAndKeepsTheRest() {
        val builds = CloneFixtures.builds()
        assertEquals(authorityLoads.keys, builds.keys)
        for ((build, file) in builds) {
            val authorities = readCloneManifest(CloneFixtures.manifest(file)).authorities
            val classes = CloneFixtures.classes(file, authorities)
            if (build == "385611438") {
                val withoutAnswer = PatchContexts.of(classes).planClone(clone, authorities)
                val refusal = assertThrows(PatchException::class.java) { withoutAnswer.requireComplete() }
                assertTrue(refusal.message, refusal.message!!.contains("OwnPackage.name()"))
            }
            val context = PatchContexts.of(classes + ownPackage())
            val plan = context.planClone(clone, authorities)
            plan.requireComplete()
            println("[$build] ${plan.summary()}")
            plan.sites.forEach { println("[$build]   $it") }

            assertEquals("[$build] uses", uses, SelfUse.entries.associateWith(plan::count))
            assertEquals("[$build] uses in named classes", namedUses, plan.sites.filter { it.use != null && !it.type.startsWith("LX/") }
                .map { "${it.type} ${it.use!!.name}" }.sorted())
            assertTrue("[$build] each use moves to the copy", plan.sites.filter { it.use != null }.all { it.to == clone })
            assertEquals("[$build] authority loads", authorityLoads[build], plan.rewrites.count { it.use == null })
            assertTrue("[$build] each authority load names the copy's", plan.rewrites.filter { it.use == null }.all { it.to!!.removePrefix("content://").startsWith("$clone.") })
            assertEquals("[$build] kept loads", keptLoads[build], plan.kept.size)
            assertTrue("[$build] the extension's answer", plan.ownPackage?.to == clone)

            context.applyClone(plan)

            val after = context.planClone(clone, authorities)
            assertEquals("[$build] no use is left", 0, after.sites.count { it.use != null })
            assertEquals("[$build] no load names an authority Instagram declares, pooled or not", emptyList<CloneSite>(), after.authorities)
            assertEquals("[$build] the kept loads are untouched", plan.kept.map { "$it" }, after.kept.map { "$it" })
            val answer = context.classDefBy(OWN_PACKAGE_CLASS).methods.single { it.name == "name" }.implementation!!.instructions
                .filter { it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO }
                .map { ((it as ReferenceInstruction).reference as StringReference).string }
            assertEquals("[$build] OwnPackage.name()", listOf(clone), answer)
        }
    }

    @Test fun codeWithoutInstagramsUsesIsRefused() {
        val empty = PatchContexts.of(listOf(ownPackage())).planClone(clone, listOf("com.instagram.fileprovider"))
        val refusal = assertThrows(PatchException::class.java) { empty.requireComplete() }
        assertTrue(refusal.message, refusal.message!!.contains("found no code that loads $INSTAGRAM"))
    }

    @Test fun theExtensionAnswersWithOneStringThePatchCanMove() {
        val method = ownPackage().methods.single { it.name == "name" }
        val strings = method.implementation!!.instructions.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
        assertEquals(listOf(INSTAGRAM), strings)
    }
}
