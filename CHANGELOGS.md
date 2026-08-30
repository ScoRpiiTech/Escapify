# 📖 Escapify - Project Documentation & Changelogs

## 📌 Project Overview
**Escapify** (forked from Vivi Music / `vivimusic`) is an open-source (GPL-3.0), modern, privacy-first Android audio streaming and local music player. It provides high-fidelity music playback from YouTube Music and JioSaavn, word-by-word synced karaoke lyrics, dynamic Material You theming, animated canvas visualizers, and collaborative listening rooms.

- **Application ID**: `com.vivi.vivimusic`
- **Target SDK**: 37 (Android 15+) | **Min SDK**: 26 (Android 8.0+)
- **JVM Toolchain**: Java 21 / Kotlin 2.x
- **Personal Fork (`origin`)**: `https://github.com/ScoRpiiTech/Escapify.git`
- **Upstream Repo (`upstream`)**: `https://github.com/vivizzz007/vivi-music.git`

---

## 🏛️ Architecture & Module Structure

```
Escapify/
├── app/                  # Main Android Application (UI, ViewModels, Database, Audio Services, EQ)
├── innertube/            # YouTube & YouTube Music API client, stream extractors & cipher deobfuscators
├── lyricsProvider/       # Multi-source lyrics engine (Musixmatch, LRCLIB, KuGou, PaxSenix, TTML, etc.)
├── jiosaavn/             # JioSaavn stream resolver, search API, and decryption
├── spotify/              # Spotify integration (Metadata, Playlist Importer, Canvas)
├── shazamkit/            # Audio fingerprinting & Shazam music recognition
├── canvas/               # Base Canvas / Tidal & Apple Music backdrop providers
├── applecanvas/          # Apple Music animated canvas engine
├── vivimusiccanvas/      # Custom curated video canvas provider
├── artistvideo/          # Artist video loop visualizer
├── kizzy/                # Discord Rich Presence (RPC) via Gateway WebSockets
└── lastfm/               # Last.fm scrobbling and metadata API
```

---

## 🌿 Git Branching Strategy & Push Protocols

### 1. Remotes & Roles
| Remote | URL | Role |
| :--- | :--- | :--- |
| `origin` | `https://github.com/ScoRpiiTech/Escapify.git` | Your personal fork |
| `upstream` | `https://github.com/vivizzz007/vivi-music.git` | Official repository for updates |

### 2. Dedicated Branches
- **`main`**: Pure upstream mirror. Keep clean with official releases only.
  ```bash
  git push -u origin main
  ```
- **`custom-build`**: Primary active branch for **Escapify**. All custom code, branding, and persistent signing configurations live here.
  ```bash
  git push -u origin custom-build
  ```

### 3. Major Feature Commit & Push Protocol
Whenever a major feature, enhancement, or upgrade is completed and verified:
1. Update `CHANGELOGS.md` with full details.
2. Commit changes to `custom-build`.
3. Push immediately to `origin custom-build` (`git push origin custom-build`).

### 4. Upstream Syncing Protocol (Never Overwrite Custom Changes)
```bash
# 1. Fetch official updates
git fetch upstream

# 2. Update clean main branch
git checkout main
git merge upstream/main
git push origin main

# 3. Rebase your custom Escapify changes on top of the newest version
git checkout custom-build
git rebase upstream/main
git push -f origin custom-build
```

---

## 📝 Change Log & Release Tracker

### [Branding & UI / New Minimalist Spotify-Alternative App Icon] - 2026-08-31
- **Modern Minimalist App Icon**:
  - Replaced legacy app icon with a bespoke minimalist Spotify-alternative visual identity: an emerald green emblem featuring an acoustic sound spiral "e".
  - Generated complete Android adaptive icon suite: `ic_launcher` (all densities `mdpi` to `xxxhdpi`), `ic_launcher_foreground`, `ic_launcher_background`, `ic_launcher_monochrome` (Material You themed icon support for Android 13/14/15), and 512x512 Store graphics.

### [Configuration & DevOps / Dedicated Package ID, In-App Updater & CI/CD Automation] - 2026-08-31
- **Application Package Identity**:
  - Changed `applicationId` to `com.scorpiitech.escapify` in `app/build.gradle.kts`, giving Escapify a standalone application identity that installs cleanly alongside other music players without signature or package conflicts.
- **In-App Auto-Updater Endpoints**:
  - Switched update checker, changelog viewer, and commit inspector endpoints from upstream to `ScoRpiiTech/Escapify` (`https://api.github.com/repos/ScoRpiiTech/Escapify/releases`).
  - Updated asset matching in `vivimusicupdater.kt` to resolve `Escapify.apk` and `Escapify-Universal.apk`.
- **Automated GitHub Actions CI/CD**:
  - Configured `.github/workflows/release.yml` and `build.yml` to automatically build, package, sign, and publish `Escapify.apk` to GitHub Releases on push/dispatch for the `custom-build` branch.

### [Feature / Download Health Integrity Scanner & Auto-Repair Engine] - 2026-08-31
- **Integrity Validation & Truncation Detection**:
  - Built `DownloadUtil.scanDownloadsHealth` to inspect all downloaded tracks in Room DB against actual disk cache bytes in `DownloadCache`.
  - Automatically identifies tracks with missing bytes, corrupted streams, or legacy 10MB range cutoffs (`9.95MB–10.05MB`).
