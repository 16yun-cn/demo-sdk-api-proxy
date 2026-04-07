import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy

data class ProxyRecord(val ip: String, val port: Int)

fun main() {
    val extractUrl = requiredEnv("EXTRACT_URL")
    val targetUrl = env("TARGET_URL", "https://httpbin.org/ip")

    val proxies = fetchProxyList(extractUrl)
    require(proxies.isNotEmpty()) { "未获取到可用代理" }

    println(httpGetViaProxy(targetUrl, proxies.first()))
}

private fun env(name: String, fallback: String): String {
    val value = System.getenv(name)?.trim()
    return if (value.isNullOrEmpty()) fallback else value
}

private fun requiredEnv(name: String): String {
    val value = env(name, "")
    require(value.isNotEmpty()) { "未设置 $name 环境变量" }
    return value
}

private fun fetchProxyList(extractUrl: String): List<ProxyRecord> {
    val body = httpGet(extractUrl).trim()
    return if (body.startsWith("{") || body.startsWith("[")) parseJson(body) else parseLine(body)
}

private fun parseLine(body: String): List<ProxyRecord> =
    body.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val parts = line.split(":")
            when (parts.size) {
                2 -> ProxyRecord(parts[0], parts[1].toInt())
                4 -> ProxyRecord(parts[2], parts[3].toInt())
                else -> null
            }
        }
        .toList()

private fun parseJson(body: String): List<ProxyRecord> {
    val parsed = JsonParser.parseString(body)
    val items = when {
        parsed.isJsonArray -> parsed.asJsonArray
        parsed.isJsonObject -> {
            val obj = parsed.asJsonObject
            listOf("data", "list", "proxy_list", "proxy")
                .firstNotNullOfOrNull { key -> obj.get(key)?.takeIf { it.isJsonArray }?.asJsonArray }
                ?: JsonArray()
        }
        else -> JsonArray()
    }

    return items.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        if (!obj.has("ip") || !obj.has("port")) return@mapNotNull null
        ProxyRecord(obj.get("ip").asString, obj.get("port").asInt)
    }
}

private fun httpGet(target: String): String {
    val client = OkHttpClient()
    val request = Request.Builder().url(target).build()
    client.newCall(request).execute().use { response ->
        return response.body!!.string()
    }
}

private fun httpGetViaProxy(target: String, proxy: ProxyRecord): String {
    val client = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.ip, proxy.port)))
        .build()
    val request = Request.Builder().url(target).build()
    client.newCall(request).execute().use { response ->
        return response.body!!.string()
    }
}
