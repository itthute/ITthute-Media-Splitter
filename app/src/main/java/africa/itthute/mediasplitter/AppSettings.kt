package africa.itthute.mediasplitter

import android.content.Context

class AppSettings(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var maxClipRanges: Int
        get() = preferences.getInt(KEY_MAX_CLIP_RANGES, DEFAULT_MAX_CLIP_RANGES)
            .coerceIn(MIN_MAX_CLIP_RANGES, MAX_MAX_CLIP_RANGES)
        set(value) {
            preferences.edit().putInt(
                KEY_MAX_CLIP_RANGES,
                value.coerceIn(MIN_MAX_CLIP_RANGES, MAX_MAX_CLIP_RANGES)
            ).apply()
        }

    var maxMediaDurationSeconds: Int
        get() = preferences.getInt(KEY_MAX_MEDIA_DURATION, DEFAULT_MAX_MEDIA_DURATION_SECONDS)
            .coerceIn(MIN_MAX_MEDIA_DURATION_SECONDS, ABSOLUTE_MAX_MEDIA_DURATION_SECONDS)
        set(value) {
            preferences.edit().putInt(
                KEY_MAX_MEDIA_DURATION,
                value.coerceIn(MIN_MAX_MEDIA_DURATION_SECONDS, ABSOLUTE_MAX_MEDIA_DURATION_SECONDS)
            ).apply()
        }

    var minimumClipLengthSeconds: Int
        get() = preferences.getInt(KEY_MINIMUM_CLIP_LENGTH, DEFAULT_MINIMUM_CLIP_LENGTH_SECONDS)
            .coerceIn(MIN_MINIMUM_CLIP_LENGTH_SECONDS, MAX_MINIMUM_CLIP_LENGTH_SECONDS)
        set(value) {
            preferences.edit().putInt(
                KEY_MINIMUM_CLIP_LENGTH,
                value.coerceIn(MIN_MINIMUM_CLIP_LENGTH_SECONDS, MAX_MINIMUM_CLIP_LENGTH_SECONDS)
            ).apply()
        }

    companion object {
        private const val PREFERENCES_NAME = "app_settings"
        private const val KEY_MAX_CLIP_RANGES = "max_clip_ranges"
        private const val KEY_MAX_MEDIA_DURATION = "max_media_duration_seconds"
        private const val KEY_MINIMUM_CLIP_LENGTH = "minimum_clip_length_seconds"

        const val DEFAULT_MAX_CLIP_RANGES = 20
        const val MIN_MAX_CLIP_RANGES = 1
        const val MAX_MAX_CLIP_RANGES = 50

        const val DEFAULT_MAX_MEDIA_DURATION_SECONDS = 3600
        const val MIN_MAX_MEDIA_DURATION_SECONDS = 60
        const val ABSOLUTE_MAX_MEDIA_DURATION_SECONDS = 7200

        const val DEFAULT_MINIMUM_CLIP_LENGTH_SECONDS = 5
        const val MIN_MINIMUM_CLIP_LENGTH_SECONDS = 1
        const val MAX_MINIMUM_CLIP_LENGTH_SECONDS = 60
    }
}
