package com.zhideer.app

import com.zhideer.app.domain.usecase.VoicePlaybackController
import com.zhideer.app.domain.usecase.VoicePlaybackState
import com.zhideer.app.platform.SpeechOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T23/#24 中文播报验收测试（用户故事 68）。
 *
 * 用注入的 [SpeechOutput] 替身覆盖可测状态机：
 * - 播报默认关闭，关闭时不初始化引擎、不播报任何回复；
 * - 用户可开启并播报中文回答，可随时停止；
 * - 引擎缺失/中文不可用/播报失败都给出中文提示，文字回复保留（控制器不清空任何文本）；
 * - 离开页面停止播报并释放引擎，再次进入按用户开关重新初始化（不常驻占用语音引擎）。
 */
class VoicePlaybackControllerTest {

    private class FakeSpeechOutput : SpeechOutput {
        var listener: SpeechOutput.Listener? = null
        var initializeCalls = 0
        var stopCalls = 0
        var releaseCalls = 0
        val spoken = mutableListOf<String>()

        override fun initialize(listener: SpeechOutput.Listener) {
            initializeCalls++
            this.listener = listener
        }
        override fun speak(text: String) {
            spoken += text
        }
        override fun stop() {
            stopCalls++
        }
        override fun release() {
            releaseCalls++
        }
        fun ready(chineseAvailable: Boolean = true) = listener?.onReady(chineseAvailable)
        fun unavailable(message: String) = listener?.onUnavailable(message)
        fun done() = listener?.onDone()
        fun start() = listener?.onStart()
        fun error(message: String) = listener?.onError(message)
    }

    @Test
    fun 播报默认关闭且不初始化引擎不播报回复() {
        val output = FakeSpeechOutput()
        val controller = VoicePlaybackController(output)

        assertFalse(controller.enabled.value)
        assertEquals(VoicePlaybackState.Disabled, controller.state.value)

        controller.speakReply("这是 AI 的中文回答")

        assertEquals(0, output.initializeCalls)
        assertTrue(output.spoken.isEmpty())
    }

    @Test
    fun 开启后初始化引擎并在就绪后播报中文回答() {
        val output = FakeSpeechOutput()
        val controller = VoicePlaybackController(output)

        controller.setEnabled(true)
        assertTrue(controller.enabled.value)
        assertEquals(VoicePlaybackState.Initializing, controller.state.value)
        assertEquals(1, output.initializeCalls)

        output.ready(chineseAvailable = true)
        assertEquals(VoicePlaybackState.Ready, controller.state.value)

        controller.speakReply("明天下午三点有一个会议。")
        assertEquals(listOf("明天下午三点有一个会议。"), output.spoken)
        assertEquals(VoicePlaybackState.Speaking, controller.state.value)

        output.done()
        assertEquals(VoicePlaybackState.Ready, controller.state.value)
    }

    @Test
    fun 播报中可随时停止且停止后不重复播报() {
        val output = FakeSpeechOutput()
        val controller = VoicePlaybackController(output)
        controller.setEnabled(true)
        output.ready(true)
        controller.speakReply("较长的一段中文回答")

        controller.stop()

        assertTrue(output.stopCalls >= 1)
        assertEquals(VoicePlaybackState.Ready, controller.state.value)
        output.done()
        assertEquals(VoicePlaybackState.Ready, controller.state.value)
        assertEquals(listOf("较长的一段中文回答"), output.spoken)
    }

    @Test
    fun 关闭开关立即停止并释放引擎且后续回复不播报() {
        val output = FakeSpeechOutput()
        val controller = VoicePlaybackController(output)
        controller.setEnabled(true)
        output.ready(true)
        controller.speakReply("第一段回答")

        controller.setEnabled(false)

        assertFalse(controller.enabled.value)
        assertTrue(output.stopCalls >= 1)
        assertTrue(output.releaseCalls >= 1)
        assertEquals(VoicePlaybackState.Disabled, controller.state.value)

        controller.speakReply("第二段回答")
        assertEquals(listOf("第一段回答"), output.spoken)
    }

