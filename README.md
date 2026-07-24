# ITthute Media Splitter

**ITthute Media Splitter** is an Android app for working with video files you own. It can:

- extract a chosen section of a video's audio;
- save audio as MP3, M4A/AAC, AAC, WAV, FLAC, OGG or Opus;
- remove audio and save a chosen video section as MP4, MKV, WebM, MOV or AVI;
- select precise start and end points with a range control;
- save through Android's Storage Access Framework, including local storage and supported cloud providers;
- process everything on-device without uploading media.

## Project status

This repository contains the first working MVP. The code is prepared for Android Studio and GitHub Actions. Because the FFmpeg dependency is large, the APK will also be significantly larger than an ordinary utility app.

## Technology

- Kotlin and Android Views
- Material 3 components
- Android Storage Access Framework (`OpenDocument` and `CreateDocument`)
- `dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:6.0.2`
- Minimum Android version: Android 7.0 (API 24)
- Target/compile SDK: API 35

The original FFmpegKit project was retired. This project uses a maintained community fork with current Android support. The `full-gpl` package is used because the requested MP3 and H.264 encoders require codecs supplied in the GPL-enabled build.

## Open in Android Studio

1. Install a current Android Studio release with Android SDK 35 and JDK 17 support.
2. Clone this repository.
3. Open the repository root in Android Studio.
4. Allow Gradle sync to finish.
5. Run the `app` configuration on an Android 7.0+ phone or emulator.

The repository includes Gradle distribution settings. GitHub Actions installs Gradle 8.9 directly. To generate standard local wrapper scripts and the wrapper JAR, run:

```bash
gradle wrapper --gradle-version 8.9
```

## Build an APK locally

With Gradle 8.9 installed:

```bash
gradle testDebugUnitTest assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Build with GitHub Actions

Every pull request runs unit tests and builds a debug APK. Open the workflow run, select **Artifacts**, and download **ITthute-Media-Splitter-debug**.

## Using the app

1. Tap **Select video** and choose a video that you own or are authorised to edit.
2. Choose **Trimmed audio** or **Trimmed video without audio**.
3. Select the output format.
4. Move the two range handles to set the start and end points.
5. Tap **Choose save location & export**.
6. Choose the destination and filename.
7. Keep the app open while processing. You may cancel an export in progress.

## Format notes

- MP3 uses LAME at 192 kbps.
- MP4, MKV and MOV use H.264 with a quality-oriented CRF setting.
- WebM uses VP9.
- Exact trimming re-encodes the selected video clip. This is slower than stream-copy trimming but produces accurate boundaries.
- Actual input compatibility depends on the codecs present in the bundled FFmpeg build and the selected file.

## Branding

The current source includes an ITthute-branded app mark, launcher icon, company name and colour treatment. Replace `app/src/main/res/drawable/ic_itthute_media.xml` with approved production artwork when the official high-resolution ITthute logo asset is added to the repository.

## Privacy

The app does not request internet permission and does not upload files. Selected input media is copied temporarily into the app cache for FFmpeg access and deleted after the export or when the activity is destroyed.

## Licensing

The repository source remains under the existing MIT license. However, APKs that bundle the `full-gpl` FFmpeg package are distributed subject to the GNU GPL v3 obligations applicable to that combined binary. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) before publishing an APK.
