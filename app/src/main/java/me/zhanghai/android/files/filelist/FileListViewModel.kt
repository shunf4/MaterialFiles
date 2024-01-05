/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.AsyncTask
import android.os.Parcelable
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.map
import java8.nio.charset.StandardCharsets
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.filelist.FileSortOptions.By
import me.zhanghai.android.files.filelist.FileSortOptions.Order
import me.zhanghai.android.files.provider.archive.archiveRefresh
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.common.exists
import me.zhanghai.android.files.provider.common.moveTo
import me.zhanghai.android.files.provider.common.readAllBytes
import me.zhanghai.android.files.provider.common.size
import me.zhanghai.android.files.util.CloseableLiveData
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.fadeIn
import me.zhanghai.android.files.util.valueCompat
import okio.Path.Companion.toPath
import java.io.Closeable
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.concurrent.thread
import java8.nio.file.Files
import java8.nio.file.StandardOpenOption

// TODO: Use SavedStateHandle to save state.
class FileListViewModel : ViewModel() {
    private val trailLiveData = TrailLiveData()
    val hasTrail: Boolean
        get() = trailLiveData.value != null
    val pendingState: Parcelable?
        get() = trailLiveData.valueCompat.pendingState

    fun navigateTo(lastState: Parcelable, path: Path) = trailLiveData.navigateTo(lastState, path)

    fun resetTo(path: Path) = trailLiveData.resetTo(path)

    fun navigateUp(overrideBreadcrumb: Boolean): Boolean =
        if (!overrideBreadcrumb && breadcrumbLiveData.valueCompat.selectedIndex == 0) {
            false
        } else {
            trailLiveData.navigateUp()
        }

    val currentPathLiveData = trailLiveData.map { it.currentPath }
    val currentPath: Path
        get() = currentPathLiveData.valueCompat

    private val _searchStateLiveData = MutableLiveData(SearchState(false, ""))
    val searchStateLiveData: LiveData<SearchState> = _searchStateLiveData
    val searchState: SearchState
        get() = _searchStateLiveData.valueCompat

    private var lastOpenedTimeMap: LinkedHashMap<String, Date>? = null
    private var lastOpenedTimeMapPath: Path? = null

    fun search(query: String) {
        val searchState = _searchStateLiveData.valueCompat
        if (searchState.isSearching && searchState.query == query) {
            return
        }
        _searchStateLiveData.value = SearchState(true, query)
    }

    fun stopSearching() {
        val searchState = _searchStateLiveData.valueCompat
        if (!searchState.isSearching) {
            return
        }
        _searchStateLiveData.value = SearchState(false, "")
    }

    private val _fileListLiveData =
        FileListSwitchMapLiveData(currentPathLiveData, _searchStateLiveData)
    val fileListLiveData: LiveData<Stateful<List<FileItem>>>
        get() = _fileListLiveData
    val fileListStateful: Stateful<List<FileItem>>
        get() = _fileListLiveData.valueCompat

    fun reload() {
        val path = currentPath
        if (path.isArchivePath) {
            path.archiveRefresh()
        }
        GlobalScope.launch(Dispatchers.Main.immediate) {
            loadLastOpenedTimeMap(path)
            _fileListLiveData.reload()
        }
    }

    val searchViewExpandedLiveData = MutableLiveData(false)
    var isSearchViewExpanded: Boolean
        get() = searchViewExpandedLiveData.valueCompat
        set(value) {
            if (searchViewExpandedLiveData.valueCompat == value) {
                return
            }
            searchViewExpandedLiveData.value = value
        }

    private val _searchViewQueryLiveData = MutableLiveData("")
    var searchViewQuery: String
        get() = _searchViewQueryLiveData.valueCompat
        set(value) {
            if (_searchViewQueryLiveData.valueCompat == value) {
                return
            }
            _searchViewQueryLiveData.value = value
        }

    val breadcrumbLiveData: LiveData<BreadcrumbData> = BreadcrumbLiveData(trailLiveData)

    private val _viewTypeLiveData = FileViewTypeLiveData(currentPathLiveData)
    val viewTypeLiveData: LiveData<FileViewType> = _viewTypeLiveData
    var viewType: FileViewType
        get() = _viewTypeLiveData.valueCompat
        set(value) {
            _viewTypeLiveData.putValue(value)
        }

    private val _sortOptionsLiveData = FileSortOptionsLiveData(currentPathLiveData)
    val sortOptionsLiveData: LiveData<FileSortOptions> = _sortOptionsLiveData
    val sortOptions: FileSortOptions
        get() = _sortOptionsLiveData.valueCompat

    fun setSortBy(by: By) = _sortOptionsLiveData.putBy(by)

    fun setSortOrder(order: Order) = _sortOptionsLiveData.putOrder(order)

    fun setSortDirectoriesFirst(isDirectoriesFirst: Boolean) =
        _sortOptionsLiveData.putIsDirectoriesFirst(isDirectoriesFirst)

