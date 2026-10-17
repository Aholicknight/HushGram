/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.view.View;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide suggested people on profiles" patch.
 *
 * <p>Instagram draws the accounts it suggests on a profile three ways, and the server picks which:
 * a row of them inside the row of action buttons (Follow, Message), a row of its own under the
 * header, and a person-plus button beside the action buttons that opens them (Discover people on
 * your own profile). The patch hands each of them here as Instagram builds it:
 * <ul>
 *   <li>{@link #inlineRow} gets the flag the action row reads to show its suggestions, and a 0 sends
 *       it down Instagram's own path for no suggestions, which hides the row's container;</li>
 *   <li>{@link #keepStandaloneRow} is asked before the header adds its row of suggestions, and a 0
 *       leaves the row out of the header's list, so nothing is bound for it;</li>
 *   <li>{@link #chainingButton} gets the person-plus button once Instagram has set it up, and hides
 *       it, and the row's other buttons spread over the space it leaves ({@link #fitRow}).</li>
 * </ul>
 * Every other row of the header (bio, counts, links, highlights) and the action buttons themselves
 * are left alone.
 *
 * <p>The followers and following lists are built elsewhere, and only one thing there belongs to this
 * class: the Suggested for you section under the list. The list reads, as it opens, whether to fetch
 * accounts to suggest, and the patch hands that answer to {@link #followList}. A 0 means it never
 * asks, so the section is never fetched or drawn and every account in the list stays.
 *
 * <p>Your own profile can also show Complete your profile's cards (Add bio, Find people to follow)
 * above its posts. Its posts ask one check whether to add them, and {@link #hideCompletionCards}
 * answers first: a yes makes that check say no, so the cards are never added.
 *
 * <p>Your notifications can end with People you don't follow back, accounts that follow you with a
 * Follow back button on each. They come two ways, and the notifications' list builder draws both
 * under the same header. The older reply lists them under their own key, and its parser hands them to
 * {@link #followBacks} before keeping them: a null means there are none, so the section isn't built.
 * The newer reply sends them as one of its suggestion sections with the type FOLLOW_BACK, and the
 * builder asks {@link #hidesFollowBackSection} about each such section that has accounts, before it
 * adds the header: a yes skips the section, header, rows and footer, as if it had none.
 *
 * <p>Your notifications also get rows that only suggest something, a thread from an account you
 * don't follow or someone you might know (#181). The row parser hands each row and its kind to
 * {@link #notificationParsed}, and the reply's list asks {@link #keepNotification} before adding it:
 * a null there is the list's own check for a row that didn't parse, so the row is never drawn.
 *
 * <p>Each hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram's own answer stands.
 */
public final class ProfileSuggestions {
    /** The diagnostic counter route: what each hook was handed, and what it hid. */
    static final String ROUTE = "Profile suggestions";

    static final String INLINE_ROW = "inline row";
    static final String STANDALONE_ROW = "standalone row";
    static final String CHAINING_BUTTON = "discover people button";
    static final String FOLLOW_LIST = "follow list section";
    static final String COMPLETION_CARDS = "complete your profile cards";
    static final String FOLLOW_BACKS = "people you don't follow back";

    /** The type the notifications' reply gives the People you don't follow back section. */
    static final String FOLLOW_BACK_TYPE = "FOLLOW_BACK";

    /** The diagnostic count of suggestion sections the notifications' builder asked about, any type. */
    static final String SECTIONS = "notification suggestion sections";

    /** The diagnostic counter route of notification rows, by the server's name for each row's kind. */
    static final String NOTIFICATIONS = "Notification rows";
    static final String NO_TYPE = "no type";
    static final String SUGGESTION_ROWS = "suggestion rows in notifications";

    /**
     * The server's names for notification rows that only suggest something to you, read off the
     * notifications of an account that gets them on Instagram 451 (2026-10-14): "<name>, who you might
     * know, is on Instagram" and "Check out your new follow suggestions" are connect_lowlness, and
     * "<name> posted a thread you might be interested in" is the Threads one.
     */
    static final Set<String> SUGGESTION_TYPES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "connect_lowlness",
            "ig_text_app_non_onboarded_unconnected_daily_digest")));

    /** The prefix of every Threads ("text app") row, and the words in the names of those from accounts you aren't connected to. */
    static final String THREADS_PREFIX = "ig_text_app_";
    static final List<String> THREADS_SUGGESTION_WORDS = Collections.unmodifiableList(Arrays.asList("unconnected", "suggest", "recommend"));

    /**
     * The last row this thread's parser found to be a suggestion. The reply's list adds each row right
     * after parsing it, on the same thread, so the row checked there is this one. Weak, since the parser
     * also serves replies that never ask.
     */
    private static final ThreadLocal<WeakReference<Object>> lastSuggestion = new ThreadLocal<>();

    /**
     * The person-plus buttons this class hid, with the visibility each had. Instagram reuses the
     * button between binds and never sets its visibility itself, so with the switch off a button
     * hidden earlier gets its own visibility back. Weak, so a button gone from the screen isn't kept.
     */
    private static final Map<View, Integer> hidden = new WeakHashMap<>();

    /**
     * The tags Instagram gives the icon buttons of a profile's action row, the ones it sizes apart
     * from the text buttons: the person-plus button, Saved and the overflow menu.
     */
    static final Set<String> ICON_TAGS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "chaining_icon", "saved", "overflow_menu")));

    /** What's counted when the row's text buttons take the hidden button's space, and when they give it back. */
    static final String ROW_SPREAD = "action buttons spread over the hidden button's space";
    static final String ROW_PUT_BACK = "action buttons put back to Instagram's widths";

    /** The params this class weighted, so replacement native weights aren't taken back. Weak, as {@link #hidden} is. */
    private static final Map<View, LinearLayout.LayoutParams> spread = new WeakHashMap<>();

    private ProfileSuggestions() {
    }

    /**
     * Injected right after the profile's action row reads whether to show its row of suggested
     * accounts. Answers 0 while the switch is on, and [show] otherwise, or when anything goes wrong.
     * A 0 coming in stays 0. Never throws.
     */
    public static int inlineRow(int show) {
        return inlineRow(show, ProfileSuggestions::switchedOn);
    }

    static int inlineRow(int show, BooleanSupplier on) {
        if (show == 0) return 0;
        return hides(INLINE_ROW, on) ? 0 : show;
    }

    /**
     * Injected where the profile header is about to add its row of suggested accounts. Answers 0,
     * leave it out, while the switch is on, and 1 otherwise, or when anything goes wrong. Never
     * throws.
     */
    public static int keepStandaloneRow() {
        return keepStandaloneRow(ProfileSuggestions::switchedOn);
    }

    static int keepStandaloneRow(BooleanSupplier on) {
        return hides(STANDALONE_ROW, on) ? 0 : 1;
    }

    /**
     * Injected right after Instagram sets up the person-plus button beside a profile's action
     * buttons. Hides it while the switch is on. Otherwise a button this hid before gets its old
     * visibility back, and any other is left as it is. Never throws.
     */
    public static void chainingButton(View button) {
        chainingButton(button, ProfileSuggestions::switchedOn);
    }

    static void chainingButton(View button, BooleanSupplier on) {
        if (button == null) return;
        boolean hide = hides(CHAINING_BUTTON, on);
        try {
            boolean changed;
            synchronized (hidden) {
                if (hide) {
                    int was = button.getVisibility();
                    button.setVisibility(View.GONE);
                    // Only the first hide records it: a later one would record GONE as its own.
                    if (!hidden.containsKey(button)) hidden.put(button, was);
                    changed = true;
                } else {
                    Integer was = hidden.remove(button);
                    if (was != null) button.setVisibility(was);
                    changed = was != null;
                }
            }
            // The button isn't in the row yet: Instagram empties the row and adds each button back
            // after setting it up. A post waits until it's attached there again.
            if (changed) button.post(() -> fitRow(button));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_SUGGESTIONS, CHAINING_BUTTON, failure);
        }
    }

    /**
     * Spreads the action row's other buttons over the space the hidden person-plus button leaves
     * (#87), or takes that back once the button shows again. Never throws.
     *
     * <p>Instagram doesn't let the row's buttons share it: it works out a fixed width for each text
     * button (Follow, Message, Edit profile, Share profile) from the row's width, how many there are,
     * and whether an icon button sits beside them, which keeps its own fixed width. The icon buttons
     * are the ones it tags {@link #ICON_TAGS}. So with the person-plus button gone the text buttons
     * keep widths that leave room for it, and a gap sits at the end of the row. Each visible text
     * button here gets a weight of 1, so the row hands them that space in equal parts on top of
     * Instagram's widths. Where there's no space left over, as in a row with no icon or one whose
     * icon shows, a weight changes nothing, and Instagram's own widths keep working as they did.
     */
    static void fitRow(View button) {
        try {
            if (!(button.getParent() instanceof LinearLayout)) return;
            LinearLayout row = (LinearLayout) button.getParent();
            if (row.getOrientation() != LinearLayout.HORIZONTAL) return;
            boolean gone;
            synchronized (hidden) {
                gone = hidden.containsKey(button) && button.getVisibility() == View.GONE;
            }
            int moved = 0;
            synchronized (spread) {
                for (int i = 0; i < row.getChildCount(); i++) {
                    View child = row.getChildAt(i);
                    if (child == button || child == null || isIcon(child)) continue;
                    if (!(child.getLayoutParams() instanceof LinearLayout.LayoutParams)) continue;
                    LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) child.getLayoutParams();
                    LinearLayout.LayoutParams owned = spread.get(child);
                    if (owned != null && (owned != params || params.weight != 1)) {
                        // A native rebind replaced the params or changed their weight.
                        spread.remove(child);
                    }
                    if (gone && child.getVisibility() != View.GONE && params.weight == 0) {
                        params.weight = 1;
                        spread.put(child, params);
                        moved++;
                    } else if (!gone && spread.remove(child) == params) {
                        params.weight = 0;
                        moved++;
                    }
                }
            }
            if (moved == 0) return;
            row.requestLayout();
            HookStatus.counted(FamilyNames.PROFILE_SUGGESTIONS, gone ? ROW_SPREAD : ROW_PUT_BACK);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_SUGGESTIONS, ROW_SPREAD, failure);
        }
    }

    /** Whether Instagram counts [child] as one of the row's icon buttons, by the tag it gives it. */
    private static boolean isIcon(View child) {
        Object tag = child.getTag();
        return tag != null && ICON_TAGS.contains(tag.toString());
    }

    /**
     * Injected right after the followers or following list reads whether to fetch the accounts it
     * suggests under the list. Answers 0 while Hide Suggested for you on follow lists is on, and
     * [fetch] otherwise, or when anything goes wrong. A 0 coming in stays 0. Never throws.
     */
    public static int followList(int fetch) {
        return followList(fetch, ProfileSuggestions::followListOn);
    }

    static int followList(int fetch, BooleanSupplier on) {
        if (fetch == 0) return 0;
        return hides(FOLLOW_LIST, on) ? 0 : fetch;
    }

    /**
     * Injected first in the check your own profile's posts make before adding the Complete your
     * profile cards (#159). True, which answers that check no, while Hide Complete your profile is
     * on, and false, Instagram's own check, otherwise or when anything goes wrong. Never throws.
     */
    public static boolean hideCompletionCards() {
        return hideCompletionCards(ProfileSuggestions::completionOn);
    }

    static boolean hideCompletionCards(BooleanSupplier on) {
        return hides(COMPLETION_CARDS, on);
    }

    /**
     * Injected where the notifications' parser keeps the accounts it read for People you don't follow
     * back (#161), the suggestion section under your notifications. Answers null while Hide People you
     * don't follow back is on, the same as a reply without those accounts, so the notifications build
     * no section for them at all. Otherwise, or when anything goes wrong, [accounts] as they came.
     * A follow notification is a story of its own and never passes here. Counts the call even with
     * no accounts, so the diagnostic report shows the hook ran. Never throws.
     */
    public static List<?> followBacks(List<?> accounts) {
        return followBacks(accounts, ProfileSuggestions::followBacksOn);
    }

    static List<?> followBacks(List<?> accounts, BooleanSupplier on) {
        HookStatus.invoked(FamilyNames.PROFILE_SUGGESTIONS);
        if (accounts == null || accounts.isEmpty()) return accounts;
        return decides(FOLLOW_BACKS, on) ? null : accounts;
    }

    /**
     * Injected where the notifications' list builder has a suggestion section from the server with
     * accounts in it and is about to add its header (#161). True, skip the whole section, when [type]
     * is People you don't follow back's and Hide People you don't follow back is on. False, Instagram
     * goes on to draw it, for any other section, with the switch off, paused or unready, or when
     * anything goes wrong. Follow notifications are stories, not sections, and never pass here.
     * Never throws.
     */
    public static boolean hidesFollowBackSection(String type) {
        return hidesFollowBackSection(type, ProfileSuggestions::followBacksOn);
    }

    static boolean hidesFollowBackSection(String type, BooleanSupplier on) {
        HookStatus.invoked(FamilyNames.PROFILE_SUGGESTIONS);
        HookStatus.counted(FamilyNames.PROFILE_SUGGESTIONS, SECTIONS);
        return FOLLOW_BACK_TYPE.equals(type) && decides(FOLLOW_BACKS, on);
    }

    /**
     * Injected right before the notifications' row parser hands back a row, with the row and the
     * server's name for its kind (#181). Counts the name for the diagnostic report, and remembers a
     * row that only suggests something, so {@link #keepNotification} can leave it out of the list.
     * Never throws.
     */
    public static void notificationParsed(Object row, String type) {
        try {
            HookStatus.invoked(FamilyNames.PROFILE_SUGGESTIONS);
            FeedFilterCounters.sawKind(NOTIFICATIONS, type == null ? NO_TYPE : type);
            lastSuggestion.set(row != null && isSuggestion(type) ? new WeakReference<>(row) : null);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_SUGGESTIONS, SUGGESTION_ROWS, failure);
        }
    }

    /**
     * Whether a row of this kind only suggests something: one of {@link #SUGGESTION_TYPES}, or a
     * Threads row whose name says it comes from an account you aren't connected to or is a suggestion.
     * Replies, likes and mentions on Threads say neither and stay.
     */
    static boolean isSuggestion(String type) {
        if (type == null) return false;
        if (SUGGESTION_TYPES.contains(type)) return true;
        if (!type.startsWith(THREADS_PREFIX)) return false;
        for (String word : THREADS_SUGGESTION_WORDS) {
            if (type.contains(word)) return true;
        }
        return false;
    }

    /**
     * Injected where the notifications' reply adds each row it parsed to its list, the null check
     * right after. Answers null, which that check skips, for a row {@link #notificationParsed} found
     * to be a suggestion while Hide suggestions in notifications is on. Any other row, the switch off,
     * paused or unready, or anything going wrong, answers [row] as it came. Never throws.
     */
    public static Object keepNotification(Object row) {
        return keepNotification(row, ProfileSuggestions::notificationSuggestionsOn);
    }

    static Object keepNotification(Object row, BooleanSupplier on) {
        if (row == null) return null;
        try {
            WeakReference<Object> marked = lastSuggestion.get();
            if (marked == null || marked.get() != row) return row;
            lastSuggestion.set(null);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_SUGGESTIONS, SUGGESTION_ROWS, failure);
            return row;
        }
        return decides(SUGGESTION_ROWS, on) ? null : row;
    }

    /** Whether to hide this part now, counting the call and the part either way. False when anything goes wrong. */
    private static boolean hides(String part, BooleanSupplier on) {
        HookStatus.invoked(FamilyNames.PROFILE_SUGGESTIONS);
        return decides(part, on);
    }

    /** Whether to hide this part now, counting the part either way. False when anything goes wrong. */
    private static boolean decides(String part, BooleanSupplier on) {
        try {
            FeedFilterCounters.sawKind(ROUTE, part);
            if (!on.getAsBoolean()) return false;
            FeedFilterCounters.removed(ROUTE, 1, part);
            Logger.printDebug(() -> "Profile suggestions: hid the " + part);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_SUGGESTIONS, part, failure);
            return false;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_PROFILE_SUGGESTIONS.get();
    }

    private static boolean followListOn() {
        return Utils.settingsReady() && Settings.HIDE_FOLLOW_LIST_SUGGESTIONS.get();
    }

    private static boolean completionOn() {
        return Utils.settingsReady() && Settings.HIDE_PROFILE_COMPLETION.get();
    }

    private static boolean followBacksOn() {
        return Utils.settingsReady() && Settings.HIDE_FOLLOW_BACK_SUGGESTIONS.get();
    }

    private static boolean notificationSuggestionsOn() {
        return Utils.settingsReady() && Settings.HIDE_NOTIFICATION_SUGGESTIONS.get();
    }
}
