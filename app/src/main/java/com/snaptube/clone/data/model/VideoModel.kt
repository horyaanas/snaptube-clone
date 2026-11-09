package com.snaptube.clone.data.model

/** The type of media a download format represents. */
enum class MediaType {
    AUDIO,
    VIDEO,
    SUBTITLE
}

/**
 * A single downloadable format extracted from a streaming site.
 *
 * For YouTube, high-resolution video is served as a video-only stream plus a
 * separate audio stream. When that happens [downloadUrl] points to the video
 * track, [audioUrl] points to the audio track and [requiresMux] is true.
 */
data class FormatOption(
    val id: String,
    val type: MediaType,
    /** e.g. "720P", "M4A 128K", "English" */
    val qualityLabel: String,
    /** e.g. "HD", "Best for mobile", "auto-generated" */
    val subtitle: String = "",
    /** File extension without the dot, e.g. "mp4", "m4a", "webm", "srt" */
    val formatExtension: String,
    val estimatedSizeBytes: Long,
    /** Primary media URL (video track for muxed video, or the audio URL). */
    val downloadUrl: String,
    /** Separate audio track URL, only set when [requiresMux] is true. */
    val audioUrl: String? = null,
    val isHd: Boolean = false,
    val bitrateKbps: Int = 0,
    val resolution: String = "",
    val requiresMux: Boolean = false,
    val container: String = "mp4",
    val audioLanguage: String = ""
) {
    val readableSize: String
        get() {
            if (estimatedSizeBytes <= 0L) return ""
            val mb = estimatedSizeBytes.toDouble() / (1024 * 1024)
            return if (mb >= 1024) {
                String.format("%.2f GB", mb / 1024)
            } else {
                String.format("%.1f MB", mb)
            }
        }

    val isAudio: Boolean get() = type == MediaType.AUDIO
    val isVideo: Boolean get() = type == MediaType.VIDEO
}

data class VideoDetails(
    val id: String,
    val title: String,
    val author: String,
    val durationSeconds: Long,
    val durationFormatted: String,
    val thumbnailUrl: String,
    val webpageUrl: String,
    val formats: List<FormatOption> = emptyList(),
    val viewCountText: String = "",
    val uploadDateText: String = "",
    val serviceId: Int = 0,
    val description: String = ""
) {
    val hasFormats: Boolean get() = formats.isNotEmpty()
}

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    MERGING,
    COMPLETED,
    FAILED
}

data class DownloadTask(
    val id: String,
    val title: String,
    val author: String,
    val thumbnailUrl: String,
    val format: FormatOption,
    val targetFilePath: String,
    var totalBytes: Long = 0L,
    var downloadedBytes: Long = 0L,
    var progressPercent: Int = 0,
    var downloadSpeedBps: Long = 0L,
    var status: DownloadStatus = DownloadStatus.QUEUED,
    val mimeType: String = "video/mp4",
    val createdAt: Long = System.currentTimeMillis(),
    var errorMessage: String? = null
) {
    val readableSpeed: String
        get() {
            if (downloadSpeedBps <= 0L) return ""
            val kb = downloadSpeedBps.toDouble() / 1024
            return if (kb >= 1024) {
                String.format("%.1f MB/s", kb / 1024)
            } else {
                String.format("%.0f KB/s", kb)
            }
        }

    val isActive: Boolean
        get() = status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING ||
            status == DownloadStatus.MERGING
}

data class SiteShortcut(
    val id: String,
    val title: String,
    val url: String,
    val iconResId: Int? = null,
    val iconColorHex: String = "#FFCD22",
    val packageName: String = ""
)

/** A search suggestion / hot search entry. */
data class SearchEntry(
    val text: String,
    val isHistory: Boolean = false
)
