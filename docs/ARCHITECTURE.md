# Architecture

## User interface

`MainActivity` presents four Material bottom-navigation destinations:

1. **Splitter** selects media, validates a clip range, and starts audio or silent-video export.
2. **File Divider** shares the loaded source, applies duration rules, offers 30/60/90-second presets and a 30–300-second slider, and displays determinate multi-part progress.
3. **Split Media** queries MediaStore for the ten newest files created in the ITthute output folders and exposes open, metadata edit, path copy, share, move, and delete actions.
4. **Diagnostics** reports app, device, native-library, media-engine, last-error, and detailed-log information.

The File Divider page is constructed programmatically and inserted into the same page host used by the XML-backed destinations. This keeps the original splitter layout stable while adding a fully independent workflow.

## Processing pipeline

`MediaProcessor` copies the selected document URI into app-private cache and executes FFmpeg asynchronously. Ordinary clip exports use `-ss` and an explicit output duration (`-t`). File division runs one bounded FFmpeg operation per part so progress and cancellation can be reported accurately and every completed part can be committed to MediaStore immediately.

Audio division outputs M4A/AAC. Video division outputs MP4 using MPEG-4 video and optional AAC audio. The final part uses the remaining duration and may be shorter than the selected division length.

Temporary source and part files are deleted in `finally` blocks. Already committed parts remain available if a later part fails or the user cancels.

## Division policy

`DivisionRules` is a pure Kotlin policy component covered by unit tests:

- source duration must be greater than 30 and less than 3600 seconds;
- segment duration must be 30–300 seconds and shorter than the source;
- source duration greater than 600 seconds requires 300-second parts;
- part count is calculated with a ceiling operation to include the final short part.

## Saved-media index and management

`SplitMediaRepository` queries the app's Music and Movies MediaStore folder prefixes, including `Divided/` subfolders, merges results by creation time, and returns at most ten entries.

Sharing and opening use content URIs and temporary read grants. Moving uses Android's Storage Access Framework: the repository creates a document in the selected tree, copies content, then removes the original. Deletion first attempts direct MediaStore removal and falls back to Android's user-consent flow where required.

## Diagnostics

`DiagnosticsStore` writes a bounded log in app-private storage. Full throwable stack traces are preserved. Clearing diagnostics never deletes saved media.
