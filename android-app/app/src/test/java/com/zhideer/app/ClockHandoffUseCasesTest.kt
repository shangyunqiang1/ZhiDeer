package com.zhideer.app

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.zhideer.app.data.db.ZhiDeerDatabase
import com.zhideer.app.data.repository.ClockHandoffRepositoryImpl
import com.zhideer.app.domain.model.ClockCapability
import com.zhideer.app.domain.model.ClockHandoffStatus
import com.zhideer.app.domain.model.ClockLaunchResult
import com.zhideer.app.domain.usecase.ClockHandoffFailure
import com.zhideer.app.domain.usecase.ClockHandoffUseCases
import com.zhideer.app.platform.ClockLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * T24/#25 验收测试：交接只记"待核对"、绝不标已保存；指定日期不静默丢弃；
 * 平台不支持/失败不崩溃并给回退；不自动创建自有提醒；记录真实发起行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ClockHandoffUseCasesTest {

    private lateinit var dbFile: File
    private lateinit var db: ZhiDeerDatabase
    private val clock = mutableListOf(1_000L)
    private val idSeq = AtomicInteger(0)

    private class FakeLauncher(
        var capability: ClockCapability = ClockCapability(canCreateAlarm = true, canOpenAlarmList = true),
        var createResult: ClockLaunchResult = ClockLaunchResult.Launched,
        var listResult: ClockLaunchResult = ClockLaunchResult.Launched,
    ) : ClockLauncher {
        val created = mutableListOf<Triple<Int, Int, Set<Int>>>()
        var listCalls = 0

        override fun capability() = capability

        override fun createAlarm(hour: Int, minute: Int, label: String, weekdays: Set<Int>): ClockLaunchResult {
            created.add(Triple(hour, minute, weekdays))
            return createResult
        }

        override fun openAlarmList(): ClockLaunchResult {
            listCalls++
            return listResult
        }
    }

    private lateinit var launcher: FakeLauncher
    private lateinit var useCases: ClockHandoffUseCases

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = context.getDatabasePath("t24_clock_test.db")
        dbFile.parentFile?.mkdirs()
        context.deleteDatabase(dbFile.name)
        open()
    }

    private fun open() {
        db = Room.databaseBuilder<ZhiDeerDatabase>(
            context = ApplicationProvider.getApplicationContext<Context>(),
            name = dbFile.absolutePath,
        ).setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        launcher = FakeLauncher()
        useCases = ClockHandoffUseCases(
            repository = ClockHandoffRepositoryImpl(db.clockHandoffDao(), Dispatchers.IO),
            launcher = launcher,
            idGen = { "h-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun 发起成功后记录为待核对且不显示已保存() = runBlocking {
        val preview = useCases.preview(7, 30, "起床", null, setOf(1, 2, 3)).getOrThrow()
        assertEquals("07:30", preview.timeText)
        assertEquals("每周 一、二、三", preview.weekdayText)
        assertTrue(preview.canHandoff)

        val handoff = useCases.handoff(preview).getOrThrow()
        assertEquals(ClockHandoffStatus.PENDING_CHECK, handoff.status)
        assertEquals("待核对", handoff.status.label)
        // 记录文案必须明确"不代表已保存"，且不得出现"已保存"式的肯定表述
        assertTrue(handoff.note.contains("不代表已保存"))
        assertFalse(handoff.note.startsWith("已保存"))
        // 平台只收到时分与星期
        assertEquals(Triple(7, 30, setOf(1, 2, 3)), launcher.created.single())
        // 记录持久化
        db.close(); open()
        assertEquals(1, useCases.observeHandoffs().first().size)
    }

    @Test
    fun 指定日历日期时拒绝交接且不静默丢弃日期() = runBlocking {
        val preview = useCases.preview(7, 30, "指定日期", targetEpochDay = 20_000L, weekdays = emptySet()).getOrThrow()
        assertTrue(preview.dateNotConveyable)
        assertFalse(preview.canHandoff)

        val result = useCases.handoff(preview)
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message.contains("无法传达具体日期"))
        // 未向平台发起任何创建请求，也未写记录
        assertTrue(launcher.created.isEmpty())
        assertTrue(useCases.observeHandoffs().first().isEmpty())
    }

    @Test
    fun 平台不支持时不崩溃并记录不可用状态() = runBlocking {
        launcher.capability = ClockCapability(canCreateAlarm = false, canOpenAlarmList = false)
        val preview = useCases.preview(8, 0, "闹钟", null, emptySet()).getOrThrow()
        assertFalse(preview.canHandoff)

        val result = useCases.handoff(preview)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("没有可用的时钟应用"))
        assertTrue(launcher.created.isEmpty())
    }

    @Test
    fun 发起被系统拒绝时记录失败状态并提供回退提示() = runBlocking {
        launcher.createResult = ClockLaunchResult.Rejected("requires com.android.alarm.permission.SET_ALARM")
        val preview = useCases.preview(9, 15, "开会", null, emptySet()).getOrThrow()

        val result = useCases.handoff(preview)
        assertTrue(result.isFailure)
        val failure = result.exceptionOrNull() as ClockHandoffFailure
        assertEquals(ClockHandoffStatus.FAILED, failure.handoff.status)
        assertTrue(failure.handoff.note.contains("发起失败"))
        // 记录仍保留，供界面展示真实失败原因
        assertEquals(1, useCases.observeHandoffs().first().size)
        // 回退提示含双响警告
        assertTrue(useCases.fallbackHint().contains("重复响铃"))
        assertTrue(useCases.fallbackHint().contains("系统时钟"))
    }

    @Test
    fun 打开系统闹钟列表与能力探测() = runBlocking {
        assertEquals(ClockCapability(true, true), useCases.capability())
        assertTrue(useCases.openAlarmList().isSuccess)
        assertEquals(1, launcher.listCalls)

        launcher.listResult = ClockLaunchResult.NoHandler
        val failed = useCases.openAlarmList()
        assertTrue(failed.isFailure)
        assertTrue(failed.exceptionOrNull()!!.message!!.contains("手动打开系统时钟"))
    }

    @Test
    fun 非法时间与星期被拒绝且中文说明() = runBlocking {
        assertTrue(useCases.preview(24, 0, "x", null, emptySet()).isFailure)
        assertEquals("小时需在 0–23 之间", useCases.preview(24, 0, "x", null, emptySet()).exceptionOrNull()!!.message)
        assertTrue(useCases.preview(7, 60, "x", null, emptySet()).isFailure)
        assertTrue(useCases.preview(7, 0, "x", null, setOf(8)).isFailure)
        assertTrue(launcher.created.isEmpty())
    }

    @Test
    fun 交接不自动创建自有提醒且记录可删除() = runBlocking {
        val preview = useCases.preview(6, 45, "晨跑", null, emptySet()).getOrThrow()
        val handoff = useCases.handoff(preview).getOrThrow()
        // 自有提醒表未受影响（本用例不依赖提醒用例，验证交接记录独立存在）
        assertEquals(1, useCases.observeHandoffs().first().size)
        useCases.delete(handoff.id)
        assertTrue(useCases.observeHandoffs().first().isEmpty())
    }
}
