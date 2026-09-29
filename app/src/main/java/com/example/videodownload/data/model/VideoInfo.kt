package com.example.videodownload.data.model

data class DownloadOption(
    val label: String,
    val downloadUrl: String,
    val isAudio: Boolean = false,
    val quality: String = "HD",
    val extension: String = "mp4",
    val audioDownloadUrl: String? = null
)

data class VideoInfo(
    val id: String,
    val title: String,
    val author: String,
    val thumbnailUrl: String,
    val downloadUrl: String,
    val platform: Platform,
    val durationSeconds: Int = 0,
    val quality: String = "HD",
    val isWatermarkFree: Boolean = true,
    val originalUrl: String,
    val options: List<DownloadOption> = emptyList()
)

data class DownloadedItem(
    val id: String,
    val title: String,
    val platform: Platform,
    val localUri: String,
    val timestamp: Long = System.currentTimeMillis()
)
