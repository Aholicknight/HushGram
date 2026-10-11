/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * The chats "Lock your messages" hides, one at a time.
 *
 * <p>A hidden chat is left out of the list of chats the inbox draws its rows from ({@link #inbox}),
 * out of the chats each folder tab counts its unread ones from ({@link #folder}) and the ones the
 * unread badge reads ({@link #filter}), out of the inbox search's results and message matches ({@link #searchResults},
 * {@link #searchHits}), out of the recent searches it lists before you type ({@link #recents}), and a push for it isn't posted at all ({@link ChatLocks#track} asks {@link #hides}). The list is
 * {@link Settings#HIDDEN_CHATS}, kept in the same lines as the locked chats ({@link ChatList}): a
 * chat's thread id and the name it had when it was hidden. The ids never leave the phone. HushGram
 * settings add the chat you opened last and take a chat off the list.
 *
 * <p>A list with a chat on it is the switch. Paused, the setting answers an empty list, so every
 * hidden chat is back until HushGram is turned on again; the settings screen still shows what was
 * chosen ({@link #saved}).
 *
 * <p>A chat ticked or unticked in HushGram settings shows in the inbox as soon as you're back
 * ({@link #changed}): each inbox that drew its rows is handed Instagram's own list again without the
 * chats hidden now, and draws them again.
 *
 * <p>Nothing here throws into Instagram. A list that can't be read hides nothing, and a summary
 * whose id can't be read stays in the inbox.
 */
public final class HiddenChats {
    /** What is counted in the diagnostic report. */
    static final String LEFT_OUT = "hidden chats left out of the inbox";
    static final String LEFT_OUT_FOLDERS = "hidden chats left out of folder unread counts";
    static final String LEFT_OUT_BADGE = "hidden chats left out of the unread badge";
    static final String LEFT_OUT_SEARCH = "hidden chats left out of search";
    static final String LEFT_OUT_RECENTS = "hidden chats left out of recent searches";
    static final String SILENCED = "hidden chat notifications dropped";
    static final String REDRAWN = "inbox redrawn after hidden chats changed";

    /** Steps a failure is reported under. */
    static final String LIST = "hidden chats";
    static final String FILTER = "inbox filter";
    static final String REDRAW = "inbox redraw";
    static final String FOLDER_FILTER = "folder count filter";
    static final String BADGE_FILTER = "unread badge filter";
    static final String SEARCH = "inbox search filter";
    static final String RECENTS = "recent searches filter";

    /** Instagram's own names for a chat as the inbox search lists it, and for a chat whose messages matched. */
    private static final String SHARE_TARGET = "com.instagram.model.direct.DirectShareTarget";
    private static final String HIT_THREAD = "com.instagram.model.direct.DirectMessageSearchThread";
    private static final String HIT_MESSAGE = "com.instagram.model.direct.DirectMessageSearchMessage";

    /** Reads the thread id of one of the inbox's thread summaries. Tests put a fake in. */
    interface Reader {
        String threadId(Object summary);
    }

    static volatile Reader reader = HiddenChats::threadId;

    /** Reads the thread id of a chat in the inbox search's results. Tests put a fake in. */
    static volatile Reader searchReader = HiddenChats::targetThreadId;

    /** Hands an inbox holder a list and has it draw its rows from it. Tests put a fake in. */
    interface Redrawer {
        void redraw(Object holder, List<Object> summaries);
    }

    static volatile Redrawer redrawer = HiddenChats::redraw;

    private HiddenChats() {
    }

    /**
     * The thread id of a thread summary, or null when it has none. The patch writes the body at
     * patch time, from the summary's own chat key getter and the key's thread id field, and a build
     * without it answers null, so no chat is hidden from the inbox.
     */
    public static String threadId(Object summary) {
        return null;
    }

    // ---------------------------------------------------------------- the inbox

    /**
     * Asked with the inbox's holder and the list of chats it is about to keep and turn into rows,
     * each time Instagram hands it one: the one read from the phone at start, a fresh page from the
     * server, a pull to refresh, a switch of folder tab. A list with no hidden chat in it comes back
     * as it is, and otherwise a copy without the hidden ones does. Instagram's own list is left as it
     * was, so the inbox's state, its next page and what it saves to the phone still have every chat.
     * The holder is remembered weakly with Instagram's own list, so a change to the hidden chats can
     * draw it again ({@link #changed}).
     */
    public static List<Object> inbox(Object holder, List<Object> summaries) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            Set<String> hidden = ids();
            remember(holder, summaries, hidden);
            if (summaries == null || summaries.isEmpty()) return summaries;
            return without(summaries, hidden, LEFT_OUT);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, FILTER, t);
            return summaries;
        }
    }

    /**
     * Asked with one folder tab's chats, which Instagram counts the tab's unread chats from (the
     * "1 unread message" on a folder pill). The same as {@link #inbox}, so a hidden chat's unread
     * messages don't count on any tab.
     */
    public static List<Object> folder(List<Object> summaries) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            if (summaries == null || summaries.isEmpty()) return summaries;
            return without(summaries, ids(), LEFT_OUT_FOLDERS);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, FOLDER_FILTER, t);
            return summaries;
        }
    }

    /** [summaries] as they are when none is [hidden], and otherwise a copy without those, counted when [counted] isn't null. */
    private static List<Object> without(List<Object> summaries, Set<String> hidden, String counted) {
        if (hidden.isEmpty() || summaries.isEmpty()) return summaries;
        ArrayList<Object> shown = shown(summaries, hidden);
        if (shown.size() == summaries.size()) return summaries;
        if (counted != null) HookStatus.counted(FamilyNames.MESSAGES_LOCK, counted);
        return shown;
    }

    /**
     * Asked with the thread summaries Instagram's unread badge snapshot is about to read. A list
     * with no hidden chat in it comes back as it is. Otherwise a copy without the hidden ones does,
     * and Instagram's own list is left as it was.
     */
    public static ArrayList<Object> filter(ArrayList<Object> summaries) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            if (summaries == null || summaries.isEmpty()) return summaries;
            Set<String> hidden = ids();
            if (hidden.isEmpty()) return summaries;
            ArrayList<Object> shown = shown(summaries, hidden);
            if (shown.size() != summaries.size()) HookStatus.counted(FamilyNames.MESSAGES_LOCK, LEFT_OUT_BADGE);
            return shown;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, BADGE_FILTER, t);
            return summaries;
        }
    }

    /** A new list of the summaries whose chats aren't hidden, in their order. */
    private static ArrayList<Object> shown(List<Object> summaries, Set<String> hidden) {
        ArrayList<Object> shown = new ArrayList<>(summaries.size());
        for (Object summary : summaries) {
            String id = idOf(summary);
            if (id == null || !hidden.contains(id)) shown.add(summary);
        }
        return shown;
    }

    private static String idOf(Object summary) {
        try {
            String id = reader.threadId(summary);
            return id == null || id.trim().isEmpty() ? null : id.trim();
        } catch (Throwable t) {
            // One summary that can't be read stays in the inbox; the rest are still filtered.
            return null;
        }
    }

    // ---------------------------------------------------------------- drawing the inbox again

    /**
     * Hands the inbox holder [holder] the list [summaries] and runs its row factory on it: the two
     * steps Instagram's own subscriber takes each time the inbox's state hands the holder a list. The
     * patch writes the body at patch time, from the holder's list field and its own starter, and a
     * build without it does nothing. The starter does nothing while its inbox is paused or gone, and
     * the inbox runs it again when it resumes, so the rows are drawn from this list then.
     */
    public static void redraw(Object holder, List<Object> summaries) {
    }

    /** An inbox holder Instagram handed a list, that list, and the chats that were hidden from it. */
    private static final class Drawn {
        final WeakReference<Object> holder;
        final List<Object> summaries;
        Set<String> hidden;

        Drawn(Object holder, List<Object> summaries, Set<String> hidden) {
            this.holder = new WeakReference<>(holder);
            this.summaries = summaries;
            this.hidden = hidden;
        }
    }

    /** The inbox holders Instagram handed a list, one entry each. A holder Instagram dropped goes the next time it's read. */
    private static final ArrayList<Drawn> drawn = new ArrayList<>();

    /**
     * Keeps [holder] with Instagram's own list [summaries] and the chats [hidden] from it, in place of
     * what it had. A null list forgets the holder. Nothing is kept while HushGram is paused or its
     * settings aren't ready, so nothing is drawn again then either.
     */
    private static void remember(Object holder, List<Object> summaries, Set<String> hidden) {
        if (holder == null || !Utils.settingsReady() || HushgramPause.isPaused()) return;
        synchronized (drawn) {
            for (Iterator<Drawn> it = drawn.iterator(); it.hasNext(); ) {
                Object kept = it.next().holder.get();
                if (kept == null || kept == holder) it.remove();
            }
            if (summaries != null) drawn.add(new Drawn(holder, summaries, hidden));
        }
    }

    /**
     * Asked after the hidden chats were saved. Each inbox holder Instagram still has, whose rows the
     * change adds a chat to or takes one from, is handed its list again without the chats hidden now
     * and draws its rows from it ({@link #redraw}). Nothing runs while HushGram is paused or its
     * settings aren't ready, for a holder whose rows stay the same (nothing hidden before or after,
     * the same chats hidden, or a chat that isn't in its list), or for one Instagram dropped. No
     * listener or timer is left behind: this runs once for each change and is done.
     */
    static void changed() {
        try {
            if (!Utils.settingsReady() || HushgramPause.isPaused()) return;
            List<Object> holders = new ArrayList<>();
            List<List<Object>> lists = new ArrayList<>();
            synchronized (drawn) {
                if (drawn.isEmpty()) return;
                Set<String> now = ids();
                for (Iterator<Drawn> it = drawn.iterator(); it.hasNext(); ) {
                    Drawn one = it.next();
                    Object holder = one.holder.get();
                    if (holder == null) {
                        it.remove();
                        continue;
                    }
                    if (one.hidden.equals(now)) continue;
                    List<Object> before = without(one.summaries, one.hidden, null);
                    List<Object> after = without(one.summaries, now, null);
                    one.hidden = now;
                    if (sameRows(before, after)) continue;
                    holders.add(holder);
                    lists.add(after);
                }
            }
            for (int i = 0; i < holders.size(); i++) {
                try {
                    redrawer.redraw(holders.get(i), lists.get(i));
                    HookStatus.counted(FamilyNames.MESSAGES_LOCK, REDRAWN);
                } catch (Throwable t) {
                    HookStatus.threw(FamilyNames.MESSAGES_LOCK, REDRAW, t);
                }
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, REDRAW, t);
        }
    }

    /** The same summaries in the same order. */
    private static boolean sameRows(List<Object> a, List<Object> b) {
        if (a == b) return true;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) return false;
        }
        return true;
    }

    /** Asks {@link #changed} on the main thread, where Instagram hands the inbox its lists. */
    private static void changedSoon() {
        Utils.runOnMainThreadNowOrLater(HiddenChats::changed);
    }

    /** Instagram dropped every inbox holder, as the garbage collector does once its screens are gone. */
    static void holdersDroppedForTests() {
        synchronized (drawn) {
            for (Drawn one : drawn) one.holder.clear();
        }
    }

    /** How many inbox holders are kept now. */
    static int keptForTests() {
        synchronized (drawn) {
            return drawn.size();
        }
    }

    // ---------------------------------------------------------------- search

    /**
     * The thread id of a chat in the inbox search's results, or null when it has none. The patch
     * writes the body at patch time, from Instagram's own result type, and a build without it
     * answers null, so no result is hidden. A result that is only a person has no thread, and stays.
     */
    public static String targetThreadId(Object result) {
        return null;
    }

    /**
     * Asked with the results Instagram's inbox search is about to turn into rows. A list with no
     * hidden chat in it comes back as it is, and otherwise a copy without the hidden ones does.
     * People who aren't a chat yet stay, so a hidden chat doesn't stop you finding its person.
     */
    public static List<Object> searchResults(List<Object> results) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            if (results == null || results.isEmpty()) return results;
            Set<String> hidden = ids();
            if (hidden.isEmpty()) return results;
            List<Object> shown = new ArrayList<>(results.size());
            for (Object result : results) {
                if (!inHiddenChat(result, hidden)) shown.add(result);
            }
            if (shown.size() == results.size()) return results;
            HookStatus.counted(FamilyNames.MESSAGES_LOCK, LEFT_OUT_SEARCH);
            return shown;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SEARCH, t);
            return results;
        }
    }

    /** The same for the messages the server found: the ones said in a hidden chat are left out. */
    public static ArrayList<Object> searchHits(ArrayList<Object> hits) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            if (hits == null || hits.isEmpty()) return hits;
            Set<String> hidden = ids();
            if (hidden.isEmpty()) return hits;
            ArrayList<Object> shown = new ArrayList<>(hits.size());
            for (Object hit : hits) {
                if (!inHiddenChat(hit, hidden)) shown.add(hit);
            }
            if (shown.size() == hits.size()) return hits;
            HookStatus.counted(FamilyNames.MESSAGES_LOCK, LEFT_OUT_SEARCH);
            return shown;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SEARCH, t);
            return hits;
        }
    }

    /**
     * Asked with the recent searches Instagram's inbox search lists before anything is typed. An
     * entry is either a chat or a wrapper holding one. A person or another kind of search has no
     * chat and stays. A list with no hidden chat in it comes back as it is, and otherwise a copy
     * without the hidden ones does.
     */
    public static List<Object> recents(List<Object> entries) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            if (entries == null || entries.isEmpty()) return entries;
            Set<String> hidden = ids();
            if (hidden.isEmpty()) return entries;
            List<Object> shown = new ArrayList<>(entries.size());
            for (Object entry : entries) {
                if (!recentInHiddenChat(entry, hidden)) shown.add(entry);
            }
            if (shown.size() == entries.size()) return entries;
            HookStatus.counted(FamilyNames.MESSAGES_LOCK, LEFT_OUT_RECENTS);
            return shown;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, RECENTS, t);
            return entries;
        }
    }

    /**
     * The entry is a chat on the list, or holds one in a field of Instagram's chat type. The
     * wrapper's field name changes with each build and its type doesn't. An entry that can't be
     * read stays.
     */
    private static boolean recentInHiddenChat(Object entry, Set<String> hidden) {
        try {
            if (entry == null) return false;
            Object target = isShareTarget(entry.getClass()) ? entry : heldShareTarget(entry);
            if (target == null) return false;
            String id = searchReader.threadId(target);
            return id != null && hidden.contains(id.trim());
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isShareTarget(Class<?> type) {
        for (; type != null; type = type.getSuperclass()) {
            if (SHARE_TARGET.equals(type.getName())) return true;
        }
        return false;
    }

    private static Object heldShareTarget(Object entry) throws IllegalAccessException {
        for (Class<?> type = entry.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || !isShareTarget(field.getType())) continue;
                field.setAccessible(true);
                Object value = field.get(entry);
                if (value != null) return value;
            }
        }
        return null;
    }

    /** The result is a chat on the list, or a message said in one. A result that can't be read isn't. */
    private static boolean inHiddenChat(Object result, Set<String> hidden) {
        try {
            if (result == null) return false;
            for (Class<?> type = result.getClass(); type != null; type = type.getSuperclass()) {
                String name = type.getName();
                if (SHARE_TARGET.equals(name)) {
                    String id = searchReader.threadId(result);
                    return id != null && hidden.contains(id.trim());
                }
                if (HIT_THREAD.equals(name) || HIT_MESSAGE.equals(name)) return carriesAny(result, type, hidden);
            }
        } catch (Throwable t) {
            // One result that can't be read stays in the search; the rest are still filtered.
        }
        return false;
    }

    /**
     * A message match keeps its chat's thread id in one of its text fields, under a name that
     * changes with each Instagram build. A thread id is a long number nothing else in the match
     * looks like, so any text field holding a hidden chat's is the chat.
     */
    private static boolean carriesAny(Object hit, Class<?> type, Set<String> hidden) throws IllegalAccessException {
        for (java.lang.reflect.Field field : type.getDeclaredFields()) {
            if (field.getType() != String.class || java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value = field.get(hit);
            if (value instanceof String && hidden.contains(((String) value).trim())) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- notifications

    /**
     * Some id a push carries (its deep link's chat id, its thread id or its thread IG id) is a
     * hidden chat's. Counted when it is, so the diagnostic report shows pushes that were dropped.
     */
    static boolean hides(Collection<String> pushIds) {
        try {
            Set<String> hidden = ids();
            if (hidden.isEmpty()) return false;
            for (String id : pushIds) {
                if (hidden.contains(id)) {
                    HookStatus.counted(FamilyNames.MESSAGES_LOCK, SILENCED);
                    return true;
                }
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, LIST, t);
        }
        return false;
    }

    // ---------------------------------------------------------------- the list

    /** The ids as last parsed, with the text they came from, so a list's worth of rows parse it once. */
    private static final class Parsed {
        final String raw;
        final Set<String> ids;

        Parsed(String raw, Set<String> ids) {
            this.raw = raw;
            this.ids = ids;
        }
    }

    private static volatile Parsed parsed;

    /** The thread ids hidden now, none while HushGram is paused; parsed again only when the list changed. */
    private static Set<String> ids() {
        if (!Utils.settingsReady()) return Collections.emptySet();
        String text = Settings.HIDDEN_CHATS.get();
        if (text.isEmpty()) return Collections.emptySet();
        Parsed kept = parsed;
        if (kept == null || !kept.raw.equals(text)) {
            Set<String> ids = new HashSet<>();
            for (ChatLocks.Chat chat : ChatList.parse(text)) ids.add(chat.id);
            kept = new Parsed(text, Collections.unmodifiableSet(ids));
            parsed = kept;
        }
        return kept.ids;
    }

    /** The hidden chats, oldest first, none while HushGram is paused. */
    public static List<ChatLocks.Chat> chats() {
        try {
            if (Utils.settingsReady()) return ChatList.parse(Settings.HIDDEN_CHATS.get());
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, LIST, t);
        }
        return new ArrayList<>();
    }

    /** The chats hidden as chosen, paused or not. The settings screen shows these. */
    public static List<ChatLocks.Chat> saved() {
        try {
            if (Utils.settingsReady()) return ChatList.parse(Settings.HIDDEN_CHATS.savedValue());
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, LIST, t);
        }
        return new ArrayList<>();
    }

    /** The chat with [id] is hidden now. */
    public static boolean listed(String id) {
        return ChatList.contains(chats(), id);
    }

    /** The chat with [id] is on the chosen list, paused or not. */
    public static boolean savedListed(String id) {
        return ChatList.contains(saved(), id);
    }

    /** The last chat opened, if it isn't hidden. */
    public static ChatLocks.Chat lastOpened() {
        return ChatLocks.lastOpenedUnless(HiddenChats::savedListed);
    }

    /** Hides a chat, or renames it on the list, and the inbox shows it. */
    public static void add(String id, String name) {
        Settings.HIDDEN_CHATS.save(ChatList.added(saved(), id, name));
        changedSoon();
    }

    /** Brings a chat back, in the inbox too. */
    public static void remove(String id) {
        Settings.HIDDEN_CHATS.save(ChatList.removed(saved(), id));
        changedSoon();
    }

    /** Back to how a fresh start finds it. */
    static void resetForTests() {
        reader = HiddenChats::threadId;
        searchReader = HiddenChats::targetThreadId;
        redrawer = HiddenChats::redraw;
        synchronized (drawn) {
            drawn.clear();
        }
    }
}
