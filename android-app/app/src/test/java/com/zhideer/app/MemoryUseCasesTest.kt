package com.zhideer.app

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.zhideer.app.data.db.ZhiDeerDatabase
import com.zhideer.app.data.repository.MemoriesRepositoryImpl
import com.zhideer.app.data.repository.NotesRepositoryImpl
import com.zhideer.app.data.repository.TasksRepositoryImpl
import com.zhideer.app.domain.model.MemorySource
import com.zhideer.app.domain.model.MemoryType
import com.zhideer.app.domain.model.TaskFilter
import com.zhideer.app.domain.usecase.MemoryInput
import com.zhideer.app.domain.usecase.MemoryUseCases
import com.zhideer.app.domain.usecase.NoteInput
import com.zhideer.app.domain.usecase.NoteUseCases
import com.zhideer.app.domain.usecase.TaskInput
import com.zhideer.app.domain.usecase.TaskUseCases
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
 * 全部走真实 Room 文件库、统一用例入口与注入的固定时钟。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MemoryUseCasesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private lateinit var dbFile: File
    private lateinit var db: ZhiDeerDatabase
    private lateinit var useCases: MemoryUseCases
    private lateinit var taskUseCases: TaskUseCases
    private lateinit var noteUseCases: NoteUseCases
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
        db = Room.databaseBuilder<ZhiDeerDatabase>(
            context = ApplicationProvider.getApplicationContext<Context>(),
            name = dbFile.absolutePath,
        )
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        useCases = MemoryUseCases(
            repository = MemoriesRepositoryImpl(db.memoryDao(), Dispatchers.IO),
            idGen = { "memory-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
        )
        taskUseCases = TaskUseCases(
            repository = TasksRepositoryImpl(db.taskDao(), Dispatchers.IO),
            idGen = { "task-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
            zoneId = zone.id,
        )
        noteUseCases = NoteUseCases(
            repository = NotesRepositoryImpl(db.noteDao(), Dispatchers.IO),
            idGen = { "note-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
        )
    }

    @After
    fun tearDown() {
        db.close()
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
}
