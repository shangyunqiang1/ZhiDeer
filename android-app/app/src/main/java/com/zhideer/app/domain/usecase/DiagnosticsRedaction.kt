package com.zhideer.app.domain.usecase

/**
 * 诊断文本脱敏（T35/#36，规格用户故事 54、89）。
 *
 * 统一处理失败记录与导出文本：任何进入诊断存储/导出文件的文本都必须先经过本对象。
 * 规则只做确定性替换，绝不猜测或保留“可能有用”的敏感片段：
 * - API Key（sk- 前缀）、Bearer 令牌、api_key/password/secret/token 等键值；
 * - URL 中的用户名密码；
 * - 多余空白折叠，超长截断，避免把整段私人内容带出。
 */
object DiagnosticsRedaction {

    /** 默认摘要最大长度：只保留足够定位问题的信息。 */
    const val DEFAULT_MAX_LENGTH = 200

    /** 键值对的值：在空白、中文标点与引号处结束，避免一条规则吞掉后续字段。 */
    private const val VALUE: String = """[^\s；，。;,"'）)]+"""

    private val rules: List<Pair<Regex, String>> = listOf(
        // API Key 先整体抹掉，避免后续规则残留片段
        Regex("(?i)sk-[A-Za-z0-9_-]{4,}") to "sk-***",
        Regex("(?i)bearer\\s+$VALUE") to "Bearer ***",
        Regex("(?i)api[_-]?key\\s*[=:]\\s*$VALUE") to "apiKey=***",
        Regex("(?i)(?:password|passwd|secret|token)\\s*[=:]\\s*$VALUE") to "凭据=***",
        Regex("://[^/@\\s:]+:[^/@\\s]+@") to "://***@",
    )

    /** 脱敏并规范化一段诊断文本；结果永不超过 [maxLength]（含省略号）。 */
    fun redact(text: String, maxLength: Int = DEFAULT_MAX_LENGTH): String {
        var result = text
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        rules.forEach { (regex, replacement) ->
            result = regex.replace(result, replacement)
        }
        if (result.length > maxLength) {
            result = result.take(maxLength - 1) + "…"
        }
        return result
    }
}
