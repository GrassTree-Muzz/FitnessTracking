# Ol'Man Muz – Android app

An Android app wrapping the tracker in the repository root's `index.html`. Your data lives in the app's private storage (the page's `localStorage`). The app only goes online for the Home page weather and the activity route map tiles. Its permissions are internet and approximate location (both for the weather) and read-only Health Connect for your Garmin data.

Versions: AGP 9.3.3, Gradle 9.5.1 (wrapper), compileSdk 37, targetSdk 36, minSdk 26 (Android 8.0+). Garmin data needs Android 14 or newer.

## Build (free, on any PC)

1. Install [Android Studio](https://developer.android.com/studio) Quail 2 or newer. Use its bundled JDK.
2. **File → Open** and select this `html-to-app-android` folder. Wait for Gradle sync, which downloads Gradle, the Android plugin and SDK 37. Accept the SDK licence if prompted.
3. **Build → Generate App Bundles or APKs → Generate APKs**. Alternatively, run `.\gradlew.bat assembleDebug` in Android Studio's Terminal.
4. The APK is written to `app\build\outputs\apk\debug\app-debug.apk`.

## Install and check

Copy the APK to your phone, open it, and allow "Install unknown apps" for that app just this once. Then work through the checks in [../Android-WebView-Guide.txt](../Android-WebView-Guide.txt).

## Move your existing data

1. In the browser version, open **Backups and data** and choose **Download backup**.
2. In the app, open **Backups and data** (the database icon at the top right of the cover photo), choose **Restore a backup**, and pick that file.

## Garmin data (Health Connect)

Garmin Connect copies your activities (runs, paddles, tennis, rides, swims and the rest), sleep, heart rate, steps and weight into Android Health Connect after every watch sync. The app reads them from there. Nothing is uploaded, and no Garmin account or server is involved.

1. On the phone (Android 14+), open **Garmin Connect** and sync the watch. Then open **Settings**, search for **Health Connect**, open **App permissions → Garmin Connect** and turn on **Allow all**.
2. Open **Ol'Man Muz** and go to the **Health** tab. Tap **Connect Health Connect**, choose **Allow all**, and if Health Connect asks about past data, allow it so the app can read more than the last 30 days.
3. Garmin runs appear in **Runs** with a Garmin badge; every other Garmin activity appears in **Activities**, with a filter by type, weekly hours per sport and a year-in-sport summary. Tap the chart icon on any activity for heart-rate zones, plus pace or speed and splits where it has distance. Sleep, resting heart rate, steps and trends are in **Health**, along with a heart-rate timeline in 5-minute detail that you can swipe back through the last 90 days.
4. After each session, let the watch sync to Garmin Connect, then open the app. It refreshes by itself when opened (only new or edited activities are re-read), or tap the refresh arrow on the Garmin pill at the top right of the cover photo to re-read everything.

What Garmin doesn't share with Health Connect: stress, Body Battery, HRV, VO2 max, SpO2, training status, or its own resting heart rate. GPS routes appear only if Garmin Connect writes them (see Route map). The app estimates resting heart rate as your lowest 30-minute average while asleep.

To change access later: **Settings → Health Connect → App permissions → Ol'Man Muz**. The old live cloud sync is archived in [../archive/garmin-cloud-sync/](../archive/garmin-cloud-sync/README.md).

## Route map

Activity details show the GPS route on a map when Health Connect holds one for that activity. Health Connect asks for consent per activity, so the first time you open one, tap **Show route** and allow it.

- Map tiles are Thunderforest Outdoors, drawn with Leaflet 1.9.4 (bundled in `app/src/main/assets/leaflet/`, loaded only in the app). Create a free key at [thunderforest.com](https://www.thunderforest.com/) and add this line to `local.properties` in this folder (it's gitignored):

	```
	thunderforestKey=YOUR_KEY
	```

- Without a key the route still draws, on a blank background.
- The app fetches tiles itself and caches up to 50 MB in its cache folder, so the key never appears in page code or the cache. Tiles are only requested for the area you view.
- Route points stay on the phone. Thunderforest sees tile numbers (roughly the area you view) and the phone's IP address.

## Home page and weather

The app opens with a short hiker splash (tap to skip), then **Home**: a 7-day forecast, the hourly forecast for the chosen day, and tiles for last night's sleep, your last activity, today's steps and how many days you've been active this week. Tap a tile to open it: the last activity opens its details, and the week tile opens a month calendar that compares this month with last month up to the same date.

- Tap the place name to switch between **Current location**, your saved places (Denmark WA and Perth WA to start) and **Add a place**. The × removes a saved place.
- Current location asks for approximate location once. It's used only to fetch the forecast and name the nearest town, and only while Current location is selected.
- Forecasts come from [Open-Meteo](https://open-meteo.com/) (free, no account). Town names for your current location come from BigDataCloud's free client-side lookup. The last forecast is kept for offline use and refreshed after 30 minutes.

## Update the app later

1. Edit the root `index.html`, then copy it and the root JPG images into `app/src/main/assets/`. From the repository root:

	```powershell
	Copy-Item index.html html-to-app-android\app\src\main\assets\index.html
	Copy-Item *.JPG html-to-app-android\app\src\main\assets\
	```

	Android Studio packages files from this assets folder with the page.
2. Increase `versionCode` in `app/build.gradle.kts`.
3. Rebuild and install over the existing app. Don't uninstall first: uninstalling deletes the app's data.

Keep the same package name (`com.example.peakmildeffort`) and signing key across updates. A debug key can differ between PCs, so back up your data before building on a different machine.
