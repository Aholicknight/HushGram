/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import java.util.function.BooleanSupplier;

/**
 * Helper for the "Hide Plus preview in chat menus" patch.
 *
 * <p>The menu a long press on a chat opens has a peek form: a preview overlay above the menu and
 * one extra row below the options that sells Instagram Plus. Instagram builds the menu once per
 * long press and reads one flag to decide between the two forms. The patch hands that flag to
 * {@link #peek} right after Instagram unboxes it, and while the switch is on the answer is no, so
 * the menu takes the plain form accounts without the experiment get: the options alone, in order,
 * with no overlay and no empty slot. Nothing Plus offers is turned on by this.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram's own flag comes back.
 */
public final class PlusPreview {
    /** The step a failed switch read is reported under. */
    static final String SWITCH = "switch read";

    private static volatile boolean logged;

    private PlusPreview() {
    }

    /**
     * Called with Instagram's peek flag (non-zero is on). Returns false while the switch is on,
     * otherwise the flag as it came. Takes an int because the register Instagram's code hands over
     * can be typed as one. Never throws.
     */
    public static boolean peek(int flag) {
        return peek(flag, PlusPreview::switchedOn);
    }

    static boolean peek(int flag, BooleanSupplier on) {
        boolean original = flag != 0;
        try {
            HookStatus.invoked(FamilyNames.PLUS_PREVIEW);
            if (!original) return false;
            boolean hide = on.getAsBoolean();
            if (hide && !logged) {
                logged = true;
                Logger.printDebug(() -> "PlusPreview: opened a chat menu without the peek form");
            }
            return !hide;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.PLUS_PREVIEW, SWITCH, t);
            return original;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_PLUS_PREVIEW.get();
    }
}
