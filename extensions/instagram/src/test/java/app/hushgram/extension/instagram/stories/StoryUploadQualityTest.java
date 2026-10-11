/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What Upload stories at full quality answers at the start of Instagram's high quality upload check. */
@RunWith(RobolectricTestRunner.class)
public class StoryUploadQualityTest {
    /** Stand-in for Instagram's ShareType, named as its enum names them. */
    private enum ShareType { FEED, STORY, STORY_AND_DIRECT_STORY_SHARE, CLIPS, SCHEDULED_STORY }

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** The switch starts off, and then Instagram's own check runs for every upload. */
    @Test
    public void theSwitchStartsOffAndInstagramDecides() {
        assertFalse(Settings.UPLOAD_STORIES_AT_FULL_QUALITY.get());
        for (ShareType type : ShareType.values()) assertFalse(type.name(), StoryUploadQuality.fullQuality(type));
        assertTrue(HookStatus.missing(FamilyNames.STORY_UPLOAD_QUALITY).toString(),
                HookStatus.missing(FamilyNames.STORY_UPLOAD_QUALITY).isEmpty());
    }

    /** On, a story gets a yes, and every other upload, a story shared to messages too, gets Instagram's check. */
    @Test
    public void onOnlyAStoryIsRaised() {
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.save(true);
        assertTrue("story", StoryUploadQuality.fullQuality(ShareType.STORY));
        assertFalse("post", StoryUploadQuality.fullQuality(ShareType.FEED));
        assertFalse("reel", StoryUploadQuality.fullQuality(ShareType.CLIPS));
        assertFalse("story and messages", StoryUploadQuality.fullQuality(ShareType.STORY_AND_DIRECT_STORY_SHARE));
        assertFalse("scheduled story", StoryUploadQuality.fullQuality(ShareType.SCHEDULED_STORY));
        assertFalse("no share type", StoryUploadQuality.fullQuality(null));
        assertFalse("not an enum", StoryUploadQuality.fullQuality("STORY"));
        assertTrue(HookStatus.missing(FamilyNames.STORY_UPLOAD_QUALITY).toString(),
                HookStatus.missing(FamilyNames.STORY_UPLOAD_QUALITY).isEmpty());
    }

    /** Paused, or before the settings are read, Instagram's own check runs. */
    @Test
    public void pausedAndUnreadyLeaveInstagramsCheck() {
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", StoryUploadQuality.fullQuality(ShareType.STORY));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse("no context", StoryUploadQuality.fullQuality(ShareType.STORY)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertFalse("pause undecided", StoryUploadQuality.fullQuality(ShareType.STORY)));

        assertTrue("back on", StoryUploadQuality.fullQuality(ShareType.STORY));
    }
}
