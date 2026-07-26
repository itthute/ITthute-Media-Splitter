# ITthute Media Splitter - Detailed Application Design

**Document version:** 1.0  
**Application version:** 1.3.0  
**Owner:** ITthute (Pty) Ltd  
**Platform:** Android 10 and later (API 29+)  
**Repository:** `itthute/ITthute-Media-Splitter`

## 1. Purpose

ITthute Media Splitter is an on-device Android utility for media owned or lawfully controlled by the user. It supports precise multi-range trimming, audio extraction, silent-video creation, equal-length file division, diagnostics, and saved-output management without uploading media to an external service.

## 2. Design goals

1. **Privacy:** all processing remains on the Android device.
2. **Predictability:** validation occurs before expensive processing begins.
3. **Transparency:** determinate progress and detailed diagnostics remain visible.
4. **Android compatibility:** Storage Access Framework and MediaStore are used instead of unrestricted storage paths.
5. **Recoverability:** temporary files are deleted in `finally` blocks and completed files remain available after later failures.
6. **Learnability:** controllers separate UI concerns from media, settings, and storage logic.

## 3. Functional scope

### 3.1 Splitter

The Splitter accepts audio or video and allows one or more clip ranges. Each range has typed start/end fields and a synchronized two-handle slider.

For audio input:

- **Save audio clip/s** trims the audio source.
- Silent-video output is disabled because no video stream exists.

For video input:

- **Save audio clip/s** removes the video stream, extracts audio, and trims each selected range.
- **Save silent video clip/s** removes audio and creates a separate video file for each selected range.

### 3.2 File Divider

The Divider creates consecutive equal-length parts. It accepts sources longer than 30 seconds and shorter than 3600 seconds, with 30/60/90-second presets and a custom 30-300-second value. Sources longer than 600 seconds use 300-second parts.

### 3.3 Split Media

The recent-output list shows up to ten files from ITthute output folders. Actions include open, metadata edit, path copy, share, move, and delete.

### 3.4 Diagnostics

Diagnostics reports app, device, ABI, native-library, FFmpeg, error, and log information. Reports can be copied or shared.

### 3.5 Overflow menu

The top-right menu provides alternate navigation plus Settings, Help, and About.

## 4. Configurable settings

| Setting | Default | Allowed values | Purpose |
|---|---:|---:|---|
| Maximum clip ranges | 20 | 1-50 | Protects the UI and batch workload while remaining user configurable. |
| Maximum Splitter source duration | 3600 s | 60-7200 s | Rejects excessively long Splitter workloads. The hard maximum is two hours. |
| Minimum clip length | 5 s | 1-60 s | Prevents accidental near-empty output clips. |

Settings are stored in Android `SharedPreferences` by `AppSettings`.

## 5. Architecture

```text
MainActivity
  |-- ClipRangeController ------ ClipRangeRules
  |-- SettingsController ------- AppSettings
  |-- FileDividerController ---- DivisionRules
  |-- SplitMediaController ----- SplitMediaRepository
  |-- DiagnosticsController ---- DiagnosticsStore
  `-- MediaProcessor ----------- FFmpegKit + MediaStore
```

### 5.1 MainActivity

`MainActivity` is the composition root. It owns the selected media state, page navigation, system-window insets, overflow menu, source metadata, and batch-operation lifecycle.

### 5.2 ClipRangeController

The controller dynamically creates range cards. It synchronizes:

- start text field;
- end text field;
- two-handle `RangeSlider`;
- range duration summary.

Changing the requested number of ranges initially divides the media duration evenly. Users may then adjust every range independently.

### 5.3 ClipRangeRules

This pure Kotlin component validates ranges and is unit tested without Android dependencies. Rules include:

1. non-empty range list;
2. maximum count;
3. finite values;
4. non-negative start;
5. end later than start;
6. end within source duration;
7. minimum duration;
8. sequential, non-overlapping ordering.

### 5.4 MediaProcessor

The processor copies the selected content URI into private cache once per batch. It then performs each range sequentially with FFmpeg, publishes progress statistics, saves the result through MediaStore, and deletes temporary files.

Sequential processing was selected because it:

- limits memory, CPU, and storage spikes;
- provides understandable progress;
- simplifies cancellation;
- avoids multiple FFmpeg sessions competing for phone resources.

### 5.5 Storage model

Input is read through a content URI obtained from Android's document picker. Outputs are inserted into MediaStore with `IS_PENDING=1`, copied, and finalized with `IS_PENDING=0`.

This protects partially written files from appearing in other apps.

## 6. Multi-range processing flow

```text
User selects media
  -> MediaMetadataRetriever reads duration and stream type
  -> source duration is checked against settings
  -> range UI is initialized
  -> user chooses count and edits sliders/text
  -> ClipRangeRules validates the complete ordered set
  -> source is copied once to private cache
  -> for each range:
       FFmpeg executes
       statistics update overall progress
       output is verified as non-empty
       output is saved to MediaStore
  -> temporary input is deleted
  -> user sees completion count
