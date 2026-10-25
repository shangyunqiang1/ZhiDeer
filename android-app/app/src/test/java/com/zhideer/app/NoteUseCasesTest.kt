package com.zhideer.app

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.zhideer.app.data.db.ZhiDeerDatabase
import com.zhideer.app.data.repository.NotesRepositoryImpl
import com.zhideer.app.data.repository.TasksRepositoryImpl
import com.zhideer.app.domain.model.TaskFilter
import com.zhideer.app.domain.usecase.NoteInput
import com.zhideer.app.domain.usecase.NoteListItemInput
import com.zhideer.app.domain.usecase.NoteUseCases
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
 * T05/#6 验收测试：便签文本与清单的创建编辑重开读取、清单独立勾选（与待办零耦合）、
 * 标签与置顶、关键词搜索（含清单文本命中与回收站排除）、回收站语义。
 * 全部走真实 Room 文件库、统一用例入口与注入的固定时钟。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NoteUseCasesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private lateinit var dbFile: File
    private lateinit var db: ZhiDeerDatabase
    private lateinit var useCases: NoteUseCases
    private lateinit var taskUseCases: TaskUseCases
    private val clock = mutableListOf(0L)
    private val idSeq = AtomicInteger(0)

    private fun millis(date: String, time: String = "10:00"): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = context.getDatabasePath("t05_test.db")
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
        useCases = NoteUseCases(
            repository = NotesRepositoryImpl(db.noteDao(), Dispatchers.IO),
            idGen = { "note-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
        )
        taskUseCases = TaskUseCases(
            repository = TasksRepositoryImpl(db.taskDao(), Dispatchers.IO),
            idGen = { "task-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
            zoneId = zone.id,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun 文本便签保存原文并在重开后读取() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val created = useCases.create(
            NoteInput(
                title = "会议记录",
                content = "十月酝酿会：讨论便签设计。\n原始记录不做扩写。",
                tags = listOf("工作", "记录"),
            ),
        ).getOrThrow()
        assertEquals("会议记录", created.title)
        assertEquals("十月酝酿会：讨论便签设计。\n原始记录不做扩写。", created.content)

        // 离线重开：关闭后重新打开同一数据库文件
        db.close()
        open()

        val readBack = useCases.get(created.id)
        assertNotNull(readBack)
        assertEquals("会议记录", readBack!!.title)
        assertEquals("十月酝酿会：讨论便签设计。\n原始记录不做扩写。", readBack.content)
        assertEquals(listOf("工作", "记录"), readBack.tags)
        assertFalse(readBack.pinned)
        assertNull(readBack.trashedAt)
    }

    @Test
    fun 清单便签勾选状态独立保存并在重开后保持() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val created = useCases.create(
            NoteInput(
                title = "采购清单",
                items = listOf(
                    NoteListItemInput(text = "牛奶"),
                    NoteListItemInput(text = "  "),
                    NoteListItemInput(text = "鸡蛋"),
                    NoteListItemInput(text = "面包"),
                ),
            ),
        ).getOrThrow()
        // 空白清单项被丢弃，其余保留用户原文
        assertEquals(listOf("牛奶", "鸡蛋", "面包"), created.items.map { it.text })
        assertTrue(created.items.none { it.checked })

        clock.add(millis("2026-10-25", "10:00"))
        val toggled = useCases.toggleItem(created.id, created.items[1].id, checked = true).getOrThrow()
        assertTrue(toggled.items.first { it.text == "鸡蛋" }.checked)
        assertFalse(toggled.items.first { it.text == "牛奶" }.checked)
        assertEquals(millis("2026-10-25", "10:00"), toggled.updatedAt)

        db.close()
        open()

        val readBack = useCases.get(created.id)!!
        assertEquals(listOf("牛奶", "鸡蛋", "面包"), readBack.items.map { it.text })
        assertEquals(listOf(false, true, false), readBack.items.map { it.checked })
    }

    @Test
    fun 清单项勾选不创建不关联不完成待办() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val note = useCases.create(
            NoteInput(
                title = "今日安排便签",
                items = listOf(NoteListItemInput(text = "写周报")),
            ),
        ).getOrThrow()

        clock.add(millis("2026-10-25", "10:00"))
        useCases.toggleItem(note.id, note.items[0].id, checked = true).getOrThrow()
        clock.add(millis("2026-10-25", "11:00"))
        useCases.toggleItem(note.id, note.items[0].id, checked = false).getOrThrow()

        // 勾选前后待办系统始终为空：便签清单不自动创建、关联或完成任何待办
        assertTrue(taskUseCases.observe(TaskFilter.ALL).first().isEmpty())
        // 清单项勾选状态只落在便签自身，且可以自由来回勾选
        assertFalse(useCases.get(note.id)!!.items[0].checked)
    }

    @Test
    fun 编辑便签更新标题正文标签清单并记录更新时间() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val created = useCases.create(
            NoteInput(title = "初稿", content = "第一版内容", tags = listOf("草稿")),
        ).getOrThrow()

        clock.add(millis("2026-10-25", "11:30"))
        val updated = useCases.update(
            created.id,
            NoteInput(
                title = "定稿",
                content = "第二版内容",
                tags = listOf("工作", " 定稿 "),
                items = listOf(NoteListItemInput(text = "跟进一件事")),
            ),
        ).getOrThrow()
        assertEquals("定稿", updated.title)
        assertEquals("第二版内容", updated.content)
        // 标签规范化：去除首尾空白，保留用户书写顺序
        assertEquals(listOf("工作", "定稿"), updated.tags)
        assertEquals(listOf("跟进一件事"), updated.items.map { it.text })
        assertEquals(millis("2026-10-25", "11:30"), updated.updatedAt)
        assertEquals(created.createdAt, updated.createdAt)

        db.close()
        open()
        val readBack = useCases.get(created.id)!!
        assertEquals("定稿", readBack.title)
        assertEquals("第二版内容", readBack.content)
        assertEquals(listOf("工作", "定稿"), readBack.tags)
        assertEquals(listOf("跟进一件事"), readBack.items.map { it.text })
    }

    @Test
    fun 列表置顶在前其余按更新时间倒序() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val a = useCases.create(NoteInput(title = "甲")).getOrThrow()
        clock.add(millis("2026-10-25", "10:00"))
        val b = useCases.create(NoteInput(title = "乙")).getOrThrow()
        clock.add(millis("2026-10-25", "11:00"))
        val c = useCases.create(NoteInput(title = "丙")).getOrThrow()

        assertEquals(listOf("丙", "乙", "甲"), titles())

        clock.add(millis("2026-10-25", "12:00"))
        useCases.togglePin(a.id, pinned = true).getOrThrow()
        assertEquals(listOf("甲", "丙", "乙"), titles())

        clock.add(millis("2026-10-25", "13:00"))
        useCases.togglePin(c.id, pinned = true).getOrThrow()
        // 同为置顶时仍按更新时间倒序
        assertEquals(listOf("丙", "甲", "乙"), titles())

        clock.add(millis("2026-10-25", "14:00"))
        useCases.togglePin(c.id, pinned = false).getOrThrow()
        assertEquals(listOf("甲", "丙", "乙"), titles())
        assertTrue(useCases.get(a.id)!!.pinned)
        assertFalse(useCases.get(c.id)!!.pinned)
    }

    @Test
    fun 关键词搜索命中标题正文与清单项文本且大小写不敏感() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val byTitle = useCases.create(
            NoteInput(title = "Kotlin 学习笔记", content = "与关键词无关的正文"),
        ).getOrThrow()
        val byContent = useCases.create(
            NoteInput(title = "读书摘录", content = "书中提到 Kotlin 协程"),
        ).getOrThrow()
        val byItem = useCases.create(
            NoteInput(title = "采购清单", items = listOf(NoteListItemInput(text = "Kotlin 官方文档打印版"))),
        ).getOrThrow()
        useCases.create(NoteInput(title = "无关便签", content = "其他内容"))

        assertEquals(
            setOf(byTitle.id, byContent.id, byItem.id),
            useCases.search("kotlin").first().map { it.id }.toSet(),
        )
        assertEquals(listOf(byItem.id), useCases.search("官方文档").first().map { it.id })
        assertEquals(listOf(byContent.id), useCases.search("协程").first().map { it.id })
        assertEquals(listOf(byTitle.id), useCases.search("学习笔记").first().map { it.id })
        // 空白关键词不返回结果
        assertTrue(useCases.search("   ").first().isEmpty())
    }

    @Test
    fun 普通删除进入回收站_列表与搜索都排除但不永久删除() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val trashed = useCases.create(
            NoteInput(title = "将被删除", content = "回收站语义", tags = listOf("测试")),
        ).getOrThrow()
        val kept = useCases.create(
            NoteInput(title = "保留便签", content = "提及回收站语义关键词"),
        ).getOrThrow()

        assertEquals(
            setOf(trashed.id, kept.id),
            useCases.search("语义").first().map { it.id }.toSet(),
        )

        clock.add(millis("2026-10-25", "10:00"))
        useCases.trash(trashed.id).getOrThrow()

        // 正常列表排除回收站记录
        assertEquals(listOf("保留便签"), titles())
        // 搜索不检索回收站内容
        assertEquals(listOf(kept.id), useCases.search("语义").first().map { it.id })
        // 记录保留且可通过 id 读取（回收站语义：不永久删除）
        val readBack = useCases.get(trashed.id)
        assertNotNull("回收站记录必须保留以供恢复", readBack)
        assertEquals(millis("2026-10-25", "10:00"), readBack!!.trashedAt)
        assertEquals("将被删除", readBack.title)
        assertNull(useCases.get(kept.id)!!.trashedAt)
    }

    @Test
    fun 全空便签被拒绝且提示为中文() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))

        val blank = useCases.create(NoteInput(title = "   ", content = "  "))
        assertTrue(blank.isFailure)
        assertEquals("便签内容不能为空", blank.exceptionOrNull()!!.message)

        val note = useCases.create(NoteInput(title = "正常便签")).getOrThrow()
        val badUpdate = useCases.update(note.id, NoteInput(title = "", content = ""))
        assertTrue(badUpdate.isFailure)
        assertEquals("便签内容不能为空", badUpdate.exceptionOrNull()!!.message)

        // 清单项文本全空白同样视为空内容
        val blankItems = useCases.create(
            NoteInput(title = "", items = listOf(NoteListItemInput(text = "  "))),
        )
        assertTrue(blankItems.isFailure)
        assertEquals("便签内容不能为空", blankItems.exceptionOrNull()!!.message)
    }

    @Test
    fun 操作不存在的便签或清单项返回中文错误() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val note = useCases.create(
            NoteInput(title = "便签", items = listOf(NoteListItemInput(text = "项"))),
        ).getOrThrow()

        val missingItem = useCases.toggleItem(note.id, "no-such-item", checked = true)
        assertTrue(missingItem.isFailure)
        assertEquals("清单项不存在", missingItem.exceptionOrNull()!!.message)

        val missingNote = useCases.toggleItem("no-such-note", note.items[0].id, checked = true)
        assertTrue(missingNote.isFailure)
        assertEquals("便签不存在或已永久删除", missingNote.exceptionOrNull()!!.message)

        val missingUpdate = useCases.update("no-such-note", NoteInput(title = "任意"))
        assertTrue(missingUpdate.isFailure)
        assertEquals("便签不存在或已永久删除", missingUpdate.exceptionOrNull()!!.message)

        val missingTrash = useCases.trash("no-such-note")
        assertTrue(missingTrash.isFailure)
        assertEquals("便签不存在或已永久删除", missingTrash.exceptionOrNull()!!.message)
    }

    private suspend fun titles(): List<String> =
        useCases.observe().first().map { it.title }
}
