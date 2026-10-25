package com.zhideer.app

import com.zhideer.app.domain.usecase.VoiceInputController
import com.zhideer.app.domain.usecase.VoiceInputState
import com.zhideer.app.platform.SpeechInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T23/#24 语音输入验收测试（用户故事 65–67、69；语音提交与文字共用同一条发送/提案路径，见 AiScreen）。
 *
 * 用注入的 [SpeechInput] 替身覆盖可测状态机：
 * - 无识别服务、麦克风拒绝、识别服务失败与空结果都给中文提示并保留键盘回退；
 * - 按住说话 → 松手识别 → 已转写，转写只是草稿，点击发送后才复位；
 * - 退出页面/取消立即停止并释放识别器，任何迟到结果不得产生转写（不隐蔽录音、不常驻监听）；
 * - 平台接口不暴露录音落盘能力（知时鹿不持久保存原始录音，仅由系统识别服务处理）。
 */
class VoiceInputControllerTest {

    private class FakeSpeechInput(available: Boolean = true) : SpeechInput {
        var available: Boolean = available
        var startCalls = 0
        var stopCalls = 0
        var cancelCalls = 0
        var releaseCalls = 0
        var throwOnStart: Throwable? = null
        val listeners = mutableListOf<SpeechInput.Listener>()

        override fun isAvailable(): Boolean = available
        override fun start(listener: SpeechInput.Listener) {
            throwOnStart?.let { throw it }
            startCalls++
            listeners += listener
        }
        override fun stop() {
            stopCalls++
        }
        override fun cancel() {
            cancelCalls++
        }
        override fun release() {
            releaseCalls++
        }
        fun latest(): SpeechInput.Listener = listeners.last()
    }

    @Test
    fun 设备无识别服务时按压给出中文说明并回退键盘且不启动监听() {
        val fake = FakeSpeechInput(available = false)
        val controller = VoiceInputController(fake)

        val started = controller.startRecording()

        assertFalse(started)
        assertEquals(0, fake.startCalls)
        val state = controller.state.value
        assertTrue("应为失败状态，实际 $state", state is VoiceInputState.Failed)
        val message = (state as VoiceInputState.Failed).message
        assertTrue("提示应说明没有语音识别服务：$message", message.contains("语音识别服务"))
        assertTrue("提示应给出键盘回退：$message", message.contains("键盘"))
    }

    @Test
    fun 麦克风权限被拒绝时给出中文提示并回退键盘() {
        val fake = FakeSpeechInput()
        val controller = VoiceInputController(fake)

        controller.reportPermissionDenied()

        val state = controller.state.value
        assertTrue(state is VoiceInputState.Failed)
        val message = (state as VoiceInputState.Failed).message
        assertTrue("提示应说明缺少麦克风权限：$message", message.contains("麦克风权限"))
        assertTrue("提示应给出键盘回退：$message", message.contains("键盘"))
        assertEquals("权限拒绝时绝不启动监听", 0, fake.startCalls)
    }

    @Test
    fun 按住说话依次显示录音中与识别中松手后转写() {
        val fake = FakeSpeechInput()
        val controller = VoiceInputController(fake)

        assertTrue(controller.startRecording())
        assertEquals(VoiceInputState.Recording(), controller.state.value)
        assertEquals(1, fake.startCalls)

        fake.latest().onPartialResult("明天下午")
        assertEquals(VoiceInputState.Recording("明天下午"), controller.state.value)

        controller.releaseAndTranscribe()
        assertEquals(VoiceInputState.Recognizing, controller.state.value)
        assertEquals(1, fake.stopCalls)

        fake.latest().onResult("明天下午三点提醒我开会")
        assertEquals(VoiceInputState.Transcribed("明天下午三点提醒我开会"), controller.state.value)
    }

    @Test
    fun 转写是等待发送的草稿点击发送后才复位() {
        val fake = FakeSpeechInput()
        val controller = VoiceInputController(fake)
        controller.startRecording()
        controller.releaseAndTranscribe()
        fake.latest().onResult("记一条待办：买牛奶")

        assertEquals(VoiceInputState.Transcribed("记一条待办：买牛奶"), controller.state.value)

        // 转写结果在发送前始终保留（可继续编辑），发送后才复位为空闲。
        assertEquals(VoiceInputState.Transcribed("记一条待办：买牛奶"), controller.state.value)
        controller.onDraftSent()
        assertEquals(VoiceInputState.Idle, controller.state.value)
    }

