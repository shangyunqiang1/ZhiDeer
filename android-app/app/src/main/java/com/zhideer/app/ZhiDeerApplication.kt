package com.zhideer.app

import android.app.Application
import com.zhideer.app.di.AppContainer

class ZhiDeerApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // T16/#17：三十天到期清理的应用启动懒执行（幂等；清理逻辑与测试见 RecycleBinUseCases）
        container.purgeExpiredOnStartup()
    }
}
