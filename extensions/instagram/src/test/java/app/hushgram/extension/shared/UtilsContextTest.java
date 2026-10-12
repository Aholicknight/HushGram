/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.shared;

import static org.junit.Assert.assertSame;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/** The context every hook reads is the application's, whatever the start hook hands in. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class UtilsContextTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** A static field holding an activity keeps its window and views after it's closed or recreated. */
    @Test
    public void anActivityIsFollowedWeaklyAndItsApplicationIsKept() {
        Application app = RuntimeEnvironment.getApplication();
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            assertSame(app, Utils.getContext());
            assertSame(activity, Utils.getActivity());
        }
    }

    /** Anything that isn't an activity is kept as handed in, so a wrapper's own answers still count. */
    @Test
    public void aWrapperThatIsNotAnActivityIsKeptAsItIs() {
        Context wrapper = new ContextWrapper(RuntimeEnvironment.getApplication());
        Utils.setContext(wrapper);
        assertSame(wrapper, Utils.getContext());
    }
}
