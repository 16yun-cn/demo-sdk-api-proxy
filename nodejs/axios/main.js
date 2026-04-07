// 16YUN(亿牛云) Node.js API 提取式 Demo（axios 版）
//
// 按照伪代码规范实现：
// - 统一的 ProxyRecord 结构
// - 支持 line 和 json 两种格式解析
// - 规范的错误处理和中文提示
// - 指数退避重试机制
// - 单代理认证信息解析
//
// 环境变量：
// - EXTRACT_URL：后台"代理提取链接"（必需）
// - TARGET_URL：目标网站（可选，默认 https://httpbin.org/ip）

const axios = require('axios');
const { HttpProxyAgent } = require('http-proxy-agent');
const { HttpsProxyAgent } = require('https-proxy-agent');

/**
 * 统一的代理记录结构
 */
class ProxyRecord {
  constructor({
    scheme = 'http',
    ip = '',
    port = 0,
    remote_ip = null,
    expired_sec = null,
    expired_abs = null,
    expired_str = null
  } = {}) {
    this.scheme = scheme;
    this.ip = ip;
    this.port = port;
    this.remote_ip = remote_ip;
    this.expired_sec = expired_sec;
    this.expired_abs = expired_abs;
    this.expired_str = expired_str;
  }

  isValid() {
    return Boolean(this.ip && this.port > 0);
  }

  isExpired() {
    return this.expired_sec !== null && this.expired_sec <= 0;
  }

  toProxyUrl() {
    return `${this.scheme}://${this.ip}:${this.port}`;
  }
}

/**
 * 获取环境变量值
 */
function getEnv(name, defaultValue = null) {
  const value = process.env[name];
  if (value && typeof value === 'string') {
    const trimmed = value.trim();
    return trimmed || null;
  }
  return defaultValue;
}

/**
 * 获取提取链接，支持别名
 */
function getExtractUrl() {
  const extractUrl = getEnv('EXTRACT_URL') || getEnv('API_EXTRACT_URL');
  if (!extractUrl) {
    throw new Error('未设置 EXTRACT_URL 环境变量');
  }
  return extractUrl;
}

/**
 * 获取目标URL
 */
function getTargetUrl() {
  return getEnv('TARGET_URL') || 'https://httpbin.org/ip';
}

/**
 * 指数退避重试等待
 */
function backoffSleep(attempt) {
  const sleepSec = Math.min(2 ** Math.max(0, attempt - 1), 8); // 1s, 2s, 4s, 8s
  if (sleepSec > 0) {
    console.log(`等待 ${sleepSec}s 后重试...`);
    return new Promise(resolve => setTimeout(resolve, sleepSec * 1000));
  }
  return Promise.resolve();
}

/**
 * 简单HTTP GET请求，返回[状态码, 响应体]
 */
async function httpGet(url, timeout = 15000) {
  try {
    const response = await axios.get(url, { timeout });
    return [response.status, response.data];
  } catch (error) {
    if (error.code === 'ECONNABORTED') {
      throw new Error('请求超时');
    }
    throw new Error(`请求异常: ${error.message}`);
  }
}

/**
 * 通过代理进行HTTP GET请求
 */
async function httpGetViaProxy(url, proxy, timeout = 15000) {
  try {
    const resp = await axios.get(url, {
      proxy: {
        host: proxy.ip,
        port: proxy.port
      },
      timeout: timeout
    });

    return [resp.status, resp.data];
  } catch (error) {
    if (error.code === 'ECONNABORTED') {
      throw new Error('请求超时');
    }
    throw new Error(`请求异常: ${error.message}`);
  }
}

/**
 * 解析line格式的代理记录
 */
function parseLineItemToProxy(line) {
  line = line.trim();
  if (!line) return null;

  const parts = line.split(':');
  if (parts.length === 4) {
    // username:password:ip:port
    const [username, password, ip, portStr] = parts;
    const port = parseInt(portStr, 10);
    if (isNaN(port)) return null;
    return new ProxyRecord({ ip, port });
  } else if (parts.length === 2) {
    // ip:port
    const [ip, portStr] = parts;
    const port = parseInt(portStr, 10);
    if (isNaN(port)) return null;
    return new ProxyRecord({ ip, port });
  }
  return null;
}

/**
 * 解析json格式的代理记录
 */
function parseJsonItemToProxy(item) {
  if (!item || typeof item !== 'object') return null;

  const { ip, port } = item;
  if (!ip || !port) return null;

  const portNum = parseInt(port, 10);
  if (isNaN(portNum)) return null;

  const proxy = new ProxyRecord({
    ip,
    port: portNum,
    remote_ip: item.remote_ip || null,
    expired_sec: item.expired_sec || null,
    expired_abs: item.expired_abs || null,
    expired_str: item.expired_str || null
  });

  // 过滤已过期代理
  if (proxy.isExpired()) return null;

  return proxy;
}

/**
 * 获取代理列表（含提取API错误分类）
 */
