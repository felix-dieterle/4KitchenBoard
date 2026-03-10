# 4KitchenBoard – GitHub Copilot Instructions

## Project Overview

4KitchenBoard is a modular Android dashboard app (`com.kitchenboard`) that shows several
functional modules on a shared screen via a `ViewPager2`-based page system with automatic
page rotation. It is designed to run permanently mounted (e.g. on a kitchen wall) in
**landscape orientation**.

Modules: `shopping`, `calendar`, `cooking`, `weather`, `tasks`, `immobilien`,
`notifications`, `update`.

---

## 1. Code Structure

### Package layout
```
com.kitchenboard/
├── MainActivity.java          # Host activity; ViewPager2, dots, notification bell
├── ScreenPagerAdapter.java    # Wires fragments into ViewPager2 pages
├── KitchenBoardApp.java       # Application class; global init
├── calendar/                  # Appointments, reminders, weight chart
├── cooking/                   # Recipe / dish management
├── feedback/                  # In-app feedback & feature requests
├── immobilien/                # Real-estate alert checks (ImmobilienCheckReceiver)
├── notifications/             # AppNotification, NotificationStore (SharedPreferences)
├── shopping/                  # Shopping list (ShoppingFragment, DB)
├── tasks/                     # Task management
├── update/                    # Auto-update pipeline (see section 4)
└── weather/                   # Weather display
```

### Conventions
- One **Fragment** per module; name it `<Module>Fragment.java`.
- Supporting classes (adapters, DB helpers, model POJOs) stay **inside their own
  module package** – never in the root `com.kitchenboard` package unless they are
  truly app-wide (e.g. `KitchenBoardApp`).
- Keep `MainActivity` thin. All business logic belongs in the relevant Fragment or
  helper class.
- Use **SharedPreferences** for lightweight per-module state; always use a dedicated
  preference file name (e.g. `"shopping_prefs"`, `"calendar_prefs"`). Never use the
  default shared preferences for module-specific data.
- SQL databases live in their own `*DatabaseHelper` class inside the module package;
  increment `DB_VERSION` and add a proper `onUpgrade()` migration for every schema change.
- Constants (notification IDs, preference keys, action strings) must be defined as
  `static final` fields at the top of the class that owns them, not scattered as
  magic literals.
- All public methods need a brief Javadoc comment (one line is enough).

---

## 2. UX Guidelines

### General
- **Landscape-first**: every layout must look correct in landscape. The activity is
  locked to `android:screenOrientation="landscape"`, so portrait layouts are never
  needed, but all `ConstraintLayout` constraints must work at typical tablet and
  phone landscape aspect ratios (≈ 16:9 down to ≈ 4:3).
- Use **Material Design** components (`AppCompatActivity`, `MaterialAlertDialogBuilder`,
  `MaterialButton`, `TextInputLayout`, etc.). Avoid raw `android.app.AlertDialog`.
- Touch targets must be **≥ 48 dp × 48 dp** (WCAG / Material guidelines). Pay special
  attention on tablets where fingers are farther apart.
- Font sizes: use `sp` units only. Minimum body text is `14 sp`; labels / captions
  `12 sp`. Do not hard-code pixel sizes.
- Use `dp` for all spacing and dimension values. Never use `px`.
- Prefer **`ConstraintLayout`** for new fragments. Use `LinearLayout` only for simple
  vertical/horizontal stacks that do not need relative positioning.

### Navigation & Interaction
- The main navigation is the `ViewPager2` page switcher. **Do not add a bottom
  navigation bar or drawer** – this breaks the always-on wall-mount use-case.
- Page-header buttons (e.g. rotation toggle `btn_rotation_toggle`) must be small and
  unobtrusive (`alpha ≤ 0.55`). Heavy chrome distracts from content.
- Dialogs should always have exactly **two buttons**: a destructive/cancel action
  (left/neutral) and a confirming action (right/positive).
- Auto-advance rotation: after any user interaction in a fragment, the auto-advance
  timer should be paused for at least one full interval before resuming, so the screen
  does not flip away while someone is actively using a module.

### Notifications & Alerts
- Use the in-app `NotificationStore` for all in-app alerts; do not show a system
  notification for events that only matter inside the app.
- Notification IDs must be unique across the whole app. Reserved ranges:
  - `1000–1999`: calendar / reminder notifications
  - `2001–2002`: auto-update status / install prompt
  - `3000–3999`: immobilien / property alerts
- The notification badge (`tv_notification_badge`) shows counts up to `99`; anything
  higher should display `"99+"`.

---

## 3. Device Support

### Supported devices
| Device class | Example | Screen | Density | Min API |
|---|---|---|---|---|
| Small phones | modern mid-range | 5–6 in, ~360 dp wide | xxhdpi / xxxhdpi | 21 |
| Large phones | flagship | 6–7 in, ~400 dp wide | xxhdpi / xxxhdpi | 21 |
| **Galaxy Tab 10.1** | Samsung GT-P7500/P7510 | **10.1 in, 1280×800 px** (≈ 150 dpi) | **mdpi** | **15** |
| Generic tablets | 7–10 in | 600–800 dp wide | hdpi / xhdpi | 21 |

