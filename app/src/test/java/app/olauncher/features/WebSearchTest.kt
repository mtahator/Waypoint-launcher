package app.olauncher.features

import app.olauncher.features.websearch.domain.SearchResult
import app.olauncher.features.websearch.domain.Source
import app.olauncher.features.websearch.domain.WebSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchTest {
    private val ddg = """
        <div class="result results_links results_links_deep web-result">
          <h2 class="result__title"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.metoffice.gov.uk%2Fweather%2Fforecast%2Fgcrjm8mfn&amp;rut=abc">Nottingham weather - <b>Met Office</b></a></h2>
          <a class="result__snippet" href="x">Today: sunny intervals, <b>14&#176;C</b>.</a>
        </div>
        <div class="result"><h2><a class="result__a" href="https://www.bbc.co.uk/weather/2641170">BBC Weather - Nottingham</a></h2>
          <a class="result__snippet" href="y">Latest forecast &amp; more.</a></div>
        <div class="result"><a class="result__a" href="https://duckduckgo.com/y.js?ad_provider=bing">Ad</a></div>
    """.trimIndent()

    @Test fun duckDuckGo() {
        val r = WebSearch.parseDuckDuckGo(ddg)
        assertEquals(2, r.size)
        assertEquals("https://www.metoffice.gov.uk/weather/forecast/gcrjm8mfn", r[0].url)
        assertEquals("Nottingham weather - Met Office", r[0].title)
        assertEquals("Today: sunny intervals, 14°C.", r[0].snippet)
        assertEquals("Latest forecast & more.", r[1].snippet)
    }

    @Test fun brave() {
        val r = WebSearch.parseBrave("""{"web":{"results":[{"title":"A <b>b</b>","url":"https://a.com","description":"d"}]}}""")
        assertEquals(listOf(SearchResult("A b", "https://a.com", "d")), r)
    }

    @Test fun pageText() {
        val html = "<html><head><title>x</title><script>var a=1</script></head><body><nav>menu</nav>" +
            "<article><h1>Title</h1><p>" + "Real content here. ".repeat(30) + "</p><p>Second &amp; last.</p></article><footer>f</footer></body></html>"
        val t = WebSearch.htmlToText(html)
        assertTrue(t.startsWith("Title"))
        assertTrue(t.contains("Second & last."))
        assertFalse(t.contains("var a")); assertFalse(t.contains("menu"))
        assertTrue(WebSearch.htmlToText("<p>" + "word ".repeat(2000) + "</p>", 100).length <= 101)
    }

    @Test fun plan() {
        val p = WebSearch.parsePlan("Sure: {\"search\": true, \"queries\": [\"Nottingham weather now\", \"\"], \"use_about_me\": false}", false)
        assertTrue(p.search); assertEquals(listOf("Nottingham weather now"), p.queries); assertFalse(p.useAboutMe)
        val none = WebSearch.parsePlan("{\"search\": false, \"queries\": []}", false)
        assertFalse(none.search)
        assertTrue(WebSearch.parsePlan("garbage", true).search)
        assertFalse(WebSearch.parsePlan("garbage", false).search)
    }

    @Test fun mergeAndSources() {
        val a = listOf(SearchResult("a", "https://a.com/", ""), SearchResult("b", "https://b.com", ""))
        val b = listOf(SearchResult("a2", "https://a.com", ""), SearchResult("c", "https://c.com", ""))
        assertEquals(listOf("a", "b", "c"), WebSearch.merge(listOf(a, b), 5).map { it.title })
        val enc = WebSearch.encodeSources(listOf(Source("T", "https://www.x.org/p")))
        assertEquals("x.org", WebSearch.decodeSources(enc)[0].host)
        val prompt = WebSearch.sourcesPrompt(listOf(SearchResult("T", "https://x", "s", "body")), "2026-10-29")
        assertTrue(prompt.contains("[1] T\nURL: https://x\nbody"))
        assertEquals(listOf("1", "3"), WebSearch.CITATION.findAll("see [1] and [3].").map { it.groupValues[1] }.toList())
    }
}
