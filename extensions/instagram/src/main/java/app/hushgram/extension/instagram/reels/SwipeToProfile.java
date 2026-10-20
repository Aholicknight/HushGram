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
import java.lang.ref.WeakReference;
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
 * <p>The panel itself is on only for accounts Instagram's server picks. The tab host turns it on
 * when you reach Reels if a second gate says yes, and the swipe container turns it off on each touch
 * if that gate says no. The patch hands the container's answer to {@link #allow} as well, calls
 * {@link #tabChanged} at the end of each tab change, which turns the panel on for the Reels tab, and
 * {@link #tabChanging} at the start of the next, which turns off only what HushGram turned on, so
 * Instagram picks the other tabs' panels as it always does.
 *
 * <p>The hooks fail open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram's own answer comes back and the panel stays as Instagram left it.
 */
public final class SwipeToProfile {
    /** The step a failed switch read is reported under. */
    static final String SWITCH = "switch read";
    /** The step a failed panel turn is reported under. */
    static final String PANEL = "panel turn";

    /** What the tab hooks need of Instagram, reached through the stubs the patch writes. */
    interface Panels {
        Object controller(Object host);

        Object container(Object controller);

        Object tab(Object host);

        Object reelsTab();

        Object panel(Object container);

        boolean panelOn(Object panel);

        void turn(Object container, Object panel, boolean on);
    }

    private static final Panels INSTAGRAM = new Panels() {
        @Override
        public Object controller(Object host) {
            return SwipeToProfileTargets.controller(host);
        }

        @Override
        public Object container(Object controller) {
            return SwipeToProfileTargets.container(controller);
        }

        @Override
        public Object tab(Object host) {
            return SwipeToProfileTargets.tab(host);
        }

        @Override
        public Object reelsTab() {
            return SwipeToProfileTargets.reelsTab();
        }

        @Override
        public Object panel(Object container) {
            return SwipeToProfileTargets.panel(container);
        }

        @Override
        public boolean panelOn(Object panel) {
            return SwipeToProfileTargets.panelOn(panel);
        }

        @Override
        public void turn(Object container, Object panel, boolean on) {
            SwipeToProfileTargets.turn(container, panel, on);
        }
    };

    private static volatile boolean logged;
    private static volatile boolean loggedPanel;

    /** The panel HushGram turned on and its container, until the next tab change turns it off. */
    private static WeakReference<Object> turnedPanel;
    private static WeakReference<Object> turnedContainer;

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

    /**
     * Called first thing in each tab change, before Instagram picks the next tab's panels. Turns the
     * profile panel off if HushGram turned it on and it's still on. Never throws.
     */
    public static void tabChanging(Object host) {
        tabChanging(INSTAGRAM);
    }

    static void tabChanging(Panels panels) {
        try {
            Object panel = turnedPanel == null ? null : turnedPanel.get();
            Object container = turnedContainer == null ? null : turnedContainer.get();
            turnedPanel = null;
            turnedContainer = null;
            if (panel != null && container != null && panels.panelOn(panel)) panels.turn(container, panel, false);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SWIPE_TO_PROFILE, PANEL, t);
        }
    }

    /**
     * Called at each return of a tab change. On the Reels tab with the switch on, turns the profile
     * panel on when Instagram left it off, and remembers it. Never throws.
     */
    public static void tabChanged(Object host) {
        tabChanged(host, INSTAGRAM, SwipeToProfile::switchedOn);
    }

    static void tabChanged(Object host, Panels panels, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.SWIPE_TO_PROFILE);
            if (!on.getAsBoolean()) return;
            Object tab = panels.tab(host);
            if (tab == null || tab != panels.reelsTab()) return;
            Object controller = panels.controller(host);
            Object container = controller == null ? null : panels.container(controller);
            Object panel = container == null ? null : panels.panel(container);
            if (panel == null || panels.panelOn(panel)) return;
            panels.turn(container, panel, true);
            turnedPanel = new WeakReference<>(panel);
            turnedContainer = new WeakReference<>(container);
            if (!loggedPanel) {
                loggedPanel = true;
                Logger.printDebug(() -> "SwipeToProfile: turned the Reels profile panel on");
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SWIPE_TO_PROFILE, PANEL, t);
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.REEL_SWIPE_TO_PROFILE.get();
    }
}
