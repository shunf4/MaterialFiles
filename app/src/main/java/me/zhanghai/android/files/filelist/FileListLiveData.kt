/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.AsyncTask
import android.util.Log
import androidx.core.util.Supplier
import java8.nio.file.DirectoryIteratorException
import java8.nio.file.Path
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.util.CloseableLiveData
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.valueCompat
import java.io.IOException
import java.util.Date
import java.util.TreeMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import kotlin.io.path.Path

class FileListLiveData(private val path: Path, private val lastOpenedTimeMapSupplier: Supplier<Map<String, Date>?>, private val lastOpenedTimeMapFilePathSupplier: Supplier<Path?>) : CloseableLiveData<Stateful<List<FileItem>>>() {
    private var future: Future<Unit>? = null

    private val observer: PathObserver

    @Volatile
    private var isChangedWhileInactive = false

    init {
        loadValue()
        observer = PathObserver(path) { onChangeObserved() }
    }

    fun loadValue() {
        future?.cancel(true)
        value = Loading(value?.value)
        future = (AsyncTask.THREAD_POOL_EXECUTOR as ExecutorService).submit<Unit> {
            Log.i(javaClass.simpleName, "fileListLiveData.loadValue")
            val lastOpenedTimeMap = lastOpenedTimeMapSupplier.get()
            val lastOpenedTimeMapFilePath = lastOpenedTimeMapFilePathSupplier.get()
            val value = try {
                path.newDirectoryStream().use { directoryStream ->
                    val fileList = mutableListOf<FileItem>()
                    for (path in directoryStream) {
                        try {
                            fileList.add(path.loadFileItem().apply {
                                lastOpenedTime = null
                                lastOpenedTimeMap?.let b1@{
                                    val relPathToMapFilePath = if (lastOpenedTimeMapFilePath?.parent?.equals(this@FileListLiveData.path) == true) {
                                        path.name
                                    } else {
                                        lastOpenedTimeMapFilePath!!.parent.relativize(path).toString()
                                    }
                                    lastOpenedTimeMap[relPathToMapFilePath]?.let {
                                        lastOpenedTime = it
                                        lastOpenedFilesForDir = ""
                                        return@b1
                                    }

                                    val relPathToMapFilePathSlash = relPathToMapFilePath + "/"
                                    val lastOpenedTimeSortedMap = TreeMap<Date, String>()
                                    lastOpenedTimeMap.forEach { (iterRelPath, lastOpenedTime) ->
                                        if (iterRelPath.startsWith(relPathToMapFilePathSlash)) {
                                            lastOpenedTimeSortedMap.put(lastOpenedTime, iterRelPath.removePrefix(relPathToMapFilePathSlash))
                                        }
                                    }
                                    lastOpenedTime = lastOpenedTimeSortedMap.lastEntry()?.key
                                    lastOpenedFilesForDir = lastOpenedTimeSortedMap.descendingMap().values.take(3).joinToString(", ").let {
                                        if (it.isBlank()) {
                                            ""
                                        } else {
                                            "Last: " + it
                                        }
                                    }
                                }
                            })
                        } catch (e: DirectoryIteratorException) {
                            // TODO: Ignoring such a file can be misleading and we need to support
                            //  files without information.
                            e.printStackTrace()
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                    }
                    Success(fileList as List<FileItem>)
                }
            } catch (e: Exception) {
                Failure(valueCompat.value, e)
            }
            postValue(value)
        }
    }

    private fun onChangeObserved() {
        if (hasActiveObservers()) {
            loadValue()
        } else {
            isChangedWhileInactive = true
        }
    }

    override fun onActive() {
        if (isChangedWhileInactive) {
            loadValue()
            isChangedWhileInactive = false
        }
    }

    override fun close() {
        observer.close()
        future?.cancel(true)
    }
}
