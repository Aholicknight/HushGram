/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

/**
 * Stubs the "Swipe left to open the profile" patch writes, so {@link SwipeToProfile} can reach the
 * Reels tab's profile panel without naming Instagram's renamed classes. Unpatched, each one answers
 * as if the piece were missing, and turning a panel does nothing.
 */
public final class SwipeToProfileTargets {
    private SwipeToProfileTargets() {
    }

    /** The tab host's swipe controller, or null. */
    public static Object controller(Object host) {
        return null;
    }

    /** The controller's side panel container, or null. */
    public static Object container(Object controller) {
        return null;
    }

    /** The tab the host is on. */
    public static Object tab(Object host) {
        return null;
    }

    /** The Reels tab. */
    public static Object reelsTab() {
        return null;
    }

    /** The container's profile panel, or null. */
    public static Object panel(Object container) {
        return null;
    }

    /** Whether the panel is on. */
    public static boolean panelOn(Object panel) {
        return false;
    }

    /** Turns the panel on or off, by the panel's own position in the container. */
    public static void turn(Object container, Object panel, boolean on) {
    }
}
