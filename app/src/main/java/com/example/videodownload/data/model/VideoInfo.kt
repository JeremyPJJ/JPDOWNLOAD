package com.example.videodownload.data.model

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
    val originalUrl: String
)

data class DownloadedItem(
    val id: String,
    val title: String,
    val platform: Platform,
    val localUri: String,
    val timestamp: Long = System.currentTimeMillis()
)
