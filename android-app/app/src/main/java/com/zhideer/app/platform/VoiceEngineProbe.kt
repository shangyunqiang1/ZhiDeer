package com.zhideer.app.platform

import android.content.Context
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.zhideer.app.domain.model.SpeechEngineAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 语音引擎能力探测（T35/#36）：供诊断页展示语音识别与中文播报的真实状态。
 *
 * - 只探测能力，不录音、不播报、不保存任何音频；
 * - 播报引擎按需初始化后立即释放，不常驻占用系统引擎；
 * - 探测失败/超时如实返回 [SpeechEngineAvailability.UNKNOWN]，绝不假装可用。
 */
interface VoiceEngineProbe {

    /** 系统是否具备可用的语音识别服务（与 #24 的按住说话使用同一系统能力）。 */
    fun recognitionAvailable(): Boolean

    /** 初始化系统 TTS 并检查中文支持，结束后立即释放。 */
    suspend fun textToSpeechStatus(): SpeechEngineAvailability
}

/** 生产实现：与 #24 相同的系统 SpeechRecognizer / TextToSpeech 能力，仅只读探测。 */
class AndroidVoiceEngineProbe(private val context: Context) : VoiceEngineProbe {

    override fun recognitionAvailable(): Boolean = runCatching {
        SpeechRecognizer.isRecognitionAvailable(context)
    }.getOrDefault(false)

    override suspend fun textToSpeechStatus(): SpeechEngineAvailability =
        withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { continuation ->
                    var engine: TextToSpeech? = null
                    try {
                        engine = TextToSpeech(context.applicationContext) { status ->
                            val created = engine
                            val availability = when {
                                status != TextToSpeech.SUCCESS || created == null ->
                                    SpeechEngineAvailability.ENGINE_MISSING

                                else -> {
                                    val language = runCatching {
                                        created.setLanguage(Locale.SIMPLIFIED_CHINESE)
                                    }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                                    if (language == TextToSpeech.LANG_MISSING_DATA ||
                                        language == TextToSpeech.LANG_NOT_SUPPORTED
                                    ) {
                                        SpeechEngineAvailability.CHINESE_UNAVAILABLE
                                    } else {
                                        SpeechEngineAvailability.AVAILABLE
                                    }
                                }
                            }
                            runCatching { created?.shutdown() }
                            if (continuation.isActive) continuation.resume(availability)
                        }
                    } catch (t: Throwable) {
                        runCatching { engine?.shutdown() }
                        if (continuation.isActive) continuation.resume(SpeechEngineAvailability.ENGINE_MISSING)
                    }
                    continuation.invokeOnCancellation { runCatching { engine?.shutdown() } }
                }
            }
        } ?: SpeechEngineAvailability.UNKNOWN

    private companion object {
        const val PROBE_TIMEOUT_MS = 3_000L
    }
}
