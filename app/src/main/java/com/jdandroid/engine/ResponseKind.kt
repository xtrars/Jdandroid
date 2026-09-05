package com.jdandroid.engine

import com.jdandroid.core.Texts
import com.jdandroid.core.formatBytes
import com.jdandroid.hoster.HosterException

/** Einordnung der Server-Antwort auf eine (Range-)Download-Anfrage. */
internal enum class ResponseKind {
    /** 416 und Teildatei passt zur bekannten Groesse: war schon vollstaendig. */
    AlreadyComplete,
    /** 416, Teildatei aber zu lang (Fremdinhalt): loeschen und neu laden. */
    RestartMismatch,
    /** Kein 2xx. */
    HttpError,
    /** HTML statt Datei (abgelaufener Link, Sitzungsseite): nie speichern. */
    HtmlPage,
    /** Server ignoriert Range (200 statt 206): von vorn beginnen. */
    RangeIgnored,
    /** Normal weiterladen. */
    Continue
}

/**
 * Reine Einordnung, damit die Faelle ohne OkHttp/DB testbar sind.
 * [offset] = bereits vorhandene Bytes der Teildatei, [knownSize] = bekannte
 * Dateigroesse oder <= 0.
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
