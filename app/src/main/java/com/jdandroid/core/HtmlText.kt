package com.jdandroid.core

/** Reduces HTML to its visible text so page patterns cannot match inside scripts or comments. */
object HtmlText {

    private val ic = RegexOption.IGNORE_CASE
    private val script = Regex("""<script\b[^>]*>[\s\S]*?</script>""", ic)
    private val style = Regex("""<style\b[^>]*>[\s\S]*?</style>""", ic)
    private val comment = Regex("""<!--[\s\S]*?-->""")
    private val tag = Regex("""<[^>]+>""")
    private val blanks = Regex("""\s+""")

    /** Scripts, styles, comments and tags removed, `&nbsp;` and whitespace collapsed to single spaces. */
    fun visible(html: String): String =
        html.replace(script, " ")
            .replace(style, " ")
            .replace(comment, " ")
            .replace(tag, " ")
            .replace("&nbsp;", " ")
            .replace(blanks, " ")
            .trim()
}
