package com.example.videodownload.ui

import android.app.Application
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.videodownload.data.model.DownloadUiState
import com.example.videodownload.data.model.DownloadedItem
import com.example.videodownload.data.model.Platform
import com.example.videodownload.data.model.VideoInfo
import com.example.videodownload.data.repository.VideoExtractorRepository
import com.example.videodownload.downloader.AndroidVideoDownloader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VideoExtractorRepository()
    private val downloader = AndroidVideoDownloader(application)

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _uiState = MutableStateFlow<DownloadUiState>(DownloadUiState.Idle)
    val uiState: StateFlow<DownloadUiState> = _uiState.asStateFlow()

    private val _detectedPlatform = MutableStateFlow(Platform.UNKNOWN)
    val detectedPlatform: StateFlow<Platform> = _detectedPlatform.asStateFlow()

    private val _historyList = MutableStateFlow<List<DownloadedItem>>(emptyList())
    val historyList: StateFlow<List<DownloadedItem>> = _historyList.asStateFlow()

    fun onUrlInputChanged(newUrl: String) {
        _urlInput.value = newUrl
        _detectedPlatform.value = repository.detectPlatform(newUrl)
        if (newUrl.isBlank()) {
            _uiState.value = DownloadUiState.Idle
        }
    }

    fun pasteFromClipboard() {
        val context = getApplication<Application>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: ""
            if (text.isNotBlank()) {
                onUrlInputChanged(text)
                fetchVideoInfo()
            }
        }
    }

    fun handleSharedUrl(sharedText: String) {
        if (sharedText.isNotBlank()) {
            onUrlInputChanged(sharedText)
            fetchVideoInfo()
        }
    }

    fun fetchVideoInfo() {
        val url = _urlInput.value.trim()
        if (url.isBlank()) {
            _uiState.value = DownloadUiState.Error("Ingresa o pega un enlace de video válido.")
            return
        }

        viewModelScope.launch {
            _uiState.value = DownloadUiState.Loading("Analizando enlace y obteniendo video...")
            val result = repository.extractVideoInfo(url)

            result.onSuccess { info ->
                _uiState.value = DownloadUiState.InfoLoaded(info)
            }.onFailure { exception ->
                _uiState.value = DownloadUiState.Error(
                    exception.localizedMessage ?: "No se pudo procesar el enlace. Revisa que sea público."
                )
            }
        }
    }

    fun downloadVideo(videoInfo: VideoInfo) {
        viewModelScope.launch {
            try {
                _uiState.value = DownloadUiState.Downloading(0, videoInfo.title)
                val downloadId = downloader.downloadVideo(videoInfo)

                var isComplete = false
                while (!isComplete) {
                    delay(800)
                    val (status, progress) = downloader.getDownloadStatus(downloadId)

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            isComplete = true
                            _uiState.value = DownloadUiState.Success(
                                videoInfo = videoInfo,
                                filePath = "Movies/VideoDownload/"
                            )

                            // Agregar al historial de la app
                            val newItem = DownloadedItem(
                                id = videoInfo.id,
                                title = videoInfo.title,
                                platform = videoInfo.platform,
                                localUri = videoInfo.downloadUrl
                            )
                            _historyList.value = listOf(newItem) + _historyList.value
                        }
                        DownloadManager.STATUS_FAILED -> {
                            isComplete = true
                            _uiState.value = DownloadUiState.Error("La descarga falló. Intenta de nuevo.")
                        }
                        else -> {
                            _uiState.value = DownloadUiState.Downloading(
                                progress = if (progress > 0) progress else 25,
                                title = videoInfo.title
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.value = DownloadUiState.Error("Error iniciando la descarga: ${e.localizedMessage}")
            }
        }
    }

    fun resetState() {
        _uiState.value = DownloadUiState.Idle
        _urlInput.value = ""
        _detectedPlatform.value = Platform.UNKNOWN
    }
}
