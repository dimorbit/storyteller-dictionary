@file:OptIn(ExperimentalReadiumApi::class, InternalReadiumApi::class)

package expo.modules.readium

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.text.Html
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.ColorInt
import androidx.core.graphics.toColorInt
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.KeyEvent as ReadiumKeyEvent
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.extensions.toMap
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.shared.publication.Link
import kotlin.math.abs
import kotlin.math.ceil
import java.io.File
import java.util.Locale

data class Highlight(val id: String, @ColorInt val color: Int, val locator: Locator)

// A saved word, its stored locator, and the book that locator belongs to.
data class DictionaryEntryDecoration(
    val id: String,
    val locator: Locator,
    val word: String,
    val bookUuid: String?
)

data class CustomFont(val uri: String, val name: String, val type: String)

data class Props(
    var bookUuid: String?,
    var locator: Locator?,
    var isPlaying: Boolean?,
    var highlights: List<Highlight>?,
    var dictionaryEntries: List<DictionaryEntryDecoration>?,
    var activeDictionaryDecorationId: String?,
    var bookmarks: List<Locator>?,
    var readaloudColor: Int?,
    var readaloudDecoratorStyle: String?,
    var customFonts: List<CustomFont>?,
    @ColorInt var foreground: Int?,
    @ColorInt var background: Int?,
    var fontFamily: FontFamily?,
    var lineHeight: Double?,
    var paragraphSpacing: Double?,
    var fontSize: Double?,
    var textAlign: TextAlign?,
    var marginLeft: Int?,
    var marginRight: Int?,
    var scrollMode: Boolean?,
    var jumpBarBottom: Double?
)


data class FinalizedProps(
    var bookUuid: String,
    var locator: Locator?,
    var isPlaying: Boolean,
    var highlights: List<Highlight>,
    var dictionaryEntries: List<DictionaryEntryDecoration>,
    var activeDictionaryDecorationId: String?,
    var bookmarks: List<Locator>,
    var readaloudColor: Int,
    var readaloudDecoratorStyle: String,
    var customFonts: List<CustomFont>,
    @ColorInt var foreground: Int,
    @ColorInt var background: Int,
    var fontFamily: FontFamily,
    var lineHeight: Double,
    var paragraphSpacing: Double,
    var fontSize: Double,
    var textAlign: TextAlign,
    var marginLeft: Int,
    var marginRight: Int,
    var scrollMode: Boolean,
    var jumpBarBottom: Double
)


