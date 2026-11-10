# Fervo TV

## Import provider catalogs {#ingestion}

tech: `Repository.kt` coordinates the M3U and Xtream implementations under `core/source` and the shared HTTP client under `core/net`.
needs: [persistence]
links: [experience, playback, media-lab]
files: [core/src/main/java/app/iptvplayer/core/Repository.kt, core/src/main/java/app/iptvplayer/core/source/**, core/src/main/java/app/iptvplayer/core/net/**, core/src/test/java/app/iptvplayer/core/source/**, core/src/test/java/app/iptvplayer/core/net/**]

- [x] Parse live and VOD entries from M3U and Xtream providers {#ingestion-parse-providers}
  tech: `core/src/main/java/app/iptvplayer/core/source/m3u/M3uParser.kt`; `core/src/main/java/app/iptvplayer/core/source/xtream/Xtream.kt`; `core/src/main/java/app/iptvplayer/core/source/xtream/XtreamLibrary.kt`
- [x] Cache provider responses and reload them through one repository API {#ingestion-cache-sources}
  tech: `core/src/main/java/app/iptvplayer/core/source/PlaylistCache.kt`; `core/src/main/java/app/iptvplayer/core/Repository.kt` (`load`, `recognize`, `episodes`)
- [ ] Combine multiple provider accounts in one library {#ingestion-multiple-providers}
  from: roadmap
  tech: intent is `SRC-013`–`SRC-015` in `docs/spec/90-roadmap.md`; current `Repository.kt` exposes one active `Source`, not a combined source set

## Preserve catalogs and viewing state {#persistence}

tech: Room entities, DAOs, migrations and exported schemas live under `core/db` and `core/schemas`.
links: [ingestion, experience, configuration]
files: [core/src/main/java/app/iptvplayer/core/db/**, core/src/main/java/app/iptvplayer/core/model/**, core/src/test/java/app/iptvplayer/core/db/**, core/schemas/**]

- [x] Store channels, EPG, films and episodes with paging and full-text search {#persistence-catalogs}
  tech: `core/src/main/java/app/iptvplayer/core/db/Db.kt`; `PlaylistStore.kt`; `EpgStore.kt`; `LibraryStore.kt`; `core/schemas/app.iptvplayer.core.db.AppDb/7.json`
- [x] Preserve favorites, edits, collections, history and resume positions {#persistence-user-state}
  tech: `core/src/main/java/app/iptvplayer/core/db/UserStore.kt`; `LibraryUser.kt`; `core/src/test/java/app/iptvplayer/core/db/UserStoreTest.kt`; `LibraryStoreTest.kt`
- [x] Prove categories return exactly to the playlist {#experience-live-reset}
  by: codex
  from: roadmap
  tech: `core/src/test/java/app/iptvplayer/core/db/UserStoreTest.kt` covers LIVE-015 reset scope and preserved user data
- [x] Keep favorite channels available across source changes {#persistence-live-favorites}
  by: codex
  from: roadmap
  tech: stable channel keys and stored source names resolve the first current-source match without deleting unmatched favorites

## Navigate live, guide and library content {#experience}

tech: the adaptive Compose shell in `app-tv` consumes home, search, guide and logo projections from `core`.
needs: [ingestion, persistence]
links: [playback, configuration]
files: [core/src/main/java/app/iptvplayer/core/epg/**, core/src/main/java/app/iptvplayer/core/home/**, core/src/main/java/app/iptvplayer/core/search/**, core/src/main/java/app/iptvplayer/core/logos/**, app-tv/src/main/java/app/iptvplayer/MainActivity.kt, app-tv/src/main/java/app/iptvplayer/Input.kt, app-tv/src/main/java/app/iptvplayer/ui/HomeScreen.kt, app-tv/src/main/java/app/iptvplayer/ui/Live*.kt, app-tv/src/main/java/app/iptvplayer/ui/GuideGrid.kt, app-tv/src/main/java/app/iptvplayer/ui/LibraryScreen.kt, app-tv/src/main/java/app/iptvplayer/ui/SearchScreen.kt, app-tv/src/main/java/app/iptvplayer/ui/MoreScreen.kt, app-tv/src/main/java/app/iptvplayer/ui/VodPages.kt, app-tv/src/main/java/app/iptvplayer/ui/VodParts.kt, app-tv/src/main/java/app/iptvplayer/ui/Sections.kt, app-tv/src/main/java/app/iptvplayer/ui/ChannelLogo.kt, app-tv/src/main/res/**, app-tv/src/sharedUiTest/**, app-tv/src/testDebug/**, app-tv/src/androidTest/**]

- [x] Route home, live TV, guide, VOD, search, favorites and history in one shell {#experience-route-sections}
  tech: `app-tv/src/main/java/app/iptvplayer/MainActivity.kt`; `ui/Sections.kt`; `ui/HomeScreen.kt`; `ui/LiveScreen.kt`; `ui/LibraryScreen.kt`; `ui/SearchScreen.kt`; `ui/MoreScreen.kt`
- [x] Render remote-aware lists, grids, guide navigation and adaptive layouts {#experience-adaptive-navigation}
  tech: `app-tv/src/main/java/app/iptvplayer/Input.kt`; `ui/LiveParts.kt`; `ui/GuideGrid.kt`; `app-tv/src/testDebug/java/app/iptvplayer/LiveNavigationTest.kt`
- [x] Open grouped channel actions from remote and touch {#experience-channel-actions}
  by: codex
  tech: context menu entry points, conditional actions and focus restoration in `ui/Live*.kt`, with targeted UI tests
- [x] Show and toggle favorite channels across live surfaces {#experience-live-favorites}
  by: codex
  from: roadmap
  tech: live rows, tiles, guide, search and player surfaces expose the favorite toggle and marker; `LiveNavigationTest.kt` covers touch and TV remote paths
- [x] Keep live preview delayed and reuse its stream in full screen {#experience-live-preview}
  by: codex
  from: roadmap
  tech: `ui/LiveScreen.kt`; `ui/LiveParts.kt`; `core/Defaults.kt`; `app-tv/src/testDebug/java/app/iptvplayer/LiveNavigationTest.kt`
- [~] Automate end-to-end touch and TV-remote interaction routes {#experience-device-ui-matrix}
  by: codex
  from: roadmap
  tech: `NavigationUiTest.kt`, `LiveGuideUiTest.kt`, `LibrarySearchHistoryUiTest.kt` and `SettingsUiTest.kt` compile independent touch/remote routes; emulator execution remains pending
- [x] Isolate device UI tests in a dedicated application variant {#experience-ui-test-variant}
  by: codex
  tech: `app-tv/build.gradle.kts`; `UiTestPackage.kt`; `UiHarnessTest.kt`; assembled packages `io.github.divarion_d.fervotv.uitest` and `.uitest.test`
- [x] Seed repeatable synthetic catalog and playback data {#experience-ui-test-fixtures}
  by: codex
  tech: `UiFixtures.kt` uses `PlaylistStore`, `LibraryStore` and `LibraryUser`; `UiFixturesTest.kt` proves package/URL rejection and repeatable live/VOD/episode/progress state
- [~] Drive core screen routes with real touch and remote events {#experience-ui-test-routes}
  by: codex
  from: roadmap
  tech: `NavigationUiTest.kt` contains touch `Live → More → Live` and DPAD focus movement between seeded channels; `make test-ui-build` compiles both routes, while device execution remains pending

## Play streams across supported formats {#playback}

tech: `PlaybackEngine` and `PlayerSession` isolate the Compose controls from the Media3 `ExoEngine` and its bundled FFmpeg audio renderer.
needs: [ingestion, persistence]
links: [experience, media-lab]
files: [core/src/main/java/app/iptvplayer/core/player/**, core/src/main/java/app/iptvplayer/core/input/**, core/src/test/java/app/iptvplayer/core/player/**, app-tv/src/main/java/app/iptvplayer/ui/NowPlaying.kt, app-tv/src/main/java/app/iptvplayer/ui/PlayerScreen.kt, app-tv/src/main/java/app/iptvplayer/ui/PlayerTools.kt, app-tv/src/main/java/app/iptvplayer/ui/SubtitleOverlay.kt, app-tv/src/main/java/app/iptvplayer/ui/VideoPanels.kt, app-tv/src/main/java/app/iptvplayer/ui/VodPlayer.kt, third_party/media3-ffmpeg/**, tools/build-media3-ffmpeg.sh]

- [x] Own playback lifecycle, retries, recovery and remembered stream format {#playback-session}
  tech: `core/src/main/java/app/iptvplayer/core/player/Playback.kt`; `ExoEngine.kt`; `PlayerMemory.kt`; `core/src/test/java/app/iptvplayer/core/player/PlayerSessionTest.kt`; `ExoRecoveryTest.kt`
- [x] Control tracks, subtitles, scale, speed, volume, delay and stream diagnostics {#playback-controls}
  tech: `app-tv/src/main/java/app/iptvplayer/ui/PlayerTools.kt`; `VodPlayer.kt`; `core/src/main/java/app/iptvplayer/core/player/AudioDelayProcessor.kt`; `AudioEffectsProcessor.kt`; `VideoDetails.kt`
- [x] Switch live channels from the transport buttons instead of seeking {#playback-live-transport}
  tech: `PlayerTools.kt`; `PlayerScreen.kt`; `NavigationUiTest.kt`; `make test-ui-build`; `make test-ui-host`
- [x] Decode unsupported audio with the bundled Media3 FFmpeg extension {#playback-ffmpeg-audio}
  tech: `third_party/media3-ffmpeg/media3-decoder-ffmpeg-1.5.1.aar`; `third_party/media3-ffmpeg/README.md`; `app-tv/build.gradle.kts`; `tools/build-media3-ffmpeg.sh`
- [ ] Add and select a second playback backend {#playback-alternative-engine}
  from: roadmap
  tech: `PLR-017` and `PLR-018` remain open in `docs/spec/90-roadmap.md`; `Playback.kt` names libmpv only as a possible backend and no implementation exists

## Configure, back up and update installations {#configuration}

tech: settings are persisted in the core model, serialized by `SettingsFile`, and presented by the setup/settings Compose screens.
needs: [persistence]
links: [experience, delivery]
files: [core/src/main/java/app/iptvplayer/core/Defaults.kt, core/src/main/java/app/iptvplayer/core/Qr.kt, core/src/main/java/app/iptvplayer/core/backup/**, core/src/main/java/app/iptvplayer/core/update/**, core/src/test/java/app/iptvplayer/core/backup/**, core/src/test/java/app/iptvplayer/core/update/**, app-tv/src/main/java/app/iptvplayer/Language.kt, app-tv/src/main/java/app/iptvplayer/ui/SettingsFile.kt, app-tv/src/main/java/app/iptvplayer/ui/SettingsScreen.kt, app-tv/src/main/java/app/iptvplayer/ui/SetupScreen.kt]

- [x] Persist settings and move them between devices in a validated backup file {#configuration-backup}
  tech: `core/src/main/java/app/iptvplayer/core/backup/SettingsFile.kt`; `Repository.kt` (`backup`, `saveSettingsFile`, `restore`); `core/src/test/java/app/iptvplayer/core/backup/SettingsFileTest.kt`
- [x] Check, download and hand an APK to the Android installer {#configuration-updates}
  tech: `core/src/main/java/app/iptvplayer/core/update/AppUpdate.kt`; `app-tv/src/main/java/app/iptvplayer/MainActivity.kt` (`checkForUpdate`, `installUpdate`)

## Build and ship one universal Android app {#delivery}

tech: Gradle includes only `core` and the universal `app-tv`; Make and GitHub Actions build, test, sign and verify that APK.
needs: [playback, configuration]
links: [media-lab, knowledge]
files: [settings.gradle.kts, build.gradle.kts, core/build.gradle.kts, app-tv/build.gradle.kts, gradle.properties, gradle/**, gradlew, gradlew.bat, Makefile, .github/workflows/**, app-tv/src/main/AndroidManifest.xml]

- [x] Assemble phone, tablet and TV support from the single `app-tv` module {#delivery-universal-app}
  tech: `settings.gradle.kts` includes `:core` and `:app-tv`; `app-tv/build.gradle.kts`; `app-tv/src/main/AndroidManifest.xml`
- [x] Gate debug and release builds with tests, lint, signing and APK verification {#delivery-gates}
  tech: `Makefile` targets `check`, `release`, `verify`, `ci-release`; `.github/workflows/ci.yml`; `.github/workflows/release.yml`
- [~] Run isolated device UI jobs on phone and TV emulators {#delivery-device-ui-ci}
  by: codex
  from: roadmap
  tech: `.github/workflows/ui-tests.yml` defines fixed phone API 35 and Android TV API 36 jobs with retained reports; successful execution remains pending
- [x] Expose safe host and device UI-test commands through Make {#delivery-ui-test-commands}
  by: codex
  tech: `Makefile` targets `test-ui-host`, `test-ui-build` and `test-ui-device`; device execution rejects a missing `ANDROID_SERIAL`

## Exercise media compatibility locally {#media-lab}

tech: Python tools generate finite fixtures and launch on-demand FFmpeg processes for continuous HTTP streams with byte-range support.
needs: [delivery]
links: [ingestion, playback]
files: [tools/media-fixtures.py, tools/media-fixtures.sh, tools/media-test-server.py, tools/tests/test_media_fixtures.py, tools/tests/test_media_server.py]

- [x] Generate and probe representative finite containers, manifests, tracks and subtitles {#media-lab-fixtures}
  tech: `tools/media-fixtures.py`; `tools/tests/test_media_fixtures.py`
- [x] Serve continuous live TS, MP4, MKV, HLS and DASH streams on demand {#media-lab-continuous-streams}
  tech: `tools/media-test-server.py`; `tools/tests/test_media_server.py`; `Makefile` targets `media-serve` and `test-media-fixtures`

## Keep the repository map auditable {#knowledge}

kind: knowledge
tech: component boundaries and status rules are defined in `AGENTS.md` and `CLAUDE.md`; supporting specifications and acceptance records live under `docs`.
links: [delivery, experience, playback]
files: [PLAN.md, AGENTS.md, CLAUDE.md, README.md, docs/**]

- [x] Backfill the component map from code, artifacts and recent Git history {#knowledge-backfill}
  by: codex
  tech: `PLAN.md`; `settings.gradle.kts`; source paths cited above; Git commits `22a2151`, `0457282`, and `d74f44b`
- [x] Record release and device-acceptance boundaries separately from implementation {#knowledge-acceptance-records}
  tech: `docs/PRE_RELEASE.md`; `docs/P3_PLAYER_DEVICE_CHECKS.md`; `docs/P3_TV_DEVICE_CHECKS.md`

## decisions

- 2026-11-09: LIVE-006 production behavior already exists in `LiveScreen.kt`, `LiveParts.kt`, settings and player-session handoff. This task will add focused regression coverage for delayed focus playback and stream reuse instead of duplicating the feature; DL-009 and physical-TV acceptance stay separate.

- 2026-11-07: Replaced the placeholder `setup` node with eight session-ownable components derived from the current source tree. IDs introduced by this backfill are now stable.
- 2026-11-07: Treated code and tracked build inputs as implementation evidence, then recent Git history, then acceptance/handoff records. README and roadmap claims were used only for unchecked intent.
- 2026-11-07: Omitted `app-mobile` as a component: commit `22a2151` removed its source, and `settings.gradle.kts` now includes only `core` and `app-tv`. The roadmap's older three-module P0 criterion is stale.
- 2026-11-07: Left multi-provider aggregation, the device UI matrix/CI, and an alternative playback backend unchecked. Their planning documents name them, but the corresponding aggregate source model, `androidTest`/`uiTest` harness and second engine were not found in code.
- 2026-11-07: Did not promote partial device observations to `[x]`; the checked playback tasks are limited to source and automated-test artifacts, while hardware acceptance remains bounded by the P3 device reports.
- 2026-11-07: Split the device UI matrix into independent screen-route test classes over the shared deterministic fixtures. Production UI changes require a reproduced route failure; agents may report missing semantics instead of editing shared screens speculatively.
- 2026-11-07: Kept the phone emulator on API 35 but moved the TV job to API 36: the official SDK repository has no Android TV API 35 image, while `system-images;android-36;android-tv;x86_64` is published. The first API 35 TV job failed before Gradle for that exact missing package.
- 2026-11-08: Live transport buttons will reuse the fullscreen player's existing circular channel index navigation; VOD keeps seek and episode controls unchanged. This changes only the two touch-panel actions and their labels/icons, not remote key behavior.
- 2026-11-09: LIVE-015 keeps the existing reset implementation and adds one focused regression test because the menu, preview and transactional reset already exist; no duplicate reset path is introduced.
- 2026-11-09: Assign the LIVE-015 reset proof to `persistence`, which owns its only changed artifact under `core/db`.
- 2026-11-09: Split LIVE-008 ownership between persistence and experience so the component map assigns storage to core and favorite controls to app-tv.
