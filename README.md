# Shell

A small, minimal Android launcher built with Kotlin and Jetpack Compose.

Shell keeps the home screen calm and useful: a clock, battery status, pinned apps, and a lightweight app drawer with fast search. It is designed to stay out of the way while still providing practical launcher features.

## Features

- Minimal home screen with time, date, battery, alarm, and remaining-day indicators
- App drawer with alphabetical browsing, app icons, frequent apps, and pinned apps
- Fast fuzzy app search, including word and initial matching
- Calculator expressions directly in the search field
- Search shortcuts such as `y cats` for YouTube or `gh issues` for GitHub
- Quick links to common Android settings screens
- Optional instant launch when a query has exactly one app match
- Hide apps from the drawer, with configurable search behavior
- Long-press app menus for shortcuts, app info, hiding, uninstalling, and pinning
- Custom accent colors, wallpaper-aware colors, optional shell background, and icon visibility controls
- Optional accessibility service for double-tap-to-lock and reliable notification shade access

## Install

Download the latest APK from the [Releases](https://github.com/AhhadQamar/Shell/releases) page and install it on an Android device running Android 8.1 (API 27) or newer.

After installation:

1. Open Shell.
2. Choose Shell when Android asks which home app to use.
3. Long-press an empty area of the home screen to open Shell settings.

## Using Shell

- **Swipe up** or tap **APPS** to open the drawer.
- **Swipe down** from the home screen to open the notification shade.
- **Tap an app** to launch it.
- **Long-press an app** to pin it or open its app actions.
- **Long-press empty home space** to open wallpaper, Shell settings, and system settings actions.
- **Double-tap the home screen** to lock the device when Shell's optional accessibility service is enabled.

In the drawer, enter an app name, a calculation such as `2^8`, a settings query such as `wifi`, or a bang search such as `g android launcher`. A normal unmatched query can be sent to the configured web search engine.

## Build from source

Requirements:

- JDK 17
- Android SDK with platform 35
- Gradle 8.9 or newer compatible with the project

```bash
git clone https://github.com/AhhadQamar/Shell.git
cd Shell
gradle assembleRelease
```
