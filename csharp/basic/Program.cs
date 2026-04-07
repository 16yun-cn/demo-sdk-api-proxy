/*
 * 16YUN(亿牛云) C# API 提取式示例（HttpClient 版）
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

using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

/// <summary>
/// 统一的代理记录结构
/// </summary>
/// <summary>
/// 统一的代理记录结构
/// </summary>
public class ProxyRecord
{
    public string Scheme { get; set; } = "http";
    public string Ip { get; set; } = string.Empty;
    public int Port { get; set; } = 0;
    public string? RemoteIp { get; set; }
    public int? ExpiredSec { get; set; }
    public long? ExpiredAbs { get; set; }
    public string? ExpiredStr { get; set; }

    public ProxyRecord() { }

    public ProxyRecord(string ip, int port)
    {
        Ip = ip;
        Port = port;
    }

    public bool IsValid() => !string.IsNullOrEmpty(Ip) && Port > 0;

    public bool IsExpired() => ExpiredSec.HasValue && ExpiredSec.Value <= 0;

    public string ToProxyUrl()
    {
        return $"{Scheme}://{Ip}:{Port}";
    }

    public override string ToString() => $"ProxyRecord{{Ip='{Ip}', Port={Port}}}";
}

class Program
{
    /// <summary>
    /// 获取环境变量值
    /// </summary>
    static string GetEnv(string name, string defaultValue = "")
    {
        var value = Environment.GetEnvironmentVariable(name);
        return string.IsNullOrWhiteSpace(value) ? defaultValue : value.Trim();
    }

    /// <summary>
    /// 获取提取链接，支持别名
    /// </summary>
    static string GetExtractUrl()
    {
        var extractUrl = GetEnv("EXTRACT_URL");
        if (string.IsNullOrEmpty(extractUrl))
        {
            extractUrl = GetEnv("API_EXTRACT_URL");
        }
        if (string.IsNullOrEmpty(extractUrl))
        {
            throw new ArgumentException("未设置 EXTRACT_URL 环境变量");
        }
        return extractUrl;
    }

    /// <summary>
    /// 获取目标URL
    /// </summary>
    static string GetTargetUrl() => GetEnv("TARGET_URL", "https://httpbin.org/ip");

    /// <summary>
    /// 指数退避重试等待
    /// </summary>
    static async Task BackoffSleep(int attempt)
    {
        var sleepSec = Math.Min((int)Math.Pow(2, Math.Max(0, attempt - 1)), 8); // 1s, 2s, 4s, 8s
        if (sleepSec > 0)
        {
            Console.WriteLine($"等待 {sleepSec}s 后重试...");
            await Task.Delay(TimeSpan.FromSeconds(sleepSec));
        }
    }

    /// <summary>
    /// 简单HTTP GET请求，返回(状态码, 响应体)
    /// </summary>
    static async Task<(int StatusCode, string Body)> HttpGet(string url)
    {
        using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(15) };
        try
        {
            var response = await client.GetAsync(url);
            var body = await response.Content.ReadAsStringAsync();
            return ((int)response.StatusCode, body);
        }
        catch (TaskCanceledException)
        {
            throw new Exception("请求超时");
        }
        catch (Exception e)
        {
            throw new Exception($"请求异常: {e.Message}");
        }
    }

    /// <summary>
    /// 通过代理进行HTTP GET请求
    /// </summary>
    static async Task<(int StatusCode, string Body)> HttpGetViaProxy(string url, ProxyRecord proxy)
    {
        var webProxy = new WebProxy(new Uri(proxy.ToProxyUrl()));
        
        var handler = new HttpClientHandler
        {
            Proxy = webProxy,
            UseProxy = true
        };

        using var client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(15) };
        try
        {
            var response = await client.GetAsync(url);
            var body = await response.Content.ReadAsStringAsync();
            return ((int)response.StatusCode, body);
        }
        catch (TaskCanceledException)
        {
            throw new Exception("请求超时");
        }
        catch (Exception e)
        {
            throw new Exception($"请求异常: {e.Message}");
        }
    }

    /// <summary>
    /// 解析line格式的代理记录
    /// </summary>
    static ProxyRecord? ParseLineItemToProxy(string line)
    {
        line = line.Trim();
        if (string.IsNullOrEmpty(line)) return null;

        var parts = line.Split(':');
        if (parts.Length == 4)
        {
            // username:password:ip:port
            if (int.TryParse(parts[3], out var port))
            {
                return new ProxyRecord(parts[2], port);
            }
        }
        else if (parts.Length == 2)
        {
            // ip:port
            if (int.TryParse(parts[1], out var port))
            {
                return new ProxyRecord(parts[0], port);
            }
        }

        return null;
    }

    /// <summary>
    /// 解析json格式的代理记录
    /// </summary>
    static ProxyRecord? ParseJsonItemToProxy(JsonElement item)
    {
        if (item.ValueKind != JsonValueKind.Object) return null;

        if (!item.TryGetProperty("ip", out var ipElement) || 
            !item.TryGetProperty("port", out var portElement))
        {
            return null;
        }

        var ip = ipElement.GetString();
        if (string.IsNullOrEmpty(ip)) return null;

        int port;
        if (portElement.ValueKind == JsonValueKind.Number)
        {
            port = portElement.GetInt32();
        }
        else if (portElement.ValueKind == JsonValueKind.String)
        {
            if (!int.TryParse(portElement.GetString(), out port))
            {
                return null;
            }
        }
        else
        {
            return null;
        }

        var proxy = new ProxyRecord(ip, port);

        // 解析可选字段
        if (item.TryGetProperty("remote_ip", out var remoteIpElement) && 
            remoteIpElement.ValueKind == JsonValueKind.String)
        {
            proxy.RemoteIp = remoteIpElement.GetString();
        }

        if (item.TryGetProperty("expired_sec", out var expiredSecElement) && 
            expiredSecElement.ValueKind == JsonValueKind.Number)
        {
            proxy.ExpiredSec = expiredSecElement.GetInt32();
        }

        if (item.TryGetProperty("expired_abs", out var expiredAbsElement) && 
            expiredAbsElement.ValueKind == JsonValueKind.Number)
        {
            proxy.ExpiredAbs = expiredAbsElement.GetInt64();
        }

        if (item.TryGetProperty("expired_str", out var expiredStrElement) && 
            expiredStrElement.ValueKind == JsonValueKind.String)
        {
            proxy.ExpiredStr = expiredStrElement.GetString();
        }

        // 过滤已过期代理
        return proxy.IsExpired() ? null : proxy;
    }

    /// <summary>
    /// 获取代理列表（含提取API错误分类）
    /// </summary>
    static async Task<List<ProxyRecord>> FetchProxyList()
    {
        var extractUrl = GetExtractUrl();

        // 自动检测格式或使用URL中指定的格式
        var extractFormat = extractUrl.Contains("format=json") ? "json" : "line";

        // 确保URL包含格式参数
        if (!extractUrl.Contains("format="))
        {
            var separator = extractUrl.Contains("?") ? "&" : "?";
            extractUrl = $"{extractUrl}{separator}format={extractFormat}";
        }

        var (status, body) = await HttpGet(extractUrl);

        // 提取API链接错误（按官方指引分类）
        if (status != 200)
        {
            var errorMessage = status switch
            {
                400 => "提取API链接错误：HTTP 400 → 访问API链接的参数错误；请检查参数。",
                403 => "提取API链接错误：HTTP 403 → 访问API的主机IP不在白名单；请把当前公网IP加入白名单。",
                429 => "提取API链接错误：HTTP 429 → 访问频率过快；请降低提取频率或咨询开通分发模式。",
                _ => $"提取API链接错误：HTTP {status}"
            };
            throw new Exception(errorMessage);
        }

        var proxies = new List<ProxyRecord>();

        if (extractFormat == "line")
        {
            var lines = body.Split('\n', '\r');
            foreach (var line in lines)
            {
                if (!string.IsNullOrWhiteSpace(line))
                {
                    var proxy = ParseLineItemToProxy(line);
                    if (proxy?.IsValid() == true)
                    {
                        proxies.Add(proxy);
                    }
                }
            }
        }
        else if (extractFormat == "json")
        {
            try
            {
                using var document = JsonDocument.Parse(body);
                var root = document.RootElement;

                // 兼容某些接口返回 {data: [...]} 形式
                if (root.ValueKind == JsonValueKind.Object)
                {
                    if (root.TryGetProperty("data", out var dataElement))
                    {
                        root = dataElement;
                    }
                    else if (root.TryGetProperty("list", out var listElement))
                    {
                        root = listElement;
                    }
                    else if (root.TryGetProperty("proxy_list", out var proxyListElement))
                    {
                        root = proxyListElement;
                    }
                    else if (root.TryGetProperty("proxy", out var proxyElement))
                    {
                        root = proxyElement;
                    }
                }

                if (root.ValueKind == JsonValueKind.Array)
                {
                    foreach (var item in root.EnumerateArray())
                    {
                        var proxy = ParseJsonItemToProxy(item);
                        if (proxy?.IsValid() == true)
                        {
                            proxies.Add(proxy);
                        }
                    }
                }
            }
            catch (JsonException)
            {
                throw new Exception("JSON格式解析错误");
            }
        }

        if (proxies.Count == 0)
        {
            throw new Exception("提取成功但没有可用代理：可能已过期或字段不匹配。");
        }

        Console.WriteLine($"成功解析到 {proxies.Count} 个可用代理");
        return proxies;
    }

    /// <summary>
    /// 选择第一个代理
    /// </summary>
    static ProxyRecord PickFirstProxy(List<ProxyRecord> proxies) => proxies.First();

    /// <summary>
    /// 通过代理访问目标站（含"访问代理错误"分类）
    /// </summary>
    static async Task<string> VisitTargetViaProxy(ProxyRecord proxy, string targetUrl)
    {
        const int maxAttempts = 4;

        for (int attempt = 1; attempt <= maxAttempts; attempt++)
        {
            try
            {
                var (status, body) = await HttpGetViaProxy(targetUrl, proxy);

                // 访问代理错误（按官方指引分类）
                switch (status)
                {
                    case 200:
                        return body;
                    case 403:
                        throw new Exception("访问代理错误：HTTP 403 → 访问代理的主机IP不在白名单；请确认当前IP在白名单中。");
                    case 408:
                        throw new Exception("访问代理错误：HTTP 408 → 网速过慢导致超时；请检查带宽/目标站响应并适当增大超时。");
                    case 429:
                        if (attempt < maxAttempts)
                        {
                            Console.WriteLine($"访问代理错误：HTTP 429 → 访问代理频率过快，正在重试... [第{attempt}次]");
                            await BackoffSleep(attempt);
                            continue;
                        }
                        else
                        {
                            throw new Exception("访问代理错误：HTTP 429 → 访问代理频率过快；降低并发与请求频率（建议单请求等待 >300ms）。");
                        }
                    case 504:
                        throw new Exception("访问代理错误：HTTP 504 → 代理在切换IP或目标站不可达；少量属正常，若频繁请先直连自测目标站并补充 UA/Cookie/Referer。");
                    default:
                        throw new Exception($"访问代理错误：HTTP {status}");
                }
            }
            catch (Exception e)
            {
                if (e.Message.Contains("超时") && attempt < maxAttempts)
                {
                    Console.WriteLine($"请求超时，正在重试... [第{attempt}次]");
                    await BackoffSleep(attempt);
                    continue;
                }
                else
                {
                    throw new Exception($"访问代理错误：{e.Message}");
                }
            }
        }

        throw new Exception("访问代理失败：已达到最大重试次数");
    }

    /// <summary>
    /// 主流程
    /// </summary>
    static async Task Main()
    {
        try
        {
            // 1) 获取代理列表（可能抛出"提取API链接错误"）
            var proxyList = await FetchProxyList();

            // 2) 选择第一个代理
            var firstProxy = PickFirstProxy(proxyList);
            Console.WriteLine($"选择代理: {firstProxy.Ip}:{firstProxy.Port}");

            // 3) 通过该代理访问目标站（可能抛出"访问代理错误"）
            var targetUrl = GetTargetUrl();
            var body = await VisitTargetViaProxy(firstProxy, targetUrl);

            Console.WriteLine("OK → httpbin 响应：");
            Console.WriteLine(body);
        }
        catch (Exception e)
        {
            Console.Error.WriteLine($"ERROR → {e.Message}");
            Environment.Exit(1);
        }
    }
}

