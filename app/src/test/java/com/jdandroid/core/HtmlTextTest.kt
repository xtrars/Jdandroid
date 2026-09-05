package com.jdandroid.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlTextTest {

    @Test
    fun entferntSkripteStylesKommentareUndTags() {
        val html = """
            <html><head><style>.a{color:red}</style>
            <script src="x.js"></script><script>var m = 'You must wait';</script></head>
            <body><!-- hidden: wait 5 minutes --><div class="a">Traffic&nbsp;available:
            <b>120,5</b> GB</div></body></html>
        """.trimIndent()
        assertEquals("Traffic available: 120,5 GB", HtmlText.visible(html))
    }

    @Test
    fun skriptMitAttributenUndGrossschreibung() {
        assertEquals("a b", HtmlText.visible("a <SCRIPT type=\"text/javascript\">x<y</SCRIPT> <STYLE>p{}</STYLE> b"))
    }
}
