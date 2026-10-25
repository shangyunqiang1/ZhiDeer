package com.zhideer.app.platform

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 生产中文播报适配器（T23/#24）：系统 TextToSpeech + 中文语言检查。
 *
 * - 引擎缺失或中文语言不可用时回调中文提示，但绝不影响文字回复（文字仍显示在会话中）；
 * - 播报只读取文字，不保存任何音频；
 * - 所有 TTS 调用切到主线程；[release] 停止并关闭引擎，不常驻占用。
 */
class AndroidSpeechOutput(private val context: Context) : SpeechOutput {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var listener: SpeechOutput.Listener? = null
    private var chineseAvailable = false

    override fun initialize(listener: SpeechOutput.Listener) {
        this.listener = listener
        mainHandler.post {
            if (tts != null) {
                listener.onReady(chineseAvailable)
                return@post
            }
            try {
                var engineRef: TextToSpeech? = null
                val engine = TextToSpeech(context.applicationContext) { status ->
                    val created = engineRef
                    if (status != TextToSpeech.SUCCESS || created == null) {
                        listener.onUnavailable("未找到可用的中文语音引擎，文字回复仍保留在对话中。")
                    } else {
                        val languageResult = created.setLanguage(Locale.SIMPLIFIED_CHINESE)
                        chineseAvailable = languageResult != TextToSpeech.LANG_MISSING_DATA &&
                            languageResult != TextToSpeech.LANG_NOT_SUPPORTED
                        if (!chineseAvailable) {
                            listener.onUnavailable("当前语音引擎不支持中文播报，文字回复仍保留在对话中。")
                        } else {
                            created.setOnUtteranceProgressListener(progressListener)
                            listener.onReady(true)
                        }
                    }
                }
                engineRef = engine
                tts = engine
            } catch (t: Throwable) {
                listener.onUnavailable("未找到可用的中文语音引擎，文字回复仍保留在对话中。")
            }
        }
    }

    override fun speak(text: String) {
        mainHandler.post {
            val engine = tts
            if (engine == null || !chineseAvailable) {
                listener?.onUnavailable("当前语音引擎不支持中文播报，文字回复仍保留在对话中。")
                return@post
            }
            val result = try {
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            } catch (t: Throwable) {
                TextToSpeech.ERROR
            }
            if (result != TextToSpeech.SUCCESS) {
                listener?.onError("中文播报失败，文字回复仍保留在对话中。")
            }
        }
    }

    override fun stop() {
        mainHandler.post {
            runCatching { tts?.stop() }
        }
    }

    override fun release() {
        mainHandler.post {
            listener = null
            runCatching {
                tts?.stop()
                tts?.shutdown()
            }
            tts = null
            chineseAvailable = false
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            listener?.onStart()
        }

        override fun onDone(utteranceId: String?) {
            listener?.onDone()
        }

        @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, -1)"))
        override fun onError(utteranceId: String?) {
            listener?.onError("中文播报失败，文字回复仍保留在对话中。")
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            listener?.onError("中文播报失败，文字回复仍保留在对话中。")
        }
    }

    private companion object {
        const val UTTERANCE_ID = "zhideer-voice-reply"
    }
}
