package com.snaptube.clone.data.model

enum class MediaType {
    AUDIO,
    VIDEO
}

data class FormatOption(
    val id: String,
    val type: MediaType,
    val qualityLabel: String,     // e.g. "MP3 128K", "720P HD"
    val subtitle: String = "",    // e.g. "Best for mobile"
    val formatExtension: String, // "mp3", "m4a", "mp4", "webm"
    val estimatedSizeBytes: Long,
    val downloadUrl: String,
    val isHd: Boolean = false
) {
    val readableSize: String
        get() {
            val mb = estimatedSizeBytes.toDouble() / (1024 * 1024)
            return if (mb >= 1024) {
                String.format("%.1f GB", mb / 1024)
            } else {
                String.format("%.1f MB", mb)
            }
        }
}

data class VideoDetails(
    val id: String,
    val title: String,
    val author: String,
    val durationSeconds: Long,
    val durationFormatted: String,
    val thumbnailUrl: String,
    val webpageUrl: String,
    val formats: List<FormatOption>
)

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
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
    var totalBytes: Long = 0,
    var downloadedBytes: Long = 0,
    var progressPercent: Int = 0,
    var downloadSpeedBps: Long = 0,
    var status: DownloadStatus = DownloadStatus.QUEUED
) {
    val readableSpeed: String
        get() {
            val kb = downloadSpeedBps.toDouble() / 1024
            return if (kb >= 1024) {
                String.format("%.1f MB/s", kb / 1024)
            } else {
                String.format("%.0f KB/s", kb)
            }
        }
}

data class SiteShortcut(
    val id: String,
    val title: String,
    val url: String,
    val iconResId: Int? = null,
    val iconColorHex: String = "#FFBB00"
)