- **1-Tap "Verify & Repair Downloads" in Storage Settings**:
  - Added dedicated repair tool in `StorageSettings.kt` under the Downloads storage group.
  - Scans library and presents a summary of healthy vs incomplete tracks with a single-tap **"Repair & Re-download"** button.
- **Auto-Repair Pipeline**:
  - `DownloadUtil.repairIncompleteDownloads` automatically clears corrupt partial blocks, resets DB download markers, and dispatches fresh unbounded download requests to retrieve complete, pristine audio files.
- **Individual Song Re-download Action**:
  - Added **"Re-download / Repair"** action in `SongMenu.kt` and `YouTubeSongMenu.kt` to re-fetch any downloaded track with one tap.

### [Major Upgrade / Audio Bitrate Control, Strict Wi-Fi Only Mode, Auto-Download on Like, & Spotify Matching] - 2026-08-31
- **Audio Bitrate Quality Engine**:
  - Expanded `AudioQuality` enum to include `MEDIUM` (~128 kbps) alongside `AUTO`, `HIGH` (Opus ~160k / AAC ~256k), and `LOW` (Data Saver 48–64k).
  - Enhanced `YTPlayerUtils.kt` `findFormat` with mathematical target bitrate matching for InnerTube adaptive audio streams.
  - Updated UI dialogs, descriptive labels, player badges, and bottom sheets across `PlayerSettings.kt`, `AudioDeviceBottomSheet.kt`, and `Player.kt`.
- **Strict Wi-Fi Only / Disable Mobile Data Mode**:
  - Added `DisableMobileDataKey` preference toggle in `PlayerSettings.kt` under Playback & Network settings.
  - Enforced `Requirements.NETWORK_UNMETERED` on `DownloadManager` in `DownloadUtil.kt` so downloads strictly run on Wi-Fi and pause on cellular data.
  - Added streaming guard in `MusicService.kt` `createDataSourceFactory` to block uncached cellular streaming when active while keeping offline cached playback seamless.
- **Universal Auto-Download on Like**:
  - Added centralized `DownloadUtil.autoDownloadIfLiked(context, song)` helper.
  - Connected auto-download triggers across `MusicService.kt`, `SongMenu.kt`, and `YouTubeSongMenu.kt` so liking a track from any UI screen automatically queues its background download.
- **Spotify Matching Accuracy Upgrade**:
  - Enhanced `SpotifyMapper.kt` title normalization to clean remastered, deluxe, edition, live, bonus track, and featuring tags, boosting YouTube search match precision.

### [Milestone / GitHub Remote Synchronization] - 2026-08-30
- **Remote `custom-build` Live**: Successfully initialized, organized into clean modular commits, and published the `custom-build` branch to GitHub remote: [`https://github.com/ScoRpiiTech/Escapify/tree/custom-build`](https://github.com/ScoRpiiTech/Escapify/tree/custom-build).
- **Repository Optimization**: Excluded heavy documentation images (`NEW-UI/`, 92MB) from the tracking tree, keeping the codebase repository lightweight (~37MB) and eliminating network HTTP timeouts.
- **Upstream Tracking**: Verified dual-remote configuration with `origin` (personal fork) and `upstream` (official vivi-music releases).

### [Overhaul / Download & Offline Playback Engine] - 2026-08-30
- **Unbounded Stream Resolution**: Removed hardcoded 10MB (`10_000_000`) range restriction on YouTube stream requests in `DownloadUtil.kt`, preventing cutoffs on high-bitrate, long, or unsegmented tracks.
- **Batch Rate-Limit Resilience**: Added automatic retry mechanism with exponential backoff (up to 3 attempts) for InnerTube playback stream resolution during bulk playlist/album downloads.
- **Download Integrity Validation**: Enforced byte-verification threshold (>100KB) in `DownloadManager.Listener` before marking tracks as `isDownloaded = true` in Room database, eliminating false-positive 0-byte downloads.
- **Resilient Offline Playback Fallback**: Enhanced `MusicService.kt` data source factory to prioritize local cached bytes when offline, preventing `ERROR_NO_INTERNET` errors during offline playback.

### [Initial Setup & Branding] - 2026-08-30
- **Initialized Fork**: Connected repository to `origin` (`https://github.com/ScoRpiiTech/Escapify.git`) and `upstream` (`https://github.com/vivizzz007/vivi-music.git`).
- **Configured Permanent Agent Rules**: Created `.agent/rules.md` and added private/confidential paths to `.gitignore` to prevent any sensitive data leakage.
- **Branch & Push Protocol Added**: Enforced automatic commit and push to `custom-build` for every major feature/upgrade.
- **Created Master Changelog**: Initialized `CHANGELOGS.md` with complete project architecture and module documentation.
- **Branding Update**: Updated app display name to `Escapify` in `app/src/main/res/values/app_name.xml`.

---

## 🛡️ Privacy & Sensitive Information Protection
- All private configuration files (`.agent/`, `.claude/`, `local.properties`, `.env`, `*.keystore`, `*.jks`, `secrets.*`) are strictly excluded from version control.
- Never commit private signing keys or credentials.

