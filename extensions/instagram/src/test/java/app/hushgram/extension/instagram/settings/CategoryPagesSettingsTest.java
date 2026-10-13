/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
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
import org.robolectric.shadows.ShadowLooper;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Open categories as pages: a list of categories, one page each, Back to the list, and search across all of them. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, qualifiers = "w320dp-h480dp-mdpi")
@SuppressWarnings("deprecation")
public class CategoryPagesSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;
    private SettingsDialog dialog;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.CATEGORY_PAGES.resetToDefault();
        Settings.START_ON_FOLLOWING.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.FOLLOWING_FEED,
                PatchFamily.NOTIFICATION_GROUPS);
    }

    @After public void close() throws Exception {
        if (dialog != null) dialog.dismissAllowingStateLoss();
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.CATEGORY_PAGES.resetToDefault();
        Settings.START_ON_FOLLOWING.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        SettingsEntry.onClosedByUser();
    }

    private void open() throws Exception {
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }

    /** The titles of the categories and the category rows on the screen itself, in order. */
    private List<String> shown(Class<? extends Preference> type) {
        PreferenceScreen screen = page.getPreferenceScreen();
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference item = screen.getPreference(i);
            boolean row = item.getKey() != null && item.getKey().startsWith(HushgramPreferenceFragment.CATEGORY_ROW_KEY);
            if (type == PreferenceCategory.class ? item instanceof PreferenceCategory : row) {
                titles.add(String.valueOf(item.getTitle()));
            }
        }
        return titles;
    }

    private Preference categoryRow(String title) {
        PreferenceScreen screen = page.getPreferenceScreen();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference item = screen.getPreference(i);
            if (item.getKey() != null && item.getKey().startsWith(HushgramPreferenceFragment.CATEGORY_ROW_KEY)
                    && title.contentEquals(item.getTitle())) return item;
        }
        throw new AssertionError("no row for " + title + " in " + shown(Preference.class));
    }

    private void tap(Preference row) {
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
        ShadowLooper.idleMainLooper();
    }

    private void openDialog() throws Exception {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        dialog = new SettingsDialog();
        dialog.show(controller.get().getFragmentManager(), "category_pages");
        controller.get().getFragmentManager().executePendingTransactions();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        page = (HushgramPreferenceFragment) dialog.getChildFragmentManager()
                .findFragmentById(SettingsDialog.CONTAINER_ID);
        layoutPage();
    }

    private void layoutPage() {
        // PreferenceGroupAdapter applies hierarchy changes on its next main-thread turn.
        ShadowLooper.idleMainLooper();
        // Lay out the actual dialog viewport. Measuring only its child page at a different
        // height lets the attached window's next traversal undo the scroll under test.
        View root = dialog.getView();
        float density = root.getResources().getDisplayMetrics().density;
        int width = Math.round(320 * density), height = Math.round(480 * density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        ShadowLooper.idleMainLooper();
    }

    @Test public void onToStartTheCategoriesAreListed() throws Exception {
        open();
        SwitchPreference pages = (SwitchPreference) page.findPreference(Settings.CATEGORY_PAGES.key);
        assertNotNull(pages);
        assertEquals("Open categories as pages", pages.getTitle().toString());
        assertTrue(pages.isChecked());
        assertTrue(shown(Preference.class).contains("Feed"));
    }

    @Test public void theTopButtonSwitchesBetweenListAndCategories() throws Exception {
        open();
        Preference button = page.findPreference("hushgram_view_toggle");
        assertEquals("Show all settings as a list", button.getTitle().toString());
        tap(button);
        assertFalse(Settings.CATEGORY_PAGES.savedValue());
        assertEquals("Show settings by category", button.getTitle().toString());
        assertTrue(shown(Preference.class).isEmpty());
        assertTrue(shown(PreferenceCategory.class).containsAll(
                List.of("Settings entry", "Ads and privacy", "Feed", "Notifications", "Pause and diagnostics")));
        // The switch the long list shows says the same as the button did, so a tap on it changes the view.
        SwitchPreference pages = (SwitchPreference) page.findPreference(Settings.CATEGORY_PAGES.key);
        assertSame(pages, page.getPreferenceScreen().findPreference(Settings.CATEGORY_PAGES.key));
        assertFalse(pages.isChecked());
        tap(button);
        assertTrue(Settings.CATEGORY_PAGES.savedValue());
        assertTrue(shown(Preference.class).contains("Feed"));
        assertTrue(pages.isChecked());
    }

    @Test public void offTheCategoriesStayOneList() throws Exception {
        Settings.CATEGORY_PAGES.save(false);
        open();
        assertTrue(shown(Preference.class).isEmpty());
        assertTrue(shown(PreferenceCategory.class).containsAll(
                List.of("Settings entry", "Ads and privacy", "Feed", "Notifications", "Pause and diagnostics")));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.CATEGORY_PAGES.key));
    }

    @Test public void onTheCategoriesAreListedWithPauseStillOpen() throws Exception {
        Settings.CATEGORY_PAGES.save(true);
        open();
        List<String> rows = shown(Preference.class);
        assertTrue(rows.toString(), rows.containsAll(List.of("Settings entry", "Ads and privacy", "Feed", "Notifications")));
        assertFalse("Pause and diagnostics stays a section", rows.contains("Pause and diagnostics"));
        assertEquals(List.of("Pause and diagnostics"), shown(PreferenceCategory.class));
        assertTrue(categoryRow("Ads and privacy").getSummary().toString().contains("Hide ads"));
        // Rows off the screen are still the page's own, for syncing and saved choices.
        assertNotNull(page.findPreference(Settings.HIDE_ADS.key));
    }

    @Test public void aTapOpensOnlyThatCategoryAndBackGoesToTheList() throws Exception {
        Settings.CATEGORY_PAGES.save(true);
        open();
        tap(categoryRow("Feed"));
        assertEquals(List.of("Feed"), shown(PreferenceCategory.class));
        assertTrue(shown(Preference.class).isEmpty());

        assertTrue(page.closeCategory());
        ShadowLooper.idleMainLooper();
        assertEquals(List.of("Pause and diagnostics"), shown(PreferenceCategory.class));
        assertTrue(shown(Preference.class).contains("Feed"));
        assertFalse("Back from the list closes settings", page.closeCategory());
    }

    @Test public void searchLooksThroughEveryCategoryFromAPage() throws Exception {
        Settings.CATEGORY_PAGES.save(true);
        open();
        tap(categoryRow("Feed"));
        page.searchSettings("Hide ads");
        assertTrue(shown(PreferenceCategory.class).contains("Ads and privacy"));
        assertFalse(shown(PreferenceCategory.class).contains("Feed"));
        assertTrue(shown(Preference.class).isEmpty());

        page.searchSettings("");
        assertEquals(List.of("Feed"), shown(PreferenceCategory.class));
    }

    @Test public void theRealSearchInputStaysReachableInsideACategory() throws Exception {
        openDialog();
        tap(categoryRow("Feed"));
        layoutPage();
        assertNotNull(page.getPreferenceScreen().findPreference("hushgram_settings_search"));
        EditText input = page.getView().findViewWithTag("hushgram-settings-search");
        assertNotNull(input);
        assertTrue(input.isShown());
        assertEquals("Search settings", input.getContentDescription());
        touchMode(input, true);
        assertTrue(input.requestFocus());
        layoutPage();
        input.setText("Hide ads");
        layoutPage();
        assertSame("typing keeps the live input", input,
                page.getView().findViewWithTag("hushgram-settings-search"));
        assertTrue(input.hasFocus());
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIDE_ADS.key));
        assertFalse(shown(PreferenceCategory.class).contains("Feed"));
        input.setText("");
        layoutPage();
        assertEquals(List.of("Feed"), shown(PreferenceCategory.class));
        assertSame(input, page.getView().findViewWithTag("hushgram-settings-search"));
        assertTrue(input.hasFocus());
    }

    @Test public void everyCategoryStartsAtSearchOutsideTouchModeEvenFromALaterOverviewRow() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        openDialog();
        touchMode(page.getView(), false);
        List<String> categories = shown(Preference.class);
        assertEquals(23, categories.size());
        for (String title : categories) {
            ListView list = page.getView().findViewById(android.R.id.list);
            list.setSelectionFromTop(12, -8);
            layoutPage();
            tap(categoryRow(title));
            layoutPage();
            assertEquals(title + " must start at Search", 0, list.getFirstVisiblePosition());
            assertNotNull(title + " must keep Search visible",
                    page.getView().findViewWithTag("hushgram-settings-search"));
            assertTrue(page.closeCategory());
            layoutPage();
        }
    }

    private static void touchMode(View view, boolean value) {
        Object root = org.robolectric.util.ReflectionHelpers.callInstanceMethod(view, "getViewRootImpl");
        assertNotNull("The test must change the attached window's touch mode", root);
        org.robolectric.util.ReflectionHelpers.callInstanceMethod(root, "ensureTouchMode",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(boolean.class, value));
        assertEquals(value, view.isInTouchMode());
    }

    @Test public void aCategoryShowsItsPendingRestartAndHidesItWhenTheChangeIsUndone() throws Exception {
        open();
        Preference status = page.getPreferenceScreen().getPreference(0);
        tap(categoryRow("Feed"));
        assertNull(status.getParent());
        SwitchPreference following = (SwitchPreference) page.findPreference(Settings.START_ON_FOLLOWING.key);
        boolean before = following.isChecked();
        following.setChecked(!before);
        ShadowLooper.idleMainLooper();
        assertSame(page.getPreferenceScreen(), status.getParent());
        assertSame(status, page.getPreferenceScreen().getPreference(0));
        assertEquals("hushgram_settings_search", page.getPreferenceScreen().getPreference(1).getKey());
        assertTrue(status.isSelectable());
        assertNotNull(status.getOnPreferenceClickListener());
        assertTrue(status.getSummary().toString().contains("Tap to restart it now."));
        following.setChecked(before);
        ShadowLooper.idleMainLooper();
        assertFalse(status.isSelectable());
        assertNull("a resolved restart no longer occupies the category", status.getParent());
    }

    @Test public void recreationKeepsTheCategoryTitleAndScrollWithoutSavingTheSearchText() throws Exception {
        PatchFamily.inBuildForTests.add(PatchFamily.REEL_DECLUTTER);
        openDialog();
        tap(categoryRow("Reels"));
        layoutPage();
        ListView list = page.getView().findViewById(android.R.id.list);
        list.setSelectionFromTop(3, -8);
        layoutPage();
        int position = list.getFirstVisiblePosition();
        int top = list.getChildAt(0).getTop() - list.getPaddingTop();
        assertTrue("the test must leave the top of the page: first=" + position
                + ", last=" + list.getLastVisiblePosition() + ", count=" + list.getCount()
                + ", height=" + list.getHeight() + ", focus=" + page.getView().findFocus(), position > 0);
        Bundle state = new Bundle();
        page.onSaveInstanceState(state);
        assertFalse(state.containsKey("hushgram_settings_query"));

        controller.recreate();
        ShadowLooper.idleMainLooper();
        dialog = (SettingsDialog) controller.get().getFragmentManager().findFragmentByTag("category_pages");
        assertNotNull(dialog);
        page = (HushgramPreferenceFragment) dialog.getChildFragmentManager()
                .findFragmentById(SettingsDialog.CONTAINER_ID);
        layoutPage();
        assertEquals(List.of("Reels"), shown(PreferenceCategory.class));
        LinearLayout bar = (LinearLayout) ((LinearLayout) dialog.getView()).getChildAt(0);
        assertEquals("Reels", ((TextView) bar.getChildAt(1)).getText());
        list = page.getView().findViewById(android.R.id.list);
        assertEquals(position, list.getFirstVisiblePosition());
        assertEquals(top, list.getChildAt(0).getTop() - list.getPaddingTop());
        page.searchSettings("location setup");
        controller.recreate();
        ShadowLooper.idleMainLooper();
        dialog = (SettingsDialog) controller.get().getFragmentManager().findFragmentByTag("category_pages");
        page = (HushgramPreferenceFragment) dialog.getChildFragmentManager()
                .findFragmentById(SettingsDialog.CONTAINER_ID);
        layoutPage();
        assertEquals("the page returns without retaining what was searched", List.of("Reels"),
                shown(PreferenceCategory.class));
        EditText input = page.getView().findViewWithTag("hushgram-settings-search");
        assertNotNull("the restored page starts at its search field", input);
        assertEquals("", input.getText().toString());
        assertFalse(input.isSaveEnabled());
        assertTrue(page.closeCategory());
        ShadowLooper.idleMainLooper();
        assertTrue(shown(Preference.class).contains("Reels"));
    }

    @Test public void recreationAlsoKeepsOverviewAndFlatListScroll() throws Exception {
        for (boolean pages : new boolean[]{true, false}) {
            Settings.CATEGORY_PAGES.save(pages);
            openDialog();
            ListView list = page.getView().findViewById(android.R.id.list);
            list.setSelectionFromTop(3, -8);
            layoutPage();
            int position = list.getFirstVisiblePosition();
            int top = list.getChildAt(0).getTop() - list.getPaddingTop();
            assertTrue("the test must scroll the " + (pages ? "overview" : "flat list"), position > 0);
            controller.recreate();
            ShadowLooper.idleMainLooper();
            dialog = (SettingsDialog) controller.get().getFragmentManager().findFragmentByTag("category_pages");
            page = (HushgramPreferenceFragment) dialog.getChildFragmentManager()
                    .findFragmentById(SettingsDialog.CONTAINER_ID);
            layoutPage();
            list = page.getView().findViewById(android.R.id.list);
            assertEquals(position, list.getFirstVisiblePosition());
            assertEquals(top, list.getChildAt(0).getTop() - list.getPaddingTop());
            assertFalse("restoration must not open a category", page.closeCategory());
            dialog.dismissAllowingStateLoss();
            controller.close();
            dialog = null;
            controller = null;
        }
    }

    @Test public void theSwitchChangesTheOpenPage() throws Exception {
        Settings.CATEGORY_PAGES.save(false);
        open();
        ((SwitchPreference) page.findPreference(Settings.CATEGORY_PAGES.key)).setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(Settings.CATEGORY_PAGES.savedValue());
        assertTrue(shown(Preference.class).contains("Feed"));

        tap(categoryRow("Settings entry"));
        ((SwitchPreference) page.findPreference(Settings.CATEGORY_PAGES.key)).setChecked(false);
        ShadowLooper.idleMainLooper();
        assertTrue(shown(Preference.class).isEmpty());
        assertTrue(shown(PreferenceCategory.class).containsAll(List.of("Settings entry", "Feed", "Ads and privacy")));
        assertFalse(page.closeCategory());
    }

    @Test public void pauseLeavesTheListAsChosen() throws Exception {
        Settings.CATEGORY_PAGES.save(true);
        BaseSettings.PAUSED.save(true);
        try {
            open();
            assertTrue(shown(Preference.class).contains("Feed"));
        } finally {
            BaseSettings.PAUSED.save(false);
        }
    }

    @Test public void theDialogsBackClosesThePageFirst() throws Exception {
        Settings.CATEGORY_PAGES.save(true);
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        dialog = new SettingsDialog();
        dialog.show(controller.get().getFragmentManager(), "category_pages");
        controller.get().getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        page = (HushgramPreferenceFragment) dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
        assertNotNull(page);
        tap(categoryRow("Notifications"));
        Dialog window = dialog.getDialog();

        window.onBackPressed();
        ShadowLooper.idleMainLooper();
        assertTrue("the first Back goes to the list", window.isShowing());
        assertTrue(shown(Preference.class).contains("Notifications"));

        window.onBackPressed();
        ShadowLooper.idleMainLooper();
        assertFalse(window.isShowing());
        dialog = null;
    }
}
