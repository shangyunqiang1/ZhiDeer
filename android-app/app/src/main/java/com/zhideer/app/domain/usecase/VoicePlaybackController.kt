package com.zhideer.app.domain.usecase

import com.zhideer.app.platform.SpeechOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 中文播报状态（T23/#24，用户故事 68）：
 * 默认 [Disabled]；开启后初始化引擎 → [Ready]；播报中 → [Speaking]；
 * 引擎缺失或中文不可用 → [Unavailable]（文字回复保留，绝不清空对话）。
 */
sealed interface VoicePlaybackState {

    /** 播报关闭（默认）或已释放。 */
    data object Disabled : VoicePlaybackState

    /** 正在初始化语音引擎。 */
    data object Initializing : VoicePlaybackState

    /** 引擎就绪，可播报。 */
    data object Ready : VoicePlaybackState

    /** 正在播报。 */
    data object Speaking : VoicePlaybackState

    /** 不可用：引擎缺失/中文不支持/播报失败；[message] 为中文提示，文字回复仍保留。 */
    data class Unavailable(val message: String) : VoicePlaybackState
}

/**
 * 中文播报开关与暂停控制（T23/#24）：UI 只渲染状态并转发事件。
 *
 * 关键约束（对应验收）：
 * - [enabled] 默认 false：不开关就绝不初始化引擎、绝不播报；
 * - 只有开关开启且引擎就绪时 [speakReply] 才播报，否则保持文字、不抢跑；
 * - [stop] 可随时停止；[setEnabled] 关闭时立即停止并释放引擎；
 * - 引擎缺失/中文不可用/播报失败以 [VoicePlaybackState.Unavailable] 中文提示，
 *   控制器不持有、不清空回答文字（文字仍在会话中）；
 * - [onPageExit] 停止并释放引擎，不常驻占用语音能力。
 */
class VoicePlaybackController(
    private val speechOutput: SpeechOutput,
) {

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _state = MutableStateFlow<VoicePlaybackState>(VoicePlaybackState.Disabled)
    val state: StateFlow<VoicePlaybackState> = _state.asStateFlow()

    /** 用户开关播报；默认关闭。 */
    fun setEnabled(enabled: Boolean) {
        if (_enabled.value == enabled) return
        _enabled.value = enabled
        if (enabled) {
            initializeEngine()
        } else {
            runCatching { speechOutput.stop() }
            runCatching { speechOutput.release() }
            _state.value = VoicePlaybackState.Disabled
        }
    }

    /** 收到一条新的中文回答：仅在开关开启且引擎就绪时播报，否则保持文字。 */
    fun speakReply(text: String) {
        if (!_enabled.value) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val current = _state.value
        if (current != VoicePlaybackState.Ready && current != VoicePlaybackState.Speaking) return
        if (current == VoicePlaybackState.Speaking) {
            runCatching { speechOutput.stop() }
        }
        _state.value = VoicePlaybackState.Speaking
        try {
            speechOutput.speak(trimmed)
        } catch (t: Throwable) {
            _state.value = VoicePlaybackState.Unavailable(MESSAGE_SPEAK_FAILED)
        }
    }

    /** 随时停止当前播报。 */
    fun stop() {
        if (_state.value != VoicePlaybackState.Speaking) return
        runCatching { speechOutput.stop() }
        _state.value = if (_enabled.value) VoicePlaybackState.Ready else VoicePlaybackState.Disabled
    }

    /** 进入聊天页：按用户选择（默认关闭）按需初始化引擎。 */
    fun onPageEntered() {
        if (!_enabled.value) return
        if (_state.value == VoicePlaybackState.Disabled) {
            initializeEngine()
        }
    }

    /** 离开聊天页：停止播报并释放引擎。 */
    fun onPageExit() {
        runCatching { speechOutput.stop() }
        runCatching { speechOutput.release() }
        _state.value = VoicePlaybackState.Disabled
    }

    private fun initializeEngine() {
        _state.value = VoicePlaybackState.Initializing
        try {
            speechOutput.initialize(listener)
        } catch (t: Throwable) {
            _state.value = VoicePlaybackState.Unavailable(MESSAGE_ENGINE_UNAVAILABLE)
        }
    }

    private val listener: SpeechOutput.Listener = object : SpeechOutput.Listener {

        override fun onReady(chineseAvailable: Boolean) {
            if (!_enabled.value) return
            _state.value = if (chineseAvailable) {
                VoicePlaybackState.Ready
            } else {
                VoicePlaybackState.Unavailable(MESSAGE_CHINESE_UNAVAILABLE)
            }
        }

        override fun onUnavailable(message: String) {
            if (!_enabled.value) return
            _state.value = VoicePlaybackState.Unavailable(message.ifBlank { MESSAGE_ENGINE_UNAVAILABLE })
        }

        override fun onStart() {
            if (!_enabled.value) return
            _state.value = VoicePlaybackState.Speaking
        }

        override fun onDone() {
            if (!_enabled.value) return
            if (_state.value == VoicePlaybackState.Speaking) {
                _state.value = VoicePlaybackState.Ready
            }
        }

        override fun onError(message: String) {
            if (!_enabled.value) return
            _state.value = VoicePlaybackState.Unavailable(message.ifBlank { MESSAGE_SPEAK_FAILED })
        }
    }

    companion object {
        const val MESSAGE_ENGINE_UNAVAILABLE = "未找到可用的中文语音引擎，文字回复仍保留在对话中。"
        const val MESSAGE_CHINESE_UNAVAILABLE = "当前语音引擎不支持中文播报，文字回复仍保留在对话中。"
        const val MESSAGE_SPEAK_FAILED = "中文播报失败，文字回复仍保留在对话中。"
    }
}
