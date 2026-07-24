# Architecture

## User interface

`MainActivity` presents three Material bottom-navigation destinations:

1. **Splitter** selects media, validates the clip range, and starts audio or silent-video export.
2. **Split Media** queries MediaStore for the ten newest files created in the ITthute output folders and exposes open, metadata edit, path copy, and share actions.
3. **Diagnostics** reports the app, device, native libraries, media-engine state, last error, and detailed processing logs.

## Processing pipeline

`MediaProcessor` copies the selected document URI into app-private cache, constructs an FFmpeg command using `-ss` and an explicit output duration (`-t`), executes it asynchronously, and copies the completed result into MediaStore. Temporary input and output files are deleted in a `finally` block whether the operation succeeds or fails.

Native FFmpeg libraries are extracted at installation time (`useLegacyPackaging = true` and `android:extractNativeLibs = true`) to improve loading reliability on devices that cannot initialize the libraries directly from the APK.

## Saved-media index

`SplitMediaRepository` queries the app's Music and Movies MediaStore folders, merges the results by creation time, and returns at most ten entries. Media is shared using content URIs and temporary read grants; unrestricted storage paths are not required.

## Diagnostics

`DiagnosticsStore` writes a bounded log file in app-private storage. Full throwable stack traces are preserved. A diagnostics report can safely be copied or shared by the user, and clearing diagnostics does not delete saved media.
