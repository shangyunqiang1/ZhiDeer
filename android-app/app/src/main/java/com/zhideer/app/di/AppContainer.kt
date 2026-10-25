package com.zhideer.app.di

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.zhideer.app.data.ai.AiConfigStore
import com.zhideer.app.data.ai.AiSessionStore
import com.zhideer.app.data.ai.DeepSeekChatClient
import com.zhideer.app.data.ai.EncryptedAiConfigStore
import com.zhideer.app.data.db.ZhiDeerDatabase
import com.zhideer.app.data.repository.CalendarRepositoryImpl
import com.zhideer.app.data.repository.NotesRepositoryImpl
import com.zhideer.app.data.repository.TasksRepositoryImpl
import com.zhideer.app.domain.repository.CalendarRepository
import com.zhideer.app.domain.repository.NotesRepository
import com.zhideer.app.domain.repository.TasksRepository
import com.zhideer.app.domain.usecase.AiChatUseCases
import com.zhideer.app.domain.usecase.CalendarUseCases
import com.zhideer.app.domain.usecase.NoteUseCases
import com.zhideer.app.domain.usecase.TaskUseCases
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.time.ZoneId
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/**
 * 手动依赖容器：统一业务用例入口在此组装，测试可注入替身时钟与生成器。
 */
class AppContainer(
    context: Context,
    zoneId: ZoneId = ZoneId.systemDefault(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    val zoneId: ZoneId = zoneId

    val database: ZhiDeerDatabase = Room.databaseBuilder<ZhiDeerDatabase>(
        context = context,
        name = context.getDatabasePath(ZhiDeerDatabase.NAME).absolutePath,
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        // T04/#5 与 T05/#6 合并后数据库为 1→2→3（日程表 + 便签表）。当前应用尚无对外发布的
        // 真实用户数据，暂以破坏性迁移兜底（旧库重建，不保留历史数据）；
        // 接入真实用户数据前必须替换为保留数据的正式迁移。
        .fallbackToDestructiveMigration()
        .build()

    private val ioDispatcher: CoroutineContext = Dispatchers.IO

    val tasksRepository: TasksRepository = TasksRepositoryImpl(database.taskDao(), Dispatchers.IO)

    val taskUseCases: TaskUseCases = TaskUseCases(
        repository = tasksRepository,
        idGen = { UUID.randomUUID().toString() },
        now = nowMillis,
        zoneId = zoneId.id,
    )

    val calendarRepository: CalendarRepository = CalendarRepositoryImpl(database.calendarEntryDao(), Dispatchers.IO)

    val calendarUseCases: CalendarUseCases = CalendarUseCases(
        repository = calendarRepository,
        idGen = { UUID.randomUUID().toString() },
        now = nowMillis,
        zoneId = zoneId.id,
    )

    val notesRepository: NotesRepository = NotesRepositoryImpl(database.noteDao(), Dispatchers.IO)

    val noteUseCases: NoteUseCases = NoteUseCases(
        repository = notesRepository,
        idGen = { UUID.randomUUID().toString() },
        now = nowMillis,
    )

    // —— AI 对话（T17/#18）：纯新增装配，不改动既有依赖 ——
    // API Key 存本机加密存储（EncryptedSharedPreferences，主密钥 AndroidKeystore）；
    // 会话历史存 DataStore（有意避开 Room，见 AiSessionStore 说明）。
    // 惰性构造：AndroidKeyStore 在 Robolectric 单元测试环境中不可用，
    // 且这些组件仅在 AI 界面使用，不应在应用启动时强制初始化。
    val aiConfigStore: AiConfigStore by lazy { EncryptedAiConfigStore(context) }

    val aiChatClient: DeepSeekChatClient by lazy { DeepSeekChatClient() }

    val aiChatUseCases: AiChatUseCases by lazy {
        AiChatUseCases(
            configStore = aiConfigStore,
            sessionStore = AiSessionStore(File(context.filesDir, "ai_chat_sessions.preferences_pb")),
            client = aiChatClient,
            idGen = { UUID.randomUUID().toString() },
            now = nowMillis,
        )
    }
}
