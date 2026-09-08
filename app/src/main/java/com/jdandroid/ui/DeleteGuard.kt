package com.jdandroid.ui

import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadStatus

/**
 * Why an entry or package cannot be deleted right now. Extraction and the
 * move to the NAS run to completion regardless of the row, so deleting
 * meanwhile would only leave orphaned files or a surprise on the NAS.
 */
internal enum class DeleteBlock { EXTRACTING, UPLOADING }

internal object DeleteGuard {
    fun forItem(item: DownloadItem, uploadPercent: Int): DeleteBlock? = when {
        uploadPercent >= 0 -> DeleteBlock.UPLOADING
        item.status == DownloadStatus.EXTRACTING -> DeleteBlock.EXTRACTING
        else -> null
    }

    fun forGroup(group: DownloadGroup): DeleteBlock? = when {
        group.uploading -> DeleteBlock.UPLOADING
        group.extracting -> DeleteBlock.EXTRACTING
        else -> null
    }
}
