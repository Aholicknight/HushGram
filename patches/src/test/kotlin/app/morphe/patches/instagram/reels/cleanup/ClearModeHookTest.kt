/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.PURGE_MARKER
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClearModeHookTest {
    private val handler = "Lfixture/FullscreenHandler;"
    private val reason = "Lfixture/FullscreenReason;"
    private val item = "Lfixture/ClipsItem;"
    private val viewer = "Lfixture/Viewer;"
    private val pager = "Lfixture/Pager;"
    private val pagers = "Lfixture/Pagers;"
    private val actions = "Lfixture/ViewerActions;"
    private val listener = "Lfixture/PagerListener;"
    private val button = "Lfixture/ExitButton;"
    private val scope = "Lfixture/Scope;"
    private val component = "Lfixture/Component;"
    private val function1 = "Lkotlin/jvm/functions/Function1;"
    private val function2 = "Lfixture/Function2;"
    private val unit = "Lfixture/Unit;->INSTANCE:Lfixture/Unit;"
    private val purge = "Lfixture/Purge;->mark(Ljava/lang/String;)V"
    private val anyType = "Ljava/lang/Object;"
    private val stringType = "Ljava/lang/String;"
    private val hooks = listOf(CLEAR_MODE_ENTERED, CLEAR_MODE_LEFT, CLEAR_MODE_CURRENT, CLEAR_MODE_PAGED, CLEAR_MODE_BUILT)

    private fun marker(name: String, quarter: String = "q3") = "android_purge_26_${quarter}_$name"

    /** Every hook the patch writes is in the ReelClearMode the bundle ships, public and static, and so is every stub. */
    @Test
    fun theHooksAndStubsAreInTheExtension() {
        val declared = ExtensionDex.classDef(CLEAR_MODE).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        val stubs = listOf("$CLEAR_MODE_ENTER_STUB($anyType$anyType)V", "$CLEAR_MODE_ID_STUB($anyType)$stringType")
        for (signature in hooks.map { it.substringAfter("->") } + stubs) {
            assertTrue("$signature is not in the extension: $declared", signature in declared)
        }
    }

    /**
     * The handler's turn-on and turn-off each tell the extension after their action's call, where the
     * branch around a missing action still lands past the hook. The viewer's turn-on passes the reel
     * it read off the pager through the extension in front of its own test. The pager's listener and
     * the exit button's render each ask first thing. The stubs call the handler's turn-on and the
     * reel's getId.
     */
    @Test
    fun everyHookGoesInAndBothStubsAreFilled() {
        val context = PatchContexts.of(classes())

        context.keepClearMode()

        val enable = context.method(handler, "enable")
        // One local, then this and the reason.
        assertCall("the turn-on's hook", enable[5], CLEAR_MODE_ENTERED, listOf(1, 2))
        assertEquals("the action's call doesn't come just before", "$function1->invoke($anyType)$anyType", enable[4].referenceText())
        assertEquals(Opcode.RETURN_VOID, enable[6].opcode)
        assertEquals("a handler with no action runs the hook", 6, enable.target(enable.indexOfFirst { it.opcode == Opcode.IF_EQZ }))

        val dismiss = context.method(handler, "dismiss")
        // One local, then this, the reel and the reason.
        assertCall("the turn-off's hook", dismiss[6], CLEAR_MODE_LEFT, listOf(1, 2, 3))
        assertEquals("the action's call doesn't come just before", "$function2->invoke($anyType$anyType)$anyType", dismiss[5].referenceText())
        assertEquals(Opcode.RETURN_VOID, dismiss[7].opcode)
        assertEquals("a handler with no action runs the hook", 7, dismiss.target(dismiss.indexOfFirst { it.opcode == Opcode.IF_EQZ }))

        val enabled = context.method(actions, "enabled")
        val asked = enabled.indexOfFirst { it.referenceText() == CLEAR_MODE_CURRENT }
        assertEquals("the reel isn't read off the pager just before", "$pager->current()$item", enabled[asked - 2].referenceText())
        assertEquals(Opcode.MOVE_RESULT_OBJECT, enabled[asked - 1].opcode)
        assertCall("the reel handed over", enabled[asked], CLEAR_MODE_CURRENT, listOf(2))
        assertEquals(Opcode.MOVE_RESULT_OBJECT, enabled[asked + 1].opcode)
        assertEquals("the answer goes where the reel was", 2, (enabled[asked + 1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.CHECK_CAST, enabled[asked + 2].opcode)
        assertEquals("the answer is cast back to the reel", item, enabled[asked + 2].type())
        assertEquals(2, (enabled[asked + 2] as OneRegisterInstruction).registerA)
        assertEquals("Instagram's own test doesn't follow", Opcode.IF_EQZ, enabled[asked + 3].opcode)
        assertEquals(2, (enabled[asked + 3] as OneRegisterInstruction).registerA)
        assertEquals("no reel doesn't go straight to the return", enabled.size - 2, enabled.target(asked + 3))

        val paged = context.method(listener, "onPageSelected")
        assertCall("the page report's hook", paged[0], CLEAR_MODE_PAGED, emptyList())

        val render = context.method(button, "render")
        assertEquals(Opcode.MOVE_OBJECT_FROM16, render[0].opcode)
        // Two locals, then this and the scope.
        assertEquals(0, (render[0] as TwoRegisterInstruction).registerA)
        assertEquals("the button itself isn't what the reel is read from", 2, (render[0] as TwoRegisterInstruction).registerB)
        assertEquals("$button->reel:$item", render[1].referenceText())
        assertCall("the exit button's hook", render[2], CLEAR_MODE_BUILT, listOf(0))

        val enter = context.method(CLEAR_MODE, CLEAR_MODE_ENTER_STUB)
        assertEquals(
            listOf(Opcode.CHECK_CAST, Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID),
            enter.take(4).map { it.opcode },
        )
        assertEquals(handler, enter[0].type())
        assertEquals(reason, enter[1].type())
        assertEquals("$handler->enable($reason)V", enter[2].referenceText())
        val reelId = context.method(CLEAR_MODE, CLEAR_MODE_ID_STUB)
        assertEquals(
            listOf(Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
            reelId.take(4).map { it.opcode },
        )
        assertEquals(item, reelId[0].type())
        assertEquals("$item->getId()$stringType", reelId[1].referenceText())
    }

    /**
     * 451's x86 385713117 folds the pager lookup into the read: one static call handed what the pager
     * comes from answers the reel. The reel is still handed over right before Instagram's own test.
     */
    @Test
    fun aReadFoldedIntoAStaticCallIsHookedToo() {
        val context = PatchContexts.of(classes(Shape(pagerFolded = true)))

        context.keepClearMode()

        val enabled = context.method(actions, "enabled")
        val asked = enabled.indexOfFirst { it.referenceText() == CLEAR_MODE_CURRENT }
        assertEquals("the reel isn't read just before", "$pagers->current($pager)$item", enabled[asked - 2].referenceText())
        assertCall("the reel handed over", enabled[asked], CLEAR_MODE_CURRENT, listOf(2))
        assertEquals(Opcode.CHECK_CAST, enabled[asked + 2].opcode)
        assertEquals("Instagram's own test doesn't follow", Opcode.IF_EQZ, enabled[asked + 3].opcode)
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            Shape(enables = 0) to "expected one method marked $CLEAR_MODE_ENABLE, found 0",
            Shape(enables = 2) to "expected one method marked $CLEAR_MODE_ENABLE, found 2",
            Shape(enablePublic = false) to "isn't a public instance method of a public class taking the reason",
            Shape(reasonInApp = false) to "the reason $reason isn't in the app",
            Shape(reasonIsEnum = false) to "the reason $reason isn't a public enum",
            Shape(reasonNamesSwipe = false) to "doesn't name $CLEAR_MODE_SWIPED_AWAY",
            Shape(enableActions = 0) to "runs 0 actions, expected one",
            Shape(enableActions = 2) to "runs 2 actions, expected one",
            Shape(enableHandsReason = false) to "doesn't hand its action what it was called with",
            Shape(enableEndsAtCall = true) to "ends at its action's call",
            Shape(enableKeepsResult = true) to "keeps what its action answers",
            Shape(reasonOverwritten = true) to "writes over parameter 0",
            Shape(dismissElsewhere = true) to "$CLEAR_MODE_DISMISS isn't in $handler",
            Shape(dismissTakesReason = false) to "isn't an instance (reel, reason) method",
            Shape(itemInApp = false) to "the reel $item isn't in the app",
            Shape(itemPublic = false) to "the reel $item isn't public",
            Shape(itemHasId = false) to "has 0 public getId() answering a string, expected one",
            Shape(handlerOverwritten = true) to "writes over this",
            Shape(enabledInDismiss = true) to "two hooks would go in one method",
            Shape(pagerReads = 0) to "reads the reel the pager is on 0 times, expected once",
            Shape(pagerReads = 2) to "reads the reel the pager is on 2 times, expected once",
            Shape(reelTested = false) to "doesn't test the reel for null right after reading it off the pager",
            Shape(jumpToTest = true) to "jumps to its test of the reel",
            Shape(noReelCalls = true) to "does more than return when the pager has no reel",
            Shape(noReelReturns = false) to "doesn't return when the pager has no reel",
            Shape(pageTakesInts = false) to "isn't an instance (int, int) method returning nothing",
            Shape(buttonTakesScope = false) to "isn't a render taking one scope and answering a component",
            Shape(buttonReels = 0) to "holds the reel in 0 fields, expected one",
            Shape(buttonReels = 2) to "holds the reel in 2 fields, expected one",
            Shape(buttonLocals = false) to "has no local register for the hook",
            Shape(stubs = false) to "has no static $stringType $CLEAR_MODE_ID_STUB(",
        )
        for ((shape, expected) in cases) {
            val classes = classes(shape)
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.keepClearMode() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            assertTrue("$expected: ${failure.message}", failure.message!!.startsWith("Clean up Reels: Keep clear mode on: "))
            val written = context.written(classes)
            assertTrue("$expected: something was written to $written", written.isEmpty())
            val stubs = context.classDefBy(CLEAR_MODE).methods.filter { it.name == CLEAR_MODE_ENTER_STUB || it.name == CLEAR_MODE_ID_STUB }
            assertTrue("$expected: a stub was filled", stubs.none { stub -> stub.code().any { it.opcode == Opcode.CHECK_CAST } })
        }
    }

    /**
     * The handler's and the viewer's hooks go in by the index the find phase read. A method that
     * grew between the two would take its hook in the wrong place, so the patch stops instead.
     */
    @Test
    fun aMethodChangedAfterItsPlaceWasFoundStopsThePatch() {
        for (changed in listOf(handler to "enable", handler to "dismiss", actions to "enabled")) {
            val classes = classes()
            val context = PatchContexts.of(classes)
            val found = context.findClearMode()
            context.mutableClassDefBy(changed.first).methods.single { it.name == changed.second }.addInstructions(0, "nop")

            val failure = assertThrows(changed.second, PatchException::class.java) { context.applyClearMode(found) }

            assertTrue("${changed.second}: ${failure.message}", failure.message!!.contains("changed after its hook's place was found"))
            val written = context.written(classes)
            assertTrue("${changed.second}: something was written to $written", written.isEmpty())
        }
    }

    /**
     * In each declared build every hook goes where the stand-ins put it: after the action's call in
     * the handler's turn-on and turn-off, in front of the viewer's own test of the pager's reel, and
     * first thing in the pager listener's page report and the exit button's render.
     */
    @Test
    fun eachDeclaredBuildGetsEveryHook() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildGetsEveryHookToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val markers = CLEAR_MODE_MARKERS.toSet()
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            val holds = dex.stringSection.any { held ->
                held.startsWith("android_purge_") && PURGE_MARKER.find(held)?.groupValues?.get(1) in markers
            }
            if (!holds) return@forEach
            for (classDef in dex.classes) {
                if (classDef.methods.any { method -> method.markers().any { it in markers } }) holders += ImmutableClassDef.of(classDef)
            }
        }
        val methods = holders.flatMap { it.methods }
        val enable = methods.single { CLEAR_MODE_ENABLE in it.markers() }
        val dismiss = methods.single { CLEAR_MODE_DISMISS in it.markers() }
        val enabled = methods.single { CLEAR_MODE_ENABLED in it.markers() }
        val paged = methods.single { REEL_PAGE_SELECTED in it.markers() }
        val render = methods.single { CLEAR_MODE_BUTTON in it.markers() }
        val handlerType = enable.definingClass
        val reasonType = enable.parameterTypes.single().toString()
        val itemType = dismiss.parameterTypes.first().toString()
        val extra = FixtureDex.classes(bundle, setOf(reasonType, itemType)).values
        val context = PatchContexts.of((holders + extra + ExtensionDex.classDef(CLEAR_MODE)).distinctBy { it.type })

        context.keepClearMode()

        val name = label
        val after = { method: Method ->
            context.mutableClassDefBy(method.definingClass).methods.single {
                it.name == method.name && it.returnType == method.returnType &&
                    it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
            }.code()
        }

        // The turn-on: this and the reason, right after the action's call, and the branch around a
        // missing action lands past the hook.
        val newEnable = after(enable)
        assertEquals("$name: the turn-on grew", enable.code().size + 1, newEnable.size)
        val entered = newEnable.indexOfFirst { it.referenceText() == CLEAR_MODE_ENTERED }
        val enableThis = enable.implementation!!.registerCount - 2
        assertEquals("$name: the turn-on hands over this and the reason", listOf(enableThis, enableThis + 1), newEnable[entered].registers())
        assertEquals("$name: the turn-on's action comes just before", "invoke", newEnable[entered - 1].methodRef()?.name)
        assertEquals("$name: the reason is the one the action got", newEnable[entered - 1].registers().last(), enableThis + 1)
        assertEquals("$name: a missing action skips the turn-on's hook", entered + 1, newEnable.target(newEnable.indexOfFirst { it.opcode == Opcode.IF_EQZ }))

        // The turn-off: this, the reel and the reason, placed the same way.
        val newDismiss = after(dismiss)
        assertEquals("$name: the turn-off grew", dismiss.code().size + 1, newDismiss.size)
        val left = newDismiss.indexOfFirst { it.referenceText() == CLEAR_MODE_LEFT }
        val dismissThis = dismiss.implementation!!.registerCount - 3
        assertEquals(
            "$name: the turn-off hands over this, the reel and the reason",
            listOf(dismissThis, dismissThis + 1, dismissThis + 2),
            newDismiss[left].registers(),
        )
        assertEquals("$name: the turn-off's action comes just before", "invoke", newDismiss[left - 1].methodRef()?.name)
        assertEquals("$name: a missing action skips the turn-off's hook", left + 1, newDismiss.target(newDismiss.indexOfFirst { it.opcode == Opcode.IF_EQZ }))

        // The viewer's turn-on: the reel read off the pager goes through the extension and comes back
        // in the same register, cast to the reel, in front of Instagram's own test, and no reel only returns.
        val newEnabled = after(enabled)
        assertEquals("$name: the viewer's turn-on grew", enabled.code().size + 3, newEnabled.size)
        val asked = newEnabled.indexOfFirst { it.referenceText() == CLEAR_MODE_CURRENT }
        assertEquals("$name: the pager's reel is read just before", itemType, newEnabled[asked - 2].methodRef()?.returnType)
        assertEquals("$name: the read is kept", Opcode.MOVE_RESULT_OBJECT, newEnabled[asked - 1].opcode)
        val reel = (newEnabled[asked - 1] as OneRegisterInstruction).registerA
        assertEquals("$name: the reel handed over is the pager's", listOf(reel), newEnabled[asked].registers())
        assertEquals("$name: the answer is kept", Opcode.MOVE_RESULT_OBJECT, newEnabled[asked + 1].opcode)
        assertEquals("$name: the answer goes where the reel was", reel, (newEnabled[asked + 1] as OneRegisterInstruction).registerA)
        assertEquals("$name: the answer is cast back", Opcode.CHECK_CAST, newEnabled[asked + 2].opcode)
        assertEquals("$name: the answer is cast to the reel", itemType, newEnabled[asked + 2].type())
        assertEquals("$name: the cast is on the reel's register", reel, (newEnabled[asked + 2] as OneRegisterInstruction).registerA)
        assertEquals("$name: Instagram's own test follows", Opcode.IF_EQZ, newEnabled[asked + 3].opcode)
        assertEquals("$name: the test is of the answer", reel, (newEnabled[asked + 3] as OneRegisterInstruction).registerA)
        val withoutReel = newEnabled.drop(newEnabled.target(asked + 3)).takeWhile { it.opcode != Opcode.RETURN_OBJECT }
        assertTrue("$name: no reel does more than return", withoutReel.none { it.methodRef() != null || it is OffsetInstruction })

        val newPaged = after(paged)
        assertEquals("$name: the page report grew", paged.code().size + 1, newPaged.size)
        assertEquals("$name: the page report's first instruction", CLEAR_MODE_PAGED, newPaged[0].referenceText())

        // The exit button: its own reel, read off this into the first local, first thing.
        val newRender = after(render)
        assertEquals("$name: the exit button's render grew", render.code().size + 3, newRender.size)
        assertEquals("$name: the render's first instruction", Opcode.MOVE_OBJECT_FROM16, newRender[0].opcode)
        assertEquals("$name: this is what the reel is read from", render.implementation!!.registerCount - 2, (newRender[0] as TwoRegisterInstruction).registerB)
        val reelField = (newRender[1] as ReferenceInstruction).reference as FieldReference
        assertEquals("$name: the reel is the exit button's own", render.definingClass, reelField.definingClass)
        assertEquals("$name: the field holds a reel", itemType, reelField.type)
        assertEquals("$name: the exit button's hook", CLEAR_MODE_BUILT, newRender[2].referenceText())
        assertEquals("$name: the reel handed over is the one read", listOf(0), newRender[2].registers())

        val enter = context.method(CLEAR_MODE, CLEAR_MODE_ENTER_STUB)
        assertEquals("$name: the stub calls the handler's turn-on", "$handlerType->${enable.name}($reasonType)V", enter[2].referenceText())
        val reelId = context.method(CLEAR_MODE, CLEAR_MODE_ID_STUB)
        assertEquals("$name: the stub reads the reel's id", "$itemType->getId()$stringType", reelId[1].referenceText())
    }

    // ---- stand-ins shaped like Instagram 450's -------------------------------------------------

    /** What each stand-in looks like; the defaults are 450's shape, and each case changes one thing. */
    private data class Shape(
        val enables: Int = 1,
        val enablePublic: Boolean = true,
        val reasonInApp: Boolean = true,
        val reasonIsEnum: Boolean = true,
        val reasonNamesSwipe: Boolean = true,
        val enableActions: Int = 1,
        val enableHandsReason: Boolean = true,
        val enableEndsAtCall: Boolean = false,
        val enableKeepsResult: Boolean = false,
        val reasonOverwritten: Boolean = false,
        val dismissElsewhere: Boolean = false,
        val dismissTakesReason: Boolean = true,
        val itemInApp: Boolean = true,
        val itemPublic: Boolean = true,
        val itemHasId: Boolean = true,
        val handlerOverwritten: Boolean = false,
        val enabledInDismiss: Boolean = false,
        val pagerReads: Int = 1,
        val pagerFolded: Boolean = false,
        val reelTested: Boolean = true,
        val jumpToTest: Boolean = false,
        val noReelCalls: Boolean = false,
        val noReelReturns: Boolean = true,
        val pageTakesInts: Boolean = true,
        val buttonTakesScope: Boolean = true,
        val buttonReels: Int = 1,
        val buttonLocals: Boolean = true,
        val stubs: Boolean = true,
    )

    /**
     * The fullscreen view's action handler, whose turn-on and turn-off each run the action the viewer
     * set; the reason enum and the reel with its getId; the viewer's turn-on action, which reads the
     * reel the pager is on and does nothing without one; the pager's listener; and the exit button a
     * reel in clear mode draws, which holds its reel.
     */
    private fun classes(shape: Shape = Shape()): List<ClassDef> {
        val handed = if (shape.enableHandsReason) "p1" else "p0"
        val enableCalls = "invoke-interface { v0, $handed }, $function1->invoke($anyType)$anyType\n".repeat(shape.enableActions)
        val enableBody = if (shape.enableEndsAtCall) {
            """
                const-string v0, "${marker(CLEAR_MODE_ENABLE)}"
                invoke-static { v0 }, $purge
                iget-object v0, p0, $handler->onEnable:$function1
                if-nez v0, :call
                return-void
                :call
                invoke-interface { v0, p1 }, $function1->invoke($anyType)$anyType
            """
        } else {
            """
                const-string v0, "${marker(CLEAR_MODE_ENABLE)}"
                invoke-static { v0 }, $purge
                iget-object v0, p0, $handler->onEnable:$function1
                if-eqz v0, :done
                ${if (shape.reasonOverwritten) "const/4 p1, 0x0" else ""}
                $enableCalls
                ${if (shape.enableKeepsResult) "move-result-object v0" else ""}
                :done
                return-void
            """
        }
        val enables = (0 until shape.enables).map { copy ->
            method(
                handler, if (copy == 0) "enable" else "enableAgain", listOf(reason), "V", 3, enableBody,
                (if (shape.enablePublic) AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value) or AccessFlags.FINAL.value,
            )
        }

        val dismissOwner = if (shape.dismissElsewhere) "Lfixture/Other;" else handler
        val dismissParameters = listOf(item, if (shape.dismissTakesReason) reason else stringType)
        val dismiss = if (shape.enabledInDismiss) {
            method(dismissOwner, "dismiss", dismissParameters, "V", 5, """
                invoke-static { p1 }, Lfixture/Checks;->notNull($anyType)V
                const-string v0, "${marker(CLEAR_MODE_DISMISS)}"
                invoke-static { v0 }, $purge
                const-string v0, "${marker(CLEAR_MODE_ENABLED)}"
                iget-object v0, p0, $handler->pager:$pager
                invoke-virtual { v0 }, $pager->current()$item
                move-result-object v1
                if-eqz v1, :done
                iget-object v0, p0, $handler->onDismiss:$function2
                if-eqz v0, :done
                invoke-interface { v0, p2, p1 }, $function2->invoke($anyType$anyType)$anyType
                :done
                return-void
            """)
        } else {
            method(dismissOwner, "dismiss", dismissParameters, "V", 4, """
                invoke-static { p1 }, Lfixture/Checks;->notNull($anyType)V
                const-string v0, "${marker(CLEAR_MODE_DISMISS)}"
                invoke-static { v0 }, $purge
                iget-object v0, p0, $handler->onDismiss:$function2
                if-eqz v0, :done
                ${if (shape.handlerOverwritten) "const/4 p0, 0x0" else ""}
                invoke-interface { v0, p2, p1 }, $function2->invoke($anyType$anyType)$anyType
                :done
                return-void
            """)
        }
        val handlerClass = classDef(
            handler, enables + (if (shape.dismissElsewhere) emptyList() else listOf(dismiss)),
            fields = listOf(field(handler, "onEnable", function1), field(handler, "onDismiss", function2), field(handler, "pager", pager)),
        )

        val names = listOfNotNull("LONG_PRESS", "EXIT_BUTTON", if (shape.reasonNamesSwipe) CLEAR_MODE_SWIPED_AWAY else null, "OVERFLOW_MENU")
        val reasonClass = ImmutableClassDef(
            reason, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or AccessFlags.ENUM.value,
            if (shape.reasonIsEnum) "Ljava/lang/Enum;" else "Ljava/lang/Object;", null, null, null, emptyList(),
            listOf(
                method(
                    reason, "<clinit>", emptyList(), "V", 1,
                    names.joinToString("\n") { "const-string v0, \"$it\"" } + "\nreturn-void",
                    AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value,
                ),
            ),
        )
        val itemClass = ImmutableClassDef(
            item, (if (shape.itemPublic) AccessFlags.PUBLIC.value else 0) or AccessFlags.FINAL.value, "Ljava/lang/Object;",
            null, null, null, listOf(field(item, "id", stringType)),
            if (!shape.itemHasId) emptyList() else listOf(
                method(item, "getId", emptyList(), stringType, 2, """
                    iget-object v0, p0, $item->id:$stringType
                    return-object v0
                """),
            ),
        )

        val read = (
            if (shape.pagerFolded) "invoke-static { v0 }, $pagers->current($pager)$item\nmove-result-object v2\n"
            else "invoke-virtual { v0 }, $pager->current()$item\nmove-result-object v2\n"
            ).repeat(shape.pagerReads)
        val answer = "sget-object v0, $unit\nreturn-object v0"
        val enabledMarker = if (shape.enabledInDismiss) "ClipsViewerNavigatorProvider_handleLongPress" else CLEAR_MODE_ENABLED
        val enabled = method(actions, "enabled", listOf(anyType, anyType), anyType, 6, """
            check-cast p0, $viewer
            check-cast p1, $reason
            const-string v0, "${marker(enabledMarker)}"
            invoke-static { v0 }, $purge
            iget-object v0, p0, $viewer->pager:$pager
            $read
            ${if (shape.reelTested) "" else "const/4 v1, 0x0"}
            ${if (shape.jumpToTest) ":test" else ""}
            if-eqz v2, :none
            invoke-static { v2, p1 }, $viewer->show($item$reason)V
            ${if (shape.jumpToTest) "if-nez v2, :test" else ""}
            ${if (shape.noReelReturns) "" else answer}
            :none
            ${if (shape.noReelCalls) "invoke-static { }, Lfixture/Log;->none()V" else ""}
            ${if (shape.noReelReturns) answer else "const/4 v0, 0x0"}
        """, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)

        val paged = method(listener, "onPageSelected", if (shape.pageTakesInts) listOf("I", "I") else listOf("I"), "V", 5, """
            const/4 v0, 0x0
            const-string v1, "${marker(REEL_PAGE_SELECTED, "q2")}"
            invoke-static { v1 }, $purge
            return-void
        """)

        val local = if (shape.buttonLocals) "v0" else "p1"
        val renderParameters = if (shape.buttonTakesScope) listOf(scope) else listOf(scope, "I")
        val render = method(button, "render", renderParameters, component, if (shape.buttonLocals) 3 + renderParameters.size else 2, """
            const-string $local, "${marker(CLEAR_MODE_BUTTON)}"
            invoke-static { $local }, $purge
            const/4 $local, 0x0
            return-object $local
        """)
        val buttonFields = (0 until shape.buttonReels).map { field(button, if (it == 0) "reel" else "other", item) } +
            listOf(field(button, "state", "Lfixture/State;"), field(button, "exited", "Z"))

        val extension = if (shape.stubs) {
            ExtensionDex.classDef(CLEAR_MODE)
        } else {
            classDef(
                CLEAR_MODE,
                listOf(method(CLEAR_MODE, CLEAR_MODE_ENTER_STUB, listOf(anyType, anyType), "V", 2, "return-void", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)),
            )
        }

        return listOfNotNull(
            handlerClass,
            if (shape.dismissElsewhere) classDef("Lfixture/Other;", listOf(dismiss)) else null,
            if (shape.reasonInApp) reasonClass else null,
            if (shape.itemInApp) itemClass else null,
            classDef(actions, listOf(enabled)),
            classDef(listener, listOf(paged)),
            classDef(button, listOf(render), fields = buttonFields),
            extension,
        )
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        body: String,
        flags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
    ): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"))
        return ImmutableMethod.of(mutable)
    }

    private fun field(owner: String, name: String, type: String) = ImmutableField(
        owner, name, type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null,
    )

    private fun classDef(type: String, methods: List<Method>, fields: List<ImmutableField> = emptyList()): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, fields, methods)

    private fun BytecodePatchContext.method(owner: String, name: String): List<Instruction> =
        mutableClassDefBy(owner).methods.single { it.name == name }.code()

    /** The names of the methods of [classes] that call one of the hooks, as the context has them now. */
    private fun BytecodePatchContext.written(classes: List<ClassDef>): List<String> =
        classes.map { it.type }.distinct().flatMap { type -> classDefByOrNull(type)?.methods?.toList().orEmpty() }
            .filter { method -> method.code().any { it.referenceText() in hooks } }.map { it.name }

    private fun assertCall(what: String, instruction: Instruction, hook: String, registers: List<Int>) {
        assertEquals("$what: the hook", hook, instruction.referenceText())
        assertEquals("$what: the registers", registers, instruction.registers())
    }

    /** The index the branch at [index] lands on. */
    private fun List<Instruction>.target(index: Int): Int {
        var address = 0
        val addresses = map { instruction -> address.also { address += instruction.codeUnits } }
        val landing = addresses[index] + (this[index] as OffsetInstruction).codeOffset
        return addresses.indexOf(landing)
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.let {
        if (it is FieldReference) "${it.definingClass}->${it.name}:${it.type}" else it.toString()
    }

    private fun Instruction.type(): String? = ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type

    private fun Instruction.methodRef(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }
}
