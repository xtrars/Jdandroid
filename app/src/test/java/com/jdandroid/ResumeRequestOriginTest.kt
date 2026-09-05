package com.jdandroid

import com.jdandroid.ui.isOwnAppReferrer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only the app's own PendingIntents may trigger "resume all" via the exported activity. */
class ResumeRequestOriginTest {

    @Test
    fun eigenesPaketAlsReferrerWirdAngenommen() {
        assertTrue(isOwnAppReferrer("android-app://com.jdandroid", "com.jdandroid"))
    }

    @Test
    fun fremdesPaketWirdAbgelehnt() {
        assertFalse(isOwnAppReferrer("android-app://com.evil", "com.jdandroid"))
    }

    @Test
    fun ohneReferrerWirdAbgelehnt() {
        assertFalse(isOwnAppReferrer(null, "com.jdandroid"))
    }

    @Test
    fun andererSchemaOderPraefixWirdAbgelehnt() {
        assertFalse(isOwnAppReferrer("https://com.jdandroid", "com.jdandroid"))
        assertFalse(isOwnAppReferrer("android-app://com.jdandroid.evil", "com.jdandroid"))
    }
}
