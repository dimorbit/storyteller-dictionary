package expo.modules.readium

import org.readium.r2.navigator.epub.EpubNavigatorFragment

/**
 * Turns a page inside the current chapter using Readium's own animated scroller.
 *
 * `EpubNavigatorFragment.goForward/goBackward` do not animate a reflowable page: they call
 * `R2WebView.scrollRight/scrollLeft`, which asks the page in JavaScript to move, and the page
 * assigns `scrollLeft` directly. Nothing smooths that assignment, so the text jumps to the next
 * column. A swipe looks smooth because it takes a different path - the fling branch of
 * `R2WebView.onTouchEvent` calls `setCurrentItemInternal(target, true, velocity)`, which drives the
 * native Scroller and reports intermediate positions. `R2WebView.setCurrentItem` exposes that same
 * scroller.
 *
 * So a turn that stays inside the chapter is sent there instead. A turn that would leave the
 * chapter returns null and the caller keeps Readium's own navigation, so resource changes behave
 * exactly as upstream and no turn is ever swallowed here.
 *
 * Reflection is deliberate: `R2WebView` is an internal Kotlin class and does not compile
 * from this module, and its accessors carry a `$readium_navigator_release` suffix, so a guessed
 * `getNumPages` would compile and then silently fail. Names are matched by prefix, and every step
 * degrades to null rather than throwing.
 */
internal object ChapterPageTurn {

    /** True when turned here; null when Readium should handle it. */
    fun forward(
        navigator: EpubNavigatorFragment,
        animated: Boolean,
        rtl: Boolean,
    ): Boolean? = turn(navigator, delta = 1, animated = animated, rtl = rtl)

    fun backward(
        navigator: EpubNavigatorFragment,
        animated: Boolean,
        rtl: Boolean,
    ): Boolean? = turn(navigator, delta = -1, animated = animated, rtl = rtl)

    private fun turn(
        navigator: EpubNavigatorFragment,
        delta: Int,
        animated: Boolean,
        rtl: Boolean,
    ): Boolean? {
        val webView = currentWebView(navigator) ?: return null
        val pages = intGetter(webView, "getNumPages") ?: return null
        if (pages <= 1) return null

        val current = intGetter(webView, "getMCurItem") ?: return null
        // In RTL the physical page index runs opposite to reading order. Readium's own
        // goForward/goBackward account for that, so this path must too.
        val target = current + if (rtl) -delta else delta
        if (target < 0 || target >= pages) {
            return null
        }

        val setter = methods(webView).firstOrNull { m ->
            m.name == "setCurrentItem" &&
                m.parameterTypes.size == 2 &&
                m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                m.parameterTypes[1] == Boolean::class.javaPrimitiveType
        } ?: return null

        return try {
            setter.isAccessible = true
            setter.invoke(webView, target, animated)
            true
        } catch (_: Throwable) {
            null
        }
    }

    private fun currentWebView(navigator: EpubNavigatorFragment): Any? {
        val fragment = methods(navigator)
            .firstOrNull {
                it.parameterCount == 0 &&
                    it.name.startsWith("getCurrentReflowablePageFragment")
            }
            ?.let { m ->
                try {
                    m.isAccessible = true
                    m.invoke(navigator)
                } catch (_: Throwable) {
                    null
                }
            }
            ?: return null

        return fields(fragment)
            .firstOrNull { it.name == "webView" }
            ?.let { f ->
                try {
                    f.isAccessible = true
                    f.get(fragment)
                } catch (_: Throwable) {
                    null
                }
            }
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

    private fun fields(instance: Any): List<java.lang.reflect.Field> {
        val out = mutableListOf<java.lang.reflect.Field>()
        var c: Class<*>? = instance.javaClass
        while (c != null && c != Any::class.java) {
            out.addAll(c.declaredFields)
            c = c.superclass
        }
        return out
    }
}
