/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import kotlin.jvm.functions.Function1;

import app.hushgram.extension.instagram.feed.FeedItemKinds;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Download button on reels (#178): the icon joins the column beside a reel only with its switch and
 * Download on reels on, only on a reel with something to save, and a tap saves the reel the column
 * shows at that moment through the menu's own save.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelDownloadButtonTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final ReelDownloadButton.Actions originalActions = ReelDownloadButton.actions;
    private final ReelDownloadButton.Bridge originalBridge = ReelDownloadButton.bridge;
    private final Recorder recorder = new Recorder();
    private final FakeBridge bridge = new FakeBridge();
    private Activity activity;

    /** A reel's kinds, as Instagram's enum names them. */
    enum Kind { ORGANIC, AD }

    /** A reel's item: its kind and its Media. */
    static final class Item {
        final Kind kind;
        final Object media;

        Item(Kind kind, Object media) {
            this.kind = kind;
            this.media = media;
        }
    }

    /** The column beside a reel, holding the reel it shows now. */
    static final class Column {
        Item item;

        Column(Item item) {
            this.item = item;
        }
    }

    /** Reads the fake column and item, and keeps what the icon was made with. */
    private static final class FakeBridge implements ReelDownloadButton.Bridge {
        final Object made = new Object();
        int items;
        int icons;
        RuntimeException failure;
        Drawable drawable;
        CharSequence description;
        Function1<Object, Object> click;
        long size;
        long padding;

        @Override public Object item(Object column) {
            items++;
            if (failure != null) throw failure;
            return ((Column) column).item;
        }

        @Override public Object media(Object item) {
            return ((Item) item).media;
        }

        @Override public String kind(Object item) throws ReflectiveOperationException {
            return FeedItemKinds.kindOf(item, ReelDownloadButton.ORGANIC, FamilyNames.REEL_DOWNLOAD);
        }

        @Override public Object icon(Drawable drawable, CharSequence description, Function1<Object, Object> click, long size,
                                     long padding) {
            icons++;
            this.drawable = drawable;
            this.description = description;
            this.click = click;
            this.size = size;
            this.padding = padding;
            return made;
        }
    }

    /** The calls a tap makes, and the Media with nothing to save or with music. */
    private static final class Recorder implements ReelDownloadButton.Actions {
        final List<String> calls = new ArrayList<>();
        final Set<Object> empty = new HashSet<>();
        final Set<Object> music = new HashSet<>();
        Runnable video;
        Runnable photo;

        @Override public boolean savable(Object media) {
            return !empty.contains(media);
        }

        @Override public boolean music(Object media) {
            return music.contains(media);
        }

        @Override public boolean save(String row, Object media, Activity activity) {
            calls.add("save " + row + " " + media);
            return true;
        }

        @Override public void choose(View anchor, Runnable video, Runnable photo) {
            calls.add("choose");
            this.video = video;
            this.photo = photo;
        }

        @Override public void nothing(Context context) {
            calls.add("nothing");
        }
    }

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        ReelDownloadButton.actions = recorder;
        ReelDownloadButton.bridge = bridge;
        ReelDownloadButton.resetForTests();
        Settings.DOWNLOAD_REELS.save(true);
        Settings.REEL_DOWNLOAD_BUTTON.save(true);
    }

    @After
    public void tearDown() {
        ReelDownloadButton.actions = originalActions;
        ReelDownloadButton.bridge = originalBridge;
        ReelDownloadButton.resetForTests();
        Settings.REEL_DOWNLOAD_BUTTON.resetToDefault();
        Settings.DOWNLOAD_REELS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void theSwitchStartsOffAndWaitsForDownloadOnReels() {
        assertFalse(Settings.REEL_DOWNLOAD_BUTTON.defaultValue);
        assertFalse("no restart needed", Settings.REEL_DOWNLOAD_BUTTON.rebootApp);
        assertEquals(Collections.singletonList(Settings.DOWNLOAD_REELS), Settings.REEL_DOWNLOAD_BUTTON.getParentSettings());
        Settings.DOWNLOAD_REELS.save(false);
        assertFalse(Settings.REEL_DOWNLOAD_BUTTON.isAvailable());
        Settings.DOWNLOAD_REELS.save(true);
        assertTrue(Settings.REEL_DOWNLOAD_BUTTON.isAvailable());
    }

    @Test
    public void offOrPausedLeavesTheColumnAsItWas() {
        Column column = new Column(new Item(Kind.ORGANIC, "reel"));
        Settings.REEL_DOWNLOAD_BUTTON.save(false);
        assertFalse(ReelDownloadButton.on());
        assertNull(ReelDownloadButton.litho(column, null, 120, 30));

        Settings.REEL_DOWNLOAD_BUTTON.save(true);
        Settings.DOWNLOAD_REELS.save(false);
        assertFalse("it needs Download on reels", ReelDownloadButton.on());
        assertNull(ReelDownloadButton.litho(column, null, 120, 30));

        Settings.DOWNLOAD_REELS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ReelDownloadButton.on());
        assertNull(ReelDownloadButton.litho(column, null, 120, 30));
        assertEquals("nothing was read while off", 0, bridge.items);
        assertEquals(0, bridge.icons);
    }

    @Test
    public void aReelGetsTheIconWithSavesSizeAndPaddingInWhite() {
        Column column = new Column(new Item(Kind.ORGANIC, "reel"));
        assertTrue(ReelDownloadButton.on());
        assertSame(bridge.made, ReelDownloadButton.litho(column, null, 120, 30));
        assertEquals(120, bridge.size);
        assertEquals(30, bridge.padding);
        assertEquals("Download", String.valueOf(bridge.description));
        assertNotNull(bridge.click);
        assertTrue("this build has no glyph of Instagram's, so HushGram's own", bridge.drawable instanceof FeedDownloadButton.Glyph);
        ColorFilter tint = bridge.drawable.getColorFilter();
        assertEquals(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN), tint);

        ColorFilter instagrams = new PorterDuffColorFilter(0xFF262626, PorterDuff.Mode.SRC_IN);
        assertSame(bridge.made, ReelDownloadButton.litho(column, instagrams, 120, 30));
        assertSame("Instagram's own tint where it has one", instagrams, bridge.drawable.getColorFilter());
    }

    @Test
    public void anAdOrAReelWithNothingToSaveGetsNoIcon() {
        assertNull(ReelDownloadButton.litho(new Column(new Item(Kind.AD, "ad")), null, 120, 30));
        recorder.empty.add("bare");
        assertNull(ReelDownloadButton.litho(new Column(new Item(Kind.ORGANIC, "bare")), null, 120, 30));
        assertNull(ReelDownloadButton.litho(new Column(new Item(Kind.ORGANIC, null)), null, 120, 30));
        assertNull(ReelDownloadButton.litho(new Column(null), null, 120, 30));
        assertEquals(0, bridge.icons);
    }

    @Test
    public void aTapSavesTheReelTheColumnShowsNow() {
        Column column = new Column(new Item(Kind.ORGANIC, "first"));
        ReelDownloadButton.litho(column, null, 120, 30);
        // The row is reused for the next reel before the tap.
        column.item = new Item(Kind.ORGANIC, "second");
        bridge.click.invoke(new View(activity));
        assertEquals(Collections.singletonList("save null second"), recorder.calls);
    }

    @Test
    public void aPhotoWithMusicAsksForVideoOrPhoto() {
        recorder.music.add("song");
        Column column = new Column(new Item(Kind.ORGANIC, "song"));
        ReelDownloadButton.litho(column, null, 120, 30);
        bridge.click.invoke(new View(activity));
        assertEquals(Collections.singletonList("choose"), recorder.calls);
        recorder.video.run();
        recorder.photo.run();
        assertEquals(java.util.Arrays.asList("choose", "save " + ReelDownload.VIDEO_OPTION + " song",
                "save " + ReelDownload.PHOTO_OPTION + " song"), recorder.calls);
    }

    @Test
    public void aTapWithNothingToSaveSaysSoAndOffSaysNothing() {
        Column column = new Column(new Item(Kind.ORGANIC, "reel"));
        ReelDownloadButton.litho(column, null, 120, 30);
        recorder.empty.add("reel");
        bridge.click.invoke(new View(activity));
        assertEquals(Collections.singletonList("nothing"), recorder.calls);

        recorder.calls.clear();
        Settings.REEL_DOWNLOAD_BUTTON.save(false);
        bridge.click.invoke(new View(activity));
        assertTrue("switched off, a tap does nothing", recorder.calls.isEmpty());
    }

    @Test
    public void aFailureLeavesTheColumnAndNeverThrows() {
        Column column = new Column(new Item(Kind.ORGANIC, "reel"));
        ReelDownloadButton.litho(column, null, 120, 30);
        Function1<Object, Object> click = bridge.click;
        bridge.failure = new IllegalStateException("unreadable");
        assertNull(ReelDownloadButton.litho(column, null, 120, 30));
        assertNull(click.invoke(new View(activity)));
        assertNull(click.invoke(null));
        assertTrue(recorder.calls.isEmpty());
        assertTrue(String.join("\n", HookStatus.report()).contains("reel download button"));
    }
}
