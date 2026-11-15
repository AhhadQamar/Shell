# Shell — minimal Android launcher (Jetpack Compose)

Home
- Wallpaper shows through; accent is picked from it (or choose a preset in Shell settings).
- Date, battery %, battery line, big clock, next alarm, dock (up to 5 pinned apps).
- Swipe up or tap the bar: drawer. Swipe down: notification shade. Long-press empty space: wallpaper / settings menu.

Drawer
- A-Z sections with a draggable fast-scroll rail; most-used apps row at the top.
- Search: substring, word-start and initials ("gm" finds Google Maps). Enter launches the top match.
- Type a sum ("12*(3+4)") to get an answer; tap to copy. No match? Search the web.
- Long-press any app: pin/unpin, hide/unhide, app info, uninstall. Hidden apps are still found by search.

Build: open this folder in Android Studio (it creates the Gradle wrapper), Run, press Home, choose "Shell".
Notes: swipe-down shade uses a hidden API and quietly does nothing if your OS blocks it.

Get an APK without Android Studio: push this folder to a GitHub repo. The included workflow
(.github/workflows/build.yml) builds it; download "Shell-debug-apk" from the run's Artifacts.
