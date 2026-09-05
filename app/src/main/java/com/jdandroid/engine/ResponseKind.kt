package com.jdandroid.engine

import com.jdandroid.core.Texts
import com.jdandroid.core.formatBytes
import com.jdandroid.hoster.HosterException

/** Classification of the server response to a (range) download request. */
internal enum class ResponseKind {
    /** 416 and the part file matches the known size: already complete. */
    AlreadyComplete,
    /** 416 but the part file is too long (foreign content): delete and reload. */
    RestartMismatch,
    /** Not 2xx. */
    HttpError,
    /** HTML instead of the file (expired link, session page): never store it. */
    HtmlPage,
    /** Server ignored the Range header (200 instead of 206): start over. */
    RangeIgnored,
    /** Continue transferring. */
    Continue
}

/**
 * Pure classification so the cases are testable without OkHttp or the database.
 * [offset] is the size of the existing part file, [knownSize] the known file
 * size or <= 0.
 */
internal fun classifyResponse(
    code: Int,
    contentType: String?,
    disposition: String?,
    offset: Long,
    knownSize: Long
): ResponseKind {
    if (code == 416 && offset > 0) {
        return if (knownSize > 0 && offset != knownSize) ResponseKind.RestartMismatch
        else ResponseKind.AlreadyComplete
    }
    if (code !in 200..299) return ResponseKind.HttpError
    val type = contentType.orEmpty().lowercase()
    val cd = disposition.orEmpty().lowercase()
    if ((type.startsWith("text/html") || type.startsWith("application/xhtml")) && !cd.startsWith("attachment")) {
        return ResponseKind.HtmlPage
    }
    if (offset > 0 && code != 206) return ResponseKind.RangeIgnored
    return ResponseKind.Continue
}

/**
 * Total length of the file for a body of [contentLength] (-1 = unknown) that
 * continues [offset] bytes already on disk; [knownSize] <= 0 when unknown.
 * An empty body or a length off from the known size by more than a factor of
 * two cannot be the file (error text, wrong link) and fails the attempt, so
 * the link is resolved again. Known sizes may stem from rounded page text
 * ("1.38 GB", base 1000 or 1024), hence the wide tolerance.
 */
internal fun transferTotal(contentLength: Long, offset: Long, knownSize: Long): Long {
    if (contentLength == 0L) throw HosterException(Texts.t("engine_empty_response"))
    val total = if (contentLength >= 0) contentLength + offset else knownSize
    if (knownSize > 0 && total > 0 && (total < knownSize / 2 || total > knownSize * 2)) {
        throw HosterException(Texts.t("engine_size_mismatch", formatBytes(total), formatBytes(knownSize)))
    }
    return total
}
