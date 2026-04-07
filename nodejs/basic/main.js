// 16YUN(亿牛云) Node.js API 提取式 Demo
// 环境变量：
//   EXTRACT_URL: 后台提取链接（如 ?format=line&count=3）
//   TARGET_URL:  访问目标（缺省 https://httpbin.org/ip）

const axios = require('axios');
const { HttpProxyAgent } = require('http-proxy-agent');
const { HttpsProxyAgent } = require('https-proxy-agent');

const extractUrl = process.env.EXTRACT_URL;
const target = process.env.TARGET_URL || 'https://httpbin.org/ip';

async function main() {
  if (!extractUrl) throw new Error('缺少 EXTRACT_URL');
  const response = await axios.get(extractUrl, { timeout: 15000 });
  let data = response.data;

  let proxyInfo;

  // 检测是否为 JSON 格式
  if (typeof data === 'object' && data !== null) {
    // JSON 格式，支持 {proxy: [...]} 等格式
    let proxyList = data.data || data.list || data.proxy_list || data.proxy || data;
    if (Array.isArray(proxyList) && proxyList.length > 0) {
      const item = proxyList[0];
      if (item.ip && item.port) {
        proxyInfo = `${item.ip}:${item.port}`;
      }
    }
  } else {
    // Line 格式
    const lines = String(data).split(/\r?\n/).map((x) => x.trim()).filter(Boolean);
    if (lines.length > 0) {
      proxyInfo = lines[0];
      // 如果是 user:pass:ip:port 格式，提取 ip:port
      const parts = proxyInfo.split(':');
      if (parts.length === 4) {
        proxyInfo = `${parts[2]}:${parts[3]}`;
      }
    }
  }

  if (!proxyInfo) throw new Error('未获取到可用代理');

  // 确保使用正确的 http:// 协议前缀
  const proxyUrl = proxyInfo.startsWith('http') ? proxyInfo : `http://${proxyInfo}`;
  // const httpAgent = new HttpProxyAgent(proxyUrl); // 不再需要
  // const httpsAgent = new HttpsProxyAgent(proxyUrl); // 不再需要

  // 使用标准的 proxy 配置
  const [proxyHost, proxyPort] = proxyInfo.split(':');
  const resp = await axios.get(target, {
    proxy: {
      host: proxyHost.replace(/^http:\/\//, ''),
      port: parseInt(proxyPort, 10)
    },
    timeout: 15000
  });
  console.log(resp.status, resp.data);
}

main().catch((e) => { console.error(e); process.exit(1); });

