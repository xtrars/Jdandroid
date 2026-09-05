package com.jdandroid

import android.annotation.SuppressLint
import android.content.Context
import com.jdandroid.core.Texts
import java.util.concurrent.ConcurrentHashMap

/**
 * Android-Provider fuer [Texts]: loest einen Schluessel als String-Ressource
 * (`R.string.<key>`) in der Sprache des Geraets auf. Fehlt die Ressource,
 * greift [Texts] auf die deutschen Kotlin-Maps zurueck.
 */
class ResourceTexts(context: Context) : Texts.Provider {
    private val app = context.applicationContext
    private val ids = ConcurrentHashMap<String, Int>()

    // getIdentifier ist hier Absicht: die Schluessel kommen zur Laufzeit aus
    // Schichten ohne R-Klasse; Lint kennt die Verwendung nicht (UnusedResources
    // ist in den betroffenen Dateien abgeschaltet).
    @SuppressLint("DiscouragedApi")
    override fun text(key: String, args: Array<out Any>): String? {
        val res = app.resources
        val id = ids.getOrPut(key) { res.getIdentifier(key, "string", app.packageName) }
        if (id == 0) return null
        return if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
    }
}
