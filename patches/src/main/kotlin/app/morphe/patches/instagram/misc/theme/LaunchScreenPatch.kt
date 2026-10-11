/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private const val PATCH = "HushGram launch screen"

/** The theme Instagram's launcher activities wear, whose Android 12 version names the launch screen's icon. */
internal const val INSTAGRAM_LAUNCH_THEME = "Base.Theme.Launcher"

/** The theme the patch adds, which the extension's LaunchScreen hands to Android by this name. */
internal const val LAUNCH_THEME = "HushGramLaunchScreen"

/** The drawable the patch adds for HushGram's mark. */
internal const val LAUNCH_MARK = "hushgram_launch_mark"

internal const val SPLASH_ICON = "android:windowSplashScreenAnimatedIcon"
internal const val SPLASH_BRANDING = "android:windowSplashScreenBrandingImage"

/**
 * HushGram's mark, drawn from vectors so it's sharp on every screen: the pink to plum tile of the
 * app's icon with its white H and the ribbon through it. Android 12's launch screen shows an icon in
 * a 160dp square, masked to the middle two thirds, and Instagram's own glyph is 61.333dp across, so
 * the mark is 64dp, centered.
 */
internal val LAUNCH_MARK_XML = """
<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:gravity="center" android:width="64dp" android:height="64dp">
        <shape android:shape="rectangle">
            <corners android:radius="15dp" />
            <gradient android:type="linear" android:angle="315" android:startColor="#FFF0186C" android:endColor="#FF3B0A3D" />
        </shape>
    </item>
    <item android:gravity="center" android:width="64dp" android:height="64dp">
        <vector android:width="64dp" android:height="64dp" android:viewportWidth="100" android:viewportHeight="100">
            <path android:fillColor="#FFFFFFFF" android:pathData="M22,15h15a3,3 0 0 1 3,3v64a3,3 0 0 1 -3,3h-15a3,3 0 0 1 -3,-3v-64a3,3 0 0 1 3,-3z" />
            <path android:fillColor="#FFFFFFFF" android:pathData="M63,15h15a3,3 0 0 1 3,3v64a3,3 0 0 1 -3,3h-15a3,3 0 0 1 -3,-3v-64a3,3 0 0 1 3,-3z" />
            <path android:fillColor="#FFFF7A9E" android:pathData="M11,38C22,36 30,46 42,45C54,44 66,36 80,40C84,41 87,44 88,48L88,58C85,53 82,52 78,52C66,50 54,57 42,57C30,57 22,49 11,50Z" />
        </vector>
    </item>
</layer-list>
""".trimStart()

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")

private fun Document.styles(): List<Element> {
    val list = getElementsByTagName("style")
    return (0 until list.length).map { list.item(it) as Element }
}

private fun Element.items(): List<Element> {
    val list = getElementsByTagName("item")
    return (0 until list.length).map { list.item(it) as Element }
}

/**
 * Adds [LAUNCH_THEME] to one decoded styles.xml: [INSTAGRAM_LAUNCH_THEME] with [LAUNCH_MARK] as the
 * launch screen's icon and no branding image, so "from Meta" goes too. Everything else, the
 * background color and its dark version among it, comes from Instagram's theme. Refuses unless that
 * theme is there and this one isn't.
 */
internal fun addLaunchTheme(styles: Document) {
    val names = styles.styles().map { it.getAttribute("name") }
    if (INSTAGRAM_LAUNCH_THEME !in names) refuse("res/values/styles.xml has no $INSTAGRAM_LAUNCH_THEME")
    if (LAUNCH_THEME in names) refuse("res/values/styles.xml already has a $LAUNCH_THEME")
    val style = styles.createElement("style")
    style.setAttribute("name", LAUNCH_THEME)
    style.setAttribute("parent", "@style/$INSTAGRAM_LAUNCH_THEME")
    for ((name, value) in listOf(SPLASH_ICON to "@drawable/$LAUNCH_MARK", SPLASH_BRANDING to "@null")) {
        val item = styles.createElement("item")
        item.setAttribute("name", name)
        item.textContent = value
        style.appendChild(item)
    }
    styles.documentElement.appendChild(style)
}

/**
 * Whether one decoded styles.xml gives [INSTAGRAM_LAUNCH_THEME] a launch screen icon, which is what
 * the theme the patch adds replaces.
 */
internal fun namesSplashIcon(styles: Document): Boolean =
    styles.styles().any { style ->
        style.getAttribute("name") == INSTAGRAM_LAUNCH_THEME && style.items().any { it.getAttribute("name") == SPLASH_ICON }
    }

/**
 * Adds [LAUNCH_THEME] and [LAUNCH_MARK]. Refuses unless Instagram's launch theme names the launch
 * screen's icon in its Android 12 version, so a build that drew its launch screen some other way
 * isn't patched blind. Those versions are only read: document() writes its file back, and the
 * patcher re-encodes every file written.
 */
internal fun ResourcePatchContext.addLaunchScreen() {
    val res = get("res")
    val v31 = res.listFiles { file -> file.isDirectory && file.name.startsWith("values") && file.name.endsWith("-v31") }
        .orEmpty().map { File(it, "styles.xml") }.filter { it.isFile }
    val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    if (v31.none { namesSplashIcon(builder.parse(it)) }) {
        refuse("no values-v31 styles.xml gives $INSTAGRAM_LAUNCH_THEME a launch screen icon")
    }
    val mark = File(res, "drawable/$LAUNCH_MARK.xml")
    if (mark.exists()) refuse("res/drawable already has a $LAUNCH_MARK")
    document("res/values/styles.xml").use(::addLaunchTheme)
    mark.parentFile.mkdirs()
    mark.writeText(LAUNCH_MARK_XML)
}

/**
 * The theme and the mark. Manager decodes Instagram's resources for Remove the advertising ID
 * already, so this costs no second decode when both are picked, and it touches no color.
 */
private val launchThemePatch = resourcePatch {
    execute {
        addLaunchScreen()
    }
}

/**
 * HushGram's mark on the screen Android shows while Instagram starts. Included in the default
 * selection with its switch off.
 *
 * Android draws that screen itself, from the launch theme, before any of Instagram's code runs, and
 * takes it down at Instagram's first frame. 450 never asks it to stay up, and its launcher
 * activities wear [INSTAGRAM_LAUNCH_THEME]. So the patch changes no code. It adds a theme of its own
 * next to that one, and the extension, on Android 13 and newer, tells Android to use it for later
 * starts while the switch is on (Activity.getSplashScreen().setSplashScreenTheme). Android keeps the
 * answer itself, so the switch costs a start nothing. Android 12 and older only ever use the
 * manifest's theme, so they keep Instagram's screen.
 */
@Suppress("unused")
val launchScreenPatch = bytecodePatch(
    name = "HushGram launch screen",
    description = "Shows HushGram's logo instead of Instagram's on the screen you see while Instagram starts, on " +
        "Android 13 and newer. Starts off. Turn it on in HushGram settings > Layout.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch, launchThemePatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("launchScreen")
        enableStatus("launchScreen")
    }
}
