package com.zhideer.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 轻量设置页（T34/#35 二级入口）：版本信息、AI 配置指引、备份与提醒入口说明。 */
@Composable
fun SettingsScreen(appVersion: String, onOpenClockHandoff: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("设置", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp))
        SettingsCard("AI 配置", "API Key、模型与接口地址在「小鹿」页的设置入口中配置；Key 加密存储、掩码显示、不进入日志。")
        SettingsCard("提醒", "待办与日程的提醒在各自详情页设置；精确提醒能力不足时会标记未就绪并提供修复入口。")
        androidx.compose.material3.OutlinedButton(onClick = onOpenClockHandoff, modifier = Modifier.padding(vertical = 4.dp)) {
            Text("系统闹钟交接")
        }
        SettingsCard("备份与恢复", "在首页「备份」入口中导出加密备份或整库恢复；备份文件不含 API Key。")
        SettingsCard("数据与隐私", "本地数据存储于设备；记忆删除为彻底删除、不进入回收站；AI 上下文授权为会话级，可随时撤销。")
        SettingsCard("版本", appVersion)
    }
}

@Composable
private fun SettingsCard(title: String, body: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
