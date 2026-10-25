package com.zhideer.app.platform

/**
 * 提醒通知投递器（T08/#9）：提醒到达时由接收器调用，发出本地通知。
 * 生产实现负责通知渠道（提示音/振动由渠道配置承载）；测试用替身记录调用。
 */
interface NotificationPoster {

    /** 确保提醒通知渠道存在（幂等）。 */
    fun ensureChannel()

    /** 投递提醒通知；权限被拒等失败时返回 false，调用方不得据此改动待办记录。 */
    fun post(reminderId: String, taskId: String, title: String, text: String): Boolean
}
