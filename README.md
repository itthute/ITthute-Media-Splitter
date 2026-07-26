# ITthute Media Splitter

ITthute Media Splitter is a privacy-friendly native Android app that processes media locally on the device. It extracts or trims audio, creates trimmed silent video, divides audio or video into consecutive files, and manages recent output files.

## Features

- Select video or audio through Android's document picker.
- Configure and process multiple sequential, non-overlapping clip ranges.
- Set every range through typed seconds/`HH:MM:SS` values or a two-handle Material range slider.
- Configure the maximum number of ranges (default 20, protected app limit 50).
- Configure the minimum clip duration (default 5 seconds).
- Configure the maximum media duration accepted by the Splitter, up to an absolute limit of 02:00:00 / 7200 seconds.
- Trim audio-only sources without enabling silent-video output.
- Extract and trim audio from video sources.
- Export audio as MP3, M4A, AAC, WAV, FLAC, or OGG.
- Export one or more silent-video clips as MP4, MKV, WebM, MOV, or AVI.
- Show determinate overall progress and the current clip while batch processing.
- Divide eligible media into 30, 60, 90, or custom 30-300 second parts.
- View up to ten recently created files and open, rename, share, move, or delete them.
- Use bottom navigation or the top-right overflow menu.
- Open Settings, Help, About, and Diagnostics from the overflow menu.
- Keep media on the device; no upload service is used.

## Output folders

- Extracted or trimmed audio: `Music/ITthute Media Splitter/`
- Silent video: `Movies/ITthute Media Splitter/`
- Divided audio: `Music/ITthute Media Splitter/Divided/`
- Divided video: `Movies/ITthute Media Splitter/Divided/`

Android MediaStore manages app-created files. Modern Android versions expose content URIs and user-visible relative paths rather than unrestricted filesystem paths.

## Splitter range rules

- Every start time must be earlier than its end time.
- Ranges are evaluated in displayed order.
- A later range may start when or after the preceding range ends, but may not overlap it.
- Every range must remain within the loaded media duration.
- Every range must meet the configurable minimum clip length.
- The number of ranges may not exceed the configurable maximum.
- The loaded source may not exceed the configurable Splitter duration, and the setting can never exceed 7200 seconds.

## File Divider rules

- The source must be greater than 30 seconds and less than 3600 seconds.
- Presets are 30, 60, and 90 seconds.
- Custom length is an integer from 30 through 300 seconds.
- A source longer than 600 seconds is fixed to 300-second divisions.

## Technology

- Kotlin and Android Views
- Material 3 bottom navigation, sliders, dialogs, and progress indicators
- Android Storage Access Framework and MediaStore
- Maintained FFmpegKit Android package
- Minimum Android 10 (API 29)
- Target Android API 35

The APK forces legacy native-library extraction for compatibility. Smart Exception helper dependencies are declared explicitly because the maintained FFmpegKit AAR does not currently publish them transitively.

## Build

Use Android Studio with JDK 17 and Android SDK 35, or run:

```bash
gradle --stacktrace testDebugUnitTest assembleDebug lint
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions builds, tests, lints, checks packaged runtime classes, and uploads the APK and reports.

## Design documentation

See [docs/ITthute-Media-Splitter-Design.md](docs/ITthute-Media-Splitter-Design.md) for the detailed requirements, architecture, processing flows, validation model, storage design, testing strategy, and extension guidance.

## Troubleshooting

Open **Diagnostics** after a failed operation. The report includes app and Android versions, device model and ABIs, native libraries, FFmpeg state, the last error, and detailed processing logs.

## Licensing

The application source is licensed under the MIT License. FFmpeg and FFmpegKit dependencies retain their own licences. Binary distributors must comply with those licences and applicable codec-distribution obligations.
