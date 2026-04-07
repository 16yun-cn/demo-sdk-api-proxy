<?php
/**
 * 16YUN(亿牛云) PHP API 提取式示例（Guzzle 版）
 *
 * 按照伪代码规范实现：
 * - 统一的 ProxyRecord 结构
 * - 支持 line 和 json 两种格式解析
 * - 规范的错误处理和中文提示
 * - 指数退避重试机制
 * - 单代理认证信息解析
 *
 * 环境变量：
 * - EXTRACT_URL：后台"代理提取链接"（必需）
 * - TARGET_URL：目标网站（可选，默认 https://httpbin.org/ip）
 */

require_once __DIR__ . '/vendor/autoload.php';

use GuzzleHttp\Client;
use GuzzleHttp\Exception\GuzzleException;
use GuzzleHttp\Exception\RequestException;

/**
 * 统一的代理记录结构
 */
/**
 * 统一的代理记录结构
 */
class ProxyRecord
{
    public string $scheme = 'http';
    public string $ip = '';
    public int $port = 0;
    public ?string $remote_ip = null;
    public ?int $expired_sec = null;
    public ?int $expired_abs = null;
    public ?string $expired_str = null;

    public function __construct(
        string $ip = '',
        int $port = 0
    ) {
        $this->ip = $ip;
        $this->port = $port;
    }

    public function isValid(): bool
    {
        return !empty($this->ip) && $this->port > 0;
    }

    public function isExpired(): bool
    {
        return $this->expired_sec !== null && $this->expired_sec <= 0;
    }

    public function toProxyUrl(): string
    {
        return sprintf('%s://%s:%d', $this->scheme, $this->ip, $this->port);
    }
}

/**
 * 获取环境变量值
 */
function getEnvironmentVariable(string $name, string $defaultValue = ''): string
{
    $value = getenv($name);
    if ($value !== false) {
        $value = trim($value);
        return empty($value) ? $defaultValue : $value;
    }
    return $defaultValue;
}

/**
 * 获取提取链接，支持别名
 */
function getExtractUrl(): string
{
    $extractUrl = getEnvironmentVariable('EXTRACT_URL');
    if (empty($extractUrl)) {
        $extractUrl = getEnvironmentVariable('API_EXTRACT_URL');
    }
    if (empty($extractUrl)) {
        throw new InvalidArgumentException('未设置 EXTRACT_URL 环境变量');
    }
    return $extractUrl;
}

/**
 * 获取目标URL
 */
function getTargetUrl(): string
{
    return getEnvironmentVariable('TARGET_URL', 'https://httpbin.org/ip');
}

/**
 * 指数退避重试等待
 */
function backoffSleep(int $attempt): void
{
    $sleepSec = min(pow(2, max(0, $attempt - 1)), 8); // 1s, 2s, 4s, 8s
    if ($sleepSec > 0) {
        echo "等待 {$sleepSec}s 后重试...\n";
        sleep((int) $sleepSec);
    }
}

/**
 * 简单HTTP GET请求，返回[状态码, 响应体]
 */
function httpGet(string $url, float $timeout = 15.0): array
{
    $client = new Client(['timeout' => $timeout, 'verify' => false]);

    try {
        $response = $client->get($url);
        return [$response->getStatusCode(), (string) $response->getBody()];
    } catch (RequestException $e) {
        if ($e->hasResponse()) {
            return [$e->getResponse()->getStatusCode(), (string) $e->getResponse()->getBody()];
        }
        throw new RuntimeException('请求异常: ' . $e->getMessage());
    }
}

/**
 * 通过代理进行HTTP GET请求
 */
function httpGetViaProxy(string $url, ProxyRecord $proxy, float $timeout = 15.0): array
{
    $proxyUrl = $proxy->toProxyUrl();
    $client = new Client([
        'timeout' => $timeout,
        'verify' => false,
        'proxy' => $proxyUrl
    ]);

    try {
        $response = $client->get($url);
        return [$response->getStatusCode(), (string) $response->getBody()];
    } catch (RequestException $e) {
        if ($e->hasResponse()) {
            return [$e->getResponse()->getStatusCode(), (string) $e->getResponse()->getBody()];
        }
        throw new RuntimeException('请求异常: ' . $e->getMessage());
    }
}

/**
 * 解析line格式的代理记录
 */
function parseLineItemToProxy(string $line): ?ProxyRecord
{
    $line = trim($line);
    if (empty($line)) {
        return null;
    }

    $parts = explode(':', $line);
    if (count($parts) === 4) {
        // username:password:ip:port
        $port = (int) $parts[3];
        if ($port <= 0)
            return null;
        return new ProxyRecord($parts[2], $port);
    } elseif (count($parts) === 2) {
        // ip:port
        $port = (int) $parts[1];
        if ($port <= 0)
            return null;
        return new ProxyRecord($parts[0], $port);
    }

    return null;
}

/**
 * 解析json格式的代理记录
 */
function parseJsonItemToProxy(array $item): ?ProxyRecord
{
    $ip = $item['ip'] ?? '';
    $port = (int) ($item['port'] ?? 0);

    if (empty($ip) || $port <= 0) {
        return null;
    }

    $proxy = new ProxyRecord($ip, $port);

    // 解析可选字段
    if (!empty($item['remote_ip'])) {
        $proxy->remote_ip = $item['remote_ip'];
    }
    if (isset($item['expired_sec'])) {
        $proxy->expired_sec = (int) $item['expired_sec'];
    }
    if (isset($item['expired_abs'])) {
        $proxy->expired_abs = (int) $item['expired_abs'];
    }
    if (!empty($item['expired_str'])) {
        $proxy->expired_str = $item['expired_str'];
    }

    // 过滤已过期代理
    if ($proxy->isExpired()) {
        return null;
    }

    return $proxy;
}

