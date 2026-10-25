package com.zhideer.app.di

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.zhideer.app.data.ai.AiConfigStore
import com.zhideer.app.data.ai.AiProposalStore
import com.zhideer.app.data.ai.AiSessionStore
import com.zhideer.app.data.ai.DeepSeekChatClient
import com.zhideer.app.data.ai.EncryptedAiConfigStore
import com.zhideer.app.data.ai.RepositoryAiContextProvider
import com.zhideer.app.data.db.ZhiDeerDatabase
import com.zhideer.app.data.repository.CalendarRemindersRepositoryImpl
import com.zhideer.app.data.repository.CalendarRepositoryImpl
import com.zhideer.app.data.repository.MemoriesRepositoryImpl
import com.zhideer.app.data.repository.NotesRepositoryImpl
import com.zhideer.app.data.repository.OccurrenceSubTaskRecordsRepositoryImpl
import com.zhideer.app.data.repository.RemindersRepositoryImpl
import com.zhideer.app.data.repository.SubTasksRepositoryImpl
import com.zhideer.app.data.repository.TaskOccurrenceRepositoryImpl
import com.zhideer.app.data.repository.TasksRepositoryImpl
import com.zhideer.app.domain.repository.CalendarRemindersRepository
import com.zhideer.app.domain.repository.CalendarRepository
import com.zhideer.app.domain.model.MemoryRefType
import com.zhideer.app.domain.repository.MemoriesRepository
import com.zhideer.app.domain.repository.NotesRepository
import com.zhideer.app.domain.repository.OccurrenceSubTaskRecordsRepository
import com.zhideer.app.domain.repository.RemindersRepository
import com.zhideer.app.domain.repository.SubTasksRepository
import com.zhideer.app.domain.repository.TaskOccurrenceRepository
import com.zhideer.app.domain.repository.TasksRepository
import com.zhideer.app.domain.usecase.AiChatUseCases
import com.zhideer.app.domain.usecase.AiContextAuthorizer
import com.zhideer.app.domain.usecase.AiContextProvider
import com.zhideer.app.domain.usecase.AiProposalTargetMatcher
import com.zhideer.app.domain.usecase.AiProposalUseCases
import com.zhideer.app.domain.usecase.BackupUseCases
import com.zhideer.app.domain.usecase.CalendarReminderUseCases
import com.zhideer.app.domain.usecase.CalendarUseCases
import com.zhideer.app.domain.usecase.MemoryUseCases
import com.zhideer.app.domain.usecase.NoteUseCases
import com.zhideer.app.domain.usecase.OccurrenceSubTaskUseCases
import com.zhideer.app.domain.usecase.RecycleBinKind
import com.zhideer.app.domain.usecase.RecycleBinUseCases
import com.zhideer.app.domain.usecase.RecurrenceUseCases
import com.zhideer.app.domain.usecase.ReminderUseCases
import com.zhideer.app.domain.usecase.SubTaskUseCases
import com.zhideer.app.domain.usecase.TaskScheduleLinkUseCases
import com.zhideer.app.domain.usecase.TaskUseCases
import com.zhideer.app.platform.AlarmScheduler
import com.zhideer.app.platform.AndroidNotificationPoster
import com.zhideer.app.platform.NotificationPoster
import com.zhideer.app.platform.PlatformAlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
        // T06/#7（日程外键 taskId）与 T07/#8（sub_tasks 子任务表）合并后数据库升至 5，
        // T08/#9（reminders 提醒表）后升至 6。当前应用尚无对外发布的
        // 真实用户数据，暂以破坏性迁移兜底（旧库重建，不保留历史数据）；
        // 接入真实用户数据前必须替换为保留数据的正式迁移。
        .fallbackToDestructiveMigration()
        .build()

    private val ioDispatcher: CoroutineContext = Dispatchers.IO

    // —— T26/#27 长期记忆：纯新增装配（T27/#28 起先于普通事项用例，供来源删除边界联动钩子引用）——
    // 记忆是独立于便签与待办的隐私数据：独立 memories 表与独立入口，删除即彻底删除
    // （不进回收站、不套普通事项三十天保留规则）；只有用户显式保存并确认的内容成为
    // 长期记忆（T28/#29 起含聊天确认来源），是否随 AI 请求外发由授权体系管理。
    val memoriesRepository: MemoriesRepository =
        MemoriesRepositoryImpl(database.memoryDao(), Dispatchers.IO)

    val memoryUseCases: MemoryUseCases = MemoryUseCases(
        repository = memoriesRepository,
        idGen = { UUID.randomUUID().toString() },
        now = nowMillis,
    )

    val tasksRepository: TasksRepository = TasksRepositoryImpl(database.taskDao(), Dispatchers.IO)

    val subTasksRepository: SubTasksRepository = SubTasksRepositoryImpl(database.subTaskDao(), Dispatchers.IO)

    // 子任务用例先于待办用例组装，供待办删除时的子任务联动钩子引用（#8）。
    val subTaskUseCases: SubTaskUseCases = SubTaskUseCases(
        repository = subTasksRepository,
        tasksRepository = tasksRepository,
        idGen = { UUID.randomUUID().toString() },
        now = nowMillis,
    )

    // T08/#9 提醒：平台适配器与用例先于待办用例组装，供截止变化/取消/删除的联动钩子引用。
    val remindersRepository: RemindersRepository = RemindersRepositoryImpl(database.reminderDao(), Dispatchers.IO)

    val alarmScheduler: AlarmScheduler = PlatformAlarmScheduler(context)

    val notificationPoster: NotificationPoster = AndroidNotificationPoster(context)

    val reminderUseCases: ReminderUseCases = ReminderUseCases(
        repository = remindersRepository,
        tasksRepository = tasksRepository,
        scheduler = alarmScheduler,
        idGen = { UUID.randomUUID().toString() },
        now = nowMillis,
    )

    // T12/#13 纯新增：重复出现完成记录仓库先于待办用例组装，
    // 供列表过滤合成“下一次未完成出现”与完成转发使用。
    val taskOccurrenceRepository: TaskOccurrenceRepository = TaskOccurrenceRepositoryImpl(
        database.taskOccurrenceDao(),
        Dispatchers.IO,
    )

    val recurrenceUseCases: RecurrenceUseCases = RecurrenceUseCases(
        tasksRepository = tasksRepository,
        occurrenceRepository = taskOccurrenceRepository,
        now = nowMillis,
    )

    val taskUseCases: TaskUseCases = TaskUseCases(
        repository = tasksRepository,
        idGen = { UUID.randomUUID().toString() },
        now = nowMillis,
        zoneId = zoneId.id,
        // #8：父待办进入回收站时子任务联动删除，保留父子关系供恢复；
        // #9：待办删除时其提醒一并停止（取消闹钟并清除记录）
        // #12：待办删除时保留全部关联日程并解除关联（删除待办不连带删除日程）
        // #28：待办删除（回收站）时立即停用引用它的记忆并标记来源缺失
        onTrashed = { taskId ->
            subTaskUseCases.trashForParent(taskId)
            reminderUseCases.cancelReminders(taskId)
            taskScheduleLinkUseCases.unlinkTask(taskId)
            memoryUseCases.markSourceMissing(MemoryRefType.TASK, taskId)
        },
        // #9：截止编辑/延期后相对提醒随截止移动，独立时刻提醒不动
        onDueChanged = { taskId -> reminderUseCases.rescheduleForDueChange(taskId) },
        // #9：取消待办同时停止该待办自身的提醒（GLOSSARY）
        onCancelled = { taskId -> reminderUseCases.cancelReminders(taskId) },
        // #12：完成待办同时停止该待办自身的全部提醒（issue #12 验收：完成停止不再适用的提醒）；
        // 撤销完成不自动恢复已停止的提醒（不假装恢复，界面如实提示需重新设置）。
        // 未来关联日程是否一并取消由界面经 CompleteTaskFutureSchedulesDialog 让用户选择，此处不隐式处置。
        onCompleted = { taskId, completed ->
            if (completed) reminderUseCases.cancelReminders(taskId)
        },
        // #13：重复待办的列表过滤与完成走出现维度（每一次分别记录完成）
        occurrenceRepository = taskOccurrenceRepository,
    )

    // T15/#16 纯新增：出现维度子任务步骤记录仓库与用例——重复待办的每一次出现
    // 按父待办子任务模板分别执行步骤，勾选状态按出现独立记录（occurrence_subtask_records）。
    val occurrenceSubTaskRecordsRepository: OccurrenceSubTaskRecordsRepository =
        OccurrenceSubTaskRecordsRepositoryImpl(database.occurrenceSubTaskRecordDao(), Dispatchers.IO)

    val occurrenceSubTaskUseCases: OccurrenceSubTaskUseCases = OccurrenceSubTaskUseCases(
        tasksRepository = tasksRepository,
        occurrenceRepository = taskOccurrenceRepository,
        recordsRepository = occurrenceSubTaskRecordsRepository,
        subTasksRepository = subTasksRepository,
        now = nowMillis,
    )

    val calendarRepository: CalendarRepository = CalendarRepositoryImpl(database.calendarEntryDao(), Dispatchers.IO)

    // T09/#10 纯新增：日程提醒仓库与用例先于日程用例组装，
    // 供改期重算（onTimesChanged）与回收站删除停止提醒（onTrashed）的联动钩子引用。
    val calendarRemindersRepository: CalendarRemindersRepository =
        CalendarRemindersRepositoryImpl(database.calendarReminderDao(), Dispatchers.IO)

    val calendarReminderUseCases: CalendarReminderUseCases = CalendarReminderUseCases(
        repository = calendarRemindersRepository,
        calendarRepository = calendarRepository,
        scheduler = alarmScheduler,
        idGen = { UUID.randomUUID().toString() },
        now = nowMillis,
    )

    val calendarUseCases: CalendarUseCases = CalendarUseCases(
        repository = calendarRepository,
        idGen = { UUID.randomUUID().toString() },
        now = nowMillis,
        zoneId = zoneId.id,
        // #10：日程改期/时区重解释后相对提醒随开始时刻移动，独立时刻提醒不动
        onTimesChanged = { entryId -> calendarReminderUseCases.rescheduleForStartChange(entryId) },
        // #10：日程删除（回收站）时停止其自身提醒，不影响关联待办自身的提醒
        // #28：日程删除（回收站）时立即停用引用它的记忆并标记来源缺失
        onTrashed = { entryId ->
            calendarReminderUseCases.cancelReminders(entryId)
            memoryUseCases.markSourceMissing(MemoryRefType.SCHEDULE, entryId)
        },
    )

    val notesRepository: NotesRepository = NotesRepositoryImpl(database.noteDao(), Dispatchers.IO)

    val noteUseCases: NoteUseCases = NoteUseCases(
        repository = notesRepository,
        idGen = { UUID.randomUUID().toString() },
        now = nowMillis,
        // #28：便签删除（回收站）时立即停用引用它的记忆并标记来源缺失
        onTrashed = { noteId -> memoryUseCases.markSourceMissing(MemoryRefType.NOTE, noteId) },
    )

    val taskScheduleLinkUseCases: TaskScheduleLinkUseCases = TaskScheduleLinkUseCases(
        tasksRepository = tasksRepository,
        calendarRepository = calendarRepository,
        now = nowMillis,
    )

    // —— T16/#17 回收站：纯新增装配 ——
    // 统一回收站入口：查看、恢复、彻底删除与三十天到期清理（GLOSSARY：回收站）。
    // 恢复策略：父子整体恢复；已解除的日程关联默认不自动重连（恢复结果明确提示）；
    // 提醒不复活，错过的提醒只提示不补响。彻底删除级联清理从属数据。
    // #28：来源恢复/彻底删除的关联记忆联动（恢复不清除停用、彻底删除级联清理记忆）。
    val recycleBinUseCases: RecycleBinUseCases = RecycleBinUseCases(
        tasksRepository = tasksRepository,
        subTasksRepository = subTasksRepository,
        calendarRepository = calendarRepository,
        notesRepository = notesRepository,
        taskOccurrenceRepository = taskOccurrenceRepository,
        reminderUseCases = reminderUseCases,
        calendarReminderUseCases = calendarReminderUseCases,
        taskScheduleLinkUseCases = taskScheduleLinkUseCases,
        now = nowMillis,
        onSourceRestored = { kind, sourceId ->
            when (kind) {
                RecycleBinKind.TASK -> memoryUseCases.clearSourceMissing(MemoryRefType.TASK, sourceId)
                RecycleBinKind.SCHEDULE -> memoryUseCases.clearSourceMissing(MemoryRefType.SCHEDULE, sourceId)
                RecycleBinKind.NOTE -> memoryUseCases.clearSourceMissing(MemoryRefType.NOTE, sourceId)
                RecycleBinKind.SUB_TASK -> Unit // 记忆来源引用不指向子任务
            }
        },
        onSourcePurged = { kind, sourceId ->
            when (kind) {
                RecycleBinKind.TASK -> memoryUseCases.purgeBySource(MemoryRefType.TASK, sourceId)
                RecycleBinKind.SCHEDULE -> memoryUseCases.purgeBySource(MemoryRefType.SCHEDULE, sourceId)
                RecycleBinKind.NOTE -> memoryUseCases.purgeBySource(MemoryRefType.NOTE, sourceId)
                RecycleBinKind.SUB_TASK -> Unit // 记忆来源引用不指向子任务
            }
        },
    )

    // 三十天到期清理的启动懒执行调度（T16/#17）：应用启动时触发一次，
    // 不引入 WorkManager 依赖；测试可注入固定时钟（purgeExpired 接受 cutoff）。
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 应用启动时调用：清理 trashedAt 早于当前时刻减三十天的全部回收站记录。幂等。 */
    fun purgeExpiredOnStartup() {
        cleanupScope.launch {
            runCatching {
                recycleBinUseCases.purgeExpired(nowMillis() - RecycleBinUseCases.RETENTION_MILLIS)
            }
        }
    }

    // —— AI 对话（T17/#18）：纯新增装配，不改动既有依赖 ——
    // API Key 存本机加密存储（EncryptedSharedPreferences，主密钥 AndroidKeystore）；
    // 会话历史存 DataStore（有意避开 Room，见 AiSessionStore 说明）。
    // 惰性构造：AndroidKeyStore 在 Robolectric 单元测试环境中不可用，
    // 且这些组件仅在 AI 界面使用，不应在应用启动时强制初始化。
    val aiConfigStore: AiConfigStore by lazy { EncryptedAiConfigStore(context) }

    val aiChatClient: DeepSeekChatClient by lazy { DeepSeekChatClient() }

    // —— 授权上下文（T18/#19，T28/#29 扩展记忆段）：纯新增装配 ——
    // 会话级授权状态（纯内存）与真实记录快照提供者（只读，不回写业务数据）。
    val aiContextAuthorizer: AiContextAuthorizer by lazy { AiContextAuthorizer() }

    val aiContextProvider: AiContextProvider by lazy {
        RepositoryAiContextProvider(
            tasksRepository = tasksRepository,
            calendarRepository = calendarRepository,
            notesRepository = notesRepository,
            // T28/#29：长期记忆段同样只读授权类别（MEMORY 默认关闭，勾选后才外发）
            memoriesRepository = memoriesRepository,
            zoneId = zoneId,
            now = nowMillis,
        )
    }

    // —— T31/#32 手动加密备份导出：纯新增装配 ——
    // 会话历史 DataStore 实例提升为容器级共享：同一文件只能存在一个单进程 DataStore
    // （AiSessionStore 的 createSingleProcessCoordinator 约束），AI 对话与备份共用同一实例，
    // 行为不变（仍读写同一文件）。
    val aiSessionStore: AiSessionStore by lazy {
        AiSessionStore(File(context.filesDir, "ai_chat_sessions.preferences_pb"))
    }

    val aiChatUseCases: AiChatUseCases by lazy {
        AiChatUseCases(
            configStore = aiConfigStore,
            sessionStore = aiSessionStore,
            client = aiChatClient,
            idGen = { UUID.randomUUID().toString() },
            now = nowMillis,
            contextAuthorizer = aiContextAuthorizer,
            contextProvider = aiContextProvider,
            zoneId = zoneId,
        )
    }

    // —— 操作提案（T19/#20，T20/#21 扩展日程与提醒，T21/#22 扩展修改与删除，T28/#29 扩展记忆）：纯新增装配 ——
    // 提案草稿存 DataStore JSON（有意避开 Room，数据库零改动）；
    // 确认创建走 taskUseCases/noteUseCases/calendarUseCases/calendarReminderUseCases/memoryUseCases
    // 真实入口（不绕过），忽略/幂等状态机在用例层。
    val aiProposalStore: AiProposalStore by lazy {
        AiProposalStore(File(context.filesDir, "ai_proposals.preferences_pb"))
    }

    // —— T21/#22 修改/删除提案的目标定位：纯新增装配 ——
    // 按定位线索从真实仓库只读匹配目标记录（排除回收站）；授权门在 AiProposalUseCases
    // （复用 #19 的 AiContextAuthorizer），未授权对应类别绝不读取任何仓库。
    val aiProposalTargetMatcher: AiProposalTargetMatcher by lazy {
        AiProposalTargetMatcher(
            tasksRepository = tasksRepository,
            notesRepository = notesRepository,
            calendarRepository = calendarRepository,
        )
    }

    val aiProposalUseCases: AiProposalUseCases by lazy {
        AiProposalUseCases(
            chat = aiChatUseCases,
            store = aiProposalStore,
            taskUseCases = taskUseCases,
            noteUseCases = noteUseCases,
            // T20/#21 纯新增：日程提案确认后经日程与日程提醒真实入口创建
            calendarUseCases = calendarUseCases,
            calendarReminderUseCases = calendarReminderUseCases,
            // T21/#22 纯新增：修改/删除提案的目标定位
            targetMatcher = aiProposalTargetMatcher,
            // T28/#29 纯新增：记忆提案确认后经长期记忆真实入口保存（来源聊天确认）
            memoryUseCases = memoryUseCases,
            idGen = { UUID.randomUUID().toString() },
            now = nowMillis,
            zoneId = zoneId,
        )
    }

    // —— T31/#32 手动加密备份导出：纯新增装配 ——
    // 备份走 BackupUseCases 统一入口：Room 全部用户数据表 + 会话历史（共享 aiSessionStore），
    // 绝不触碰 aiConfigStore（API Key 安全边界，见 BackupUseCases 说明）。
    // MVP 导出位置为应用外部文件目录（展示绝对路径）；SAF「另存为」由后续任务补齐。
    val backupExportDirectory: File by lazy {
        (context.getExternalFilesDir(null) ?: File(context.filesDir, "backups"))
            .apply { if (!isDirectory) mkdirs() }
    }

    val backupUseCases: BackupUseCases by lazy {
        BackupUseCases(
            database = database,
            sessionHistory = { aiSessionStore.load() },
            ioDispatcher = ioDispatcher,
            now = nowMillis,
        )
    }
}
