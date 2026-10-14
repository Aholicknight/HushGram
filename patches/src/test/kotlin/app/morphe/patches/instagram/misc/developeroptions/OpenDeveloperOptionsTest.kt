/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.developeroptions

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.PatchLogCapture
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
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

class OpenDeveloperOptionsTest {
    private val options = "Lfixture/Options;"
    private val press = "Lfixture/HomePress;"
    private val context = "Landroid/content/Context;"
    private val activity = "Landroidx/fragment/app/FragmentActivity;"
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val mainActivity = "Lcom/instagram/mainactivity/InstagramMainActivity;"
    private val pool = "Lfixture/Strings;"

    /**
     * The long press asks open() first, and on a yes tells opening() its main activity, hands that
     * activity, twice, and its session to the opener's instance and returns true. On a no it runs
     * from its own first instruction.
     */
    @Test
    fun theLongPressAsksFirstAndOpensTheOptions() {
        val patch = PatchContexts.of(classes())

        patch.openOnLongPress(patch.findOptionsOpener())

        val method = patch.longPress()
        val code = method.code()
        val self = method.implementation!!.registerCount - 2
        assertEquals("the question", OPEN_DEVELOPER_OPTIONS, code[0].referenceText())
        assertEquals(Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals(Opcode.IF_EQZ, code[2].opcode)
        assertEquals("this", self, (code[3] as TwoRegisterInstruction).registerB)
        assertEquals("the activity", "$press->activity:$mainActivity", code[4].referenceText())
        assertEquals("the session", "$press->session:$session", code[5].referenceText())
        val opening = code[6] as Instruction35c
        assertEquals("the time limit starts", OPENING, opening.referenceText())
        assertEquals("on the activity", 1 to 1, opening.registerCount to opening.registerC)
        assertEquals("the opener's instance", "$options->instance:$options", code[7].referenceText())
        val call = code[8] as Instruction35c
        assertEquals("the opener", "$options->open($context$activity$session)V", call.referenceText())
        assertEquals("the call's registers", listOf(3, 1, 1, 2), listOf(call.registerC, call.registerD, call.registerE, call.registerF))
        assertEquals(Opcode.CONST_4, code[9].opcode)
        assertEquals(Opcode.RETURN, code[10].opcode)
        assertEquals(0, (code[10] as OneRegisterInstruction).registerA)
        assertEquals("the press as it was", "$press->flag:I", code[11].referenceText())
    }

    /** An opener asking a string pool for its error key, as 450's 385611395 and 385611400 do, is found the same (#77). */
    @Test
    fun anOpenerAskingAPoolForItsKeyIsFound() {
        val patch = PatchContexts.of(classes(pooledError = true))

        val opener = patch.findOptionsOpener()

        assertEquals("$options->instance:$options", opener.instance)
        assertEquals("$options->open($context$activity$session)V", opener.open)
    }

    /** A build missing a piece, or with two of one, says which and leaves the press alone. */
    @Test
    fun whatItCantPlaceItLeavesAlone() {
        val cases = mapOf(
            "no opener" to classes(error = false),
            "pooled key without its pool" to classes(pooledError = true).filter { it.type != pool },
            "no instance" to classes(instance = false),
            "no press" to classes(pressStrings = listOf("click")),
            "two presses" to classes() + press("Lfixture/OtherPress;"),
            "no session" to classes(sessionField = false),
            "too few locals" to classes(registers = 4),
        )
        for ((case, classes) in cases) {
            val patch = PatchContexts.of(classes)

            val failure = runCatching { patch.openOnLongPress(patch.findOptionsOpener()) }.exceptionOrNull()

            assertTrue(case, failure?.message?.startsWith("Open developer options: ") == true)
            assertTrue(case, patch.longPress().code().none { it.referenceText() == OPEN_DEVELOPER_OPTIONS })
        }
    }

    /**
     * With no override reader and no MetaConfig list to be found, the long press and the MetaConfig
     * and Whitehat entries go in all the same. No reader, writer or flag name stub is filled, their
     * statuses stay off, and the patch log says what was left out.
     */
    @Test
    fun aReaderThatMovedLeavesTheLongPressAndTheEntriesIn() {
        val patch = PatchContexts.of(classes() + extension())
        val stock = patch.bridge()

        val warnings = PatchLogCapture.warnings { patch.openDeveloperOptions(standInEditor, whitehat) }

        assertEquals(warnings.toString(), 3, warnings.size)
        assertTrue(warnings[0], warnings[0].startsWith("Open developer options: expected one signed-in override diagnostics, found 0. "))
        assertTrue(warnings[0], warnings[0].endsWith(" without Export, Validate and Import."))
        assertTrue(warnings[1], warnings[1].startsWith("Open developer options: expected one MetaConfig schema getter, found 0. "))
        assertTrue(warnings[1], warnings[1].endsWith(" without Import flag names."))
        assertEquals(warnings[2], "Open developer options: expected one callback made by $options's static, found 0. " +
            "The long press goes in without its time limit on the loading dialog.", warnings[2])
        assertEquals("asks first", OPEN_DEVELOPER_OPTIONS, patch.longPress().code()[0].referenceText())
        val filled = patch.bridge().filter { (stub, code) -> stock[stub] != code }.keys
        assertEquals(setOf("openOverridesNative(Ljava/lang/Object;)I", "openWhitehatNative(Ljava/lang/Object;)I"), filled)
        assertEquals(1, patch.answer("developerOptions"))
        assertEquals(0, patch.answer(OVERRIDE_EXCHANGE_STATUS))
        assertEquals(0, patch.answer(OVERRIDE_IMPORT_STATUS))
        assertEquals(0, patch.answer(FLAG_NAMES_STATUS))
    }

    /** An opener that moved still stops the patch before a stub, the press or a status changes. */
    @Test
    fun anOpenerThatMovedStillStopsThePatchBeforeAnythingChanges() {
        val patch = PatchContexts.of(classes(error = false) + extension())
        val stock = patch.bridge()

        PatchLogCapture.warnings { assertThrows(PatchException::class.java) { patch.openDeveloperOptions(standInEditor, whitehat) } }

        assertEquals(stock, patch.bridge())
        assertTrue(patch.longPress().code().none { it.referenceText() == OPEN_DEVELOPER_OPTIONS })
        assertEquals(0, patch.answer("developerOptions"))
        assertEquals(0, patch.answer(OVERRIDE_EXCHANGE_STATUS))
        assertEquals(0, patch.answer(OVERRIDE_IMPORT_STATUS))
        assertEquals(0, patch.answer(FLAG_NAMES_STATUS))
    }

    /** In each declared build, the one long press with both strings asks first and opens the one opener. */
    @Test
    fun eachDeclaredBuildOpensTheOptionsOnALongPress() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val classes = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    if (OPTIONS_ERROR !in dex.stringSection && LONG_PRESS_STRINGS.any { it !in dex.stringSection }) return@forEach
                    for (classDef in dex.classes) {
                        val wanted = classDef.methods.any { method ->
                            val strings = method.strings()
                            OPTIONS_ERROR in strings || (method.name == "onLongClick" && strings.containsAll(LONG_PRESS_STRINGS))
                        }
                        if (wanted) classes += ImmutableClassDef.of(classDef)
                    }
                }
                val patch = PatchContexts.of(classes)

