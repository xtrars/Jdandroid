package com.jdandroid.engine

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
