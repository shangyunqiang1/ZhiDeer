package com.zhideer.app

import com.zhideer.app.data.diagnostics.DiagnosticsFailureStore
import com.zhideer.app.domain.model.DiagnosticsFailure
import com.zhideer.app.domain.model.DiagnosticsFailureCategory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * T35/#36 失败记录存储：只保留最近少量脱敏记录，重复失败不刷屏，清空后为空。
 */
class DiagnosticsFailureStoreTest {

    private lateinit var dir: File
    private lateinit var store: DiagnosticsFailureStore

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "t35-failure-store-${System.nanoTime()}")
        check(dir.mkdirs() || dir.isDirectory) { "无法创建测试目录" }
        store = DiagnosticsFailureStore(File(dir, "failures.preferences_pb"))
    }

    @After
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private fun failure(index: Int, summary: String = "失败 #$index") = DiagnosticsFailure(
        id = "failure-$index",
        occurredAtMillis = 1_000L + index,
        category = DiagnosticsFailureCategory.MODEL_CONNECTION,
        summary = summary,
    )

    @Test
    fun 相同连续失败只保留一条并更新时间() = runBlocking {
        store.append(failure(1, "连接超时"))
        store.append(failure(2, "连接超时"))

        val records = store.load()
        assertEquals(1, records.size)
        assertEquals("failure-2", records.single().id)
        assertEquals(1_002L, records.single().occurredAtMillis)
    }

    @Test
    fun 最多保留五十条且最新的在前() = runBlocking {
        repeat(55) { index -> store.append(failure(index)) }

        val records = store.load()
        assertEquals(DiagnosticsFailureStore.MAX_RECORDS, records.size)
        assertEquals("failure-54", records.first().id)
        assertEquals("failure-5", records.last().id)
        assertTrue(records.none { it.id == "failure-0" })
    }

    @Test
    fun 清空后没有失败记录() = runBlocking {
        store.append(failure(1))
        store.clear()

        assertEquals(emptyList<DiagnosticsFailure>(), store.load())
    }
}