                val opener = patch.findOptionsOpener()
                patch.openOnLongPress(opener)

                val handler = classes.single { classDef ->
                    classDef.methods.any { it.name == "onLongClick" && it.strings().containsAll(LONG_PRESS_STRINGS) }
                }
                val code = patch.classDefBy(handler.type).methods.single { it.name == "onLongClick" }.code()
                assertEquals("${bundle.name}: asks first", OPEN_DEVELOPER_OPTIONS, code[0].referenceText())
                assertTrue("${bundle.name}: the main activity", code[4].referenceText()!!.endsWith(":$mainActivity"))
                assertTrue("${bundle.name}: the session", code[5].referenceText()!!.endsWith(":$session"))
                assertEquals("${bundle.name}: the time limit starts", OPENING, code[6].referenceText())
                assertEquals("${bundle.name}: the opener", opener.open, code[8].referenceText())
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * The same in the other builds of each declared version (#77, #95). 451's x86 build 385713117
     * asks a string pool for the error key instead of holding it, and the opener is found through it.
     */
    @Test
    fun everyOtherBuildOpensTheOptionsOnALongPress() {
        val pooled = mutableSetOf<String>()
        for (bundle in Fixtures.otherBuilds()) {
            val label = bundle.parentFile.name
            val classes = slice(bundle)
            val patch = PatchContexts.of(classes)

            val opener = patch.findOptionsOpener()
            patch.openOnLongPress(opener)

            val handler = classes.single { classDef ->
                classDef.methods.any { it.name == "onLongClick" && it.strings().containsAll(LONG_PRESS_STRINGS) }
            }
            val code = patch.classDefBy(handler.type).methods.single { it.name == "onLongClick" }.code()
            assertEquals("$label: asks first", OPEN_DEVELOPER_OPTIONS, code[0].referenceText())
            assertTrue("$label: the main activity", code[4].referenceText()!!.endsWith(":$mainActivity"))
            assertTrue("$label: the session", code[5].referenceText()!!.endsWith(":$session"))
            assertEquals("$label: the time limit starts", OPENING, code[6].referenceText())
            assertEquals("$label: the opener", opener.open, code[8].referenceText())
            val type = opener.open.substringBefore("->")
            if (classes.single { it.type == type }.methods.none { OPTIONS_ERROR in it.strings() }) pooled += label.substringAfterLast('-')
        }
        assertTrue("the builds asking a pool for $OPTIONS_ERROR: $pooled", pooled.containsAll(listOf("385713117")))
    }

    /**
     * The time limit (#158) asks loaded() first in both answers of the callback the opener makes,
     * and on a 1 returns before the answer's own code. The dismiss stub takes the activity's
     * manager, finds the dialog by its tag and, when it's Instagram's loading dialog, takes it down
     * the way the module task does and answers 1.
     */
    @Test
    fun theTimeLimitAsksFirstInBothAnswersAndFillsTheDismiss() {
        val patch = PatchContexts.of(timeoutClasses() + extension())

        patch.answerLoads(patch.findLoadingTimeout(patch.findOptionsOpener()))

        for (answer in listOf("onSuccess", "onFailure")) {
            val code = patch.classDefBy(callback).methods.single { it.name == answer }.code()
            assertEquals(answer, LOADED, code[0].referenceText())
            assertEquals(answer, Opcode.MOVE_RESULT, code[1].opcode)
            assertEquals(answer, Opcode.IF_EQZ, code[2].opcode)
            assertEquals(answer, Opcode.RETURN_VOID, code[3].opcode)
            assertEquals("$answer as it was after", 5, code.size)
        }
        val stub = patch.classDefBy(OVERRIDE_BRIDGE).methods.single { it.name == "dismissSpinnerNative" }
        assertEquals(3, stub.implementation!!.registerCount)
        assertEquals(
            listOf("$activity->manager()$manager", SPINNER_TAG, "$manager->find(Ljava/lang/String;)$fragment", spinner, "$spinners->hide($manager)V"),
            stub.code().mapNotNull { it.referenceText() }.filter { it != activity },
        )
    }

    /** A build missing a piece of the time limit, or with two of one, says which, before anything changes. */
    @Test
    fun whatTheTimeLimitCantPlaceItRefuses() {
        val cases = mapOf(
            "no callback" to timeoutClasses(callbacks = emptyList()),
            "two callbacks" to timeoutClasses(callbacks = listOf(callback, "Lfixture/OtherCallback;")),
            "one answer" to timeoutClasses(answers = 1),
            "three answers" to timeoutClasses(answers = 3),
            "no local in an answer" to timeoutClasses(answerRegisters = 1),
            "no module task" to timeoutClasses(taskString = false),
            "two dialog calls" to timeoutClasses(shows = 2),
            "an untagged dialog" to timeoutClasses(tagged = false),
            "no dismiss" to timeoutClasses(dismisses = 0),
            "two dismisses" to timeoutClasses(dismisses = 2),
            "a dismiss finding nothing" to timeoutClasses(finds = false),
            "no manager getter" to timeoutClasses(getters = 0),
            "two manager getters" to timeoutClasses(getters = 2),
            "a dialog that isn't public" to timeoutClasses(dialogPublic = false),
        )
        for ((case, classes) in cases) {
            val patch = PatchContexts.of(classes + extension())

            val failure = runCatching { patch.findLoadingTimeout(patch.findOptionsOpener()) }.exceptionOrNull()

            assertTrue("$case: $failure", failure is PatchException && failure.message!!.startsWith("Open developer options: "))
        }
        val unbridged = PatchContexts.of(timeoutClasses())
        val failure = runCatching { unbridged.findLoadingTimeout(unbridged.findOptionsOpener()) }.exceptionOrNull()
        assertEquals("Open developer options: the build has no $OVERRIDE_BRIDGE", failure?.message)
    }

    /** With every piece found, the time limit goes in with the long press and the entries, warning about nothing of its own. */
    @Test
    fun theTimeLimitGoesInWithTheLongPress() {
        val patch = PatchContexts.of(timeoutClasses() + extension())
        val stock = patch.bridge()

        val warnings = PatchLogCapture.warnings { patch.openDeveloperOptions(standInEditor, whitehat) }

        assertEquals(warnings.toString(), 2, warnings.size)
        assertEquals("the time limit starts", OPENING, patch.longPress().code()[6].referenceText())
        val filled = patch.bridge().filter { (stub, code) -> stock[stub] != code }.keys
        assertEquals(setOf("openOverridesNative(Ljava/lang/Object;)I", "openWhitehatNative(Ljava/lang/Object;)I",
            "dismissSpinnerNative(Ljava/lang/Object;)I"), filled)
        val answers = patch.classDefBy(callback).methods.filter { it.name != "<init>" }
        assertEquals(2, answers.size)
        answers.forEach { assertEquals(it.name, LOADED, it.code()[0].referenceText()) }
    }

    /**
     * In the declared 451 build and every other one, the time limit finds the opener's callback with
     * its success (the answer calling the Callable that makes the options screen) and its failure,
     * and Instagram's loading dialog with its dismiss, and asks first in both answers.
     */
    @Test
    fun everyBuildPutsTheTimeLimitIn() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        for (bundle in bundles) {
            val label = if (bundle.extension == "apks") bundle.name else bundle.parentFile.name
            val patch = PatchContexts.of(timeoutSlice(bundle) + extension())

            val timeout = patch.findLoadingTimeout(patch.findOptionsOpener())
            patch.answerLoads(timeout)

            val answers = timeout.answers.map { answer ->
                patch.classDefBy(answer.substringBefore("->")).methods.single { "${it.definingClass}->${it.name}()V" == answer }
            }
            assertEquals(label, 2, answers.size)
            answers.forEach { assertEquals("$label: ${it.name} asks first", LOADED, it.code()[0].referenceText()) }
            assertTrue("$label: the success", answers.any { answer ->
                answer.code().any { it.referenceText() == "Ljava/util/concurrent/Callable;->call()Ljava/lang/Object;" }
            })
            val stub = patch.classDefBy(OVERRIDE_BRIDGE).methods.single { it.name == "dismissSpinnerNative" }.code().mapNotNull { it.referenceText() }
            assertTrue("$label: $stub", SPINNER_TAG in stub && stub.any { it.endsWith("(Ljava/lang/String;)$fragment") })
        }
        assertTrue("the 451 builds: $bundles", bundles.size >= 8)
    }

