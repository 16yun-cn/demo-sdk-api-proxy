package cn.y16yun.demo.okhttp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Main {
    private static final class ProxyRecord {
        private final String ip;
        private final int port;

        private ProxyRecord(String ip, int port) {
            this.ip = ip;
            this.port = port;
        }
    }

    public static void main(String[] args) throws Exception {
        String extractUrl = requiredEnv("EXTRACT_URL");
        String targetUrl = env("TARGET_URL", "https://httpbin.org/ip");

        List<ProxyRecord> proxies = fetchProxyList(extractUrl);
        if (proxies.isEmpty()) {
            throw new IllegalStateException("未获取到可用代理");
        }

        System.out.println(httpGetViaProxy(targetUrl, proxies.get(0)));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim();
    }

    private static String requiredEnv(String name) {
        String value = env(name, "");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("未设置 " + name + " 环境变量");
        }
        return value;
    }

    private static List<ProxyRecord> fetchProxyList(String extractUrl) throws IOException {
        String body = httpGet(extractUrl);
        String trimmed = body.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return parseJson(trimmed);
        }
        return parseLine(trimmed);
    }

    private static List<ProxyRecord> parseLine(String body) {
        List<ProxyRecord> proxies = new ArrayList<>();
        for (String line : body.split("\\R")) {
            String value = line.trim();
            if (value.isEmpty()) {
                continue;
            }
            String[] parts = value.split(":");
            if (parts.length == 2) {
                proxies.add(new ProxyRecord(parts[0], Integer.parseInt(parts[1])));
            } else if (parts.length == 4) {
                proxies.add(new ProxyRecord(parts[2], Integer.parseInt(parts[3])));
            }
        }
        return proxies;
    }

    private static List<ProxyRecord> parseJson(String body) {
        List<ProxyRecord> proxies = new ArrayList<>();
        JsonElement parsed = JsonParser.parseString(body);
        JsonArray items = new JsonArray();
        if (parsed.isJsonArray()) {
            items = parsed.getAsJsonArray();
        } else if (parsed.isJsonObject()) {
            JsonObject object = parsed.getAsJsonObject();
            for (String key : Arrays.asList("data", "list", "proxy_list", "proxy")) {
                if (object.has(key) && object.get(key).isJsonArray()) {
                    items = object.getAsJsonArray(key);
                    break;
                }
            }
        }
        for (JsonElement item : items) {
            JsonObject object = item.getAsJsonObject();
            if (object.has("ip") && object.has("port")) {
                proxies.add(new ProxyRecord(object.get("ip").getAsString(), object.get("port").getAsInt()));
            }
        }
        return proxies;
    }

    private static String httpGet(String target) throws IOException {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(target).build();
        try (Response response = client.newCall(request).execute()) {
            return response.body().string();
        }
    }

    private static String httpGetViaProxy(String target, ProxyRecord proxy) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
            .proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxy.ip, proxy.port)))
            .build();
        Request request = new Request.Builder().url(target).build();
        try (Response response = client.newCall(request).execute()) {
            return response.body().string();
        }
    }
}
