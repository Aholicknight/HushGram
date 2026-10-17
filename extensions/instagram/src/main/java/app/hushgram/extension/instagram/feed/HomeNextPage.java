/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.view.View;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Asks Home for its next page right away when Hide suggested posts, or any other switch that takes
 * things out of Home, leaves a page of For you short (#52, #28).
 *
 * <p>On an account whose For you pages each carry about one followed post and four suggested ones,
 * Hide suggested posts leaves a page holding one post, and Instagram's loading row sits right under
 * it. Instagram asks for the next page when the scroll nears that row, so Home grew one post at a
 * time. Now, when a page of Home's response keeps fewer than {@link #SHORT_PAGE} items after the
 * switches took some out ({@link #pageParsed}), the next build of Home's list
 * ({@link #buildStarts}) posts a request for the next page to the main thread, to run once the
 * build is done. It's the call Instagram makes when that loading row comes into view, made on Home's
 * own load more policy, which still checks for itself that the feed is loaded and has a next page.
 * It keeps asking while pages come back short, {@link #CHAIN_CAP} times in a row at most, and a
 * page that keeps enough starts the count over.
 *
 * <p>Past that cap it goes on asking, up to {@link #END_CAP} in a row, while Home's list sits at its
 * end ({@link #atEnd}): nothing else would ask there, since Instagram asks on a scroll and as its
 * loading row comes into view, and a list that can't scroll further shows a row that's been in view
 * all along (#28). A short page no build of Home's list follows, one the switches emptied that
 * changes nothing Home draws, or one whose build ran while it was still loading, is decided by a
 * check {@link #SETTLE_MS} later ({@link #settle}) with the latest build that read its feed.
 *
 * <p>A page the switches emptied leaves Home empty, and {@link FeedSuggestions#feedEnded} ends it so
 * Instagram draws its empty feed card rather than a loading placeholder nothing would ever fill. It
 * still asks for For you's next page then, within the same cap: the card shows until that page comes,
 * and the posts it brings take its place. Before, nothing asked, and the card stayed until something
 * else loaded Home (#28). An account that follows nobody reaches suggested posts
 * only, which it doesn't ask for while Hide suggested posts is on, so its Home stays on the card
 * (#105).
 *
 * <p>It never asks while a page is loading, once the feed has no next page, or for a feed that isn't
 * For you, so Following and Favorites page as they did. For you's suggested posts past the ones from
 * accounts you follow page from a source of their own, which it asks for only while Hide suggested
 * posts is off ({@link #asksFor}). Hide videos took a page of them down to a post or two and the
 * loading row under them never moved, since Instagram asks only on a scroll (#28). The Older Posts
 * page has builders of its own and is left alone. It fails open: with every switch that took
 * something out of Home off ({@link FeedSuggestions#switchesTookOut}), HushGram paused, the settings
 * not read yet, a stub the patch couldn't fill or anything thrown, Home waits for the scroll as
 * Instagram has it.
 */
public final class HomeNextPage {
    /** A page of Home's response is short when it keeps fewer items than this. */
    static final int SHORT_PAGE = 5;

    /** The most next pages asked for in a row while pages keep coming back short. */
    static final int CHAIN_CAP = 3;

    /** The most next pages asked for in a row while Home's list sits at its end, where no scroll would ask (#28). */
    static final int END_CAP = 10;

    /** How long after a short page was parsed a build of Home may still ask for the next one, in nanoseconds. */
    static final long WINDOW_NANOS = 10_000_000_000L;

    /** How long after a short page {@link #settle} checks whether a build decided it, in milliseconds. */
    static final long SETTLE_MS = 1_500L;

    /** For you's paging source, as Instagram names the feed Home's switcher calls ALL. */
    static final String FOR_YOU = "homecoming_all";

    /** The paging source of the suggested posts For you pages in once the posts from accounts you follow run out. */
    static final String SUGGESTED = "feed_recs";

    /** The report's count of next pages asked for. */
    static final String ASKED = "asked for the next page after a short page";

    /** The report's count of chains that reached their cap with pages still short. */
    static final String CAPPED = "chain stopped at its cap";

    /** How many pages of Home's response that held items have been parsed. Tests clear it. */
    static volatile long pages;

    /** The number ({@link #pages}) of the latest page if it came back short, 0 otherwise. Tests clear it. */
    static volatile long shortPage;

    /** When that page was parsed, by {@link #clock}. Tests clear it. */
    static volatile long shortPageAt;

    /** The number of the short page a build has already decided about. Tests clear it. */
    static volatile long decided;

    /** Next pages asked for in a row while pages came back short. Tests clear it. */
    static volatile int chain;

    /** Set when the next page parsed is the answer to a request this class made. Tests clear it. */
    static volatile boolean waitingForOurs;

    /** Set once the report has counted {@link #CAPPED} for the current chain. Tests clear it. */
    static volatile boolean capCounted;

    /** Set while a request is posted to the main thread and hasn't run yet. Tests clear it. */
    static volatile boolean posted;

    /** The latest build of Home's list since a request was posted, which the request reads. Tests clear it. */
    @Nullable
    static volatile Build latest;

    /** The latest build of Home's list that read its feed, which {@link #settle} asks with. Tests clear it. */
    @Nullable
    static volatile Build home;

    /** The build of Home's list running on this thread, until it hands over its feed in {@link #feedRead}. */
    private static final ThreadLocal<Build> BUILDING = new ThreadLocal<>();

    /**
     * One build of Home's list: its adapter, and the feed it reads its "no next page" flag from. Both
     * are held weakly: a build that never reaches its feed stays on its thread until the next one,
     * {@link #home} keeps the latest that did, and neither may keep Home's fragment or feed alive.
     */
    static final class Build {
        final WeakReference<Object> adapter;
        @Nullable private volatile WeakReference<Object> feed;

        Build(Object adapter) {
            this.adapter = new WeakReference<>(adapter);
        }

        /** Notes the feed this build read its flag from. */
        void read(@Nullable Object feed) {
            this.feed = feed == null ? null : new WeakReference<>(feed);
        }

        /** The feed this build read its flag from, or null when it read none or the feed is gone. */
        @Nullable
        Object feed() {
            WeakReference<Object> read = feed;
            return read == null ? null : read.get();
        }
    }

    /** The time a short page's age is measured by. Tests stand in. */
    static volatile LongSupplier clock = System::nanoTime;

    /** Posts the request to the main thread. Tests stand in. */
    static volatile Consumer<Runnable> poster = Utils::runOnMainThread;

    /** Reads an adapter's load more policy, as {@link #policyOf} does. Tests stand in. */
    static volatile Function<Object, Object> policies = HomeNextPage::policyOf;

    /** Reads a feed's paging source, as {@link #sourceOf} does. Tests stand in. */
    static volatile Function<Object, String> sources = HomeNextPage::sourceOf;

    /** Asks a policy whether a page is loading, as {@link #loadingNow} does. Tests stand in. */
    static volatile ToIntFunction<Object> loading = HomeNextPage::loadingNow;

    /** Asks a policy whether the feed has a next page, as {@link #moreLeft} does. Tests stand in. */
    static volatile ToIntFunction<Object> more = HomeNextPage::moreLeft;

    /** Asks a policy for the next page, as {@link #askNextPage} does. Tests stand in. */
    static volatile BiConsumer<Object, Object> asker = HomeNextPage::askNextPage;

    /** Tells whether an adapter's list sits at its end, as {@link #atEnd} does. Tests stand in. */
    static volatile ToIntFunction<Object> ends = HomeNextPage::atEnd;

    /** Posts {@link #settle} to the main thread {@link #SETTLE_MS} later. Tests stand in. */
    static volatile Consumer<Runnable> later = HomeNextPage::settleLater;

    private HomeNextPage() {
    }

    /**
     * Called by {@link FeedSuggestions#homePageParsed} for each page of Home's response that held
     * items: [read] of them, [lost] to the switches. A page that keeps {@link #SHORT_PAGE} or more
     * ends the chain. A shorter one the switches took items out of becomes the page the next build
     * decides about, and starts a new chain unless it answers a request made here, and
     * {@link #settle} checks on it later. A short page the switches took nothing out of is
     * Instagram's own and is left to it. Never throws.
     */
    static void pageParsed(int read, int lost) {
        try {
            boolean ours = waitingForOurs;
            waitingForOurs = false;
            long number = ++pages;
            if (read - lost >= SHORT_PAGE) {
                chain = 0;
                capCounted = false;
                shortPage = 0;
                return;
            }
            if (lost <= 0) {
                shortPage = 0;
                return;
            }
            if (!ours) {
                chain = 0;
                capCounted = false;
            }
            shortPageAt = clock.getAsLong();
            shortPage = number;
            Logger.printDebug(() -> "Home next page: a page kept " + (read - lost) + " of its " + read + " items");
            later.accept(() -> settle(number));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "home short page", failure);
        }
    }

    /**
     * Called by {@link FeedSuggestions#homePageParsed} for a page of Home's response with no items.
     * It still answers a request made here, so the next short page starts a chain of its own instead
     * of continuing this one. Never throws.
     */
    static void emptyPageParsed() {
        waitingForOurs = false;
    }

    /**
     * Injected first thing in Home's list builder (MainfeedAdapter.buildModels), with the adapter.
     * Notes this build on its thread for {@link #feedRead}. While a short page waits for a decision,
     * it also posts {@link #run} to the main thread, once, so the request is made after the build.
     * Never throws.
     */
    public static void buildStarts(Object adapter) {
        if (adapter == null) return;
        try {
            Build build = new Build(adapter);
            BUILDING.set(build);
            long page = shortPage;
            if (page == 0 || page == decided) return;
            latest = build;
            if (posted) return;
            posted = true;
            poster.accept(HomeNextPage::run);
        } catch (Throwable failure) {
            posted = false;
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "home next page", failure);
        }
    }

    /**
     * Called by {@link FeedSuggestions#homeFeedRead} with the feed the adapter reads its "no next
     * page" flag from, so the build on this thread knows its feed and becomes {@link #home}. Never
     * throws.
     */
    static void feedRead(@Nullable Object feed) {
        try {
            Build build = BUILDING.get();
            if (build == null) return;
            BUILDING.remove();
            build.read(feed);
            if (feed != null) home = build;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "home next page feed", failure);
        }
    }

    /** The posted request: decides about the short page for the latest build. */
    static void run() {
        posted = false;
        Build build = latest;
        latest = null;
        if (build != null) askIfShort(build);
    }

    /**
     * Posted {@link #SETTLE_MS} after short page [page] was parsed, for a page no build of Home's
     * list decided: one the switches emptied changes nothing Home draws, so Instagram may not build
     * again, and a build that ran while the page was still loading left it waiting. Asks with
     * {@link #home}, the latest build that read its feed, and checks again while the page waits,
     * within {@link #WINDOW_NANOS}. Never throws.
     */
    static void settle(long page) {
        if (shortPage != page || decided == page) return;
        try {
            Build build = home;
            if (build != null && !posted) askIfShort(build);
            if (shortPage != page || decided == page) return;
            long age = clock.getAsLong() - shortPageAt;
            if (age >= 0 && age <= WINDOW_NANOS) later.accept(() -> settle(page));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "home next page settle", failure);
        }
    }

    /** Posts [check] to the main thread {@link #SETTLE_MS} from now. */
    static void settleLater(Runnable check) {
        Utils.runOnMainThreadDelayed(check, SETTLE_MS);
    }

    /**
     * Asks [build]'s load more policy for the next page when the latest short page is still waiting
     * and every check passes. A page loading now, a build that never read its feed, or a build of
     * another feed's list (Following's, say, which keeps its own adapter) leaves the short page for a
     * later build within {@link #WINDOW_NANOS}. Every other way out decides it, so it's asked about
     * once.
     */
    static void askIfShort(Build build) {
        long page = shortPage;
        if (page == 0 || page == decided) return;
        try {
            if (!Utils.settingsReady() || !FeedSuggestions.switchesTookOut()) {
                decided = page;
                return;
            }
            long age = clock.getAsLong() - shortPageAt;
            if (age < 0 || age > WINDOW_NANOS) {
                decided = page;
                return;
            }
            Object feed = build.feed();
            if (feed == null) {
                Logger.printDebug(() -> "Home next page: the build never read its feed, so the short page waits");
                return;
            }
            String source = sources.apply(feed);
            if (!asksFor(source)) {
                Logger.printDebug(() -> "Home next page: a list paging " + source + ", which isn't For you, doesn't ask");
                return;
            }
            Object adapter = build.adapter.get();
            Object policy = adapter == null ? null : policies.apply(adapter);
            if (policy == null) {
                decided = page;
                Logger.printDebug(() -> "Home next page: no load more policy to ask");
                return;
            }
            int busy = loading.applyAsInt(policy);
            if (busy == 1) {
                Logger.printDebug(() -> "Home next page: a page is loading, so the short page waits");
                return;
            }
            int left = busy == 0 ? more.applyAsInt(policy) : -1;
            if (left != 1) {
                decided = page;
                Logger.printDebug(() -> "Home next page: " + (left == 0 ? "Home has no next page" : "can't tell whether Home can load a page"));
                return;
            }
            decided = page;
            boolean atEnd = chain >= CHAIN_CAP && chain < END_CAP && ends.applyAsInt(adapter) == 1;
            if (chain >= CHAIN_CAP && !atEnd) {
                if (!capCounted) {
                    capCounted = true;
                    HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, CAPPED);
                    int asked = chain;
                    Logger.printDebug(() -> "Home next page: " + asked + " short pages in a row, so Home waits for the scroll");
                }
                return;
            }
            chain++;
            waitingForOurs = true;
            asker.accept(policy, new HashMap<String, String>());
            HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, ASKED);
            Logger.printDebug(() -> "Home next page: asked for the next page after a short one (" + chain + " in a row"
                    + (atEnd ? ", Home's list at its end)" : ")"));
        } catch (Throwable failure) {
            decided = page;
            waitingForOurs = false;
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "home next page", failure);
        }
    }

    /** Whether [source], a feed's paging source, is For you's. For you's first pages may name none. */
    static boolean forYou(@Nullable String source) {
        return source == null || source.isEmpty() || FOR_YOU.equals(source);
    }

    /**
     * Whether a short page of a list paging [source] gets its next page asked for: For you's always,
     * and the suggested posts For you pages in past the posts from accounts you follow
     * ({@link #SUGGESTED}) while Hide suggested posts is off, so they show and a post switch left
     * the page short (#28). With it on, every post on those pages goes, so asking brings nothing.
     * Callers check {@link Utils#settingsReady} first.
     */
    static boolean asksFor(@Nullable String source) {
        if (forYou(source)) return true;
        return SUGGESTED.equals(source) && !Settings.HIDE_SUGGESTED_POSTS.get();
    }

    /**
     * Whether [adapter]'s list sits at its end: 1 when it's on screen and can't scroll further down,
     * 0 when it can or isn't on screen, and -1 when it can't be told. Runs on the main thread, from
     * {@link #run} and {@link #settle}.
     */
    static int atEnd(Object adapter) {
        try {
            return endOf(listOf(adapter));
        } catch (Throwable failure) {
            // Instagram's own read of the list throws once Home's fragment is gone.
            Logger.printDebug(() -> "Home next page: Home's list can't be read: " + failure);
            return -1;
        }
    }

    /** {@link #atEnd}'s answer for [list], the view {@link #listOf} read. */
    static int endOf(@Nullable Object list) {
        if (!(list instanceof View)) return -1;
        View view = (View) list;
        if (!view.isShown()) return 0;
        return view.canScrollVertically(1) ? 0 : 1;
    }

    /**
     * Home's list, the view the adapter draws into, read through the adapter's host the way
     * Instagram reads it for its most visible post. The patch writes the body. Null as built.
     */
    public static Object listOf(Object adapter) {
        return null;
    }

    /**
     * Home's load more policy, from the adapter's field holding it. The patch writes the body. Null
     * as built.
     */
    public static Object policyOf(Object adapter) {
        return null;
    }

    /**
     * The paging source of the feed the adapter reads its flag from, the one Instagram compares
     * with Following's to apply Following's end card rule. The patch writes the body. Null as
     * built, which a build without the body never gets this far to read.
     */
    public static String sourceOf(Object feed) {
        return null;
    }

    /**
     * Whether the policy has a page loading: 1 when it does, 0 when it doesn't and -1 when it can't
     * be told. The patch writes the body, which asks the question both isLoading() and the call for
     * the next page ask.
     */
    public static int loadingNow(Object policy) {
        return -1;
    }

    /**
     * Whether the policy's feed has a next page: 1 when it does, 0 when it doesn't and -1 when it
     * can't be told. The patch writes the body, which asks the question isLoading() and the load
     * more row's show check share.
     */
    public static int moreLeft(Object policy) {
        return -1;
    }

    /**
     * Asks the policy for the next page with [params], the request's extra parameters, as Instagram
     * does when the loading row comes into view. The patch writes the body. Does nothing as built.
     */
    public static void askNextPage(Object policy, Object params) {
    }
}
