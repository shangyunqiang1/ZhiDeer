package com.zhideer.app

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.zhideer.app.data.db.ZhiDeerDatabase
import com.zhideer.app.data.repository.ReminderScheduleLogRepositoryImpl
import com.zhideer.app.domain.model.ReminderRestoreTrigger
import com.zhideer.app.domain.usecase.ReminderRestoreUseCases
import com.zhideer.app.platform.AndroidNotificationPoster
import com.zhideer.app.platform.NotificationPoster
import com.zhideer.app.platform.ReminderAlarmReceiver
import com.zhideer.app.platform.ReminderDeliveryRecorder
import com.zhideer.app.platform.ReminderRestoreReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * T10/#11 平台接线测试：设备变化广播声明与动作映射、提醒投递台账。
 * 真实重启/改时间/改时区/省电限制仍需在 OPPO 真机按 docs/validation/t10-restore.md 执行，
 * 业务模拟不能替代真实设备验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReminderDeviceChangeTest {

    private lateinit var dbFile: File
    private lateinit var db: ZhiDeerDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = context.getDatabasePath("t10_device_change_test.db")
        dbFile.parentFile?.mkdirs()
        context.deleteDatabase(dbFile.name)
        db = Room.databaseBuilder<ZhiDeerDatabase>(
            context = context,
            name = dbFile.absolutePath,
        )
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun 清单声明重启时间时区与精确提醒权限变化恢复接收器() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager
        val packageInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(
            "重启恢复必须声明 RECEIVE_BOOT_COMPLETED",
            packageInfo.requestedPermissions?.contains(Manifest.permission.RECEIVE_BOOT_COMPLETED) == true,
        )

        val actions = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
        actions.forEach { action ->
            val receivers = pm.queryBroadcastReceivers(
                Intent(action).setPackage(context.packageName),
                PackageManager.MATCH_ALL,
            )
            assertTrue(
                "$action 必须由 ReminderRestoreReceiver 接收，实际：${receivers.map { it.activityInfo?.name }}",
                receivers.any { it.activityInfo?.name == ReminderRestoreReceiver::class.java.name },
            )
        }
    }

    @Test
    fun 广播动作映射到恢复原因且未知动作不恢复() {
        assertEquals(
            ReminderRestoreTrigger.DEVICE_BOOT,
            ReminderRestoreReceiver.triggerFor(Intent.ACTION_BOOT_COMPLETED),
        )
        assertEquals(
            ReminderRestoreTrigger.TIME_CHANGED,
            ReminderRestoreReceiver.triggerFor(Intent.ACTION_TIME_CHANGED),
        )
        assertEquals(
            ReminderRestoreTrigger.TIMEZONE_CHANGED,
            ReminderRestoreReceiver.triggerFor(Intent.ACTION_TIMEZONE_CHANGED),
        )
        assertEquals(
            ReminderRestoreTrigger.EXACT_ALARM_PERMISSION_CHANGED,
            ReminderRestoreReceiver.triggerFor(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED),
        )
        assertNull(ReminderRestoreReceiver.triggerFor(Intent.ACTION_SCREEN_ON))
        assertNull(ReminderRestoreReceiver.triggerFor(null))
    }

    @Test
    fun 提醒到达投递成功才记录已投递投递失败保持待投递() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val delivered = mutableListOf<Triple<String, String, Long>>()
        var postResult = true
        val poster = object : NotificationPoster {
            override fun ensureChannel() = Unit
            override fun post(reminderId: String, taskId: String, title: String, text: String): Boolean =
                postResult
        }
        ReminderAlarmReceiver.posterFactory = { poster }
        ReminderAlarmReceiver.deliveryRecorderFactory = { _ ->
            ReminderDeliveryRecorder { reminderKey, ownerId, _ownerTitle, scheduledAtMillis ->
                delivered += Triple(reminderKey, ownerId, scheduledAtMillis)
            }
        }

        // 投递成功：记录已投递（携带计划时刻，供台账按“键@时刻”区分）
        ReminderAlarmReceiver().onReceive(
            context,
            ReminderAlarmReceiver.buildIntent(context, "rem-1", "task-1", "装修新房", 1_000L),
        )
        assertEquals(listOf(Triple("rem-1", "task-1", 1_000L)), delivered)

        // 投递失败（如通知权限关闭）：不记录已投递，调度仍待投递，恢复时如实进入错过提示
        postResult = false
        ReminderAlarmReceiver().onReceive(
            context,
            ReminderAlarmReceiver.buildIntent(context, "rem-2", "task-2", "交水电费", 2_000L),
        )
        assertEquals(1, delivered.size)

        // 旧版本残留闹钟（缺少计划时刻）：无法核对时刻，不写台账
        postResult = true
        ReminderAlarmReceiver().onReceive(
            context,
            ReminderAlarmReceiver.buildIntent(context, "rem-3", "task-3", "旧提醒"),
        )
        assertEquals(1, delivered.size)

        // 还原生产工厂，避免影响其他测试类
        ReminderAlarmReceiver.posterFactory = { ctx -> AndroidNotificationPoster(ctx) }
        ReminderAlarmReceiver.deliveryRecorderFactory = { ctx ->
            (ctx.applicationContext as? ZhiDeerApplication)?.container?.reminderDeliveryRecorder
        }
    }

    @Test
    fun 调度台账只转换一次且终态不会被重复恢复覆盖() = runBlocking {
        val repository = ReminderScheduleLogRepositoryImpl(db.reminderScheduleLogDao(), Dispatchers.IO)
        val now = 10_000L

        // 已提交调度：待投递
        repository.recordPending("rem-1", "task-1", "装修新房", 5_000L, recordedAtMillis = 1_000L)
        repository.recordPending("rem-1", "task-1", "装修新房", 5_000L, recordedAtMillis = 2_000L)
        assertEquals(1, repository.listPending().size)

        // 设备变化恢复：过去时刻仍待投递 → 错过（只转换一次）
        assertEquals(1, repository.markMissedBefore(now, now).size)
        assertEquals(0, repository.markMissedBefore(now, now).size)
        assertEquals(1, repository.listOutstandingMissed().size)
        assertTrue(repository.listTerminalInstants().contains("rem-1@5000"))

        // 终态不会被同一键同刻的重复调度请求覆盖（时间倒拨也不补响）
        repository.recordPending("rem-1", "task-1", "装修新房", 5_000L, recordedAtMillis = 3_000L)
        assertEquals(0, repository.listPending().size)

        // 投递成功的提醒同样进入终态；超时补记不会再次排出待投递
        repository.recordPending("cal:rem-2", "entry-1", "周会", 8_000L, recordedAtMillis = 4_000L)
        repository.markDelivered("cal:rem-2", "entry-1", "周会", 8_000L, deliveredAtMillis = 9_000L)
        assertEquals(0, repository.listPending().size)

        // 用户确认知悉后不再显示合并提示，但终态抑制仍保留
        repository.recordPending("rem-3", "task-3", "交水电费", 6_000L, recordedAtMillis = 5_000L)
        repository.markMissedBefore(now, now)
        // rem-1 与 rem-3 两条错过提示一并确认
        assertEquals(2, repository.acknowledgeMissed(now))
        assertTrue(repository.listOutstandingMissed().isEmpty())
        assertTrue(repository.listTerminalInstants().contains("rem-3@6000"))
        assertTrue(repository.listTerminalInstants().contains("rem-1@5000"))
    }
}
