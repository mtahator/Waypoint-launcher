package app.olauncher.features.assistant

import app.olauncher.features.assistant.domain.CustomTool
import app.olauncher.features.assistant.domain.Panel
import app.olauncher.features.assistant.domain.Panels
import app.olauncher.features.assistant.domain.Tools
import app.olauncher.features.space.domain.ReminderRequest
import app.olauncher.features.widgets.domain.Agenda
import app.olauncher.features.widgets.domain.AgendaEvent
import app.olauncher.features.widgets.domain.StackSlot
import app.olauncher.features.widgets.domain.WidgetItem
import app.olauncher.features.widgets.domain.WidgetStack
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelsWidgetsTest {
    @Test fun widgetStack() {
        val items = listOf(WidgetItem.Own(WidgetItem.Kind.CALENDAR), WidgetItem.App(12, "com.x/.W", "Notes"), WidgetItem.Own(WidgetItem.Kind.WEATHER))
        assertEquals(items, WidgetStack.decode(WidgetStack.encode(items)))
        assertEquals(WidgetStack.DEFAULT, WidgetStack.decode(null))
        val music = WidgetItem.Own(WidgetItem.Kind.MUSIC)
        assertEquals(listOf(music) + items, WidgetStack.visible(items, playing = true))
        assertEquals(items, WidgetStack.visible(items + music, playing = false))
        assertEquals(listOf(items[1], items[0], items[2]), WidgetStack.move(items, 1, 0))
        assertEquals(items, WidgetStack.move(items, 0, -1))
    }

    @Test fun stackLayout() {
        val cal = WidgetItem.Own(WidgetItem.Kind.CALENDAR); val wx = WidgetItem.Own(WidgetItem.Kind.WEATHER)
        val goals = WidgetItem.Own(WidgetItem.Kind.GOALS); val music = WidgetItem.Own(WidgetItem.Kind.MUSIC)
        val slots = listOf(StackSlot(1, true, listOf(cal)), StackSlot(2, false, listOf(wx)), StackSlot(3, false, listOf(goals)), StackSlot(4, false, emptyList()))
        assertEquals(slots, WidgetStack.decodeLayout(WidgetStack.encodeLayout(slots)))
        assertEquals(WidgetStack.DEFAULT_LAYOUT, WidgetStack.decodeLayout(null))
        // an older single list becomes one full-width stack
        assertEquals(listOf(StackSlot(1, true, listOf(cal, wx))), WidgetStack.decodeLayout(WidgetStack.encode(listOf(cal, wx))))
        // wide alone, squares paired, empty stacks hidden
        assertEquals(listOf(listOf(slots[0]), listOf(slots[1], slots[2])), WidgetStack.rows(slots))
        val lone = listOf(StackSlot(2, false, listOf(wx)), StackSlot(1, true, listOf(cal)))
        assertEquals(listOf(listOf(lone[0]), listOf(lone[1])), WidgetStack.rows(lone))
        assertEquals(listOf(music, cal), WidgetStack.withMusic(slots, true)[0].items)
        assertEquals(slots, WidgetStack.withMusic(slots.map { it.copy(items = it.items + music) }, false))
        assertEquals(listOf(StackSlot(WidgetStack.MUSIC_SLOT, true, listOf(music))), WidgetStack.rows(WidgetStack.withMusic(emptyList(), true)).flatten())
        assertEquals(5, WidgetStack.nextId(slots))
    }

    @Test fun agenda() {
        val now = 1_000_000L; val eod = now + 3_600_000
        val ev = listOf(
            AgendaEvent("Past", now - 7_200_000, now - 3_600_000, false, 0),
            AgendaEvent("Lecture", now + 1_500_000, now + 4_000_000, false, 0),
            AgendaEvent("Holiday", now - 10_000, now + 80_000_000, true, 0),
            AgendaEvent("Tomorrow", eod + 1_000_000, eod + 2_000_000, false, 0),
        )
        assertEquals(listOf("Holiday", "Lecture", "Tomorrow"), Agenda.upcoming(ev, now, eod).map { it.title })
        assertEquals("In 25 min", Agenda.nextLabel(ev[1], now))
    }

    @Test fun customTools() {
        val p = listOf(CustomTool.Param("word", "The word"))
        assertNull(CustomTool.problem("dictionary", "https://api.dictionaryapi.dev/api/v2/entries/en/{word}", p))
        assertNotNull(CustomTool.problem("dictionary", "http://example.com/{word}", p))
        assertNotNull(CustomTool.problem("dictionary", "https://example.com/{other}", p))
        val t = CustomTool(1, "dictionary", "Define a word", "https://api.dictionaryapi.dev/api/v2/entries/en/{word}", p, true)
        assertEquals("https://api.dictionaryapi.dev/api/v2/entries/en/ice+cream", t.expand(JSONObject().put("word", "ice cream")))
        assertEquals("my_dictionary", t.spec().name)
        assertEquals("api.dictionaryapi.dev", t.host())
        assertEquals(p, CustomTool.decodeParams(CustomTool.encodeParams(p)))
        assertEquals("exchange_rates", CustomTool.normalizeName("Exchange Rates!"))
    }

    @Test fun panels() {
        val weather = """{"weather":"…","panel":{"place":"Nottingham","temp":9.4,"feels":7.0,"code":3,"wind":12,
            "days":[{"date":"2026-11-05","min":4,"max":10,"code":61,"rain":70}]}}"""
        val w = Panels.fromTool(Tools.WEATHER, weather) as Panel.Weather
        assertEquals("Nottingham", w.place); assertEquals(70, w.days.single().rain)
        val cal = Panels.fromTool(Tools.CALENDAR, """{"title":"Thursday 5 November","events":[{"title":"Lab","begin_ms":1,"end_ms":2,"all_day":false,"color":-65536,"location":"ERS"}]}""") as Panel.Agenda
        assertEquals("Lab", cal.events.single().title)
        val tool = Panels.fromTool(Tools.CREATE_TOOL, """{"tool_id":3,"name":"rates","description":"FX","url":"https://x.io/{c}"}""") as Panel.NewTool
        assertEquals(3L, tool.toolId)
        assertEquals(listOf(w, cal, tool), Panels.decode(Panels.encode(listOf(w, cal, tool))))
        assertNull(Panels.fromTool(Tools.WEATHER, """{"error":"no"}"""))
    }

    @Test fun compoundRequests() {
        assertFalse(ReminderRequest.looksCompound("Remind me tomorrow at 9 to call home"))
        assertTrue(ReminderRequest.looksCompound("Remind me to call mom and buy milk"))
        assertTrue(ReminderRequest.looksCompound("Add a task: plan the trip, book flights, pack"))
        assertTrue(ReminderRequest.looksCompound("یادم بنداز نون بخرم و به مامان زنگ بزنم"))
    }
}
