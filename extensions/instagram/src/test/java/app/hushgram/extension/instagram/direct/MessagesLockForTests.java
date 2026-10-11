/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

/** Lets tests in other packages start the lock from how a fresh start finds it. */
public final class MessagesLockForTests {
    private MessagesLockForTests() {
    }

    public static void reset() {
        MessagesLock.resetForTests();
    }
}