    private val _viewSortPathSpecificLiveData =
        FileViewSortPathSpecificLiveData(currentPathLiveData)
    val viewSortPathSpecificLiveData: LiveData<Boolean>
        get() = _viewSortPathSpecificLiveData
    var isViewSortPathSpecific: Boolean
        get() = _viewSortPathSpecificLiveData.valueCompat
        set(value) {
            _viewSortPathSpecificLiveData.putValue(value)
        }

    private val _pickOptionsLiveData = MutableLiveData<PickOptions?>()
    val pickOptionsLiveData: LiveData<PickOptions?>
        get() = _pickOptionsLiveData
    var pickOptions: PickOptions?
        get() = _pickOptionsLiveData.value
        set(value) {
            _pickOptionsLiveData.value = value
        }

    private val _selectedFilesLiveData = MutableLiveData(fileItemSetOf())
    val selectedFilesLiveData: LiveData<FileItemSet>
        get() = _selectedFilesLiveData
    val selectedFiles: FileItemSet
        get() = _selectedFilesLiveData.valueCompat

    fun selectFile(file: FileItem, selected: Boolean) {
        selectFiles(fileItemSetOf(file), selected)
    }

    fun selectFiles(files: FileItemSet, selected: Boolean) {
        val selectedFiles = _selectedFilesLiveData.valueCompat
        if (selectedFiles === files) {
            if (!selected && selectedFiles.isNotEmpty()) {
                selectedFiles.clear()
                _selectedFilesLiveData.value = selectedFiles
            }
            return
        }
        var changed = false
        for (file in files) {
            changed = changed or if (selected) {
                selectedFiles.add(file)
            } else {
                selectedFiles.remove(file)
            }
        }
        if (changed) {
            _selectedFilesLiveData.value = selectedFiles
        }
    }

    fun replaceSelectedFiles(files: FileItemSet) {
        val selectedFiles = _selectedFilesLiveData.valueCompat
        if (selectedFiles == files) {
            return
        }
        selectedFiles.clear()
        selectedFiles.addAll(files)
        _selectedFilesLiveData.value = selectedFiles
    }

    fun clearSelectedFiles() {
        val selectedFiles = _selectedFilesLiveData.valueCompat
        if (selectedFiles.isEmpty()) {
            return
        }
        selectedFiles.clear()
        _selectedFilesLiveData.value = selectedFiles
    }

    val pasteStateLiveData: LiveData<PasteState> = _pasteStateLiveData
    val pasteState: PasteState
        get() = _pasteStateLiveData.valueCompat

    fun addToPasteState(copy: Boolean, files: FileItemSet) {
        val pasteState = _pasteStateLiveData.valueCompat
        var changed = false
        if (pasteState.copy != copy) {
            changed = pasteState.files.isNotEmpty()
            pasteState.files.clear()
            pasteState.copy = copy
        }
        changed = changed or pasteState.files.addAll(files)
        if (changed) {
            _pasteStateLiveData.value = pasteState
        }
    }

    fun clearPasteState() {
        val pasteState = _pasteStateLiveData.valueCompat
        if (pasteState.files.isEmpty()) {
            return
        }
        pasteState.files.clear()
        _pasteStateLiveData.value = pasteState
    }

