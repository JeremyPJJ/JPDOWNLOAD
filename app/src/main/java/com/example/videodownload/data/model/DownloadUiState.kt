package com.example.videodownload.data.model

sealed interface DownloadUiState {
    object Idle : DownloadUiState
    data class Loading(val message: String = "Analizando enlace...") : DownloadUiState
    data class InfoLoaded(val videoInfo: VideoInfo) : DownloadUiState
    data class Downloading(val progress: Int, val title: String) : DownloadUiState
    data class Success(val videoInfo: VideoInfo, val filePath: String) : DownloadUiState
    data class Error(val message: String) : DownloadUiState
}
