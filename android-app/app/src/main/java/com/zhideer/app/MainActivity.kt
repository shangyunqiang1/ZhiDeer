package com.zhideer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.zhideer.app.ui.TasksScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ZhiDeerApplication).container
        setContent {
            MaterialTheme {
                Surface {
                    TasksScreen(useCases = container.taskUseCases, zoneId = container.zoneId)
                }
            }
        }
    }
}
