package com.zhideer.app.domain.usecase

import com.zhideer.app.platform.SpeechInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 语音输入状态（T23/#24，用户故事 65–67、69）：
 * 空闲 → 录音中 → 识别中 → 已转写 / 失败。
 *
 * - 录音中与识别中都必须对用户可见，不隐蔽录音；
 * - [Transcribed] 只是「已填入输入框、可编辑」的草稿状态，不代表已提交；
 * - [Failed] 一律是中文可诊断提示，键盘输入始终可用。
 */
sealed interface VoiceInputState {

    /** 空闲：没有进行中的识别会话。 */
    data object Idle : VoiceInputState

    /** 录音中；[partialText] 是识别服务的实时部分结果，可为空。 */
    data class Recording(val partialText: String = "") : VoiceInputState

    /** 已松手、等待识别结果。 */
    data object Recognizing : VoiceInputState

    /** 已转写：文本已给出，等待用户编辑并点击发送。 */
    data class Transcribed(val text: String) : VoiceInputState

    /** 失败：统一的中文提示（无识别器/权限拒绝/服务失败等），键盘输入仍可用。 */
    data class Failed(val message: String) : VoiceInputState
}

/**
 * 语音输入状态机（T23/#24）：UI 只渲染 [state] 与转发事件，不自行判断状态。
 *
 * 关键约束（对应验收）：
 * - 系统识别优先：[startRecording] 先检查 [SpeechInput.isAvailable]，无服务时直接中文说明并回退键盘；
 * - 松手才转写：[releaseAndTranscribe] 只把状态推进到 [VoiceInputState.Recognizing]，
 *   识别结果到达才进入 [VoiceInputState.Transcribed]；提交由既有聊天发送路径完成，
 *   本控制器绝不直接发送或写入业务数据（保证与文字共用同一提案确认）；
 * - 退出页面/取消立即 [SpeechInput.cancel] + [SpeechInput.release]，不常驻监听；
 * - 用会话令牌丢弃迟到回调，退出后重新录音不会被旧会话结果污染；
 * - 平台接口不暴露录音落盘能力：原始音频只交给系统识别服务，知时鹿不持久保存。
 */
class VoiceInputController(
    private val speechInput: SpeechInput,
) {

    private val _state = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    val state: StateFlow<VoiceInputState> = _state.asStateFlow()

    /** 识别会话令牌：每次开始/取消/退出递增，旧会话的迟到回调一律丢弃。 */
    private var session = 0

    /** 本机是否有系统语音识别服务；无服务时 UI 直接展示中文说明并回退键盘。 */
    fun isRecognitionAvailable(): Boolean =
        try {
            speechInput.isAvailable()
        } catch (t: Throwable) {
            false
        }

    /** 按住说话：开始录音。返回是否真的开始（无服务/启动失败返回 false 并给出中文提示）。 */
    fun startRecording(): Boolean {
        val current = _state.value
        if (current is VoiceInputState.Recording || current is VoiceInputState.Recognizing) return false
        if (!isRecognitionAvailable()) {
            _state.value = VoiceInputState.Failed(MESSAGE_NO_RECOGNIZER)
            return false
        }
        val token = ++session
        _state.value = VoiceInputState.Recording()
        return try {
            speechInput.start(listenerFor(token))
            true
        } catch (t: Throwable) {
            session++
            _state.value = VoiceInputState.Failed(MESSAGE_START_FAILED)
            false
        }
    }

    /** 松手转写：结束录音并等待识别结果。 */
    fun releaseAndTranscribe() {
        if (_state.value !is VoiceInputState.Recording) return
        _state.value = VoiceInputState.Recognizing
        try {
            speechInput.stop()
        } catch (t: Throwable) {
            session++
            _state.value = VoiceInputState.Failed(MESSAGE_STOP_FAILED)
        }
    }

    /** 取消本次录音/识别，不产生转写（手势取消或用户主动放弃）。 */
    fun cancel() {
        val current = _state.value
        if (current is VoiceInputState.Recording || current is VoiceInputState.Recognizing) {
            session++
            _state.value = VoiceInputState.Idle
            runCatching { speechInput.cancel() }
        }
    }

    /** 麦克风权限被拒绝：中文提示并保留键盘输入。 */
    fun reportPermissionDenied() {
        session++
        runCatching { speechInput.cancel() }
        _state.value = VoiceInputState.Failed(MESSAGE_PERMISSION_DENIED)
    }

    /** 用户已点击发送：转写草稿状态复位（提交动作由既有聊天发送路径完成）。 */
    fun onDraftSent() {
        if (_state.value is VoiceInputState.Transcribed) {
            _state.value = VoiceInputState.Idle
        }
    }

    /** 离开页面：停止录音/识别并释放识别器，绝不常驻监听。 */
    fun onPageExit() {
        session++
        runCatching { speechInput.cancel() }
        runCatching { speechInput.release() }
        _state.value = VoiceInputState.Idle
    }

    private fun listenerFor(token: Int): SpeechInput.Listener = object : SpeechInput.Listener {

        private fun isActiveSession(): Boolean = token == session

        private fun isAwaitingResult(): Boolean {
            val current = _state.value
            return current is VoiceInputState.Recording || current is VoiceInputState.Recognizing
        }

        override fun onPartialResult(text: String) {
            if (!isActiveSession()) return
            val current = _state.value
            if (current is VoiceInputState.Recording) {
                _state.value = VoiceInputState.Recording(text)
            }
        }

        override fun onResult(text: String) {
            if (!isActiveSession() || !isAwaitingResult()) return
            val trimmed = text.trim()
            _state.value = if (trimmed.isEmpty()) {
                VoiceInputState.Failed(MESSAGE_NO_RESULT)
            } else {
                VoiceInputState.Transcribed(trimmed)
            }
        }

        override fun onError(message: String) {
            if (!isActiveSession() || !isAwaitingResult()) return
            _state.value = VoiceInputState.Failed(message.ifBlank { MESSAGE_START_FAILED })
        }
    }

    companion object {
        const val MESSAGE_NO_RECOGNIZER = "本机没有可用的语音识别服务，已回退键盘输入。"
        const val MESSAGE_PERMISSION_DENIED =
            "没有麦克风权限，无法录音。可继续用键盘输入，或在系统设置中允许麦克风权限后重试。"
        const val MESSAGE_NO_RESULT = "没有识别到语音内容，请再说一次或改用键盘输入。"
        const val MESSAGE_START_FAILED = "无法启动语音识别，请重试或改用键盘输入。"
        const val MESSAGE_STOP_FAILED = "停止录音时出错，请重试或改用键盘输入。"
    }
}