/**
 * 获取代理列表（含提取API错误分类）
 */
function fetchProxyList(): array
{
    $extractUrl = getExtractUrl();

    // 自动检测格式或使用URL中指定的格式
    $extractFormat = str_contains($extractUrl, 'format=json') ? 'json' : 'line';

    // 确保URL包含格式参数
    if (!str_contains($extractUrl, 'format=')) {
        $separator = str_contains($extractUrl, '?') ? '&' : '?';
        $extractUrl = $extractUrl . $separator . 'format=' . $extractFormat;
    }

    [$status, $body] = httpGet($extractUrl);

    // 提取API链接错误（按官方指引分类）
    if ($status !== 200) {
        switch ($status) {
            case 400:
                throw new RuntimeException('提取API链接错误：HTTP 400 → 访问API链接的参数错误；请检查参数。');
            case 403:
                throw new RuntimeException('提取API链接错误：HTTP 403 → 访问API的主机IP不在白名单；请把当前公网IP加入白名单。');
            case 429:
                throw new RuntimeException('提取API链接错误：HTTP 429 → 访问频率过快；请降低提取频率或咨询开通分发模式。');
            default:
                throw new RuntimeException("提取API链接错误：HTTP {$status}");
        }
    }

    $proxies = [];

    if ($extractFormat === 'line') {
        $lines = preg_split('/\r?\n/', $body);
        foreach ($lines as $line) {
            if (!empty(trim($line))) {
                $proxy = parseLineItemToProxy($line);
                if ($proxy && $proxy->isValid()) {
                    $proxies[] = $proxy;
                }
            }
        }
    } elseif ($extractFormat === 'json') {
        $data = json_decode($body, true);
        if (json_last_error() !== JSON_ERROR_NONE) {
            throw new RuntimeException('JSON格式解析错误');
        }

        // 兼容某些接口返回 {data: [...]} 形式
        if (is_array($data) && !isset($data[0])) {
            $data = $data['data'] ?? $data['list'] ?? $data['proxy_list'] ?? $data['proxy'] ?? $data;
        }

        if (is_array($data)) {
            foreach ($data as $item) {
                if (is_array($item)) {
                    $proxy = parseJsonItemToProxy($item);
                    if ($proxy && $proxy->isValid()) {
                        $proxies[] = $proxy;
                    }
                }
            }
        }
    }

    if (empty($proxies)) {
        throw new RuntimeException('提取成功但没有可用代理：可能已过期或字段不匹配。');
    }

    echo "成功解析到 " . count($proxies) . " 个可用代理\n";
    return $proxies;
}

/**
 * 选择第一个代理
 */
function pickFirstProxy(array $proxies): ProxyRecord
{
    return $proxies[0];
}

/**
 * 通过代理访问目标站（含"访问代理错误"分类）
 */
function visitTargetViaProxy(ProxyRecord $proxy, string $targetUrl): string
{
    $maxAttempts = 4;

    for ($attempt = 1; $attempt <= $maxAttempts; $attempt++) {
        try {
            [$status, $body] = httpGetViaProxy($targetUrl, $proxy);

            // 访问代理错误（按官方指引分类）
            switch ($status) {
                case 200:
                    return $body;
                case 403:
                    throw new RuntimeException('访问代理错误：HTTP 403 → 访问代理的主机IP不在白名单；请确认当前IP在白名单中。');
                case 408:
                    throw new RuntimeException('访问代理错误：HTTP 408 → 网速过慢导致超时；请检查带宽/目标站响应并适当增大超时。');
                case 429:
                    if ($attempt < $maxAttempts) {
                        echo "访问代理错误：HTTP 429 → 访问代理频率过快，正在重试... [第{$attempt}次]\n";
                        backoffSleep($attempt);
                        continue 2;
                    } else {
                        throw new RuntimeException('访问代理错误：HTTP 429 → 访问代理频率过快；降低并发与请求频率（建议单请求等待 >300ms）。');
                    }
                case 504:
                    throw new RuntimeException('访问代理错误：HTTP 504 → 代理在切换IP或目标站不可达；少量属正常，若频繁请先直连自测目标站并补充 UA/Cookie/Referer。');
                default:
                    throw new RuntimeException("访问代理错误：HTTP {$status}");
            }
        } catch (RuntimeException $e) {
            if (str_contains($e->getMessage(), '超时') && $attempt < $maxAttempts) {
                echo "请求超时，正在重试... [第{$attempt}次]\n";
                backoffSleep($attempt);
                continue;
            } else {
                throw new RuntimeException('访问代理错误：' . $e->getMessage());
            }
        }
    }

    throw new RuntimeException('访问代理失败：已达到最大重试次数');
}

/**
 * 主流程
 */
function main(): int
{
    try {
        // 1) 获取代理列表（可能抛出"提取API链接错误"）
        $proxyList = fetchProxyList();

        // 2) 选择第一个代理
        $firstProxy = pickFirstProxy($proxyList);
        echo "选择代理: {$firstProxy->ip}:{$firstProxy->port}\n";

        // 3) 通过该代理访问目标站（可能抛出"访问代理错误"）
        $targetUrl = getTargetUrl();
        $body = visitTargetViaProxy($firstProxy, $targetUrl);

        echo "OK → httpbin 响应：\n";
        echo $body . "\n";

        return 0;
    } catch (Exception $e) {
        fwrite(STDERR, "ERROR → " . $e->getMessage() . "\n");
        return 1;
    }
}

// 运行主程序
if (php_sapi_name() === 'cli') {
    exit(main());
}
