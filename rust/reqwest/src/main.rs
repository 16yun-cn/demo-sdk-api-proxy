/*
 * 16YUN(亿牛云) Rust API 提取式示例（reqwest 版）
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

use anyhow::{anyhow, Result};
use reqwest::Proxy;
use serde_json::Value;
use std::env;
use std::time::Duration;
use tokio::time;

/// 统一的代理记录结构
/// 统一的代理记录结构
#[derive(Debug, Clone)]
struct ProxyRecord {
    scheme: String,
    ip: String,
    port: u16,
    remote_ip: Option<String>,
    expired_sec: Option<i32>,
    expired_abs: Option<i64>,
    expired_str: Option<String>,
}

impl ProxyRecord {
    fn new(ip: String, port: u16) -> Self {
        Self {
            scheme: "http".to_string(),
            ip,
            port,
            remote_ip: None,
            expired_sec: None,
            expired_abs: None,
            expired_str: None,
        }
    }

    fn is_valid(&self) -> bool {
        !self.ip.is_empty() && self.port > 0
    }

    fn is_expired(&self) -> bool {
        self.expired_sec.map_or(false, |sec| sec <= 0)
    }

    fn to_proxy_url(&self) -> String {
        format!("{}://{}:{}", self.scheme, self.ip, self.port)
    }
}

/// 获取环境变量值
fn get_env(name: &str) -> Option<String> {
    env::var(name).ok().filter(|s| !s.trim().is_empty()).map(|s| s.trim().to_string())
}

/// 获取提取链接，支持别名
fn get_extract_url() -> Result<String> {
    get_env("EXTRACT_URL")
        .or_else(|| get_env("API_EXTRACT_URL"))
        .ok_or_else(|| anyhow!("未设置 EXTRACT_URL 环境变量"))
}

/// 获取目标URL
fn get_target_url() -> String {
    get_env("TARGET_URL").unwrap_or_else(|| "https://httpbin.org/ip".to_string())
}

/// 指数退避重试等待
async fn backoff_sleep(attempt: u32) {
    let sleep_sec = (2_u64.pow(attempt.saturating_sub(1))).min(8); // 1s, 2s, 4s, 8s
    if sleep_sec > 0 {
        println!("等待 {}s 后重试...", sleep_sec);
        time::sleep(Duration::from_secs(sleep_sec)).await;
    }
}

/// 简单HTTP GET请求，返回(状态码, 响应体)
async fn http_get(url: &str) -> Result<(u16, String)> {
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(15))
        .danger_accept_invalid_certs(true)
        .build()?;

    let response = client.get(url).send().await
        .map_err(|e| anyhow!("请求异常: {}", e))?;

    let status = response.status().as_u16();
    let body = response.text().await
        .map_err(|e| anyhow!("读取响应失败: {}", e))?;

    Ok((status, body))
}

/// 通过代理进行HTTP GET请求
async fn http_get_via_proxy(url: &str, proxy: &ProxyRecord) -> Result<(u16, String)> {
    let proxy_url = proxy.to_proxy_url();
    let proxy_obj = Proxy::all(&proxy_url)
        .map_err(|e| anyhow!("代理配置错误: {}", e))?;

    let client = reqwest::Client::builder()
        .proxy(proxy_obj)
        .timeout(Duration::from_secs(15))
        .danger_accept_invalid_certs(true)
        .build()?;

    let response = client.get(url).send().await
        .map_err(|e| anyhow!("请求异常: {}", e))?;

    let status = response.status().as_u16();
    let body = response.text().await
        .map_err(|e| anyhow!("读取响应失败: {}", e))?;

    Ok((status, body))
}

/// 解析line格式的代理记录
fn parse_line_item_to_proxy(line: &str) -> Option<ProxyRecord> {
    let line = line.trim();
    if line.is_empty() {
        return None;
    }

    let parts: Vec<&str> = line.split(':').collect();
    match parts.len() {
        4 => {
            // username:password:ip:port
            if let Ok(port) = parts[3].parse::<u16>() {
                Some(ProxyRecord::new(parts[2].to_string(), port))
            } else {
                None
            }
        }
        2 => {
            // ip:port
            if let Ok(port) = parts[1].parse::<u16>() {
                Some(ProxyRecord::new(parts[0].to_string(), port))
            } else {
                None
            }
        }
        _ => None,
    }
}

/// 解析json格式的代理记录
fn parse_json_item_to_proxy(item: &Value) -> Option<ProxyRecord> {
    let obj = item.as_object()?;
    
    let ip = obj.get("ip")?.as_str()?.to_string();
    let port = obj.get("port")?;
    
    let port = if let Some(port_num) = port.as_u64() {
        port_num as u16
    } else if let Some(port_str) = port.as_str() {
        port_str.parse().ok()?
    } else {
        return None;
    };

    let mut proxy = ProxyRecord::new(ip, port);

    // 解析可选字段
    if let Some(remote_ip) = obj.get("remote_ip").and_then(|v| v.as_str()) {
        proxy.remote_ip = Some(remote_ip.to_string());
    }
    if let Some(expired_sec) = obj.get("expired_sec").and_then(|v| v.as_i64()) {
        proxy.expired_sec = Some(expired_sec as i32);
    }
    if let Some(expired_abs) = obj.get("expired_abs").and_then(|v| v.as_i64()) {
        proxy.expired_abs = Some(expired_abs);
    }
    if let Some(expired_str) = obj.get("expired_str").and_then(|v| v.as_str()) {
        proxy.expired_str = Some(expired_str.to_string());
    }

    // 过滤已过期代理
    if proxy.is_expired() {
        None
    } else {
        Some(proxy)
    }
}

/// 获取代理列表（含提取API错误分类）
async fn fetch_proxy_list() -> Result<Vec<ProxyRecord>> {
    let mut extract_url = get_extract_url()?;

    // 自动检测格式或使用URL中指定的格式
    let extract_format = if extract_url.contains("format=json") {
        "json"
    } else {
        "line"
    };

    // 确保URL包含格式参数
    if !extract_url.contains("format=") {
        let separator = if extract_url.contains('?') { "&" } else { "?" };
        extract_url = format!("{}{}format={}", extract_url, separator, extract_format);
    }

    let (status, body) = http_get(&extract_url).await?;

    // 提取API链接错误（按官方指引分类）
    if status != 200 {
        let error_msg = match status {
            400 => "提取API链接错误：HTTP 400 → 访问API链接的参数错误；请检查参数。",
            403 => "提取API链接错误：HTTP 403 → 访问API的主机IP不在白名单；请把当前公网IP加入白名单。",
            429 => "提取API链接错误：HTTP 429 → 访问频率过快；请降低提取频率或咨询开通分发模式。",
            _ => return Err(anyhow!("提取API链接错误：HTTP {}", status)),
        };
        return Err(anyhow!("{}", error_msg));
    }

    let mut proxies = Vec::new();

    if extract_format == "line" {
        for line in body.lines() {
            if !line.trim().is_empty() {
                if let Some(proxy) = parse_line_item_to_proxy(line) {
                    if proxy.is_valid() {
                        proxies.push(proxy);
                    }
                }
            }
        }
    } else if extract_format == "json" {
        let data: Value = serde_json::from_str(&body)
            .map_err(|_| anyhow!("JSON格式解析错误"))?;

        // 兼容某些接口返回 {data: [...]} 形式
        let array = if data.is_object() {
            data.get("data")
                .or_else(|| data.get("list"))
                .or_else(|| data.get("proxy_list"))
                .or_else(|| data.get("proxy"))
                .unwrap_or(&data)
        } else {
            &data
        };

        if let Some(items) = array.as_array() {
            for item in items {
                if let Some(proxy) = parse_json_item_to_proxy(item) {
                    if proxy.is_valid() {
                        proxies.push(proxy);
                    }
                }
            }
        }
    }

    if proxies.is_empty() {
        return Err(anyhow!("提取成功但没有可用代理：可能已过期或字段不匹配。"));
    }

    println!("成功解析到 {} 个可用代理", proxies.len());
    Ok(proxies)
}

/// 选择第一个代理
fn pick_first_proxy(proxies: &[ProxyRecord]) -> &ProxyRecord {
    &proxies[0]
}

/// 通过代理访问目标站（含"访问代理错误"分类）
async fn visit_target_via_proxy(proxy: &ProxyRecord, target_url: &str) -> Result<String> {
    const MAX_ATTEMPTS: u32 = 4;

    for attempt in 1..=MAX_ATTEMPTS {
        match http_get_via_proxy(target_url, proxy).await {
            Ok((status, body)) => {
                // 访问代理错误（按官方指引分类）
                match status {
                    200 => return Ok(body),
                    403 => return Err(anyhow!("访问代理错误：HTTP 403 → 访问代理的主机IP不在白名单；请确认当前IP在白名单中。")),
                    408 => return Err(anyhow!("访问代理错误：HTTP 408 → 网速过慢导致超时；请检查带宽/目标站响应并适当增大超时。")),
                    429 => {
                        if attempt < MAX_ATTEMPTS {
                            println!("访问代理错误：HTTP 429 → 访问代理频率过快，正在重试... [第{}次]", attempt);
                            backoff_sleep(attempt).await;
                            continue;
                        } else {
                            return Err(anyhow!("访问代理错误：HTTP 429 → 访问代理频率过快；降低并发与请求频率（建议单请求等待 >300ms）。"));
                        }
                    }
                    504 => return Err(anyhow!("访问代理错误：HTTP 504 → 代理在切换IP或目标站不可达；少量属正常，若频繁请先直连自测目标站并补充 UA/Cookie/Referer。")),
                    _ => return Err(anyhow!("访问代理错误：HTTP {}", status)),
                }
            }
            Err(e) => {
                if e.to_string().contains("超时") && attempt < MAX_ATTEMPTS {
                    println!("请求超时，正在重试... [第{}次]", attempt);
                    backoff_sleep(attempt).await;
                    continue;
                } else {
                    return Err(anyhow!("访问代理错误：{}", e));
                }
            }
        }
    }

    Err(anyhow!("访问代理失败：已达到最大重试次数"))
}

/// 主流程
#[tokio::main]
async fn main() -> Result<()> {
    // 1) 获取代理列表（可能抛出"提取API链接错误"）
    let proxy_list = fetch_proxy_list().await.map_err(|e| {
        eprintln!("ERROR → {}", e);
        e
    })?;

    // 2) 选择第一个代理
    let first_proxy = pick_first_proxy(&proxy_list);
    println!("选择代理: {}:{}", first_proxy.ip, first_proxy.port);

    // 3) 通过该代理访问目标站（可能抛出"访问代理错误"）
    let target_url = get_target_url();
    let body = visit_target_via_proxy(first_proxy, &target_url).await.map_err(|e| {
        eprintln!("ERROR → {}", e);
        e
    })?;

    println!("OK → httpbin 响应：");
    println!("{}", body);

    Ok(())
}