    @Test
    fun 识别结果为空时给出中文提示且不产生转写() {
        val fake = FakeSpeechInput()
        val controller = VoiceInputController(fake)
        controller.startRecording()
        controller.releaseAndTranscribe()

        fake.latest().onResult("   ")

        val state = controller.state.value
        assertTrue(state is VoiceInputState.Failed)
        val message = (state as VoiceInputState.Failed).message
        assertTrue("提示应说明没有识别到内容：$message", message.contains("识别"))
        assertTrue("提示应给出键盘回退：$message", message.contains("键盘"))
    }

    @Test
    fun 识别服务失败时原样展示中文提示并可回退键盘() {
        val fake = FakeSpeechInput()
        val controller = VoiceInputController(fake)
        controller.startRecording()
        controller.releaseAndTranscribe()

        fake.latest().onError("语音识别服务网络异常，请重试或改用键盘输入。")

        assertEquals(
            VoiceInputState.Failed("语音识别服务网络异常，请重试或改用键盘输入。"),
            controller.state.value,
        )
    }

    @Test
    fun 退出页面停止录音并释放识别器迟到结果被忽略() {
        val fake = FakeSpeechInput()
        val controller = VoiceInputController(fake)
        controller.startRecording()
        val oldListener = fake.latest()

        controller.onPageExit()

        assertEquals("退出页面必须取消进行中的识别", 1, fake.cancelCalls)
        assertEquals("退出页面必须释放识别器，不常驻监听", 1, fake.releaseCalls)
        assertEquals(VoiceInputState.Idle, controller.state.value)

        oldListener.onResult("迟到的识别结果")
        assertEquals("迟到结果不得产生转写", VoiceInputState.Idle, controller.state.value)
    }

    @Test
    fun 退出后重新录音时旧会话的迟到结果不会污染新转写() {
        val fake = FakeSpeechInput()
        val controller = VoiceInputController(fake)
        controller.startRecording()
        val oldListener = fake.latest()
        controller.onPageExit()

        assertTrue(controller.startRecording())
        oldListener.onResult("旧会话的结果")
        assertEquals(VoiceInputState.Recording(), controller.state.value)

        fake.latest().onResult("新会话的结果")
        assertEquals(VoiceInputState.Transcribed("新会话的结果"), controller.state.value)
    }

    @Test
    fun 录音中再次按压不会重复启动监听() {
        val fake = FakeSpeechInput()
        val controller = VoiceInputController(fake)

        controller.startRecording()
        val secondStarted = controller.startRecording()

        assertFalse(secondStarted)
        assertEquals(1, fake.startCalls)
        assertEquals(VoiceInputState.Recording(), controller.state.value)
    }

    @Test
    fun 取消录音不产生转写() {
        val fake = FakeSpeechInput()
        val controller = VoiceInputController(fake)
        controller.startRecording()
        val listener = fake.latest()

        controller.cancel()

        assertEquals(1, fake.cancelCalls)
        assertEquals(VoiceInputState.Idle, controller.state.value)
        listener.onResult("取消后迟到结果")
        assertEquals(VoiceInputState.Idle, controller.state.value)
    }

    @Test
    fun 启动识别抛异常时给出中文提示而不是崩溃() {
        val fake = FakeSpeechInput().apply { throwOnStart = IllegalStateException("boom") }
        val controller = VoiceInputController(fake)

        val started = controller.startRecording()

        assertFalse(started)
        val state = controller.state.value
        assertTrue(state is VoiceInputState.Failed)
        val message = (state as VoiceInputState.Failed).message
        assertTrue("提示应是中文可诊断文案：$message", message.contains("语音识别"))
        assertTrue("提示应给出键盘回退：$message", message.contains("键盘"))
    }

    @Test
    fun 识别可用性直接反映平台能力() {
        val fake = FakeSpeechInput(available = false)
        val controller = VoiceInputController(fake)
        assertFalse(controller.isRecognitionAvailable())

        fake.available = true
        assertTrue(controller.isRecognitionAvailable())
    }
}