async function fetchProxyList() {
  let extractUrl = getExtractUrl();

  // 自动检测格式或使用URL中指定的格式
  const extractFormat = extractUrl.includes('format=json') ? 'json' : 'line';

  // 确保URL包含格式参数
  if (!extractUrl.includes('format=')) {
    const separator = extractUrl.includes('?') ? '&' : '?';
    extractUrl = `${extractUrl}${separator}format=${extractFormat}`;
  }

  const [status, body] = await httpGet(extractUrl);

  // 提取API链接错误（按官方指引分类）
  if (status !== 200) {
    if (status === 400) {
      throw new Error('提取API链接错误：HTTP 400 → 访问API链接的参数错误；请检查参数。');
    } else if (status === 403) {
      throw new Error('提取API链接错误：HTTP 403 → 访问API的主机IP不在白名单；请把当前公网IP加入白名单。');
    } else if (status === 429) {
      throw new Error('提取API链接错误：HTTP 429 → 访问频率过快；请降低提取频率或咨询开通分发模式。');
    } else {
      throw new Error(`提取API链接错误：HTTP ${status}`);
    }
  }

  const proxies = [];

  if (extractFormat === 'line') {
    const lines = String(body).split(/\r?\n/);
    for (const line of lines) {
      if (line.trim()) {
        const proxy = parseLineItemToProxy(line);
        if (proxy && proxy.isValid()) {
          proxies.push(proxy);
        }
      }
    }
  } else if (extractFormat === 'json') {
    try {
      let data = typeof body === 'string' ? JSON.parse(body) : body;

      // 兼容某些接口返回 {data: [...]} 形式
      if (data && typeof data === 'object' && !Array.isArray(data)) {
        data = data.data || data.list || data.proxy_list || data.proxy || data;
      }

      if (Array.isArray(data)) {
        for (const item of data) {
          const proxy = parseJsonItemToProxy(item);
          if (proxy && proxy.isValid()) {
            proxies.push(proxy);
          }
        }
      }
    } catch (error) {
      throw new Error('JSON格式解析错误');
    }
  }

  if (proxies.length === 0) {
    throw new Error('提取成功但没有可用代理：可能已过期或字段不匹配。');
  }

  console.log(`成功解析到 ${proxies.length} 个可用代理`);
  return proxies;
}

/**
 * 选择第一个代理
 */
function pickFirstProxy(proxies) {
  return proxies[0];
}

/**
 * 通过代理访问目标站（含"访问代理错误"分类）
 */
async function visitTargetViaProxy(proxy, targetUrl) {
  const maxAttempts = 4;

  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      const [status, body] = await httpGetViaProxy(targetUrl, proxy);

      // 访问代理错误（按官方指引分类）
      if (status === 200) {
        return body;
      } else if (status === 403) {
        throw new Error('访问代理错误：HTTP 403 → 访问代理的主机IP不在白名单；请确认当前IP在白名单中。');
      } else if (status === 408) {
        throw new Error('访问代理错误：HTTP 408 → 网速过慢导致超时；请检查带宽/目标站响应并适当增大超时。');
      } else if (status === 429) {
        if (attempt < maxAttempts) {
          console.warn(`访问代理错误：HTTP 429 → 访问代理频率过快，正在重试... [第${attempt}次]`);
          await backoffSleep(attempt);
          continue;
        } else {
          throw new Error('访问代理错误：HTTP 429 → 访问代理频率过快；降低并发与请求频率（建议单请求等待 >300ms）。');
        }
      } else if (status === 504) {
        throw new Error('访问代理错误：HTTP 504 → 代理在切换IP或目标站不可达；少量属正常，若频繁请先直连自测目标站并补充 UA/Cookie/Referer。');
      } else {
        throw new Error(`访问代理错误：HTTP ${status}`);
      }
    } catch (error) {
      if (error.message.includes('超时') && attempt < maxAttempts) {
        console.warn(`请求超时，正在重试... [第${attempt}次]`);
        await backoffSleep(attempt);
        continue;
      } else {
        throw new Error(`访问代理错误：${error.message}`);
      }
    }
  }

  throw new Error('访问代理失败：已达到最大重试次数');
}

/**
 * 主流程
 */
async function main() {
  try {
    // 1) 获取代理列表（可能抛出"提取API链接错误"）
    const proxyList = await fetchProxyList();

    // 2) 选择第一个代理
    const firstProxy = pickFirstProxy(proxyList);
    console.log(`选择代理: ${firstProxy.ip}:${firstProxy.port}`);

    // 3) 通过该代理访问目标站（可能抛出"访问代理错误"）
    const targetUrl = getTargetUrl();
    const body = await visitTargetViaProxy(firstProxy, targetUrl);

    console.log('OK → httpbin 响应：');
    console.log(body);
    return 0;
  } catch (error) {
    console.error(`ERROR → ${error.message}`);
    return 1;
  }
}

// 运行主程序
if (require.main === module) {
  main().then(code => process.exit(code));
}
