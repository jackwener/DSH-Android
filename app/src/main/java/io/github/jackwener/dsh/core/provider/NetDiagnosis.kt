package io.github.jackwener.dsh.core.provider

import io.github.jackwener.dsh.R
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * Why a model endpoint could not be reached, in the terms the user can act on. The same mapping
 * serves a chat turn (engine `TRANSPORT` errors, whose message carries the root cause since js
 * patch 14) and 获取模型列表 (Java exceptions from HttpURLConnection), so both show the same hint.
 */
enum class NetCause(val message: Int, val hint: Int?) {
    Refused(R.string.net_refused, R.string.net_refused_hint),
    Unreachable(R.string.net_unreachable, R.string.net_unreachable_hint),
    Dns(R.string.net_dns, R.string.net_dns_hint),
    Tls(R.string.net_tls, R.string.net_tls_hint),
    /** Unrecognized cause: the raw detail is all there is to show. */
    Other(R.string.net_other, null),
}

/** [detail] is the raw root cause (e.g. `ECONNREFUSED 192.168.1.5:8080 (connection refused)`). */
data class NetDiagnosis(val cause: NetCause, val detail: String)

object NetDiagnose {
    private const val ENGINE_MARK = "provider transport failed"
    private val TLS_CODE = Regex("^(ERR_TLS_|ERR_SSL_|CERT_|UNABLE_TO_|DEPTH_ZERO_|SELF_SIGNED_|HOSTNAME_MISMATCH|ERR_OSSL_)")
    private val ERRNO = Regex("\\b(ECONNREFUSED|ETIMEDOUT|EHOSTUNREACH|ENETUNREACH|ENETDOWN|ENOTFOUND|EAI_AGAIN|EAI_NODATA|EAI_NONAME)\\b")

    fun causeOf(code: String): NetCause = when {
        code == "ECONNREFUSED" -> NetCause.Refused
        code in setOf("ETIMEDOUT", "UND_ERR_CONNECT_TIMEOUT", "EHOSTUNREACH", "ENETUNREACH", "ENETDOWN") -> NetCause.Unreachable
        code in setOf("ENOTFOUND", "EAI_AGAIN", "EAI_NODATA", "EAI_NONAME") -> NetCause.Dns
        TLS_CODE.containsMatchIn(code) -> NetCause.Tls
        else -> NetCause.Other
    }

    /**
     * An engine turn error. Only `TRANSPORT` failures of the third-party adapter carry a cause
     * (`llm-service-lite: provider transport failed: <CODE> <host:port> (<gloss>)`); anything else,
     * or a transport failure without a cause, is left to the generic error row (null).
     */
    fun ofEngineError(code: String?, message: String): NetDiagnosis? {
        if (code != "TRANSPORT") return null
        val detail = message.substringAfter("$ENGINE_MARK: ", "").trim()
        if (detail.isEmpty()) return null
        val first = detail.substringBefore(' ')
        val cause = if (first.matches(Regex("[A-Z][A-Z0-9_]+"))) causeOf(first) else NetCause.Other
        return NetDiagnosis(cause, detail)
    }

    private const val PARSE_MARK = "could not parse the provider's response: "

    /**
     * The provider answered, but in a shape the engine's adapter could not translate (js patch 16:
     * `MALFORMED_RESPONSE`, e.g. `llm-service-lite: could not parse the provider's response:
     * TypeError: …` or `… stream contains invalid JSON`). Not a network problem and not retried;
     * returns the detail to show after "模型服务返回的数据格式不兼容：", else null.
     */
    fun formatErrorOf(code: String?, message: String): String? {
        if (code != "MALFORMED_RESPONSE") return null
        val detail = message.substringAfter(PARSE_MARK, message.removePrefix("llm-service-lite: ")).trim()
        return detail.ifEmpty { code }
    }

    /** A 获取模型列表 failure (HttpURLConnection); null for non-network failures (HTTP status, bad JSON). */
    fun ofException(t: Throwable): NetDiagnosis? {
        val chain = generateSequence(t) { it.cause.takeIf { c -> c !== it } }.take(8).toList()
        val detail = chain.firstNotNullOfOrNull { it.message?.takeIf(String::isNotBlank) }?.let { "${t.javaClass.simpleName}: $it" }?.take(300)
            ?: t.javaClass.simpleName
        // Android's messages name the errno ("… failed: ECONNREFUSED (Connection refused)"): most precise.
        chain.firstNotNullOfOrNull { it.message?.let(ERRNO::find)?.value }?.let { return NetDiagnosis(causeOf(it), detail) }
        val cause = when {
            chain.any { it is UnknownHostException } -> NetCause.Dns
            chain.any { it is SSLException || it is CertificateException } -> NetCause.Tls
            chain.any { it is SocketTimeoutException || it is NoRouteToHostException } -> NetCause.Unreachable
            chain.any { it is ConnectException } -> NetCause.Refused
            chain.any { it is java.io.IOException } -> NetCause.Other
            else -> return null
        }
        return NetDiagnosis(cause, detail)
    }

    /**
     * The base URL names the phone itself (loopback / unspecified): a gateway on the user's PC is
     * not reachable there. Only a warning: something may really run on the phone (e.g. Termux).
     */
    fun isOnDeviceAddress(baseUrl: String): Boolean {
        val host = runCatching { URI(baseUrl.trim()).host }.getOrNull()?.removePrefix("[")?.removeSuffix("]")?.lowercase() ?: return false
        return host == "localhost" || host.endsWith(".localhost") || host == "0.0.0.0" || host == "::1" || host == "::" ||
            host == "0:0:0:0:0:0:0:1" || Regex("^127(\\.\\d{1,3}){3}$").matches(host)
    }
}
