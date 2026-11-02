package expo.modules.readium

import org.readium.r2.navigator.epub.EpubNavigatorFragment

internal class ScrollModeChapterNavigator {
    fun goForward(navigator: EpubNavigatorFragment, animated: Boolean): Boolean =
        invokeResourceNavigation(navigator, methodName = "goToNextResource", animated = animated)

    fun goBackward(navigator: EpubNavigatorFragment, animated: Boolean): Boolean =
        invokeResourceNavigation(navigator, methodName = "goToPreviousResource", animated = animated)

    private fun invokeResourceNavigation(
        navigator: EpubNavigatorFragment,
        methodName: String,
        animated: Boolean
    ): Boolean =
        try {
            navigator.javaClass
                .getDeclaredMethod(
                    methodName,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
                .let { method ->
                    method.isAccessible = true
                    method.invoke(navigator, true, animated) as? Boolean ?: false
                }
        } catch (_: NoSuchMethodException) {
            false
        } catch (_: IllegalAccessException) {
            false
        } catch (_: SecurityException) {
            false
        }
}
