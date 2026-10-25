package com.zhideer.app

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.zhideer.app.data.ai.AiSessionStore
import com.zhideer.app.data.ai.RepositoryAiContextProvider
import com.zhideer.app.data.db.ZhiDeerDatabase
import com.zhideer.app.data.repository.CalendarRemindersRepositoryImpl
import com.zhideer.app.data.repository.CalendarRepositoryImpl
import com.zhideer.app.data.repository.MemoriesRepositoryImpl
import com.zhideer.app.data.repository.NotesRepositoryImpl
import com.zhideer.app.data.repository.RemindersRepositoryImpl
import com.zhideer.app.data.repository.SubTasksRepositoryImpl
import com.zhideer.app.data.repository.TaskOccurrenceRepositoryImpl
import com.zhideer.app.data.repository.TasksRepositoryImpl
import com.zhideer.app.domain.model.AiChatMessage
import com.zhideer.app.domain.model.AiChatRole
import com.zhideer.app.domain.model.AiChatSession
import com.zhideer.app.domain.model.Memory
import com.zhideer.app.domain.model.MemoryRefType
import com.zhideer.app.domain.model.MemorySourceRef
import com.zhideer.app.domain.model.MemorySource
import com.zhideer.app.domain.model.MemoryType
import com.zhideer.app.domain.model.TaskFilter
import com.zhideer.app.domain.repository.MemoriesRepository
import com.zhideer.app.domain.usecase.AiContextCategory
import com.zhideer.app.domain.usecase.AiContextGrant
import com.zhideer.app.domain.usecase.CalendarReminderUseCases
import com.zhideer.app.domain.usecase.CalendarInput
import com.zhideer.app.domain.usecase.CalendarUseCases
import com.zhideer.app.domain.usecase.MemoryInput
import com.zhideer.app.domain.usecase.MemoryUseCases
import com.zhideer.app.domain.usecase.NoteInput
import com.zhideer.app.domain.usecase.NoteUseCases
import com.zhideer.app.domain.usecase.RecycleBinKind
import com.zhideer.app.domain.usecase.RecycleBinUseCases
import com.zhideer.app.domain.usecase.ReminderUseCases
import com.zhideer.app.domain.usecase.TaskInput
import com.zhideer.app.domain.usecase.TaskScheduleLinkUseCases
import com.zhideer.app.domain.usecase.TaskUseCases
import com.zhideer.app.platform.AlarmScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * T26/#27 验收测试：长期记忆的显式保存与重开读取、编辑与停用启用、关键词检索定位、
 * 可用记忆集合（停用/过期不进入）、彻底删除语义（不进回收站、不套三十天保留规则）、
 * 空内容中文拒绝，以及与待办/便签的零耦合。
 * T27/#28 增补：记忆来源引用（sourceRef）与来源删除边界的全部联动——
 * 来源进回收站立即停用关联记忆并标记来源缺失、恢复来源不自动启用、
 * 彻底删除来源（含三十天到期清理）清理关联记忆与检索入口、
 * 删除记忆保留来源原文、已删除内容不进入 AI 上下文快照、会话历史不改写。
 * 全部走真实 Room 文件库、统一用例入口与注入的固定时钟。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MemoryUseCasesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private lateinit var dbFile: File
    private lateinit var db: ZhiDeerDatabase
    private lateinit var useCases: MemoryUseCases
    private lateinit var memoriesRepository: MemoriesRepository
    private lateinit var taskUseCases: TaskUseCases
    private lateinit var noteUseCases: NoteUseCases
    private lateinit var calendarUseCases: CalendarUseCases
    private lateinit var recycleBinUseCases: RecycleBinUseCases
    private lateinit var aiProvider: RepositoryAiContextProvider
    private lateinit var sessionStore: AiSessionStore
    private val clock = mutableListOf(0L)
    private val idSeq = AtomicInteger(0)

    private fun millis(date: String, time: String = "10:00"): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = context.getDatabasePath("t26_test.db")
        dbFile.parentFile?.mkdirs()
        context.deleteDatabase(dbFile.name)
        open()
    }

    private fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.databaseBuilder<ZhiDeerDatabase>(
            context = ApplicationProvider.getApplicationContext<Context>(),
            name = dbFile.absolutePath,
        )
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val taskRepo = TasksRepositoryImpl(db.taskDao(), Dispatchers.IO)
        val calendarRepo = CalendarRepositoryImpl(db.calendarEntryDao(), Dispatchers.IO)
        val noteRepo = NotesRepositoryImpl(db.noteDao(), Dispatchers.IO)
        memoriesRepository = MemoriesRepositoryImpl(db.memoryDao(), Dispatchers.IO)
        useCases = MemoryUseCases(
            repository = memoriesRepository,
            idGen = { "memory-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
        )
        taskUseCases = TaskUseCases(
            repository = taskRepo,
            idGen = { "task-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
            zoneId = zone.id,
            // 与 AppContainer 相同的组装：待办进入回收站时联动停用引用它的记忆（#28）
            onTrashed = { taskId -> useCases.markSourceMissing(MemoryRefType.TASK, taskId) },
        )
        noteUseCases = NoteUseCases(
            noteRepo,
            { "note-${idSeq.incrementAndGet()}" },
            { clock.last() },
            // 与 AppContainer 相同的组装：便签进入回收站时联动停用引用它的记忆（#28）
            onTrashed = { noteId -> useCases.markSourceMissing(MemoryRefType.NOTE, noteId) },
        )
        calendarUseCases = CalendarUseCases(
            repository = calendarRepo,
            idGen = { "entry-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
            zoneId = zone.id,
            // 与 AppContainer 相同的组装：日程进入回收站时联动停用引用它的记忆（#28）
            onTrashed = { entryId -> useCases.markSourceMissing(MemoryRefType.SCHEDULE, entryId) },
        )
        aiProvider = RepositoryAiContextProvider(taskRepo, calendarRepo, noteRepo, zone, { clock.last() })
        val scheduler = MemoryFakeAlarmScheduler()
        val reminderUseCases = ReminderUseCases(
            repository = RemindersRepositoryImpl(db.reminderDao(), Dispatchers.IO),
            tasksRepository = taskRepo,
            scheduler = scheduler,
            idGen = { "rem-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
        )
        val calendarReminderUseCases = CalendarReminderUseCases(
            repository = CalendarRemindersRepositoryImpl(db.calendarReminderDao(), Dispatchers.IO),
            calendarRepository = calendarRepo,
            scheduler = scheduler,
            idGen = { "crem-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
        )
        recycleBinUseCases = RecycleBinUseCases(
            tasksRepository = taskRepo,
            subTasksRepository = SubTasksRepositoryImpl(db.subTaskDao(), Dispatchers.IO),
            calendarRepository = calendarRepo,
            notesRepository = noteRepo,
            taskOccurrenceRepository = TaskOccurrenceRepositoryImpl(db.taskOccurrenceDao(), Dispatchers.IO),
            reminderUseCases = reminderUseCases,
            calendarReminderUseCases = calendarReminderUseCases,
            taskScheduleLinkUseCases = TaskScheduleLinkUseCases(taskRepo, calendarRepo, { clock.last() }),
            now = { clock.last() },
            // 与 AppContainer 相同的组装：来源恢复清除缺失标记（不自动启用）、
            // 来源彻底删除（purge/三十天到期清理）级联清理关联记忆（#28）
            onSourceRestored = { kind, sourceId ->
                when (kind) {
                    RecycleBinKind.TASK -> useCases.clearSourceMissing(MemoryRefType.TASK, sourceId)
                    RecycleBinKind.SCHEDULE -> useCases.clearSourceMissing(MemoryRefType.SCHEDULE, sourceId)
                    RecycleBinKind.NOTE -> useCases.clearSourceMissing(MemoryRefType.NOTE, sourceId)
                    RecycleBinKind.SUB_TASK -> Unit
                }
            },
            onSourcePurged = { kind, sourceId ->
                when (kind) {
                    RecycleBinKind.TASK -> useCases.purgeBySource(MemoryRefType.TASK, sourceId)
                    RecycleBinKind.SCHEDULE -> useCases.purgeBySource(MemoryRefType.SCHEDULE, sourceId)
                    RecycleBinKind.NOTE -> useCases.purgeBySource(MemoryRefType.NOTE, sourceId)
                    RecycleBinKind.SUB_TASK -> Unit
                }
            },
        )
        sessionStore = AiSessionStore(
            File(context.cacheDir, "t28_sessions_${idSeq.incrementAndGet()}.preferences_pb"),
        )
    }

    @After
    fun tearDown() {
        db.close()
        sessionStore.close()
    }

    /** 经仓储直写一条带来源引用的记忆（来源引用写入入口由后续聊天记忆任务接入，机制先行）。 */
    private suspend fun createMemoryWithSourceRef(
        type: MemoryRefType,
        sourceId: String,
        content: String = "来自来源的记忆",
    ): Memory {
        val t = clock.last()
        val memory = Memory(
            id = "memory-src-${idSeq.incrementAndGet()}",
            content = content,
            type = MemoryType.PREFERENCE,
            source = MemorySource.MANUAL,
            confirmed = true,
            enabled = true,
            createdAt = t,
            updatedAt = t,
            verifiedAt = t,
            sourceRef = MemorySourceRef(type = type, id = sourceId),
        )
        memoriesRepository.upsertMemory(memory)
        return memory
    }

    @Test
    fun 显式保存记忆原文并在重开后读取() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val created = useCases.create(
            MemoryInput(
                content = "我对芒果过敏，推荐食谱时请避开",
                type = MemoryType.FACT,
            ),
        ).getOrThrow()
        assertEquals("我对芒果过敏，推荐食谱时请避开", created.content)
        assertEquals(MemoryType.FACT, created.type)
        // 手动录入即显式保存并确认：来源可追溯（手动录入）、确认状态为已确认、处于启用状态
        assertTrue(created.confirmed)
        assertTrue(created.enabled)
        assertEquals(millis("2026-10-25", "09:00"), created.createdAt)
        assertEquals(millis("2026-10-25", "09:00"), created.verifiedAt)
        // 未填有效期即长期有效
        assertNull(created.expiresAt)

        // 离线重开：关闭后重新打开同一数据库文件
        db.close()
        open()

        val readBack = useCases.get(created.id)
        assertNotNull(readBack)
        assertEquals("我对芒果过敏，推荐食谱时请避开", readBack!!.content)
        assertEquals(MemoryType.FACT, readBack.type)
        assertTrue(readBack.confirmed)
        assertTrue(readBack.enabled)
        assertEquals(millis("2026-10-25", "09:00"), readBack.createdAt)
        assertEquals(millis("2026-10-25", "09:00"), readBack.verifiedAt)
        assertNull(readBack.expiresAt)
    }

    @Test
    fun 编辑记忆更新内容类型有效期并记录更新与验证时间() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val a = useCases.create(MemoryInput(content = "偏好早晨跑步", type = MemoryType.PREFERENCE))
            .getOrThrow()
        val b = useCases.create(MemoryInput(content = "另一条互不影响", type = MemoryType.FACT))
            .getOrThrow()

        clock.add(millis("2026-10-25", "11:30"))
        val updated = useCases.update(
            a.id,
            MemoryInput(
                content = "偏好清晨六点沿河边跑步",
                type = MemoryType.PREFERENCE,
                expiresAt = millis("2026-12-31"),
            ),
        ).getOrThrow()
        assertEquals("偏好清晨六点沿河边跑步", updated.content)
        assertEquals(MemoryType.PREFERENCE, updated.type)
        assertEquals(millis("2026-12-31"), updated.expiresAt)
        assertEquals(millis("2026-10-25", "11:30"), updated.updatedAt)
        // 编辑保存是用户对内容的重新确认：验证时间随之更新，创建时间不变
        assertEquals(millis("2026-10-25", "11:30"), updated.verifiedAt)
        assertEquals(a.createdAt, updated.createdAt)

        // 记忆相互独立：编辑一条不影响另一条
        val bReadBack = useCases.get(b.id)!!
        assertEquals("另一条互不影响", bReadBack.content)
        assertEquals(MemoryType.FACT, bReadBack.type)

        db.close()
        open()
        val readBack = useCases.get(a.id)!!
        assertEquals("偏好清晨六点沿河边跑步", readBack.content)
        assertEquals(millis("2026-12-31"), readBack.expiresAt)
    }

    @Test
    fun 关键词检索命中内容且大小写不敏感() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val hit = useCases.create(
            MemoryInput(content = "Kotlin 是我首选的编程语言", type = MemoryType.PREFERENCE),
        ).getOrThrow()
        useCases.create(MemoryInput(content = "住在杭州", type = MemoryType.FACT))

        assertEquals(listOf(hit.id), useCases.search("kotlin").first().map { it.id })
        assertEquals(listOf(hit.id), useCases.search("编程语言").first().map { it.id })
        assertEquals(listOf(hit.id), useCases.search("  Kotlin  ").first().map { it.id })
        // 空白关键词不返回结果
        assertTrue(useCases.search("   ").first().isEmpty())
        // 无命中时为空
        assertTrue(useCases.search("不存在的词").first().isEmpty())
    }

    @Test
    fun 停用的记忆不进入可用集合_启用后恢复_管理搜索仍可定位() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val memory = useCases.create(
            MemoryInput(content = "偏好简约风格的界面", type = MemoryType.PREFERENCE),
        ).getOrThrow()
        assertEquals(listOf(memory.id), useCases.observeActive().first().map { it.id })

        clock.add(millis("2026-10-25", "10:00"))
        val disabled = useCases.setEnabled(memory.id, enabled = false).getOrThrow()
        assertFalse(disabled.enabled)
        assertEquals(millis("2026-10-25", "10:00"), disabled.updatedAt)

        // 停用内容不进入可用记忆集合，但管理视角（全部列表与搜索）仍可定位
        assertTrue(useCases.observeActive().first().isEmpty())
        assertEquals(listOf(memory.id), useCases.observe().first().map { it.id })
        assertEquals(listOf(memory.id), useCases.search("简约").first().map { it.id })

        clock.add(millis("2026-10-25", "11:00"))
        val reEnabled = useCases.setEnabled(memory.id, enabled = true).getOrThrow()
        assertTrue(reEnabled.enabled)
        assertEquals(listOf(memory.id), useCases.observeActive().first().map { it.id })

        db.close()
        open()
        assertTrue(useCases.get(memory.id)!!.enabled)
    }

    @Test
    fun 过期的记忆不进入可用集合_未过期与长期有效正常进入() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val expired = useCases.create(
            MemoryInput(content = "临时偏好：本周尝试低碳饮食", type = MemoryType.PREFERENCE,
                expiresAt = millis("2026-10-20")),
        ).getOrThrow()
        val active = useCases.create(
            MemoryInput(content = "事实：家里有两只猫", type = MemoryType.FACT,
                expiresAt = millis("2026-11-01")),
        ).getOrThrow()
        val permanent = useCases.create(
            MemoryInput(content = "偏好不吃香菜", type = MemoryType.PREFERENCE),
        ).getOrThrow()

        // 有效期是创建时的持久字段：已过有效期的记忆不进入可用记忆集合
        assertEquals(setOf(active.id, permanent.id), useCases.observeActive().first().map { it.id }.toSet())
        // 全部列表与搜索仍可见（管理视角），条目未被隐藏或删除
        assertEquals(
            setOf(expired.id, active.id, permanent.id),
            useCases.observe().first().map { it.id }.toSet(),
        )
        assertEquals(listOf(expired.id), useCases.search("低碳").first().map { it.id })
    }

    @Test
    fun 删除是彻底删除_不进回收站_重开后仍不存在() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val deleted = useCases.create(
            MemoryInput(content = "将被彻底删除的记忆", type = MemoryType.FACT),
        ).getOrThrow()
        val kept = useCases.create(
            MemoryInput(content = "保留的记忆提到删除关键词", type = MemoryType.FACT),
        ).getOrThrow()

        clock.add(millis("2026-10-25", "10:00"))
        useCases.delete(deleted.id).getOrThrow()

        // 删除后任何入口都不可再读取：get、列表、搜索均不出现
        assertNull("彻底删除后不得保留记录", useCases.get(deleted.id))
        assertEquals(listOf(kept.id), useCases.observe().first().map { it.id })
        assertEquals(listOf(kept.id), useCases.search("删除").first().map { it.id })
        assertTrue(useCases.search("彻底删除的记忆").first().isEmpty())

        // 物理删除持久生效：重开数据库后依旧不存在，不进入回收站、无三十天保留
        db.close()
        open()
        assertNull(useCases.get(deleted.id))
        assertEquals(listOf(kept.id), useCases.observe().first().map { it.id })
    }

    @Test
    fun 空内容记忆被拒绝且提示为中文() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))

        val blank = useCases.create(MemoryInput(content = "   "))
        assertTrue(blank.isFailure)
        assertEquals("记忆内容不能为空", blank.exceptionOrNull()!!.message)

        val memory = useCases.create(MemoryInput(content = "正常记忆")).getOrThrow()
        val badUpdate = useCases.update(memory.id, MemoryInput(content = ""))
        assertTrue(badUpdate.isFailure)
        assertEquals("记忆内容不能为空", badUpdate.exceptionOrNull()!!.message)

        // 拒绝后不产生任何记录
        assertTrue(useCases.observe().first().size == 1)
    }

    @Test
    fun 聊天确认来源的记忆经数据库往返保持来源与确认状态() = runBlocking {
        // T28/#29 纯新增：聊天确认保存（操作提案确认后经真实入口，来源 CHAT、写入即已确认）
        clock.add(millis("2026-10-25", "09:00"))
        val created = useCases.create(
            MemoryInput(content = "偏好靠窗座位", type = MemoryType.PREFERENCE),
            MemorySource.CHAT,
        ).getOrThrow()
        assertEquals(MemorySource.CHAT, created.source)
        assertTrue(created.confirmed)
        assertTrue(created.enabled)
        // 手动录入路径零回归：来源仍为手动录入
        val manual = useCases.create(MemoryInput(content = "手动录入的记忆", type = MemoryType.FACT)).getOrThrow()
        assertEquals(MemorySource.MANUAL, manual.source)

        // 离线重开：来源字段随行持久化，往返一致（可追溯）
        db.close()
        open()

        val readBack = useCases.get(created.id)!!
        assertEquals(MemorySource.CHAT, readBack.source)
        assertTrue(readBack.confirmed)
        assertEquals("偏好靠窗座位", readBack.content)
        assertEquals(MemorySource.MANUAL, useCases.get(manual.id)!!.source)
    }

    @Test
    fun 操作不存在的记忆返回中文错误() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))

        val missingUpdate = useCases.update("no-such-memory", MemoryInput(content = "任意"))
        assertTrue(missingUpdate.isFailure)
        assertEquals("记忆不存在或已彻底删除", missingUpdate.exceptionOrNull()!!.message)

        val missingToggle = useCases.setEnabled("no-such-memory", enabled = false)
        assertTrue(missingToggle.isFailure)
        assertEquals("记忆不存在或已彻底删除", missingToggle.exceptionOrNull()!!.message)

        val missingDelete = useCases.delete("no-such-memory")
        assertTrue(missingDelete.isFailure)
        assertEquals("记忆不存在或已彻底删除", missingDelete.exceptionOrNull()!!.message)
    }

    @Test
    fun 记忆与待办便签零耦合_保存记忆不产生任何待办或便签() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        useCases.create(
            MemoryInput(content = "偏好通过邮件接收周报", type = MemoryType.PREFERENCE),
        ).getOrThrow()

        // 长期记忆是独立隐私数据：保存记忆不自动创建、关联或影响任何待办与便签
        assertTrue(taskUseCases.observe(TaskFilter.ALL).first().isEmpty())
        assertTrue(noteUseCases.observe().first().isEmpty())

        // 反向隔离：普通事项进入回收站不套用记忆数据（记忆列表不随之变化）
        taskUseCases.create(TaskInput(title = "普通待办", dueZoneId = zone.id)).getOrThrow()
        noteUseCases.create(NoteInput(title = "普通便签")).getOrThrow()
        assertEquals(1, useCases.observe().first().size)
    }

    // ---------- T27/#28：记忆来源引用与来源删除边界 ----------

    @Test
    fun 记忆可携带来源引用并在重开后读取() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val note = noteUseCases.create(NoteInput(title = "饮食记录", content = "不吃香菜")).getOrThrow()
        val memory = createMemoryWithSourceRef(
            type = MemoryRefType.NOTE,
            sourceId = note.id,
            content = "偏好不吃香菜（来自饮食记录便签）",
        )

        val readBack = useCases.get(memory.id)
        assertNotNull(readBack)
        assertNotNull(readBack!!.sourceRef)
        assertEquals(MemoryRefType.NOTE, readBack.sourceRef!!.type)
        assertEquals(note.id, readBack.sourceRef!!.id)
        assertFalse("来源尚在时不得标记缺失", readBack.sourceRef!!.missing)

        // 离线重开：来源引用随记忆持久化
        db.close()
        open()
        val reopened = useCases.get(memory.id)!!
        assertEquals(MemoryRefType.NOTE, reopened.sourceRef!!.type)
        assertEquals(note.id, reopened.sourceRef!!.id)
        assertFalse(reopened.sourceRef!!.missing)
    }

    @Test
    fun 来源进入回收站立即停用关联记忆并标记来源缺失() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val note = noteUseCases.create(NoteInput(title = "饮食记录", content = "不吃香菜")).getOrThrow()
        val linked = createMemoryWithSourceRef(MemoryRefType.NOTE, note.id, "偏好不吃香菜")
        val standalone = useCases.create(MemoryInput(content = "手动录入的独立记忆")).getOrThrow()
        assertEquals(setOf(linked.id, standalone.id), useCases.observeActive().first().map { it.id }.toSet())

        clock.add(millis("2026-10-25", "10:00"))
        noteUseCases.trash(note.id).getOrThrow()

        // 立即停用：来源进回收站，关联记忆马上退出可用集合并标记来源缺失（issue #28）
        val after = useCases.get(linked.id)!!
        assertFalse("关联记忆必须立即停用", after.enabled)
        assertTrue("关联记忆必须标记来源缺失", after.sourceRef!!.missing)
        assertEquals(millis("2026-10-25", "10:00"), after.updatedAt)
        assertEquals(listOf(standalone.id), useCases.observeActive().first().map { it.id })

        // 停用不等于删除：管理视角仍可定位（记忆是用户显式保存的独立资产）
        assertEquals(listOf(linked.id), useCases.search("香菜").first().map { it.id })
        assertTrue(useCases.observe().first().any { it.id == linked.id })

        // 来源原文只在回收站：正常查询与 AI 检索不再含来源原文
        assertNull(noteUseCases.get(note.id)?.let { if (it.trashedAt == null) it else null })
    }

    @Test
    fun 恢复来源清除缺失标记但不自动启用记忆() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val note = noteUseCases.create(NoteInput(title = "饮食记录", content = "不吃香菜")).getOrThrow()
        val linked = createMemoryWithSourceRef(MemoryRefType.NOTE, note.id, "偏好不吃香菜")
        noteUseCases.trash(note.id).getOrThrow()
        assertFalse(useCases.get(linked.id)!!.enabled)

        clock.add(millis("2026-10-25", "11:00"))
        recycleBinUseCases.restoreNote(note.id).getOrThrow()

        // 恢复来源不自动启用记忆（issue #28）：来源缺失标记清除，停用状态保留
        val restored = useCases.get(linked.id)!!
        assertFalse("恢复来源不得自动启用记忆", restored.enabled)
        assertFalse("来源已恢复，缺失标记应清除", restored.sourceRef!!.missing)
        assertTrue(useCases.observeActive().first().isEmpty())

        // 用户显式启用后才回到可用集合
        clock.add(millis("2026-10-25", "12:00"))
        useCases.setEnabled(linked.id, enabled = true).getOrThrow()
        assertEquals(listOf(linked.id), useCases.observeActive().first().map { it.id })
    }

    @Test
    fun 彻底删除来源清理关联记忆与检索入口() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val note = noteUseCases.create(NoteInput(title = "饮食记录", content = "不吃香菜")).getOrThrow()
        val linked = createMemoryWithSourceRef(MemoryRefType.NOTE, note.id, "偏好不吃香菜")
        val standalone = useCases.create(MemoryInput(content = "手动录入的独立记忆")).getOrThrow()

        clock.add(millis("2026-10-25", "10:00"))
        recycleBinUseCases.purgeNote(note.id).getOrThrow()

        // 来源彻底删除：清理关联记忆（issue #28：清理关联记忆和检索索引）
        assertNull("关联记忆必须被清理", useCases.get(linked.id))
        assertTrue(useCases.observe().first().none { it.id == linked.id })
        assertTrue("清理后不得再被检索", useCases.search("香菜").first().isEmpty())
        assertTrue(useCases.observeActive().first().none { it.id == linked.id })

        // 无来源的独立记忆不受影响
        assertNotNull(useCases.get(standalone.id))
        assertEquals(listOf(standalone.id), useCases.observeActive().first().map { it.id })
    }

    @Test
    fun 彻底删除待办与日程来源同样清理关联记忆_进回收站先停用() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = taskUseCases.create(TaskInput(title = "晨跑计划", dueZoneId = zone.id)).getOrThrow()
        val taskMemory = createMemoryWithSourceRef(MemoryRefType.TASK, task.id, "偏好清晨六点跑步")
        val entry = calendarUseCases.create(
            CalendarInput(
                title = "周五例会",
                startMillis = millis("2026-10-30", "14:00"),
                endMillis = millis("2026-10-30", "15:00"),
                timezoneId = zone.id,
            ),
        ).getOrThrow()
        val entryMemory = createMemoryWithSourceRef(MemoryRefType.SCHEDULE, entry.id, "周五下午不安排深度工作")

        // 来源进回收站：立即停用 + 标记缺失
        clock.add(millis("2026-10-25", "10:00"))
        taskUseCases.trash(task.id).getOrThrow()
        calendarUseCases.trash(entry.id).getOrThrow()
        val afterTask = useCases.get(taskMemory.id)!!
        val afterEntry = useCases.get(entryMemory.id)!!
        assertFalse(afterTask.enabled)
        assertTrue(afterTask.sourceRef!!.missing)
        assertFalse(afterEntry.enabled)
        assertTrue(afterEntry.sourceRef!!.missing)
        assertTrue(useCases.observeActive().first().isEmpty())

        // 来源彻底删除：清理关联记忆
        clock.add(millis("2026-10-25", "11:00"))
        recycleBinUseCases.purgeTask(task.id).getOrThrow()
        recycleBinUseCases.purgeEntry(entry.id).getOrThrow()
        assertNull(useCases.get(taskMemory.id))
        assertNull(useCases.get(entryMemory.id))
        assertTrue(useCases.observe().first().isEmpty())
        assertTrue(useCases.search("跑步").first().isEmpty())
    }

    @Test
    fun 三十天到期清理同样清理关联记忆_记忆不套三十天规则() = runBlocking {
        clock.add(millis("2026-10-01", "09:00"))
        val note = noteUseCases.create(NoteInput(title = "旧便签", content = "旧偏好来源")).getOrThrow()
        val linked = createMemoryWithSourceRef(MemoryRefType.NOTE, note.id, "旧偏好记忆")
        val standalone = useCases.create(MemoryInput(content = "与来源无关的记忆")).getOrThrow()
        noteUseCases.trash(note.id).getOrThrow()
        assertFalse(useCases.get(linked.id)!!.enabled)

        // 三十一天后到期清理：来源原文被彻底删除，关联记忆随之清理
        clock.add(millis("2026-11-02", "09:00"))
        val result = recycleBinUseCases.purgeExpired(clock.last() - RecycleBinUseCases.RETENTION_MILLIS)
        assertEquals(1, result.purgedNotes)
        assertNull("来源到期清理后关联记忆必须被清理", useCases.get(linked.id))
        assertTrue(useCases.search("旧偏好").first().isEmpty())

        // 记忆自身不进回收站、不套三十天规则：无来源且未删除的记忆不受清理影响
        assertNotNull(useCases.get(standalone.id))
        assertEquals(listOf(standalone.id), useCases.observe().first().map { it.id })
    }

    @Test
    fun 删除记忆本身保留来源原文() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val note = noteUseCases.create(NoteInput(title = "饮食记录", content = "不吃香菜，喜欢清淡")).getOrThrow()
        val linked = createMemoryWithSourceRef(MemoryRefType.NOTE, note.id, "偏好不吃香菜")

        clock.add(millis("2026-10-25", "10:00"))
        useCases.delete(linked.id).getOrThrow()

        // 删除记忆不动来源：来源原文完整保留、仍可正常查看（issue #28）
        assertNull(useCases.get(linked.id))
        val source = noteUseCases.get(note.id)
        assertNotNull("来源原文必须保留", source)
        assertEquals("饮食记录", source!!.title)
        assertEquals("不吃香菜，喜欢清淡", source.content)
        assertEquals(listOf(note.id), noteUseCases.observe().first().map { it.id })
    }

    @Test
    fun 来源缺失或删除后原文与记忆不再进入AI上下文快照() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val note = noteUseCases.create(NoteInput(title = "饮食记录", content = "不吃香菜")).getOrThrow()
        createMemoryWithSourceRef(MemoryRefType.NOTE, note.id, "偏好不吃香菜")
        val grant = AiContextGrant(
            setOf(AiContextCategory.TASKS, AiContextCategory.SCHEDULE, AiContextCategory.NOTES),
        )

        // 来源仍在：便签原文按授权进入快照（既有授权体系）
        val before = aiProvider.loadSnapshot(grant)
        assertEquals(1, before.notes.size)
        assertEquals("饮食记录", before.notes.first().title)

        // 记忆不参与上下文快照：快照只有待办/日程/便签类别，无记忆注入路径（结构保证）
        clock.add(millis("2026-10-25", "10:00"))
        noteUseCases.trash(note.id).getOrThrow()

        // 来源进回收站后：原文不再进入快照（避免从原文再次注入相同偏好，issue #28）
        val afterTrash = aiProvider.loadSnapshot(grant)
        assertTrue(afterTrash.notes.isEmpty())

        // 彻底删除后：来源原文彻底消失，关联记忆已被清理，无任何再注入路径
        recycleBinUseCases.purgeNote(note.id).getOrThrow()
        val afterPurge = aiProvider.loadSnapshot(grant)
        assertTrue(afterPurge.notes.isEmpty())
        assertTrue(useCases.search("香菜").first().isEmpty())
    }

    @Test
    fun 删除记忆不改写会话历史中已引用的内容() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val memory = useCases.create(MemoryInput(content = "我对芒果过敏")).getOrThrow()
        val session = AiChatSession(
            id = "session-1",
            title = "饮食偏好",
            messages = listOf(
                AiChatMessage(AiChatRole.USER, "请记住：我对芒果过敏"),
                AiChatMessage(AiChatRole.ASSISTANT, "已了解：你对芒果过敏（已保存为记忆 ${memory.id}）"),
            ),
            createdAt = clock.last(),
            updatedAt = clock.last(),
        )
        sessionStore.save(listOf(session))

        clock.add(millis("2026-10-25", "10:00"))
        useCases.delete(memory.id).getOrThrow()

        // 会话历史是过去的对话记录：删除记忆不改写历史，已引用内容原样保留
        val after = sessionStore.load()
        assertEquals(1, after.size)
        assertEquals(session.messages, after.first().messages)
        assertTrue(after.first().messages.any { it.content.contains("芒果") })

        // 但删除立即生效于记忆自身：get 与检索均不再出现
        assertNull(useCases.get(memory.id))
        assertTrue(useCases.search("芒果").first().isEmpty())
    }

    @Test
    fun 来源联动钩子幂等_重复标记与重复清理无副作用() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val note = noteUseCases.create(NoteInput(title = "饮食记录", content = "不吃香菜")).getOrThrow()
        val linked = createMemoryWithSourceRef(MemoryRefType.NOTE, note.id, "偏好不吃香菜")

        // 重复标记来源缺失：幂等，不重复更新
        clock.add(millis("2026-10-25", "10:00"))
        assertEquals(1, useCases.markSourceMissing(MemoryRefType.NOTE, note.id))
        val first = useCases.get(linked.id)!!
        clock.add(millis("2026-10-25", "10:05"))
        assertEquals(0, useCases.markSourceMissing(MemoryRefType.NOTE, note.id))
        assertEquals(first.updatedAt, useCases.get(linked.id)!!.updatedAt)

        // 恢复清除标记：幂等
        assertEquals(1, useCases.clearSourceMissing(MemoryRefType.NOTE, note.id))
        assertEquals(0, useCases.clearSourceMissing(MemoryRefType.NOTE, note.id))
        assertFalse(useCases.get(linked.id)!!.sourceRef!!.missing)

        // 清理：命中删除一次，再次清理返回 0（幂等）
        assertEquals(1, useCases.purgeBySource(MemoryRefType.NOTE, note.id))
        assertEquals(0, useCases.purgeBySource(MemoryRefType.NOTE, note.id))
        assertNull(useCases.get(linked.id))

        // 无匹配来源时各操作均为 0，不抛错
        assertEquals(0, useCases.markSourceMissing(MemoryRefType.TASK, "no-such"))
        assertEquals(0, useCases.clearSourceMissing(MemoryRefType.TASK, "no-such"))
        assertEquals(0, useCases.purgeBySource(MemoryRefType.TASK, "no-such"))
    }
}

/** 单元测试用假调度器：只记录调用，不触达系统闹钟。 */
private class MemoryFakeAlarmScheduler : AlarmScheduler {
    override fun canScheduleExact(): Boolean = true

    override fun scheduleExact(reminderId: String, triggerAtMillis: Long, taskId: String, title: String): Boolean = true

    override fun scheduleInexact(reminderId: String, triggerAtMillis: Long, taskId: String, title: String): Boolean = true

    override fun cancel(reminderId: String) = Unit
}
