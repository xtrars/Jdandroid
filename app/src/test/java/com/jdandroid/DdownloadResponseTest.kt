package com.jdandroid

import com.jdandroid.hoster.DdownloadHoster
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Non-text responses (e.g. the file itself after a redirect) must never be
 * read into memory as a string (OutOfMemoryError).
 */
class DdownloadResponseTest {

    private val hoster = DdownloadHoster()

    @Test
    fun textantwortenWerdenGelesen() {
        assertTrue(hoster.isTextual("text/html; charset=UTF-8"))
        assertTrue(hoster.isTextual("application/json"))
        assertTrue(hoster.isTextual("application/xml"))
        assertTrue(hoster.isTextual(null))
    }

    @Test
    fun dateiantwortenWerdenNichtGelesen() {
        assertFalse(hoster.isTextual("application/octet-stream"))
        assertFalse(hoster.isTextual("video/x-matroska"))
        assertFalse(hoster.isTextual("application/x-rar-compressed"))
        assertFalse(hoster.isTextual("application/zip"))
    }
}
