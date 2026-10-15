package de.x0bubbuff.needlebub.runtime

import de.x0bubbuff.needlebub.gateway.ErrorCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the engine conversation policy.
 *
 * The coordinator's synthetic desktop run showed that identical independent requests
 * without a rewind hang, while the same sequence with a rewind completes in ~130 ms
 * median. These tests pin the app side of that evidence: every independent request over a
 * warm runtime rewinds, and the mapped model, tools and loaded pack stay resident.
 */
class IsolatedInferenceRunnerTest {
    private class FakeEngine : RuntimeEngine {
        val calls = mutableListOf<String>()
        var loadResult = 0
        var initResult = 0
        var envelope: String? = COMPLETE_CALL

        override fun load(fd: Int, size: Long): Int {
            calls += "load"
            return loadResult
        }

        override fun initialize(systemPrompt: String, toolsJson: String): Int {
            calls += "initialize"
            return initResult
        }

        override fun complete(input: String, maxTokens: Int, capacity: Int): String? {
            calls += "complete"
            lastInput = input
            return envelope
        }

        override fun rewind() {
            calls += "rewind"
        }

        override fun reset() {
            calls += "reset"
        }

        var lastInput: String = ""

        fun count(name: String) = calls.count { it == name }

        companion object {
            const val COMPLETE_CALL =
                """{"type":"call","success":true,"function_calls":[{"name":"extract_otp","arguments":{"code":"123456"}}]}"""
        }
    }

    private fun runner(engine: FakeEngine) = IsolatedInferenceRunner(
        engine = engine,
        elapsedRealtime = { 0L },
        pssKb = { 1_024L },
        logLine = { },
    )

    private fun request(
        id: String,
        input: String = "Your code is 123456",
        forceReload: Boolean = false,
        packVersion: String = "1.0.0",
        appVersionName: String = "0.1.0-beta.2",
    ) = RuntimeRequest(
        requestId = id,
        packId = "needlebub.otp.extractor",
        packVersion = packVersion,
        input = input,
        queryTemplate = "Extract the code: {{input}}",
        modelSize = 4_096L,
        timeoutMs = 5_000L,
        surface = "notification",
        forceReload = forceReload,
        requestedAtEpochMs = 1_700_000_000_000L,
        appVersionName = appVersionName,
        appVersionCode = 9L,
        modelIdentity = "needlebub.otp.extractor@$packVersion:abi:4096",
    )

    private val tools = """{"tools":[{"name":"extract_otp"}]}"""

    @Test
    fun `independent requests rewind the conversation instead of reloading the model`() {
        val engine = FakeEngine()
        val runner = runner(engine)

        val first = runner.run(request("first"), 7, tools)
        val second = runner.run(request("second", input = "Another message 654321"), 7, tools)

        assertEquals(
            listOf("load", "initialize", "complete", "rewind", "complete"),
            engine.calls,
        )
        assertEquals("the mapped model must be loaded once", 1, engine.count("load"))
        assertEquals(1, engine.count("rewind"))
        assertEquals(0, engine.count("reset"))

        assertEquals(RuntimeLoadState.COLD, first.loadState)
        assertEquals(true, first.coldLoad)
        assertEquals(RuntimeLoadState.WARM, second.loadState)
        assertEquals(false, second.coldLoad)
        assertEquals("OK", second.status)
        assertEquals("extract_otp", second.toolName)
        assertEquals("{\"code\":\"123456\"}", second.resultJson)

        // Each independent notification is its own query: no turn history is replayed.
        assertTrue(engine.lastInput.contains("Another message 654321"))
        assertTrue(!engine.lastInput.contains("Your code is 123456"))
    }

    @Test
    fun `a third independent request still rewinds and never reloads`() {
        val engine = FakeEngine()
        val runner = runner(engine)

        repeat(3) { index -> runner.run(request("r$index", input = "message $index"), 7, tools) }

        assertEquals(1, engine.count("load"))
        assertEquals(2, engine.count("rewind"))
        assertEquals(3, engine.count("complete"))
    }

    @Test
    fun `a forced reload releases the mapped model and reports a cold load`() {
        val engine = FakeEngine()
        val runner = runner(engine)

        runner.run(request("warm"), 7, tools)
        val forced = runner.run(request("forced", forceReload = true), 7, tools)

        assertEquals(listOf("load", "initialize", "complete", "reset", "load", "initialize", "complete"), engine.calls)
        assertEquals(true, forced.coldLoad)
        assertEquals(RuntimeLoadState.COLD, forced.loadState)
    }