```

Overall progress is calculated as:

```text
overall = ((completed clip index + current clip percentage / 100) / total clips) * 100
```

## 7. FFmpeg command strategy

Every range uses an explicit seek and duration:

```text
-hide_banner -y -ss <start> -i <input> -t <duration> <codec arguments> <output>
```

Examples:

- MP3 audio: `-vn -c:a libmp3lame -b:a 192k`
- M4A audio: `-vn -c:a aac -b:a 192k -movflags +faststart`
- Silent MP4: `-an -c:v mpeg4 -q:v 3 -pix_fmt yuv420p`

Re-encoding is used for accurate boundaries and broad playback compatibility.

## 8. User-interface design

### 8.1 System-bar safety

Android API 35 uses edge-to-edge layouts. The header and bottom navigation apply `WindowInsetsCompat` values so the logo/title no longer collide with the status bar and navigation bar.

### 8.2 Dual time input

Each clip supports both:

- text entry in seconds, `MM:SS`, or `HH:MM:SS` form;
- a two-handle slider for visual adjustment.

Valid text changes update the slider; slider changes update the text fields.

### 8.3 Progress

A determinate linear indicator, clip counter, percentage, status message, and cancel action remain visible throughout processing.

## 9. Error handling

- Metadata failures are logged and surfaced before processing.
- Invalid ranges are blocked with the clip number and reason.
- FFmpeg return codes and logs are captured.
- Native startup failures direct the user to Diagnostics.
- Cancellation stops the active FFmpeg session and preserves already saved clips.
- Temporary input/output files are removed in `finally` blocks.

## 10. Testing strategy

### 10.1 Unit tests

`ClipRangeRulesTest` covers valid sequential ranges, overlaps, reversed times, minimum-length enforcement, time parsing, and even initial range generation. `DivisionRulesTest` covers the Divider boundaries.

### 10.2 Continuous integration

GitHub Actions performs unit tests, debug compilation, Android lint, runtime dependency verification, DEX inspection for required feature classes, and APK/report publication.

### 10.3 Device tests

Recommended device scenarios:

- audio-only MP3 with three ranges;
- MP4 video with extracted MP3 ranges;
- MP4 silent-video ranges;
- touching ranges (`end == next start`);
- overlapping ranges (must be rejected);
- range below configured minimum (must be rejected);
- source just below and above configured maximum;
- cancellation during a later clip;
- screen rotation and returning from Android's file picker.

## 11. Security and privacy

- No internet permission is required.
- Selected media is copied only to app-private cache.
- Diagnostics are stored in app-private storage.
- Sharing occurs only after an explicit user action.
- Android permission and consent flows govern file deletion and movement.

## 12. Known trade-offs

- Re-encoding can be slower than stream copying.
- Large files require temporary free space approximately equal to the input plus one output clip.
- Equal initial ranges may need manual adjustment when desired content is not evenly distributed.
- Codec support depends on the bundled FFmpeg build.

## 13. Future enhancements

1. waveform previews for audio;
2. thumbnail timeline for video;
3. named range presets and reusable projects;
4. background foreground-service processing with notifications;
5. pause/resume where codec and FFmpeg constraints permit;
6. per-range output format and filename templates;
7. instrumented UI tests on multiple Android versions;
8. signed release builds and store distribution workflows.

## 14. Source-file map

| File | Responsibility |
|---|---|
| `MainActivity.kt` | Composition, navigation, source metadata, batch lifecycle |
| `ClipRangeController.kt` | Dynamic range UI and slider/text synchronization |
| `ClipRangeRules.kt` | Pure range model, parsing, formatting, validation |
| `AppSettings.kt` | Persistent validated preferences |
| `SettingsController.kt` | Material settings dialog |
| `MediaProcessor.kt` | FFmpeg execution, progress, cancellation, MediaStore output |
| `FileDividerController.kt` | Equal-part division UI |
| `SplitMediaController.kt` | Recent-output actions |
| `SplitMediaRepository.kt` | MediaStore queries and mutations |
| `DiagnosticsStore.kt` | Bounded logs and diagnostic report generation |

## 15. Learning notes

A useful way to understand this project is to follow one requirement end-to-end. For example, the five-second minimum begins as a persisted setting in `AppSettings`, appears as a slider in `SettingsController`, is displayed in `ClipRangeController`, is enforced by `ClipRangeRules`, is tested in `ClipRangeRulesTest`, and prevents `MediaProcessor` from receiving an invalid workload. This traceability is a core software-design practice.
