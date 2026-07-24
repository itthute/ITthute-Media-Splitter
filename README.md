# ITthute Media Splitter

ITthute Media Splitter is a privacy-friendly native Android app that processes media locally on the device. It can extract and trim audio, create trimmed video clips without audio, and save the result in Android's media library.

## Features

- Select video and audio through Android's document picker.
- Set the clip range in seconds or `HH:MM:SS` format.
- Export audio as MP3, M4A, AAC, WAV, FLAC, or OGG.
- Export silent video as MP4, MKV, WebM, MOV, or AVI.
- View up to ten recently created files under **Split Media**.
- Open, rename, edit the title, copy the user-visible path, or share a saved file.
- Open the current Music or Movies output folder in a compatible file manager.
- Generate, copy, and share a complete diagnostics report.
- Review detailed processing logs, open Android app settings, or clear diagnostics data.
- Keep media on the device; no upload service is used.

## Output folders

- Audio: `Music/ITthute Media Splitter/`
- Video: `Movies/ITthute Media Splitter/`

Android MediaStore manages these files. On modern Android versions the app displays a user-visible relative path and content URI rather than relying on unrestricted filesystem paths.

## Technology

- Kotlin and Android Views
- Material 3 bottom navigation
- Android Storage Access Framework and MediaStore
- Maintained FFmpegKit Android package
- Minimum Android 10 (API 29)
- Target Android 15 (API 35)

The APK forces legacy native-library extraction because some devices fail to initialize large FFmpeg shared libraries when they are loaded directly from the APK.

## Build in Android Studio

1. Install the latest stable Android Studio and JDK 17.
2. Clone this repository.
3. Open the repository root in Android Studio.
4. Allow Gradle to sync and download dependencies.
5. Connect an Android device with USB debugging enabled, or start an emulator.
6. Select **Run > Run 'app'**.

To generate an APK, use **Build > Generate App Bundles or APKs > Generate APKs**. The debug APK is normally written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Command-line build

Use Gradle 8.11.1 with JDK 17 and Android SDK 35:

```bash
gradle --stacktrace assembleDebug lint
```

GitHub Actions performs the same build and publishes the debug APK as a workflow artifact.

## Troubleshooting

Open the **Diagnostics** tab after a failed operation. The complete report includes:

- app and Android versions;
- device model and supported ABIs;
- native-library directory contents;
- FFmpegKit Java and native initialization status;
- the last processing error and complete stack trace;
- recent processing logs.

Use **Copy complete diagnostics report** or **Share diagnostics report** when reporting a fault.

## Current limitations

- Large source files require enough free temporary space because the source is copied into app-private cache during processing.
- Encoding speed varies by file size, format, and phone performance.
- The file-manager folder intent is supported by many, but not all, Android file managers. The app falls back to Android's document picker when necessary.
- This version uses time fields rather than a waveform or video timeline editor.

## Branding

The supplied ITthute company logo is used in the application header and as the launcher icon.

## Licensing

The application source is licensed under the MIT License. The included FFmpeg and FFmpegKit dependencies retain their own LGPL and other component licenses. Binary distributors must comply with those dependency licences and provide the corresponding notices and source-access information where required.
