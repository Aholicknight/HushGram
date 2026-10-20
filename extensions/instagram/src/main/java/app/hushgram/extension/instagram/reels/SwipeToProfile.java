/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import java.util.function.BooleanSupplier;

/**
 * Helper for the "Swipe left to open the profile" patch.
 *
 * <p>Older Instagram builds opened the creator's profile when you swiped left on a reel. Now the
 * swipe goes to the Friends tab, or nowhere. Before it moves, the Reels viewer asks one check
 * whether a swipe from a reel to its side panel is allowed, and for an ordinary reel the last gate
 * of that check is a call that is usually false. The patch hands that call's answer to {@link #allow}
 * right after Instagram reads it. While the switch is on the answer is yes, and Instagram's own
 * panel picker then chooses the profile panel for an ordinary reel, the way it used to.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram's own answer comes back.
 */
public final class SwipeToProfile {
    /** The step a failed switch read is reported under. */
    static final String SWITCH = "switch read";

    private static volatile boolean logged;

    private SwipeToProfile() {
    }

    /**
     * Called with Instagram's answer to whether this swipe may go on to the side panel. Returns true
     * while the switch is on, otherwise the answer as it came. Never throws.
     */
    public static boolean allow(boolean stock) {
        return allow(stock, SwipeToProfile::switchedOn);
    }

    static boolean allow(boolean stock, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.SWIPE_TO_PROFILE);
            if (stock) return true;
            boolean open = on.getAsBoolean();
            if (open && !logged) {
                logged = true;
                Logger.printDebug(() -> "SwipeToProfile: let a swipe on a reel go to its profile");
            }
            return open;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SWIPE_TO_PROFILE, SWITCH, t);
            return stock;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.REEL_SWIPE_TO_PROFILE.get();
    }
}
