package com.zhideer.app.di

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.zhideer.app.data.db.ZhiDeerDatabase
import com.zhideer.app.data.repository.TasksRepositoryImpl
import com.zhideer.app.domain.repository.TasksRepository
import com.zhideer.app.domain.usecase.TaskUseCases
import kotlinx.coroutines.Dispatchers
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
        .build()

    private val ioDispatcher: CoroutineContext = Dispatchers.IO

    val tasksRepository: TasksRepository = TasksRepositoryImpl(database.taskDao(), Dispatchers.IO)

    val taskUseCases: TaskUseCases = TaskUseCases(
        repository = tasksRepository,
        idGen = { UUID.randomUUID().toString() },
        now = nowMillis,
        zoneId = zoneId.id,
    )
}
