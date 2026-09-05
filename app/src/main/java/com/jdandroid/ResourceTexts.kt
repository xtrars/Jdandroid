package com.jdandroid

import android.annotation.SuppressLint
import android.content.Context
import com.jdandroid.core.Texts
import java.util.concurrent.ConcurrentHashMap

/**
 * Android provider for [Texts]: resolves a key as `R.string.<key>` in the
 * device language. Missing resources fall back to the Kotlin maps in [Texts].
 */
class ResourceTexts(context: Context) : Texts.Provider {
    private val app = context.applicationContext
    private val ids = ConcurrentHashMap<String, Int>()

    // Keys come from layers without an R class, so lookup by name is required;
    // Lint cannot see these usages (UnusedResources is disabled for those files).
    @SuppressLint("DiscouragedApi")
    override fun text(key: String, args: Array<out Any>): String? {
        val res = app.resources
        val id = ids.getOrPut(key) { res.getIdentifier(key, "string", app.packageName) }
        if (id == 0) return null
        return if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
    }
}
