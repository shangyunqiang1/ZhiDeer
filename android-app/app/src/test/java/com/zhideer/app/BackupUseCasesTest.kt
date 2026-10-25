package com.zhideer.app

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.zhideer.app.data.ai.AiSessionStore
import com.zhideer.app.data.db.CalendarEntryEntity
import com.zhideer.app.data.db.CalendarReminderEntity
import com.zhideer.app.data.db.MemoryEntity
import com.zhideer.app.data.db.NoteEntity
import com.zhideer.app.data.db.NoteListItemEntity
import com.zhideer.app.data.db.OccurrenceSubTaskRecordEntity
import com.zhideer.app.data.db.ReminderEntity
import com.zhideer.app.data.db.SubTaskEntity
import com.zhideer.app.data.db.TaskEntity
import com.zhideer.app.data.db.TaskOccurrenceEntity
import com.zhideer.app.data.db.ZhiDeerDatabase
import com.zhideer.app.domain.model.AiChatMessage
import com.zhideer.app.domain.model.AiChatRole
import com.zhideer.app.domain.model.AiChatSession
import com.zhideer.app.domain.usecase.BackupUseCases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId

/**
 * T31/#32 验收测试：手动加密备份导出。
 * - 全部用户数据表 + AI 会话历史进入备份，密文不含明文数据与口令；
 * - 独立口令 PBKDF2 + AES-GCM 加密，格式版本化，可核对完整性与版本信息；
 * - 错误口令不可读取（中文提示）；导出失败不报告成功、不留损坏文件；
 * - API Key 与本机配置绝不进入备份内容。
 * 走真实 Room 文件库（close-reopen）+ AndroidSQLiteDriver + 真实 AiSessionStore。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupUseCasesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val password = "独立备份口令-鹿123"
    private lateinit var context: Context
    private lateinit var dbFile: File
    private lateinit var db: ZhiDeerDatabase
    private lateinit var sessionFile: File
    private lateinit var sessionStore: AiSessionStore
    private lateinit var exportDir: File
    private lateinit var useCases: BackupUseCases
    private val clock = mutableListOf(1769000000000L)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath("t31_backup_test.db")
        dbFile.parentFile?.mkdirs()
        context.deleteDatabase(dbFile.name)
        sessionFile = File(context.filesDir, "t31_backup_sessions.preferences_pb")
        sessionFile.delete()
        exportDir = File(context.filesDir, "t31_backup_out").apply { mkdirs() }
        exportDir.listFiles()?.forEach { it.deleteRecursively() }
        sessionStore = AiSessionStore(sessionFile)
        open()
    }

    private fun open() {
        db = Room.databaseBuilder<ZhiDeerDatabase>(
            context = context,
            name = dbFile.absolutePath,
        )
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        useCases = BackupUseCases(
            database = db,
            sessionHistory = { sessionStore.load() },
            now = { clock.last() },
        )
    }

    /** 离线重开：关闭后重新打开同一数据库文件（数据确来自文件库而非内存）。 */
    private fun reopen() {
        db.close()
        open()
    }

    @After
    fun tearDown() {
        db.close()
        sessionStore.close()
    }

    // ---------- 造数：全部用户数据表各至少一行（外键依赖按父先后插入） ----------

    private fun taskEntity(id: String, title: String) = TaskEntity(
        id = id,
        title = title,
        note = "备注-$id",
        priority = "NORMAL",
        category = "健康",
        dueEpochDay = null,
        dueAtMillis = null,
        dueZoneId = zone.id,
        status = "ACTIVE",
        trashedAt = null,
        createdAt = 1L,
        updatedAt = 1L,
        completedAt = null,
        postponedAt = null,
        cancelledAt = null,
    )

    private fun seedSampleData() = runBlocking {
        db.taskDao().upsert(taskEntity("task-1", "对芒果过敏的提醒"))
        db.taskDao().upsert(taskEntity("task-2", "买牛奶"))
        db.subTaskDao().upsert(
            SubTaskEntity(
                id = "sub-1", parentId = "task-1", title = "查看配料表", done = false,
                doneAt = null, sortOrder = 0, createdAt = 1L, updatedAt = 1L, trashedAt = null,
            ),
        )
        db.calendarEntryDao().upsert(
            CalendarEntryEntity(
                id = "entry-1", title = "医院复诊", startMillis = 100L, endMillis = 200L,
                timezoneId = zone.id, location = "社区医院", note = "带病历", category = "就医",
                taskId = "task-1", trashedAt = null, zoneConfirmedAt = null,
                createdAt = 1L, updatedAt = 1L,
            ),
        )
        db.noteDao().replaceNote(
            NoteEntity(
                id = "note-1", title = "购物清单", content = "牛奶和鸡蛋", tags = "",
                pinned = false, trashedAt = null, createdAt = 1L, updatedAt = 1L,
            ),
            listOf(
                NoteListItemEntity(id = "item-1", noteId = "note-1", text = "牛奶", checked = false, sortOrder = 0),
                NoteListItemEntity(id = "item-2", noteId = "note-1", text = "鸡蛋", checked = true, sortOrder = 1),
            ),
        )
        db.reminderDao().upsert(
            ReminderEntity(
                id = "rem-1", taskId = "task-1", relativeOffsetMillis = -3600000L,
                remindAtMillis = null, enabled = true, inexactConfirmed = true,
                notReadyReason = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
        db.taskOccurrenceDao().upsert(
            TaskOccurrenceEntity(
                id = "occ-1", taskId = "task-1", originalEpochDay = 20000L,
                effectiveEpochDay = 20000L, completed = false, completedAt = null, updatedAt = 1L,
            ),
        )
        db.occurrenceSubTaskRecordDao().upsert(
            OccurrenceSubTaskRecordEntity(
                id = "rec-1", taskId = "task-1", occurrenceId = "occ-1", subTaskId = "sub-1",
                done = false, doneAt = null, updatedAt = 1L,
            ),
        )
        db.calendarReminderDao().upsert(
            CalendarReminderEntity(
                id = "calrem-1", entryId = "entry-1", relativeOffsetMillis = -900000L,
                remindAtMillis = null, enabled = true, inexactConfirmed = false,
                notReadyReason = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
        db.memoryDao().upsert(
            MemoryEntity(
                id = "memory-1", content = "我对芒果过敏，推荐食谱时请避开",
                type = "FACT", source = "MANUAL", confirmed = true, enabled = true,
                createdAt = 1L, updatedAt = 1L, verifiedAt = 1L, expiresAt = null,
            ),
        )
    }

    private fun seedSession(): AiChatSession = AiChatSession(
        id = "session-1",
        title = "帮我安排明天买牛奶",
        messages = listOf(
            AiChatMessage(role = AiChatRole.USER, content = "帮我安排明天买牛奶"),
            AiChatMessage(role = AiChatRole.ASSISTANT, content = "已为你创建待办「买牛奶」"),
        ),
        createdAt = 5L,
        updatedAt = 6L,
    )

    // ---------- 用例 ----------

    @Test
    fun 导出生成加密文件且密文不含明文数据与口令() = runBlocking {
        seedSampleData()
        reopen()
        val target = File(exportDir, "zhideer-backup-test.zdbak")

        val summary = useCases.exportAll(password, target).getOrThrow()

        assertTrue("导出后目标文件应存在", target.isFile)
        assertEquals(BackupUseCases.FORMAT_VERSION, summary.formatVersion)
        assertTrue("导出文件应大于头部（40 字节）", summary.fileSizeBytes > 40)
        assertEquals(summary.fileSizeBytes, target.length())

        val raw = target.readBytes()
        // 格式头：magic "ZDBK" + 版本号（大端 int32）
        assertEquals("ZDBK", String(raw, 0, 4, Charsets.US_ASCII))
        assertEquals(BackupUseCases.FORMAT_VERSION, readIntBE(raw, 4))
        // 密文不含任何已知明文数据与口令
        val asText = String(raw, Charsets.ISO_8859_1)
        assertFalse("密文不应包含待办标题明文", asText.contains("对芒果过敏的提醒"))
        assertFalse("密文不应包含日程标题明文", asText.contains("医院复诊"))
        assertFalse("密文不应包含记忆内容明文", asText.contains("芒果"))
        assertFalse("密文不应包含口令", asText.contains(password))
    }

    @Test
    fun 正确口令解出原数据且表计数与会话一致() = runBlocking {
        seedSampleData()
        sessionStore.save(listOf(seedSession()))
        reopen()
        val target = File(exportDir, "full.zdbak")

        val summary = useCases.exportAll(password, target).getOrThrow()
        assertEquals(clock.last(), summary.exportedAt)
        // T27/#28 数据库升至 v13（memories 来源引用拆列），备份记录的版本随之演进
        assertEquals(13, summary.dbVersion)

        // 校验入口：版本信息与清单计数核对
        val verification = useCases.verify(target, password).getOrThrow()
        assertTrue("清单计数应与实际行数一致", verification.integrityOk)
        assertEquals(BackupUseCases.FORMAT_VERSION, verification.formatVersion)
        assertEquals(13, verification.dbVersion)
        assertEquals(1, verification.aiSessionCount)

        // 内容核对：各表行数、字段原文、会话历史
        val payload = useCases.readPayload(target, password).getOrThrow()
        assertEquals(
            mapOf(
                "tasks" to 2,
                "sub_tasks" to 1,
                "calendar_entries" to 1,
                "notes" to 1,
                "note_list_items" to 2,
                "reminders" to 1,
                "task_occurrences" to 1,
                "calendar_reminders" to 1,
                "occurrence_subtask_records" to 1,
                "memories" to 1,
            ),
            payload.tableCounts,
        )
        assertEquals(1, payload.aiSessionCount)
        val tasks = payload.tables.getValue("tasks").map { it.jsonObject }
        assertEquals("对芒果过敏的提醒", tasks.first { it["id"]!!.jsonPrimitive.content == "task-1" }["title"]!!.jsonPrimitive.content)
        val items = payload.tables.getValue("note_list_items").map { it.jsonObject }
        assertEquals(2, items.size)
        assertEquals("鸡蛋", items.first { it["id"]!!.jsonPrimitive.content == "item-2" }["text"]!!.jsonPrimitive.content)
        val session = payload.aiSessions.single()
        assertEquals("帮我安排明天买牛奶", session.title)
        assertEquals(AiChatRole.USER, session.messages.first().role)
        assertEquals("已为你创建待办「买牛奶」", session.messages.last().content)
    }

    @Test
    fun 错误口令无法读取备份并返回中文提示() = runBlocking {
        seedSampleData()
        reopen()
        val target = File(exportDir, "wrongpw.zdbak")
        useCases.exportAll(password, target).getOrThrow()

        val wrong = "另一个口令456"
        val verifyResult = useCases.verify(target, wrong)
        assertTrue("错误口令必须失败", verifyResult.isFailure)
        val message = verifyResult.exceptionOrNull()?.message.orEmpty()
        assertTrue("失败提示应为中文口令错误：$message", message.contains("口令错误"))

        assertTrue("readPayload 同样应失败", useCases.readPayload(target, wrong).isFailure)

        // 原备份未被破坏：正确口令仍可完整读取
        assertTrue("正确口令应仍可读取", useCases.verify(target, password).isSuccess)
    }

    @Test
    fun 空口令被拒绝() = runBlocking {
        val target = File(exportDir, "emptypw.zdbak")
        val result = useCases.exportAll("", target)
        assertTrue(result.isFailure)
        assertTrue(
            "空口令提示应为中文：${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message.orEmpty().contains("口令"),
        )
        assertFalse(target.exists())
    }

    @Test
    fun 空库导出成功且各表计数为零() = runBlocking {
        val target = File(exportDir, "empty.zdbak")
        val summary = useCases.exportAll(password, target).getOrThrow()

        assertTrue(target.isFile)
        assertEquals(BackupUseCases.BACKUP_TABLES.size, summary.tableCounts.size)
        assertEquals(0, summary.tableCounts.values.sum())
        assertEquals(0, summary.aiSessionCount)

        val verification = useCases.verify(target, password).getOrThrow()
        assertTrue(verification.integrityOk)
        val payload = useCases.readPayload(target, password).getOrThrow()
        BackupUseCases.BACKUP_TABLES.forEach { table ->
            assertEquals("空库 $table 应为空数组", 0, payload.tables.getValue(table).size)
        }
    }

    @Test
    fun 中文与特殊字符内容完整导出() = runBlocking {
        val weirdTitle = "标题\"引号\"换行\n测试🦌与\u0001控制符"
        db.taskDao().upsert(taskEntity("task-w", weirdTitle))
        sessionStore.save(
            listOf(
                AiChatSession(
                    id = "session-w",
                    title = "🦌会话标题",
                    messages = listOf(AiChatMessage(role = AiChatRole.ASSISTANT, content = "emoji🦌与\"引号\"以及\n换行")),
                    createdAt = 1L,
                    updatedAt = 2L,
                ),
            ),
        )
        reopen()
        val target = File(exportDir, "weird.zdbak")
        useCases.exportAll(password, target).getOrThrow()

        val payload = useCases.readPayload(target, password).getOrThrow()
        val row = payload.tables.getValue("tasks").single().jsonObject
        assertEquals("特殊字符应原样保留", weirdTitle, row["title"]!!.jsonPrimitive.content)
        assertEquals("🦌会话标题", payload.aiSessions.single().title)
        assertEquals("emoji🦌与\"引号\"以及\n换行", payload.aiSessions.single().messages.single().content)
    }

    @Test
    fun 备份内容不包含AI配置与密钥字段() = runBlocking {
        // 模拟本机已配置 AI：普通配置项写入真实配置文件名（含特征字符串）。
        // 加密 Key 存储（EncryptedSharedPreferences/AndroidKeystore）在 Robolectric 下不可实例化，
        // 但备份路径从不读取任何配置文件——以配置特征字符串断言排除，防止回归。
        context.getSharedPreferences("ai_chat_config", Context.MODE_PRIVATE).edit()
            .putString("model", "deepseek-chat")
            .putString("base_url", "https://api.deepseek.com")
            .putString("api_key", "sk-TEST-KEY-123456")
            .apply()

        seedSampleData()
        reopen()
        val target = File(exportDir, "nokey.zdbak")
        useCases.exportAll(password, target).getOrThrow()

        // 解密后的明文内容不含任何配置特征字符串与 Key 字段名
        val plain = String(useCases.decryptPayloadBytes(target, password).getOrThrow(), Charsets.UTF_8)
        assertFalse("备份不应包含模型配置", plain.contains("deepseek-chat"))
        assertFalse("备份不应包含 Base URL", plain.contains("api.deepseek.com"))
        assertFalse("备份不应包含 API Key 特征串", plain.contains("sk-TEST-KEY-123456"))
        assertFalse("备份不应包含 apiKey 字段名", plain.contains("apiKey"))

        // 结构断言：表清单恰好是全部用户数据表，无任何配置表混入
        val payload = useCases.readPayload(target, password).getOrThrow()
        assertEquals(BackupUseCases.BACKUP_TABLES.toSet(), payload.tables.keys)
    }

    @Test
    fun 导出失败时不报告成功且不留下损坏文件() = runBlocking {
        seedSampleData()
        reopen()
        // 目标路径是一个非空目录：无法被备份文件覆盖，模拟导出中断
        // （Windows 下空目录会被 Files.move 直接替换，非空目录才会失败）
        val target = File(exportDir, "occupied")
        assertTrue(target.mkdirs())
        val marker = File(target, "占位.txt")
        marker.writeText("目录非空，不可覆盖")
        val markerBytes = marker.readBytes()

        val result = useCases.exportAll(password, target)

        assertTrue("写入失败必须返回失败而不是成功", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("失败提示应为中文：$message", message.contains("失败"))
        assertTrue("原目录不应被破坏", target.isDirectory)
        assertTrue("占位文件不应被改动", marker.isFile && marker.readBytes().contentEquals(markerBytes))
        // 不留下 .tmp 临时残留
        val leftovers = exportDir.listFiles { f -> f.name.endsWith(".tmp") }.orEmpty()
        assertEquals("不应留下临时文件残留", 0, leftovers.size)
    }

    @Test
    fun 会话历史读取失败时导出失败且不生成文件() = runBlocking {
        val broken = BackupUseCases(
            database = db,
            sessionHistory = { throw IllegalStateException("会话存储被占用") },
            now = { clock.last() },
        )
        val target = File(exportDir, "broken.zdbak")
        val result = broken.exportAll(password, target)
        assertTrue("会话读取失败必须整体失败", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("失败提示应指出会话历史：$message", message.contains("会话"))
        assertFalse("失败时不应生成备份文件", target.exists())
    }

    private fun readIntBE(raw: ByteArray, offset: Int): Int =
        ((raw[offset].toInt() and 0xFF) shl 24) or
            ((raw[offset + 1].toInt() and 0xFF) shl 16) or
            ((raw[offset + 2].toInt() and 0xFF) shl 8) or
            (raw[offset + 3].toInt() and 0xFF)
}
