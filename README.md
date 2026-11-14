# AVPlayer Preview

English | [日本語](README.ja.md)

A macOS-only IntelliJ IDEA plugin that previews local MP4, M4V and MOV files in an editor tab, without converting them first (prototype).
Decoding uses the built-in macOS AVPlayer, so FFmpeg, VLC, JCEF and HTTP servers are not needed, and the original file is never modified.

> **This is a prototype.** Native playback is tested, but playback, audio and controls inside the IDE window have not been verified yet.
> See [Verification status](#verification-status) for details.

## Requirements

- macOS 13 or later, Apple Silicon (arm64) only. Intel Macs are not supported.
- IntelliJ IDEA 2026.3 or later (build 263+). Built and checked with 2026.3; later versions are untested.
- Local files only. Remote Development is not supported.

## Installation

### A. Add the plugin repository (recommended, with automatic updates)

1. Open `Settings → Plugins`, then choose the gear menu → `Manage Plugin Repositories…`.
2. Click `+`, add the following URL and press OK.
   ```
   https://raw.githubusercontent.com/junara/avplayer-preview/main/updatePlugins.xml
   ```
3. Search for `AVPlayer Preview` on the `Marketplace` tab, install it and restart the IDE.

New versions arrive through the IDE's usual plugin update notifications. If you use several Macs, add the same URL in each IDE.

### B. Install from a ZIP file

1. Download `avplayer-preview-<version>.zip` from the **Assets** of [Releases](https://github.com/junara/avplayer-preview/releases).
   Do not pick "Source code (zip)"; that is the source tree.
2. Choose `Settings → Plugins` → gear menu → `Install Plugin from Disk…`, select the ZIP and restart the IDE.

If you built the plugin yourself, select `dist/avplayer-preview-<version>.zip` the same way.

### Uninstall

Disable or uninstall `AVPlayer Preview` in `Settings → Plugins → Installed` and restart the IDE.
The plugin contains native code, so always restart after updating, disabling or uninstalling it.

## Usage

Double-click an MP4/M4V/MOV file in the Project view and press `Play`.

- Play/pause, timeline seeking, volume and playback speed (0.5 / 1 / 1.5 / 2×)
- `Info` shows or hides the video info panel
- Switching to another tab pauses playback; press Play again when you come back.
- Pressing `Play` at the end starts again from the beginning.

If a video does not open with this plugin:

- Disable other plugins that handle the same extensions (such as Jetplay).
- In `Settings → Editor → File Types`, check that `mp4` / `m4v` / `mov` are associated with `AVPlayer Video`.

### Video info panel

- **File**: name, size, container (detected from the `ftyp` brand at the start of the file, or the first atom of classic QuickTime files, not from the extension), duration, overall bitrate
- **Video**: codec, resolution, rotation, horizontal/vertical mirroring, frame rate, bitrate, bit depth, color space, HDR type
- **Audio**: codec, sample rate, channels, bitrate, language

Rows can be selected and copied. The info is loaded on a separate thread from playback the first time the panel opens (up to 10 seconds), and it is shown even for videos that fail to play.
Items that could not be loaded are listed in the `Metadata` row with the reason (or a timeout) and are also written to the IDE log. Reopening the panel loads the info again.

### Remembered settings

- Volume is remembered IDE-wide and applied to the next video you open and after restarting the IDE. It is not applied to other tabs that are already open; the value from the tab you changed last is saved.
- Whether the video info panel is shown is also remembered IDE-wide.

## Limitations

- Video frames for display are scaled down to **at most 1280 px on the long side, at about 30 fps**. This scaling happens after decoding; no converted file is created. AVPlayer manages audio and playback time.
- Rendering copies frames through CPU memory; it is not zero-copy GPU rendering.
- Only codecs supported by AVFoundation can be played, so not every MP4 will play.
- HDR (HDR10 / HLG) is converted to SDR (BT.709) for display. Showing HDR as HDR, applying rotation and pixel aspect ratio, and choosing subtitle or audio tracks are not supported.
- Native code runs inside the IDE process. Save your work before trying the plugin for the first time.

## Verification status

| Item | Status |
| --- | --- |
| Build | Succeeds with IntelliJ IDEA **IU-263.7307.200** (bundled JBR **25.0.4.1**), including the arm64 dylib and ad-hoc signing |
| Native playback tests (no GUI, through JNI) | 2026-11-13: all checks PASS on Apple Silicon (see [Tests](#tests)) |
| Playback, audio, seeking and tab handling inside the IDE window | **Not verified** |
| 4K60, HEVC 10-bit, HDR10/HLG | 2026-11-14: playback and video info checked with a measurement run without a GUI; about 30 fps when reproducing the editor's polling interval. SDR conversion is covered by an automated test for PQ only; for HLG it was only checked by hand that conversion happens (accuracy not checked). Appearance inside the IDE window not verified |
| Portrait videos | Not verified (rotation and mirroring are tested only in the video info) |

### If playback fails with an error

If you see `AVFoundationErrorDomain -11821` or `NSOSStatusErrorDomain -12911` (`kVTVideoDecoderMalfunctionErr`),
the problem may be in the macOS decoder rather than in the plugin.
Check whether a standalone AVFoundation probe, which uses neither JNI nor the IDE, shows the same error.

```sh
xcrun clang++ -fobjc-arc tests/avfoundation_probe.mm \
  -framework Foundation -framework AVFoundation -framework CoreVideo \
  -o build/avfoundation-probe
build/avfoundation-probe '/path/to/video.mp4'
```

## Development

### How it works

Kotlin (editor UI) → JNI → Objective-C++ → AVPlayer → AVPlayerItemVideoOutput → drawn with Swing.
It does not embed AVPlayerView as a native window.
AVPlayer is driven on the AppKit main queue, and JNI calls run serially on a dedicated worker thread.
Closing a tab stops AVPlayer asynchronously and releases its resources.

### Build

Requirements: IntelliJ IDEA 2026.3 (its bundled Kotlin plugin and JBR are used), Xcode Command Line Tools and Python 3.
The installed IDE is used as the SDK, so Gradle and downloads of external dependencies are not needed.

```sh
python3 scripts/build.py
```

If the IDE is not at `~/Applications/IntelliJ IDEA.app`:

```sh
IDEA_HOME='/Applications/IntelliJ IDEA.app/Contents' python3 scripts/build.py
```

Output: `dist/avplayer-preview-<version>.zip` (the version is `<version>` in `plugin.xml`)

### Tests

Native playback is tested through the real JNI bridge, without a GUI.
Test videos are generated into `build/` with FFmpeg, so **FFmpeg is needed only for tests**. Rotation and mirroring checks need FFmpeg 7 or later; with an older version only those checks are skipped.
Tests run at zero volume and never touch existing videos.

```sh
python3 scripts/build.py
python3 scripts/test_native.py
```

Covered: missing path, open, decode, time advancing, pause, seek, playback rate,
video info (codec, resolution, frame rate, audio format, 29.97 fps, rotation, horizontal mirroring), three create/destroy rounds, double close,
frame buffer reuse, every pixel opaque, color order (R/B not swapped in BGRA → xRGB),
and PQ HDR converted to SDR (skipped without libx265). Tests time out after 60 seconds.
They run on a path containing Japanese characters (`build/日本語 sample.mp4`).

To check inside the IDE window:

- Whether landscape MP4 shows video and plays audio, and whether audio stays in sync
- Seeking, playing again from the end, playback speed and volume changes
- Window resizing, tab switching, and stopping/releasing when a tab is closed
- Freezes or growing memory when reopening files or using several tabs
- Portrait, HEVC, 4K and similar videos

### Release

1. Bump `<version>` in `plugin.xml`, update `<change-notes>` and commit.
2. Dry run (does not change git or GitHub). Check `build/updatePlugins.xml` and the release notes.
   ```sh
   python3 scripts/release.py --dry-run
   ```
3. Release (requires a logged-in `gh` CLI):
   ```sh
   python3 scripts/release.py
   ```
   This builds, updates and commits `updatePlugins.xml`, pushes the `v<version>` tag, attaches the ZIP to a GitHub Release and then pushes `main`, in that order.
   Because the ZIP is published before `main` is pushed, `updatePlugins.xml` never points at a ZIP that does not exist.

### Files

| Path | Contents |
| --- | --- |
| `src/main/kotlin/.../VideoEditor.kt` | Editor, UI and worker |
| `src/main/kotlin/.../VideoFileType.kt` | File type registration |
| `src/main/java/.../NativePlayer.java` | JNI declarations and native library loading |
| `native/NativePlayer.mm` | AVPlayer, frame fetching, scaling and video info |
| `src/main/resources/META-INF/plugin.xml` | Plugin definition (version and change notes) |
| `scripts/build.py` | Build against the installed IDE |
| `scripts/release.py` | Publishing to GitHub Releases and updating `updatePlugins.xml` |
| `updatePlugins.xml` | Custom plugin repository (generated by `release.py`) |
| `tests/` | JNI tests and the standalone AVFoundation probe |

### References

- [AVPlayer](https://developer.apple.com/documentation/avfoundation/avplayer)
- [AVPlayerItemVideoOutput](https://developer.apple.com/documentation/avfoundation/avplayeritemvideooutput)
- [Build Number Ranges](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html)
- [Custom Plugin Repository](https://plugins.jetbrains.com/docs/intellij/custom-plugin-repository.html)

## Contributing

Pull requests are not accepted. You are free to fork and modify the code under the MIT License.

## License

[MIT License](LICENSE)
