package com.jdandroid

import com.jdandroid.core.AppMessage
import com.jdandroid.core.AppMessages
import com.jdandroid.core.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The last message posted without a UI is kept until it is shown. */
class AppMessagesTest {

    @Before
    fun leeren() = AppMessages.markShown()

    @Test
    fun letzteMeldungBleibtBisZurAnzeigeErhalten() {
        AppMessages.error("Download-Dienst: Speicher voll")
        assertEquals(
            listOf(AppMessage("Download-Dienst: Speicher voll", MessageKind.ERROR)),
            AppMessages.events.replayCache
        )
        AppMessages.markShown()
        assertTrue(AppMessages.events.replayCache.isEmpty())
    }

    @Test
    fun artenWerdenZugeordnet() {
        AppMessages.progress("DLC wird entschlüsselt …")
        assertEquals(MessageKind.PROGRESS, AppMessages.events.replayCache.single().kind)
        AppMessages.success("3 Links übernommen")
        assertEquals(MessageKind.SUCCESS, AppMessages.events.replayCache.single().kind)
        AppMessages.info("Keine neuen Links")
        assertEquals(MessageKind.INFO, AppMessages.events.replayCache.single().kind)
    }
}