    /**
     * The classes the time limit's finders read in [bundle]: the opener's static and its callback
     * (the class whose constructor takes what the static does), the module task's spinner runnable,
     * the class putting the dialog up and taking it down, FragmentActivity, the manager and the
     * dialog, and the string pools the opener asks.
     */
    private fun timeoutSlice(bundle: File): List<ClassDef> {
        val shape = listOf(context, activity, session, "Ljava/util/concurrent/Callable;")
        val classes = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                val wanted = classDef.type == activity || classDef.methods.any { method ->
                    val parameters = method.parameterTypes.map(Any::toString)
                    val strings = method.strings()
                    parameters == shape || OPTIONS_ERROR in strings || SPINNER_TASK in strings ||
                        (AccessFlags.STATIC.isSet(method.accessFlags) && parameters.size == 1 && SPINNER_TAG in strings)
                }
                if (wanted) classes += ImmutableClassDef.of(classDef)
            }
        }
        val show = classes.flatMap { it.methods }.single { SPINNER_TASK in it.strings() }.code().mapNotNull { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
                instruction.opcode == Opcode.INVOKE_STATIC && it.returnType == "V" && it.parameterTypes.size == 1
            }
        }.single()
        val dialog = classes.single { it.type == show.definingClass }.methods.single { it.name == show.name }.code()
            .single { it.opcode == Opcode.NEW_INSTANCE }.referenceText()!!
        val taken = classes.mapTo(HashSet()) { it.type }
        val more = FixtureDex.classes(bundle, setOf(show.parameterTypes[0].toString(), dialog)).values.filter { it.type !in taken }
        return FixtureDex.withStringPools(bundle, classes + more)
    }

    private val callback = "Lfixture/Callback;"
    private val spinners = "Lfixture/Spinners;"
    private val manager = "Lfixture/Manager;"
    private val spinner = "Lfixture/Spinner;"
    private val fragment = "Landroidx/fragment/app/Fragment;"

    /**
     * The opener making [callbacks], the callback answering [answers] times, the module task's
     * spinner runnable calling [shows] statics with a manager, the class putting the dialog up and
     * taking it down [dismisses] times, the manager finding fragments by tag, and FragmentActivity
     * with [getters] getters for the manager.
     */
    private fun timeoutClasses(
        callbacks: List<String> = listOf(callback),
        answers: Int = 2,
        answerRegisters: Int = 2,
        taskString: Boolean = true,
        shows: Int = 1,
        tagged: Boolean = true,
        dismisses: Int = 1,
        finds: Boolean = true,
        getters: Int = 1,
        dialogPublic: Boolean = true,
    ): List<ClassDef> {
        val public = AccessFlags.PUBLIC.value
        val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val answerMethods = listOf("onSuccess", "onFailure", "onCancel").take(answers).map {
            method(callback, it, emptyList(), "V", answerRegisters, public, "return-void")
        }
        val callbackClass = ImmutableClassDef(callback, public, "Ljava/lang/Object;", null, null, null, null,
            listOf(method(callback, "<init>", emptyList(), "V", 1, public or AccessFlags.CONSTRUCTOR.value, "return-void")) + answerMethods)
        val showNames = listOf("show", "showAgain").take(shows)
        val task = method("Lfixture/ModuleTask;", "run", emptyList(), "V", 3, public, """
            ${showNames.joinToString("\n") { "invoke-static { v0 }, $spinners->$it($manager)V" }}
            invoke-static { v1 }, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
            ${if (taskString) "const-string v1, \"$SPINNER_TASK\"" else ""}
            return-void
        """)
        val spinnerMethods = showNames.map { name ->
            method(spinners, name, listOf(manager), "V", 2, static, """
                new-instance v0, $spinner
                ${if (tagged) "const-string v0, \"$SPINNER_TAG\"" else ""}
                return-void
            """)
        } + listOf("hide", "hideAgain").take(dismisses).map { name ->
            method(spinners, name, listOf(manager), "V", 3, static, """
                const-string v0, "$SPINNER_TAG"
                ${if (finds) "invoke-virtual { p0, v0 }, $manager->find(Ljava/lang/String;)$fragment" else ""}
                return-void
            """)
        }
        val managerClass = ImmutableClassDef(manager, public, "Ljava/lang/Object;", null, null, null, null,
            listOf(method(manager, "find", listOf("Ljava/lang/String;"), fragment, 3, public, "const/4 v0, 0x0\nreturn-object v0")))
        val activityClass = ImmutableClassDef(activity, public, "Ljava/lang/Object;", null, null, null, null,
            listOf("manager", "managerAgain").take(getters).map { method(activity, it, emptyList(), manager, 2, public, "const/4 v0, 0x0\nreturn-object v0") })
        return classes(callbacks = callbacks) + listOf(
            callbackClass,
            ImmutableClassDef("Lfixture/ModuleTask;", public, "Ljava/lang/Object;", null, null, null, null, listOf(task)),
            ImmutableClassDef(spinners, public, "Ljava/lang/Object;", null, null, null, null, spinnerMethods),
            managerClass,
            ImmutableClassDef(spinner, if (dialogPublic) public else 0, "Ljava/lang/Object;", null, null, null, null, null),
            activityClass,
        )
    }

    /**
     * The classes the opener and long press finders read in [bundle]: every class with a static
     * shaped like the opener's or holding the error key, the long press handler, and the string
     * pools they ask.
     */
    private fun slice(bundle: File): List<ClassDef> {
        val shape = listOf(context, activity, session, "Ljava/util/concurrent/Callable;")
        val classes = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                val wanted = classDef.methods.any { method ->
                    (AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.map(Any::toString) == shape) ||
                        (method.name == "onLongClick" && method.strings().containsAll(LONG_PRESS_STRINGS)) ||
                        OPTIONS_ERROR in method.strings()
                }
                if (wanted) classes += ImmutableClassDef.of(classDef)
            }
        }
        return FixtureDex.withStringPools(bundle, classes)
    }

    private fun BytecodePatchContext.longPress(): Method = classDefBy(press).methods.single { it.name == "onLongClick" }

    /** An editor and a Whitehat screen as the finders hand them over; only their smali has to assemble. */
    private val standInEditor = OverrideEditor(
        "Lcom/instagram/base/activity/IgFragmentActivity;->session()Lfixture/BaseSession;",
        "Lfixture/Editor;->factory(Lcom/instagram/base/activity/IgFragmentActivity;Ljava/lang/Object;)Ljava/lang/Object;",
        "Lfixture/EditorFragment;",
        "Lfixture/Editor;->present(Landroidx/fragment/app/Fragment;Ljava/lang/Object;)V",
    )
    private val whitehat = "Lfixture/WhitehatScreen;"

    /** The bridge with every stub, and the status class, as the bundle ships them. */
    private fun extension() = listOf(ExtensionDex.classDef(OVERRIDE_BRIDGE), ExtensionDex.classDef(SETTINGS_STATUS))

    /** Each bridge stub's code, as opcodes and what they reference. */
    private fun BytecodePatchContext.bridge(): Map<String, List<String>> = classDefBy(OVERRIDE_BRIDGE).methods.associate { method ->
        "${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}" to
            method.code().map { "${it.opcode} ${it.referenceText().orEmpty()}" }
    }

    /** What a SettingsStatus method answers: 1 once the patch switches it on, 0 as shipped. */
    private fun BytecodePatchContext.answer(status: String): Int =
        (classDefBy(SETTINGS_STATUS).methods.single { it.name == status }.code().first() as NarrowLiteralInstruction).narrowLiteral

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.strings(): List<String> = code().mapNotNull {
        if (it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO) ((it as ReferenceInstruction).reference as StringReference).string else null
    }

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    /**
     * The developer options opener, with its instance, its static holding the error key (or asking
     * a Redex string pool for it, with [pooledError]) and its open method, and Home's long press
     * handler keeping the main activity and the session.
     */
    private fun classes(
        error: Boolean = true,
        pooledError: Boolean = false,
        instance: Boolean = true,
        pressStrings: List<String> = LONG_PRESS_STRINGS,
        sessionField: Boolean = true,
        registers: Int = 9,
        callbacks: List<String> = emptyList(),
    ): List<ClassDef> {
        val loadError = when {
            pooledError -> "const/16 v0, 0x7\ninvoke-static { v0 }, $pool->A00(I)Ljava/lang/String;\nmove-result-object v0\n"
            error -> "const-string v0, \"$OPTIONS_ERROR\"\n"
            else -> ""
        }
        val made = callbacks.joinToString("") { "new-instance v0, $it\n" }
        val openerMethods = listOf(
            method(options, "show", listOf(context, activity, session, "Ljava/util/concurrent/Callable;"), "V", 5,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, loadError + made + "return-void"),
            method(options, "open", listOf(context, activity, session), "V", 4, AccessFlags.PUBLIC.value, "return-void"),
        )
        val openerFields = if (instance) {
            listOf(ImmutableField(options, "instance", options, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, null))
        } else {
            emptyList()
        }
        return listOfNotNull(
            ImmutableClassDef(options, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, openerFields, openerMethods),
            press(press, pressStrings, sessionField, registers),
            if (pooledError) stringPool() else null,
        )
    }

    /** A Redex string pool: a packed switch from the error key's number, 7, to the key, anything else null. */
    private fun stringPool(): ClassDef = ImmutableClassDef(pool, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
        listOf(method(pool, "A00", listOf("I"), "Ljava/lang/String;", 2, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, """
            packed-switch p0, :keys
            const/4 v0, 0x0
            return-object v0
            :error
            const-string v0, "$OPTIONS_ERROR"
            return-object v0
            :keys
            .packed-switch 0x7
                :error
            .end packed-switch
        """)))

    private fun press(
        owner: String,
        strings: List<String> = LONG_PRESS_STRINGS,
        sessionField: Boolean = true,
        registers: Int = 9,
    ): ClassDef {
        val fields = listOfNotNull(
            "activity" to mainActivity,
            if (sessionField) "session" to session else null,
            "flag" to "I",
        ).map { (name, type) -> ImmutableField(owner, name, type, AccessFlags.PUBLIC.value, null, null, null) }
        val longClick = method(owner, "onLongClick", listOf("Landroid/view/View;"), "Z", registers, AccessFlags.PUBLIC.value, """
            iget v0, p0, $owner->flag:I
            ${strings.joinToString("\n") { "const-string v1, \"$it\"" }}
            return v0
        """.trimIndent())
        return ImmutableClassDef(owner, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, fields, listOf(longClick))
    }

    private fun method(owner: String, name: String, parameters: List<String>, returnType: String, registers: Int, flags: Int, body: String): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }
}
