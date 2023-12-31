/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.compat.reversedCompat
import me.zhanghai.android.files.file.FileItem
import java.util.Date

@Parcelize
data class FileSortOptions(
    val by: By,
    val order: Order,
    val isDirectoriesFirst: Boolean
) : Parcelable {
    fun createComparator(): Comparator<FileItem> {
        var comparator = compareBy<FileItem> {
            NAME_UNIMPORTANT_PREFIXES.any { prefix -> it.name.startsWith(prefix) }
        }.thenBy { it.nameCollationKey }
        when (by) {
            // Nothing to do.
            By.NAME -> {}
            By.TYPE ->
                comparator = compareBy<FileItem, String>(String.CASE_INSENSITIVE_ORDER) {
                    it.extension
                }.then(comparator)
            By.SIZE -> comparator = compareBy<FileItem> { it.attributes.size() }.then(comparator)
            By.LAST_MODIFIED ->
                comparator = compareBy<FileItem> { it.attributes.lastModifiedTime() }
                    .then(comparator)
        }
        if (by == By.LAST_MODIFIED && order == Order.ASCENDING) {
            comparator = compareBy<FileItem> { it.lastOpenedTime ?: LAST_OPENED_DATE_NULL_BASE }.reversedCompat().then(comparator.reversedCompat())
        }
        when (order) {
            Order.ASCENDING -> {}
            Order.DESCENDING -> comparator = comparator.reversedCompat()
        }
        if (isDirectoriesFirst) {
            val isDirectoryComparator = compareBy<FileItem> { it.attributes.isDirectory }
                .reversedCompat()
            comparator = isDirectoryComparator.then(comparator)
        }
        return comparator
    }

    companion object {
        // Same behavior as Nautilus.
        private val NAME_UNIMPORTANT_PREFIXES = listOf(".", "#")

        private val LAST_OPENED_DATE_NULL_BASE = Date(2000-1900, 1, 1, 0, 0, 0)
    }

    enum class By {
        NAME,
        TYPE,
        SIZE,
        LAST_MODIFIED
    }

    enum class Order {
        ASCENDING,
        DESCENDING
    }
}
