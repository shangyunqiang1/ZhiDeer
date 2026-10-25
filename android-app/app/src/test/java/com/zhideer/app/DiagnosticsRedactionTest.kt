package com.zhideer.app

import com.zhideer.app.domain.usecase.DiagnosticsRedaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T35/#36 脱敏规则：诊断存储与导出文本绝不携带 API Key、令牌或 URL 凭据。
 */
class DiagnosticsRedactionTest {

    @Test
    fun 抹除apiKey令牌与url凭据() {
        val raw = "鉴权失败：API Key sk-abcdef123456789 无效；Bearer abc.def.ghi；" +
            "api_key=plain-secret；password=hunter2；https://user:pass@api.deepseek.com/v1"

        val redacted = DiagnosticsRedaction.redact(raw)

        assertFalse(redacted.contains("abcdef123456789"))
        assertFalse(redacted.contains("abc.def.ghi"))
        assertFalse(redacted.contains("plain-secret"))
        assertFalse(redacted.contains("hunter2"))
        assertFalse(redacted.contains("user:pass"))
        assertTrue(redacted.contains("sk-***"))
        assertTrue(redacted.contains("Bearer ***"))
        assertTrue(redacted.contains("apiKey=***"))
        assertTrue(redacted.contains("凭据=***"))
        assertTrue(redacted.contains("://***@"))
    }

    @Test
    fun 折叠空白并截断超长文本() {
        val raw = "第一行\n\n第二行\t  第三行 " + "很长的内容".repeat(100)

        val redacted = DiagnosticsRedaction.redact(raw, maxLength = 40)

        assertEquals(40, redacted.length)
        assertTrue(redacted.endsWith("…"))
        assertFalse(redacted.contains("\n"))
        assertTrue(redacted.startsWith("第一行 第二行 第三行"))
    }
}
