package com.zhideer.app.data.db

import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(entities = [TaskEntity::class], version = 1, exportSchema = true)
abstract class ZhiDeerDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    companion object {
        const val NAME = "zhideer.db"
    }
}