    @Test
    fun 引擎缺失时给出中文提示且文字回复保留() {
        val output = FakeSpeechOutput()
        val controller = VoicePlaybackController(output)
        controller.setEnabled(true)

        output.unavailable("未找到可用的中文语音引擎，文字回复仍保留在对话中。")

        val state = controller.state.value
        assertTrue(state is VoicePlaybackState.Unavailable)
        val message = (state as VoicePlaybackState.Unavailable).message
        assertTrue("提示应为中文且说明引擎缺失：$message", message.contains("语音引擎"))
        assertTrue("提示应说明文字回复保留：$message", message.contains("文字回复"))

        controller.speakReply("仍然显示在对话里的中文回答")
        assertTrue("引擎缺失时不得尝试播报", output.spoken.isEmpty())
    }

    @Test
    fun 语音引擎不支持中文时给出中文提示() {
        val output = FakeSpeechOutput()
        val controller = VoicePlaybackController(output)
        controller.setEnabled(true)

        output.ready(chineseAvailable = false)

        val state = controller.state.value
        assertTrue(state is VoicePlaybackState.Unavailable)
        val message = (state as VoicePlaybackState.Unavailable).message
        assertTrue("提示应说明中文不可用：$message", message.contains("中文"))
        assertTrue("提示应说明文字回复保留：$message", message.contains("文字回复"))
    }

    @Test
    fun 播报失败时给出中文提示且重新开关后可恢复() {
        val output = FakeSpeechOutput()
        val controller = VoicePlaybackController(output)
        controller.setEnabled(true)
        output.ready(true)
        controller.speakReply("会被打断的回答")

        output.error("中文播报失败，文字回复仍保留在对话中。")

        val state = controller.state.value
        assertTrue(state is VoicePlaybackState.Unavailable)
        assertTrue((state as VoicePlaybackState.Unavailable).message.contains("文字回复"))

        controller.setEnabled(false)
        controller.setEnabled(true)
        output.ready(true)
        assertEquals(VoicePlaybackState.Ready, controller.state.value)
        controller.speakReply("恢复后的回答")
        assertEquals(listOf("会被打断的回答", "恢复后的回答"), output.spoken)
    }

    @Test
    fun 空白回复不触发播报() {
        val output = FakeSpeechOutput()
        val controller = VoicePlaybackController(output)
        controller.setEnabled(true)
        output.ready(true)

        controller.speakReply("   ")

        assertTrue(output.spoken.isEmpty())
        assertEquals(VoicePlaybackState.Ready, controller.state.value)
    }

    @Test
    fun 退出页面停止播报并释放引擎再次进入按开关重新初始化() {
        val output = FakeSpeechOutput()
        val controller = VoicePlaybackController(output)
        controller.setEnabled(true)
        output.ready(true)
        controller.speakReply("播报中的回答")

        controller.onPageExit()

        assertTrue(output.stopCalls >= 1)
        assertEquals(1, output.releaseCalls)
        assertEquals(VoicePlaybackState.Disabled, controller.state.value)
        assertTrue("用户开关选择保留", controller.enabled.value)

        controller.onPageEntered()
        assertEquals("重新进入应按开关重新初始化", 2, output.initializeCalls)
        assertEquals(VoicePlaybackState.Initializing, controller.state.value)
        output.ready(true)
        assertEquals(VoicePlaybackState.Ready, controller.state.value)
    }

    @Test
    fun 播报关闭时退出页面不初始化引擎() {
        val output = FakeSpeechOutput()
        val controller = VoicePlaybackController(output)

        controller.onPageEntered()
        controller.onPageExit()

        assertEquals(0, output.initializeCalls)
        assertEquals(VoicePlaybackState.Disabled, controller.state.value)
    }

    @Test
    fun 就绪前收到的回复不会抢跑播报() {
        val output = FakeSpeechOutput()
        val controller = VoicePlaybackController(output)
        controller.setEnabled(true)

        controller.speakReply("初始化完成前生成的回答")
        assertTrue(output.spoken.isEmpty())

        output.ready(true)
        controller.speakReply("初始化完成后的回答")
        assertEquals(listOf("初始化完成后的回答"), output.spoken)
    }
}
