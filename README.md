# ITthute Media Splitter

ITthute Media Splitter is a privacy-friendly native Android app that processes media locally on the device. It extracts and trims audio, creates trimmed silent video, divides audio or video into consecutive files, and manages recent output files.

## Features

- Select video or audio through Android's document picker.
- Set a clip range in seconds or `HH:MM:SS` format.
- Export audio as MP3, M4A, AAC, WAV, FLAC, or OGG.
- Export silent video as MP4, MKV, WebM, MOV, or AVI.
- Divide eligible media into 30, 60, 90, or custom 30–300 second parts.
- Show determinate division progress, current part, total part count, completion, and cancellation state.
- Require 300-second parts for source files longer than 600 seconds.
- Restrict division to media longer than 30 seconds and shorter than 3600 seconds.
- View up to ten recently created files under **Split Media**, including divided-media subfolders.
- Open, rename, edit title metadata, copy a path, share, move, or delete a saved file.
- Open the current Music or Movies output folder in a compatible file manager.
- Generate, copy, and share a complete diagnostics report.
- Keep media on the device; no upload service is used.

## Output folders

- Extracted audio: `Music/ITthute Media Splitter/`
- Silent video: `Movies/ITthute Media Splitter/`
- Divided audio: `Music/ITthute Media Splitter/Divided/`
- Divided video: `Movies/ITthute Media Splitter/Divided/`

Divided audio is standardised as M4A/AAC. Divided video is standardised as MP4 with MPEG-4 video and AAC audio so boundaries can be encoded accurately and opened by common Android players. The final part may be shorter than the selected division length.

Android MediaStore manages app-created output files. On modern Android versions the app displays a user-visible relative path and content URI rather than relying on unrestricted filesystem paths.

## Technology

- Kotlin and Android Views
- Material 3 bottom navigation and sliders
- Android Storage Access Framework and MediaStore
- Maintained FFmpegKit Android package
- Minimum Android 10 (API 29)
- Target Android API 35

The APK forces legacy native-library extraction because some devices fail to initialise large FFmpeg shared libraries directly from the APK. Smart Exception helper dependencies are declared explicitly because the maintained FFmpegKit AAR does not currently publish them transitively.

## Build in Android Studio

1. Install a current Android Studio release with Android SDK 35 and JDK 17.
2. Clone this repository.
3. Open the repository root in Android Studio.
4. Allow Gradle to sync and download dependencies.
5. Connect an Android device or start an emulator.
6. Select **Run > Run 'app'**.

The debug APK is normally written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Command-line build

Use Gradle 8.11.1 with JDK 17 and Android SDK 35:

```bash
gradle --stacktrace testDebugUnitTest assembleDebug lint
```

GitHub Actions performs the same validation and publishes the debug APK, lint report, and unit-test report as workflow artifacts. CI also verifies that the Smart Exception runtime and File Divider implementation are packaged in the APK.

## File Divider rules

- The source must be **greater than 30 seconds** and **less than 3600 seconds**.
- Presets are 30, 60, and 90 seconds.
- Custom length is an integer from 30 through 300 seconds.
- The division length must be shorter than the source duration so at least two files are created.
- A source longer than 600 seconds is fixed to 300-second divisions.
- Completed parts remain saved if the user cancels during a later part.

## Saved-media actions

The **Split Media** menu supports:

- Open Media
- Edit media metadata
- Copy file path
- Share
- Move file through Android's folder picker
- Delete file, with Android consent where required

A move copies the item to the selected Storage Access Framework folder and removes the original only after the copy succeeds.

## Troubleshooting

Open **Diagnostics** after a failed operation. The complete report includes app and Android versions, device model and ABIs, native-library contents, media-engine state, the last error, and processing logs.

## Current limitations

- Source files are copied to private cache, so enough temporary free space is required.
- Division re-encodes media for reliable boundaries and can take time on large files.
- The file-manager folder intent is supported by many, but not all, Android file managers.
- Metadata support varies by Android media provider.

## Branding

The supplied ITthute company logo is used in the application header and as the launcher icon.

## Licensing

The application source is licensed under the MIT License. Included FFmpeg and FFmpegKit dependencies retain their own LGPL and component licences. Binary distributors must comply with those licences and applicable codec-distribution obligations.
