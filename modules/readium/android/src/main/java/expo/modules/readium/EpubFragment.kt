@file:OptIn(InternalReadiumApi::class)

package expo.modules.readium

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ActionMode
import android.view.Menu
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONTokener
import org.readium.r2.navigator.epub.EpubDefaults
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.navigator.epub.css.FontWeight
import org.readium.r2.navigator.epub.css.RsProperties
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.util.BaseActionModeCallback
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.extensions.toMap
import org.readium.r2.shared.publication.Publication
import kotlin.math.ceil

class SelectionActionModeCallback(private val epubView: EpubView) : BaseActionModeCallback() {
    private var currentActionMode: ActionMode? = null
    // An old selection must not close a newer one after rotation.
    private var generation = 0L
    private var pendingRotationClear: Long? = null
    private var rotationClear: Runnable? = null

    init {
        epubView.selectionActionModeCallback = this
    }

    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        currentActionMode = mode
        val currentGeneration = ++generation
        pendingRotationClear = null
        rotationClear?.let { epubView.removeCallbacks(it) }
        rotationClear = null
        epubView.beginSelection()
        val activity: FragmentActivity? = epubView.appContext.currentActivity as FragmentActivity?
        activity?.lifecycleScope?.launch {
            val selection = epubView.navigator?.currentSelection() ?: return@launch
            // The locator's highlight only carries the first paragraph of a selection
            // that spans paragraphs, so read the full live selection text.
            val liveText = epubView.navigator
                ?.evaluateJavascript("window.getSelection().toString()")
                ?.let { result ->
                    runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
                }
            if (currentGeneration != generation || pendingRotationClear != null) return@launch
            val selectedText = liveText?.takeIf { it.isNotBlank() }
                ?: selection.locator.text.highlight.orEmpty()
            selection.rect?.let {
                val x = ceil(it.centerX() / epubView.resources.displayMetrics.density).toInt()
                val y = ceil(it.top / epubView.resources.displayMetrics.density).toInt() - 16
                val yBottom = ceil(it.bottom / epubView.resources.displayMetrics.density).toInt()
                if (currentGeneration != generation) return@let
                epubView.onSelection(
                    mapOf(
                        "locator" to selection.locator.toJSON().toMap(),
                        "selectedText" to selectedText,
                        "x" to x,
                        "y" to y,
                        "yBottom" to yBottom
                    )
                )
            }
        }

        mode?.let { actionMode ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                actionMode.hide(0)
            }
        }

        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        if (currentActionMode === mode) currentActionMode = null
        epubView.endSelection()
        super.onDestroyActionMode(mode)
    }

    fun handleSelectionCleared() {
        // Once selection has actually moved, an old rotation has no stale UI to clear.
        pendingRotationClear = null
        rotationClear?.let { epubView.removeCallbacks(it) }
        rotationClear = null
    }

    fun finishForRotation() {
        val oldMode = currentActionMode ?: return
        val dismissed = ++generation
        pendingRotationClear = dismissed
        oldMode.finish()
        // ActionMode can stay referenced after finish, so use the dismissal's
        // own generation instead of an API-unavailable isActive query.
        // Only the current old mode may send this late dismissal.
        val clear = Runnable {
            if (pendingRotationClear == dismissed) {
                epubView.onSelection(mapOf("cleared" to true))
            }
            if (rotationClear != null) pendingRotationClear = null
            rotationClear = null
        }
        rotationClear = clear
        epubView.postDelayed(clear, 250)
    }
}

@SuppressLint("ViewConstructor")
@OptIn(ExperimentalReadiumApi::class)
class EpubFragment(
    private val publication: Publication,
    private val listener: EpubView
) : Fragment(R.layout.fragment_reader) {
    var navigator: EpubNavigatorFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        childFragmentManager.fragmentFactory = EpubNavigatorFactory(
            publication,
            EpubNavigatorFactory.Configuration(
                defaults = EpubDefaults(
                    publisherStyles = false
                ),
            ),
        ).createFragmentFactory(
            listener.props!!.locator,
            listener = listener,
            paginationListener = listener,
            configuration = EpubNavigatorFragment.Configuration {
                servedAssets = listOf("fonts/Literata_500Medium.ttf")
                shouldApplyInsetsPadding = false
                disablePageTurnsWhileScrolling = true
                decorationTemplates = storytellerDecorationTemplates()

                addFontFamilyDeclaration(FontFamily("Literata")) {
                    addFontFace {
                        addSource("fonts/Literata_500Medium.ttf")
                        setFontStyle(FontStyle.NORMAL)
                        setFontWeight(FontWeight.NORMAL)
                    }
                }

                // Custom fonts are imported at runtime, so they live outside the app's assets,
                // which is the only place Readium can serve an `addSource` path from on Android.
                // EpubView injects them into the pages as data URLs instead.

                selectionActionModeCallback = SelectionActionModeCallback(listener)

                registerJavascriptInterface("storytellerAPI") {
                    listener.setupUserScript()
                }
            },
            initialPreferences = listener.layoutPreferences(),
        )

        super.onCreate(savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val navigatorFragmentTag = getString(R.string.epub_navigator_tag)

        if (savedInstanceState == null) {
            childFragmentManager.commitNow {
                setReorderingAllowed(true)
                add(
                    R.id.fragment_reader_container,
                    EpubNavigatorFragment::class.java,
                    Bundle(),
                    navigatorFragmentTag
                )
            }
        }
        navigator =
            childFragmentManager.findFragmentByTag(navigatorFragmentTag) as EpubNavigatorFragment
    }
}
