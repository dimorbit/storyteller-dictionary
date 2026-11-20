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
import org.readium.r2.navigator.input.DragEvent
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
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.publication.indexOfFirstWithHref
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.html.cssSelector
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
    var activeHighlightDecorationId: String?,
    var selectionMenuVisible: Boolean?,
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
    var jumpBarBottom: Double?,
    var readerUiVisible: Boolean?,
    var volumeKeysTurnPages: Boolean?,
    var invertVolumeKeys: Boolean?
)


data class FinalizedProps(
    var bookUuid: String,
    var locator: Locator?,
    var isPlaying: Boolean,
    var highlights: List<Highlight>,
    var dictionaryEntries: List<DictionaryEntryDecoration>,
    var activeDictionaryDecorationId: String?,
    var activeHighlightDecorationId: String?,
    var selectionMenuVisible: Boolean,
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
    var jumpBarBottom: Double,
    // While the reader UI is on screen no tap may reach the reader: the overlay is
    // what the reader is supposed to be interacting with, so a tap anywhere only
    // dismisses it. Swipes are still allowed through, because a swipe is how the page
    // is turned, and dismissing on the way is what the reader expects.
    var readerUiVisible: Boolean,
    // Turn the page with the volume buttons, but only while the UI is hidden. See
    // ReaderInputState for why the check is split across two entry points.
    var volumeKeysTurnPages: Boolean,
    // Swap which volume button goes forward.
    var invertVolumeKeys: Boolean
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

        // Prefetching reads a neighbouring chapter out of the EPUB from the IO dispatcher
        // and runs the ICU search over it. It is deliberately started after the reader has
        // landed, so it competes with nothing the hand is waiting for.
        private const val DICTIONARY_PREFETCH_DELAY_MS = 350L

        // The reader works out how the marks changed by diffing the new list against
        // the previous one in the background. Two applications started close together
        // read each other's lists and throw, so the marks are applied once after a
        // chapter change settles, and never straight after the previous application.
        private const val DICTIONARY_DECORATION_SETTLE_MS = 80L
        // How long a gesture that hid the reader UI is ignored, so the same gesture
        // cannot hide and then re-show it while the prop is still in flight.
        private const val READER_UI_DISMISS_LATCH_MS = 400L
        private const val DICTIONARY_DECORATION_GAP_MS = 200L

        // Volume keys, when the setting is on and the UI is hidden.
        private const val KEYCODE_VOLUME_UP = 24
        private const val KEYCODE_VOLUME_DOWN = 25
        // Horizontal travel, in pixels, before a drag counts as a page turn rather than
        // a tap or a handle drag. Kept small on purpose: collapsing the selection is
        // asynchronous, and the pager decides whether to start a drag using its own
        // slop on the same move, so the earlier this fires the more of the gesture is
        // left for the page turn. Still well above finger jitter.
        private const val SWIPE_TO_CLEAR_SELECTION_SLOP = 8f
        // How long the recursive layout stays held after a swipe ends a selection. Covers
        // the rest of the gesture; the page turn itself only needs a few frames.
        private const val SWIPE_SELECTION_LAYOUT_HOLD_MS = 700L
        // How long after the release a page turn still counts as the one the swipe asked
        // for. A swipe that is abandoned before turning a page leaves the selection
        // alone; the flag simply lapses.
        private const val SWIPE_SELECTION_CLEAR_DEADLINE_MS = 600L

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
        // Readium sizes each dot box from the range's client rects, which are full
        // line boxes wherever the range runs past a line, so the dots run to the
        // margin and one merged box covers several lines. This measures the saved
        // range word by word — a word never crosses a line break — and rebuilds one
        // box per line, clamped to that line's first and last glyph. Boxes are held
        // back by the pending class until they are sized, so the wrong geometry is
        // never painted. It is idempotent: every pass recomputes and clears its own
        // clones, so it can run on every paint.
        // Readium lays out again on its own when the body resizes, and rebuilds each
        // box from the template, which brings the pending class back. This clamps
        // once more after any such reflow, so the dots cannot stay hidden until the
        // next tap. It installs once per page and coalesces bursts with a frame.

        private fun installDictionaryClampObserverJs() = """
            (function () {
              if (window.__storytellerDictClampInstalled) return "already";
              window.__storytellerDictClampInstalled = true;
              var pending = false;
              function again() {
                if (pending) return;
                pending = true;
                requestAnimationFrame(function () {
                  pending = false;
                  // The clamp is assigned to Android before this observer is
                  // installed; it used to call a name nothing ever defined, so every
                  // reflow went unclamped and the boxes stayed hidden behind the
                  // pending class until the next tap.
                  if (typeof window.storytellerClampDictionary === 'function') {
                    window.storytellerClampDictionary();
                  }
                  // A resize rebuilds boxes from the inactive template. Put the wash
                  // back so an open menu keeps its blue mark after an image loads.
                  var active = window.__storytellerActiveDecorationId;
                  if (active) {
                    var g = readium.getDecorations('dictionary');
                    if (g && g.items) g.items.forEach(function (i) {
                      if (i.decoration.id === active && i.container) {
                        Array.prototype.slice.call(i.container.children).forEach(function (b) {
                          b.classList.add('$dictionaryActiveClass');
                        });
                      }
                    });
                  }
                });
              }
              if (window.ResizeObserver) new ResizeObserver(again).observe(document.body);
              document.addEventListener("load", again, true);
              return "installed";
            })();
        """.trimIndent()

        private const val clampDictionaryDecorations = """
window.storytellerClampDictionary = function () {
  var beganAt = (window.performance && performance.now) ? performance.now() : 0;
  var stats = { items: 0, nodes: 0, rects: 0, walkMs: 0, geomMs: 0, layouts: 0 };
  var made = 0;
  try {
  var group = readium.getDecorations('dictionary');
  if (!group || !group.items) return 'no-group';

  function isSpace(c) {
    if (c === 32 || c === 9 || c === 10 || c === 13) return true;
    if (c === 160 || c === 0x1680 || c === 0x202F || c === 0x205F) return true;
    if (c >= 0x2000 && c <= 0x200B) return true;
    if (c === 0x2028 || c === 0x2029 || c === 0x3000) return true;
    if (c === 0xFEFF) return true;
    return false;
  }

  // A range's own client rects are full line boxes on every line it runs past,
  // so they cannot say where a line's text ends. A word never spans a line break, so
  // measuring word by word and grouping by line gives each line's true extent.
  function lineRects(range, limit) {
    var root = range.commonAncestorContainer;
    var walkRoot = (root.nodeType === 3) ? root.parentNode : root;
    if (!walkRoot) return { lines: [], truncated: false };
    var walker = document.createTreeWalker(walkRoot, NodeFilter.SHOW_TEXT, null, false);
    var sub = document.createRange(), lines = [], words = 0, node;
    var tWalk = 0, tGeom = 0, nodesVisited = 0;
    while ((node = walker.nextNode()) && words < limit) {
      nodesVisited++;
      var tNodeA = now();
      if (!range.intersectsNode(node)) { tWalk += now() - tNodeA; continue; }
      var data = node.data, i = 0;
      while (i < data.length && words < limit) {
        while (i < data.length && isSpace(data.charCodeAt(i))) i++;
        if (i >= data.length) break;
        var start = i;
        while (i < data.length && !isSpace(data.charCodeAt(i))) i++;
        sub.setStart(node, start);
        sub.setEnd(node, i);
        if (sub.compareBoundaryPoints(Range.START_TO_START, range) < 0) {
          sub.setStart(range.startContainer, range.startOffset);
        }
        if (sub.compareBoundaryPoints(Range.END_TO_END, range) > 0) {
          sub.setEnd(range.endContainer, range.endOffset);
        }
        if (sub.collapsed) { tWalk += now() - tNodeA; continue; }
        words++;
        var tGeomA = now();
        var list = sub.getClientRects();
        stats.rects++;
        tGeom += now() - tGeomA;
        for (var k = 0; k < list.length; k++) {
          var r = list[k];
          if (r.width < 1 || r.height < 1) continue;
          var line = null;
          // Group by the line, not by adjacency: justified text leaves wide gaps
          // between words, so a gap test small enough to find words would split one
          // line into many. A column break is much wider than any word space, so the
          // gap only decides when two fragments are too far apart to be one line.
          var maxGap = Math.max(r.height * 3, 40);
          for (var L = 0; L < lines.length; L++) {
            if (Math.abs(lines[L].top - r.top) > 2) continue;
            var gap = (r.left > lines[L].right) ? (r.left - lines[L].right)
              : (lines[L].left > r.right) ? (lines[L].left - r.right) : 0;
            if (gap < maxGap) { line = lines[L]; break; }
          }
          if (!line) {
            lines.push({ left: r.left, right: r.right, top: r.top, bottom: r.bottom });
          } else {
            line.left = Math.min(line.left, r.left);
            line.right = Math.max(line.right, r.right);
            line.top = Math.min(line.top, r.top);
            line.bottom = Math.max(line.bottom, r.bottom);
          }
        }
      }
    }
    stats.nodes += nodesVisited;
    stats.walkMs += tWalk;
    stats.geomMs += tGeom;
    var truncated = false;
    if (words >= limit) {
      // Words past the cap still belong to this mark. Their boxes must keep their
      // own dots and tap targets instead of being hidden with the measured ones.
      var tail = document.createRange();
      tail.setStart(sub.endContainer, sub.endOffset);
      tail.setEnd(range.endContainer, range.endOffset);
      truncated = !tail.collapsed && tail.toString().trim().length > 0;
    }
    return { lines: lines, truncated: truncated };
  }

  function now() {
    return (window.performance && performance.now) ? performance.now() : 0;
  }

  // Nothing may be left pending: a box that stays hidden is worse than a box with
  // wrong geometry. Every path out of the item loop clears the class.
  function reveal(container) {
    if (!container) return;
    Array.prototype.slice.call(container.children).forEach(function (b) {
      b.classList.remove('storyteller-dictionary-pending');
    });
  }

  function hideDots(item, container) {
    if (!item || !item.decoration || !item.decoration.style) return;
    if (item.decoration.style.hidden !== true) return;
    Array.prototype.slice.call(container.children).forEach(function (b) {
      b.classList.add('storyteller-dictionary-hidden');
      b.style.setProperty('--dictionary-dot-color', 'transparent');
    });
  }

  // The work is split so that every style write happens after every geometry read has
  // finished. Interleaved per item, the first read of item n came straight after the
  // writes of item n-1, so it forced a synchronous layout of the whole chapter: the
  // cost was one layout per mark, not per word, which is why a chapter with
  // fifty-seven marks spent more time laying out than measuring. With the split the
  // chapter settles once, and every read in passed two is served from that layout.
  //
  // pass one: writes. The clones the previous paint left behind are dropped, so the
  // boxes measured in pass two are the marks' own and not leftovers.
  group.items.forEach(function (item) {
    var container = item.container;
    if (!container) return;
    Array.prototype.slice.call(container.children).forEach(function (b) {
      if (b.dataset && b.dataset.stlClone === '1') container.removeChild(b);
      else b.style.display = '';
    });
  });

  // pass two: reads only. Nothing below writes to the document, so the range geometry
  // stays valid from the first mark to the last and layout runs at most once.
  var plans = [];
  group.items.forEach(function (item) {
    var container = item.container;
    if (!container) return;

    var boxes = Array.prototype.slice.call(container.children || []);
    var range = item.range;

    if (!range || range.collapsed || !boxes.length) {
      plans.push({ container: container, revealOnly: true });
      return;
    }

    var probe = (range.startContainer.nodeType === 1)
      ? range.startContainer : range.startContainer.parentElement;
    var cs = probe ? getComputedStyle(probe) : null;
    // In vertical writing Readium already emits one box per column and the stylesheet
    // paints the strip along the block axis. The word-by-word clamp below groups by
    // `top`, which fuses columns sharing a top into one wide box and loses a strip.
    if (cs && /^(vertical|sideways)-/.test(cs.writingMode || '')) {
      plans.push({ container: container, revealOnly: true });
      return;
    }

    var measured = lineRects(range, 600);
    var lines = measured.lines;
    stats.items++;
    stats.layouts++;

    if (!lines.length) {
      plans.push({ container: container, revealOnly: true });
      return;
    }

    var lineHeight = cs ? parseFloat(cs.lineHeight) : NaN;
    if (!isFinite(lineHeight) || lineHeight <= 0) {
      lineHeight = cs ? parseFloat(cs.fontSize) * 1.2 : 0;
    }

    var first = boxes[0];
    var fr = first.getBoundingClientRect();
    var originLeft = fr.left - (parseFloat(first.style.left) || 0);
    var originTop = fr.top - (parseFloat(first.style.top) || 0);
    var template = first.cloneNode(false);
    template.dataset.stlClone = '1';
    // Read the boxes' own rects here, while pass two is reads only. They are needed
    // to pair measured lines to boxes by geometry in pass three.
    var boxRects = boxes.map(function (b) {
      var br = b.getBoundingClientRect();
      return { left: br.left, right: br.right, top: br.top, bottom: br.bottom };
    });

    plans.push({
      container: container,
      boxes: boxes,
      boxRects: boxRects,
      lines: lines,
      item: item,
      lineHeight: lineHeight,
      originLeft: originLeft,
      originTop: originTop,
      template: template,
      truncated: measured.truncated,
      revealOnly: false
    });
    made += lines.length;
  });

  // pass three: writes only. The boxes are sized from the rects pass two already took;
  // appending one cannot move another, because a box is absolutely positioned.
  plans.forEach(function (plan) {
    var container = plan.container;
    try {
      if (plan.revealOnly) return;

      var lines = plan.lines;
      var boxes = plan.boxes;
      var boxRects = plan.boxRects;
      var originLeft = plan.originLeft;
      var originTop = plan.originTop;
      var lineHeight = plan.lineHeight;
      var taken = {};

      lines.forEach(function (r) {
        // Readium sorts boxes by top then left, interleaving pages in a spread, while
        // measured lines are in document order. Pair by rect overlap so a capped
        // measurement never rewrites an unmeasured later-page box.
        var box = null;
        for (var bi = 0; bi < boxes.length; bi++) {
          if (taken[bi]) continue;
          var br = boxRects[bi];
          if (Math.min(br.right, r.right) <= Math.max(br.left, r.left)) continue;
          if (Math.min(br.bottom, r.bottom) <= Math.max(br.top, r.top)) continue;
          taken[bi] = true;
          box = boxes[bi];
          break;
        }
        if (!box) {
          box = plan.template.cloneNode(false);
          container.appendChild(box);
        }
        box.style.display = '';
        // A word rect is the em box. The dot row is drawn at the box bottom, so the
        // box has to be the line box; the em box sits centred inside it.
        var emH = r.bottom - r.top;
        var boxTop = r.top, boxH = emH;
        if (isFinite(lineHeight) && lineHeight > emH) {
          boxTop = r.top - (lineHeight - emH) / 2;
          boxH = lineHeight;
        }
        box.style.left = (r.left - originLeft) + 'px';
        box.style.top = (boxTop - originTop) + 'px';
        box.style.width = (r.right - r.left) + 'px';
        box.style.height = boxH + 'px';
        box.style.marginRight = '0';
      });
      // With a capped measurement the untouched boxes already sit on the words past
      // the cap; hiding them would strip their underlines and dictionary tap targets.
      if (!plan.truncated) {
        for (var b = 0; b < boxes.length; b++) {
          if (!taken[b]) boxes[b].style.display = 'none';
        }
      }

      hideDots(plan.item, container);

      // Readium hit-tests item.clickableElements, built once when the item was made.
      // The boxes have been resized and cloned since, so rebuild from what is on
      // screen: every line stays tappable and stale detached nodes are dropped.
      if (plan.item) {
        plan.item.clickableElements = Array.prototype.slice.call(container.children);
      }
    } finally {
      reveal(container);
    }
  });

  return 'lines:' + made + ' items:' + stats.items + ' nodes:' + stats.nodes +
    ' rects:' + stats.rects + ' walkMs:' + Math.round(stats.walkMs) +
    ' geomMs:' + Math.round(stats.geomMs) +
    ' total:' + Math.round(((window.performance && performance.now) ? performance.now() : 0) - beganAt);
  } catch (e) {
    return 'ERR:' + e + ' after:' + made;
  }
};
window.storytellerClampDictionary();
        """

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
        // While a selection is active, skip the forced recursive layout: the
        // WebView asks for layout on every selection update and force-laying out
        // the whole reader makes the selection snap. This applies during playback
        // too; the readaloud decoration is painted inside the WebView and does not
        // need the native layout pass.
        //
        // Nothing else is skipped. An earlier version held this off for the duration of a
        // page-turn gesture to stop it stalling image-heavy slides, but the upstream app
        // this forked from runs the pass unconditionally and has no such lag — so the
        // suppression was the regression, not the cure. Readium's own pager needs the
        // view laid out to scroll it, and withholding that made backward turns stutter.
        val skip = isSelecting || now < selectionLayoutHoldUntil
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
        // Detach and rotation clear the selection without a prop update, so the shared
        // flag is refreshed here too. Otherwise volume navigation would stay disabled
        // after rotating away from a selection.
        publishHasSelection()
    }

    // The volume-key gate lives in ReaderInputState, which MainActivity reads before JS
    // sees the key. It combines a live text selection with an open word menu, since a
    // dictionary menu is not a native selection. The menu is read from activeDictionaryId
    // rather than the prop: the prop travels back through JS, so it is still null in the
    // frame where the menu opens.
    private fun publishHasSelection() {
        // A saved highlight tap opens JS's SelectionMenu without a native selection or
        // a dictionary word menu. Mirror JS's activeHighlight into the gate so volume
        // keys keep adjusting volume while that menu is open.
        ReaderInputState.hasSelection =
            isSelecting || menuVisible || props?.activeHighlightDecorationId != null ||
                props?.selectionMenuVisible == true
    }

    // Re-used on attach and after a navigator rebuild: a modal can detach the view
    // without sending new props, and destroyNavigator clears the activity's gate.
    private fun publishReaderInputState() {
        val currentProps = props ?: return
        ReaderInputState.readerUiVisible = currentProps.readerUiVisible
        // The reader stays mounted under the definition screen. Only an attached view
        // may claim the volume keys, or a prop update from readaloud re-enables the
        // gate on a screen where the JS page-turn listener is hidden.
        ReaderInputState.volumeKeysTurnPages =
            currentProps.volumeKeysTurnPages && isAttachedToWindow
        ReaderInputState.invertVolumeKeys = currentProps.invertVolumeKeys
        publishHasSelection()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        publishReaderInputState()
    }

    override fun onDetachedFromWindow() {
        resetSelectionLayout()
        // The activity reads this gate before JS sees a key. If the reader leaves with
        // the UI hidden it would keep swallowing volume keys on every other screen.
        ReaderInputState.volumeKeysTurnPages = false
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val orientation = resources.configuration.orientation
        val rotated = orientation != lastOrientation
        lastOrientation = orientation
        if (rotated) {
            // Match the reader's rotation behaviour: a rotation dismisses the
            // selection and its system toolbar instead of leaving stale handles
            // and a menu behind. A foldable or split-screen resize keeps the
            // same configuration orientation and must not clear a live selection.
            selectionActionModeCallback?.finishForRotation()
            clearSelection()
            onSelection(mapOf("cleared" to true))
            resetSelectionLayout()
        }
        super.onSizeChanged(w, h, oldw, oldh)
    }

    private fun cancelJumpForReaderInput() {
        if (pendingJumpLocator != null || jumpPositionJob?.isActive == true) {
            pendingJumpLocator = null
            cancelJumpPosition()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelJumpForReaderInput()
            swipeGestureGeneration++
            swipeStartX = event.x
            swipeStartY = event.y
            swipeSelectionCleared = false
            swipeClearedTextSelection = false
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE && !swipeSelectionCleared) {
            // A horizontal drag is a page turn, not a selection drag. Readium's pager
            // refuses to start one while a selection is live (`!isSelecting` in
            // R2WebView.onTouchEvent), and the pager re-checks that on every move, so
            // ending the selection here still lets the drag begin a few pixels in.
            // ReadEra behaves the same way: swiping turns the page and takes the
            // selection with it.
            val dx = abs(event.x - swipeStartX)
            val dy = abs(event.y - swipeStartY)
            // Whether to act is decided from state that cannot go stale: a word menu is
            // open exactly when the active decoration id is set, and a text selection is
            // open exactly when isSelecting is. A third derived flag used to sit here and
            // went out of step — endSelection() and the swipe path both rewrote it — so
            // the branch was skipped and the menu only hid when the turn completed, from
            // onPageTurned, which is the animation's end.
            val menuOpen = isSelecting ||
                activeDictionaryId != null ||
                props?.activeDictionaryDecorationId != null
            if (menuOpen && dx > SWIPE_TO_CLEAR_SELECTION_SLOP && dx > dy * 2) {
                swipeSelectionCleared = true
                swipeClearedTextSelection = isSelecting
                swipeSelectionClearDeadline =
                    SystemClock.elapsedRealtime() + SWIPE_SELECTION_CLEAR_DEADLINE_MS
                // The turn this swipe is about to cause belongs to a new generation. A
                // collapse report that arrives after the deadline is only trustworthy if
                // no turn has happened since the gesture began: otherwise it is the tail
                // of this swipe's own scroll, and reporting it would unmount a menu that
                // has since been restored on the page the reader came back to.
                sawTurnSinceSwipeStart = false
                // The menu goes at once. A swipe is the reader leaving this text, so the
                // menu should not linger until the page has finished moving; "swiping"
                // tells JS to hide it but keep it, so an abandoned swipe can put it back.
                // The wash id stays for the slide, but the menu is no longer visible, so
                // stop gating volume keys and swallowing taps on the destination page.
                if (activeDictionaryId != null) {
                    menuVisible = false
                    publishHasSelection()
                }
                onSelection(mapOf("swiping" to true))
                // Neither the browser selection nor its shading is touched. The pager's
                // drag is blocked by `isSelecting` on Readium's WebView, and that flag is
                // set by the page through the "Android" JavaScript interface. Calling its
                // onSelectionEnd() clears the flag while the selection stays live, so the
                // text keeps its highlight through the transition and the swipe is allowed.
                // The page's own selectionchange listener cannot put the flag back: its
                // latch is already true, and it only calls onSelectionStart when the
                // selection is non-collapsed AND the latch is false.
                //
                activity?.lifecycleScope?.launch {
                    // The pin is dropped here, at the start of the drag, not at its end.
                    // It re-asserts scrollLeft on every scroll event, and a reflowable page
                    // turn *is* a horizontal scroll — so while it was held the pager's own
                    // movement was being undone, which is the backward-turn lag. It only
                    // exists to keep handle-dragging steady, and no handle is being dragged
                    // during a page swipe.
                    navigator?.evaluateJavascript(
                        "storyteller.unpinSelectionScroll && storyteller.unpinSelectionScroll();"
                    )
                    navigator?.evaluateJavascript(
                        "storyteller.releaseSelectionDragLock && storyteller.releaseSelectionDragLock();"
                    )
                    // Not endSelection(): it arms a 200ms layout hold and a 500ms tap
                    // guard, both sized for dragging a selection handle. On a swipe they
                    // made requestLayout skip the pager's own layout, so turning back a
                    // page stuttered or jumped without animating. Only the two flags that
                    // matter here are updated.
                    isSelecting = false
                    publishHasSelection()
                }
            }
        } else if (
            event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            // The gesture is over. The layout hold is dropped here in the common case: a
            // committed turn's animation is driven by the WebView's own invalidate loop,
            // which does not need a host re-measure, so nothing is gained by holding on.
            // Keeping it up would suppress layout long after the reader stopped gesturing.
            val selectionSwipe = swipeSelectionCleared
            if (selectionSwipe) {
                // The selection may have to come back: a swipe that turns no page leaves
                // the reader where it started, so the menu and its highlight are put back
                // rather than dismissed. The scroll pin was already dropped when the drag
                // began — holding it to here is what fought the turn.
                val activityScope = activity?.lifecycleScope
                val restoreGeneration = swipeGestureGeneration
                activityScope?.launch {
                    delay(SWIPE_SELECTION_CLEAR_DEADLINE_MS)
                    if (swipeSelectionCleared && restoreGeneration == swipeGestureGeneration) {
                        swipeSelectionCleared = false
                        // No turn happened, so the reader is still on this page: the menu
                        // goes back. "restore" tells JS this was not a dismissal.
                        if (swipeClearedTextSelection) {
                            // The swipe cleared both the native gate and Readium's WebView
                            // drag lock. Restore them too: otherwise JS puts the plain
                            // selection menu back while hasSelection stays false and the
                            // enabled volume keys turn pages underneath it.
                            isSelecting = true
                            activity?.lifecycleScope?.launch {
                                navigator?.evaluateJavascript(
                                    "storyteller.acquireSelectionDragLock && storyteller.acquireSelectionDragLock();"
                                )
                            }
                        }
                        if (activeDictionaryId != null) {
                            menuVisible = true
                        }
                        publishHasSelection()
                        onSelection(mapOf("restore" to true))
                    }
                    // Cleared here too when no turn arrived; onPageChanged clears it when
                    // one did. The deadline inside requestLayout is only a backstop for a
                    // gesture that never reaches this branch.
                }
            }
        }
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
            when (event.keyCode) {
                93, 117 -> {
                    cancelJumpForReaderInput()
                    turnPageForward(animated = true)
                    return true
                }
                92 -> {
                    cancelJumpForReaderInput()
                    turnPageBackward(animated = true)
                    return true
                }
            }
        }
        return super.dispatchKeyEventPreIme(event)
    }

    fun turnPageForward(animated: Boolean = true) {
        // Scroll layout has a single horizontal page, so ChapterPageTurn falls back and
        // Readium's own turns are disabled there (disablePageTurnsWhileScrolling). Route
        // key presses through the same scroll navigation the edge taps use.
        if (props?.scrollMode == true) {
            handleScrollModeEdgeTap(goForward = true)
            return
        }
        val host = activity ?: return
        host.lifecycleScope.launch {
            val nav = navigator ?: return@launch
            // ChapterPageTurn returns true when it scrolled the current page itself, and null at a
            // chapter boundary or when the page cannot be measured - both of which Readium's own
            // navigation covers, unchanged.
            if (ChapterPageTurn.forward(nav, animated = animated, rtl = chapterTurnRtl) == true) {
                return@launch
            }
            nav.goForward(animated = animated)
        }
    }

    fun turnPageBackward(animated: Boolean = true) {
        if (props?.scrollMode == true) {
            handleScrollModeEdgeTap(goForward = false)
            return
        }
        val host = activity ?: return
        host.lifecycleScope.launch {
            val nav = navigator ?: return@launch
            if (ChapterPageTurn.backward(nav, animated = animated, rtl = chapterTurnRtl) == true) {
                return@launch
            }
            nav.goBackward(animated = animated)
        }
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
    // Fires when the page really changes, so a menu opened on one page can be hidden on
    // the next and brought back when the reader returns. Readium recomputes the current
    // page without turning it, so this is emitted from the same place that decides a
    // turn actually happened rather than from the locator stream.
    val onPageTurned by EventDispatcher()
    val onShowFootnote by EventDispatcher()

    var navigator: EpubNavigatorFragment? = null

    private var isSelecting: Boolean = false
    private var selectionLayoutHoldUntil: Long = 0L
    // Taps that end a selection must only end it. Readium reports the tap once the
    // selection has collapsed, so without this it also turned a page or toggled the
    // reader UI, and the user had to tap again (or the UI flipped under the menu).
    @Volatile
    private var selectionTapGuardUntil: Long = 0L
    private var lastOrientation: Int = resources.configuration.orientation

    // Where the current gesture started, so a horizontal drag can be told from a tap
    // and from the vertical drag of a selection handle. Reset on every touch down.
    private var swipeStartX: Float = 0f
    private var swipeStartY: Float = 0f
    private var swipeSelectionCleared: Boolean = false
    // A swipe can clear a dictionary menu or a live native text selection. The restore
    // path only knows about the menu through activeDictionaryId, so remember which was
    // cleared and put the same ownership back when the swipe turns no page.
    private var swipeClearedTextSelection: Boolean = false
    private var swipeSelectionClearDeadline: Long = 0L
    // Each touch-down advances this. A restore timer captured from an earlier gesture must
    // not fire on a later drag that re-armed swipeSelectionCleared: it would clear that
    // gesture's flag and reopen the menu mid-drag.
    private var swipeGestureGeneration: Long = 0L
    // Set when the page turns after a swipe began. A collapse report arriving later than
    // the swipe's own deadline is then recognised as that swipe's tail rather than a
    // fresh dismissal, which is what lost the menu and highlight under fast switching.
    private var sawTurnSinceSwipeStart: Boolean = false
    private var chapterTurnRtl: Boolean = false
    // A word menu can be hidden by a swipe while its wash is kept for the slide. The wash
    // id alone cannot tell the two apart, so volume gating and tap dismissal use this flag.
    private var menuVisible: Boolean = false
    var selectionActionModeCallback: SelectionActionModeCallback? = null
    private val webViews = LinkedHashSet<WebView>()
    private val webViewScrollPositions = mutableMapOf<WebView, Pair<Int, Int>>()

    fun beginSelection() {
        isSelecting = true
        // A new selection is not the tail of an earlier swipe, so its cleared event
        // must be reported normally.
        sawTurnSinceSwipeStart = false
        publishHasSelection()
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

    // True while a selection is live or was just dismissed by a tap.
    private fun isSelectionTap(): Boolean =
        isSelecting || SystemClock.elapsedRealtime() < selectionTapGuardUntil

    private fun guardSelectionTaps() {
        selectionTapGuardUntil = SystemClock.elapsedRealtime() + 500
    }

    fun endSelection() {
        isSelecting = false
        publishHasSelection()
        guardSelectionTaps()
        // The action mode can be recreated between handle moves. Hold the
        // recursive layout briefly so it cannot reset the selection in that gap.
        selectionLayoutHoldUntil = SystemClock.elapsedRealtime() + 200
        // The scroll pin is what keeps the page still while the selection is collapsed:
        // Readium's own selectionchange handler scrolls the page by half a gutter to
        // hide the handles, and releasing the pin at the same moment let that scroll
        // through — the jitter seen when a swipe ended a selection. A gesture that is
        // still travelling releases the pin when it finishes, not here.
        if (swipeSelectionCleared) return
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
        activeHighlightDecorationId = null,
        selectionMenuVisible = false,
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
        jumpBarBottom = null,
        readerUiVisible = null,
        volumeKeysTurnPages = null,
        invertVolumeKeys = null
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
            // Only a locator that came from outside the reader may move it. A page turn
            // emits a locator, JS stores it, and it arrives back as a prop; navigating to
            // it would undo the turn the reader just made, which is the page flashing and
            // snapping back to where it was. Scroll mode already gates on this flag; paged
            // mode relied on locator equality alone, and a page turn always breaks that.
            if (!change.locatorChangedFromExternalUpdate) return false
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
                activeHighlightDecorationId = pendingProps.activeHighlightDecorationId,
                selectionMenuVisible =
                    pendingProps.selectionMenuVisible ?: oldProps?.selectionMenuVisible ?: false,
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
                jumpBarBottom = pendingProps.jumpBarBottom ?: oldProps?.jumpBarBottom ?: 0.0,
                readerUiVisible = pendingProps.readerUiVisible ?: oldProps?.readerUiVisible ?: false,
                volumeKeysTurnPages =
                    pendingProps.volumeKeysTurnPages ?: oldProps?.volumeKeysTurnPages ?: false,
                invertVolumeKeys = pendingProps.invertVolumeKeys ?: oldProps?.invertVolumeKeys ?: false
            )

        // Published for MainActivity, which sees the volume keys before this view does
        // and cannot read props. A live selection or an open word menu both keep the
        // keys at their normal meaning, so the gate is published as one unit.
        props = finalProps
        publishReaderInputState()

        if (finalProps.isPlaying || (oldProps?.scrollMode == true && !finalProps.scrollMode) ||
            (oldProps != null && oldProps.jumpBarBottom > 0.0 && finalProps.jumpBarBottom <= 0.0)
        ) {
            pendingJumpLocator = null
            cancelJumpPosition()
        }
        // Changing the overlay alone must not run navigation or playback follow.
        // A UI visibility change is the same kind of update: the listener reads the flag
        // live, so nothing below needs to run for it.
        if (oldProps != null &&
            (
                (finalProps.jumpBarBottom != oldProps.jumpBarBottom &&
                    finalProps.copy(jumpBarBottom = oldProps.jumpBarBottom) == oldProps) ||
                    (finalProps.readerUiVisible != oldProps.readerUiVisible &&
                        finalProps.copy(readerUiVisible = oldProps.readerUiVisible) == oldProps)
                )
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
            publishReaderInputState()
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

        if (finalProps.activeHighlightDecorationId != oldProps?.activeHighlightDecorationId) {
            publishHasSelection()
        }

        if (finalProps.selectionMenuVisible != oldProps?.selectionMenuVisible) {
            publishHasSelection()
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
        appliedDictionaryDecorations = listOf()
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
        // Use the direction the navigator actually resolved. The raw metadata is null
        // for books that rely on their language, which Readium still lays out RTL.
        chapterTurnRtl =
            navigator?.settings?.value?.readingProgression == ReadingProgression.RTL
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

        // While the reader UI is on screen it is the only thing a tap should reach.
        // Readium dispatches taps to the registered listeners in order and stops at the
        // first that returns true, so this check has to come before the page-turn
        // listener below, which would otherwise consume a left or right tap and turn the
        // page instead of dismissing the UI.
        navigator?.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                if (props?.readerUiVisible == true) {
                    onMiddleTouch(mapOf())
                    return true
                }
                // A swipe restores the React menu (selectionMenuVisible) without setting
                // the native flag: it clears isSelecting/menuVisible but Epub.tsx puts its
                // own menu back. Check both so a tap on an unmarked edge dismisses that
                // menu instead of falling through to page navigation.
                if (menuVisible || props?.selectionMenuVisible == true) {
                    onSelection(mapOf("cleared" to true))
                    setActiveDictionaryDecoration(null)
                    // The tap only closes the menu. Letting it through turns the page and
                    // then reverts it, which reads as a flicker; a second tap pages.
                    return true
                }
                if (isSelectionTap()) {
                    // A live selection is ended by this tap; never turn the page.
                    if (isSelecting) clearSelection()
                    onSelection(mapOf("cleared" to true))
                    return true
                }
                val handled = layoutBehavior().onTap(
                    this@EpubView,
                    event,
                    directionalNavigationAdapter
                )
                // A tap outside the paging zones was the reader asking for the UI.
                if (!handled) onMiddleTouch(mapOf())
                return true
            }

            // A swipe is how the page is turned, so it is never consumed: returning
            // false lets the reader's own pager take the gesture and change the page.
            // The gesture still dismisses the UI, once, as it starts.
            override fun onDrag(event: DragEvent): Boolean {
                if (
                    props?.readerUiVisible == true &&
                    event.type == DragEvent.Type.Start &&
                    !isSelecting &&
                    System.currentTimeMillis() >= suppressReaderUiDismissUntil
                ) {
                    // The prop that hides the UI travels back through JS, so a second
                    // gesture can arrive before it lands; without this the UI would be
                    // toggled back on by the same swipe.
                    suppressReaderUiDismissUntil =
                        System.currentTimeMillis() + READER_UI_DISMISS_LATCH_MS
                    onMiddleTouch(mapOf())
                }
                return false
            }

            override fun onKey(event: ReadiumKeyEvent): Boolean {
                return directionalNavigationAdapter.onKey(event)
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
        ReaderInputState.volumeKeysTurnPages = false
        selectionActionModeCallback = null
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
        dictionaryPrefetchJob?.cancel()
        dictionaryPrefetchJob = null
        dictionaryWordsVersion++
        dictionaryDecorationJob?.cancel()
        dictionaryDecorationJob = null
        decoratedDictionaryHref = null
        lastDictionarySearchHref = null
        dictionaryOccurrences = listOf()
        appliedDictionaryDecorations = listOf()
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
        // The overscroll *mode* is left at the platform default, as upstream leaves it.
        // This used to force OVER_SCROLL_NEVER, which is the only thing this fork ever did
        // differently here, so removing it restores upstream behaviour on the drag path.
        //
        // I could not show which mechanism it was interfering with: Readium's
        // onOverScrolled callback turns out to track end-of-page for scroll mode, not the
        // pager. It is reverted on the evidence that upstream works and this differed.
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
                // Keep the locator's before/after context. Readium builds them from
                // the text around the whole selection, and the saved-locator quote
                // matcher uses them to pick the right copy of a repeated phrase.
                else -> selection.locator.copy(
                    text = selection.locator.text.copy(highlight = consistentLive)
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
        sawTurnSinceSwipeStart = false

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
            locator.locations.cssSelector != null &&
            bookUuid != null &&
            BookService.getFragments(bookUuid, locator).any { it.fragmentId == fragment }
        ) {
            // Clip IDs are navigation markers, not element selectors. A block-scoped
            // quote resolves the actual sentence instead of its empty clip anchor.
            locator.copy(
                locations = locator.locations.copy(
                    fragments = locator.locations.fragments.drop(1),
                    otherLocations = if (locator.locations.otherLocations[BookService.CLIP_ID_LOCATION_KEY] is String) {
                        locator.locations.otherLocations
                    } else {
                        locator.locations.otherLocations + (BookService.CLIP_ID_LOCATION_KEY to fragment)
                    }
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
                "beforePage" to json.optBoolean("beforePage", false),
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
            val currentProps = props ?: return@launch
            if (!currentProps.isPlaying || !layoutBehavior().supportsPagedClipScheduling ||
                currentProps.locator?.locations?.fragments?.firstOrNull() != fragmentId
            ) return@launch
            val beforePage = result["beforePage"] as? Boolean ?: return@launch
            if (beforePage) {
                // The JavaScript measurement was asynchronous. Do not follow a
                // clip that stopped or changed before this result arrived.
                currentProps.locator?.let { go(it) }
                return@launch
            }
            val crossesPage = result["crossesPage"] as? Boolean ?: return@launch
            if (!crossesPage) return@launch

            val proportion = result["proportionOnCurrentPage"] as? Double ?: return@launch

            player.scheduleClipEvent(fragmentId, proportion) {
                activity.lifecycleScope.launch {
                    if (props?.isPlaying != true ||
                        props?.locator?.locations?.fragments?.firstOrNull() != fragmentId ||
                        !layoutBehavior().supportsPagedClipScheduling
                    ) return@launch

                    val recheck = getFragmentPageProportion(fragmentId) ?: return@launch
                    val liveProps = props ?: return@launch
                    if (!liveProps.isPlaying ||
                        liveProps.locator?.locations?.fragments?.firstOrNull() != fragmentId ||
                        !layoutBehavior().supportsPagedClipScheduling
                    ) return@launch
                    val overflowsRight = recheck["crossesPage"] as? Boolean ?: false

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

    // Gestures that arrive while the UI is still being hidden are ignored, so a swipe
    // cannot dismiss the UI and then a second event toggle it straight back on before
    // the prop has made the round trip through JS.
    private var suppressReaderUiDismissUntil: Long = 0

    override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
        // A decoration tap does not go through the input listeners, so it needs the same
        // check: while the UI is on screen a tap on a marked word only dismisses the UI,
        // rather than opening a menu behind the overlay that was just hidden.
        if (props?.readerUiVisible == true) {
            onMiddleTouch(mapOf())
            return true
        }
        val rect = event.rect ?: return false
        val x = ceil(rect.centerX() / this.resources.displayMetrics.density).toInt()
        val y = ceil(rect.top / this.resources.displayMetrics.density).toInt() - 16
        val id = event.decoration.id
        ignoreSelectionClearedUntil = System.currentTimeMillis() + 400
        if (id.startsWith("dictionary:")) {
            // Tapping the word whose menu is already open closes it. The menu opens on
            // the first tap, so a second tap on the same word means "dismiss"; the
            // activated id is unchanged, so this has to be handled before the setter
            // short-circuits on the same id.
            if (activeDictionaryId == id) {
                onSelection(mapOf("cleared" to true))
                setActiveDictionaryDecoration(null)
                return true
            }
            setActiveDictionaryDecoration(id)
            // A word tap is not a text selection, but the selection flags can still be
            // set from an earlier one: the action mode is hidden rather than destroyed
            // when the dictionary picks up the selection, so onDestroyActionMode never
            // runs. Readium's pager refuses to start a drag while isSelecting is true,
            // so leaving it set made the page impossible to swipe while a word menu was
            // open. Treat the word tap as ending the text selection.
            if (isSelecting) endSelection()
            // The menu is open, which keeps volume navigation off even though the text
            // selection is gone. activeDictionaryId was just set by the call above.
            publishHasSelection()
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
            decorationMutex.withLock {
                // Clear first, always. Readium's onChanged indexes the NEW list with
                // DiffUtil's OLD position, so any Change it emits can throw
                // IndexOutOfBoundsException and kill the app. A Change is emitted not
                // only when the list shrinks but whenever an item keeps its id and
                // changes content — a locator or colour edit does that at the same
                // size — so comparing sizes cannot rule it out. The only safe rule is
                // to never let the reader diff two non-empty lists: clearing produces
                // removals, filling produces insertions, and neither emits a Change.
                navigator?.applyDecorations(emptyList(), group = "highlights")
                navigator?.applyDecorations(decorations, group = "highlights")
            }
        }
    }

    // The mark whose dictionary menu is open, drawn with a selection wash so it is
    // obvious which copy a tap landed on, the way ReadEra does it. The id names one
    // copy of a word, so a word marked several times in a chapter washes only where
    // it was tapped.
    private var activeDictionaryId: String? = null

    // Last page index seen, used to tell a real page turn from a page recompute.
    private var lastPageIndex: Int? = null
    // The resource the page index belongs to. Page indices restart at zero in every spine
    // item, so the index alone cannot tell A|0 from B|0.
    private var lastPageHref: String? = null

    // The saved words the view knows about, each with the book its stored locator
    // belongs to. Kept in step with the dictionaryEntries prop.
    private var dictionaryWords: List<DictionaryWord> = listOf()

    // The marks found in the chapter on screen, painted by decorateDictionaryEntries.
    private var dictionaryOccurrences: List<DictionaryOccurrence> = listOf()

    // The chapter the last search was started for. A page turn inside a chapter
    // leaves it alone, so turning pages never starts a search.
    private var lastDictionarySearchHref: Url? = null
    private var dictionarySearchJob: Job? = null

    // Warms the caches for the chapters around the one on screen, so a turn into a new
    // chapter finds its marks already found. Kept apart from dictionarySearchJob on
    // purpose: that one is cancelled the moment the reader moves, whereas a prefetch in
    // flight for the chapter being entered is exactly the work now wanted.
    private var dictionaryPrefetchJob: Job? = null
    // Bumped whenever the saved words change. A prefetch started before such a change
    // searched for words that no longer apply and is dropped rather than cached.
    private var dictionaryWordsVersion = 0
    // Neighbouring chapters are read by the same extractor, so prefetches take turns.
    private val dictionaryPrefetchMutex = Mutex()

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

    // Applying decorations is not safe while the reader is shrinking a group: its own
    // diff dispatch indexes the new list with an old-list position and throws. Every
    // application is serialised here, and each one either keeps the group the same
    // size or clears it first, so a Change is never emitted for a shrunken group.
    // See applyDictionaryDecorations for the full explanation.
    private val decorationMutex = Mutex()

    private val dictionaryDecorationMutex = decorationMutex
    private var dictionaryDecorations: List<Decoration> = listOf()
    // The list last handed to Readium. A retained id whose locator or style changed is
    // a content Change in Readium's diff, which crashes when the new list is shorter.
    private var appliedDictionaryDecorations: List<Decoration> = listOf()
    private var dictionaryDecorationJob: Job? = null
    private var dictionaryDecorationPending = false
    private var decoratedDictionaryHref: Url? = null
    // Set just before the search repaints with its result, so the paint that is waiting
    // for a search is told apart from the paint the search itself makes.
    private var resultOfSearch = false

    // The readaloud highlight is one decoration that changes on every clip. It is
    // applied through a single worker so a slow, older application cannot land after
    // a newer one and pull the highlight back to the previous sentence. The write it
    // performs is serialised by decorationMutex, like every other group.
    private val readaloudDecorationMutex = Mutex()
    private var readaloudDecorationJob: Job? = null
    private var readaloudDecorationGeneration = 0
    private var requestedReadaloudDecoration: Decoration? = null
    private var requestedReadaloudDecorationVersion = 0
    private var appliedReadaloudDecorationVersion = 0

    private fun dictionaryCacheKey(bookUuid: String, href: Url): String = "$bookUuid|$href"

    /** The href of the next spine item after [index], or null at the end of the book. */
    private fun firstForwardChapter(
        readingOrder: List<org.readium.r2.shared.publication.Link>,
        index: Int
    ): Url? = readingOrder.getOrNull(index + 1)?.url()

    /** The href of the spine item before [index], or null at the start of the book. */
    private fun firstBackwardChapter(
        readingOrder: List<org.readium.r2.shared.publication.Link>,
        index: Int
    ): Url? = readingOrder.getOrNull(index - 1)?.url()

    // A changed set of saved words makes the marks found so far stale, so the
    // chapter on screen is searched again. The extracted text does not depend on
    // the words and stays cached.
    private fun updateDictionaryWords(entries: List<DictionaryEntryDecoration>) {
        dictionaryWords = entries.map { DictionaryWord(it.id, it.word, it.bookUuid) }
        dictionaryOccurrenceCache.clear()
        dictionaryOccurrences = listOf()
        // A prefetch in flight searched for the old words. Its results are useless now, and
        // writing them would hide a mark the reader has just saved, so it is retired.
        dictionaryWordsVersion++

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
            prefetchDictionaryEntries(href)
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
                // The paint that carries the search's result must not be held back by
                // the guard in decorateDictionaryEntries: this job is still running.
                resultOfSearch = true
                decorateDictionaryEntries()
            }
        }

        prefetchDictionaryEntries(href)
    }

    /**
     * Warms the text and occurrence caches for the chapters either side of [href].
     *
     * A turn into the next chapter is the common case, and it is the one the reader
     * complains about: the marks only appear once that chapter's text has been read
     * out of the EPUB and searched, which happens on arrival. Reading it a moment
     * earlier costs an idle frame and makes the paint immediate.
     *
     * Only the two caches are touched. Nothing here applies a decoration, alters
     * [dictionaryOccurrences] or cancels [dictionarySearchJob]; a prefetch that tried to
     * do any of that would fight the paint it is meant to help.
     *
     * Prefetching one chapter on a guess is cheap and wrong occasionally; prefetching
     * several would multiply the reading for turns that mostly do not happen, so this
     * is deliberately limited to the neighbours. A TOC jump to a distant chapter simply
     * finds nothing cached, which is exactly today's behaviour.
     */
    private fun prefetchDictionaryEntries(href: Url) {
        if (dictionaryWords.isEmpty()) return

        // Nothing to warm if the reader is already gone.
        if (!isAttachedToWindow) return

        dictionaryPrefetchJob?.cancel()
        val activity = activity ?: return
        val version = dictionaryWordsVersion
        val bookUuid = props?.bookUuid ?: return
        val neighbours = dictionaryNeighbours(href)
        if (neighbours.isEmpty()) return

        dictionaryPrefetchJob = activity.lifecycleScope.launch {
            // A turn is often the reader's next gesture; let it start and land first.
            delay(DICTIONARY_PREFETCH_DELAY_MS)

            for (neighbour in neighbours) {
                // A new chapter in view means the old search is cancelled and this one is
                // moot, so it stops rather than carrying on behind the paint.
                if (lastDictionarySearchHref != href) return@launch
                if (version != dictionaryWordsVersion) return@launch

                val key = bookUuid + "|" + neighbour
                // containsKey, not get: the caches are access-ordered, and a get here would
                // promote a chapter nobody has turned to ahead of the one being read and
                // push the chapter actually in use out of the LRU.
                if (dictionaryTextCache.containsKey(key) &&
                    dictionaryOccurrenceCache.containsKey(key)
                ) {
                    continue
                }

                dictionaryPrefetchMutex.withLock {
                    if (version != dictionaryWordsVersion) return@withLock
                    val text = dictionaryTextCache[key]
                        ?: DictionarySearch.extractChapterText(bookUuid, neighbour)?.also {
                            dictionaryTextCache[key] = it
                        }
                    if (text == null) continue
                    val occurrences = DictionarySearch.findOccurrences(
                        bookUuid, neighbour, text, dictionaryWords
                    )
                    if (version == dictionaryWordsVersion) {
                        dictionaryOccurrenceCache[key] = occurrences
                    }
                }
            }
        }
    }

    /**
     * The spine items just before and after [href], nearest first.
     *
     * Page turns move one chapter at a time, so the immediate neighbours are what a turn
     * is about to reach. A jump to a distant chapter is not guessed at.
     */
    private fun dictionaryNeighbours(href: Url): List<Url> {
        val bookUuid = props?.bookUuid ?: return listOf()
        val readingOrder = BookService.getPublication(bookUuid)?.readingOrder ?: return listOf()
        val index = readingOrder.indexOfFirstWithHref(href) ?: return listOf()
        if (index < 0) return listOf()

        return buildList {
            firstForwardChapter(readingOrder, index)?.let { add(it) }
            firstBackwardChapter(readingOrder, index)?.let { add(it) }
        }
    }

    fun decorateDictionaryEntries() {
        val props = props ?: return
        // A search that is still running will repaint with the words it finds. Painting
        // now would use the saved-locator fallback, which carries a different locator
        // for the same decoration ids; the later repaint then differs in content only,
        // which the reader reports as a Change at an old index — the index that crashes
        // inside its own diff. Waiting for the search keeps each id's content stable.
        // The paint the search makes when it finishes is the exception: it is the paint
        // this guard is waiting for, and its own job is still active when it runs.
        if (resultOfSearch == false && dictionarySearchJob?.isActive == true) return
        resultOfSearch = false
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
                    // Never the active flag. It is part of the style, and the style is
                    // what the reader compares when diffing: baking it in made every tap
                    // and every entry change alter a decoration's content, which the
                    // reader reports as a Change at an old index — the index that
                    // crashes inside its own diff. The wash is a CSS class that
                    // setActiveDictionaryDecoration moves in the page without repainting,
                    // so the decorations themselves stay identical across taps.
                    active = false,
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
                    locator = trimmedQuoteLocator(entry.locator),
                    style = DictionaryUnderlineStyle(
                        tint = tint,
                        wash = wash,
                    active = false,
                        sparse = true
                    )
                )
            }

        // A word saved on its own, and the same word inside a saved sentence, both
        // cover the same characters: the word search finds it, and the sentence is
        // painted from its own quote, so it would be dotted twice. A single-word mark
        // is dropped when that word, with the text around it, already appears inside a
        // multi-word saved quote painted here.
        //
        // This compares text to text. Comparing positions does not work: the search's
        // progression is a fraction of the chapter reduced to plain text, while a
        // saved locator's progression is Readium's position in the rendered chapter.
        // Those are different measures, so a span computed from one and tested
        // against the other lands nowhere near the word.
        // The candidates are the saved entries themselves, not the decorations painted
        // from a stored locator. A sentence long enough to be found by the word search
        // is filtered out of `fromSavedLocator` (its uuid is in `searchedUuids`), so
        // reading the quotes from there left the list empty and the dedupe compared
        // against nothing. `savedEntries` holds every saved quote in this chapter
        // whichever path paints it.
        val sentenceQuotes = savedEntries.mapNotNull { entry ->
            normalizeQuote(entry.locator.text.highlight).takeIf { it.contains(' ') }
        }

        // Two saved quotes can overlap: "i was hosed" sits inside a long passage that
        // was also saved, and each one is searched and painted on its own. Both then
        // draw dots over the same characters, and because the dots are spaced by
        // `round` to each box's width, the two rows are out of step and read doubled.
        //
        // Which of the two to keep is decided by their spans, which are exact here:
        // every occurrence comes from the same search over the same chapter text, so a
        // mark whose span lies inside another's covers strictly fewer characters and is
        // the one to hide. Comparing quote text instead would hide every copy of a word
        // that happens to appear in a saved sentence; one word in this chapter has seven
        // occurrences, and six of them are nowhere near that sentence.
        fun idOf(occurrence: DictionaryOccurrence) = "dictionary:${occurrence.uuid}#${occurrence.index}"
        val spanById = occurrences.associateBy { idOf(it) }

        // A mark is covered when another's span contains it (identical spans are settled
        // by id, smaller first). Sorted by start, longest first, every covering mark is
        // seen before the ones it covers, so one pass finds them all. The old per-mark
        // full scan was O(n²): 10,000 disjoint matches cost ~200M id allocations and
        // comparisons on the main thread while a chapter was entered.
        val coveredIds = HashSet<String>()
        var farthestEnd = Int.MIN_VALUE
        occurrences
            .sortedWith(
                compareBy<DictionaryOccurrence> { it.spanStart }
                    .thenByDescending { it.spanEnd }
                    .thenBy { idOf(it) }
            )
            .forEach { occurrence ->
                val end = occurrence.spanEnd
                if (end <= farthestEnd) coveredIds.add(idOf(occurrence))
                if (end > farthestEnd) farthestEnd = end
            }
        fun coveredByLongerMark(id: String) = id in coveredIds

        val dedupedFromSearch = fromSearch.filterNot { mark -> coveredByLongerMark(mark.id) }

        // A covered mark keeps its decoration but draws no dots: it is the tap target
        // for its own menu, so it is hidden rather than dropped.
        val hiddenFromSearch = fromSearch.filter { mark -> coveredByLongerMark(mark.id) }

        // A stored locator has no span in the searched text, so it falls back to the
        // quote: a single word covered by a saved sentence is hidden rather than dropped.
        val hiddenFromSavedLocator = fromSavedLocator.filter { mark ->
            val quote = normalizeQuote(mark.locator.text.highlight)
            quote.isNotEmpty() && !quote.contains(' ') &&
                sentenceQuotes.any { sentence -> containsWholeWord(sentence, quote) }
        }
        val paintedFromSavedLocator = fromSavedLocator - hiddenFromSavedLocator.toSet()

        // The active word is hidden like any other: it has no dots because the longer
        // mark draws the row, and it keeps its wash because this is the box the menu
        // just opened for. Readium hands a tap to the newest matching decoration, so
        // these are appended last: a tap on the covered text finds it first there,
        // while the longer mark still wins everywhere that text is not.
        //
        // Within that group the shortest comes last, because two covered marks can
        // overlap in turn: a saved word inside a saved phrase inside a saved passage is
        // hidden twice, and the tap has to reach the word, not the phrase.
        val hiddenMarkers = (hiddenFromSearch + hiddenFromSavedLocator)
            .map { mark ->
                val span = spanById[mark.id]
                val length = if (span == null) Int.MAX_VALUE else span.spanEnd - span.spanStart
                length to Decoration(
                    id = mark.id,
                    locator = mark.locator,
                    style = DictionaryUnderlineStyle(
                        tint = tint,
                        wash = wash,
                        active = false,
                        sparse = true,
                        hidden = true,
                    )
                )
            }
            .sortedByDescending { it.first }
            .map { it.second }
            .distinctBy { it.id }

        dictionaryDecorations = dedupedFromSearch + paintedFromSavedLocator + hiddenMarkers
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

    // A saved selection usually ends past the last glyph: a space, or the newline
    // Selection.toString() inserts at a block break. Readium resolves the stored
    // quote verbatim, so the range ends beyond the line and the last line then gets
    // a full-width rect. Stripping the whitespace and handing it back to before/after
    // keeps the quote context, so the same copy is still matched. An all-whitespace
    // highlight is left alone: an empty one makes Readium fall back to the selector.
    private fun trimmedQuoteLocator(locator: Locator): Locator {
        val text = locator.text
        val highlight = text.highlight ?: return locator
        val trimmed = highlight.trim()
        if (trimmed.isEmpty() || trimmed.length == highlight.length) return locator

        val leading = highlight.substring(0, highlight.length - highlight.trimStart().length)
        val trailing = highlight.substring(highlight.trimEnd().length)
        return locator.copy(
            text = text.copy(
                before = (text.before ?: "") + leading,
                highlight = trimmed,
                after = trailing + (text.after ?: ""),
            )
        )
    }

    // True when [sentence] contains [word] as a whole word. Punctuation counts as a
    // boundary, so "hosed" matches "I was hosed." and does not match "hosedown".
    private fun containsWholeWord(sentence: String, word: String): Boolean {
        if (sentence.isEmpty() || word.isEmpty()) return false
        var from = 0
        while (true) {
            val at = sentence.indexOf(word, from)
            if (at < 0) return false
            val before = sentence.getOrNull(at - 1)
            val after = sentence.getOrNull(at + word.length)
            val beforeOk = before == null || !before.isLetterOrDigit()
            val afterOk = after == null || !after.isLetterOrDigit()
            if (beforeOk && afterOk) return true
            from = at + 1
        }
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
            // A paint with no marks has no boxes to size, and the clamp is the expensive part:
            // it walks the chapter word by word per item. Arriving at a chapter with no saved
            // word in it used to run that whole script over an empty group, twice, while the
            // incoming page was still laying out - and the pass that did have marks waited
            // behind it. Applying the empty list is cheap, and it is the only way to
            // remove dots for a word deleted while the reader is still on this chapter.
            if (dictionaryDecorations.isEmpty()) {
                val href = navigator?.currentLocator?.value?.href
                dictionaryDecorationMutex.withLock {
                    decoratedDictionaryHref = href
                    navigator?.applyDecorations(emptyList(), group = "dictionary")
                    appliedDictionaryDecorations = listOf()
                }
                // This paint can wait on the mutex behind another one. A search that
                // finished while we waited replaces the list and sets the pending flag;
                // returning now would drop those marks until the next refresh. Fall
                // through to the pending loop so they get painted.
                if (!dictionaryDecorationPending && dictionaryDecorations.isEmpty()) {
                    return@launch
                }
            }
            do {
                dictionaryDecorationPending = false
                delay(DICTIONARY_DECORATION_SETTLE_MS)
                // Only the writes are inside the lock, and nothing yields within it.
                //
                // Readium's own change callback has a coordinate bug: DiffUtil reports a
                // Change with a position in the OLD list, and Readium indexes the NEW
                // list with it (`target[position + i]` in DecorableNavigator.kt). A group
                // that shrinks therefore throws IndexOutOfBoundsException inside the
                // reader and kills the app — it happened with an index around 100 against
                // a list of about 32. So the group is never updated in place: it is
                // emptied first, which can only produce removals, then filled, which can
                // only produce insertions. Neither emits a Change, so the bad index is
                // unreachable.
                dictionaryDecorationMutex.withLock {
                    val href = navigator?.currentLocator?.value?.href
                    // Cleared only when the chapter changes, where the two lists share
                    // no ids and every item is genuinely added or removed. Repainting the
                    // same chapter is a diff between two lists whose items now carry
                    // identical content, so it produces removals and insertions and no
                    // Changes. Clearing on every repaint instead emptied the reader's
                    // stored group, and a page that reloaded afterwards re-registered
                    // from that empty store, which is why the dots vanished entirely.
                    val chapterChanged = href != decoratedDictionaryHref
                    decoratedDictionaryHref = href
                    val before = appliedDictionaryDecorations.associateBy { it.id }
                    val contentChanged = dictionaryDecorations.any { next ->
                        val old = before[next.id]
                        old != null && (old.locator != next.locator || old.style != next.style)
                    }
                    val idsChanged =
                        dictionaryDecorations.map { it.id } != appliedDictionaryDecorations.map { it.id }
                    // Readium's diff reads target[i] for a Change and insertions, with an
                    // index from the old list. A shrink, a reused id with changed content,
                    // or an insertion that shifts later ids all index the wrong list.
                    if (chapterChanged || contentChanged || idsChanged) {
                        navigator?.applyDecorations(emptyList(), group = "dictionary")
                    }
                    // applyDecorations suspends while Readium diffs. A deletion arriving
                    // in that window can replace dictionaryDecorations, so submit and
                    // record the same snapshot rather than reading the field twice.
                    val submitted = dictionaryDecorations.toList()
                    navigator?.applyDecorations(submitted, group = "dictionary")
                    appliedDictionaryDecorations = submitted
                }
                // The boxes are hidden by the pending class until this sizes them,
                // so every extra millisecond here is a visible gap on a tap. Readium
                // only enqueues its script, hence the short settle before measuring.
                delay(DICTIONARY_DECORATION_GAP_MS)
                withTimeoutOrNull(2000L) {
                    navigator?.evaluateJavascript(clampDictionaryDecorations)
                }
                // Readium recreates the boxes from the template on its own body
                // ResizeObserver, which re-adds the pending class and discards the
                // sizes written here. That path never calls this method, so watch
                // the document instead and clamp again after any reflow.
                navigator?.evaluateJavascript(installDictionaryClampObserverJs())
                // A repaint rebuilds every box from the inactive template, which drops
                // the wash class. The setter short-circuits when the id is unchanged, so
                // an Undo that rebuilds the group while a menu is open needs it here.
                activeDictionaryId?.let { id ->
                    activity.lifecycleScope.launch {
                        navigator?.evaluateJavascript(activeMarkScript(null, id))
                    }
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
        val previous = activeDictionaryId
        activeDictionaryId = id
        // A non-null id means a menu just opened. A swipe can leave the id set while the
        // menu is hidden; that path clears menuVisible without calling this setter.
        menuVisible = id != null
        // Opening or closing a menu changes whether volume keys turn the page.
        publishHasSelection()
        // Re-applying the whole list just to move one wash rebuilds every box: the
        // clamp runs again, so the dots blink off and on, and the menu waits for
        // all of it. The active state is a single class, so it moves in the page.
        val activity = appContext.currentActivity as? FragmentActivity ?: return
        activity.lifecycleScope.launch {
            navigator?.evaluateJavascript(activeMarkScript(previous, id))
        }
    }

    // Moves the active class between two decoration containers without touching any
    // geometry, so the dots never repaint and nothing has to be re-measured.
    private fun activeMarkScript(previous: String?, next: String?): String {
        fun selector(decorationId: String) =
            decorationId.replace("\\", "\\\\").replace("'", "\'")
        val group = "readium.getDecorations('dictionary')"
        val body = StringBuilder("(function () {")
        body.append("var g = $group; if (!g) return 'no-group';")
        // Readium's hit-test reverses `items` in place on every decoration tap. Closing
        // a menu used to rebuild the group, which reset that order; a class-only update
        // leaves it reversed, so a second tap checks the passage before the word inside
        // it. Put the items back in painted order (shortest covered mark last, so it wins
        // the hit-test) without repainting the dots.
        val painted = appliedDictionaryDecorations.joinToString(",") { "'" + selector(it.id) + "'" }
        body.append("var rank = {}; [").append(painted)
        body.append("].forEach(function (id, i) { rank[id] = i; });")
        body.append("g.items.sort(function (a, b) {")
        body.append("var ra = rank[a.decoration.id], rb = rank[b.decoration.id];")
        body.append("if (ra === undefined) ra = 1e9; if (rb === undefined) rb = 1e9;")
        body.append("return ra - rb; });")
        // The reflow observer reads this after Readium rebuilds boxes from the
        // template, so an open menu keeps its wash when an image finishes loading.
        body.append("window.__storytellerActiveDecorationId = ")
        body.append(next?.let { "'" + selector(it) + "'" } ?: "null")
        body.append(";")
        if (previous != null) {
            body.append("g.items.forEach(function (i) { if (i.decoration.id === '")
            body.append(selector(previous))
            body.append("' && i.container) Array.prototype.slice.call(i.container.children).forEach(function (b) { b.classList.remove('$dictionaryActiveClass'); }); });")
        }
        if (next != null) {
            body.append("g.items.forEach(function (i) { if (i.decoration.id === '")
            body.append(selector(next))
            body.append("' && i.container) Array.prototype.slice.call(i.container.children).forEach(function (b) { b.classList.add('$dictionaryActiveClass'); }); });")
        }
        body.append("return 'ok';})();")
        return body.toString()
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
        if (locator.locations.fragments.firstOrNull() == null) {
            // A clip with no fragment cannot be located in this chapter. Remove the
            // previous mark instead of leaving it frozen on an older sentence.
            clearReadaloudDecoration()
            return
        }

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
                        // The cross-group lock is taken while holding this one. The two
                        // are never taken in the other order, so they cannot deadlock.
                        decorationMutex.withLock {
                            // One decoration at a time, so this group never shrinks and
                            // never emits the Change that Readium mis-indexes.
                            nav.applyDecorations(emptyList(), "overlay")
                            nav.applyDecorations(listOfNotNull(pending), "overlay")
                        }
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
                // Lets a swipe start while a text selection stays live. Readium's pager
                // refuses to begin a drag while its WebView's `isSelecting` is true, and
                // that flag is set and cleared by this page through the "Android"
                // JavaScript interface. Calling onSelectionEnd() clears it without
                // disturbing the browser selection, so the highlight is never lost. The
                // page's own selectionchange listener cannot set the flag back: its latch
                // is already true, and it only calls onSelectionStart when the selection is
                // non-collapsed AND the latch is false, which needs a collapse first.
                storyteller.releaseSelectionDragLock = function() {
                    if (typeof Android === 'undefined' || !Android.onSelectionEnd) return 'no-interface';
                    Android.onSelectionEnd();
                    return 'released';
                };
                // Symmetric to releaseSelectionDragLock, for an abandoned swipe that
                // restores a still-live text selection: Readium's pager must keep
                // refusing drags while its menu is back on screen.
                storyteller.acquireSelectionDragLock = function() {
                    if (typeof Android === 'undefined' || !Android.onSelectionStart) return 'no-interface';
                    Android.onSelectionStart();
                    return 'acquired';
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
                    const anchor = document.getElementById(fragmentId);
                    if (!anchor) return null;

                    const nextId = storyteller.fragmentIds.slice(storyteller.fragmentIds.indexOf(fragmentId) + 1)
                        .find((id) => document.getElementById(id));
                    const nextAnchor = nextId ? document.getElementById(nextId) : null;
                    const range = document.createRange();
                    if (anchor.textContent && anchor.textContent.trim()) {
                        range.selectNodeContents(anchor);
                    } else if (nextAnchor) {
                        range.setStartAfter(anchor);
                        range.setEndBefore(nextAnchor);
                    } else {
                        // The last anchor marks the beginning of its clip, not a
                        // whole empty element. Use the rest of its text container.
                        const container = anchor.closest('p, h1, h2, h3, h4, h5, h6, li, div');
                        range.selectNodeContents(container || anchor.parentNode || anchor);
                        try { range.setStartAfter(anchor); } catch (_) { return null; }
                    }

                    const scroller = document.scrollingElement || document.documentElement;
                    const leadingEdge = scroller.scrollLeft;
                    const pageWidth = window.innerWidth;

                    const rects = Array.from(range.getClientRects()).filter(
                        (rect) => rect.width > 0 && rect.height > 0
                    );
                    if (rects.length === 0) return null;

                    let visibleWidth = 0;
                    let totalWidth = 0;
                    let beforePage = true;
                    let afterPage = true;
                    let crossesPage = false;
                    for (const rect of rects) {
                        const left = rect.left + scroller.scrollLeft;
                        const right = rect.right + scroller.scrollLeft;
                        const width = right - left;
                        totalWidth += width;
                        beforePage = beforePage && right <= leadingEdge;
                        afterPage = afterPage && left >= leadingEdge + pageWidth;
                        if (right > leadingEdge + pageWidth) crossesPage = true;
                        const overlap = Math.min(right, leadingEdge + pageWidth) - Math.max(left, leadingEdge);
                        if (overlap > 0) visibleWidth += overlap;
                    }

                    return {
                        crossesPage,
                        beforePage,
                        proportionOnCurrentPage: visibleWidth / totalWidth
                    };
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
        // Only a selection the user had can make the next tap a dismissal. A plain
        // tap also reports a collapsed selection and must still turn the page.
        if (isSelecting) guardSelectionTaps()
        selectionActionModeCallback?.handleSelectionCleared()
        // A swipe scrolls the document, the browser collapses the Selection, and this
        // fires — after the turn, not before it. Reporting it would unmount the menu as a
        // fresh dismissal and throw away the stash for the page being left, which is how
        // the menu ended up showing on the new page. The deadline outlives the turn for
        // exactly this reason; and past the deadline a turn since the gesture began means
        // this is a late tail of that same swipe, not a dismissal.
        val withinDeadline = SystemClock.elapsedRealtime() <= swipeSelectionClearDeadline
        if (withinDeadline) return
        if (sawTurnSinceSwipeStart) return
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

        if (props!!.isPlaying && props!!.locator?.href == locator.href) {
            // A same-chapter page turn does not change href. Repaint only from
            // the navigator's locator callback, not every prop reconciliation.
            requestedReadaloudDecoration = null
            applyReadaloudDecoration(props!!.locator!!)
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

        // A layout switch rebuilds the page documents but keeps the chapter, so no
        // dictionary paint runs and boxes recreated from the template keep the pending
        // class that hides their dots. Re-clamp and re-install the reflow observer here,
        // mirroring the paint path, so the underlines appear without another paint.
        // evaluateJavascript is suspending, and Readium may register the rebuilt boxes
        // just after this callback, so install first and clamp again after a settle.
        activity?.lifecycleScope?.launch {
            navigator?.evaluateJavascript(clampDictionaryDecorations)
            navigator?.evaluateJavascript(installDictionaryClampObserverJs())
            delay(DICTIONARY_DECORATION_GAP_MS)
            navigator?.evaluateJavascript(
                "if (window.storytellerClampDictionary) window.storytellerClampDictionary();"
            )
        }

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
        // page actually turns, so the position is compared first. Otherwise a tap that
        // only opens the dictionary menu would drop the mark it just set.
        //
        // The href is part of the comparison because a page index alone cannot notice a
        // resource change: every spine item numbers its pages from zero, so A|0 -> B|0
        // looked like no turn at all and JS was told turned=false while the reader had
        // plainly moved.
        val hrefChanged = lastPageHref != null && lastPageHref != locator.href.toString()
        val pageTurned = lastPageIndex == null ||
            lastPageIndex != pageIndex ||
            hrefChanged
        val wasSwiping = swipeSelectionCleared
        lastPageIndex = pageIndex
        lastPageHref = locator.href.toString()
        // The turn has landed, so the layout hold taken at drag start is finished with:
        // the incoming page gets its ordinary re-measure, and holding longer would only
        // suppress layout after the reader has stopped gesturing.
        // A swipe that collapsed a selection only discards it once the page has really
        // moved. Telling JS at touch-down unmounted the menu immediately, which read as
        // the selection vanishing the moment a drag began; and a swipe that is aborted
        // should leave the selection alone. From here on the page is gone, so the
        // selection goes with it.
        if (pageTurned) {
            // A real turn always cancels a pending "restore". A swipe longer than the
            // collapse deadline would otherwise emit restore from its release timer and
            // put the old page's menu back on the new page. wasSwiping was captured
            // above, so the wash decision below is unaffected.
            swipeSelectionCleared = false
        }
        if (pageTurned && activeDictionaryId != null && !wasSwiping) {
            // The wash is a class on the decoration box inside the page, so it travels
            // with the text while the page slides. It is cleared here only for a turn the
            // reader did not start with a swipe — a tap, a key, a jump — where there is no
            // slide to keep it through. A swipe keeps it.
            setActiveDictionaryDecoration(null)
        }
        // Told to JS even when no mark was open: the menu is a React overlay, so it has
        // to learn about the turn from here. The current page is reported on every
        // page change, not only on a turn, so JS knows which page a menu opened on
        // without having to guess; `turned` says whether the page actually moved.
        if (pageTurned) sawTurnSinceSwipeStart = true
        onPageTurned(
            mapOf(
                "pageIndex" to pageIndex,
                "href" to locator.href.toString(),
                "turned" to pageTurned,
            )
        )
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
