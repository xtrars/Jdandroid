package com.jdandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Below Android 11 WindowInsetsCompat only reports IME insets in adjustResize
 * mode; without it imePadding() does nothing and the window is panned instead.
 */
class ManifestTest {

    private val android = "http://schemas.android.com/apk/res/android"

    @Test
    fun mainActivityNutztAdjustResize() {
        val activity = activities().single { it.getAttributeNS(android, "name") == ".ui.MainActivity" }
        assertEquals("adjustResize", activity.getAttributeNS(android, "windowSoftInputMode"))
    }

    private fun activities(): List<Element> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(manifest())
        val nodes = doc.getElementsByTagName("activity")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun manifest(): File {
        // Gradle runs unit tests in app/; also resolve from the project root
        val candidates = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
        return candidates.firstOrNull { it.isFile }
            ?: error("Manifest nicht gefunden (Arbeitsverzeichnis ${File(".").absolutePath})")
    }
}
