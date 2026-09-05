package com.jdandroid.container

import com.jdandroid.core.Texts
import java.util.Locale

/**
 * Nutzersichtbare Texte der Container-Schicht (Click'n'Load-Server, DLC),
 * die keinen Android-Context hat. Auf dem Geraet loest [Texts] den
 * Schluessel als String-Ressource aus `strings_service.xml` in der
 * Geraetesprache auf; ohne Provider (JVM-Unit-Tests) kommt der deutsche
 * Standardtext aus der Map hier. Jeder Schluessel steht wortgleich in
 * `res/values/strings_service.xml` und uebersetzt in `res/values-en/…`
 * (geprueft von `ContainerTextsTest`).
 *
 * Mengen: [Texts] kennt nur String-Ressourcen, keine Plurals - daher je ein
 * Schluessel fuer Einzahl (`…_one`) und Mehrzahl (`…_other`), siehe [quantity].
 */
internal object ContainerTexts {

    val texts: Map<String, String> = mapOf(
        "service_cnl_last_request_line" to "%1\$s %2\$s %3\$s → %4\$s",
        "service_cnl_result_ok" to "OK",
        "service_cnl_result_preflight" to "Preflight beantwortet",
        "service_cnl_result_rejected" to "abgelehnt: %1\$s",
        "service_cnl_result_error" to "Fehler: %1\$s",
        "service_cnl_result_too_large" to "Anfrage zu groß",
        "service_cnl_result_no_links_in_form" to "keine Links im Formular (Felder: %1\$s)",
        "service_cnl_result_no_fields" to "keine",
        "service_cnl_result_nothing_decrypted" to "keine Links entschlüsselt",
        "service_cnl_result_links_taken_one" to "%1\$d Link übernommen",
        "service_cnl_result_links_taken_other" to "%1\$d Links übernommen",
        "service_cnl_selftest_ok" to "Server antwortet (HTTP %1\$d).",
        "service_cnl_selftest_http" to "Server antwortet mit HTTP %1\$d.",
        "service_cnl_selftest_unreachable" to "Server nicht erreichbar: %1\$s",
        "service_dlc_service_unreachable" to "DLC-Dienst nicht erreichbar: %1\$s",
        "service_dlc_service_no_key" to "DLC konnte nicht entschlüsselt werden – der JDownloader-DLC-Dienst " +
            "hat keinen Schlüssel geliefert (Dienst evtl. abgeschaltet).",
        "service_dlc_service_invalid_reply" to "DLC-Dienst lieferte ungültige Antwort",
        "service_dlc_too_short" to "DLC-Datei zu kurz oder ungültig",
        "service_dlc_corrupt" to "DLC-Datei beschädigt",
        "service_dlc_no_links" to "DLC enthielt keine lesbaren Links",
        "service_cnl_key_missing" to "Click'n'Load: kein Schlüssel gefunden (jk nicht auswertbar)",
        "service_cnl_key_invalid_length" to "Click'n'Load: Schlüssel hat ungültige Länge (%1\$d Byte)",
        "service_cnl_data_corrupt" to "Click'n'Load: Daten beschädigt (Länge %1\$d)",
        "service_dlc_file_too_large" to "Datei ist zu groß für einen DLC-Container (%1\$d KiB, erlaubt sind %2\$d KiB)",
        "service_dlc_file_unreadable" to "Datei nicht lesbar"
    )

    /** Text zu [key] in der Geraetesprache, sonst der deutsche Standardtext. */
    fun t(key: String, vararg args: Any): String {
        val resolved = Texts.t(key, *args)
        if (resolved != key) return resolved
        val raw = texts[key] ?: return key
        return if (args.isEmpty()) raw else String.format(Locale.getDefault(), raw, *args)
    }

    /** Einzahl/Mehrzahl-Schluessel nach [count] waehlen; [count] ist das erste Argument. */
    fun quantity(keyOne: String, keyOther: String, count: Int, vararg args: Any): String =
        t(if (count == 1) keyOne else keyOther, count, *args)
}
