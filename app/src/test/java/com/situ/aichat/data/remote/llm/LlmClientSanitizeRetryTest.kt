package com.situ.aichat.data.remote.llm

import com.situ.aichat.data.model.ApiProviderType
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * [zCODE] P1·LlmClient 网络层集成测（评审裁决④：引入 okhttp mockwebserver，仅 testImplementation）。
 *
 * 覆盖三条 P1 验收判据的仿真侧：
 * 1. 流式正文过 OutputSanitizer（MiniMax `[/dialogue]` 泄漏样本 → 气泡净文）；
 * 2. 429 限流 → 退避等待重试而非报错丢弃（连接期既有逻辑的行为锁定，防回归）；
 * 3. 非流式 completion 统一出口清洗（评审裁决①）。
 * 另钉「零内容接收期重试恰 1 次」（评审裁决②）：首读超时（零 token）→ 重开整条流成功。
 * （部分内容断流 → 上抛不重试的行为依赖 socket 层暴力断连仿真，真机压测覆盖，不在此硬造。）
 */
class LlmClientSanitizeRetryTest {

    private lateinit var server: MockWebServer

    // 与 NetworkModule.provideJson 同配置。
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        isLenient = true
    }

    private val client = LlmClient(OkHttpClient(), json)

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.close()
    }

    private fun config() = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE,
        apiKey = "test-key",
        baseUrl = server.url("/v1").toString(),
        modelName = "test-model",
    )

    private fun sse(vararg events: String) = events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    private fun contentChunk(text: String) =
        """{"choices":[{"delta":{"content":${json.encodeToString(String.serializer(), text)}}}]}"""

    private fun contentOf(tokens: List<StreamToken>): String =
        tokens.filterIsInstance<StreamToken.Content>().joinToString("") { it.text }

    @Test fun stream_sanitizes_leaked_closing_tags() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body(sse(contentChunk("夜色沉下来。"), contentChunk("[/dialogue]他没回头。")))
                .build(),
        )
        val tokens = client.streamChat(emptyList(), config()).toList()
        assertEquals("夜色沉下来。他没回头。", contentOf(tokens))
    }

    @Test fun stream_429_then_success_backs_off_and_retries() = runBlocking {
        // 判据：识别 429 → 退避等待（Retry-After: 0）→ 重试成功，而非立即报错丢弃
        server.enqueue(
            MockResponse.Builder().code(429).addHeader("Retry-After", "0").body("""{"error":"rate_limited"}""").build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body(sse(contentChunk("重试成功")))
                .build(),
        )
        val tokens = client.streamChat(emptyList(), config()).toList()
        assertEquals("重试成功", contentOf(tokens))
        assertEquals(2, server.requestCount)
    }

    @Test fun stream_zero_content_timeout_retries_once_then_succeeds() = runBlocking {
        // 判据（裁决②）：首读超时（零 token 窗口）→ 重开整条流恰 1 次并成功
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .bodyDelay(3, TimeUnit.SECONDS) // 超过 idleTimeoutSec=1 → 首读超时（零内容）
                .body(sse(contentChunk("第二次成功")))
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body(sse(contentChunk("第二次成功")))
                .build(),
        )
        val tokens = client.streamChat(emptyList(), config(), idleTimeoutSec = 1).toList()
        assertEquals("第二次成功", contentOf(tokens))
        assertEquals(2, server.requestCount)
    }

    @Test fun completion_sanitizes_leaked_closing_tags() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("""{"choices":[{"message":{"content":"总结[/dialogue]完成"},"finish_reason":"stop"}]}""")
                .build(),
        )
        val result = client.completion(emptyList(), config())
        assertEquals("总结完成", result)
    }

    @Test fun stream_429_exhausted_retries_propagates_error() = runBlocking {
        // 退避重试有上限（MAX_RETRIES）：持续 429 → 最终上抛 LlmError.Http，不无限等
        repeat(3) {
            server.enqueue(
                MockResponse.Builder().code(429).addHeader("Retry-After", "0").body("""{"error":"rate_limited"}""").build(),
            )
        }
        try {
            client.streamChat(emptyList(), config()).toList()
            fail("持续 429 应上抛 LlmError.Http")
        } catch (e: LlmError.Http) {
            assertEquals(429, e.statusCode)
            assertTrue(server.requestCount >= 3)
        }
    }
}