`minSdk` is set to **15** (Android 4.0.3) specifically to keep the Galaxy Tab 10.1
supported. **Do not raise `minSdk`** without an explicit decision to drop that device.

### Layout qualifiers
- Default `layout/` must work at phone landscape sizes (≥ 360 dp wide, ≈ 16:9).
- Add `layout-sw600dp/` overrides for fragments where a larger tablet canvas benefits
  from a different column/grid arrangement (e.g. showing more items per row).
- **Do not** rely on `layout-large` (deprecated qualifier); use `sw600dp` or `sw720dp`.
- For dimension overrides between phones and tablets, add a `values-sw600dp/dimens.xml`
  with larger text sizes / padding rather than hard-coding them in Java.

### Galaxy Tab 10.1 specific constraints
- Screen is **mdpi** (160 dpi equivalent). Always provide mdpi drawable assets.
- In landscape (the only used orientation): **1280 × 800 px** = **800 dp wide × 500 dp tall**.
  Use these dp values to size and constrain layouts correctly.
- The tablet has **no hardware back button** on some ROM variants. Make sure every
  dialog and panel can be dismissed by an on-screen button or back-press intercepted
  via `onBackPressed()` / `OnBackPressedDispatcher`.
- Avoid `SCHEDULE_EXACT_ALARM` on API < 21 code paths; `AlarmManager.set()` is
  sufficient for the older device.
- The `AutoUpdateScheduler` uses `setInexactRepeating()` which is safe down to API 15.
  Keep it that way.

### Testing on different screen sizes
- Use the Android Emulator AVD for **"Galaxy Tab 10.1" (1280×800, mdpi, API 15)**
  and at least one **phone AVD (e.g. Pixel 5, API 33)** to verify any layout change.
- In layout XML, use `tools:device="id:Galaxy Tab 10.1"` for preview accuracy in
  Android Studio.

---

## 4. Auto-Update Pipeline

The auto-update system is a core feature. **Preserve it on every change.**

### How it works
1. A push to `main` triggers the **`debug-apk.yml`** GitHub Actions workflow.
2. The workflow builds the APK with `BUILD_NUMBER=${{ github.run_number }}`, producing
   a `versionCode` equal to the run number.
3. It publishes a **pre-release** GitHub Release with:
   - Tag: `v{versionName}-{runNumber}` (e.g. `v1.0-42`)
   - Release body containing the literal string **`[auto_update]`**
4. On the device, `AutoUpdateScheduler` fires `AutoUpdateReceiver` every **12 hours**
   via `AlarmManager.setInexactRepeating(INTERVAL_HALF_DAY)`.
5. `UpdateChecker` fetches the GitHub Releases API, compares `versionCode` (= build
   number in the tag), and if a newer release has `[auto_update]` in its body it
   downloads the APK silently via `DownloadManager`.
6. When the download completes, `AutoUpdateReceiver` prompts the user to install.

### Rules – never break these
- **`[auto_update]`** must remain in the release body of every auto-deployable release.
  Do not rename or relocate this marker.
- The **tag format `v{versionName}-{buildNumber}`** must be preserved. `UpdateChecker`
  parses the build number from this tag by splitting on `"-"` and taking the last
  segment.
- `versionCode` must continue to come from `BUILD_NUMBER` env var (falls back to `1`
  for local builds). Do not hard-code it.
- `versionName` is in `app/build.gradle` and is extracted by the workflow with a
  regex. Keep it as a plain string literal on its own line:
  ```groovy
  versionName "1.0"
  ```
- The GitHub Actions workflow file is `debug-apk.yml`. It must not be renamed.
- `AutoUpdateScheduler` must be called from `MainActivity.onCreate()` so updates are
  always scheduled after a device reboot.
- Update-related logs go through `UpdateLogger`. Never use bare `Log.e/w` for update
  errors – use `UpdateLogger.logError()` so problems are visible in the device log
  file and reportable.

### Version bumping
When releasing a new feature version:
1. Increment `versionName` in `app/build.gradle`.
2. Push to `main` – the workflow creates a new release automatically.
3. Devices will pick up the update within 12 hours.

---

## 5. Security & Permissions

- `GITHUB_ISSUE_TOKEN` is injected via GitHub Actions secret and exposed as a
  `BuildConfig` field. **Never commit a real token value** into source code.
- `REQUEST_INSTALL_PACKAGES` is required for the silent APK install flow. Keep it.
- `CAMERA` is declared `android:required="false"` – handle gracefully on devices
  without a camera by checking `PackageManager.hasSystemFeature(FEATURE_CAMERA)`.
- Do not add new `<uses-permission>` entries without reviewing the impact on the
  Galaxy Tab 10.1 (some permissions were introduced after API 15).

---

## 6. Build & CI

```bash
# Local debug build
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Check a specific module
./gradlew :app:compileDebugJava
```

- Java source/target compatibility is **1.8**. Do not use language features beyond
  Java 8 (no records, sealed classes, etc.).
- MultiDex is enabled. Keep it enabled; removing it will break API 15–20 devices.
- Kotlin is **excluded** from the dependency resolution (see `configurations.all`
  exclusions). Add only Java or pure-Java Android libraries as dependencies.
