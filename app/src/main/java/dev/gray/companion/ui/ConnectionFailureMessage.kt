package dev.gray.companion.ui

import me.rerere.ai.util.HttpException

/** Show actionable transport status without echoing credential-bearing URLs or response bodies. */
internal fun connectionFailureMessage(failure: Throwable): String {
    val causes = generateSequence(failure) { it.cause }.take(8).toList()
    val code = causes.filterIsInstance<HttpException>().firstNotNullOfOrNull { it.statusCode }
    if (code != null) return "HTTP $code：" + when (code) {
        400 -> "请求或授权参数无效；服务账号请检查邮箱、私钥和设备时间，模型请求请检查参数。"
        401 -> "身份验证失败，请检查密钥或服务账号授权。"
        403 -> "访问被拒绝，请检查项目 API 是否启用、服务账号权限及服务端限制。"
        404 -> "模型或接口不存在，请核对模型 ID、项目与区域。"
        429 -> "请求限流或配额不足，请稍后重试并检查配额。"
        in 500..599 -> "服务端暂时失败，请稍后重试。"
        else -> "连接请求失败，请检查连接配置。"
    }
    if (causes.any { it is java.security.GeneralSecurityException })
        return "服务账号私钥无法解析，请导入服务账号 JSON 或填写完整的 PRIVATE KEY。"
    if (causes.any { it is java.io.IOException }) return "网络连接失败，请检查网络和代理设置。"
    return "连接请求失败，请检查连接配置。"
}
