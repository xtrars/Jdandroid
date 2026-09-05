package com.jdandroid.core

import com.jdandroid.core.texts.EngineTexts
import com.jdandroid.core.texts.HosterTexts
import java.util.Locale

/**
 * Translated texts for layers without an Android context (engine, extractor,
 * hosters). A key is also the name of a string resource: on the device
 * [com.jdandroid.JdApp] installs a [Provider] backed by the resources; without
 * one (JVM tests) the German texts come from [EngineTexts] and [HosterTexts],
 * which `TextsTest` keeps in sync with the resources. An unknown key returns
 * the key itself so a typo is visible instead of crashing.
 */
object Texts {

    /** Resolves a key with arguments; null = unknown key. */
    fun interface Provider {
        fun text(key: String, args: Array<out Any>): String?
    }

    /** German fallback texts, in lookup order. */
    private val fallbacks: List<Map<String, String>> = listOf(EngineTexts.texts, HosterTexts.texts)

    @Volatile
    private var provider: Provider? = null

    fun install(provider: Provider?) {
        this.provider = provider
    }

    fun fallback(key: String): String? = fallbacks.firstNotNullOfOrNull { it[key] }

    fun fallbackKeys(): Set<String> = fallbacks.flatMapTo(LinkedHashSet()) { it.keys }

    fun t(key: String, vararg args: Any): String {
        provider?.text(key, args)?.let { return it }
        val raw = fallback(key) ?: return key
        return if (args.isEmpty()) raw else String.format(Locale.GERMANY, raw, *args)
    }
}
