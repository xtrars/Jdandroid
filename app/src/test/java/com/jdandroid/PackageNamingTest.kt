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
        // Already current or manually named: nothing to write
        assertNull(PackageNaming.refinedName(auto.copy(name = "film"), listOf("film.part1.rar")))
        assertNull(PackageNaming.refinedName(auto.copy(autoNamed = false), listOf("film.part1.rar")))
        assertNull(PackageNaming.refinedName(auto, emptyList()))
    }

    @Test
    fun mehrteilige7zUndAlteRarTeileErgebenDenStamm() {
        assertEquals("archiv", PackageNaming.commonName(listOf("archiv.7z.001", "archiv.7z.002")))
        assertEquals("archiv", PackageNaming.commonName(listOf("archiv.rar", "archiv.r00", "archiv.r01")))
        assertEquals("archiv", PackageNaming.commonName(listOf("archiv.zip", "archiv.z01")))
    }

    @Test
    fun gemeinsamerTeilIgnoriertGrossschreibungUndTrennzeichenAmEnde() {
        // Case-insensitive comparison, the first name decides the spelling
        assertEquals("Film", PackageNaming.commonName(listOf("Film.part1.rar", "film.part2.rar")))
        // A dangling separator or bracket after the common part is dropped
        assertEquals("serie", PackageNaming.commonName(listOf("serie-a.rar", "serie-b.rar")))
        assertEquals("serie", PackageNaming.commonName(listOf("serie (1).rar", "serie (2).rar")))
        assertEquals("serie", PackageNaming.commonName(listOf("serie_part1.rar", "serie_part2.rar")))
    }

    @Test
    fun zuKurzerOderLeererNameLiefertNull() {
        assertNull(PackageNaming.commonName(listOf("ab.rar")))
        assertNull(PackageNaming.commonName(listOf("", "   ")))
        assertNull(PackageNaming.commonName(listOf("...rar")))
        // Blank names are skipped, not compared
        assertEquals("film", PackageNaming.commonName(listOf("", "film.part1.rar", " ")))
    }

    @Test
    fun vorschlagIgnoriertAbfrageteilUndKurzeNamen() {
        val name = PackageNaming.suggestFromUrls(
            listOf("https://ddownload.com/abc123/serie.part1.rar?dl=1", "https://ddownload.com/def456/serie.part2.rar")
        )
        assertEquals("serie", name)
        // "?abc" and "a.b" are not file names; the fallback is a timestamp name
        assertTrue(PackageNaming.suggestFromUrls(listOf("https://1fichier.com/?abc", "https://x.test/a.b")).startsWith("Paket vom "))
    }
}
