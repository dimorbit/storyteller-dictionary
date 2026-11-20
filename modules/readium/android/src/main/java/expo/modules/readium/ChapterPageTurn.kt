package expo.modules.readium

import android.graphics.Rect
import android.view.View
import android.webkit.WebView

/**
 * Turns a page inside the current chapter using Readium's own animated scroller.
 *
 * `EpubNavigatorFragment.goForward/goBackward` do not animate a reflowable page: they call
 * `R2WebView.scrollRight/scrollLeft`, which asks the page in JavaScript to move, and the page
 * assigns `scrollLeft` directly. Nothing smooths that assignment, so the text jumps to the next
 * column. A swipe looks smooth because it takes a different path - the fling branch of
 * `R2WebView.onTouchEvent` calls `setCurrentItemInternal(target, true, velocity)`, which drives the
 * native Scroller and reports intermediate positions.
 *
 * So a turn that stays inside the chapter is sent there instead. A turn that would leave the
 * chapter returns null and the caller keeps Readium's own navigation, so resource changes behave
 * exactly as upstream and no turn is ever swallowed here.
 *
 * The WebViews come from EpubView, which already collects them while disabling overscroll.
 * Reflecting into the navigator for the current page fragment does not survive R8: release builds
 * rename both the fragment getter and its `webView` field. The R2WebView accessors used below
 * keep their names (or a stable prefix), and every step degrades to null rather than throwing.
 */
internal object ChapterPageTurn {

    /** True when turned here; null when Readium should handle it. */
    fun forward(
        webViews: Collection<WebView>,
        animated: Boolean,
        rtl: Boolean,
    ): Boolean? = turn(webViews, delta = 1, animated = animated, rtl = rtl)

    fun backward(
        webViews: Collection<WebView>,
        animated: Boolean,
        rtl: Boolean,
    ): Boolean? = turn(webViews, delta = -1, animated = animated, rtl = rtl)

    private fun turn(
        webViews: Collection<WebView>,
        delta: Int,
        animated: Boolean,
        rtl: Boolean,
    ): Boolean? {
        val candidates = webViews.filter { view ->
            isVisible(view) && (intGetter(view, "getNumPages") ?: 0) > 1 &&
                intGetter(view, "getMCurItem") != null
        }
        // During an animated chapter transition both the outgoing and incoming WebViews are
        // visible. Which one Readium has selected is not reflected here, so turning either
        // could scroll the old chapter and swallow the requested turn. Fall back to Readium
        // while more than one chapter is visible.
        if (candidates.size != 1) return null
        val webView = candidates[0]

        val pages = intGetter(webView, "getNumPages") ?: return null
        if (pages <= 1) return null

        val current = intGetter(webView, "getMCurItem") ?: return null
        // In RTL the physical page index runs opposite to reading order. Readium's own
        // goForward/goBackward account for that, so this path must too.
        val target = current + if (rtl) -delta else delta
        if (target < 0 || target >= pages) {
            return null
        }

        // R8 inlines setCurrentItem(int, boolean) into its callers, so in a release APK
        // that name is gone. The retained internal setter is setCurrentItemInternal(index,
        // smoothScroll, velocity) and drives the same native Scroller.
        val intType = Int::class.javaPrimitiveType
        val boolType = Boolean::class.javaPrimitiveType
        val setter = methods(webView).firstOrNull { m ->
            m.name == "setCurrentItem" &&
                m.parameterTypes.contentEquals(arrayOf(intType, boolType))
        } ?: methods(webView).firstOrNull { m ->
            m.name.startsWith("setCurrentItemInternal") &&
                m.returnType == Void.TYPE &&
                m.parameterTypes.contentEquals(arrayOf(intType, boolType, intType))
        } ?: return null

        return try {
            setter.isAccessible = true
            when (setter.parameterTypes.size) {
                2 -> setter.invoke(webView, target, animated)
                3 -> setter.invoke(webView, target, animated, 0)
                else -> return null
            }
            true
        } catch (_: Throwable) {
            null
        }
    }

    private fun isVisible(view: View): Boolean {
        if (!view.isAttachedToWindow || view.visibility != View.VISIBLE || view.width <= 0) {
            return false
        }
        val rect = Rect()
        return view.getGlobalVisibleRect(rect) && rect.width() > 0
    }

    private fun intGetter(instance: Any, prefix: String): Int? =
        methods(instance)
            .firstOrNull { it.parameterCount == 0 && it.name.startsWith(prefix) }
            ?.let { m ->
                try {
                    m.isAccessible = true
                    (m.invoke(instance) as? Number)?.toInt()
                } catch (_: Throwable) {
                    null
                }
            }

    private fun methods(instance: Any): List<java.lang.reflect.Method> {
        val out = mutableListOf<java.lang.reflect.Method>()
        var c: Class<*>? = instance.javaClass
        while (c != null && c != Any::class.java) {
            out.addAll(c.declaredMethods)
            c = c.superclass
        }
        return out
    }
}