    suspend fun loadLastOpenedTimeMap(path: Path) = suspendCoroutine { continuation ->
        thread(start = true, isDaemon = false) {
            lastOpenedTimeMapPath =
                (path.resolve("RIV_FILE_LAST_OPENED_TIME_MAP.txt").let { mp ->
                    if (!mp.exists()) {
                        return@let null
                    } else {
                        mp.normalize()
                    }
                } ?: run {
                    path.resolve("../" + "RIV_FILE_LAST_OPENED_TIME_MAP.txt").let { mp ->
                        if (!mp.exists()) {
                            return@let null
                        } else {
                            mp.normalize()
                        }
                    }
                } ?: run {
                    path.resolve("../../RIV_FILE_LAST_OPENED_TIME_MAP.txt").let { mp ->
                        if (!mp.exists()) {
                            return@let null
                        } else {
                            mp.normalize()
                        }
                    }
                })

            lastOpenedTimeMap = try { lastOpenedTimeMapPath?.let { mp ->
                val zeroDate = Date(0)
                Log.i(javaClass.simpleName, "reading lastOpenedTimeMap " + mp.toString())
                val result = mp.readAllBytes()
                    .toString(StandardCharsets.UTF_8)
                    .split("\n")
                    .map {
                        val splitted = it.trim().split("     ")
                        splitted.getOrElse(1, { _ -> null}) to try {
                            splitted.getOrNull(0)?.let {
                                lastOpenedTimeMapDf.parse(it)
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }
                    .mapNotNull {
                        if (it.first != null && it.second != null) {
                            (it.first to it.second) as Pair<String, Date>
                        } else {
                            null
                        }
                    }
                    .toMap(LinkedHashMap())
                Log.i(javaClass.simpleName, "done reading lastOpenedTimeMap")
                result
            } } catch (e: Exception) {
                Log.w(javaClass.simpleName, "reading lastOpenedTimeMap fail " + Log.getStackTraceString(e))
                null
            }
            continuation.resume(Unit)
        }
    }

    fun updateLastOpenedTimeMapIfExist(filePath: Path, newOpenedDate: Date) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                loadLastOpenedTimeMap(currentPath)
                lastOpenedTimeMap?.let { lastOpenedTimeMap ->
                    Log.i(
                        javaClass.simpleName,
                        "writing lastOpenedTimeMap " + lastOpenedTimeMapPath!!.name
                    )
                    val key = /* if (lastOpenedTimeMapPath?.parent?.equals(currentPath) == true) {
                        filePath.name
                    } else { */
                        lastOpenedTimeMapPath!!.parent.relativize(filePath).toString()
                    /* } */
                    if (!(lastOpenedTimeMap[key] ?: FileSortOptions.LAST_OPENED_DATE_NULL_BASE).before(FileSortOptions.LAST_OPENED_DATE_NULL_BASE)) {
                        lastOpenedTimeMap[key] = newOpenedDate
                    }
                    val tempFilePath = lastOpenedTimeMapPath!!.resolveSibling(
                        lastOpenedTimeMapPath!!.name.removeSuffix(".txt") + ("." + lastOpenedTimeMapDf.format(
                            Date()
                        ).replace("-", "").replace(" ", "").replace(":", "") + ".txt")
                    )
                    Files.newOutputStream(tempFilePath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).apply {
                        write(
                            lastOpenedTimeMap.map {
                                lastOpenedTimeMapDf.format(it.value) + "     " + it.key
                            }.joinToString("\n").toByteArray(StandardCharsets.UTF_8)
                        )
                        close()
                    }

                    tempFilePath.moveTo(
                        lastOpenedTimeMapPath!!,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    Log.i(javaClass.simpleName, "done writing lastOpenedTimeMap")
                }
            } catch (e: Exception) {
                Log.w(
                    javaClass.simpleName,
                    "writing lastOpenedTimeMap fail " + Log.getStackTraceString(e)
                )
            }
        }
    }

    private val _isRequestingStorageAccessLiveData = MutableLiveData(false)
    var isStorageAccessRequested: Boolean
        get() = _isRequestingStorageAccessLiveData.valueCompat
        set(value) {
            _isRequestingStorageAccessLiveData.value = value
        }

    private val _isRequestingNotificationPermissionLiveData = MutableLiveData(false)
    var isNotificationPermissionRequested: Boolean
        get() = _isRequestingNotificationPermissionLiveData.valueCompat
        set(value) {
            _isRequestingNotificationPermissionLiveData.value = value
        }

    override fun onCleared() {
        Log.i(javaClass.simpleName, "onCleared: setting lastOpenedTimeMap to null")
        lastOpenedTimeMap = null
        _fileListLiveData.close()
    }

    companion object {
        private val _pasteStateLiveData = MutableLiveData(PasteState())
        val lastOpenedTimeMapDf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    }

    private inner class FileListSwitchMapLiveData(
        private val pathLiveData: LiveData<Path>,
        private val searchStateLiveData: LiveData<SearchState>
    ) : MediatorLiveData<Stateful<List<FileItem>>>(), Closeable {
        private var liveData: CloseableLiveData<Stateful<List<FileItem>>>? = null

        init {
            addSource(pathLiveData) { updateSource() }
            addSource(searchStateLiveData) { updateSource() }
        }

        private fun updateSource() {
            liveData?.let {
                removeSource(it)
                it.close()
            }
            val path = pathLiveData.valueCompat
            val searchState = searchStateLiveData.valueCompat

            val liveData = if (searchState.isSearching) {
                SearchFileListLiveData(path, searchState.query)
            } else {
                FileListLiveData(path, {
                    if (lastOpenedTimeMap == null) {
                        runBlocking {
                            loadLastOpenedTimeMap(path)
                        }
                    }
                    lastOpenedTimeMap
                }, {
                    if (lastOpenedTimeMapPath == null) {
                        runBlocking {
                            loadLastOpenedTimeMap(path)
                        }
                    }
                    lastOpenedTimeMapPath
                })
            }
            this@FileListSwitchMapLiveData.liveData = liveData
            addSource(liveData) { value = it }

        }

        fun reload() {
            when (val liveData = liveData) {
                is FileListLiveData -> liveData.loadValue()
                is SearchFileListLiveData -> liveData.loadValue()
            }
        }

        override fun close() {
            liveData?.let {
                removeSource(it)
                it.close()
                this.liveData = null
            }
        }
    }
}
