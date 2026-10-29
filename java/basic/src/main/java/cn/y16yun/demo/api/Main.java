package cn.y16yun.demo.api;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;


public final class Main {
    static final class Reply {
        final int status; final String body;
        Reply(int status, String body) { this.status = status; this.body = body; }
    }
    static final class Record {
        final String ip; final int port;
        final Double expiredSec, expiredAbs;
        Record(String ip, int port, Double sec, Double abs) {
            this.ip = ip; this.port = port; expiredSec = sec; expiredAbs = abs;
        }
    }
    static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
    static boolean timeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof java.net.SocketTimeoutException || cause instanceof java.io.InterruptedIOException)
                return true;
        return false;
    }
    static Reply get(String url, Proxy proxy) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection(proxy);
        connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
        try {
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            if (input != null) try (InputStream stream = input) {
                byte[] buffer = new byte[8192]; int length;
                while ((length = stream.read(buffer)) != -1) body.write(buffer, 0, length);
            }
            return new Reply(status, new String(body.toByteArray(), StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
    }
    static Reply fetch(String stage, String url, Proxy proxy) throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                Reply reply = get(url, proxy);
                if (reply.status == 429 && attempt < 3) {
                    Thread.sleep(1000L << attempt); continue;
                }
                if (reply.status < 200 || reply.status >= 300)
                    throw new IllegalStateException(stage + ": HTTP " + reply.status);
                return reply;
            } catch (IOException failure) {
                if (timeout(failure) && attempt < 3) { Thread.sleep(1000L << attempt); continue; }
                throw new IllegalStateException(stage + ": " + (timeout(failure) ? "请求超时" : "连接失败"));
            }
        }
        throw new IllegalStateException(stage + ": 请求重试达到上限");
    }
    static boolean literalIp(String ip) {
        if (ip.indexOf(':') >= 0 && ip.matches("[0-9A-Fa-f:]+")) {
            try { return InetAddress.getByName(ip) instanceof Inet6Address; }
            catch (UnknownHostException invalid) { return false; }
        }
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) return false;
        }
        return true;
    }
    static Record record(String ip, double port, Double sec, Double abs) {
        if (ip == null || ip.isEmpty() || !literalIp(ip) ||
            !Double.isFinite(port) || port != Math.floor(port) || port < 1 || port > 65535)
            return null;
        if (sec != null && (!Double.isFinite(sec) || sec <= 0)) return null;
        if (abs != null && (!Double.isFinite(abs) || abs <= System.currentTimeMillis() / 1000.0)) return null;
        return new Record(ip, (int)port, sec, abs);
    }
    static Double number(JsonObject item, String field) {
        return item.has(field) && !item.get(field).isJsonNull() ? item.get(field).getAsDouble() : null;
    }
    static List<Record> parse(String text) {
        List<Record> records = new ArrayList<>();
        text = text.trim();
        if (text.startsWith("[") || text.startsWith("{")) {
            JsonElement root;
            try {
                root = JsonParser.parseString(text);
                if (root.isJsonObject()) {
                    JsonObject wrapper = root.getAsJsonObject(); root = null;
                    for (String key : new String[]{"proxy", "data", "list", "proxy_list"})
                        if (wrapper.has(key)) { root = wrapper.get(key); break; }
                }
                if (root == null || !root.isJsonArray()) throw new IllegalArgumentException();
            } catch (RuntimeException failure) { throw new IllegalStateException("提取结果: JSON 格式错误"); }
            for (JsonElement element : root.getAsJsonArray()) {
                try {
                    JsonObject item = element.getAsJsonObject();
                    Record record = record(item.get("ip").getAsString(), item.get("port").getAsDouble(),
                                           number(item, "expired_sec"), number(item, "expired_abs"));
                    if (record != null) records.add(record);
                } catch (RuntimeException ignored) { /* A malformed external record is not a usable proxy. */ }
            }
        } else {
            for (String line : text.split("\\r?\\n")) {
                String[] parts = line.trim().split(":", -1);
                if (parts.length != 2) continue;
                try {
                    Record record = record(parts[0], Double.parseDouble(parts[1]), null, null);
                    if (record != null) records.add(record);
                } catch (NumberFormatException ignored) { /* Reject an invalid external port. */ }
            }
        }
        if (records.isEmpty()) throw new IllegalStateException("提取结果: 没有有效代理或返回格式错误");
        return records;
    }
    public static void main(String[] args) {
        try {
            String extract = env("EXTRACT_URL", env("API_EXTRACT_URL", null));
            if (extract == null) throw new IllegalStateException("提取接口: 缺少 EXTRACT_URL");
            String target = env("TARGET_URL", "https://httpbin.org/ip");
            Record selected = parse(fetch("提取接口", extract, Proxy.NO_PROXY).body).get(0);
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(selected.ip, selected.port));
            System.out.println("代理访问成功:\n" + fetch("代理访问目标", target, proxy).body);
        } catch (Exception failure) {
            // Only our stage descriptions are printed; URLs may contain account secrets.
            System.err.println(failure instanceof IllegalStateException ? failure.getMessage() : "请求处理失败");
            System.exit(1);
        }
    }
}
