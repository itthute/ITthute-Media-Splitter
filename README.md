# ITthute Media Splitter

A privacy-friendly native Android app that processes media entirely on the device.

## Features

- Select local video and audio files through Android's document picker.
- Enter exact start and end positions in seconds or `HH:MM:SS` format.
- Extract and trim audio to MP3, M4A, AAC, WAV, FLAC, or OGG.
- Remove audio and trim silent video to MP4, MKV, WebM, MOV, or AVI.
- Save results under `Music/ITthute Media Splitter` or `Movies/ITthute Media Splitter`.
- Cancel an active processing job.

## Build

1. Install a current stable Android Studio and JDK 17.
2. Clone the repository and open its root folder.
3. Let Gradle sync and download dependencies.
4. Run the `app` configuration on an Android 7.0 or newer device.
5. Generate an APK through **Build > Generate App Bundles or APKs > Generate APKs**.

The debug APK is normally written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

The app uses Kotlin, Android Views, the Storage Access Framework, MediaStore, and the maintained `dev.ffmpegkit-maintained:ffmpeg-kit-audio` Android package. Input media is copied to app-private cache storage, processed with FFmpeg, saved to the user's media library, and then removed from temporary storage.

## Important notes

- The exact codecs available can depend on the FFmpeg package and device architecture.
- Large media requires enough free space for a temporary input copy and output file.
- The included vector mark is a temporary ITthute-branded icon. Replace it with the approved high-resolution ITthute company logo before Play Store publication.
- Review the LGPL obligations of FFmpeg and FFmpegKit before distributing binaries.

## License

Application source code is provided under the MIT License. Third-party libraries retain their own licences.