@SuppressLint("ViewConstructor", "ResourceType")
class EpubView(context: Context, appContext: AppContext) : ExpoView(context, appContext),
    EpubNavigatorFragment.Listener, EpubNavigatorFragment.PaginationListener,
    DecorableNavigator.Listener, HyperlinkNavigator.Listener {

    companion object {
        var current: EpubView? = null

        // Chapters of extracted text, and the marks found in them, kept per book
        // and chapter. Twelve covers flipping back and forth across a chapter
        // boundary without re-reading anything.
        private const val DICTIONARY_CACHE_SIZE = 12

        // The reader works out how the marks changed by diffing the new list against
        // the previous one in the background. Two applications started close together
        // read each other's lists and throw, so the marks are applied once after a
        // chapter change settles, and never straight after the previous application.
        private const val DICTIONARY_DECORATION_SETTLE_MS = 80L
        private const val DICTIONARY_DECORATION_GAP_MS = 200L

        // Enough pending echoes to cover the reader sending them back late.
        private const val PENDING_LOCATOR_ECHO_LIMIT = 32

        // A real echo comes back within a frame or two. Anything older is no longer one.
        private const val PENDING_LOCATOR_ECHO_MAX_AGE_MS = 2000L

        // Runs of whitespace and punctuation in a quote become one space before two
        // quotes are compared, so a saved "hello," matches the bare word the chapter search
        // finds, and the web view's snippet agrees with the chapter text.
        private val quoteSeparatorRun = Regex("[\\s\\p{Z}\\p{P}\\p{S}]+")

        // Readium's group.add does not check for an id the group already holds. A page
        // that finishes loading while a diff is being worked out gets the whole list,
        // and the diff then adds the same marks again, so each id is cut back to one.
        private const val removeDuplicateDictionaryMarks = """
            (function () {
              var group = readium.getDecorations('dictionary');
              var counts = {};
              group.items.forEach(function (item) {
                var id = item.decoration.id;
                counts[id] = (counts[id] || 0) + 1;
              });
              Object.keys(counts).forEach(function (id) {
                for (var i = 1; i < counts[id]; i++) group.remove(id);
              });
            })();
        """
    }

    // Required for proper layout! Forces Expo to
    // use the Android layout system for this view,
    // rather than React Native's. Without this,
    // the ViewPager and WebViews will be laid out
    // incorrectly
    override val shouldUseAndroidLayout = true

    // Expo's shouldUseAndroidLayout only measures/layouts the ExpoView itself,
    // not children. Override requestLayout to propagate through the view tree.
    // See: https://github.com/readium/kotlin-toolkit/discussions/737
    override fun requestLayout() {
        super.requestLayout()
        val now = SystemClock.elapsedRealtime()
        val skip = isSelecting || now < selectionLayoutHoldUntil
        // While a selection handle is moving, the WebView asks for layout on
        // every selection update. Force-laying out the whole reader here makes
        // the selection range snap to the paragraph and start over, many times
        // a second. The WebView can keep its own layout until the action mode
        // ends.
        if (skip) return
        post {
            measureAndLayoutRecursively(this)
        }
    }

    // endSelection() is the only other place that clears the selection flags, and it
    // depends on the action mode callback. If that callback is missed, requestLayout
    // would skip the recursive layout forever, so clear the flags on these lifecycle events too.
    private fun resetSelectionLayout() {
        isSelecting = false
        selectionLayoutHoldUntil = 0L
    }

    override fun onDetachedFromWindow() {
        resetSelectionLayout()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        resetSelectionLayout()
        super.onSizeChanged(w, h, oldw, oldh)
    }

    private fun cancelJumpForReaderInput() {
        if (pendingJumpLocator != null || jumpPositionJob?.isActive == true) {
            pendingJumpLocator = null
            cancelJumpPosition()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) cancelJumpForReaderInput()
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) cancelJumpForReaderInput()
        return super.dispatchGenericMotionEvent(event)
    }

    // When the WebView has an active InputConnection (after a touch/tap), Android routes
    // the next key's ACTION_DOWN through the IME dispatch path (ViewRootImpl.dispatchKeyFromIme),
    // which calls dispatchKeyEventPreIme directly on the view hierarchy — bypassing
    // Activity.dispatchKeyEvent entirely. By intercepting here, we handle both cases:
    //   - Normal (WebView not focused): Activity.dispatchKeyEvent fires first, returns true,
    //     view hierarchy never sees it → no double navigation.
    //   - After touch (WebView focused): Activity never sees DOWN, we catch it here → works.
    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val activity = appContext.currentActivity as FragmentActivity?
            when (event.keyCode) {
                93, 117 -> {
                    cancelJumpForReaderInput()
                    activity?.lifecycleScope?.launch { navigator?.goForward(animated = false) }
                    return true
                }
                92 -> {
                    cancelJumpForReaderInput()
                    activity?.lifecycleScope?.launch { navigator?.goBackward(animated = false) }
                    return true
                }
            }
        }
        return super.dispatchKeyEventPreIme(event)
    }

    private fun measureAndLayoutRecursively(view: android.view.View) {
        view.forceLayout()
        view.measure(
            MeasureSpec.makeMeasureSpec(view.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(view.height, MeasureSpec.EXACTLY)
        )
        view.layout(view.left, view.top, view.right, view.bottom)
        (view as? android.view.ViewGroup)?.let { vg ->
            for (i in 0 until vg.childCount) {
                measureAndLayoutRecursively(vg.getChildAt(i))
            }
        }
    }

    val onLocatorChange by EventDispatcher()
    val onMiddleTouch by EventDispatcher()
    val onBookmarksActivate by EventDispatcher()
    val onDoubleTouch by EventDispatcher()
    val onSelection by EventDispatcher()
    val onHighlightTap by EventDispatcher()
    val onDictionaryTap by EventDispatcher()
    val onShowFootnote by EventDispatcher()

    var navigator: EpubNavigatorFragment? = null

    private var isSelecting: Boolean = false
    private var selectionLayoutHoldUntil: Long = 0L
    private val webViews = LinkedHashSet<WebView>()
    private val webViewScrollPositions = mutableMapOf<WebView, Pair<Int, Int>>()

    fun beginSelection() {
        isSelecting = true
        selectionLayoutHoldUntil = SystemClock.elapsedRealtime() + 500
        webViewScrollPositions.clear()
        for (webView in webViews) {
            webViewScrollPositions[webView] = webView.scrollX to webView.scrollY
        }
        // Capture the horizontal scroll before the first selectionchange event.
        activity?.lifecycleScope?.launch {
            navigator?.evaluateJavascript("storyteller.pinSelectionScroll && storyteller.pinSelectionScroll();")
        }
    }

    fun endSelection() {
        isSelecting = false
        // The action mode can be recreated between handle moves. Hold the
        // recursive layout briefly so it cannot reset the selection in that gap.
        selectionLayoutHoldUntil = SystemClock.elapsedRealtime() + 200
        activity?.lifecycleScope?.launch {
            navigator?.evaluateJavascript("storyteller.unpinSelectionScroll && storyteller.unpinSelectionScroll();")
        }
    }
    var player: AudiobookPlayer? = null

    var locationEmitter: Job? = null

    private var changingResource = false

    private data class PendingLocatorEcho(val locator: Locator, val emittedAtMs: Long)
    // Read the bar position when the destination page reports; it can change during a jump.
    private var pendingJumpLocator: Locator? = null
    private var jumpPositionJob: Job? = null
    // WebView scripts can outlive the coroutine that queued them.
    @Volatile
    private var jumpPositionGeneration = 0

    // Locators this view emitted that the reader has not sent back yet, oldest first. The
    // reader sends each one back as the locator prop, sometimes after newer ones were
    // emitted, so such an echo is not a jump. Once it has come back it is dropped, so a
    // later jump to the same place is not taken for an echo. Positions emitted during
    // playback are not sent back at all, so they are dropped after a short while.
    private val pendingLocatorEchoes = ArrayDeque<PendingLocatorEcho>()
    private var firstPageLoaded = CompletableDeferred<Unit>()
    private val scrollModeChapterNavigator = ScrollModeChapterNavigator()

    private val activity: FragmentActivity?
        get() = appContext.currentActivity as FragmentActivity?

    var pendingProps: Props = Props(
        bookUuid = null,
        locator = null,
        isPlaying = null,
        highlights = null,
        dictionaryEntries = null,
        activeDictionaryDecorationId = null,
        bookmarks = null,
        readaloudColor = null,
        readaloudDecoratorStyle = null,
        customFonts = null,
        foreground = null,
        background = null,
        fontFamily = null,
        lineHeight = null,
        paragraphSpacing = null,
        fontSize = null,
        textAlign = null,
        marginLeft = null,
        marginRight = null,
        scrollMode = null,
        jumpBarBottom = null
    )
    var props: FinalizedProps? = null

    // @font-face rules for the fonts imported by the user, see injectCustomFonts()
    private var customFontsCss: String = ""

    // The last preferences handed to the current navigator. Selection and menu
    // updates re-run finalizeProps; submitting identical preferences again can
    // reflow the WebView and drop the live text selection.
    private var submittedPreferences: EpubPreferences? = null

    // Readium can only serve the font sources of `addFontFamilyDeclaration` from the app's
    // assets, so the fonts imported by the user are embedded as data URLs instead.
    private fun applyCustomFonts(customFonts: List<CustomFont>) {
        customFontsCss = ""

        activity?.lifecycleScope?.launch {
            val css = withContext(Dispatchers.IO) { buildCustomFontsCss(customFonts) }

            customFontsCss = css
            injectCustomFonts()
        }
    }

    private fun buildCustomFontsCss(customFonts: List<CustomFont>): String =
        customFonts.mapNotNull { font ->
            val family = font.name
                .filterNot { it == '"' || it == '\\' || it.code < 0x20 || it.code == 0x7f }
                .trim()
            if (family.isEmpty()) return@mapNotNull null

            val bytes = try {
                File(Uri.parse(font.uri).path ?: font.uri).readBytes()
            } catch (e: Exception) {
                Log.w("StorytellerFonts", "Could not read the custom font ${font.name}", e)
                null
            } ?: return@mapNotNull null

            val type = if (font.type.equals("otf", ignoreCase = true)) "otf" else "ttf"
            val source = Base64.encodeToString(bytes, Base64.NO_WRAP)

            "@font-face { font-family: \"$family\"; font-style: normal; font-weight: normal;" +
                " src: url(data:font/$type;base64,$source) }"
        }.joinToString("\n")

    private fun injectCustomFonts() {
        val css = customFontsCss
        if (css.isEmpty()) return

        activity?.lifecycleScope?.launch {
            navigator?.evaluateJavascript(
                """
                (function() {
                  var style = document.getElementById('storyteller-custom-fonts');
                  if (!style) {
                    style = document.createElement('style');
                    style.id = 'storyteller-custom-fonts';
                    document.head.appendChild(style);
                  }
                  style.textContent = ${JSONObject.quote(css)};
                })();
                """.trimIndent()
            )
        }
    }

    private fun isSameLocatorPosition(lhs: Locator?, rhs: Locator?): Boolean {
        if (lhs == null || rhs == null) {
            return lhs == null && rhs == null
        }

        if (lhs.href != rhs.href) return false
        // Two saved quotes can share the same fragment and progression.
        if (lhs.text != rhs.text &&
            (!lhs.text.highlight.isNullOrBlank() || !rhs.text.highlight.isNullOrBlank())
        ) return false

        // The audio clip id only narrows the match: progression decides between two
        // positions in the same clip.
        val lhsFragment = lhs.locations.fragments.firstOrNull()
        val rhsFragment = rhs.locations.fragments.firstOrNull()
        if (lhsFragment != rhsFragment) return false

        val lhsProgression = lhs.locations.progression
        val rhsProgression = rhs.locations.progression
        if (lhsProgression != null || rhsProgression != null) {
            return lhsProgression != null &&
                rhsProgression != null &&
                abs(lhsProgression - rhsProgression) < 0.00001
        }

        return true
    }

    private fun emitLocatorChange(locator: Locator) {
        pendingLocatorEchoes.addLast(PendingLocatorEcho(locator, SystemClock.elapsedRealtime()))
        if (pendingLocatorEchoes.size > PENDING_LOCATOR_ECHO_LIMIT) pendingLocatorEchoes.removeFirst()
        onLocatorChange(locator.toJSON().toMap())
    }

    // Takes the pending locator that this locator echoes, if there is one. Older pending
    // locators go with it, because the reader has already moved on past them. Stale ones
    // are dropped first, so they can no longer match.
    private fun takeLocatorEcho(locator: Locator): Boolean {
        val now = SystemClock.elapsedRealtime()
        pendingLocatorEchoes.removeAll { now - it.emittedAtMs > PENDING_LOCATOR_ECHO_MAX_AGE_MS }
        val index = pendingLocatorEchoes.indexOfFirst { isSameLocatorPosition(locator, it.locator) }
        if (index < 0) return false
        repeat(index + 1) { pendingLocatorEchoes.removeFirst() }
        return true
    }

    private data class LayoutChange(
        val finalProps: FinalizedProps,
        val layoutModeChanged: Boolean,
        val enteredPageLayout: Boolean,
        val locatorChangedFromExternalUpdate: Boolean,
        val playbackStarted: Boolean,
    )

    private interface EpubLayoutBehavior {
        val supportsPagedClipScheduling: Boolean

        fun preferences(view: EpubView): EpubPreferences
        fun submitModePreferences(view: EpubView, change: LayoutChange)
        fun shouldNavigateToLocator(view: EpubView, change: LayoutChange): Boolean
        fun navigateToLocator(view: EpubView, locator: Locator)
        fun handleExternalLocatorChange(
            view: EpubView,
            change: LayoutChange,
            didNavigateToLocator: Boolean
        )
        fun handlePlayingFragmentChanged(
            view: EpubView,
            change: LayoutChange,
            newFragment: String?,
            oldFragment: String?
        )
        fun keepPlayingLocatorVisible(
            view: EpubView,
            change: LayoutChange,
            didNavigateToLocator: Boolean
        )
        fun submitReadingPreferences(view: EpubView, change: LayoutChange)
        fun onTap(
            view: EpubView,
            event: TapEvent,
            directionalNavigationAdapter: DirectionalNavigationAdapter
        ): Boolean
        suspend fun isProgressionVisible(
            view: EpubView,
            currentLocator: Locator,
            progression: Double
        ): Boolean?
        suspend fun findOnPage(view: EpubView, locator: Locator)
    }

    private object PagedLayoutBehavior : EpubLayoutBehavior {
        override val supportsPagedClipScheduling = true

        override fun preferences(view: EpubView): EpubPreferences =
            view.epubPreferences()

        override fun submitModePreferences(view: EpubView, change: LayoutChange) {
            if (change.enteredPageLayout) {
                view.submitPreferencesIfChanged(view.epubPreferences())
            }
        }

        override fun shouldNavigateToLocator(view: EpubView, change: LayoutChange): Boolean {
            val currentLocator = view.navigator?.currentLocator?.value
            return change.finalProps.locator?.let { locator ->
                !change.enteredPageLayout && locator != currentLocator
            } ?: false
        }

        override fun navigateToLocator(view: EpubView, locator: Locator) {
            view.go(locator)
        }

        override fun handleExternalLocatorChange(
            view: EpubView,
            change: LayoutChange,
            didNavigateToLocator: Boolean
        ) = Unit

        override fun handlePlayingFragmentChanged(
            view: EpubView,
            change: LayoutChange,
            newFragment: String?,
            oldFragment: String?
        ) {
            if (newFragment != null && (newFragment != oldFragment || change.enteredPageLayout || change.playbackStarted)) {
                view.player?.let { player ->
                    view.schedulePagedClipChanged(newFragment, player)
                }
            }
        }

        override fun keepPlayingLocatorVisible(
            view: EpubView,
            change: LayoutChange,
            didNavigateToLocator: Boolean
        ) = Unit

        override fun submitReadingPreferences(view: EpubView, change: LayoutChange) {
            view.submitPreferencesIfChanged(view.epubPreferences())
        }

        override fun onTap(
            view: EpubView,
            event: TapEvent,
            directionalNavigationAdapter: DirectionalNavigationAdapter
        ): Boolean = directionalNavigationAdapter.onTap(event)

        override suspend fun isProgressionVisible(
            view: EpubView,
            currentLocator: Locator,
            progression: Double
        ): Boolean? = view.isPagedProgressionVisible(currentLocator, progression)

        override suspend fun findOnPage(view: EpubView, locator: Locator) {
            view.findOnPageInPagedLayout(locator)
        }
    }

    private object ScrollLayoutBehavior : EpubLayoutBehavior {
        override val supportsPagedClipScheduling = false

        override fun preferences(view: EpubView): EpubPreferences =
            view.epubPreferences(scroll = true)

        override fun submitModePreferences(view: EpubView, change: LayoutChange) {
            view.submitPreferencesIfChanged(view.epubPreferences(scroll = true))
        }

        override fun shouldNavigateToLocator(view: EpubView, change: LayoutChange): Boolean {
            val currentLocator = view.navigator?.currentLocator?.value
            return change.finalProps.locator?.let { locator ->
                change.layoutModeChanged ||
                    currentLocator == null ||
                    locator.href != currentLocator.href
            } ?: false
        }

        override fun navigateToLocator(view: EpubView, locator: Locator) {
            view.go(locator, scrollAfterNavigation = true)
        }

        override fun handleExternalLocatorChange(
            view: EpubView,
            change: LayoutChange,
            didNavigateToLocator: Boolean
        ) {
            val locator = change.finalProps.locator ?: return
            if (!didNavigateToLocator && change.locatorChangedFromExternalUpdate && !change.finalProps.isPlaying) {
                // In active playback, same-resource updates are centered below; go() top-aligns first.
                view.go(locator, scrollAfterNavigation = true)
            }
        }

        override fun handlePlayingFragmentChanged(
            view: EpubView,
            change: LayoutChange,
            newFragment: String?,
            oldFragment: String?
        ) = Unit

        override fun keepPlayingLocatorVisible(
            view: EpubView,
            change: LayoutChange,
            didNavigateToLocator: Boolean
        ) {
            val locator = change.finalProps.locator ?: return
            if (didNavigateToLocator || !change.finalProps.isPlaying) return

            view.activity?.lifecycleScope?.launch {
                view.scrollFragmentIntoView(locator)
            }
        }

        override fun submitReadingPreferences(view: EpubView, change: LayoutChange) = Unit

        override fun onTap(
            view: EpubView,
            event: TapEvent,
            directionalNavigationAdapter: DirectionalNavigationAdapter
        ): Boolean {
            val width = view.navigator?.publicationView?.width?.toDouble() ?: return false
            val horizontalEdgeSize = maxOf(80.0, 0.3 * width)
            val x = event.point.x.toDouble()

            return when {
                x <= horizontalEdgeSize -> {
                    view.handleScrollModeEdgeTap(goForward = false)
                    true
                }
                x >= width - horizontalEdgeSize -> {
                    view.handleScrollModeEdgeTap(goForward = true)
                    true
                }
                else -> false
            }
        }

        override suspend fun isProgressionVisible(
            view: EpubView,
            currentLocator: Locator,
            progression: Double
        ): Boolean? = view.isScrollProgressionVisible(progression)

        override suspend fun findOnPage(view: EpubView, locator: Locator) {
            view.findOnPageInScrollLayout(locator)
        }
    }

    private fun layoutBehavior(props: FinalizedProps? = this.props): EpubLayoutBehavior =
        if (props?.scrollMode == true) ScrollLayoutBehavior else PagedLayoutBehavior

    fun layoutPreferences(): EpubPreferences =
        layoutBehavior(props!!).preferences(this)

    fun epubPreferences(scroll: Boolean? = null): EpubPreferences {
        val props = props!!
        return if (scroll == null) {
            EpubPreferences(
                backgroundColor = org.readium.r2.navigator.preferences.Color(props.background),
                fontFamily = props.fontFamily,
                fontSize = props.fontSize,
                lineHeight = props.lineHeight,
                paragraphSpacing = props.paragraphSpacing,
                textAlign = props.textAlign,
                textColor = org.readium.r2.navigator.preferences.Color(props.foreground),
            )
        } else {
            EpubPreferences(
                backgroundColor = org.readium.r2.navigator.preferences.Color(props.background),
                fontFamily = props.fontFamily,
                fontSize = props.fontSize,
                lineHeight = props.lineHeight,
                paragraphSpacing = props.paragraphSpacing,
                scroll = scroll,
                textAlign = props.textAlign,
                textColor = org.readium.r2.navigator.preferences.Color(props.foreground),
            )
        }
    }

    // Comparing the whole EpubPreferences value covers every setting the navigator
    // consumes, including ones added later, instead of a hand-kept field list.
    private fun submitPreferencesIfChanged(preferences: EpubPreferences) {
        val navigator = navigator ?: return
        if (preferences == submittedPreferences) return
        navigator.submitPreferences(preferences)
        submittedPreferences = preferences
    }

    fun finalizeProps() {
        val oldProps = props

        val finalProps =
            FinalizedProps(
                bookUuid = pendingProps.bookUuid!!,
                locator = pendingProps.locator,
                isPlaying = pendingProps.isPlaying ?: oldProps?.isPlaying ?: false,
                highlights = pendingProps.highlights ?: oldProps?.highlights ?: listOf(),
                dictionaryEntries = pendingProps.dictionaryEntries
                    ?: oldProps?.dictionaryEntries ?: listOf(),
                activeDictionaryDecorationId = pendingProps.activeDictionaryDecorationId,
                bookmarks = pendingProps.bookmarks ?: oldProps?.bookmarks ?: listOf(),
                readaloudColor = pendingProps.readaloudColor
                    ?: oldProps?.readaloudColor ?: 0xffffff00.toInt(),
                readaloudDecoratorStyle = pendingProps.readaloudDecoratorStyle
                    ?: oldProps?.readaloudDecoratorStyle ?: "highlight",
                customFonts = pendingProps.customFonts ?: oldProps?.customFonts ?: listOf(),
                foreground = pendingProps.foreground
                    ?: oldProps?.foreground ?: "#111111".toColorInt(),
                background = pendingProps.background
                    ?: oldProps?.background ?: "#FFFFFF".toColorInt(),
                fontFamily = pendingProps.fontFamily
                    ?: oldProps?.fontFamily ?: FontFamily("Literata"),
                lineHeight = pendingProps.lineHeight ?: oldProps?.lineHeight ?: 1.4,
                paragraphSpacing = pendingProps.paragraphSpacing
                    ?: oldProps?.paragraphSpacing ?: 0.5,
                fontSize = pendingProps.fontSize ?: oldProps?.fontSize ?: 1.0,
                textAlign = pendingProps.textAlign ?: oldProps?.textAlign ?: TextAlign.JUSTIFY,
                marginLeft = pendingProps.marginLeft ?: oldProps?.marginLeft ?: 0,
                marginRight = pendingProps.marginRight ?: oldProps?.marginRight ?: 0,
                scrollMode = pendingProps.scrollMode ?: oldProps?.scrollMode ?: false,
                jumpBarBottom = pendingProps.jumpBarBottom ?: oldProps?.jumpBarBottom ?: 0.0
            )

        props = finalProps
        if (finalProps.isPlaying || (oldProps?.scrollMode == true && !finalProps.scrollMode) ||
            (oldProps != null && oldProps.jumpBarBottom > 0.0 && finalProps.jumpBarBottom <= 0.0)
        ) {
            pendingJumpLocator = null
            cancelJumpPosition()
        }
        // Changing the overlay alone must not run navigation or playback follow.
        if (oldProps != null && finalProps.jumpBarBottom != oldProps.jumpBarBottom &&
            finalProps.copy(jumpBarBottom = oldProps.jumpBarBottom) == oldProps
        ) return

        val scrollModeChanged = oldProps?.let { finalProps.scrollMode != it.scrollMode } ?: false
        val customFontsChanged = finalProps.customFonts != oldProps?.customFonts
        val fontFamilyChanged = finalProps.fontFamily != oldProps?.fontFamily
        val enteredPageLayout = oldProps?.scrollMode == true && !finalProps.scrollMode
        // Only a changed locator can be an echo, and taking it removes it from the pending list.
        val propLocator = finalProps.locator
        val locatorChangedFromExternalUpdate = propLocator != null &&
            !isSameLocatorPosition(propLocator, oldProps?.locator) &&
            !takeLocatorEcho(propLocator)
        val playbackStarted = finalProps.isPlaying && oldProps?.isPlaying != true
        val layoutChange = LayoutChange(
            finalProps = finalProps,
            layoutModeChanged = scrollModeChanged,
            enteredPageLayout = enteredPageLayout,
            locatorChangedFromExternalUpdate = locatorChangedFromExternalUpdate,
            playbackStarted = playbackStarted,
        )
        val layoutBehavior = layoutBehavior(finalProps)

        if (finalProps.bookUuid != oldProps?.bookUuid) {
            dictionaryOccurrenceCache.clear()
        }

        if (
            finalProps.bookUuid != oldProps?.bookUuid ||
            customFontsChanged
        ) {
            destroyNavigator()
            initializeNavigator()
        }

        if (customFontsChanged) {
            applyCustomFonts(finalProps.customFonts)
        }

        if (fontFamilyChanged) {
            injectCustomFonts()
        }

        layoutBehavior.submitModePreferences(this, layoutChange)
        val shouldNavigateToLocator = layoutBehavior.shouldNavigateToLocator(this, layoutChange)
        if (shouldNavigateToLocator) {
            finalProps.locator?.let { locator ->
                layoutBehavior.navigateToLocator(this, locator)
            }
        } else {
            layoutBehavior.handleExternalLocatorChange(
                this,
                layoutChange,
                didNavigateToLocator = false
            )
        }

        if (finalProps.marginLeft != oldProps?.marginLeft) {
            setCssVar("--st-padding-left", "${finalProps.marginLeft}px")
        }

        if (finalProps.marginRight != oldProps?.marginRight) {
            setCssVar("--st-padding-right", "${finalProps.marginRight}px")
        }

        if (finalProps.isPlaying && finalProps.locator != null) {
            applyReadaloudDecoration(finalProps.locator!!)

            val newFragment = finalProps.locator?.locations?.fragments?.firstOrNull()
            val oldFragment = oldProps?.locator?.locations?.fragments?.firstOrNull()

            layoutBehavior.handlePlayingFragmentChanged(
                this,
                layoutChange,
                newFragment,
                oldFragment
            )
        } else {
            clearReadaloudDecoration()
        }

        if (finalProps.highlights != oldProps?.highlights) {
            decorateHighlights()
        }

        if (
            finalProps.dictionaryEntries != oldProps?.dictionaryEntries ||
            finalProps.foreground != oldProps?.foreground
        ) {
            if (finalProps.dictionaryEntries != oldProps?.dictionaryEntries) {
                updateDictionaryWords(finalProps.dictionaryEntries)
            }
            decorateDictionaryEntries()
        }

        if (finalProps.activeDictionaryDecorationId != oldProps?.activeDictionaryDecorationId) {
            setActiveDictionaryDecoration(finalProps.activeDictionaryDecorationId)
        }

        if (finalProps.bookmarks != oldProps?.bookmarks && finalProps.locator != null) {
            activity?.lifecycleScope?.launch { findOnPage(finalProps.locator!!) }
        }

        layoutBehavior.keepPlayingLocatorVisible(
            this,
            layoutChange,
            didNavigateToLocator = shouldNavigateToLocator
        )
        layoutBehavior.submitReadingPreferences(this, layoutChange)
    }

    fun initializeNavigator() {
        val publication = BookService.getPublication(props!!.bookUuid) ?: return

        val fragmentTag = resources.getString(R.string.epub_fragment_tag)
        val activity: FragmentActivity? = appContext.currentActivity as FragmentActivity?

        firstPageLoaded = CompletableDeferred()

        lastDictionarySearchHref = null
        dictionaryOccurrences = listOf()
        webViews.clear()
        webViewScrollPositions.clear()

        val listener = this
        val epubFragment = EpubFragment(
            publication,
            listener
        )

        activity?.supportFragmentManager?.commitNow {
            setReorderingAllowed(true)
            add(epubFragment, fragmentTag)
        }

        addView(epubFragment.view)

        navigator = epubFragment.navigator
        // The fragment was created with these initial preferences already applied.
        submittedPreferences = layoutPreferences()
        navigator?.publicationView?.let { disableOverscroll(it) }

        decorateHighlights()
        decorateDictionaryEntries()

        navigator?.addDecorationListener("highlights", this)
        navigator?.addDecorationListener("dictionary", this)

        val directionalNavigationAdapter = DirectionalNavigationAdapter(
            navigator!!,
            animatedTransition = true,
        )

        navigator?.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                return layoutBehavior().onTap(
                    this@EpubView,
                    event,
                    directionalNavigationAdapter
                )
            }

            override fun onKey(event: ReadiumKeyEvent): Boolean {
                return directionalNavigationAdapter.onKey(event)
            }
        })

        navigator?.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                onMiddleTouch(mapOf())
                return true
            }
        })

        locationEmitter = activity?.lifecycleScope?.launch {
            firstPageLoaded.await()
            navigator?.currentLocator?.collect {
                onLocatorChanged(it)
            }
            emitCurrentLocator()
        }

        current = this
    }

    fun destroyNavigator() {
        resetSelectionLayout()
        pendingJumpLocator = null
        cancelJumpPosition()
        submittedPreferences = null
        locationEmitter?.cancel()
        locationEmitter = null

        readaloudDecorationJob?.cancel()
        readaloudDecorationJob = null
        readaloudDecorationGeneration++
        requestedReadaloudDecoration = null
        requestedReadaloudDecorationVersion = 0
        appliedReadaloudDecorationVersion = 0

        dictionarySearchJob?.cancel()
        dictionarySearchJob = null
        dictionaryDecorationJob?.cancel()
        dictionaryDecorationJob = null
        decoratedDictionaryHref = null
        lastDictionarySearchHref = null
        dictionaryOccurrences = listOf()
        webViews.clear()
        webViewScrollPositions.clear()

        val navigator = this.navigator ?: return
        val activity: FragmentActivity? = appContext.currentActivity as FragmentActivity?
        // allowStateLoss prevents throwing when the navigator is unmounted while
        // the app is backgrounded
        activity?.supportFragmentManager?.commitNow(allowStateLoss = true) {
            setReorderingAllowed(true)
            remove(navigator.requireParentFragment())
        }

        removeView(navigator.view)

        this.navigator = null

        if (current === this) {
            current = null
        }
    }

    // Selecting the first paragraph can drag a handle past the top of the page.
    // The WebView then overscrolls and briefly paints the window background (the
    // gray flash) while the content moves under the finger. Turn overscroll off
    // for the pager and every page WebView.
    private fun disableOverscroll(view: View) {
        view.overScrollMode = View.OVER_SCROLL_NEVER
        if (view is WebView && webViews.add(view)) {
            // Chromium auto-scrolls the page while a selection handle is dragged
            // near an edge. In the first paragraph that scroll changes the text
            // under the focus many times a second. Pin the page while selecting;
            // the handle can still move within the visible page.
            val webView = view
            webView.setOnScrollChangeListener { _, _, _, _, _ ->
                if (!isSelecting) return@setOnScrollChangeListener
                val position = webViewScrollPositions[webView] ?: return@setOnScrollChangeListener
                if (webView.scrollX != position.first || webView.scrollY != position.second) {
                    webView.scrollTo(position.first, position.second)
                }
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                disableOverscroll(view.getChildAt(i))
            }
        }
    }

    private fun setCssVar(name: String, value: String) {
        val activity: FragmentActivity? = appContext.currentActivity as FragmentActivity?

        activity?.lifecycleScope?.launch {
            navigator?.evaluateJavascript(
                """
                (function() {
                  document.body.style.setProperty('${name}', '${value}')
                })();
            """.trimIndent()
            )
        }
    }

    private suspend fun emitCurrentLocator() {
        val currentLocator = navigator?.currentLocator?.value ?: return
        val propLocator = props?.locator
        val isPropLocatorOnPage = propLocator?.locations?.fragments?.firstOrNull()?.let {
            val result = navigator?.evaluateJavascript(
                """
            (function() {
                if (!globalThis.storyteller || !storyteller.isFirstClientRectOnScreen) return false;
                const element = document.getElementById(${JSONObject.quote(it)});
                return storyteller.isFirstClientRectOnScreen(element);
            })();
            """.trimIndent()
            )
            result?.let { Json.decodeFromString<Boolean?>(it) }
        } ?: propLocator?.locations?.progression?.let {
            layoutBehavior().isProgressionVisible(this, currentLocator, it)
        } ?: false

        // The navigator can be torn down while the JavaScript above is suspended.
        val navigator = navigator ?: return
        val found = navigator.firstVisibleElementLocator()
        if (found == null) {
            // If the locator specified by the prop is still on the page, don't emit
            // a change event. We haven't actually changed the page.
            if (isPropLocatorOnPage) return
            emitLocatorChange(currentLocator)
            return
        }

        val merged = currentLocator.copy(
            locations = currentLocator.locations.copy(
                fragments = found.locations.fragments,
                otherLocations = found.locations.otherLocations,
            ),
        )

        // If the locator specified by the prop is still on the page,
        // we still need to emit if we're adding fragments that we didn't
        // have initially
        if (isPropLocatorOnPage && (props?.locator?.locations?.fragments?.size ?: 0) > 0) {
            return
        }

        emitLocatorChange(merged)
    }

    /**
     * Read the selection as it is at the moment of the call, not when the action mode was
     * created. The user can drag the handles after that, and the saved locator and text must
     * follow the handles. The live DOM text covers selections that span paragraphs or frames.
     */
    suspend fun getCurrentSelection(): Map<String, Any>? = withTimeoutOrNull(1000L) {
        withContext(Dispatchers.Main) {
            val nav = navigator ?: return@withContext null
            val selection = nav.currentSelection() ?: return@withContext null
            val liveText = nav.evaluateJavascript(
                "storyteller.getSelectionText ? storyteller.getSelectionText() : window.getSelection().toString()"
            )?.let { result ->
                runCatching { Json.decodeFromString<String?>(result) }.getOrNull()
            }?.takeIf { it.isNotBlank() }
            // The two reads are separate calls, so the selection may have moved between them. Trust the
            // live text only when it contains the locator's highlight, otherwise keep the locator's text.
            val locatorHighlight = selection.locator.text.highlight.orEmpty()
            val consistentLive = liveText?.takeIf {
                locatorHighlight.isBlank() ||
                    normalizeSelectionText(it).contains(normalizeSelectionText(locatorHighlight))
            }
            val text = consistentLive ?: locatorHighlight
            val locator = when {
                consistentLive == null -> selection.locator
                consistentLive == locatorHighlight -> selection.locator
                // before/after come from the first block; a cross-block selection must not carry them.
                else -> selection.locator.copy(
                    text = selection.locator.text.copy(before = null, after = null, highlight = consistentLive)
                )
            }
            mapOf("text" to text, "locator" to locator.toJSON().toMap())
        }
    }

    private fun normalizeSelectionText(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /**
     * Collapse the webview's native text selection, which also hides the selection box
     * and its handles. The app's selection menu draws a backdrop over the reader, so a
     * dismissing tap never reaches the webview and the selection would otherwise stay.
     */
    fun clearSelection() {
        val navigator = navigator ?: return

        try {
            navigator.clearSelection()
        } catch (e: Exception) {
            Log.w("StorytellerSelection", "Could not clear the navigator selection", e)
        }

        // Belt and braces: some layouts (scroll mode) run the selection inside a
        // different frame than the one the navigator command targets.
        activity?.lifecycleScope?.launch {
            navigator.evaluateJavascript(
                """
                (function() {
                  const selection = window.getSelection();
                  if (selection) selection.removeAllRanges();
                })();
                """.trimIndent()
            )
        }
    }

    /**
     * Readium builds a selection's locator from the page's locator, which names no audio
     * clip, so a saved word could not be matched to its clip later. This adds the id of the
     * clip the selection starts in, or the nearest clip when it starts outside all of them,
     * while the page is live and the position is exact. A book without clips, or a page that
     * cannot answer, leaves [locator] as it was.
     */
    suspend fun locatorWithClip(locator: Locator): Locator {
        val bookUuid = props?.bookUuid ?: return locator
        val clipIds = BookService.getFragments(bookUuid, locator).map { it.fragmentId }
        if (clipIds.isEmpty()) return locator

        val result = navigator?.evaluateJavascript(
            """
            (function(clipIds) {
                const selection = window.getSelection();
                if (!selection || selection.rangeCount === 0) return null;

                const range = selection.getRangeAt(0);
                let start = range.startContainer;
                if (start.nodeType === Node.ELEMENT_NODE && range.startOffset < start.childNodes.length) {
                    start = start.childNodes[range.startOffset];
                }

                const clips = new Set(clipIds);
                let element = start.nodeType === Node.ELEMENT_NODE ? start : start.parentElement;
                for (; element; element = element.parentElement) {
                    if (clips.has(element.id)) return element.id;
                }

                const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_ELEMENT);
                walker.currentNode = start;
                for (let next = walker.nextNode(); next; next = walker.nextNode()) {
                    if (clips.has(next.id)) return next.id;
                }
                walker.currentNode = start;
                for (let previous = walker.previousNode(); previous; previous = walker.previousNode()) {
                    if (clips.has(previous.id)) return previous.id;
                }
                return null;
            })(${JSONArray(clipIds)});
            """.trimIndent()
        ) ?: return locator

        val clipId = runCatching { Json.decodeFromString<String?>(result) }.getOrNull()
            ?: return locator

        return locator.copy(
            locations = locator.locations.copy(
                otherLocations = locator.locations.otherLocations + (BookService.CLIP_ID_LOCATION_KEY to clipId)
            )
        )
    }

    private fun cancelJumpPosition() {
        jumpPositionGeneration++
        jumpPositionJob?.cancel()
        jumpPositionJob = null
    }

    @JavascriptInterface
    fun isJumpPositionCurrent(generation: Int): Boolean = jumpPositionGeneration == generation

    fun go(locator: Locator, scrollAfterNavigation: Boolean = false) {
        cancelJumpPosition()
        // Null when finalizeProps rebuilt the navigator but the publication did not load.
        val navigator = navigator ?: return
        val fragment = locator.locations.fragments.firstOrNull()
        val bookUuid = props?.bookUuid
        // Older selections put the clip ID in fragments rather than metadata.
        val navigationLocator = if (
            fragment != null &&
            !locator.text.highlight.isNullOrBlank() &&
            locator.locations.otherLocations[BookService.CLIP_ID_LOCATION_KEY] !is String &&
            bookUuid != null &&
            BookService.getFragments(bookUuid, locator).any { it.fragmentId == fragment }
        ) {
            locator.copy(
                locations = locator.locations.copy(
                    fragments = locator.locations.fragments.drop(1),
                    otherLocations = locator.locations.otherLocations +
                        (BookService.CLIP_ID_LOCATION_KEY to fragment)
                )
            )
        } else {
            locator
        }
        if (locator.href != navigator.currentLocator.value.href) {
            changingResource = true
        }
        val isPausedTextJump = scrollAfterNavigation && props?.isPlaying == false &&
            !navigationLocator.text.highlight.isNullOrBlank()
        pendingJumpLocator = if (isPausedTextJump) {
            navigationLocator
        } else {
            null
        }
        navigator.go(navigationLocator, true)
        val activity: FragmentActivity? = appContext.currentActivity as FragmentActivity?
        if (scrollAfterNavigation && !isPausedTextJump) {
            activity?.lifecycleScope?.launch {
                scrollFragmentIntoView(navigationLocator)
            }
        }
    }

    private suspend fun scrollFragmentIntoView(locator: Locator) {
        val id = locator.locations.fragments.firstOrNull() ?: return
        navigator?.evaluateJavascript(
            """
            (function() {
                if (!globalThis.storyteller || !storyteller.scrollElementIntoView) return false;
                const element = document.getElementById(${JSONObject.quote(id)});
                return storyteller.scrollElementIntoView(element);
            })();
            """.trimIndent()
        )
    }

    private suspend fun isAtScrollBoundary(end: Boolean): Boolean {
        val result = navigator?.evaluateJavascript(
            """
            (function() {
                const scroller = document.scrollingElement || document.documentElement;
                const viewportHeight = window.innerHeight;
                const maxScrollTop = Math.max(0, scroller.scrollHeight - viewportHeight);
                const tolerance = 1;

                return ${if (end) {
                    "scroller.scrollTop >= maxScrollTop - tolerance"
                } else {
                    "scroller.scrollTop <= tolerance"
                }};
            })();
            """.trimIndent()
        ) ?: return false

        return Json.decodeFromString<Boolean?>(result) ?: false
    }

    private fun handleScrollModeEdgeTap(goForward: Boolean) {
        activity?.lifecycleScope?.launch {
            if (isAtScrollBoundary(end = goForward)) {
                val navigator = navigator ?: return@launch
                if (goForward) {
                    scrollModeChapterNavigator.goForward(navigator, animated = true)
                } else {
                    scrollModeChapterNavigator.goBackward(navigator, animated = true)
                }
            } else {
                scrollCurrentResource(goForward = goForward)
            }
        }
    }

    private suspend fun scrollCurrentResource(goForward: Boolean): Boolean {
        val direction = if (goForward) 1 else -1
        val result = navigator?.evaluateJavascript(
            """
            (function() {
                const scroller = document.scrollingElement || document.documentElement;
                const viewportHeight = window.innerHeight;
                const maxScrollTop = Math.max(0, scroller.scrollHeight - viewportHeight);
                const delta = Math.max(96, viewportHeight * 0.65) * $direction;
                const targetScrollTop = Math.min(maxScrollTop, Math.max(0, scroller.scrollTop + delta));

                if (Math.abs(targetScrollTop - scroller.scrollTop) < 1) return false;

                try {
                    scroller.scrollTo({ top: targetScrollTop, behavior: 'smooth' });
                } catch (_) {
                    scroller.scrollTop = targetScrollTop;
                }

                return true;
            })();
            """.trimIndent()
        ) ?: return false

        return Json.decodeFromString<Boolean?>(result) ?: false
    }

    private suspend fun isScrollProgressionVisible(progression: Double): Boolean? {
        val result = navigator?.evaluateJavascript(
            """
            (function() {
                const scroller = document.scrollingElement || document.documentElement;
                const viewportHeight = window.innerHeight;
                const visibleTopProgression = scroller.scrollHeight > 0
                    ? scroller.scrollTop / scroller.scrollHeight
                    : 0;
                const visibleBottomProgression = scroller.scrollHeight > 0
                    ? (scroller.scrollTop + viewportHeight) / scroller.scrollHeight
                    : 1;

                return $progression >= visibleTopProgression &&
                    $progression <= visibleBottomProgression;
            })();
            """.trimIndent()
        ) ?: return null

        return Json.decodeFromString<Boolean?>(result)
    }

    private suspend fun isPagedProgressionVisible(
        currentLocator: Locator,
        progression: Double
    ): Boolean? {
        val result = navigator?.evaluateJavascript(
            """
            (function() {
                const width = Android.getViewportWidth();
                const pageWidth = width / window.devicePixelRatio;

                function snapOffset(offset) {
                    const value = offset + 1;
                    return value - (value % pageWidth);
                }

                const documentWidth = document.scrollingElement.scrollWidth;
                const currentPageStart = snapOffset(documentWidth * ${currentLocator.locations.progression});
                const currentPageEnd = currentPageStart + pageWidth;
                return $progression * documentWidth >= currentPageStart &&
                    $progression * documentWidth < currentPageEnd;
            })();
            """.trimIndent()
        ) ?: return null

        return Json.decodeFromString<Boolean?>(result)
    }

    suspend fun getFragmentPageProportion(fragmentId: String): Map<String, Any>? {
        val nav = navigator ?: return null

        val result = nav.evaluateJavascript("""
            (function() {
                return storyteller.getFragmentPageProportion("$fragmentId");
            })();
        """.trimIndent()) ?: return null

        return try {
            val json = JSONObject(result)
            mapOf(
                "crossesPage" to json.getBoolean("crossesPage"),
                "proportionOnCurrentPage" to json.getDouble("proportionOnCurrentPage")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun handleClipChanged(fragmentId: String, player: AudiobookPlayer) {
        layoutBehavior().handlePlayingFragmentChanged(
            this,
            LayoutChange(
                finalProps = props ?: return,
                layoutModeChanged = false,
                enteredPageLayout = false,
                locatorChangedFromExternalUpdate = false,
                playbackStarted = false,
            ),
            newFragment = fragmentId,
            oldFragment = null
        )
    }

    private fun schedulePagedClipChanged(fragmentId: String, player: AudiobookPlayer) {
        val activity = activity ?: return

        activity.lifecycleScope.launch {
            val result = getFragmentPageProportion(fragmentId) ?: return@launch
            val crossesPage = result["crossesPage"] as? Boolean ?: return@launch
            if (!crossesPage) return@launch

            val proportion = result["proportionOnCurrentPage"] as? Double ?: return@launch

            player.scheduleClipEvent(fragmentId, proportion) {
                activity.lifecycleScope.launch {
                    if (!layoutBehavior().supportsPagedClipScheduling) return@launch

                    val recheck = getFragmentPageProportion(fragmentId)
                    val overflowsRight = (recheck?.get("crossesPage") as? Boolean) ?: false

                    if (overflowsRight) {
                        // not animated
                        navigator?.goForward()
                    }
                }
            }
        }
    }

    // A tap that lands on a decoration still leaves a collapsed caret in the web
    // view, and the reader reports that as the selection being cleared. It arrives
    // a few dozen milliseconds after the tap, which would close the menu the tap
    // just opened, so it is ignored for a moment after any decoration is activated.
    private var ignoreSelectionClearedUntil: Long = 0

    override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
        val rect = event.rect ?: return false
        val x = ceil(rect.centerX() / this.resources.displayMetrics.density).toInt()
        val y = ceil(rect.top / this.resources.displayMetrics.density).toInt() - 16
        val id = event.decoration.id
        ignoreSelectionClearedUntil = System.currentTimeMillis() + 400
        if (id.startsWith("dictionary:")) {
            setActiveDictionaryDecoration(id)
            this.onDictionaryTap(
                mapOf(
                    "uuid" to dictionaryUuid(id),
                    "decorationId" to id,
                    "locator" to event.decoration.locator.toJSON().toMap(),
                    "x" to x,
                    "y" to y
                )
            )
        } else {
            this.onHighlightTap(mapOf("decoration" to id, "x" to x, "y" to y))
        }
        return true
    }

    fun decorateHighlights() {
        val decorations = props!!.highlights.map {
            val style = Decoration.Style.Highlight(it.color, isActive = true)
            return@map Decoration(
                id = it.id,
                locator = it.locator,
                style = style
            )
        }

        val activity: FragmentActivity? = appContext.currentActivity as FragmentActivity?
        activity?.lifecycleScope?.launch {
            navigator?.applyDecorations(decorations, group = "highlights")
        }
    }

    // The mark whose dictionary menu is open, drawn with a selection wash so it is
    // obvious which copy a tap landed on, the way ReadEra does it. The id names one
    // copy of a word, so a word marked several times in a chapter washes only where
    // it was tapped.
    private var activeDictionaryId: String? = null

    // Last page index seen, used to tell a real page turn from a page recompute.
    private var lastPageIndex: Int? = null

    // The saved words the view knows about, each with the book its stored locator
    // belongs to. Kept in step with the dictionaryEntries prop.
    private var dictionaryWords: List<DictionaryWord> = listOf()

    // The marks found in the chapter on screen, painted by decorateDictionaryEntries.
    private var dictionaryOccurrences: List<DictionaryOccurrence> = listOf()

    // The chapter the last search was started for. A page turn inside a chapter
    // leaves it alone, so turning pages never starts a search.
    private var lastDictionarySearchHref: Url? = null
    private var dictionarySearchJob: Job? = null

    private val dictionaryTextCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > DICTIONARY_CACHE_SIZE
    }

    private val dictionaryOccurrenceCache =
        object : LinkedHashMap<String, List<DictionaryOccurrence>>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, List<DictionaryOccurrence>>?
            ): Boolean = size > DICTIONARY_CACHE_SIZE
        }

    // Applying decorations is not safe to do twice at once: the reader diffs the new
    // list against the old one, and two overlapping diffs read each other's lists and
    // crash. A search finishing while a page turn repaints would do exactly that, so
    // every application waits its turn, and a burst of updates is applied once.
    private val dictionaryDecorationMutex = Mutex()
    private var dictionaryDecorations: List<Decoration> = listOf()
    private var dictionaryDecorationJob: Job? = null
    private var dictionaryDecorationPending = false
    private var decoratedDictionaryHref: Url? = null

    // The readaloud highlight is one decoration that changes on every clip. It is
    // applied through a single worker so a slow, older application cannot land after
    // a newer one and pull the highlight back to the previous sentence.
    private val readaloudDecorationMutex = Mutex()
    private var readaloudDecorationJob: Job? = null
    private var readaloudDecorationGeneration = 0
    private var requestedReadaloudDecoration: Decoration? = null
    private var requestedReadaloudDecorationVersion = 0
    private var appliedReadaloudDecorationVersion = 0

    private fun dictionaryCacheKey(bookUuid: String, href: Url): String = "$bookUuid|$href"

    // A changed set of saved words makes the marks found so far stale, so the
    // chapter on screen is searched again. The extracted text does not depend on
    // the words and stays cached.
    private fun updateDictionaryWords(entries: List<DictionaryEntryDecoration>) {
        dictionaryWords = entries.map { DictionaryWord(it.id, it.word, it.bookUuid) }
        dictionaryOccurrenceCache.clear()
        dictionaryOccurrences = listOf()

        val href = lastDictionarySearchHref ?: return
        searchDictionaryEntries(href)
    }

    /**
     * Finds every copy of every saved word in the chapter at [href].
     *
     * The chapter text is read once and cached, and so are the marks found in it,
     * so a chapter the reader returns to is painted without searching again. A
     * search still running when the reader moves to another chapter is cancelled.
     */
    private fun searchDictionaryEntries(href: Url) {
        val bookUuid = props?.bookUuid ?: return
        val words = dictionaryWords
        val key = dictionaryCacheKey(bookUuid, href)

        dictionarySearchJob?.cancel()
        dictionarySearchJob = null

        if (words.isEmpty()) {
            dictionaryOccurrences = listOf()
            decorateDictionaryEntries()
            return
        }

        val cached = dictionaryOccurrenceCache[key]
        if (cached != null) {
            dictionaryOccurrences = cached
            decorateDictionaryEntries()
            return
        }

        dictionaryOccurrences = listOf()

        val activity = activity ?: return
        dictionarySearchJob = activity.lifecycleScope.launch {
            val text = dictionaryTextCache[key]
                ?: DictionarySearch.extractChapterText(bookUuid, href)?.also {
                    dictionaryTextCache[key] = it
                }

            val occurrences =
                if (text == null) listOf()
                else DictionarySearch.findOccurrences(bookUuid, href, text, words)


            dictionaryOccurrenceCache[key] = occurrences

            if (lastDictionarySearchHref == href) {
                dictionaryOccurrences = occurrences
                decorateDictionaryEntries()
            }
        }
    }

    fun decorateDictionaryEntries() {
        val props = props ?: return
        val wash = dictionaryWash(props.background)
        val tint = props.foreground
        val bookUuid = props.bookUuid
        val currentHref = navigator?.currentLocator?.value?.href
        val occurrences = dictionaryOccurrences.filter { it.locator.href == currentHref }
        val searchedUuids = occurrences.map { it.uuid }.toSet()

        // Which copy each saved word was saved from. The stored locator is matched
        // by the quote it carries rather than by its progression or position, and
        // only against this book and this chapter, so a locator from elsewhere can
        // never mark the wrong copy. A quote that matches no copy here, or more
        // than one, proves nothing: every copy then stays drawn normally.
        val savedEntries = props!!.dictionaryEntries.filter { entry ->
            entry.bookUuid == bookUuid &&
                entry.locator.href == currentHref &&
                !entry.locator.text.highlight.isNullOrBlank()
        }
        val sparseIds = savedEntries
            .flatMap { entry ->
                val matches = occurrences.filter { occurrence ->
                    sameSavedQuote(occurrence.locator.text, entry.locator.text)
                }
                if (matches.size == 1) {
                    listOf("dictionary:${matches[0].uuid}#${matches[0].index}")
                } else {
                    emptyList()
                }
            }
            .toSet()

        val fromSearch = occurrences.map { occurrence ->
            val id = "dictionary:${occurrence.uuid}#${occurrence.index}"
            Decoration(
                id = id,
                locator = occurrence.locator,
                style = DictionaryUnderlineStyle(
                    tint = tint,
                    wash = wash,
                    active = activeDictionaryId == id,
                    sparse = id in sparseIds
                )
            )
        }

        // A saved locator only means anything in the book it was saved from: two
        // books can share a chapter href, and the stored position would then land
        // in the wrong place. It is painted only when the search found nothing for
        // that word in this chapter. The stored locator is the saved place itself,
        // so it draws the sparser row.
        val fromSavedLocator = props.dictionaryEntries
            .filter { entry ->
                entry.bookUuid == props.bookUuid &&
                    entry.locator.href == currentHref &&
                    entry.id !in searchedUuids
            }
            .map { entry ->
                val id = "dictionary:${entry.id}#0"
                Decoration(
                    id = id,
                    locator = entry.locator,
                    style = DictionaryUnderlineStyle(
                        tint = tint,
                        wash = wash,
                        active = activeDictionaryId == id,
                        sparse = true
                    )
                )
            }

        dictionaryDecorations = fromSearch + fromSavedLocator
        applyDictionaryDecorations()
    }

    // True when the quote found in the chapter is the quote the word was saved
    // from. Whitespace, punctuation and case are flattened first. The stored
    // snippet is longer than the one the chapter search keeps, so only the
    // overlapping edge is compared: the text before the word has to end the stored
    // text before it, and the text after has to start the stored text after it.
    private fun sameSavedQuote(found: Locator.Text, stored: Locator.Text): Boolean {
        val highlight = normalizeQuote(found.highlight)
        if (highlight.isEmpty()) return false
        if (highlight != normalizeQuote(stored.highlight)) return false
        return normalizeQuote(stored.before).endsWith(normalizeQuote(found.before)) &&
            normalizeQuote(stored.after).startsWith(normalizeQuote(found.after))
    }

    private fun normalizeQuote(text: String?): String =
        text?.replace(quoteSeparatorRun, " ")?.trim()?.lowercase(Locale.ROOT) ?: ""

    private fun applyDictionaryDecorations() {
        // A burst of updates is painted once: the running painter takes whatever the
        // newest list is when it comes round again.
        if (dictionaryDecorationJob?.isActive == true) {
            dictionaryDecorationPending = true
            return
        }

        val activity = appContext.currentActivity as? FragmentActivity ?: return

        dictionaryDecorationJob = activity.lifecycleScope.launch {
            do {
                dictionaryDecorationPending = false
                delay(DICTIONARY_DECORATION_SETTLE_MS)
                dictionaryDecorationMutex.withLock {
                    val href = navigator?.currentLocator?.value?.href
                    // Leaving a chapter clears its marks first, so the new chapter is
                    // always painted from an empty list. The reader then only has to
                    // work out insertions, which cannot go wrong however the two
                    // chapters differ in length. Emptying skips its diff altogether.
                    if (href != decoratedDictionaryHref) {
                        decoratedDictionaryHref = href
                        navigator?.applyDecorations(listOf(), group = "dictionary")
                    }
                    navigator?.applyDecorations(dictionaryDecorations, group = "dictionary")
                    delay(DICTIONARY_DECORATION_GAP_MS)
                }
                // The marks are drawn on the next animation frame, so this runs after the
                // gap above has let them land. It is not awaited: the reader holds the call
                // until its page has loaded, and the painter must not wait on that.
                activity.lifecycleScope.launch {
                    navigator?.evaluateJavascript(removeDuplicateDictionaryMarks)
                }
            } while (dictionaryDecorationPending)
        }
    }

    // Matches the light blue the reader already paints over a text selection, so a
    // tapped word looks selected rather than specially coloured.
    private fun dictionaryWash(background: Int): Int {
        val luminance =
            (Color.red(background) + Color.green(background) + Color.blue(background)) / 3
        return if (luminance < 128) {
            Color.argb(96, 180, 216, 254)
        } else {
            Color.rgb(180, 216, 254)
        }
    }

    fun setActiveDictionaryDecoration(id: String?) {
        if (activeDictionaryId == id) return
        activeDictionaryId = id
        // Clearing the wash repaints the group from empty. A mark the reader left
        // behind on an earlier paint still carries the active class, and the diff
        // only knows the marks in the newest list, so it would stay washed.
        if (id == null) decoratedDictionaryHref = null
        decorateDictionaryEntries()
    }

    // A mark's id names the saved word and which copy of it was tapped:
    // `dictionary:<uuid>#<n>`. The menu only needs the uuid.
    private fun dictionaryUuid(decorationId: String): String {
        val body = decorationId.removePrefix("dictionary:")
        val suffix = body.lastIndexOf('#')
        return if (suffix >= 0) body.substring(0, suffix) else body
    }

    fun applyReadaloudDecoration(locator: Locator) {
        // The readaloud mark is the only decoration in the overlay group. The id
        // stays the same within one chapter, so Readium moves the mark instead of
        // removing and adding it on every clip. It changes when the chapter href
        // changes, so Readium removes the old chapter's mark.
        if (locator.locations.fragments.firstOrNull() == null) return

        val style =
            if (props!!.readaloudDecoratorStyle == "underline") {
                Decoration.Style.Underline(props!!.foreground)
            } else {
                Decoration.Style.Highlight(props!!.readaloudColor, isActive = true)
            }
        requestReadaloudDecoration(Decoration("storyteller-readaloud:${locator.href}", locator, style))
    }

    fun clearReadaloudDecoration() {
        requestReadaloudDecoration(null)
    }

    // Readium diffs a decoration group against the list it last received. Two
    // applications started close together read each other's lists, and their
    // JavaScript commands can land in the opposite order, leaving the readaloud
    // highlight on an older sentence. Keep one worker and let it paint the newest
    // request, so the last clip always wins.
    private fun requestReadaloudDecoration(decoration: Decoration?) {
        if (
            requestedReadaloudDecorationVersion == appliedReadaloudDecorationVersion &&
            requestedReadaloudDecoration == decoration
        ) return

        requestedReadaloudDecoration = decoration
        requestedReadaloudDecorationVersion++

        if (readaloudDecorationJob?.isActive == true) return

        val activity = appContext.currentActivity as? FragmentActivity ?: return
        readaloudDecorationJob = activity.lifecycleScope.launch {
            do {
                val generation = readaloudDecorationGeneration
                val version = requestedReadaloudDecorationVersion
                val pending = requestedReadaloudDecoration

                readaloudDecorationMutex.withLock {
                    // A newer request may have arrived while this worker was waiting
                    // for the previous paint. A rebuilt navigator invalidates the
                    // whole request. Skip stale work and let the loop pick up the
                    // newest request.
                    if (generation != readaloudDecorationGeneration ||
                        version != requestedReadaloudDecorationVersion
                    ) {
                        return@withLock
                    }

                    val nav = navigator
                    if (nav == null) {
                        // Nothing to paint while the view is being rebuilt. Mark this
                        // request done, but forget it when it is still the newest one so
                        // the next finalizeProps asks again instead of being deduped.
                        appliedReadaloudDecorationVersion = version
                        if (requestedReadaloudDecorationVersion == version) {
                            requestedReadaloudDecoration = null
                        }
                    } else {
                        nav.applyDecorations(listOfNotNull(pending), "overlay")
                        appliedReadaloudDecorationVersion = version
                    }
                }
            } while (appliedReadaloudDecorationVersion != requestedReadaloudDecorationVersion)
        }
    }

    suspend fun findOnPage(locator: Locator) {
        layoutBehavior().findOnPage(this, locator)
    }

    private suspend fun findOnPageInScrollLayout(locator: Locator) {
        val epubNav = navigator ?: return

        val bookmarkCandidates = props!!.bookmarks.mapIndexedNotNull { index, bookmark ->
            if (bookmark.href != locator.href) return@mapIndexedNotNull null

            JSONObject().apply {
                put("index", index)
                bookmark.locations.fragments.firstOrNull()?.let { put("fragment", it) }
                bookmark.locations.progression?.let { put("progression", it) }
            }
        }

        val jsBookmarksArray = JSONArray(bookmarkCandidates).toString()
        val result = epubNav.evaluateJavascript(
            """
                (function() {
                    const bookmarks = $jsBookmarksArray;
                    const scroller = document.scrollingElement || document.documentElement;
                    const viewportHeight = window.innerHeight;
                    const visibleTopProgression = scroller.scrollHeight > 0
                        ? scroller.scrollTop / scroller.scrollHeight
                        : 0;
                    const visibleBottomProgression = scroller.scrollHeight > 0
                        ? (scroller.scrollTop + viewportHeight) / scroller.scrollHeight
                        : 1;

                    return bookmarks.filter((bookmark) => {
                        if (bookmark.fragment) {
                            const element = document.getElementById(bookmark.fragment);
                            if (element) {
                                const rects = Array.from(element.getClientRects());
                                const visibleRects = rects.length > 0 ? rects : [element.getBoundingClientRect()];
                                const isVisible = visibleRects.some((rect) => {
                                    const isVerticallyVisible = rect.bottom >= 0 && rect.top <= viewportHeight;
                                    const isHorizontallyVisible = rect.right >= 0 && rect.left <= window.innerWidth;
                                    return isVerticallyVisible && isHorizontallyVisible;
                                });

                                if (isVisible) return true;
                            }
                        }

                        if (typeof bookmark.progression === 'number') {
                            return bookmark.progression >= visibleTopProgression &&
                                bookmark.progression <= visibleBottomProgression;
                        }

                        return false;
                    }).map((bookmark) => bookmark.index);
                })();
                """.trimIndent()
        ) ?: return onBookmarksActivate(mapOf("activeBookmarks" to listOf<Locator>()))

        val activeIndexes = try {
            Json.decodeFromString<List<Int>>(result).toSet()
        } catch (e: Exception) {
            emptySet()
        }
        val found = props!!.bookmarks.filterIndexed { index, _ -> activeIndexes.contains(index) }

        onBookmarksActivate(mapOf("activeBookmarks" to found.map { it.toJSON().toMap() }))
    }

    private suspend fun findOnPageInPagedLayout(locator: Locator) {
        val epubNav = navigator ?: return

        val currentProgression = locator.locations.progression ?: return

        val joinedProgressions =
            props!!.bookmarks
                .filter { it.href == locator.href }
                .mapNotNull { it.locations.progression }
                .joinToString { it.toString() }


        val jsProgressionsArray = "[${joinedProgressions}]"

        val result = epubNav.evaluateJavascript(
            """
            (function() {
                const maxScreenX = window.orientation === 0 || window.orientation == 180
                        ? screen.width
                        : screen.height;

                function snapOffset(offset) {
                    const value = offset + 1;

                    return value - (value % maxScreenX);
                }

                const documentWidth = document.scrollingElement.scrollWidth;
                const currentPageStart = snapOffset(documentWidth * ${currentProgression});
                const currentPageEnd = currentPageStart + maxScreenX;
                return ${jsProgressionsArray}.filter((progression) =>
                    progression * documentWidth >= currentPageStart &&
                    progression * documentWidth < currentPageEnd
                );
            })();
            """.trimIndent()
        ) ?: return onBookmarksActivate(mapOf("activeBookmarks" to listOf<Locator>()))

        val parsed = Json.decodeFromString<List<Double>>(result)
        val found = props!!.bookmarks.filter {
            val progression = it.locations.progression ?: return@filter false
            return@filter parsed.contains(progression)
        }

        onBookmarksActivate(mapOf("activeBookmarks" to found.map { it.toJSON().toMap() }))
    }

    fun setupUserScript(): EpubView {
        val activity: FragmentActivity? = appContext.currentActivity as FragmentActivity?
        activity?.lifecycleScope?.launch {
            val locator = props!!.locator ?: return@launch
            val fragments =
                BookService.getFragments(props!!.bookUuid, locator)

            val joinedFragments = fragments.joinToString { "\"${it.fragmentId}\"" }
            val jsFragmentsArray = "[${joinedFragments}]"

            navigator?.evaluateJavascript(
                """
                globalThis.storyteller = {};
                storyteller.doubleClickTimeout = null;
                storyteller.touchMoved = false;

                storyteller.touchStartHandler = (event) => {
                    storyteller.touchMoved = false;
                }

                storyteller.touchMoveHandler = (event) => {
                    storyteller.touchMoved = true;
                }

                storyteller.touchEndHandler = (event) => {
                    if (storyteller.touchMoved || !document.getSelection().isCollapsed || event.changedTouches.length !== 1) return;

                    event.bubbles = true
                    event.clientX = event.changedTouches[0].clientX
                    event.clientY = event.changedTouches[0].clientY
                    const clone = new MouseEvent('click', event);
                    event.stopImmediatePropagation();
                    event.preventDefault();

                    if (storyteller.doubleClickTimeout) {
                        clearTimeout(storyteller.doubleClickTimeout);
                        storyteller.doubleClickTimeout = null;
                        storytellerAPI.handleDoubleTap(event.currentTarget.id);
                        return
                    }

                    const element = event.currentTarget;

                    storyteller.doubleClickTimeout = setTimeout(() => {
                        storyteller.doubleClickTimeout = null;
                        element.parentElement.dispatchEvent(clone);
                    }, 350);
                }

                storyteller.observer = new IntersectionObserver((entries) => {
                    entries.forEach((entry) => {
                        if (entry.isIntersecting) {
                            entry.target.addEventListener('touchstart', storyteller.touchStartHandler)
                            entry.target.addEventListener('touchmove', storyteller.touchMoveHandler)
                            entry.target.addEventListener('touchend', storyteller.touchEndHandler)
                        } else {
                            entry.target.removeEventListener('touchstart', storyteller.touchStartHandler)
                            entry.target.removeEventListener('touchmove', storyteller.touchMoveHandler)
                            entry.target.removeEventListener('touchend', storyteller.touchEndHandler)
                        }
                    })
                }, {
                    threshold: [0],
                })

                if (!globalThis.storyteller.selectionScrollPin) {
                    globalThis.storyteller.selectionScrollPin = { left: null, raf: null, startedAt: 0, idleFrames: 0 };
                    const selectionScrollPinHandler = () => {
                        const pin = globalThis.storyteller.selectionScrollPin;
                        if (pin.left === null) return;
                        const scroller = document.scrollingElement || document.documentElement;
                        if (scroller.scrollLeft !== pin.left) {
                            scroller.scrollLeft = pin.left;
                        }
                    };
                    document.addEventListener('scroll', selectionScrollPinHandler, true);
                    if (document.scrollingElement) {
                        document.scrollingElement.addEventListener('scroll', selectionScrollPinHandler, { passive: false });
                    }
                    window.addEventListener('scroll', selectionScrollPinHandler, { passive: false });
                }

                storyteller.getSelectionText = function() {
                    const own = window.getSelection ? window.getSelection().toString() : '';
                    if (own.length > 0) return own;
                    let best = '';
                    const visit = (win) => {
                        try {
                            const selection = win.getSelection ? win.getSelection() : null;
                            const text = selection ? selection.toString() : '';
                            if (text.length > best.length) best = text;
                            for (let i = 0; i < win.frames.length; i++) visit(win.frames[i]);
                        } catch (_) {}
                    };
                    visit(window);
                    return best;
                };

                storyteller.pinSelectionScroll = function() {
                    const pin = globalThis.storyteller.selectionScrollPin;
                    const scroller = document.scrollingElement || document.documentElement;
                    pin.left = scroller.scrollLeft;
                    pin.startedAt = performance.now();
                    pin.idleFrames = 0;
                    storyteller.startSelectionScrollPin();
                };

                storyteller.startSelectionScrollPin = function() {
                    const pin = globalThis.storyteller.selectionScrollPin;
                    if (pin.raf !== null) return;
                    const tick = () => {
                        if (pin.left === null) {
                            pin.raf = null;
                            return;
                        }
                        const selection = document.getSelection();
                        if (!selection || selection.isCollapsed) {
                            pin.idleFrames++;
                        } else {
                            pin.idleFrames = 0;
                        }
                        if (performance.now() - pin.startedAt > 30000 || pin.idleFrames > 120) {
                            pin.left = null;
                            pin.raf = null;
                            return;
                        }
                        const scroller = document.scrollingElement || document.documentElement;
                        if (scroller.scrollLeft !== pin.left) {
                            scroller.scrollLeft = pin.left;
                        }
                        pin.raf = requestAnimationFrame(tick);
                    };
                    pin.raf = requestAnimationFrame(tick);
                };

                storyteller.unpinSelectionScroll = function() {
                    const pin = globalThis.storyteller.selectionScrollPin;
                    pin.left = null;
                };

                document.addEventListener('selectionchange', () => {
                    const selection = document.getSelection();
                    const scroller = document.scrollingElement || document.documentElement;
                    const pin = globalThis.storyteller.selectionScrollPin;
                    if (selection.isCollapsed) {
                        storyteller.unpinSelectionScroll();
                    } else if (pin.left === null) {
                        pin.left = scroller.scrollLeft;
                    }
                    if (selection.isCollapsed) {
                        storytellerAPI.handleSelectionCleared();
                    }
                });

                storyteller.isEntirelyOnScreen = function isEntirelyOnScreen(element) {
                    const rects = element.getClientRects()
                    return Array.from(rects).every((rect) => {
                        const isVerticallyWithin = rect.bottom >= 0 && rect.top <= window.innerHeight;
                        const isHorizontallyWithin = rect.right >= 0 && rect.left <= window.innerWidth;
                        return isVerticallyWithin && isHorizontallyWithin;
                    });
                }

                storyteller.isFirstClientRectOnScreen = function isFirstClientRectOnScreen(element) {
                    if (!element) return false;

                    const rect = element.getClientRects()[0];
                    if (!rect) return false;

                    const isVerticallyWithin = rect.bottom >= 0 && rect.top <= window.innerHeight;
                    const isHorizontallyWithin = rect.right >= 0 && rect.left <= window.innerWidth;
                    return isVerticallyWithin && isHorizontallyWithin;
                }

                storyteller.scrollElementIntoView = function scrollElementIntoView(element) {
                    if (!element) return false;

                    const rects = Array.from(element.getClientRects())
                        .filter((rect) => rect.width > 0 && rect.height > 0);
                    if (rects.length === 0) return false;

                    const top = Math.min(...rects.map((rect) => rect.top));
                    const bottom = Math.max(...rects.map((rect) => rect.bottom));
                    const viewportHeight = window.innerHeight;
                    const viewportCenter = viewportHeight / 2;
                    const elementCenter = top + ((bottom - top) / 2);
                    const deadZone = Math.min(36, Math.max(16, viewportHeight * 0.04));
                    const delta = elementCenter - viewportCenter;

                    if (Math.abs(delta) < deadZone) return false;

                    const scroller = document.scrollingElement || document.documentElement;
                    const maxScrollTop = Math.max(0, scroller.scrollHeight - viewportHeight);
                    const targetScrollTop = Math.min(maxScrollTop, Math.max(0, scroller.scrollTop + delta));
                    if (Math.abs(targetScrollTop - scroller.scrollTop) < 1) return false;

                    try {
                        scroller.scrollTo({ top: targetScrollTop, behavior: 'smooth' });
                    } catch (_) {
                        scroller.scrollTop = targetScrollTop;
                    }
                    return true;
                }

                readium.findFirstVisibleLocator = function findFirstVisibleLocator() {
                    let firstVisibleFragmentId = null;

                    for (const fragmentId of storyteller.fragmentIds) {
                        const element = document.getElementById(fragmentId);
                        if (!element) continue;
                        if (storyteller.isEntirelyOnScreen(element)) {
                            firstVisibleFragmentId = fragmentId
                            break
                        }
                    }

                    if (firstVisibleFragmentId === null) return null;

                    return {
                        href: "#",
                        type: "application/xhtml+xml",
                        locations: {
                            cssSelector: "#" + firstVisibleFragmentId,
                            fragments: [firstVisibleFragmentId]
                        },
                        text: {
                            highlight: document.getElementById(firstVisibleFragmentId).textContent,
                        },
                    };
                }

                storyteller.fragmentIds = $jsFragmentsArray;
                storyteller.fragmentIds.map((id) => document.getElementById(id)).forEach((element) => {
                    storyteller.observer.observe(element)
                })

                storyteller.getFragmentPageProportion = function getFragmentPageProportion(fragmentId) {
                    const element = document.getElementById(fragmentId);
                    if (!element) return null;

                    const rects = Array.from(element.getClientRects());
                    if (rects.length === 0) return null;

                    const viewportWidth = window.innerWidth;
                    let visibleWidth = 0;
                    let totalWidth = 0;
                    let overflowsRight = false;

                    for (const rect of rects) {
                        totalWidth += rect.width;

                        if (rect.right > viewportWidth) {
                            overflowsRight = true;
                        }

                        if (rect.left >= 0 && rect.right <= viewportWidth) {
                            visibleWidth += rect.width;
                        } else if (rect.left < viewportWidth && rect.right > 0) {
                            const visibleLeft = Math.max(rect.left, 0);
                            const visibleRight = Math.min(rect.right, viewportWidth);
                            visibleWidth += visibleRight - visibleLeft;
                        }
                    }

                    if (totalWidth === 0) return null;

                    const proportion = visibleWidth / totalWidth;
                    return { crossesPage: overflowsRight, proportionOnCurrentPage: proportion };
                };

                document.body.firstElementChild.style.paddingLeft = "var(--st-padding-left)";
                document.body.firstElementChild.style.paddingRight = "var(--st-padding-right)";
                """.trimIndent()
            )

            setCssVar("--st-padding-left", "${props?.marginLeft ?: 0}px")
            setCssVar("--st-padding-right", "${props?.marginRight ?: 0}px")
        }

        return this
    }

    @JavascriptInterface
    fun handleDoubleTap(fragment: String) {
        val bookService = BookService
        val currentLocator = navigator?.currentLocator?.value ?: return
        val activity: FragmentActivity? = appContext.currentActivity as FragmentActivity?
        activity?.lifecycleScope?.launch {
            val locator =
                bookService.buildFragmentLocator(props!!.bookUuid, currentLocator.href, fragment)

            onDoubleTouch(locator.toJSON().toMap())
        }
    }

    @JavascriptInterface
    fun handleSelectionCleared() {
        if (System.currentTimeMillis() < ignoreSelectionClearedUntil) return
        onSelection(mapOf("cleared" to true))
    }

    private suspend fun onLocatorChanged(locator: Locator) {
        findOnPage(locator)

        // Only a new chapter needs searching. This fires on the first page load and
        // on every chapter change; page turns inside a chapter cost nothing.
        if (locator.href != lastDictionarySearchHref) {
            lastDictionarySearchHref = locator.href
            searchDictionaryEntries(locator.href)
        }

        if (locator.href != props!!.locator?.href || changingResource) {
            changingResource = false

            val fragments = BookService.getFragments(props!!.bookUuid, locator)

            val joinedFragments = fragments.joinToString { "\"${it.fragmentId}\"" }
            val jsFragmentsArray = "[${joinedFragments}]"

            navigator?.evaluateJavascript(
                """
                storyteller.fragmentIds = $jsFragmentsArray;
                storyteller.fragmentIds.map((id) => document.getElementById(id)).forEach((element) => {
                    storyteller.observer.observe(element)
                })
            """.trimIndent()
            )
            if (props!!.isPlaying && props!!.locator?.href == locator.href) {
                applyReadaloudDecoration(props!!.locator!!)
            }
            emitCurrentLocator()
        } else {
            emitCurrentLocator()
        }
    }

    @ExperimentalReadiumApi
    override fun onExternalLinkActivated(url: AbsoluteUrl) {
        TODO("Not yet implemented")
    }

    override fun onPageLoaded() {
        injectCustomFonts()
        navigator?.publicationView?.let { disableOverscroll(it) }

        if (!firstPageLoaded.isCompleted) {
            firstPageLoaded.complete(Unit)
        }
    }

    private fun positionJump(jump: Locator) {
        val nav = navigator ?: return
        val generation = jumpPositionGeneration
        jumpPositionJob = activity?.lifecycleScope?.launch {
            fun isCurrent(): Boolean =
                isActive && jumpPositionGeneration == generation &&
                    navigator === nav && nav.currentLocator.value.href == jump.href &&
                    props?.isPlaying == false && props?.scrollMode == true

            // A delayed WebView call must not adjust an old landing.
            val maxPlacementMs = 500L
            val startedAt = SystemClock.elapsedRealtime()
            val landing = runCatching {
                nav.evaluateJavascript(
                    """
                    (function() {
                        let guard = globalThis.__storytellerJumpGuard;
                        if (!guard) {
                            guard = { id: 0, input: false };
                            globalThis.__storytellerJumpGuard = guard;
                            for (const type of ['touchstart', 'touchmove', 'pointerdown', 'wheel', 'keydown']) {
                                document.addEventListener(type, () => { guard.input = true; }, { capture: true, passive: true });
                            }
                        }
                        guard.id++;
                        guard.input = false;
                        return { top: (document.scrollingElement || document.documentElement).scrollTop, time: performance.now(), id: guard.id };
                    })()
                    """.trimIndent()
                )?.let { JSONObject(it) }
            }.getOrNull() ?: return@launch
            val landingTop = landing.optDouble("top", Double.NaN)
            val landingTime = landing.optDouble("time", Double.NaN)
            val landingId = landing.optInt("id", 0)
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            if (!landingTop.isFinite() || !landingTime.isFinite() || landingId <= 0 || !isCurrent() ||
                elapsedMs > maxPlacementMs
            ) return@launch
            val deadline = landingTime + (maxPlacementMs - elapsedMs)

            delay(32L)
            suspend fun measure(barBottom: Double): Pair<Boolean, Boolean> = runCatching {
                val result = nav.evaluateJavascript(
                    """
                    (function() {
                        const scroller = document.scrollingElement || document.documentElement;
                        const guard = globalThis.__storytellerJumpGuard;
                        const now = performance.now();
                        if (!storytellerAPI.isJumpPositionCurrent($generation) ||
                            !guard || guard.id !== $landingId || guard.input || now < $landingTime ||
                            now > $deadline || scroller.scrollTop !== $landingTop) return { moved: true };
                        const locator = ${jump.toJSON()};
                        const body = document.body;
                        const normalize = (text) => text.replace(/\s+/g, ' ').trim();
                        const quote = normalize(locator.text?.highlight || '');
                        if (!body || !quote) return false;

                        const raw = body.textContent || '';
                        const text = normalize(raw);
                        const before = normalize(locator.text.before || '');
                        const after = normalize(locator.text.after || '');
                        let onlyMatch = -1;
                        let matchCount = 0;
                        let contextualMatch = -1;
                        let contextualCount = 0;
                        for (let i = text.indexOf(quote); i !== -1; i = text.indexOf(quote, i + 1)) {
                            onlyMatch = i;
                            matchCount++;
                            const beforeEnd = i > 0 && text[i - 1] === ' ' ? i - 1 : i;
                            const afterStart = i + quote.length + (text[i + quote.length] === ' ' ? 1 : 0);
                            if ((!before || text.endsWith(before, beforeEnd)) &&
                                (!after || text.startsWith(after, afterStart))) {
                                contextualMatch = i;
                                contextualCount++;
                            }
                        }
                        let uniqueWord = false;
                        if (matchCount === 1) {
                            const wordCharacter = /[\p{L}\p{N}\p{M}\p{Pc}]/u;
                            const characters = Array.from(quote);
                            const previous = Array.from(text.slice(Math.max(0, onlyMatch - 2), onlyMatch)).pop() || '';
                            const next = Array.from(text.slice(onlyMatch + quote.length, onlyMatch + quote.length + 2))[0] || '';
                            uniqueWord = !(wordCharacter.test(characters[0]) && wordCharacter.test(previous)) &&
                                !(wordCharacter.test(characters[characters.length - 1]) && wordCharacter.test(next));
                        }
                        const match = contextualCount === 1 ? contextualMatch : uniqueWord ? onlyMatch : -1;
                        if (match === -1) return false;

                        // Map the normalized match back to offsets in the unmodified DOM text.
                        const tokens = /\S+/g;
                        let token;
                        let normalizedOffset = 0;
                        let hasToken = false;
                        let rawStart = -1;
                        let rawEnd = -1;
                        while ((token = tokens.exec(raw))) {
                            if (hasToken) normalizedOffset++;
                            const tokenEnd = normalizedOffset + token[0].length;
                            if (match >= normalizedOffset && match < tokenEnd) {
                                rawStart = token.index + match - normalizedOffset;
                            }
                            const end = match + quote.length;
                            if (end > normalizedOffset && end <= tokenEnd) {
                                rawEnd = token.index + end - normalizedOffset;
                                break;
                            }
                            normalizedOffset = tokenEnd;
                            hasToken = true;
                        }
                        if (rawStart < 0 || rawEnd < 0) return false;

                        const range = document.createRange();
                        const nodes = document.createNodeIterator(body, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_CDATA_SECTION);
                        let offset = 0;
                        let foundStart = false;
                        let foundEnd = false;
                        for (let node = nodes.nextNode(); node; node = nodes.nextNode()) {
                            const nextOffset = offset + node.length;
                            if (!foundStart && rawStart < nextOffset) {
                                range.setStart(node, rawStart - offset);
                                foundStart = true;
                            }
                            if (foundStart && rawEnd <= nextOffset) {
                                range.setEnd(node, rawEnd - offset);
                                foundEnd = true;
                                break;
                            }
                            offset = nextOffset;
                        }
                        if (!foundEnd || normalize(range.toString()) !== quote) return false;
                        const firstRect = () => Array.from(range.getClientRects()).find(
                            (rect) => rect.width > 0 && rect.height > 0
                        );
                        const rect = firstRect();
                        if (!rect) return false;

                        const preceding = document.createRange();
                        preceding.selectNodeContents(body);
                        preceding.setEnd(range.startContainer, range.startOffset);
                        let previousBottom = -Infinity;
                        for (const previous of preceding.getClientRects()) {
                            if (previous.width > 0 && previous.height > 0 &&
                                previous.top < rect.top - 1 && previous.bottom <= rect.top + 1) {
                                previousBottom = Math.max(previousBottom, previous.bottom);
                            }
                        }
                        const element = range.startContainer.parentElement;
                        const computedLineHeight = element ? parseFloat(getComputedStyle(element).lineHeight) : NaN;
                        const lineHeight = Number.isFinite(computedLineHeight) && computedLineHeight > 0
                            ? computedLineHeight : rect.height;
                        const preferred = Math.max(8, lineHeight / 2);
                        // Keep the preceding line behind the bar as well as the quote below it.
                        const clearance = Number.isFinite(previousBottom)
                            ? Math.max(0, Math.min(preferred, (rect.top - previousBottom) / 2))
                            : preferred;
                        const target = $barBottom + clearance;
                        if (!storytellerAPI.isJumpPositionCurrent($generation) ||
                            guard.id !== $landingId || guard.input || scroller.scrollTop !== $landingTop ||
                            performance.now() > $deadline) return { moved: true };
                        const behavior = scroller.style.getPropertyValue('scroll-behavior');
                        const priority = scroller.style.getPropertyPriority('scroll-behavior');
                        scroller.style.setProperty('scroll-behavior', 'auto', 'important');
                        try {
                            for (let i = 0; i < 2; i++) {
                                if (!storytellerAPI.isJumpPositionCurrent($generation)) return { moved: true };
                                let current = firstRect();
                                if (!current) return false;
                                let delta = current.top - target;
                                if (Math.abs(delta) <= 1) break;
                                let nextTop = scroller.scrollTop + delta;
                                if (nextTop < 0) {
                                    // Keep this space until the resource unloads so dismissing the bar moves nothing.
                                    const padding = parseFloat(getComputedStyle(body).paddingTop) || 0;
                                    if (!storytellerAPI.isJumpPositionCurrent($generation) ||
                                        guard.id !== $landingId || guard.input || performance.now() > $deadline) return { moved: true };
                                    body.style.setProperty('padding-top', (padding - nextTop) + 'px', 'important');
                                    current = firstRect();
                                    if (!current) return { placed: true };
                                    delta = current.top - target;
                                    nextTop = scroller.scrollTop + delta;
                                }
                                if (!storytellerAPI.isJumpPositionCurrent($generation) ||
                                    guard.id !== $landingId || guard.input || performance.now() > $deadline) return { moved: true };
                                scroller.scrollTop = Math.max(0, nextTop);
                            }
                            return { placed: true };
                        } finally {
                            if (behavior) scroller.style.setProperty('scroll-behavior', behavior, priority);
                            else scroller.style.removeProperty('scroll-behavior');
                        }
                    })()
                    """.trimIndent()
                ) ?: return@runCatching (false to false)
                val outcome = JSONObject(result)
                outcome.optBoolean("moved") to outcome.optBoolean("placed")
            }.getOrDefault(false to false)

            if (!isCurrent() || SystemClock.elapsedRealtime() - startedAt > maxPlacementMs) return@launch
            val barBottom = props?.jumpBarBottom ?: 0.0
            if (barBottom <= 0.0) return@launch
            val (moved, placed) = measure(barBottom)
            if (!isCurrent() || moved || placed || props?.jumpBarBottom != barBottom ||
                SystemClock.elapsedRealtime() - startedAt > maxPlacementMs
            ) return@launch

            runCatching {
                nav.evaluateJavascript(
                    """
                    (function() {
                        const scroller = document.scrollingElement || document.documentElement;
                        const guard = globalThis.__storytellerJumpGuard;
                        const initialTop = scroller.scrollTop;
                        const now = performance.now();
                        if (!storytellerAPI.isJumpPositionCurrent($generation) ||
                            !guard || guard.id !== $landingId || guard.input || now < $landingTime ||
                            now > $deadline || initialTop !== $landingTop) return;
                        const nextTop = Math.max(0, initialTop - ($barBottom + 2));
                        if (nextTop === initialTop || !document.body) return;
                        // An unverified quote must not move already-visible content.
                        const content = document.createRange();
                        content.selectNodeContents(document.body);
                        if (Array.from(content.getClientRects()).some((rect) =>
                            rect.width > 0 && rect.height > 0 && rect.right > 0 &&
                            rect.left < window.innerWidth && rect.bottom > $barBottom && rect.top < window.innerHeight
                        )) return;
                        if (!storytellerAPI.isJumpPositionCurrent($generation) ||
                            guard.id !== $landingId || guard.input || scroller.scrollTop !== $landingTop ||
                            performance.now() > $deadline) return;
                        const behavior = scroller.style.getPropertyValue('scroll-behavior');
                        const priority = scroller.style.getPropertyPriority('scroll-behavior');
                        scroller.style.setProperty('scroll-behavior', 'auto', 'important');
                        try {
                            if (!storytellerAPI.isJumpPositionCurrent($generation) ||
                                guard.id !== $landingId || guard.input || performance.now() > $deadline) return;
                            scroller.scrollTop = nextTop;
                        } finally {
                            if (behavior) scroller.style.setProperty('scroll-behavior', behavior, priority);
                            else scroller.style.removeProperty('scroll-behavior');
                        }
                    })()
                    """.trimIndent()
                )
            }
        }
    }

    override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
        val jump = pendingJumpLocator
        if (jump != null && locator.href == jump.href) {
            pendingJumpLocator = null
            positionJump(jump)
        }

        // Readium calls this whenever it recomputes the current page, not only when the
        // page actually turns, so the index is compared first. Otherwise a tap that only
        // opens the dictionary menu would drop the mark it just set.
        val pageTurned = lastPageIndex != null && lastPageIndex != pageIndex
        lastPageIndex = pageIndex
        if (pageTurned && activeDictionaryId != null) {
            setActiveDictionaryDecoration(null)
        }
    }

    override fun shouldFollowInternalLink(
        link: Link,
        context: HyperlinkNavigator.LinkContext?,
    ): Boolean =
        when (context) {
            is HyperlinkNavigator.FootnoteContext -> {
                onShowFootnote(mapOf("content" to context.noteContent))
                false
            }
            else -> true
        }
}
