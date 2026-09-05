package com.jdandroid

import com.jdandroid.data.DownloadPackage
import com.jdandroid.data.PackageNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageNamingTest {

    @Test
    fun gemeinsamerNamensteilMehrteiligerArchive() {
        val name = PackageNaming.commonName(
            listOf("scn-smps8.part1.rar", "scn-smps8.part2.rar", "scn-smps8.part3.rar")
        )
        assertEquals("scn-smps8", name)
    }

    @Test
    fun einzelneDateiOhneArchivendung() {
        assertEquals("film", PackageNaming.commonName(listOf("film.rar")))
        assertEquals("film", PackageNaming.commonName(listOf("film.part1.rar")))
    }

    @Test
    fun keinGemeinsamerTeilLiefertNull() {
        assertNull(PackageNaming.commonName(listOf("abc.rar", "xyz.zip")))
        assertNull(PackageNaming.commonName(emptyList()))
    }

    @Test
    fun vorschlagAusLinksMitDateinamen() {
        val name = PackageNaming.suggestFromUrls(
            listOf(
                "https://ddownload.com/abc123/serie-S01.part1.rar",
                "https://ddownload.com/def456/serie-S01.part2.rar"
            )
        )
        assertEquals("serie-S01", name)
    }

    @Test
    fun vorschlagOhneDateinamenFaelltAufDatumZurueck() {
        val name = PackageNaming.suggestFromUrls(listOf("https://ddownload.com/abc123def"))
        assertTrue(name.startsWith("Paket vom "))
    }

    @Test
    fun verfeinerterNameNurWennErSichAendert() {
        val auto = DownloadPackage(id = 1, name = "Paket 1")
        assertEquals("film", PackageNaming.refinedName(auto, listOf("film.part1.rar", "film.part2.rar")))
        // Schon aktuell oder von Hand benannt: nichts schreiben
        assertNull(PackageNaming.refinedName(auto.copy(name = "film"), listOf("film.part1.rar")))
        assertNull(PackageNaming.refinedName(auto.copy(autoNamed = false), listOf("film.part1.rar")))
        assertNull(PackageNaming.refinedName(auto, emptyList()))
    }
}
