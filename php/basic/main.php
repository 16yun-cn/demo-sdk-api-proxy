<?php
// 16YUN(亿牛云) PHP API 提取式 Demo
require __DIR__ . '/vendor/autoload.php';

use GuzzleHttp\Client;

$extractUrl = getenv('EXTRACT_URL');
if (!$extractUrl) {
    fwrite(STDERR, "缺少 EXTRACT_URL\n");
    exit(2);
}
$target = getenv('TARGET_URL') ?: 'https://httpbin.org/ip';

$client = new Client(['timeout' => 15.0, 'verify' => true]);
$response = $client->get($extractUrl);
$body = (string) $response->getBody();

$proxyInfo = null;

// 检测是否为 JSON 格式
$jsonData = json_decode($body, true);
if (json_last_error() === JSON_ERROR_NONE && is_array($jsonData)) {
    // JSON 格式，支持 {proxy: [...]} 等格式
    $proxyList = $jsonData['data'] ?? $jsonData['list'] ?? $jsonData['proxy_list'] ?? $jsonData['proxy'] ?? $jsonData;
    if (is_array($proxyList) && count($proxyList) > 0) {
        $item = $proxyList[0];
        if (is_array($item) && isset($item['ip']) && isset($item['port'])) {
            $proxyInfo = $item['ip'] . ':' . $item['port'];
        }
    }
} else {
    // Line 格式
    $lines = array_values(array_filter(array_map('trim', preg_split("/\r?\n/", $body))));
    if (count($lines) > 0) {
        $proxyInfo = $lines[0]; // ip:port 或 user:pass:ip:port
        // 如果是 user:pass:ip:port 格式，提取 ip:port
        $parts = explode(':', $proxyInfo);
        if (count($parts) === 4) {
            $proxyInfo = $parts[2] . ':' . $parts[3];
        } else if (count($parts) === 2) {
            $proxyInfo = $parts[0] . ':' . $parts[1];
        }
    }
}

if (!$proxyInfo) {
    fwrite(STDERR, "未获取到可用代理\n");
    exit(3);
}

$proxy = 'http://' . $proxyInfo;
$client2 = new Client(['timeout' => 15.0, 'verify' => true, 'proxy' => $proxy]);
$resp = $client2->get($target);
echo $resp->getStatusCode(), "\n";
echo $resp->getBody();

