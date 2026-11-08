package dev.dictionary.storyteller
import expo.modules.splashscreen.SplashScreenManager

import android.os.Build
import android.os.Bundle

import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

import expo.modules.ReactActivityDelegateWrapper
import android.os.Parcel
import android.annotation.SuppressLint
import android.view.KeyEvent
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.bridge.Arguments

class MainActivity : ReactActivity() {
  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (event.action == KeyEvent.ACTION_DOWN) {
      if (listOf(92, 93, 117).contains(event.keyCode)) {
        sendKeyEvent(event.keyCode)
        return true
      }
    }

    return super.dispatchKeyEvent(event)
  }

  @SuppressLint("VisibleForTests")
  private fun sendKeyEvent(keyCode: Int) {
    val context = getReactHost().currentReactContext
    if (context != null) {
      val params = Arguments.createMap()
      params.putInt("keyCode", keyCode)
      context
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        .emit("storyteller:keydown", params)
    }
  }

  /**
   * Added to not save instance state when the state is too large. When the
   * parcel is too large, a crash occurs in the form of the ones at
   * https://github.com/facebook/react-native/issues/19458
   *
   * This usually happens because the Android activity saves the information
   * for the entire window hierarchy. This can get really large if there are
   * a ton of views in a FlatList:
   * https://android.googlesource.com/platform/frameworks/base/+/808b9f1b730ed7d046c26d0c11181632379ce570/core/java/android/app/Activity.java#2275
   *
   * Not saving state should be fine for the user too. We don't rely on
   * instance state for the state of our app, as everything is stored using
   * AsyncStorage inside of JS code instead.
   */
  override fun onSaveInstanceState(outState: Bundle) {
    try {
      super.onSaveInstanceState(outState)
      val parcel = Parcel.obtain()

      parcel.writeBundle(outState)
      val size = parcel.dataSize()

      // This was determined by looking at Bugsnag errors like:
      // "java.lang.RuntimeException: android.os.TransactionTooLargeException: data parcel size 529044 bytes"
      //
      // There were no errors for parcels with size < 520000 and many
      // above. As such, we limit the parcel size to 500000 to have a bit
      // of leeway.
      val maximumParcelSize = 500000
      if (size > maximumParcelSize) {
          outState.clear()
      }

      parcel.recycle()
    } catch (e: Exception) {
      // Failed saves don't hurt anyone, so we just swallow the errors
      // Alternatively log a handled error
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    // Set the theme to AppTheme BEFORE onCreate to support
    // coloring the background, status bar, and navigation bar.
    // This is required for expo-splash-screen.
    // setTheme(R.style.AppTheme);
    // @generated begin expo-splashscreen - expo prebuild (DO NOT MODIFY) sync-f3ff59a738c56c9a6119210cb55f0b613eb8b6af
    SplashScreenManager.registerOnActivity(this)
    // @generated end expo-splashscreen
    super.onCreate(null)
  }

  /**
   * Returns the name of the main component registered from JavaScript. This is used to schedule
   * rendering of the component.
   */
  override fun getMainComponentName(): String = "main"

  /**
   * Returns the instance of the [ReactActivityDelegate]. We use [DefaultReactActivityDelegate]
   * which allows you to enable New Architecture with a single boolean flags [fabricEnabled]
   */
  override fun createReactActivityDelegate(): ReactActivityDelegate {
    return ReactActivityDelegateWrapper(
          this,
          BuildConfig.IS_NEW_ARCHITECTURE_ENABLED,
          object : DefaultReactActivityDelegate(
              this,
              mainComponentName,
              fabricEnabled
          ){})
  }

  /**
    * Align the back button behavior with Android S
    * where moving root activities to background instead of finishing activities.
    * @see <a href="https://developer.android.com/reference/android/app/Activity#onBackPressed()">onBackPressed</a>
    */
  override fun invokeDefaultOnBackPressed() {
      if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
          if (!moveTaskToBack(false)) {
              // For non-root activities, use the default implementation to finish them.
              super.invokeDefaultOnBackPressed()
          }
          return
      }

      // Use the default back button implementation on Android S
      // because it's doing more than [Activity.moveTaskToBack] in fact.
      super.invokeDefaultOnBackPressed()
  }
}
