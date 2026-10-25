package com.zhideer.app.platform

/**
 * 系统精确闹钟适配器（T08/#9）：提醒用例通过本接口提交/取消系统闹钟调度。
 * 提交调度请求不等同于实际触发成功；真实投递留待统一实机验收。
 * 生产实现基于 AlarmManager.setExactAndAllowWhileIdle；测试用替身记录调用。
 */
interface AlarmScheduler {

    /** 是否可设置精确闹钟（API < 31 恒为可；API 31+ 取决于系统授权）。 */
    fun canScheduleExact(): Boolean

    /** 设置精确闹钟；返回调度请求是否提交成功。suspend：生产装饰器需同步写“已调度”台账。 */
    suspend fun scheduleExact(reminderId: String, triggerAtMillis: Long, taskId: String, title: String): Boolean

    /** 显式确认降级后的非精确调度（可能延迟触发）。 */
    suspend fun scheduleInexact(reminderId: String, triggerAtMillis: Long, taskId: String, title: String): Boolean

    /** 取消提醒的闹钟；未设置过时为无操作。 */
    suspend fun cancel(reminderId: String)
}
