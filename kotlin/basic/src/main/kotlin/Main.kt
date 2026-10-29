package com.example.demo
import com.google.gson.*
import java.net.*
import java.io.*

data class Reply(val status: Int, val body: String)
data class ProxyRecord(val ip: String, val port: Int, val expiredSec: Double?, val expiredAbs: Double?)
fun env(name: String, fallback: String? = null): String? = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
fun isTimeout(failure: Throwable): Boolean = generateSequence(failure) { it.cause }.any { it is SocketTimeoutException || it is InterruptedIOException }
fun get(url: String, proxy: Proxy): Reply {
    val connection = URL(url).openConnection(proxy) as HttpURLConnection
    connection.connectTimeout = 10000; connection.readTimeout = 10000
    return try {
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        Reply(status, stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: "")
    } finally { connection.disconnect() }
}
fun fetch(stage: String, url: String, proxy: Proxy): Reply {
    repeat(4) { attempt ->
        try {
            val response = get(url, proxy)
            if (response.status == 429 && attempt < 3) { Thread.sleep(1000L shl attempt); return@repeat }
            check(response.status in 200..299) { "$stage: HTTP ${response.status}" }
            return response
        } catch (failure: IOException) {
            if (isTimeout(failure) && attempt < 3) { Thread.sleep(1000L shl attempt); return@repeat }
            error("$stage: " + if (isTimeout(failure)) "请求超时" else "连接失败")
        }
    }
    error("$stage: 请求重试达到上限")
}
fun literalIp(ip: String): Boolean {
    if (ip.contains(":") && ip.matches(Regex("[0-9A-Fa-f:]+"))) {
        return try { InetAddress.getByName(ip) is Inet6Address } catch (_: UnknownHostException) { false }
    }
    val parts = ip.split(".")
    return parts.size == 4 && parts.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() <= 255 }
}
fun record(ip: String, port: Double, sec: Double?, abs: Double?): ProxyRecord? {
    if (ip.isBlank() || !literalIp(ip) || !port.isFinite() || port % 1 != 0.0 || port < 1 || port > 65535) return null
    if (sec != null && (!sec.isFinite() || sec <= 0)) return null
    if (abs != null && (!abs.isFinite() || abs <= System.currentTimeMillis() / 1000.0)) return null
    return ProxyRecord(ip, port.toInt(), sec, abs)
}
fun parse(text: String): List<ProxyRecord> {
    val records = mutableListOf<ProxyRecord>()
    val body = text.trim()
    if (body.startsWith("[") || body.startsWith("{")) {
        val root = try {
            var element: JsonElement? = JsonParser.parseString(body)
            if (element!!.isJsonObject) {
                val wrapper = element.asJsonObject
                element = listOf("proxy", "data", "list", "proxy_list").firstOrNull { wrapper.has(it) }?.let { wrapper.get(it) }
            }
            require(element != null && element.isJsonArray)
            element.asJsonArray
        } catch (_: RuntimeException) { error("提取结果: JSON 格式错误") }
        for (element in root) {
            try {
                val item = element.asJsonObject
                fun number(name: String): Double? = item.get(name)?.takeUnless { it.isJsonNull }?.asDouble
                record(item.get("ip").asString, item.get("port").asDouble, number("expired_sec"), number("expired_abs"))?.let(records::add)
            } catch (_: RuntimeException) { /* Reject malformed external records. */ }
        }
    } else {
        for (line in body.lineSequence()) {
            val parts = line.trim().split(":")
            if (parts.size != 2) continue
            val port = parts[1].toDoubleOrNull() ?: continue
            record(parts[0], port, null, null)?.let(records::add)
        }
    }
    check(records.isNotEmpty()) { "提取结果: 没有有效代理或返回格式错误" }
    return records
}
fun main() {
    try {
        val extract = env("EXTRACT_URL", env("API_EXTRACT_URL")) ?: error("提取接口: 缺少 EXTRACT_URL")
        val target = env("TARGET_URL", "https://httpbin.org/ip")!!
        val selected = parse(fetch("提取接口", extract, Proxy.NO_PROXY).body).first()
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(selected.ip, selected.port))
        println("代理访问成功:\n" + fetch("代理访问目标", target, proxy).body)
    } catch (failure: Exception) {
        System.err.println(if (failure is IllegalStateException) failure.message else "请求处理失败")
        kotlin.system.exitProcess(1)
    }
}
