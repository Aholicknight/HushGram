/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.text.Layout;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The settings page as grouped cards (#130): each category's page opens with a one-line intro,
 * rows share rounded cards under the section titles, every row leads with a decorative icon, and
 * only a row that opens something ends in a chevron. Text still wraps at twice the size.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, qualifiers = "w320dp-h640dp-xhdpi")
@SuppressWarnings("deprecation")
public class SettingsCardsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        Settings.CATEGORY_PAGES.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        RuntimeEnvironment.setFontScale(1f);
        SettingsEntry.onClosedByUser();
    }

    private void open(boolean pages, EnumSet<PatchFamily> build) throws Exception {
        Settings.CATEGORY_PAGES.save(pages);
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }

    private List<Preference> onScreen() {
        PreferenceScreen screen = page.getPreferenceScreen();
        List<Preference> shown = new ArrayList<>();
        for (int i = 0; i < screen.getPreferenceCount(); i++) shown.add(screen.getPreference(i));
        return shown;
    }

    private List<Preference> categoryRows() {
        List<Preference> rows = new ArrayList<>();
        for (Preference item : onScreen()) {
            if (item.getKey() != null && item.getKey().startsWith(HushgramPreferenceFragment.CATEGORY_ROW_KEY)) rows.add(item);
        }
        return rows;
    }

    private Preference categoryRow(String title) {
        for (Preference row : categoryRows()) if (title.contentEquals(row.getTitle())) return row;
        throw new AssertionError("no row for " + title);
    }

    @SuppressWarnings("unchecked")
    private <T extends Preference> T titled(String title) {
        for (Preference item : onScreen()) {
            if (title.contentEquals(item.getTitle())) return (T) item;
            if (!(item instanceof PreferenceCategory)) continue;
            PreferenceCategory group = (PreferenceCategory) item;
            for (int i = 0; i < group.getPreferenceCount(); i++) {
                if (title.contentEquals(group.getPreference(i).getTitle())) return (T) group.getPreference(i);
            }
        }
        throw new AssertionError("nothing titled " + title);
    }

    private void tap(Preference row) {
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
        ShadowLooper.idleMainLooper();
    }

    /** [wanted] drawn by the page's own adapter, at the test screen's 320 dp width. */
    private View render(Preference wanted) {
        ListAdapter adapter = page.getPreferenceScreen().getRootAdapter();
        for (int i = 0; i < adapter.getCount(); i++) {
            if (adapter.getItem(i) != wanted) continue;
            View row = adapter.getView(i, null, new FrameLayout(controller.get()));
            int width = Math.round(320 * row.getResources().getDisplayMetrics().density);
            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            row.layout(0, 0, width, row.getMeasuredHeight());
            return row;
        }
        throw new AssertionError("not on the page: " + wanted.getTitle());
    }

    /** The rounded surface a row draws its card with. */
    private static GradientDrawable card(View row) {
        Drawable background = row.getBackground();
        if (background instanceof RippleDrawable) background = ((RippleDrawable) background).getDrawable(0);
        assertTrue("no inset card behind the row: " + background, background instanceof InsetDrawable);
        Drawable surface = ((InsetDrawable) background).getDrawable();
        assertTrue(surface instanceof GradientDrawable);
        return (GradientDrawable) surface;
    }

    private static boolean roundedTop(GradientDrawable card) {
        float[] radii = card.getCornerRadii();
        return radii[0] > 0 && radii[2] > 0;
    }

    private static boolean roundedBottom(GradientDrawable card) {
        float[] radii = card.getCornerRadii();
        return radii[4] > 0 && radii[6] > 0;
    }

    private static boolean hasChevron(View row) {
        View chevron = row.findViewWithTag(ScreenColors.CHEVRON);
        return chevron != null && chevron.getVisibility() == View.VISIBLE
                && ((View) chevron.getParent()).getVisibility() == View.VISIBLE;
    }

    @Test public void everyCategoryPageOpensWithItsIntro() throws Exception {
        open(true, EnumSet.allOf(PatchFamily.class));
        List<String> titles = new ArrayList<>();
        for (Preference row : categoryRows()) titles.add(String.valueOf(row.getTitle()));
        assertTrue("too few categories to mean anything: " + titles, titles.size() >= 20);
        assertNull("the list of categories has no intro", page.getPreferenceScreen()
                .findPreference(HushgramPreferenceFragment.INTRO_KEY));
        for (String title : titles) {
            tap(categoryRow(title));
            List<Preference> shown = onScreen();
            int at = -1;
            for (int i = 0; i < shown.size(); i++) {
                if (shown.get(i) instanceof HushgramPreferenceFragment.Intro) at = i;
            }
            assertTrue(title + " opened without an intro: " + shown, at >= 0);
            Preference intro = shown.get(at);
            assertTrue(title + "'s section sits right under its intro",
                    at + 1 < shown.size() && shown.get(at + 1) instanceof HushgramPreferenceFragment.Heading
                            && title.contentEquals(shown.get(at + 1).getTitle()));
            String text = String.valueOf(intro.getTitle());
            assertEquals(((HushgramPreferenceFragment.Heading) shown.get(at + 1)).intro, text);
            assertFalse(title + "'s intro is one sentence or two, not a paragraph", text.length() > 90);
            assertFalse("the intro is text, not a control", intro.isSelectable());
            assertTrue(page.closeCategory());
            ShadowLooper.idleMainLooper();
            assertNull("Back takes the intro off with the page", page.getPreferenceScreen()
                    .findPreference(HushgramPreferenceFragment.INTRO_KEY));
        }
    }

    @Test public void theIntroLeavesForASearchAndTheLongList() throws Exception {
        open(true, EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.FOLLOWING_FEED));
        tap(categoryRow("Feed"));
        PreferenceScreen screen = page.getPreferenceScreen();
        assertNotNull(screen.findPreference(HushgramPreferenceFragment.INTRO_KEY));
        page.searchSettings("Hide ads");
        assertNull("a search shows every category's matches, under no page's intro",
                screen.findPreference(HushgramPreferenceFragment.INTRO_KEY));
        page.searchSettings("");
        assertNotNull("clearing the search goes back to the page", screen.findPreference(HushgramPreferenceFragment.INTRO_KEY));
        Settings.CATEGORY_PAGES.save(false);
        page.searchSettings("");
        assertNull("the long list has no page to introduce", screen.findPreference(HushgramPreferenceFragment.INTRO_KEY));
    }

    @Test public void aSectionsRowsShareOneRoundedCard() throws Exception {
        open(false, EnumSet.of(PatchFamily.FOLLOWING_FEED));
        Preference first = page.findPreference(Settings.START_ON_FOLLOWING.key);
        Preference middle = page.findPreference(Settings.ONLY_FOLLOWING.key);
        Preference last = page.findPreference(Settings.LOGO_ON_FOLLOWING.key);
        assertSame("one section holds all three", first.getParent(), last.getParent());
        assertTrue(ScreenColors.opensCard(first));
        assertFalse(ScreenColors.closesCard(first));
        assertFalse(ScreenColors.opensCard(middle));
        assertFalse(ScreenColors.closesCard(middle));
        assertFalse(ScreenColors.opensCard(last));
        assertTrue(ScreenColors.closesCard(last));

        GradientDrawable top = card(render(first));
        assertTrue(roundedTop(top));
        assertFalse(roundedBottom(top));
        GradientDrawable inside = card(render(middle));
        assertFalse(roundedTop(inside));
        assertFalse(roundedBottom(inside));
        GradientDrawable bottom = card(render(last));
        assertFalse(roundedTop(bottom));
        assertTrue(roundedBottom(bottom));
        assertEquals("the card takes the page's colours", ScreenColors.DEFAULT.card, top.getColor().getDefaultColor());
    }

    @Test public void theCategoryRowsShareOneCardAsASectionsRowsDo() throws Exception {
        open(true, EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.FOLLOWING_FEED, PatchFamily.NOTIFICATION_GROUPS));
        List<Preference> rows = categoryRows();
        Preference entry = categoryRow("Settings entry");
        Preference backup = categoryRow("Settings backup");
        Preference about = categoryRow("About");
        assertSame("the list starts with Settings entry", entry, rows.get(0));
        assertTrue(ScreenColors.opensCard(entry));
        assertFalse(ScreenColors.closesCard(entry));
        for (Preference row : rows.subList(1, rows.indexOf(backup))) {
            assertFalse(row.getTitle() + " sits inside the card", ScreenColors.opensCard(row) || ScreenColors.closesCard(row));
        }
        assertTrue("Pause and diagnostics, a section of its own, comes next", ScreenColors.closesCard(backup));
        assertTrue(ScreenColors.opensCard(about));
        assertFalse("Support HushGram shares About's card", ScreenColors.closesCard(about));
        Preference support = page.findPreference(HushgramPreferenceFragment.SUPPORT_ROW_KEY);
        assertFalse(ScreenColors.opensCard(support));
        assertTrue(ScreenColors.closesCard(support));
        Preference status = onScreen().get(0);
        assertEquals("HushGram is on", String.valueOf(status.getTitle()));
        assertTrue("the status card stays a card of its own", ScreenColors.opensCard(status) && ScreenColors.closesCard(status));

        assertTrue(roundedTop(card(render(entry))));
        assertFalse(roundedBottom(card(render(entry))));
        assertFalse(roundedTop(card(render(rows.get(1)))));
        assertTrue(roundedBottom(card(render(backup))));
    }

    @Test public void onlyARowThatOpensSomethingEndsInAChevron() throws Exception {
        open(true, EnumSet.of(PatchFamily.HIDE_ADS));
        assertTrue("a category row opens its page", hasChevron(render(categoryRow("Ads and privacy"))));
        assertFalse("the status card only reports", hasChevron(render(onScreen().get(0))));
        tap(categoryRow("Settings entry"));
        assertTrue("a list row opens its choices",
                hasChevron(render(page.findPreference(Settings.NAVIGATION_SETTINGS_TARGET.key))));
        assertFalse("a switch says what it does with its switch",
                hasChevron(render(page.findPreference(Settings.CATEGORY_PAGES.key))));
        assertFalse("the intro is text", hasChevron(render(page.findPreference(HushgramPreferenceFragment.INTRO_KEY))));
        assertTrue(page.closeCategory());
        ShadowLooper.idleMainLooper();
        tap(categoryRow("About"));
        assertFalse("Version only reports", hasChevron(render(titled("Version"))));
        assertTrue("Licenses opens a dialog", hasChevron(render(titled("Licenses"))));
    }

    @Test public void everyRowLeadsWithAnIconAScreenReaderSkips() throws Exception {
        open(false, EnumSet.allOf(PatchFamily.class));
        int rows = 0;
        for (Preference item : onScreen()) {
            // The search field draws its magnifier inside the field, not as a row's icon.
            if ("hushgram_settings_search".equals(item.getKey())) continue;
            if (!(item instanceof PreferenceCategory)) {
                assertNotNull(item.getTitle() + " has no icon", item.getIcon());
                continue;
            }
            PreferenceCategory group = (PreferenceCategory) item;
            for (int i = 0; i < group.getPreferenceCount(); i++, rows++) {
                Preference row = group.getPreference(i);
                assertNotNull(group.getTitle() + " > " + row.getTitle() + " has no icon", row.getIcon());
            }
        }
        assertTrue("too few rows to mean anything: " + rows, rows > 100);

        Preference toggle = page.findPreference(Settings.HIDE_ADS.key);
        View row = render(toggle);
        ImageView icon = row.findViewById(android.R.id.icon);
        assertNotNull(icon);
        assertEquals(View.VISIBLE, icon.getVisibility());
        assertEquals("the icon is decoration", View.IMPORTANT_FOR_ACCESSIBILITY_NO, icon.getImportantForAccessibility());
        assertNull(icon.getContentDescription());
        AccessibilityNodeInfo node = row.createAccessibilityNodeInfo();
        assertEquals("the switch still reads its name and explanation, and nothing else",
                toggle.getTitle() + "\n" + toggle.getSummary(), String.valueOf(node.getContentDescription()));
        node.recycle();
    }

    @Test public void aCategoryRowCarriesItsSectionsIconAndReadsAsBefore() throws Exception {
        open(true, EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.FOLLOWING_FEED));
        Preference feed = categoryRow("Feed");
        assertNotNull(feed.getIcon());
        View row = render(feed);
        ImageView icon = row.findViewById(android.R.id.icon);
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, icon.getImportantForAccessibility());
        AccessibilityNodeInfo node = row.createAccessibilityNodeInfo();
        assertEquals(Button.class.getName(), String.valueOf(node.getClassName()));
        assertEquals(feed.getTitle() + "\n" + feed.getSummary(), String.valueOf(node.getContentDescription()));
        node.recycle();
    }

    @Test public void aRowsIconFollowsTheWordsOfItsKey() {
        String fallback = SettingsIcons.FEED;
        assertSame(SettingsIcons.SEARCH, SettingsIcons.forKey(Settings.DONT_SAVE_RECENT_SEARCHES.key, fallback));
        assertSame(SettingsIcons.DOWNLOADS, SettingsIcons.forKey(Settings.DOWNLOAD_REELS.key, fallback));
        assertSame(SettingsIcons.LOCK, SettingsIcons.forKey(Settings.LOCK_MESSAGES.key, fallback));
        assertSame(SettingsIcons.LIKE, SettingsIcons.forKey(Settings.SHOW_HIDDEN_LIKE_COUNTS.key, fallback));
        assertSame(SettingsIcons.HIDDEN, SettingsIcons.forKey(Settings.VIEW_STORIES_ANONYMOUSLY.key, fallback));
        assertSame("whole words only: block isn't lock", SettingsIcons.STORIES,
                SettingsIcons.forKey(Settings.BLOCK_STORY_AUTO_ADVANCE.key, fallback));
        assertSame(fallback, SettingsIcons.forKey(Settings.HIDE_SUGGESTED_POSTS.key, fallback));
        assertSame(fallback, SettingsIcons.forKey(null, fallback));
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void textWrapsAndTheCardsGrowAtTwiceTheSize() throws Exception {
        RuntimeEnvironment.setFontScale(2f);
        open(true, EnumSet.of(PatchFamily.HIDE_ADS));
        Preference adsRow = categoryRow("Ads and privacy");
        assertWhole(render(adsRow));
        tap(adsRow);
        Preference intro = page.findPreference(HushgramPreferenceFragment.INTRO_KEY);
        View introView = render(intro);
        TextView introText = introView.findViewById(android.R.id.title);
        assertTrue("the intro wraps at twice the size", introText.getLayout().getLineCount() > 1);
        assertFalse("the intro is read as text, not as a heading", introView.isAccessibilityHeading());
        assertNull("the intro has no card", introView.getBackground());
        assertWhole(introView);
        assertWhole(render(page.findPreference(Settings.HIDE_ADS.key)));
    }

    /** Every line of [row]'s text is drawn in full, inside the row. */
    private static void assertWhole(View row) {
        int texts = 0;
        for (int which : new int[]{android.R.id.title, android.R.id.summary}) {
            TextView text = row.findViewById(which);
            if (text == null || text.getVisibility() != View.VISIBLE || text.getText().length() == 0) continue;
            texts++;
            Layout layout = text.getLayout();
            assertNotNull(text.getText() + " was never laid out", layout);
            for (int line = 0; line < layout.getLineCount(); line++) {
                assertEquals(text.getText() + " line " + line + " is cut short", 0, layout.getEllipsisCount(line));
            }
            assertTrue(text.getText() + " is taller than its view",
                    layout.getHeight() <= text.getHeight() - text.getTotalPaddingTop() - text.getTotalPaddingBottom() + 1);
            int bottom = text.getHeight();
            for (View at = text; at != row; at = (View) at.getParent()) bottom += at.getTop();
            assertTrue(text.getText() + " runs past the bottom of its row", bottom <= row.getHeight());
        }
        assertTrue("the row drew no text", texts > 0);
    }
}
