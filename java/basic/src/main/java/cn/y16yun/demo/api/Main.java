package cn.y16yun.demo.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Main {
    private static final Gson GSON = new Gson();

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

        String body = httpGetViaProxy(targetUrl, proxies.get(0));
        System.out.println(body);
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
        return readAll(new URL(target), null);
    }

    private static String httpGetViaProxy(String target, ProxyRecord proxy) throws IOException {
        Proxy javaProxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxy.ip, proxy.port));
        return readAll(new URL(target), javaProxy);
    }

    private static String readAll(URL target, Proxy proxy) throws IOException {
        java.net.URLConnection connection = proxy == null ? target.openConnection() : target.openConnection(proxy);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            return builder.toString();
        }
    }
}
