/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide paid partnership Reels" patch (#12).
 *
 * <p>Each page of Reels the server sends comes back from one method of the response, which turns
 * the server's items into clips items, the Reels viewer's own model. The patch passes that list
 * through {@link #page} at every one of those methods' returns, so the viewer, and everything else
 * reading a page, gets the list this answers.
 *
 * <p>Two things about a reel's post decide it, both fields Instagram already parsed from the
 * page, read through getters the patch finds by their JSON names:
 * <ul>
 *   <li>is_paid_partnership, the flag Instagram's own Paid partnership label reads. True drops it.
 *   <li>boosted_status, the state of the boost its owner bought, which Instagram's promote screens
 *       read as one of not_boosted, pending, boosted, boosted_eligible, boosted_eligible_paused,
 *       not_approved, finished, unavailable, draft, paused or not_delivering. Only boosted (running
 *       now) and finished (ran to its end) say paid delivery happened, so only those drop it, with
 *       case ignored as Instagram ignores it. The rest, and any value it doesn't know, stay.
 * </ul>
 * Nothing else counts: a tracking token, an ad id or a field the app doesn't use to mark a
 * promotion isn't proof of one. Sponsored ads are Hide ads' to remove.
 *
 * <p>A page where every item would go keeps every item, so the viewer always has a reel to show,
 * and the report counts it. Off, paused, before the settings are read, or when anything throws,
 * the page comes back as it came, the same list.
 */
public final class PaidPartnershipReels {
    /** The diagnostic counter route: the pages seen, and the items taken out of them. */
    static final String ROUTE = "Paid partnership reels";

    /** The reason, and kind, of an item whose post is a paid partnership. */
    static final String PARTNERSHIP = "paid partnership";

    /** The reason, and kind, of an item whose post's boost is running or ran. */
    static final String BOOSTED = "boosted";

    /** The kind counted for a page left whole because every item in it matched. */
    static final String PAGE_KEPT = "page kept whole";

    /** boosted_status values that mean the boost delivered: running now, and run to its end. */
    private static final String[] BOOST_RAN = {"boosted", "finished"};

    /** Reads what decides an item; the patch-filled stubs below outside tests. */
    interface Reader {
        /** The item's post, or null for anything that isn't a Reels item carrying one. */
        Object media(Object item);

        /** The post's is_paid_partnership, or null when it doesn't say. */
        Boolean paidPartnership(Object media);

        /** The post's boosted_status, or null when it doesn't say. */
        String boostedStatus(Object media);
    }

    private static final Reader PATCHED = new Reader() {
        @Override
        public Object media(Object item) {
            return PaidPartnershipReels.media(item);
        }

        @Override
        public Boolean paidPartnership(Object media) {
            return PaidPartnershipReels.paidPartnership(media);
        }

        @Override
        public String boostedStatus(Object media) {
            return PaidPartnershipReels.boostedStatus(media);
        }
    };

    private PaidPartnershipReels() {
    }

    /**
     * Injected at the return of each method that makes a page of Reels items. Answers a new list
     * without the paid partnership and boosted items while the switch is on, and [items] itself
     * when nothing goes, when every item would, when the switch is off or paused, or when anything
     * goes wrong. Never throws.
     */
    public static List<?> page(List<?> items) {
        return page(items, PATCHED, PaidPartnershipReels::switchedOn);
    }

    static List<?> page(List<?> items, Reader reader, BooleanSupplier on) {
        if (items == null) return null;
        try {
            HookStatus.invoked(FamilyNames.PAID_PARTNERSHIP_REELS);
            int size = items.size();
            FeedFilterCounters.sawList(ROUTE, size);
            if (size == 0 || !on.getAsBoolean()) return items;

            String[] reasons = new String[size];
            int partnerships = 0;
            int boosted = 0;
            int at = 0;
            for (Object item : items) {
                if (at >= size) return items;
                String reason = reasonFor(item, reader);
                if (PARTNERSHIP.equals(reason)) partnerships++;
                if (BOOSTED.equals(reason)) boosted++;
                if (reason != null) FeedFilterCounters.sawKind(ROUTE, reason);
                reasons[at++] = reason;
            }
            // A list that changed size while it was read isn't one this can rebuild.
            if (at != size) return items;
            int matched = partnerships + boosted;
            if (matched == 0) return items;
            if (matched == size) {
                FeedFilterCounters.sawKind(ROUTE, PAGE_KEPT);
                Logger.printDebug(() -> "Paid partnership reels: kept a page of " + size + " where every reel matched");
                return items;
            }

            List<Object> kept = new ArrayList<>(size - matched);
            at = 0;
            for (Object item : items) {
                if (reasons[at++] == null) kept.add(item);
            }
            FeedFilterCounters.removed(ROUTE, partnerships, PARTNERSHIP);
            FeedFilterCounters.removed(ROUTE, boosted, BOOSTED);
            int left = kept.size();
            Logger.printDebug(() -> "Paid partnership reels: took " + matched + " of " + size + " reels out, " + left + " left");
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PAID_PARTNERSHIP_REELS, "reels page", failure);
            return items;
        }
    }

    /** Why [item] goes, or null when it stays. */
    private static String reasonFor(Object item, Reader reader) {
        if (item == null) return null;
        Object media = reader.media(item);
        if (media == null) return null;
        if (Boolean.TRUE.equals(reader.paidPartnership(media))) return PARTNERSHIP;
        return boostRan(reader.boostedStatus(media)) ? BOOSTED : null;
    }

    /** Whether a boosted_status value says the boost delivered. */
    static boolean boostRan(String status) {
        if (status == null) return false;
        for (String ran : BOOST_RAN) {
            if (ran.equalsIgnoreCase(status)) return true;
        }
        return false;
    }

    /** Whether the switch is on, read only once the settings are; false while paused. */
    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_PAID_PARTNERSHIP_REELS.get();
    }

    /**
     * The post a Reels item carries, or null for anything else. The patch writes the body, which
     * checks the item is Instagram's clips item and reads its one post field. Null as built.
     */
    public static Object media(Object item) {
        return null;
    }

    /**
     * A post's is_paid_partnership, or null when it doesn't say. The patch writes the body, which
     * calls the post's getter for that field. Null as built.
     */
    public static Boolean paidPartnership(Object media) {
        return null;
    }

    /**
     * A post's boosted_status, or null when it doesn't say. The patch writes the body, which calls
     * the post's getter for that field. Null as built.
     */
    public static String boostedStatus(Object media) {
        return null;
    }
}
