package app.olauncher.features.assistant

import app.olauncher.features.assistant.domain.ApiFormat
import app.olauncher.features.assistant.domain.ChatCommand
import app.olauncher.features.assistant.domain.ChatProtocol
import app.olauncher.features.assistant.domain.Commands
import app.olauncher.features.assistant.domain.Memories
import app.olauncher.features.assistant.domain.MemoryItem
import app.olauncher.features.assistant.domain.ToolCall
import app.olauncher.features.assistant.domain.ToolCallCollector
import app.olauncher.features.assistant.domain.Tools
import app.olauncher.features.assistant.domain.Turn
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryToolsTest {

    private val memory = listOf(
        MemoryItem(1, "identity", "Name: Alex Rivera, 27", core = true),
        MemoryItem(2, "location", "Lives in Leeds, near the city centre", core = true),
        MemoryItem(3, "education", "Studying an MSc in Data Science at the University of Leeds"),
        MemoryItem(4, "interests", "Goes bouldering twice a week and enjoys climbing trips"),
        MemoryItem(5, "interests", "Likes jazz and classical piano music"),
        MemoryItem(6, "research", "Researching graph neural networks for traffic forecasting"),
        MemoryItem(7, "style", "Prefers rigorous answers with equations and honest limitations"),
    )

    @Test fun selectsCoreAndRelevantOnly() {
        val climbing = Memories.select(memory, "Any good climbing walls for bouldering this weekend?")
        assertTrue(climbing.any { it.id == 4L })
        assertTrue(climbing.any { it.core })
        assertFalse(climbing.any { it.id == 6L || it.id == 5L })

        val research = Memories.select(memory, "How should I validate my graph neural network forecasts?")
        assertTrue(research.any { it.id == 6L })
        assertFalse(research.any { it.id == 4L })

        // a tiny budget still keeps the core facts first
        val small = Memories.select(memory, "music", budget = 120)
        assertTrue(small.all { it.core || it.id == 5L })
        assertTrue(Memories.promptBlock(climbing)!!.contains("[interests] Goes bouldering"))
    }

    @Test fun organizesWithAndWithoutModel() {
        val reply = """Sure! {"items":[{"tag":"Identity","text":"Name: Alex Rivera","core":true},{"tag":"interests","text":"Bouldering twice a week"},{"tag":"x","text":"ok"}]}"""
        val drafts = Memories.parseOrganized(reply)
        assertEquals(2, drafts.size)
        assertEquals("identity", drafts[0].tag)
        assertTrue(drafts[0].core)

        val raw = "# Personal Information\n- **Full name:** Alex Rivera\n- **Age:** 27\n\n## Hobbies\nI go bouldering twice a week.\n"
        val offline = Memories.fallbackOrganize(raw)
        assertEquals(3, offline.size)
        assertEquals("Full name: Alex Rivera", offline[0].text)
        assertEquals("interests", offline[2].tag)
    }

    @Test fun learnsOnlyValidChanges() {
        assertTrue(Memories.mentionsSelf("I moved to Manchester last week"))
        assertTrue(Memories.mentionsSelf("من به منچستر رفتم"))
        assertFalse(Memories.mentionsSelf("What's the capital of Peru?"))
        val c = Memories.parseLearn("""{"add":[{"tag":"location","text":"Moved to Manchester in October"},{"tag":"x","text":"Lives in Leeds, near the city centre"}],
            "update":[{"id":2,"text":"Lives in Manchester"},{"id":99,"text":"bogus"}],"delete":[5,42]}""", memory)
        assertEquals(1, c.add.size)
        assertEquals(mapOf(2L to "Lives in Manchester"), c.update)
        assertEquals(setOf(5L), c.delete)
        assertTrue(Memories.parseLearn("""{"add":[],"update":[],"delete":[]}""", memory).isEmpty)
    }

    @Test fun commands() {
        val cmds = listOf(ChatCommand(1, "review", "Review strictly:\n{input}"), ChatCommand(2, "fa", "Translate to Persian"))
        val (cmd, rest) = Commands.match("/review my draft abstract", cmds)!!
        assertEquals("review", cmd.name)
        assertEquals("my draft abstract", rest)
        assertEquals("Review strictly:\nmy draft abstract", Commands.instruction(cmd, rest))
        assertTrue(Commands.instruction(cmds[1], "hello").contains("Translate to Persian"))
        assertEquals(null, Commands.match("/unknown x", cmds))
        assertEquals(null, Commands.match("review this", cmds))
        assertEquals("my-cmd", Commands.normalizeName(" /My-Cmd! "))
    }

    @Test fun toolCallsOpenAiStreamAndRequest() {
        val c = ToolCallCollector(ApiFormat.OPENAI)
        c.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"find_places","arguments":""}}]}}]}""")
        c.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"query\":\"phar"}}]}}]}""")
        c.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"macy\"}"}}]}}]}""")
        val calls = c.calls()
        assertEquals(listOf(ToolCall("call_1", "find_places", """{"query":"pharmacy"}""")), calls)

        val turns = listOf(
            Turn(Turn.Role.USER, "nearest pharmacy?"),
            Turn(Turn.Role.ASSISTANT, "", toolCalls = calls),
            Turn(Turn.Role.TOOL, """{"results":[]}""", toolCallId = "call_1"),
        )
        val body = JSONObject(ChatProtocol.request(ApiFormat.OPENAI, "https://x/v1", "k", "m", turns, tools = Tools.ALL).body)
        val msgs = body.getJSONArray("messages")
        assertEquals(3, msgs.length())
        assertEquals("call_1", msgs.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("tool", msgs.getJSONObject(2).getString("role"))
        assertEquals(Tools.ALL.size, body.getJSONArray("tools").length())
    }

    @Test fun toolCallsAnthropic() {
        val c = ToolCallCollector(ApiFormat.ANTHROPIC)
        c.feed("""{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tu_1","name":"weather","input":{}}}""")
        c.feed("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"place\":\"Nott"}}""")
        c.feed("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"ingham\"}"}}""")
        assertEquals(listOf(ToolCall("tu_1", "weather", """{"place":"Nottingham"}""")), c.calls())

        val turns = listOf(
            Turn(Turn.Role.USER, "weather?"),
            Turn(Turn.Role.ASSISTANT, "Checking.", toolCalls = c.calls()),
            Turn(Turn.Role.TOOL, "sunny", toolCallId = "tu_1"),
        )
        val body = JSONObject(ChatProtocol.request(ApiFormat.ANTHROPIC, "https://x/v1", "k", "m", turns, tools = Tools.ALL).body)
        val msgs = body.getJSONArray("messages")
        val use = msgs.getJSONObject(1).getJSONArray("content").getJSONObject(1)
        assertEquals("tool_use", use.getString("type"))
        assertEquals("Nottingham", use.getJSONObject("input").getString("place"))
        assertEquals("tool_result", msgs.getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("type"))
        assertNotNull(body.getJSONArray("tools").getJSONObject(0).optJSONObject("input_schema"))
    }

    @Test fun mapsAndWeatherParsing() {
        // Nottingham Old Market Square → Trent Bridge is about 1.9 km as the crow flies
        val m = Tools.haversine(52.9530, -1.1500, 52.9370, -1.1380)
        assertTrue(m in 1_800.0..2_100.0)
        assertEquals("1.9 km", Tools.formatDistance(1_934.0).replace(',', '.'))
        assertEquals("350 m", Tools.formatDistance(347.0))
        assertEquals("1 h 5 min", Tools.formatDuration(3_900.0))

        val places = Tools.parseNominatim("""[{"lat":"52.95","lon":"-1.15","name":"Boots","display_name":"Boots, Victoria Centre, Nottingham","type":"pharmacy"}]""")
        assertEquals("Boots", places.single().name)
        assertEquals(1_200.0, Tools.parseOsrm("""{"routes":[{"distance":1200,"duration":900}]}""")!!.metres, 0.1)

        val w = Tools.summarizeWeather("""{"current":{"temperature_2m":9.5,"apparent_temperature":7.1,"precipitation":0.2,"weather_code":61,"wind_speed_10m":14},
            "daily":{"time":["2026-10-29"],"temperature_2m_min":[6],"temperature_2m_max":[11],"weather_code":[3],"precipitation_probability_max":[40]}}""", "Nottingham")!!
        assertTrue(w.contains("9.5°C") && w.contains("rain") && w.contains("rain chance 40%"))

        assertTrue(Tools.wantsTools("what's the nearest pharmacy?"))
        assertTrue(Tools.wantsTools("فاصله تا ایستگاه قطار چقدره"))
        assertFalse(Tools.wantsTools("explain PINNs"))
        assertEquals("cycle", Tools.normalizeMode("bike"))
    }
}
