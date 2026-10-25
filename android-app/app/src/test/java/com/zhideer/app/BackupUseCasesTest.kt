package com.zhideer.app

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.zhideer.app.data.ai.AdvisorRecordStore
import com.zhideer.app.data.ai.AiProposalStore
import com.zhideer.app.data.ai.AiSessionStore
import com.zhideer.app.data.db.CalendarEntryEntity
import com.zhideer.app.data.db.CalendarReminderEntity
import com.zhideer.app.data.db.ClockHandoffEntity
import com.zhideer.app.data.db.MemoryEntity
import com.zhideer.app.data.db.NoteEntity
import com.zhideer.app.data.db.NoteListItemEntity
import com.zhideer.app.data.db.OccurrenceSubTaskRecordEntity
import com.zhideer.app.data.db.ReminderEntity
import com.zhideer.app.data.db.ReminderScheduleEntity
import com.zhideer.app.data.db.SubTaskEntity
import com.zhideer.app.data.db.TaskEntity
import com.zhideer.app.data.db.TaskOccurrenceEntity
import com.zhideer.app.data.db.ZhiDeerDatabase
import com.zhideer.app.data.repository.CalendarRepositoryImpl
import com.zhideer.app.data.repository.MemoriesRepositoryImpl
import com.zhideer.app.data.repository.RemindersRepositoryImpl
import com.zhideer.app.data.repository.TaskOccurrenceRepositoryImpl
import com.zhideer.app.data.repository.TasksRepositoryImpl
import com.zhideer.app.domain.model.AdvisorAdoptionOutcome
import com.zhideer.app.domain.model.AdvisorAdoptionRecord
import com.zhideer.app.domain.model.AdvisorEvidenceSnapshot
import com.zhideer.app.domain.model.AdvisorFeedbackRecord
import com.zhideer.app.domain.model.AdvisorFeedbackType
import com.zhideer.app.domain.model.AdvisorProposalBinding
import com.zhideer.app.domain.model.AdvisorRecords
import com.zhideer.app.domain.model.AiChatMessage
import com.zhideer.app.domain.model.AiChatRole
import com.zhideer.app.domain.model.AiChatSession
import com.zhideer.app.domain.model.AiProposal
import com.zhideer.app.domain.model.AiProposalKind
import com.zhideer.app.domain.model.AiProposalStatus
import com.zhideer.app.domain.model.ClockHandoffStatus
import com.zhideer.app.domain.usecase.AdvisorUseCases
import com.zhideer.app.domain.usecase.AiContextAuthorizer
import com.zhideer.app.domain.usecase.AiContextCategory
import com.zhideer.app.domain.usecase.BackupUseCases
import com.zhideer.app.domain.usecase.CalendarUseCases
import com.zhideer.app.domain.usecase.MemoryUseCases
import com.zhideer.app.domain.usecase.ReminderUseCases
import com.zhideer.app.domain.usecase.TaskUseCases
import com.zhideer.app.platform.AlarmScheduler
import com.zhideer.app.ui.BACKUP_OLD_DATA_BOUNDARY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.time.ZoneId
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * T31/#32 备份导出与 T32/#33 整库恢复验收测试，T33/#34 扩展记忆来源与参谋记录：
 * - 全部用户数据表（含 memories、clock_handoffs）+ AI 会话历史 + 参谋记录进入备份，
 *   密文不含明文数据与口令；
 * - 独立口令 PBKDF2 + AES-GCM 加密，格式版本化，可核对完整性与版本信息；
 * - 错误口令不可读取（中文提示）；导出失败不报告成功、不留损坏文件；
 * - API Key 与本机配置绝不进入备份内容；
 * - 恢复保持记忆来源/停用状态；不可核验来源不被恢复成可用事实；
 * - 恢复后旧参谋提案失效、AI 授权重新确认、提醒先暂停规则继续生效。
 * 走真实 Room 文件库（close-reopen）+ AndroidSQLiteDriver + 真实 DataStore。
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
    private lateinit var advisorFile: File
    private lateinit var advisorStore: AdvisorRecordStore
    private lateinit var proposalFile: File
    private lateinit var proposalStore: AiProposalStore
    private lateinit var authorizer: AiContextAuthorizer
    private lateinit var advisor: AdvisorUseCases
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
        advisorFile = File(context.filesDir, "t33_advisor_records.preferences_pb").apply { delete() }
        advisorStore = AdvisorRecordStore(advisorFile)
        proposalFile = File(context.filesDir, "t33_ai_proposals.preferences_pb").apply { delete() }
        proposalStore = AiProposalStore(proposalFile)
        authorizer = AiContextAuthorizer()
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
        // 参谋记录还原与生产装配一致：恢复备份记录，并让恢复前待确认的旧建议提案失效
        advisor = AdvisorUseCases(
            taskUseCases = TaskUseCases(
                repository = TasksRepositoryImpl(db.taskDao(), Dispatchers.IO),
                idGen = { "task-gen" },
                now = { clock.last() },
                zoneId = zone.id,
                occurrenceRepository = TaskOccurrenceRepositoryImpl(db.taskOccurrenceDao(), Dispatchers.IO),
            ),
            calendarUseCases = CalendarUseCases(
                repository = CalendarRepositoryImpl(db.calendarEntryDao(), Dispatchers.IO),
                idGen = { "entry-gen" },
                now = { clock.last() },
                zoneId = zone.id,
            ),
            memoryUseCases = MemoryUseCases(
                repository = MemoriesRepositoryImpl(db.memoryDao(), Dispatchers.IO),
                idGen = { "memory-gen" },
                now = { clock.last() },
            ),
            authorizer = authorizer,
            proposalStore = proposalStore,
            recordStore = advisorStore,
            idGen = { "advisor-gen" },
            now = { clock.last() },
            zoneId = zone,
        )
        useCases = BackupUseCases(
            database = db,
            sessionHistory = { sessionStore.load() },
            now = { clock.last() },
            // 恢复出口与生产装配（AppContainer）一致：Room 事务成功后还原会话历史
            sessionRestorer = { sessions -> sessionStore.save(sessions) },
            // T33/#34：参谋记录（反馈/采纳执行记录/依据绑定）与 AI 授权重置
            advisorHistory = { advisorStore.load() },
            advisorRestorer = { records -> advisor.restoreAdvisorRecords(records) },
            aiAuthorizationResetter = { authorizer.revoke() },
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
        advisorStore.close()
        proposalStore.close()
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
        db.clockHandoffDao().upsert(
            ClockHandoffEntity(
                id = "handoff-1", hour = 7, minute = 30, label = "晨跑",
                targetEpochDay = null, weekdays = "", requestedAtMillis = 1L,
                status = ClockHandoffStatus.PENDING_CHECK.name, note = "系统未返回保存回执，待核对",
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

    /**
     * 记忆来源关联（T33/#34）：来源缺失且已停用、来源记录已彻底删除两种状态。
     * 恢复必须保持原文、来源 id、来源缺失与停用状态，不得重新启用。
     */
    private fun seedMemorySources() = runBlocking {
        db.memoryDao().upsert(
            MemoryEntity(
                id = "memory-src", content = "购物清单里写过我喜欢低糖",
                type = "PREFERENCE", source = "MANUAL", confirmed = true, enabled = false,
                createdAt = 2L, updatedAt = 2L, verifiedAt = 2L, expiresAt = null,
                sourceRefType = "NOTE", sourceRefId = "note-1", sourceRefMissing = true,
            ),
        )
        db.memoryDao().upsert(
            MemoryEntity(
                id = "memory-missing", content = "来源待办已删除的偏好",
                type = "PREFERENCE", source = "MANUAL", confirmed = true, enabled = false,
                createdAt = 3L, updatedAt = 3L, verifiedAt = 3L, expiresAt = null,
                sourceRefType = "TASK", sourceRefId = "task-gone", sourceRefMissing = true,
            ),
        )
    }

    /** 参谋记录集合（T33/#34）：建议反馈、采纳执行记录与提案依据绑定（含依据快照与指纹）。 */
    private fun advisorSeed(): AdvisorRecords = AdvisorRecords(
        feedback = listOf(
            AdvisorFeedbackRecord(
                id = "feedback-1",
                suggestionId = "overdue-task:task-1",
                suggestionKind = "OVERDUE_TASK",
                title = "处理过期待办「对芒果过敏的提醒」",
                type = AdvisorFeedbackType.NEVER_SUGGEST,
                adjustmentNote = "",
                evidence = listOf(taskEvidence()),
                createdAt = 10L,
            ),
            AdvisorFeedbackRecord(
                id = "feedback-2",
                suggestionId = "schedule-conflict:entry-1:entry-2",
                suggestionKind = "SCHEDULE_CONFLICT",
                title = "明天日程时间重叠",
                type = AdvisorFeedbackType.ADJUST,
                adjustmentNote = "改为明天下午",
                evidence = emptyList(),
                createdAt = 11L,
            ),
        ),
        adoptions = listOf(
            AdvisorAdoptionRecord(
                id = "adoption-1",
                suggestionId = "overdue-task:task-1",
                suggestionKind = "OVERDUE_TASK",
                title = "处理过期待办「对芒果过敏的提醒」",
                proposalId = "proposal-history",
                outcome = AdvisorAdoptionOutcome.INVALIDATED,
                detail = "依据记录已变化：旧提案已失效，未执行任何写入",
                createdAt = 12L,
            ),
        ),
        bindings = listOf(
            AdvisorProposalBinding(
                proposalId = "proposal-history",
                suggestionId = "overdue-task:task-1",
                suggestionKind = "OVERDUE_TASK",
                title = "处理过期待办「对芒果过敏的提醒」",
                evidence = listOf(taskEvidence()),
                basisFingerprint = "overdue-task:task-1|OVERDUE_TASK|TASK:task-1:TASK|snapshot",
                createdAt = 13L,
            ),
        ),
    )

    private fun taskEvidence() = AdvisorEvidenceSnapshot(
        recordKind = AiProposalKind.TASK,
        recordId = "task-1",
        title = "对芒果过敏的提醒",
        detail = "待办「对芒果过敏的提醒」已过期未完成",
        recordFingerprint = "TASK|snapshot",
    )

    private fun seedAdvisorRecords() = runBlocking {
        val seed = advisorSeed()
        for (record in seed.feedback) advisorStore.appendFeedback(record)
        for (record in seed.adoptions) advisorStore.upsertAdoption(record)
        for (binding in seed.bindings) advisorStore.bindProposal(binding)
    }

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
        // 数据库版本随 schema 演进（当前 v14，动态读取避免每次升版改测试）
        assertEquals(currentDbVersion(), summary.dbVersion)

        // 校验入口：版本信息与清单计数核对
        val verification = useCases.verify(target, password).getOrThrow()
        assertTrue("清单计数应与实际行数一致", verification.integrityOk)
        assertEquals(BackupUseCases.FORMAT_VERSION, verification.formatVersion)
        assertEquals(currentDbVersion(), verification.dbVersion)
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
                "clock_handoffs" to 1,
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
        seedAdvisorRecords()
        reopen()
        val target = File(exportDir, "nokey.zdbak")
        useCases.exportAll(password, target).getOrThrow()

        // 解密后的明文内容不含任何配置特征字符串与 Key 字段名
        val plain = String(useCases.decryptPayloadBytes(target, password).getOrThrow(), Charsets.UTF_8)
        assertFalse("备份不应包含模型配置", plain.contains("deepseek-chat"))
        assertFalse("备份不应包含 Base URL", plain.contains("api.deepseek.com"))
        assertFalse("备份不应包含 API Key 特征串", plain.contains("sk-TEST-KEY-123456"))
        assertFalse("备份不应包含 apiKey 字段名", plain.contains("apiKey"))

        // 结构断言：表清单恰好是全部用户数据表，无任何配置表混入；参谋记录在范围内也不含 Key
        val payload = useCases.readPayload(target, password).getOrThrow()
        assertEquals(BackupUseCases.BACKUP_TABLES.toSet(), payload.tables.keys)
        assertEquals("参谋记录应在备份范围内", 2, payload.advisorRecords.feedback.size)
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

    // ==================== T32/#33 核对备份并整库恢复 ====================

    @Test
    fun 核对备份返回可读摘要且不改动当前数据() = runBlocking {
        seedSampleData()
        sessionStore.save(listOf(seedSession()))
        reopen()
        val target = File(exportDir, "inspect.zdbak")
        useCases.exportAll(password, target).getOrThrow()
        // 核对前当前库又发生变化：新增一行待办（核对必须是只读操作）
        db.taskDao().upsert(taskEntity("task-new", "核对前新增"))
        reopen()

        val inspection = useCases.inspectBackup(target, password).getOrThrow()

        assertEquals(BackupUseCases.FORMAT_VERSION, inspection.formatVersion)
        assertEquals("备份库版本应为当前 schema 版本", currentDbVersion(), inspection.dbVersion)
        assertEquals(clock.last(), inspection.exportedAt)
        assertEquals("摘要应展示备份时点各表计数", 2, inspection.tableCounts.getValue("tasks"))
        assertEquals(1, inspection.tableCounts.getValue("memories"))
        assertEquals(1, inspection.aiSessionCount)
        assertTrue("同版本备份应兼容", inspection.compatible)
        assertNull("兼容时不应有拒绝原因", inspection.incompatibilityReason)
        assertTrue("清单应与实际内容一致", inspection.integrityOk)
        // 核对是只读操作：当前库新增行仍在、计数为 3 而非备份的 2
        assertEquals("核对前新增", db.taskDao().get("task-new")?.title)
    }

    @Test
    fun 整库恢复覆盖当前数据回到备份原样() = runBlocking {
        seedSampleData()
        sessionStore.save(listOf(seedSession()))
        reopen()
        val target = File(exportDir, "restore-full.zdbak")
        val exported = useCases.exportAll(password, target).getOrThrow()

        // 恢复前当前数据被大幅改动：同 id 篡改、新增行、删除便签、会话替换
        db.taskDao().upsert(taskEntity("task-2", "被篡改的标题"))
        db.taskDao().upsert(taskEntity("task-new", "恢复前新增"))
        db.noteDao().deleteById("note-1")
        sessionStore.save(emptyList())
        reopen()
        // 本机配置特征串：API Key 与配置不属于备份范围，恢复不得触碰
        context.getSharedPreferences("ai_chat_config", Context.MODE_PRIVATE).edit()
            .putString("api_key", "sk-RESTORE-KEY-999")
            .apply()

        val summary = useCases.restoreAll(target, password).getOrThrow()

        // 覆盖式整库替换：篡改与新增全部消失，回到备份时点（行级核对）
        assertEquals("对芒果过敏的提醒", db.taskDao().get("task-1")?.title)
        assertEquals("买牛奶", db.taskDao().get("task-2")?.title)
        assertNull("恢复前新增的行应被清除", db.taskDao().get("task-new"))
        assertEquals("查看配料表", db.subTaskDao().get("sub-1")?.title)
        assertEquals("医院复诊", db.calendarEntryDao().get("entry-1")?.title)
        val note = db.noteDao().get("note-1")
        assertEquals("购物清单", note?.note?.title)
        assertEquals("删除的便签应随整库恢复回归", 2, note?.items?.size)
        assertEquals("牛奶", note?.items?.firstOrNull { it.id == "item-1" }?.text)
        assertEquals("长期记忆应恢复原文", "我对芒果过敏，推荐食谱时请避开", db.memoryDao().get("memory-1")?.content)

        // 摘要与备份清单一致
        assertEquals(exported.tableCounts, summary.tableCounts)
        assertEquals("恢复摘要的库版本应与导出一致", exported.dbVersion, summary.dbVersion)
        assertEquals(clock.last(), summary.exportedAt)
        assertEquals(1, summary.aiSessionCount)

        // AI 会话历史还原为备份时点内容
        val sessions = sessionStore.load()
        assertEquals(1, sessions.size)
        assertEquals("帮我安排明天买牛奶", sessions.single().title)
        assertEquals("已为你创建待办「买牛奶」", sessions.single().messages.last().content)

        // 本机配置与 API Key 不受恢复影响
        assertEquals(
            "sk-RESTORE-KEY-999",
            context.getSharedPreferences("ai_chat_config", Context.MODE_PRIVATE)
                .getString("api_key", null),
        )
    }

    @Test
    fun 恢复后全部提醒暂停且摘要展示清单() = runBlocking {
        seedSampleData()
        reopen()
        val target = File(exportDir, "restore-pause.zdbak")
        useCases.exportAll(password, target).getOrThrow()
        reopen()

        val summary = useCases.restoreAll(target, password).getOrThrow()

        // 恢复的提醒一律先暂停：不立即重建任何未来提醒，等待用户确认
        val taskReminder = db.reminderDao().get("rem-1")
        assertNotNull(taskReminder)
        assertFalse("恢复的待办提醒应暂停", taskReminder!!.enabled)
        val calendarReminder = db.calendarReminderDao().get("calrem-1")
        assertNotNull(calendarReminder)
        assertFalse("恢复的日程提醒应暂停", calendarReminder!!.enabled)

        // 摘要展示暂停清单（可理解的来源名称）
        assertEquals(1, summary.pausedReminderCount)
        assertEquals(1, summary.pausedCalendarReminderCount)
        assertTrue(
            "清单应含待办标题：${summary.pausedReminderSources}",
            summary.pausedReminderSources.contains("对芒果过敏的提醒"),
        )
        assertTrue(
            "清单应含日程标题：${summary.pausedCalendarReminderSources}",
            summary.pausedCalendarReminderSources.contains("医院复诊"),
        )
    }

    @Test
    fun 恢复的提醒经既有入口确认后可重新启用() = runBlocking {
        seedSampleData()
        // 相对提醒需要具体时刻截止才能换算触发时间：给 task-1 设置未来时刻截止
        db.taskDao().upsert(taskEntity("task-1", "对芒果过敏的提醒").copy(dueAtMillis = clock.last() + 3_600_000))
        reopen()
        val target = File(exportDir, "restore-enable.zdbak")
        useCases.exportAll(password, target).getOrThrow()
        reopen()

        val summary = useCases.restoreAll(target, password).getOrThrow()
        assertFalse("恢复后应先暂停", summary.pausedReminderCount == 0)

        val scheduler = RecordingScheduler()
        val reminders = ReminderUseCases(
            repository = RemindersRepositoryImpl(db.reminderDao(), Dispatchers.IO),
            tasksRepository = TasksRepositoryImpl(db.taskDao(), Dispatchers.IO),
            scheduler = scheduler,
            idGen = { "gen-id" },
            now = { clock.last() },
        )
        // 用户经既有提醒入口逐项确认：按既有权限/未就绪逻辑重建未来提醒
        val enabled = reminders.setEnabled("rem-1", true).getOrThrow()
        assertTrue(enabled.enabled)
        assertTrue(
            "确认启用后应重新调度：${scheduler.scheduled}",
            scheduler.scheduled.contains("rem-1"),
        )
    }

    @Test
    fun 错误口令恢复失败且当前数据完好() = runBlocking {
        seedSampleData()
        reopen()
        val target = File(exportDir, "restore-wrongpw.zdbak")
        useCases.exportAll(password, target).getOrThrow()
        // 恢复前当前数据被改动（有效数据）
        db.taskDao().upsert(taskEntity("task-2", "当前有效数据"))
        sessionStore.save(listOf(seedSession().copy(id = "session-now", title = "当前会话")))
        reopen()

        val result = useCases.restoreAll(target, "错误口令789")

        assertTrue("错误口令必须失败", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("失败提示应为中文口令错误：$message", message.contains("口令错误"))
        // 当前有效数据原样保留
        assertEquals("当前有效数据", db.taskDao().get("task-2")?.title)
        assertEquals("对芒果过敏的提醒", db.taskDao().get("task-1")?.title)
        assertEquals("会话历史未被改动", "当前会话", sessionStore.load().single().title)
    }

    @Test
    fun 版本不兼容备份核对即拒绝且恢复不覆盖当前数据() = runBlocking {
        seedSampleData()
        reopen()
        db.taskDao().upsert(taskEntity("task-2", "当前有效数据"))
        reopen()
        // 手工构造 dbVersion 高于当前 schema 的合法加密备份
        val future = rawBackupFile(testPayload(dbVersion = currentDbVersion() + 1), "future.zdbak")

        val inspection = useCases.inspectBackup(future, password).getOrThrow()
        assertFalse("高版本备份应标记不兼容", inspection.compatible)
        assertTrue(
            "不兼容原因应为中文：${inspection.incompatibilityReason}",
            inspection.incompatibilityReason.orEmpty().contains("版本"),
        )

        val result = useCases.restoreAll(future, password)
        assertTrue("版本不兼容必须拒绝恢复", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("拒绝说明应为中文：$message", message.contains("版本"))
        assertTrue("应说明当前数据未改动：$message", message.contains("未改动"))
        assertEquals("当前数据未被覆盖", "当前有效数据", db.taskDao().get("task-2")?.title)
    }

    @Test
    fun 损坏备份恢复失败且当前数据完好() = runBlocking {
        seedSampleData()
        reopen()
        db.taskDao().upsert(taskEntity("task-2", "当前有效数据"))
        reopen()
        // 损坏一：不是知时鹿备份文件
        val garbage = File(exportDir, "garbage.zdbak")
        garbage.writeText("ZDBK垃圾数据不是合法备份")
        val garbageResult = useCases.restoreAll(garbage, password)
        assertTrue(garbageResult.isFailure)
        assertTrue(
            "提示应指出损坏：${garbageResult.exceptionOrNull()?.message}",
            garbageResult.exceptionOrNull()?.message.orEmpty().contains("损坏") ||
                garbageResult.exceptionOrNull()?.message.orEmpty().contains("不是知时鹿备份"),
        )
        // 损坏二：合法文件被截断（密文不完整，GCM 校验失败）
        val good = File(exportDir, "good.zdbak")
        useCases.exportAll(password, good).getOrThrow()
        val truncated = File(exportDir, "truncated.zdbak")
        truncated.writeBytes(good.readBytes().copyOfRange(0, (good.length() / 2).toInt()))
        val truncatedResult = useCases.restoreAll(truncated, password)
        assertTrue(truncatedResult.isFailure)
        assertTrue(
            "截断文件应提示口令错误或损坏：${truncatedResult.exceptionOrNull()?.message}",
            truncatedResult.exceptionOrNull()?.message.orEmpty().contains("口令错误") ||
                truncatedResult.exceptionOrNull()?.message.orEmpty().contains("损坏"),
        )
        // 当前数据两次失败后均原样
        assertEquals("当前有效数据", db.taskDao().get("task-2")?.title)
    }

    @Test
    fun 恢复中途失败时事务回滚当前数据原样() = runBlocking {
        seedSampleData()
        reopen()
        // 当前数据被改动为 Y，会话有一条当前记录
        db.taskDao().upsert(taskEntity("task-2", "当前数据Y"))
        sessionStore.save(listOf(seedSession().copy(id = "session-now", title = "当前会话")))
        reopen()
        // 手工构造合法但含悬挂外键的备份：sub_tasks 引用不存在的父待办，
        // 恢复将在清空 tasks 之后、插入 sub_tasks 时失败 → 事务整体回滚
        val ghostRow = buildJsonObject {
            put("id", JsonPrimitive("sub-ghost"))
            put("parentId", JsonPrimitive("task-ghost"))
            put("title", JsonPrimitive("悬挂引用"))
            put("done", JsonPrimitive(0L))
            put("doneAt", JsonNull)
            put("sortOrder", JsonPrimitive(0L))
            put("createdAt", JsonPrimitive(1L))
            put("updatedAt", JsonPrimitive(1L))
            put("trashedAt", JsonNull)
        }
        val payload = testPayload(
            dbVersion = currentDbVersion(),
            overrides = mapOf("sub_tasks" to listOf(ghostRow)),
        )
        val file = rawBackupFile(payload, "ghost.zdbak")

        val result = useCases.restoreAll(file, password)

        assertTrue("悬挂外键必须导致恢复失败", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("失败提示应为中文：$message", message.contains("恢复"))
        // 事务回滚：当前数据 Y 原样保留，未被半恢复状态污染
        assertEquals("当前数据Y", db.taskDao().get("task-2")?.title)
        assertEquals("对芒果过敏的提醒", db.taskDao().get("task-1")?.title)
        assertEquals("会话历史未被改动", "当前会话", sessionStore.load().single().title)
    }

    @Test
    fun 恢复空备份清空当前全部数据与会话() = runBlocking {
        // 当前库为空时先导出一份空备份
        val target = File(exportDir, "empty-restore.zdbak")
        useCases.exportAll(password, target).getOrThrow()
        // 之后填入数据与会话
        seedSampleData()
        sessionStore.save(listOf(seedSession()))
        reopen()

        val summary = useCases.restoreAll(target, password).getOrThrow()

        assertEquals("各表应全部清空", 0, summary.tableCounts.values.sum())
        assertEquals(0, summary.aiSessionCount)
        assertEquals(0, countTable("tasks"))
        assertEquals(0, countTable("reminders"))
        assertEquals(0, sessionStore.load().size)
        assertEquals(0, countTable("memories"))
    }

    @Test
    fun 会话历史还原失败时如实报告失败() = runBlocking {
        seedSampleData()
        reopen()
        val target = File(exportDir, "restore-broken-session.zdbak")
        useCases.exportAll(password, target).getOrThrow()
        reopen()
        val broken = BackupUseCases(
            database = db,
            sessionHistory = { sessionStore.load() },
            sessionRestorer = { throw IllegalStateException("会话文件被占用") },
            now = { clock.last() },
        )

        val result = broken.restoreAll(target, password)

        assertTrue("会话还原失败必须如实报告", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("提示应指出会话历史：$message", message.contains("会话"))
    }

    // ==================== T33/#34 完整备份恢复记忆与建议 ====================

    @Test
    fun 备份恢复覆盖长期记忆来源与参谋记录且保持可追溯() = runBlocking {
        seedSampleData()
        seedMemorySources()
        seedAdvisorRecords()
        sessionStore.save(listOf(seedSession()))
        reopen()
        val target = File(exportDir, "t33-full.zdbak")

        val exported = useCases.exportAll(password, target).getOrThrow()
        val payload = useCases.readPayload(target, password).getOrThrow()

        // 备份范围：11 张用户数据表（含 memories 与 clock_handoffs）+ 会话 + 参谋记录
        assertEquals(3, payload.tableCounts.getValue("memories"))
        assertEquals(1, payload.tableCounts.getValue("clock_handoffs"))
        assertEquals("参谋记录应完整进入备份", advisorSeed(), payload.advisorRecords)
        assertEquals(2, payload.advisorFeedbackCount)
        assertEquals(1, payload.advisorAdoptionCount)
        assertEquals(1, payload.advisorBindingCount)
        assertEquals(2, exported.advisorFeedbackCount)
        assertEquals(1, exported.advisorAdoptionCount)
        assertEquals(1, exported.advisorBindingCount)

        // 篡改当前：参谋记录清空、来源记忆删除、交接记录改写
        advisorStore.replaceAll(AdvisorRecords())
        db.memoryDao().deleteById("memory-src")
        db.memoryDao().deleteById("memory-missing")
        db.clockHandoffDao().upsert(
            ClockHandoffEntity(
                id = "handoff-1", hour = 6, minute = 0, label = "被改写的交接",
                targetEpochDay = null, weekdays = "", requestedAtMillis = 999L,
                status = ClockHandoffStatus.FAILED.name, note = "改写",
            ),
        )
        reopen()

        val summary = useCases.restoreAll(target, password).getOrThrow()

        // 参谋记录完整还原（反馈、采纳执行记录、依据绑定，含依据快照与内容指纹）
        assertEquals("参谋记录应完整还原", advisorSeed(), advisorStore.load())
        assertEquals(2, summary.advisorFeedbackCount)
        assertEquals(1, summary.advisorAdoptionCount)
        assertEquals(1, summary.advisorBindingCount)

        // 记忆与来源关联状态保持：停用、来源缺失、原文与类型
        val sourced = db.memoryDao().get("memory-src")
        assertNotNull("来源关联记忆应恢复", sourced)
        assertEquals("NOTE", sourced!!.sourceRefType)
        assertEquals("note-1", sourced.sourceRefId)
        assertTrue("来源缺失状态应保持", sourced.sourceRefMissing)
        assertFalse("恢复不得把停用记忆重新启用", sourced.enabled)
        val missing = db.memoryDao().get("memory-missing")
        assertNotNull(missing)
        assertEquals("TASK", missing!!.sourceRefType)
        assertEquals("task-gone", missing.sourceRefId)
        assertTrue(missing.sourceRefMissing)
        assertFalse(missing.enabled)
        assertEquals("我对芒果过敏，推荐食谱时请避开", db.memoryDao().get("memory-1")?.content)

        // 系统闹钟交接记录随备份还原（待核对状态保持，不伪造成已保存）
        val handoff = db.clockHandoffDao().observeAll().first().firstOrNull { it.id == "handoff-1" }
        assertNotNull(handoff)
        assertEquals(ClockHandoffStatus.PENDING_CHECK.name, handoff!!.status)
        assertEquals("晨跑", handoff.label)
    }

    @Test
    fun 参谋记录计数与实际内容不一致的备份视为损坏() = runBlocking {
        val payload = testPayload(dbVersion = currentDbVersion()).copy(advisorFeedbackCount = 1)
        val file = rawBackupFile(payload, "t33-bad-advisor.zdbak")

        val inspection = useCases.inspectBackup(file, password).getOrThrow()
        assertFalse("参谋记录计数不一致应判为不完整", inspection.integrityOk)

        val result = useCases.restoreAll(file, password)
        assertTrue("损坏备份必须拒绝恢复", result.isFailure)
        assertTrue(
            "提示应说明损坏：${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message.orEmpty().contains("损坏"),
        )
    }

    @Test
    fun 恢复后旧参谋提案失效且不可直接执行() = runBlocking {
        seedSampleData()
        proposalStore.save(
            listOf(
                AiProposal(
                    id = "proposal-old",
                    sessionId = null, // 参谋提案：由参谋面板展示与确认
                    createdAt = 3L,
                    status = AiProposalStatus.PENDING,
                    items = emptyList(),
                ),
            ),
        )
        advisorStore.bindProposal(
            AdvisorProposalBinding(
                proposalId = "proposal-old",
                suggestionId = "overdue-task:task-1",
                suggestionKind = "OVERDUE_TASK",
                title = "处理过期待办「对芒果过敏的提醒」",
                evidence = listOf(taskEvidence()),
                basisFingerprint = "fp-old",
                createdAt = 3L,
            ),
        )
        reopen()
        val target = File(exportDir, "t33-stale-proposal.zdbak")
        useCases.exportAll(password, target).getOrThrow()
        reopen()

        useCases.restoreAll(target, password).getOrThrow()

        val restored = proposalStore.load().single()
        assertEquals("恢复后旧参谋提案必须先失效", AiProposalStatus.EXPIRED, restored.status)
        assertTrue(
            "失效说明应要求重新生成：${restored.resultMessage}",
            restored.resultMessage.orEmpty().contains("重新生成"),
        )

        val confirm = advisor.confirmAdoptedProposal("proposal-old")
        assertTrue("旧提案不可直接执行", confirm.isFailure)
        assertTrue(
            "拒绝说明应为中文已失效：${confirm.exceptionOrNull()?.message}",
            confirm.exceptionOrNull()?.message.orEmpty().contains("失效"),
        )
        assertEquals("旧提案不得修改业务数据", 2, countTable("tasks"))
        assertTrue(
            "确认被拒应留下可追溯的执行记录",
            advisorStore.load().adoptions.any {
                it.proposalId == "proposal-old" && it.outcome == AdvisorAdoptionOutcome.CANCELLED
            },
        )
    }

    @Test
    fun 恢复成功后重置AI授权且失败恢复不重置() = runBlocking {
        seedSampleData()
        reopen()
        val target = File(exportDir, "t33-auth.zdbak")
        useCases.exportAll(password, target).getOrThrow()

        authorizer.grant(setOf(AiContextCategory.MEMORY, AiContextCategory.TASKS))
        assertNotNull("前置：应已有授权", authorizer.currentGrant())

        val failed = useCases.restoreAll(target, "错误口令-999")
        assertTrue(failed.isFailure)
        assertNotNull("恢复失败不得改变 AI 授权状态", authorizer.currentGrant())

        useCases.restoreAll(target, password).getOrThrow()
        assertNull("恢复数据后 AI 上下文授权必须重新确认", authorizer.currentGrant())
    }

    @Test
    fun 恢复不会把不可核验来源的记忆变成可用事实() = runBlocking {
        // 构造历史/异常备份：来源缺失标记为真但 enabled 仍为 1
        val row = buildJsonObject {
            put("id", JsonPrimitive("memory-unsafe"))
            put("content", JsonPrimitive("来源已不存在的偏好"))
            put("type", JsonPrimitive("PREFERENCE"))
            put("source", JsonPrimitive("MANUAL"))
            put("confirmed", JsonPrimitive(1L))
            put("enabled", JsonPrimitive(1L))
            put("createdAt", JsonPrimitive(1L))
            put("updatedAt", JsonPrimitive(1L))
            put("verifiedAt", JsonPrimitive(1L))
            put("expiresAt", JsonNull)
            put("sourceRefType", JsonPrimitive("TASK"))
            put("sourceRefId", JsonPrimitive("task-gone"))
            put("sourceRefMissing", JsonPrimitive(1L))
        }
        val payload = testPayload(currentDbVersion(), overrides = mapOf("memories" to listOf(row)))
        val file = rawBackupFile(payload, "t33-unsafe-memory.zdbak")

        useCases.restoreAll(file, password).getOrThrow()

        val restored = db.memoryDao().get("memory-unsafe")
        assertNotNull(restored)
        assertTrue("来源缺失状态必须保持", restored!!.sourceRefMissing)
        assertFalse("不可核验来源的记忆不得恢复为启用事实", restored.enabled)

        val active = MemoryUseCases(
            repository = MemoriesRepositoryImpl(db.memoryDao(), Dispatchers.IO),
            idGen = { "memory-gen" },
            now = { clock.last() },
        ).observeActive().first()
        assertTrue("可用记忆集合不得包含来源缺失条目", active.none { it.id == "memory-unsafe" })
    }

    @Test
    fun 恢复会重置设备本地调度台账且台账不进入备份() = runBlocking {
        seedSampleData()
        db.reminderScheduleLogDao().insertPending(
            ReminderScheduleEntity(
                reminderKey = "rem-1|1769000000000",
                scheduledAtMillis = 1769000000000L,
                ownerId = "task-1",
                ownerTitle = "对芒果过敏的提醒",
                state = "PENDING",
                recordedAtMillis = 1L,
            ),
        )
        reopen()
        val target = File(exportDir, "t33-ledger.zdbak")
        useCases.exportAll(password, target).getOrThrow()

        // 调度台账是设备本地的调度/投递状态，不属于用户数据，不进入备份
        val payload = useCases.readPayload(target, password).getOrThrow()
        assertFalse("设备本地调度台账不应进入备份", payload.tables.containsKey("reminder_schedule_log"))

        useCases.restoreAll(target, password).getOrThrow()
        // 旧台账必须清空：避免旧终态抑制重新启用的提醒，或把本机未调度的事实伪装成已投递
        assertEquals(0, countTable("reminder_schedule_log"))
    }

    @Test
    fun 界面文案明确旧备份数据可能重新出现的边界() {
        assertTrue("应说明应用无法修改旧备份", BACKUP_OLD_DATA_BOUNDARY.contains("无法修改"))
        assertTrue("应说明彻底删除的数据可能重新出现", BACKUP_OLD_DATA_BOUNDARY.contains("彻底删除"))
        assertTrue(BACKUP_OLD_DATA_BOUNDARY.contains("重新出现"))
    }

    // ---------- 测试 helper：手工构造 ZDBK 文件与替身 ----------

    /** 手工加密负载用的 Json 实例（与 BackupUseCases 编码约定一致）。 */
    private val testJson = kotlinx.serialization.json.Json { encodeDefaults = true }

    /** 重新启用提醒测试的调度替身：记录调度与取消调用。 */
    private class RecordingScheduler : AlarmScheduler {
        val scheduled = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        override fun canScheduleExact(): Boolean = true
        override suspend fun scheduleExact(reminderId: String, triggerAtMillis: Long, taskId: String, title: String): Boolean {
            scheduled += reminderId
            return true
        }
        override suspend fun scheduleInexact(reminderId: String, triggerAtMillis: Long, taskId: String, title: String): Boolean {
            scheduled += reminderId
            return true
        }
        override suspend fun cancel(reminderId: String) {
            cancelled += reminderId
        }
    }

    @Serializable
    private data class TestPayload(
        val formatVersion: Int,
        val dbVersion: Int,
        val exportedAt: Long,
        val tableCounts: Map<String, Int>,
        val tables: Map<String, JsonArray>,
        val aiSessionCount: Int = 0,
        val aiSessions: List<JsonElement> = emptyList(),
        val advisorFeedbackCount: Int = 0,
        val advisorAdoptionCount: Int = 0,
        val advisorBindingCount: Int = 0,
        val advisorRecords: AdvisorRecords = AdvisorRecords(),
    )

    /** 构造十表齐全的测试负载：[overrides] 按表覆盖行集合，其余为空。 */
    private fun testPayload(dbVersion: Int, overrides: Map<String, List<JsonObject>> = emptyMap()): TestPayload {
        val tables = BackupUseCases.BACKUP_TABLES.associateWith { table ->
            JsonArray(overrides[table].orEmpty())
        }
        return TestPayload(
            formatVersion = BackupUseCases.FORMAT_VERSION,
            dbVersion = dbVersion,
            exportedAt = clock.last(),
            tableCounts = tables.mapValues { it.value.size },
            tables = tables,
        )
    }

    /** 按备份格式（magic+版本+盐+迭代+IV+密文）手工加密为 ZDBK 文件，迭代取测试下限以加速。 */
    private fun rawBackupFile(payload: TestPayload, name: String): File {
        val plain = testJson.encodeToString(TestPayload.serializer(), payload)
            .toByteArray(Charsets.UTF_8)
        val iterations = 10_000
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val key = SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
            "AES",
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val body = cipher.doFinal(plain)
        fun intBytes(v: Int) = byteArrayOf(
            (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
        )
        val out = ByteArrayOutputStream()
        out.write("ZDBK".toByteArray(Charsets.US_ASCII))
        out.write(intBytes(BackupUseCases.FORMAT_VERSION))
        out.write(salt)
        out.write(intBytes(iterations))
        out.write(iv)
        out.write(body)
        val file = File(exportDir, name)
        file.writeBytes(out.toByteArray())
        return file
    }

    /** 直读某表行数（不经 BackupUseCases，测试独立核对）。 */
    private suspend fun countTable(table: String): Int =
        db.useConnection(isReadOnly = true) { connection ->
            connection.usePrepared("SELECT COUNT(*) FROM $table") { statement ->
                if (statement.step()) statement.getLong(0).toInt() else 0
            }
        }

    /** 直读当前数据库 schema 版本（PRAGMA user_version），断言不硬编码版本号。 */
    private suspend fun currentDbVersion(): Int =
        db.useConnection(isReadOnly = true) { connection ->
            connection.usePrepared("PRAGMA user_version") { statement ->
                if (statement.step()) statement.getLong(0).toInt() else 0
            }
        }

    private fun readIntBE(raw: ByteArray, offset: Int): Int =
        ((raw[offset].toInt() and 0xFF) shl 24) or
            ((raw[offset + 1].toInt() and 0xFF) shl 16) or
            ((raw[offset + 2].toInt() and 0xFF) shl 8) or
            (raw[offset + 3].toInt() and 0xFF)
}
