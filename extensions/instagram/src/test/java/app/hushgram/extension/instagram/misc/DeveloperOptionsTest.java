/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Open developer options' answer to a long press of Home, and its time limit on the loading dialog (#158). */
@RunWith(RobolectricTestRunner.class)
public class DeveloperOptionsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Activity activity;
    private FakeHost host;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        host = new FakeHost();
        DeveloperOptions.forgetPress();
        HookStatus.clear();
        Settings.OPEN_DEVELOPER_OPTIONS.save(true);
    }

    @After
    public void restore() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.OPEN_DEVELOPER_OPTIONS.resetToDefault();
        DeveloperOptions.forgetPress();
        HookStatus.clear();
    }

    @Test
    public void onTheLongPressOpensTheOptions() {
        assertEquals(1, DeveloperOptions.open());
    }

    @Test
    public void offTheLongPressDoesWhatItDid() {
        Settings.OPEN_DEVELOPER_OPTIONS.save(false);
        assertEquals(0, DeveloperOptions.open());
    }

    /** A load still waiting when the time is up loses its dialog, the editor opens, and the count says so. */
    @Test
    public void aLoadThatNeverAnswersLosesItsDialogAndOpensTheEditor() {
        DeveloperOptions.opening(activity, host);

        assertEquals(1, host.checks.size());
        assertEquals(DeveloperOptions.LOAD_TIMEOUT_MS, (long) host.delays.get(0));
        assertEquals("nothing happens before the time is up", 0, host.dismissed);
        host.timeUp(0);

        assertEquals(1, host.dismissed);
        assertEquals(1, host.opened);
        assertEquals(Collections.singletonList("Instagram's developer options didn't load, so MetaConfig opened instead."), host.toasts);
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(DeveloperOptions.TIMED_OUT + " 1"));
    }

    /** Where the editor can't open, the dialog still comes down and a toast says what happened. */
    @Test
    public void anEditorThatCantOpenLeavesAToast() {
        host.editorOpens = false;
        DeveloperOptions.opening(activity, host);

        host.timeUp(0);

        assertEquals(1, host.dismissed);
        assertEquals(Collections.singletonList("Instagram's developer options didn't load. Try the long press again later."), host.toasts);
    }

    /** An answer that comes after the press gave up returns before Instagram's own code, once. */
    @Test
    public void aLateAnswerIsDroppedOnce() {
        DeveloperOptions.opening(activity, host);
        host.timeUp(0);

        assertEquals(1, DeveloperOptions.loaded());
        assertEquals(0, DeveloperOptions.loaded());
    }

    /** A load answering in time keeps Instagram's behavior: its answer runs, and the time up does nothing. */
    @Test
    public void aLoadAnsweringInTimeIsLeftAlone() {
        DeveloperOptions.opening(activity, host);

        assertEquals(0, DeveloperOptions.loaded());
        host.timeUp(0);

        assertEquals(0, host.dismissed);
        assertEquals(0, host.opened);
        assertTrue(host.toasts.isEmpty());
        assertFalse(HookStatus.report().toString().contains(DeveloperOptions.TIMED_OUT));
    }

    /** No dialog to take down means nothing is stuck on screen, so nothing opens and a late answer still runs. */
    @Test
    public void noDialogLeavesEverythingAsItIs() {
        host.spinnerUp = false;
        DeveloperOptions.opening(activity, host);

        host.timeUp(0);

        assertEquals(0, host.opened);
        assertTrue(host.toasts.isEmpty());
        assertEquals(0, DeveloperOptions.loaded());
    }

    /** Turned off, or paused, before the time is up: the dialog is Instagram's to keep. */
    @Test
    public void offWhenTheTimeIsUpLeavesTheDialog() {
        DeveloperOptions.opening(activity, host);
        Settings.OPEN_DEVELOPER_OPTIONS.save(false);

        host.timeUp(0);

        assertEquals(0, host.dismissed);
        assertEquals(0, DeveloperOptions.loaded());
    }

    @Test
    public void pausedOrUnreadyWhenTheTimeIsUpLeavesTheDialog() {
        DeveloperOptions.opening(activity, host);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        try {
            host.timeUp(0);
        } finally {
            BaseSettings.PAUSED.save(false);
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> host.timeUp(0));
        SettingsContextRule.beforeThePauseIsDecided(() -> host.timeUp(0));

        assertEquals(0, host.dismissed);
        host.timeUp(0);
        assertEquals("the same press, resumed", 1, host.dismissed);
    }

    /** Only the newest press acts on its time up. */
    @Test
    public void aNewerPressTakesOver() {
        DeveloperOptions.opening(activity, host);
        DeveloperOptions.opening(activity, host);

        host.timeUp(0);
        assertEquals("the first press's time up", 0, host.dismissed);
        host.timeUp(1);
        assertEquals(1, host.dismissed);
    }

    /** A closed activity has no dialog to take down and no screen to open on. */
    @Test
    public void aClosedActivityIsLeftAlone() {
        DeveloperOptions.opening(activity, host);
        controller.pause().stop().destroy();

        host.timeUp(0);

        assertEquals(0, host.dismissed);
        assertEquals(0, host.opened);
    }

    /** Anything but an activity starts no time limit. */
    @Test
    public void somethingElseStartsNoTimeLimit() {
        DeveloperOptions.opening("not an activity", host);
        DeveloperOptions.opening(null, host);

        assertTrue(host.checks.isEmpty());
        assertEquals(0, DeveloperOptions.loaded());
    }

    /** A host that throws is counted, and the press goes on as Instagram has it. */
    @Test
    public void aThrowIsCountedAndChangesNothing() {
        host.dismissThrows = true;
        DeveloperOptions.opening(activity, host);

        host.timeUp(0);

        assertEquals(0, host.opened);
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("loading time limit"));
        assertEquals(0, DeveloperOptions.loaded());
    }

    /** The unfilled stub answers 0, so the shipped extension takes nothing down before the patch fills it. */
    @Test
    public void theUnfilledStubTakesNothingDown() {
        assertEquals(0, DeveloperOptions.dismissSpinnerNative(activity));
    }

    private static final class FakeHost implements DeveloperOptions.Host {
        final List<Runnable> checks = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();
        final List<String> toasts = new ArrayList<>();
        boolean spinnerUp = true;
        boolean editorOpens = true;
        boolean dismissThrows;
        int dismissed;
        int opened;

        void timeUp(int check) {
            checks.get(check).run();
        }

        @Override
        public void later(Runnable check, long delayMs) {
            checks.add(check);
            delays.add(delayMs);
        }

        @Override
        public int dismissSpinner(Activity activity) {
            if (dismissThrows) throw new IllegalStateException("dismiss");
            if (!spinnerUp) return 0;
            dismissed++;
            return 1;
        }

        @Override
        public boolean openEditor(Activity activity) {
            opened++;
            return editorOpens;
        }

        @Override
        public void toast(String text) {
            toasts.add(text);
        }
    }
}
