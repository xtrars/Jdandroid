package com.jdandroid

import com.jdandroid.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/** Die gespeicherte Passwortliste bleibt begrenzt, auch wenn Click'n'Load-Seiten beliebig viele Eintraege schicken. */
class PasswordListTest {

    @Test
    fun anhaengenOhneDuplikateBehaeltReihenfolge() {
        val merged = SettingsRepository.mergePasswords(listOf("a", " b ", ""), listOf("b", "c", " ", "a"))
        assertEquals(listOf("a", "b", "c"), merged)
    }

    @Test
    fun listeWaechstNichtUeberDieObergrenze() {
        val max = SettingsRepository.MAX_STORED_PASSWORDS
        var list = emptyList<String>()
        repeat(20) { round ->
            list = SettingsRepository.mergePasswords(list, (1..50).map { "pw-$round-$it" })
        }
        assertEquals(max, list.size)
        // Die aeltesten Eintraege fallen weg, die neuesten bleiben
        assertEquals("pw-19-50", list.last())
        assertEquals("pw-16-1", list.first())
    }
}
