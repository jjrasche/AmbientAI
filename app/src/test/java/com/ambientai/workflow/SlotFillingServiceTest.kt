package com.ambientai.workflow

import com.ambientai.core.llm.GroqLlmService
import com.ambientai.core.workflow.SlotFillingService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SlotFillingServiceTest {

    private lateinit var llmService: GroqLlmService
    private lateinit var slotFillingService: SlotFillingService

    @Before
    fun setup() {
        llmService = mockk(relaxed = true)
        slotFillingService = SlotFillingService(llmService)
    }

    // ===== NO SLOTS =====

    @Test
    fun `returns empty map when workflow has no slots`() = runTest {
        val workflow = JSONObject("""{"triggers": ["test"], "steps": []}""")
        val result = slotFillingService.fillSlots(workflow, "test transcript")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns empty map when slots object is empty`() = runTest {
        val workflow = JSONObject("""{"triggers": ["test"], "slots": {}, "steps": []}""")
        val result = slotFillingService.fillSlots(workflow, "test transcript")
        assertTrue(result.isEmpty())
    }

    // ===== SUCCESSFUL EXTRACTION =====

    @Test
    fun `extracts single required slot`() = runTest {
        val workflow = JSONObject("""{
            "slots": {
                "taskName": {"description": "Name of the task", "required": true}
            },
            "steps": []
        }""")

        every { llmService.execute(any(), any()) } returns JSONObject().apply {
            put("success", true)
            put("response", """{"taskName": "coding"}""")
        }

        val result = slotFillingService.fillSlots(workflow, "start task coding")

        assertEquals("coding", result["taskName"])
    }

    @Test
    fun `extracts multiple slots`() = runTest {
        val workflow = JSONObject("""{
            "slots": {
                "artist": {"description": "Artist name", "required": false, "default": "random"},
                "song": {"description": "Song name", "required": false}
            },
            "steps": []
        }""")

        every { llmService.execute(any(), any()) } returns JSONObject().apply {
            put("success", true)
            put("response", """{"artist": "Taylor Swift", "song": "Shake It Off"}""")
        }

        val result = slotFillingService.fillSlots(workflow, "play shake it off by taylor swift")

        assertEquals("Taylor Swift", result["artist"])
        assertEquals("Shake It Off", result["song"])
    }

    @Test
    fun `handles JSON wrapped in markdown code blocks`() = runTest {
        val workflow = JSONObject("""{
            "slots": {"query": {"description": "Search query", "required": true}},
            "steps": []
        }""")

        every { llmService.execute(any(), any()) } returns JSONObject().apply {
            put("success", true)
            put("response", "```json\n{\"query\": \"weather forecast\"}\n```")
        }

        val result = slotFillingService.fillSlots(workflow, "what's the weather")

        assertEquals("weather forecast", result["query"])
    }

    // ===== DEFAULT VALUES =====

    @Test
    fun `applies default when slot not in LLM response`() = runTest {
        val workflow = JSONObject("""{
            "slots": {
                "query": {"description": "Search query", "required": false, "default": "random"}
            },
            "steps": []
        }""")

        every { llmService.execute(any(), any()) } returns JSONObject().apply {
            put("success", true)
            put("response", """{}""")
        }

        val result = slotFillingService.fillSlots(workflow, "play some music")

        assertEquals("random", result["query"])
    }

    @Test
    fun `applies default when LLM returns null for slot`() = runTest {
        val workflow = JSONObject("""{
            "slots": {
                "query": {"description": "Search query", "required": false, "default": "random"}
            },
            "steps": []
        }""")

        every { llmService.execute(any(), any()) } returns JSONObject().apply {
            put("success", true)
            put("response", """{"query": null}""")
        }

        val result = slotFillingService.fillSlots(workflow, "shuffle")

        assertEquals("random", result["query"])
    }

    // ===== ERROR HANDLING =====

    @Test
    fun `applies defaults when LLM call fails`() = runTest {
        val workflow = JSONObject("""{
            "slots": {
                "taskName": {"description": "Task name", "required": true},
                "priority": {"description": "Priority", "required": false, "default": "normal"}
            },
            "steps": []
        }""")

        every { llmService.execute(any(), any()) } returns JSONObject().apply {
            put("success", false)
            put("error", "API error")
        }

        val result = slotFillingService.fillSlots(workflow, "start task")

        assertEquals("normal", result["priority"])
        assertTrue(!result.containsKey("taskName"), "Required slot without default should not be in result")
    }

    @Test
    fun `applies defaults when LLM returns invalid JSON`() = runTest {
        val workflow = JSONObject("""{
            "slots": {
                "query": {"description": "Query", "required": false, "default": "random"}
            },
            "steps": []
        }""")

        every { llmService.execute(any(), any()) } returns JSONObject().apply {
            put("success", true)
            put("response", "not valid json at all")
        }

        val result = slotFillingService.fillSlots(workflow, "play music")

        assertEquals("random", result["query"])
    }

    // ===== LLM CALL VERIFICATION =====

    @Test
    fun `passes correct parameters to LLM`() = runTest {
        val workflow = JSONObject("""{
            "slots": {
                "taskName": {"description": "Name of task", "required": true}
            },
            "steps": []
        }""")

        every { llmService.execute(any(), any()) } returns JSONObject().apply {
            put("success", true)
            put("response", """{"taskName": "test"}""")
        }

        slotFillingService.fillSlots(workflow, "start task test")

        verify {
            llmService.execute("llm.prompt", match { input ->
                input.optString("systemPrompt").contains("taskName") &&
                input.optString("userPrompt").contains("start task test") &&
                input.optDouble("temperature") == 0.1 &&
                input.optInt("maxTokens") == 200
            })
        }
    }

    @Test
    fun `does not call LLM when no slots defined`() = runTest {
        val workflow = JSONObject("""{"triggers": ["test"], "steps": []}""")
        slotFillingService.fillSlots(workflow, "test")

        verify(exactly = 0) { llmService.execute(any(), any()) }
    }
}
