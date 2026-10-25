package com.zhideer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zhideer.app.ui.CalendarScreen
import com.zhideer.app.ui.HomeScreen
import com.zhideer.app.ui.NotesScreen
import com.zhideer.app.ui.TasksScreen
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ZhiDeerApplication).container
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    MainTabs(container)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // T10/#11：用户可能刚在系统设置改动通知/精确提醒/省电限制，回前台时刷新状态与调度（幂等）
        (application as ZhiDeerApplication).container.refreshReminderStatusOnResume()
    }
}

/** T34/#35：五个底栏——首页、日历、便签、待办、小鹿。 */
private enum class Tab(val label: String) { HOME("首页"), CALENDAR("日历"), NOTES("便签"), TASKS("待办"), DEER("小鹿") }

/** 二级入口（从首页进入，返回回首页）：记忆、回收站、备份、设置、提醒状态、系统闹钟交接。 */
private enum class Secondary(val label: String) {
    MEMORY("记忆"),
    BIN("回收站"),
    BACKUP("备份"),
    SETTINGS("设置"),
    REMINDERS("提醒状态"),
    CLOCK("系统闹钟交接"),
}

@Composable
private fun MainTabs(container: com.zhideer.app.di.AppContainer) {
    var tab by remember { mutableStateOf(Tab.HOME) }
    var secondary by remember { mutableStateOf<Secondary?>(null) }
    var pendingDetailTask by remember { mutableStateOf<com.zhideer.app.domain.model.TodoTask?>(null) }
    var pendingCreating by remember { mutableStateOf(false) }
    // T10/#11：错过提醒合并提示（首页横幅），数据来自系统调度台账的真实错过记录
    val missedReminders by container.reminderRestoreUseCases.observeOutstandingMissed()
        .collectAsState(initial = emptyList())

    secondary?.let { page ->
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { secondary = null }) { Text("← 返回首页") }
            Box(Modifier.fillMaxSize()) {
                when (page) {
                    Secondary.MEMORY -> com.zhideer.app.ui.MemoryScreen(useCases = container.memoryUseCases)
                    Secondary.BIN -> com.zhideer.app.ui.RecycleBinScreen(useCases = container.recycleBinUseCases)
                    Secondary.BACKUP -> com.zhideer.app.ui.BackupScreen(
                        useCases = container.backupUseCases,
                        exportDirectory = container.backupExportDirectory,
                    )
                    Secondary.SETTINGS -> com.zhideer.app.ui.SettingsScreen(
                        appVersion = "0.1.0",
                        onOpenClockHandoff = { secondary = Secondary.CLOCK },
                        onOpenReminderStatus = { secondary = Secondary.REMINDERS },
                    )
                    Secondary.REMINDERS -> com.zhideer.app.ui.ReminderStatusScreen(
                        useCases = container.reminderRestoreUseCases,
                        fixLauncher = container.reminderFixLauncher,
                        zoneId = container.zoneId,
                    )
                    Secondary.CLOCK -> com.zhideer.app.ui.ClockHandoffScreen(
                        useCases = container.clockHandoffUseCases,
                        zoneId = container.zoneId,
                    )
                }
            }
        }
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Text(t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.HOME -> HomeScreen(
                    homeUseCases = container.homeUseCases,
                    taskUseCases = container.taskUseCases,
                    zoneId = container.zoneId,
                    creating = pendingCreating,
                    onCreatingChange = { pendingCreating = it; if (it) tab = Tab.TASKS },
                    onOpenNotes = { tab = Tab.NOTES },
                    onOpenAdvisor = { tab = Tab.DEER },
                    onOpenMemory = { secondary = Secondary.MEMORY },
                    onOpenBin = { secondary = Secondary.BIN },
                    onOpenBackup = { secondary = Secondary.BACKUP },
                    onOpenSettings = { secondary = Secondary.SETTINGS },
                    missedReminderCount = missedReminders.size,
                    onOpenReminders = { secondary = Secondary.REMINDERS },
                    onOpenTaskDetail = { task ->
                        pendingDetailTask = task
                        tab = Tab.TASKS
                    },
                )
                Tab.CALENDAR -> CalendarScreen(
                    useCases = container.calendarUseCases,
                    deviceZone = ZoneId.systemDefault(),
                    calendarReminderUseCases = container.calendarReminderUseCases,
                )
                Tab.NOTES -> NotesScreen(useCases = container.noteUseCases)
                Tab.TASKS -> TasksScreen(
                    useCases = container.taskUseCases,
                    zoneId = container.zoneId,
                    subTaskUseCases = container.subTaskUseCases,
                    linkUseCases = container.taskScheduleLinkUseCases,
                    calendarUseCases = container.calendarUseCases,
                    reminderUseCases = container.reminderUseCases,
                    recurrenceUseCases = container.recurrenceUseCases,
                    occurrenceSubTaskUseCases = container.occurrenceSubTaskUseCases,
                    initialDetailTask = pendingDetailTask,
                    onDetailTaskConsumed = { pendingDetailTask = null },
                    initialCreating = pendingCreating,
                    onCreatingConsumed = { pendingCreating = false },
                )
                Tab.DEER -> com.zhideer.app.ui.AiScreen(
                    useCases = container.aiChatUseCases,
                    proposalUseCases = container.aiProposalUseCases,
                    advisorUseCases = container.advisorUseCases,
                    // T23/#24：语音输入（无服务/权限拒绝时中文提示并按键盘回退）与中文播报（默认关闭）
                    voiceInput = container.voiceInputController,
                    voicePlayback = container.voicePlaybackController,
                )
            }
        }
    }
}
