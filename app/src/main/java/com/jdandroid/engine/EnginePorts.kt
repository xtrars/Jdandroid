package com.jdandroid.engine

import com.jdandroid.data.DownloadItem
import com.jdandroid.hoster.CaptchaRequiredException
import com.jdandroid.hoster.Hoster
import com.jdandroid.hoster.ResolvedLink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import java.io.File

/** Settings the engine reads; adapted from [com.jdandroid.data.SettingsRepository] in [DownloadEngine.create]. */
internal interface EngineSettings {
    suspend fun maxConcurrent(): Int
    suspend fun wifiOnly(): Boolean
    val speedLimitMbit: Flow<Double>
}

/** True when [wifiOnly] is set and the active network is metered or unknown. */
internal fun interface NetworkPolicy {
    fun blocked(wifiOnly: Boolean): Boolean
}

/** Where downloads are written and exported copies removed; [StorageTarget] implements it. */
internal interface DownloadFiles {
    fun downloadDir(): File
    suspend fun deleteExported(displayPath: String): Boolean
}

/** The finishing side of a download: archive sets, extraction and export; [ArchiveCoordinator] implements it. */
internal interface Completion {
    val completionMutex: Mutex
    val activeCount: Int
    val exportingIds: List<Long>
    fun archiveDir(packageId: Long?): File
    fun markReplaceOnExtract(packageId: Long?, base: String)
    suspend fun completeDownload(id: Long, temp: File, originalName: String)
    suspend fun retryWaitingSets(packageId: Long?)
    suspend fun extractNow(id: Long): String?
}

/** Uploads of exports left local; [ExportRetry] implements it. */
internal interface ExportRetries {
    val activeCount: Int
    suspend fun run(forced: Boolean): Boolean
}

/** Premium or free resolution with its waits; [FreeFlow] implements it. */
internal interface LinkResolver {
    suspend fun resolve(id: Long, item: DownloadItem, hoster: Hoster): ResolvedLink
    suspend fun scheduleWait(id: Long, seconds: Int, reason: String?)
    suspend fun holdForCaptcha(id: Long, e: CaptchaRequiredException)
}

/** Package rows the engine changes besides the downloads. */
internal interface PackageStore {
    suspend fun refineAutoName(packageId: Long?)

    /** Removes the package and its download rows in one transaction. */
    suspend fun deletePackage(packageId: Long)
}
