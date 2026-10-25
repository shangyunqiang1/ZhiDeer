package com.zhideer.app.platform

/**
 * 语音识别能力封装（T23/#24）：把系统语音能力与状态机解耦。
 *
 * - 生产实现 [AndroidSpeechInput] 使用系统 SpeechRecognizer（不要求额外语音 Key、不保证设备预装或完全离线）；
 * - 测试使用替身注入，验证录音/识别/失败状态机与退出停止，而不依赖真实麦克风；
 * - 接口不暴露任何录音落盘能力：原始音频只交给系统识别服务，知时鹿不持久保存。
 */
interface SpeechInput {

    /** 本机是否存在可用的语音识别服务（[start] 之前用于给出中文说明与键盘回退）。 */
    fun isAvailable(): Boolean

    /** 开始一次识别会话；结果与错误经 [listener] 回调。 */
    fun start(listener: Listener)

    /** 结束录音并请求识别结果（松手转写）。 */
    fun stop()

    /** 放弃本次识别，不期待任何结果（取消/退出页面）。 */
    fun cancel()

    /** 释放底层识别器，退出页面后不常驻监听。 */
    fun release()

    /** 识别会话回调；[onError] 的消息必须是可直接展示的中文。 */
    interface Listener {
        fun onPartialResult(text: String)
        fun onResult(text: String)
        fun onError(message: String)
    }
}
