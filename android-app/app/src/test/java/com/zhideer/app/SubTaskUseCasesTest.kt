package com.zhideer.app

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.zhideer.app.data.db.ZhiDeerDatabase
import com.zhideer.app.data.repository.SubTasksRepositoryImpl
import com.zhideer.app.data.repository.TasksRepositoryImpl
import com.zhideer.app.domain.model.TaskStatus
import com.zhideer.app.domain.model.TodoTask
import com.zhideer.app.domain.usecase.SubTaskMove
import com.zhideer.app.domain.usecase.SubTaskProgress
import com.zhideer.app.domain.usecase.SubTaskUseCases
import com.zhideer.app.domain.usecase.TaskInput
import com.zhideer.app.domain.usecase.TaskUseCases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * T07/#8 验收测试：子任务新增、查看、编辑、删除（回收站语义）、
 * 独立完成互不联动（完成全部子任务不自动完成父待办；完成父待办不自动勾选子任务）、
 * 父子时间戳各自可核对不伪造历史、进度 n/m、排序稳定（上移/下移/重开保持）、
 * 父子一起删除与恢复保留完整关系、重开读取完整显示。
 * 全部走真实 Room 文件库与统一用例入口，时钟与 id 生成器固定注入。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubTaskUseCasesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private lateinit var dbFile: File
    private lateinit var db: ZhiDeerDatabase
    private lateinit var subTaskUseCases: SubTaskUseCases
    private lateinit var taskUseCases: TaskUseCases
    private val clock = mutableListOf(0L)
    private val idSeq = AtomicInteger(0)

    private fun millis(date: String, time: String = "10:00"): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = context.getDatabasePath("t07_test.db")
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
        val taskRepo = TasksRepositoryImpl(db.taskDao(), Dispatchers.IO)
        subTaskUseCases = SubTaskUseCases(
            repository = SubTasksRepositoryImpl(db.subTaskDao(), Dispatchers.IO),
            tasksRepository = taskRepo,
            idGen = { "sub-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
        )
        taskUseCases = TaskUseCases(
            repository = taskRepo,
            idGen = { "task-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
            zoneId = zone.id,
            // 与 AppContainer 相同的组装：父待办进入回收站时联动子任务
            onTrashed = { parentId -> subTaskUseCases.trashForParent(parentId) },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun createParent(title: String = "装修新房"): TodoTask =
        taskUseCases.create(TaskInput(title = title, dueZoneId = zone.id)).getOrThrow()

    private suspend fun titles(parentId: String): List<String> =
        subTaskUseCases.observeByParent(parentId).first().map { it.title }

    private suspend fun doneFlags(parentId: String): List<Boolean> =
        subTaskUseCases.observeByParent(parentId).first().map { it.done }

    @Test
    fun 新增子任务父子关系持久保存并重开后完整显示() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val parent = createParent()
        clock.add(millis("2026-10-25", "09:01"))
        val a = subTaskUseCases.create(parent.id, "量尺寸").getOrThrow()
        val b = subTaskUseCases.create(parent.id, "买材料").getOrThrow()
        val c = subTaskUseCases.create(parent.id, "约工人").getOrThrow()

        assertEquals(parent.id, a.parentId)
        assertEquals(listOf("量尺寸", "买材料", "约工人"), titles(parent.id))
        // 新子任务按创建顺序排在最后，初始未完成
        assertEquals(listOf(0, 1, 2), listOf(a, b, c).map { it.sortOrder })
        assertTrue(listOf(a, b, c).none { it.done })
        assertTrue(listOf(a, b, c).all { it.doneAt == null && it.trashedAt == null })

        // 离线重开：关闭后重新打开同一数据库文件，父子关系完整显示
        db.close()
        open()

        assertEquals(listOf("量尺寸", "买材料", "约工人"), titles(parent.id))
        val readBack = subTaskUseCases.get(b.id)
        assertNotNull(readBack)
        assertEquals(parent.id, readBack!!.parentId)
        assertEquals("买材料", readBack.title)
    }

    @Test
    fun 编辑子任务更新标题并保留创建时间() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val parent = createParent()
        val sub = subTaskUseCases.create(parent.id, "量尺寸").getOrThrow()

        clock.add(millis("2026-10-25", "10:00"))
        val updated = subTaskUseCases.update(sub.id, "精确量尺寸").getOrThrow()
        assertEquals("精确量尺寸", updated.title)
        assertEquals(millis("2026-10-25", "10:00"), updated.updatedAt)
        assertEquals(sub.createdAt, updated.createdAt)
        assertEquals(parent.id, updated.parentId)

        db.close()
        open()
        assertEquals("精确量尺寸", subTaskUseCases.get(sub.id)!!.title)
    }

    @Test
    fun 完成全部子任务后父待办仍由用户主动确认完成() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val parent = createParent()
        val a = subTaskUseCases.create(parent.id, "第一步").getOrThrow()
        val b = subTaskUseCases.create(parent.id, "第二步").getOrThrow()

        clock.add(millis("2026-10-25", "10:00"))
        subTaskUseCases.setDone(a.id, done = true).getOrThrow()
        clock.add(millis("2026-10-25", "11:00"))
        subTaskUseCases.setDone(b.id, done = true).getOrThrow()

        assertEquals(SubTaskProgress(total = 2, done = 2), subTaskUseCases.progress(parent.id))
        // 全部子任务完成不会自动完成父待办
        val stillActive = taskUseCases.get(parent.id)!!
        assertEquals(TaskStatus.ACTIVE, stillActive.status)
        assertNull(stillActive.completedAt)

        // 父待办必须由用户主动确认完成
        clock.add(millis("2026-10-25", "12:00"))
        taskUseCases.complete(parent.id, completed = true).getOrThrow()
        assertEquals(TaskStatus.COMPLETED, taskUseCases.get(parent.id)!!.status)
        // 且父待办完成不自动改变任何子任务状态
        assertEquals(listOf(true, true), doneFlags(parent.id))
    }

    @Test
    fun 提前完成父待办时展示未完成步骤且不自动勾选子任务() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val parent = createParent()
        val a = subTaskUseCases.create(parent.id, "第一步").getOrThrow()
        val b = subTaskUseCases.create(parent.id, "第二步").getOrThrow()

        clock.add(millis("2026-10-25", "10:00"))
        subTaskUseCases.setDone(a.id, done = true).getOrThrow()

        // 提前完成前可核对未完成步骤（供界面展示提示）
        assertEquals(listOf("第二步"), subTaskUseCases.pendingTitles(parent.id))
        assertEquals(SubTaskProgress(total = 2, done = 1), subTaskUseCases.progress(parent.id))

        clock.add(millis("2026-10-25", "11:00"))
        taskUseCases.complete(parent.id, completed = true).getOrThrow()

        // 不自动勾选任何子任务，已勾选的保持原样
        assertEquals(listOf(true, false), doneFlags(parent.id))
        assertNull(subTaskUseCases.get(b.id)!!.doneAt)
        assertEquals(listOf("第二步"), subTaskUseCases.pendingTitles(parent.id))
    }

    @Test
    fun 父子状态变化分别记录时间戳且不伪造对方历史() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val parent = createParent()
        clock.add(millis("2026-10-25", "09:30"))
        val sub = subTaskUseCases.create(parent.id, "唯一一步").getOrThrow()

        clock.add(millis("2026-10-25", "10:00"))
        val done = subTaskUseCases.setDone(sub.id, done = true).getOrThrow()
        assertEquals(millis("2026-10-25", "10:00"), done.doneAt)
        // 完成子任务不触碰父待办的完成记录与更新时间
        var p = taskUseCases.get(parent.id)!!
        assertNull(p.completedAt)
        assertEquals(millis("2026-10-25", "09:00"), p.updatedAt)

        clock.add(millis("2026-10-25", "11:00"))
        taskUseCases.complete(parent.id, completed = true).getOrThrow()
        p = taskUseCases.get(parent.id)!!
        assertEquals(millis("2026-10-25", "11:00"), p.completedAt)
        // 完成父待办不伪造子任务的完成历史
        assertEquals(millis("2026-10-25", "10:00"), subTaskUseCases.get(sub.id)!!.doneAt)

        clock.add(millis("2026-10-25", "12:00"))
        taskUseCases.complete(parent.id, completed = false).getOrThrow()
        assertNull(taskUseCases.get(parent.id)!!.completedAt)
        // 撤销父待办完成也不清除子任务自己的完成记录
        assertEquals(millis("2026-10-25", "10:00"), subTaskUseCases.get(sub.id)!!.doneAt)

        clock.add(millis("2026-10-25", "13:00"))
        subTaskUseCases.setDone(sub.id, done = false).getOrThrow()
        assertNull(subTaskUseCases.get(sub.id)!!.doneAt)
        assertNull(taskUseCases.get(parent.id)!!.completedAt)
    }

    @Test
    fun 子任务排序稳定且支持上移下移重开后保持() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val parent = createParent()
        val a = subTaskUseCases.create(parent.id, "甲").getOrThrow()
        val b = subTaskUseCases.create(parent.id, "乙").getOrThrow()
        val c = subTaskUseCases.create(parent.id, "丙").getOrThrow()
        assertEquals(listOf("甲", "乙", "丙"), titles(parent.id))

        clock.add(millis("2026-10-25", "10:00"))
        subTaskUseCases.move(c.id, SubTaskMove.UP).getOrThrow()
        assertEquals(listOf("甲", "丙", "乙"), titles(parent.id))

        subTaskUseCases.move(c.id, SubTaskMove.UP).getOrThrow()
        assertEquals(listOf("丙", "甲", "乙"), titles(parent.id))
        // 已在顶部再上移：操作成功且顺序不变
        val atTop = subTaskUseCases.move(c.id, SubTaskMove.UP).getOrThrow()
        assertEquals(c.id, atTop.id)
        assertEquals(listOf("丙", "甲", "乙"), titles(parent.id))

        subTaskUseCases.move(c.id, SubTaskMove.DOWN).getOrThrow()
        assertEquals(listOf("甲", "丙", "乙"), titles(parent.id))

        // 重开读取顺序保持
        db.close()
        open()
        assertEquals(listOf("甲", "丙", "乙"), titles(parent.id))

        // 顶部再上移与底部再下移均为无操作成功
        subTaskUseCases.move(a.id, SubTaskMove.UP).getOrThrow()
        assertEquals(listOf("甲", "丙", "乙"), titles(parent.id))
        subTaskUseCases.move(b.id, SubTaskMove.DOWN).getOrThrow()
        assertEquals(listOf("甲", "丙", "乙"), titles(parent.id))
    }

    @Test
    fun 删除子任务进入回收站并从列表排除但不永久删除() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val parent = createParent()
        val a = subTaskUseCases.create(parent.id, "甲").getOrThrow()
        val b = subTaskUseCases.create(parent.id, "乙").getOrThrow()
        val c = subTaskUseCases.create(parent.id, "丙").getOrThrow()
        clock.add(millis("2026-10-25", "10:00"))
        subTaskUseCases.setDone(b.id, done = true).getOrThrow()

        clock.add(millis("2026-10-25", "11:00"))
        subTaskUseCases.delete(b.id).getOrThrow()

        assertEquals(listOf("甲", "丙"), titles(parent.id))
        // 回收站语义：记录保留、关系保留、删除时间戳可核对
        val kept = subTaskUseCases.get(b.id)
        assertNotNull("回收站记录必须保留以供恢复", kept)
        assertEquals(parent.id, kept!!.parentId)
        assertEquals(millis("2026-10-25", "11:00"), kept.trashedAt)
        assertTrue(kept.done)
        // 进度只统计未删除的子任务
        assertEquals(SubTaskProgress(total = 2, done = 0), subTaskUseCases.progress(parent.id))
        assertEquals(listOf("甲", "丙"), subTaskUseCases.pendingTitles(parent.id))
        assertNull(subTaskUseCases.get(a.id)!!.trashedAt)

        db.close()
        open()
        assertEquals(listOf("甲", "丙"), titles(parent.id))
        assertNotNull(subTaskUseCases.get(b.id))
    }

    @Test
    fun 父待办删除时子任务一起进入回收站且恢复后关系完整() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val parent = createParent()
        clock.add(millis("2026-10-25", "09:30"))
        val a = subTaskUseCases.create(parent.id, "甲").getOrThrow()
        val b = subTaskUseCases.create(parent.id, "乙").getOrThrow()
        clock.add(millis("2026-10-25", "10:00"))
        subTaskUseCases.setDone(a.id, done = true).getOrThrow()

        clock.add(millis("2026-10-25", "11:00"))
        taskUseCases.trash(parent.id).getOrThrow()

        // 子任务与父待办一起进入回收站，同一删除时间戳，父子关系保留
        assertEquals(emptyList<String>(), titles(parent.id))
        val aKept = subTaskUseCases.get(a.id)!!
        assertEquals(parent.id, aKept.parentId)
        assertEquals(millis("2026-10-25", "11:00"), aKept.trashedAt)
        assertTrue(aKept.done)
        assertEquals(millis("2026-10-25", "11:00"), subTaskUseCases.get(b.id)!!.trashedAt)

        // 恢复（回收站交互由对应任务完善，此处验证用例能力）：关系与状态完整还原
        clock.add(millis("2026-10-25", "12:00"))
        subTaskUseCases.restoreForParent(parent.id, millis("2026-10-25", "11:00")).getOrThrow()
        assertEquals(listOf("甲", "乙"), titles(parent.id))
        assertTrue(subTaskUseCases.get(a.id)!!.done)
        assertNull(subTaskUseCases.get(a.id)!!.trashedAt)
        assertNull(subTaskUseCases.get(b.id)!!.trashedAt)

        // 重复恢复同一批次或恢复不存在的批次均为无操作成功
        subTaskUseCases.restoreForParent(parent.id, millis("2026-10-25", "11:00")).getOrThrow()
        subTaskUseCases.restoreForParent(parent.id, millis("2020-01-01", "08:00")).getOrThrow()
        assertEquals(listOf("甲", "乙"), titles(parent.id))
    }

    @Test
    fun 非法输入与不存在记录返回中文错误() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val parent = createParent()

        val noParent = subTaskUseCases.create("no-such-parent", "任意")
        assertTrue(noParent.isFailure)
        assertEquals("父待办不存在或已进入回收站", noParent.exceptionOrNull()!!.message)

        val blank = subTaskUseCases.create(parent.id, "   ")
        assertTrue(blank.isFailure)
        assertEquals("子任务标题不能为空", blank.exceptionOrNull()!!.message)

        val sub = subTaskUseCases.create(parent.id, "合法步骤").getOrThrow()
        val badUpdate = subTaskUseCases.update(sub.id, "  ")
        assertTrue(badUpdate.isFailure)
        assertEquals("子任务标题不能为空", badUpdate.exceptionOrNull()!!.message)

        val missingUpdate = subTaskUseCases.update("no-such-sub", "任意")
        assertTrue(missingUpdate.isFailure)
        assertEquals("子任务不存在", missingUpdate.exceptionOrNull()!!.message)

        val missingDone = subTaskUseCases.setDone("no-such-sub", done = true)
        assertTrue(missingDone.isFailure)
        assertEquals("子任务不存在", missingDone.exceptionOrNull()!!.message)

        val missingMove = subTaskUseCases.move("no-such-sub", SubTaskMove.UP)
        assertTrue(missingMove.isFailure)
        assertEquals("子任务不存在", missingMove.exceptionOrNull()!!.message)

        val missingDelete = subTaskUseCases.delete("no-such-sub")
        assertTrue(missingDelete.isFailure)
        assertEquals("子任务不存在", missingDelete.exceptionOrNull()!!.message)

        // 已进入回收站的父待办不能再添加子任务
        clock.add(millis("2026-10-25", "10:00"))
        taskUseCases.trash(parent.id).getOrThrow()
        val trashedParent = subTaskUseCases.create(parent.id, "新步骤")
        assertTrue(trashedParent.isFailure)
        assertEquals("父待办不存在或已进入回收站", trashedParent.exceptionOrNull()!!.message)
    }
}
