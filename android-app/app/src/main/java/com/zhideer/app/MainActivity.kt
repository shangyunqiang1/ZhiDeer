package com.zhideer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zhideer.app.ui.CalendarScreen
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
}

private enum class Tab(val label: String) { TASKS("待办"), CALENDAR("日程"), NOTES("便签"), AI("AI"), BIN("回收站") }

@Composable
private fun MainTabs(container: com.zhideer.app.di.AppContainer) {
    var tab by remember { mutableStateOf(Tab.TASKS) }
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
                Tab.TASKS -> TasksScreen(
                    useCases = container.taskUseCases,
                    zoneId = container.zoneId,
                    subTaskUseCases = container.subTaskUseCases,
                    linkUseCases = container.taskScheduleLinkUseCases,
                    calendarUseCases = container.calendarUseCases,
                    reminderUseCases = container.reminderUseCases,
                    recurrenceUseCases = container.recurrenceUseCases,
                )
                Tab.CALENDAR -> CalendarScreen(
                    useCases = container.calendarUseCases,
                    deviceZone = ZoneId.systemDefault(),
                    calendarReminderUseCases = container.calendarReminderUseCases,
                )
                Tab.NOTES -> NotesScreen(useCases = container.noteUseCases)
                Tab.AI -> com.zhideer.app.ui.AiScreen(useCases = container.aiChatUseCases)
                Tab.BIN -> com.zhideer.app.ui.RecycleBinScreen(useCases = container.recycleBinUseCases)
            }
        }
    }
}
