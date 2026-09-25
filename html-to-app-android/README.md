# Peak Mild Effort – Android app

An offline Android app wrapping the tracker in the repository root's `index.html`. Your data lives in the app's private storage (the page's `localStorage`). The app requests no permissions, not even internet access.

Versions: AGP 9.3.3, Gradle 9.5.1 (wrapper), compileSdk 37, targetSdk 36, minSdk 24 (Android 7.0+).

## Build (free, on any PC)

1. Install [Android Studio](https://developer.android.com/studio) Quail 2 or newer. Use its bundled JDK.
2. **File → Open** and select this `html-to-app-android` folder. Wait for Gradle sync, which downloads Gradle, the Android plugin and SDK 37. Accept the SDK licence if prompted.
3. **Build → Generate App Bundles or APKs → Generate APKs**. Alternatively, run `.\gradlew.bat assembleDebug` in Android Studio's Terminal.
4. The APK is written to `app\build\outputs\apk\debug\app-debug.apk`.

## Install and check

Copy the APK to your phone, open it, and allow "Install unknown apps" for that app just this once. Then work through the checks in [../Android-WebView-Guide.txt](../Android-WebView-Guide.txt).

## Move your existing data

1. In the browser version, open **Backups and data** and choose **Download backup**.
2. In the app, open **Backups and data**, choose **Restore a backup**, and pick that file.

## Update the app later

1. Edit the root `index.html`, then copy it over `app/src/main/assets/index.html`.
2. Increase `versionCode` in `app/build.gradle.kts`.
3. Rebuild and install over the existing app. Don't uninstall first: uninstalling deletes the app's data.

Keep the same package name (`com.example.peakmildeffort`) and signing key across updates. A debug key can differ between PCs, so back up your data before building on a different machine.
