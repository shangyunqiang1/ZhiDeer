package com.zhideer.app

import android.app.Application
import com.zhideer.app.di.AppContainer

class ZhiDeerApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
