package com.zhideer.app.platform

/**
 * 中文播报能力封装（T23/#24）：把系统 TTS 与播报开关状态机解耦。
 *
 * - 生产实现 [AndroidSpeechOutput] 使用系统 TextToSpeech 中文引擎；
 * - 测试使用替身注入，验证默认关闭、可开关、可随时停止、缺引擎/中文不可用时的中文提示；
 * - 播报只读取文字回复，不持久保存任何音频。
 */
interface SpeechOutput {

    /** 初始化语音引擎；结果经 [listener] 回调（成功、引擎缺失、中文不可用）。 */
    fun initialize(listener: Listener)

    /** 播报一段中文文本；失败经 [listener.onError] 中文提示。 */
    fun speak(text: String)

    /** 立即停止当前播报。 */
    fun stop()

    /** 释放语音引擎；退出页面后不占用引擎。 */
    fun release()

    /** 语音引擎回调。 */
    interface Listener {
        /** 引擎初始化完成；[chineseAvailable] 表示当前引擎是否支持中文。 */
        fun onReady(chineseAvailable: Boolean)

        /** 引擎缺失或其他初始化失败；[message] 为中文可展示文案。 */
        fun onUnavailable(message: String)

        fun onStart()

        fun onDone()

        /** 播报失败；[message] 为中文可展示文案，文字回复仍保留。 */
        fun onError(message: String)
    }
}
