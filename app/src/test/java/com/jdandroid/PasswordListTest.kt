package com.jdandroid

import com.jdandroid.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/** The stored password list stays capped even when Click'n'Load pages send arbitrarily many entries. */
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
        // Oldest entries are dropped
        assertEquals("pw-19-50", list.last())
        assertEquals("pw-16-1", list.first())
    }
}
