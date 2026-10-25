package com.zhideer.app.platform

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * 生产语音识别适配器（T23/#24）：系统 SpeechRecognizer，中文优先。
 *
 * - 系统识别优先：不要求额外语音 Key，也不保证设备预装识别服务或完全离线；
 * - 无识别服务时 [isAvailable] 返回 false，[start] 也会直接回中文错误（键盘回退由状态机处理）；
 * - 原始音频只交给系统识别服务：本类不接收、不读取、不保存音频缓冲；
 * - 所有 SpeechRecognizer 调用切到主线程，[release] 销毁识别器，退出页面后不常驻监听。
 */
class AndroidSpeechInput(private val context: Context) : SpeechInput {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listener: SpeechInput.Listener? = null

    override fun isAvailable(): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(context)
    } catch (t: Throwable) {
        false
    }

    override fun start(listener: SpeechInput.Listener) {
        mainHandler.post {
            this.listener = listener
            if (!isAvailable()) {
                listener.onError("本机没有可用的语音识别服务，已回退键盘输入。")
                return@post
            }
            try {
                val engine = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
                    it.setRecognitionListener(recognitionListener)
                    recognizer = it
                }
                engine.startListening(recognizerIntent())
            } catch (t: Throwable) {
                listener.onError("无法启动语音识别，请重试或改用键盘输入。")
            }
        }
    }

    override fun stop() {
        mainHandler.post {
            runCatching { recognizer?.stopListening() }
        }
    }

    override fun cancel() {
        mainHandler.post {
            runCatching { recognizer?.cancel() }
        }
    }

    override fun release() {
        mainHandler.post {
            listener = null
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
    }

    private fun recognizerIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        // 自由口语优先，中文语言提示；系统识别服务可自行决定是否联网或离线。
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.SIMPLIFIED_CHINESE.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private fun dispatchResults(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit

        // 系统回传的音频缓冲只用于识别，本应用不读取、不落盘（不持久保存原始录音）。
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            listener?.onError(
                when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "录音出错，请重试或改用键盘输入。"
                    SpeechRecognizer.ERROR_CLIENT -> "语音识别已中断，请重试或改用键盘输入。"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "没有麦克风权限，无法录音。可继续用键盘输入，或在系统设置中允许麦克风权限后重试。"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "语音识别服务网络异常，请重试或改用键盘输入。"
                    SpeechRecognizer.ERROR_NO_MATCH -> "没有听清，请再说一次或改用键盘输入。"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别服务正忙，请稍后重试或改用键盘输入。"
                    SpeechRecognizer.ERROR_SERVER -> "语音识别服务暂时不可用，请重试或改用键盘输入。"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到语音，请再说一次或改用键盘输入。"
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "语音识别服务不支持当前语言，请改用键盘输入。"
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "语音识别服务的中文语言包不可用，请改用键盘输入。"
                    else -> "语音识别失败（错误码 $error），请重试或改用键盘输入。"
                },
            )
        }

        override fun onResults(results: Bundle?) {
            listener?.onResult(dispatchResults(results))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = dispatchResults(partialResults)
            if (text.isNotBlank()) {
                listener?.onPartialResult(text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
