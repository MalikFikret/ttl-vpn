# TTL VPN · Architecture

How the app is put together, the rules that keep it working, and why things are the way they
are. For what the app does and how to install it, see the [README](../README.md).

- [Overview](#overview)
- [Data flow](#data-flow)
- [Invariants and design decisions](#invariants-and-design-decisions)
- [Settings and storage](#settings-and-storage)
- [Localization](#localization)
- [Icons](#icons)
- [Open source licenses](#open-source-licenses)
- [Building](#building)
- [Conventions](#conventions)

## Overview

TTL VPN has two parts:

- **`engine/`**: a Go module (`github.com/MalikFikret/ttl-vpn/engine`) that wraps
  [tun2socks](https://github.com/xjasonlyu/tun2socks), imported as a regular module rather than
  vendored. Its `ttlvpn` package is compiled with gomobile into an Android library (AAR) and
  exposes exactly two functions: `Start(fd, mtu, ttl)` and `Stop()`. That API is deliberately
  fixed.
- **`android/`**: a Kotlin / Jetpack Compose app (`com.malikfikret.ttlvpn`, minSdk 24,
  targetSdk 37) that consumes the AAR.

## Data flow

1. **Consent.** `MainActivity` (or the tile or widget, through `VpnController`) checks
   `VpnService.prepare()`. If Android hasn't approved the app as a VPN yet, only an activity can
   show the consent dialog, so the tile and widget open the app instead.
2. **Service.** `TtlVpnService` starts as a foreground service (type `specialUse`) and builds
   the TUN interface with `VpnService.Builder`: address `10.111.0.2/32`, route `0.0.0.0/0`, DNS
   `1.1.1.1` and `8.8.8.8`, MTU 1500. IPv4 only (see below).
3. **Handoff.** `tun.detachFd()` gives up the file descriptor, and
   `Ttlvpn.start(fd, mtu, ttl)` (the gomobile binding, Kotlin class `ttlvpn.Ttlvpn`) passes it
   to the engine.
4. **Engine.** tun2socks runs in `direct://` mode: it terminates every TCP/UDP flow arriving on
   the TUN and re-originates it from real sockets. A registered socket option (`withTTL`) sets
   `IP_TTL` (and `IPV6_UNICAST_HOPS` on IPv6 sockets) on each of them.
5. **Exit.** Those sockets belong to the app, which is excluded from its own VPN, so they leave
   through the normal network with the chosen TTL.

## Invariants and design decisions

These are easy to break by accident. Each one exists for a concrete reason.

### Engine and file descriptor

- **fd ownership.** After `detachFd()`, the Go engine owns the TUN file descriptor and closes it
  in `Stop()`. If `Ttlvpn.start` throws, the engine never took it, so Kotlin must close it
  itself (`ParcelFileDescriptor.adoptFd(fd).close()`). Anything else either leaks the
  descriptor (the VPN stays half-up) or closes it twice.
- **No routing loop.** The app excludes itself with `addDisallowedApplication(packageName)`.
  Without that, the engine's own outbound sockets would be captured by the TUN again.
- **IPv6 is intentionally not configured.** The TUN gets no IPv6 address, route or DNS server,
  and `allowFamily(AF_INET6)` is never called, so Android blocks all IPv6 while connected.
  Advertising IPv6 made apps prefer it, but on the IPv4-only hotspot the engine couldn't reach
  IPv6 destinations and reset those connections (Chrome showed "This site can't be reached"
  instead of falling back). Blocking it makes apps use IPv4, and no traffic can leak out with
  the default hop limit. Don't add any IPv6 configuration unless the engine can reach IPv6
  upstream.
- **Validate before `engine.Start()`.** tun2socks calls `log.Fatalf` on a bad configuration,
  which kills the whole app process. `ttlvpn.Start` therefore validates the fd, MTU and TTL
  first and returns an error instead.
- **Register the socket option after `engine.Start()`.** Startup calls `dialer.Reset()`, which
  silently wipes socket options registered earlier. `Stop()` calls `dialer.Reset()` too.
- **Address family from the socket.** `setTTL` asks the socket for its family (`SO_DOMAIN`)
  rather than guessing from the network string. On IPv6 sockets it also sets `IP_TTL` on a
  best-effort basis, for IPv4-mapped traffic.
- **The engine is a process-wide singleton** guarded by a mutex (`running` flag); calling
  `Start` while it runs returns an error.

### Threading in the service

- **One engine thread.** Every start, stop, revoke and destroy request runs on a single
  process-wide thread (`ttlvpn-engine`), strictly in submission order. It's process-wide rather
  than per service instance, so a new instance's start can never overtake an old instance's
  final stop.
- **Latest request wins.** Each request bumps a counter on the main thread. A job whose request
  is no longer the latest doesn't publish state or leave the foreground: the newer request's job
  runs after it and owns the outcome. This covers double taps, a Stop during a start, and the
  service being destroyed mid-start.
- **The start job never suspends.** Once it begins, it runs to completion, so cancellation can
  never land between `detachFd()` and `Ttlvpn.start()`. That's also why the TTL setting is read
  with a *blocking* read (plus a 2-second timeout so a stuck DataStore can't hang Stop): a
  suspending read would free the engine thread mid-start and let a queued Stop run in between.
- **`startForeground()` stays synchronous** in `onStartCommand`, before any engine work, so the
  foreground-service deadline never depends on I/O. If Android refuses the foreground start
  (possible from the background on Android 12+), a failure job goes through the same engine
  thread and publishes an error instead of crashing.
- **Leaving the foreground happens on the main thread**, where requests are counted, so the
  "is this still the latest request" check can't race with a Start arriving at that moment.

### On/off requests and published state

- **All on/off requests go through `VpnController`**: the app's toggle, the Quick Settings tile
  and the widget. It only sends the service's `ACTION_START` / `ACTION_STOP` intents and returns
  a `StartResult` (`Started`, `NeedsConsent`, `NotAllowed`). Callers decide what to do with it:
  the app shows the consent dialog; the tile and widget open the app. Start/stop logic lives
  only on the service's engine thread.
- **State is published only through `TtlVpnService.publish()`**, which updates
  `VpnStateRepository` (a `StateFlow` the UI and tile observe) and redraws the home screen
  widgets (`TtlWidgetProvider.updateAll`). `TtlVpnApp.onCreate` redraws the widgets once per
  process start, so a widget left stale by a force stop corrects itself the next time anything of
  the app runs. Nothing polls (`updatePeriodMillis="0"`).
- **Widget taps** go to the non-exported `WidgetToggleReceiver` through an explicit, immutable
  PendingIntent. The widget provider has to be exported to receive system updates, so it must
  never handle the toggle itself, or any app could turn the VPN on or off. When the VPN
  permission is known to be missing, the widget's tap opens the app directly instead, because
  starting an activity from a broadcast receiver is restricted on Android 10+.
- **Widget text.** Below 100 dp the widget shows only the badge (`widget_small`); from 100 dp
  the 2×1 layout (`widget_wide`). The 2×1 has a 12 sp caption on top ("TTL 63" while
  connected, "TTL VPN" otherwise) and the state alone below it in 14 sp bold ("Connected",
  "Off", "Connecting…", "Not connected"), each on one line. Keeping the TTL out of the state
  line is what lets the state fit a 2×1 cell; the screen-reader description still says the full
  "Connected · TTL 63".
- **Lock screen.** The tile allows turning the VPN on while the phone is locked, but turning it
  off requires unlocking: stopping it silently would bill traffic to the wrong package.

### Other security-relevant choices

- All PendingIntents are `FLAG_IMMUTABLE`.
- The VPN service is protected by `BIND_VPN_SERVICE` and the tile service by
  `BIND_QUICK_SETTINGS_TILE`; both permissions are held only by the system.
- Backups and device-to-device transfers are disabled (`allowBackup="false"` plus data
  extraction rules that exclude everything).
- If code shrinking (R8) is ever enabled, the gomobile classes (`go.**`, `ttlvpn.**`) must be
  kept; the native library looks them up by name. The commented rules are in
  `android/app/src/main/keepRules/rules.keep`.

## Settings and storage

- MTU (1500), the TUN address and the DNS servers are constants in `TtlVpnService`.
- User settings live in one Preferences DataStore (`AppSettings`): the TTL (1–255, default 63;
  out-of-range values fall back to 63), the theme, the language (Android 7–12 only), and whether
  the Quick Settings tile has been added (only used to hide the *Add tile* button). A corrupt
  file is reset to defaults instead of crashing.
- **Theme.** On Android 12+ the choice is also handed to
  `UiModeManager.setApplicationNightMode()`, so the system applies it before the app starts and
  there's no wrong-theme flash at launch. On Android 7–11 the setting is read before the first
  frame; the system's launch preview window can still show the system theme for a moment.

## Localization

- **Languages:** English (default), Turkish (`values-tr`) and Arabic (`values-ar`, Modern
  Standard Arabic). "TTL", "VPN" and "TTL VPN" are never translated. Keep
  `res/xml/locales_config.xml` and the `AppLanguage` enum in sync with the `values-*` folders.
- **Per-app language without AppCompat** (`AppLanguages`). On Android 13+ it uses the system's
  `LocaleManager`, which applies the language to every component of the app and shows it in the
  system's per-app language settings. On Android 7–12 the choice is stored in DataStore and
  `MainActivity.attachBaseContext` wraps the context. Any code outside an activity that shows
  text (the service, the tile, the widget) must take its strings from
  `AppLanguages.localized(context)`, never from its own `getString`, or it shows the wrong
  language on Android 7–12.
- **Latin digits everywhere, including Arabic.** Format numbers only with `Formatting.kt`
  (`formatTtl`, `formatDataSize`, `formatDuration`, `formatVersion`) and pass the result to a
  `%s` placeholder. Never use `%d` in string resources or `Formatter.formatShortFileSize`: both
  use the locale's digits, which are Arabic-Indic in Arabic. The helpers also wrap every value in
  a Unicode LTR isolate (U+2066…U+2069), so it renders as one left-to-right unit ("7.5 MB", not
  "MB 7.5") inside right-to-left text. Never build a displayed number any other way.
- **Right-to-left** layout is automatic in Compose. Icons that have a direction
  (`ic_arrow_back`, `ic_ttl`) set `android:autoMirrored`; the stamp, the digits and the power
  glyph must never mirror. The widget sets its layout direction from the app's language,
  because the launcher would otherwise use the phone's.
- **Arabic strings must start with an Arabic word**, or the paragraph direction flips to
  left-to-right. ("قيمة TTL الصادرة", not "TTL الصادر".)
- App Bundle language splits are disabled (`bundle.language.enableSplit = false`), so every
  language ships in every install and the in-app picker always works.

## Icons

- **Launcher** (`mipmap-anydpi-v26`): an adaptive icon. `ic_launcher_foreground` is the round
  "63" stamp with traffic passing through it (faded dots in, a mint `#7DE8DC` line out);
  `ic_launcher_background` is the teal radial gradient; `ic_launcher_monochrome` (themed icons,
  Android 13+) is the stamp alone. Android 7.x uses the pre-masked vectors in `mipmap/`
  (rounded square and round). There are no bitmap icons.
- **`ic_stamp`** (24 dp, stamp only, no inner ring) is the small glyph everywhere else: the
  notification icon, the Quick Settings tile, the top bar logo and the Settings badges. The
  widget uses per-state colored copies (`ic_widget_stamp_*`).
- **`ic_power`** is used only on the home screen toggle button.
- **No shield imagery.** The app labels traffic with a TTL; it doesn't encrypt or hide
  anything, and the icons shouldn't suggest otherwise.

## Open source licenses

- **Settings → About → Open source licenses** shows the app's own license (GPL-3.0, from the
  repository's `LICENSE`) and every bundled component. The component list
  (`ui/OpenSourceLicenses.kt`) and the verbatim license texts (`res/raw/license_*.txt`) are
  **generated; never edit them by hand.**
- **Re-run [`tools/update_licenses.py`](../tools/update_licenses.py) from the repository root
  after any dependency change**: a Go module bump or rebuilt engine, or any added or updated
  Gradle dependency. It needs Go on `PATH` and a built `engine/build/ttlvpn.aar`.
  - **Engine:** it reads the Go modules actually linked into the AAR (`go version -m` on
    `libgojni.so`) and copies each one's license from the Go module cache, plus the Go standard
    library's LICENSE and PATENTS.
  - **Android:** it checks the release runtime classpath against its `ANDROID` table and fails
    if a library group isn't covered. Add the new library there (name, SPDX id, copyright) and
    re-run.
  - It fails on any license text it can't classify instead of guessing. Every component so far
    is MIT, BSD or Apache-2.0 (tun2socks is MIT), all compatible with the app's GPL-3.0.

## Building

The README has the [prerequisites and troubleshooting](../README.md#building-from-source). This
section covers how the build is wired.

- The app links the prebuilt AAR at `engine/build/ttlvpn.aar`
  (`implementation(files("../../engine/build/ttlvpn.aar"))`). `engine/build/` is gitignored, so
  **build the engine first**, and rebuild it after any Go change.
- **[`build.bat`](../build.bat)** does both in one step and runs from any directory:

  ```cmd
  build.bat           :: Go engine, then the Android debug APK
  build.bat install   :: same, then adb install -r on the connected phone
  build.bat app       :: skip the engine, reuse engine\build\ttlvpn.aar (fails if missing)
  build.bat help
  ```

  It checks its prerequisites first: Go; gomobile and gobind (skipped for `app`); `JAVA_HOME`
  with `bin\javac.exe` (Gradle uses `JAVA_HOME`, so whatever `java` comes first on `PATH`
  doesn't matter and isn't checked); `ANDROID_HOME` (falling back to `ANDROID_SDK_ROOT`); and
  the NDK (`ANDROID_NDK_HOME`, otherwise the alphabetically last folder in
  `%ANDROID_HOME%\ndk`). For `install` it finds adb (SDK `platform-tools` first, then `PATH`)
  and requires exactly one authorized device, or `ANDROID_SERIAL`, **before** building. Every
  step checks `errorlevel`, and the script ends by printing the APK path.
- **Editing `build.bat`:** keep it CRLF; call `gradlew.bat` by its full path (cmd may be
  configured not to search the current directory); and keep `( ) & | < >` out of its messages
  and out of any parenthesized block that expands a path, or cmd's parser breaks on paths like
  `Program Files (x86)`.
- **The engine build command** (run in CMD from `engine\`; create `build\` first):

  ```cmd
  gomobile bind -target=android/arm64 -androidapi 24 -trimpath -ldflags="-s -w" -o build\ttlvpn.aar ./ttlvpn
  ```

  - `-trimpath` stores module-relative source paths (`github.com/...@v1.2.3/file.go`) instead of
    the build machine's absolute paths, so no local path or user name ships in the APK and the
    build doesn't depend on where the repository lives.
  - `-ldflags="-s -w"` strips the ELF symbol table and DWARF debug info from `libgojni.so`,
    roughly halving it. Go panic traces still show function names and file:line (they come from
    the runtime's `pclntab`), the JNI entry points stay exported, and `go version -m` (used by
    the license tool) still works. Drop the flag only to attach a native debugger or profiler.
  - Only arm64 is built, so the AAR won't load on x86_64 emulators; add `android/amd64` to
    `-target` if one is needed.
  - `tools.go` pins `golang.org/x/mobile/bind`, so gomobile keeps working after `go mod tidy`.
- **Android** (from `android/`; `gradlew.bat` on Windows):

  ```bash
  ./gradlew assembleDebug
  ./gradlew installDebug
  ./gradlew test                      # JVM unit tests
  ./gradlew connectedAndroidTest      # instrumented tests (device)
  ./gradlew test --tests "com.malikfikret.ttlvpn.ExampleUnitTest"
  ```

- **Go** (from `engine/`): `go build ./...` and `go vet ./...`. On Windows, `ttl_others.go`
  (`//go:build !linux`) is a stub so the package compiles; the real TTL code in `ttl_linux.go`
  only compiles for Linux/Android. Check it from CMD with
  `set GOOS=linux&& set GOARCH=arm64&& go build ./...`, then reset with `set GOOS=` and
  `set GOARCH=` (no space before `&&`, or CMD includes it in the value).

## Conventions

- `.gitattributes` enforces LF everywhere except `*.bat` / `*.cmd` (CRLF).
- Code comments explain *why* (platform quirks, ordering constraints), not *what*.
- Code comments are in English.