    @Test
    fun `a pack change reloads and does not rewind the previous pack conversation`() {
        val engine = FakeEngine()
        val runner = runner(engine)

        runner.run(request("v1"), 7, tools)
        runner.run(request("v2", packVersion = "1.1.0"), 7, tools)

        assertEquals(2, engine.count("load"))
        assertEquals(0, engine.count("rewind"))
    }

    @Test
    fun `reset releases the model so the next request is cold again`() {
        val engine = FakeEngine()
        val runner = runner(engine)

        runner.run(request("first"), 7, tools)
        runner.reset()
        val after = runner.run(request("second"), 7, tools)

        assertEquals(1, engine.count("reset"))
        assertEquals(2, engine.count("load"))
        assertEquals(true, after.coldLoad)
    }

    @Test
    fun `an incomplete call envelope is reported as truncated not as a match`() {
        val engine = FakeEngine()
        engine.envelope = """{"type":"call","success":true,"function_calls":[{"name":"extract_otp"}]}"""
        val runner = runner(engine)

        val response = runner.run(request("truncated"), 7, tools)

        assertEquals("ERROR", response.status)
        assertEquals(ErrorCodes.OUTPUT_TRUNCATED, response.errorCode)
        assertEquals(true, response.outputTruncated)
        assertNull(response.resultJson)
    }

    @Test
    fun `a length capped engine failure is flagged as truncated and keeps the engine code`() {
        val engine = FakeEngine()
        engine.envelope = """{"type":"error","success":false,"error_code":"max_new_tokens_reached"}"""
        val runner = runner(engine)

        val response = runner.run(request("capped"), 7, tools)

        assertEquals(true, response.outputTruncated)
        assertEquals("max_new_tokens_reached", response.engineErrorCode)
        assertEquals(false, response.engineSuccess)
    }

    @Test
    fun `a complete envelope is explicitly not truncated`() {
        val engine = FakeEngine()
        val runner = runner(engine)

        val response = runner.run(request("complete"), 7, tools)

        assertEquals(false, response.outputTruncated)
        assertEquals("call", response.responseType)
    }

    @Test
    fun `a missing envelope is a crash and truncation stays unknown`() {
        val engine = FakeEngine()
        engine.envelope = null
        val runner = runner(engine)

        val response = runner.run(request("null"), 7, tools)

        assertEquals(ErrorCodes.RUNTIME_CRASH, response.errorCode)
        assertNull("a crash is not evidence of truncation", response.outputTruncated)
    }

    @Test
    fun `an oversized input never touches the engine`() {
        val engine = FakeEngine()
        val runner = runner(engine)

        val response = runner.run(request("huge", input = "x".repeat(IsolatedInferenceRunner.MAX_INPUT_BYTES + 1)), 7, tools)

        assertEquals(ErrorCodes.INPUT_TOO_LARGE, response.errorCode)
        assertEquals(0, engine.calls.size)
        assertEquals(RuntimeLoadState.UNKNOWN, response.loadState)
        assertNull(response.coldLoad)
    }

    @Test
    fun `a failed load leaves the pack unloaded for the next request`() {
        val engine = FakeEngine()
        engine.loadResult = -1
        val runner = runner(engine)

        val failed = runner.run(request("failed"), 7, tools)
        engine.loadResult = 0
        val recovered = runner.run(request("recovered"), 7, tools)

        assertEquals(ErrorCodes.PACK_INVALID, failed.errorCode)
        assertEquals(2, engine.count("load"))
        assertEquals("OK", recovered.status)
    }

    @Test
    fun `trace metadata is reported without exposing request or result content`() {
        val engine = FakeEngine()
        val logged = ArrayList<String>()
        val runner = IsolatedInferenceRunner(
            engine = engine,
            elapsedRealtime = { 0L },
            pssKb = { 2_048L },
            logLine = { logged += it },
        )

        val response = runner.run(request("traced", input = "secret message body"), 7, tools)

        assertEquals(1_700_000_000_000L, response.requestedAtEpochMs)
        assertEquals("0.1.0-beta.2", response.appVersionName)
        assertEquals(9L, response.appVersionCode)
        assertEquals("needlebub.otp.extractor@1.0.0:abi:4096", response.modelIdentity)
        assertEquals(RuntimeLoadState.COLD, response.loadState)

        val line = logged.single()
        assertTrue(line.contains("model=needlebub.otp.extractor@1.0.0:abi:4096"))
        assertTrue(line.contains("load=cold"))
        assertTrue(line.contains("rewound=false"))
        assertTrue(!line.contains("secret message body"))
        assertTrue(!line.contains("123456"))
    }
}
