package app.marlboroadvance.mpvex.ui.browser.folderlist

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.marlboroadvance.mpvex.database.entities.PlaylistEntity
import app.marlboroadvance.mpvex.database.repository.PlaylistRepository
import app.marlboroadvance.mpvex.domain.media.model.Video
import app.marlboroadvance.mpvex.domain.media.model.VideoFolder
import app.marlboroadvance.mpvex.domain.playbackstate.repository.PlaybackStateRepository
import app.marlboroadvance.mpvex.preferences.AppearancePreferences
import app.marlboroadvance.mpvex.preferences.FoldersPreferences
import app.marlboroadvance.mpvex.ui.browser.base.BaseBrowserViewModel
import app.marlboroadvance.mpvex.ui.browser.playlist.PlaylistWithCount
import app.marlboroadvance.mpvex.utils.media.MediaLibraryEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

data class FolderWithNewCount(
  val folder: VideoFolder,
  val newVideoCount: Int = 0,
)

class FolderListViewModel(
  application: Application,
) : BaseBrowserViewModel(application),
  KoinComponent {
  private val foldersPreferences: FoldersPreferences by inject()
  private val appearancePreferences: AppearancePreferences by inject()
  private val playbackStateRepository: PlaybackStateRepository by inject()
  private val playlistRepository: PlaylistRepository by inject()

  private val _allVideoFolders = MutableStateFlow<List<VideoFolder>>(emptyList())
  private val _videoFolders = MutableStateFlow<List<VideoFolder>>(emptyList())
  val videoFolders: StateFlow<List<VideoFolder>> = _videoFolders.asStateFlow()

  private val _userPlaylists = MutableStateFlow<List<PlaylistWithCount>>(emptyList())
  val userPlaylists: StateFlow<List<PlaylistWithCount>> = _userPlaylists.asStateFlow()

  private val _foldersWithNewCount = MutableStateFlow<List<FolderWithNewCount>>(emptyList())
  val foldersWithNewCount: StateFlow<List<FolderWithNewCount>> = _foldersWithNewCount.asStateFlow()

  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  private val _hasCompletedInitialLoad = MutableStateFlow(false)
  val hasCompletedInitialLoad: StateFlow<Boolean> = _hasCompletedInitialLoad.asStateFlow()

  private val _foldersWereDeleted = MutableStateFlow(false)
  val foldersWereDeleted: StateFlow<Boolean> = _foldersWereDeleted.asStateFlow()

  private var previousFolderCount = 0

  companion object {
    private const val TAG = "FolderListViewModel"

    fun factory(application: Application) =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = FolderListViewModel(application) as T
      }
  }

  init {
    loadCachedFolders()
    loadVideoFolders()
    observeUserPlaylists()

    viewModelScope.launch(Dispatchers.IO) {
      MediaLibraryEvents.changes.collectLatest {
        app.marlboroadvance.mpvex.repository.MediaFileRepository.clearCache()
        loadVideoFolders()
      }
    }

    viewModelScope.launch {
      combine(_allVideoFolders, foldersPreferences.blacklistedFolders.changes()) { folders, blacklist ->
        folders.filter { folder -> folder.path !in blacklist }
      }.collectLatest { filteredFolders ->
        if (previousFolderCount > 0 && filteredFolders.isEmpty()) {
          _foldersWereDeleted.value = true
        } else if (filteredFolders.isNotEmpty()) {
          _foldersWereDeleted.value = false
        }
        previousFolderCount = filteredFolders.size
        _videoFolders.value = filteredFolders
        calculateNewVideoCounts(filteredFolders)
        saveFoldersToCache(_allVideoFolders.value)
      }
    }
  }

  private fun observeUserPlaylists() {
    viewModelScope.launch(Dispatchers.IO) {
      playlistRepository.observeAllPlaylists().collectLatest { playlistsFromDb ->
        val sortedPlaylists = playlistsFromDb.sortedBy { it.name.lowercase() }
        val playlistsWithCounts = sortedPlaylists.map { playlist ->
          val count = getActualVideoCount(playlist.id)
          PlaylistWithCount(playlist, count)
        }
        _userPlaylists.value = playlistsWithCounts
      }
    }
  }

  private suspend fun getActualVideoCount(playlistId: Int): Int {
    val playlist = playlistRepository.getPlaylistById(playlistId)
    val items = playlistRepository.getPlaylistItems(playlistId)
    if (items.isEmpty()) return 0
    if (playlist?.isM3uPlaylist == true) return items.size
    
    val bucketIds = items.map { item ->
      File(item.filePath).parent ?: ""
    }.toSet()
    val allVideos = app.marlboroadvance.mpvex.repository.MediaFileRepository.getVideosForBuckets(getApplication(), bucketIds)
    return items.count { item ->
      allVideos.any { video -> video.path == item.filePath }
    }
  }

  fun deleteUserPlaylist(playlist: PlaylistEntity) {
    viewModelScope.launch(Dispatchers.IO) {
      playlistRepository.deletePlaylist(playlist)
    }
  }

  private fun loadCachedFolders() {
    val prefs = getApplication<Application>().getSharedPreferences("folder_cache", android.content.Context.MODE_PRIVATE)
    val cachedJson = prefs.getString("folders", null)
    if (cachedJson != null) {
      try {
        val folders = parseFoldersFromJson(cachedJson)
        if (folders.isNotEmpty()) {
          _allVideoFolders.value = folders
          _hasCompletedInitialLoad.value = true
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error loading cached folders", e)
      }
    }
  }

  private fun saveFoldersToCache(folders: List<VideoFolder>) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val prefs = getApplication<Application>().getSharedPreferences("folder_cache", android.content.Context.MODE_PRIVATE)
        val json = serializeFoldersToJson(folders)
        prefs.edit().putString("folders", json).apply()
      } catch (e: Exception) {
        Log.e(TAG, "Error saving folders to cache", e)
      }
    }
  }

  private fun serializeFoldersToJson(folders: List<VideoFolder>): String {
    return folders.joinToString(separator = "|") { folder ->
      "${folder.bucketId}::${folder.name}::${folder.path}::${folder.videoCount}::${folder.totalSize}::${folder.totalDuration}::${folder.lastModified}"
    }
  }

  private fun parseFoldersFromJson(json: String): List<VideoFolder> {
    return try {
      json.split("|").mapNotNull { item ->
        val parts = item.split("::")
        if (parts.size == 7) {
          VideoFolder(
            bucketId = parts[0],
            name = parts[1],
            path = parts[2],
            videoCount = parts[3].toIntOrNull() ?: 0,
            totalSize = parts[4].toLongOrNull() ?: 0L,
            totalDuration = parts[5].toLongOrNull() ?: 0L,
            lastModified = parts[6].toLongOrNull() ?: 0L,
          )
        } else null
      }
    } catch (e: Exception) {
      emptyList()
    }
  }

  private fun calculateNewVideoCounts(folders: List<VideoFolder>) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val showLabel = appearancePreferences.showUnplayedOldVideoLabel.get()
        if (!showLabel) {
          _foldersWithNewCount.value = folders.map { FolderWithNewCount(it, 0) }
          return@launch
        }
        val thresholdDays = appearancePreferences.unplayedOldVideoDays.get()
        val thresholdMillis = thresholdDays * 24 * 60 * 60 * 1000L
        val currentTime = System.currentTimeMillis()
        val foldersWithCounts = folders.map { folder ->
          try {
            val videos = app.marlboroadvance.mpvex.repository.MediaFileRepository.getVideosInFolder(getApplication(), folder.bucketId)
            val newCount = videos.count { video ->
              val videoAge = currentTime - (video.dateAdded * 1000)
              val isRecent = videoAge <= thresholdMillis
              val playbackState = playbackStateRepository.getVideoDataByTitle(video.displayName)
              isRecent && playbackState == null
            }
            FolderWithNewCount(folder, newCount)
          } catch (e: Exception) {
            FolderWithNewCount(folder, 0)
          }
        }
        _foldersWithNewCount.value = foldersWithCounts
      } catch (e: Exception) {
        _foldersWithNewCount.value = folders.map { FolderWithNewCount(it, 0) }
      }
    }
  }

  override fun refresh() {
    app.marlboroadvance.mpvex.repository.MediaFileRepository.clearCache()
    loadVideoFolders()
  }

  fun recalculateNewVideoCounts() {
    calculateNewVideoCounts(_videoFolders.value)
  }

  private fun loadVideoFolders() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        if (_allVideoFolders.value.isEmpty()) {
          _isLoading.value = true
        }
        val showHiddenFiles = appearancePreferences.showHiddenFiles.get()
        val videoFolders = app.marlboroadvance.mpvex.repository.MediaFileRepository
          .getAllVideoFolders(
            context = getApplication(),
            showHiddenFiles = showHiddenFiles,
          )
        _allVideoFolders.value = videoFolders
        _hasCompletedInitialLoad.value = true
      } catch (e: Exception) {
        _allVideoFolders.value = emptyList()
        _hasCompletedInitialLoad.value = true
      } finally {
        _isLoading.value = false
      }
    }
  }

  override suspend fun deleteVideos(videos: List<Video>): Pair<Int, Int> {
    val result = super.deleteVideos(videos)
    refresh()
    return result
  }
}
