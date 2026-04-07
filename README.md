## 16YUN(亿牛云) API 代理使用说明

本说明文档基于以下官方文档整理、合并而成，并补充了 Python 场景的实践要点与示例代码：

* 接入示例: [https://www.16yun.cn/help/ss\_demo/](https://www.16yun.cn/help/ss_demo/)
* 常见问题(爬虫代理): [https://www.16yun.cn/help/s\_fq/](https://www.16yun.cn/help/s_fq/)
* 购买/开通与使用引导: [https://www.16yun.cn/help/nu\_demo/](https://www.16yun.cn/help/nu_demo/)
* 隧道转发与细节说明: [https://www.16yun.cn/help/ss\_detail/](https://www.16yun.cn/help/ss_detail/)

---

### 一、API 代理与隧道型代理的区别（概览）

* **API 提取式**：周期性访问“提取链接”获取一批代理，客户端**自管**验证、缓存、分发与切换策略；适合“固定拉取列表 + 本地池管理”。
* **隧道型代理**：固定域名/端口 + 认证，云端做池管理与均衡，支持 `Proxy-Tunnel` 精确控制切换/保持；客户端逻辑更简单。

> 本文件聚焦“API 提取式”。如需隧道接入，请参考仓库内的 `tunnel-proxy/README.md` 与对应语言 demo 目录。

---

### 二、准备与开通

1. 注册登录并开通合适的 API 代理产品。
2. 在后台获取“**代理提取链接**”（支持 `line/json` 格式）。
3. 如产品需要白名单，在“当前代理/白名单”把本机**公网 IP**加入。

参考：[购买/开通与使用引导](https://www.16yun.cn/help/nu_demo/)

---

### 三、提取链接与请求参数

* 常见参数（以后台为准）：

  * `format`：`line`(默认) / `json`
  * `part`：换行分隔符（line 模式，默认 `\r\n`）
  * `count`：每次提取数量

#### 3.1 返回格式与字段

* **line 模式**（形态）

  1. **免认证代理**：`ip:port`

* **json 模式**（常见字段）

  | 字段            | 说明              |
  | ------------- | --------------- |
  | `ip`          | 代理 IP（使用时请取该字段） |
  | `port`        | 代理端口（使用时请取该字段）  |
  | `remote_ip`   | 访问目标站所见真实 IP    |
  | `expired_sec` | 从提取到过期的剩余秒数     |
  | `expired_abs` | 过期的 UNIX 时间戳    |
  | `expired_str` | 东八区时间的过期时间字符串   |

> 注：不同套餐/接口字段可能略有差异，请以后台文档为准。**建议根据 `expired_sec` 过滤已过期或即将过期的条目**。

---

### 四、Python 快速示例

> 以下示例仅演示核心流程，生产环境请加入重试（如指数退避）、日志与超时收敛。

#### 4.1 拉取 `line` 并解析

```python
#!/usr/bin/env python3
import requests

EXTRACT_URL = "<后台提取链接>?format=line&count=5"  # 示例

def parse_line_item(line: str):
    """
    支持格式：ip:port
    返回 dict: {ip, port}
    """
    parts = line.strip().split(":")
    if len(parts) == 2:
        ip, port = parts
        return {"ip": ip, "port": int(port)}
    return None

resp = requests.get(EXTRACT_URL, timeout=15)
resp.raise_for_status()
proxies = []
for ln in resp.text.splitlines():
    if not ln.strip():
        continue
    item = parse_line_item(ln)
    if item:
        proxies.append(item)

print(proxies)
# 例: [{'ip': '1.2.3.4', 'port': 1234}, {'ip':'5.6.7.8','port':8080}]
```

#### 4.2 拉取 `json` 并解析为统一结构

```python
#!/usr/bin/env python3
import requests

EXTRACT_URL = "<后台提取链接>?format=json&count=5"  # 示例

data = requests.get(EXTRACT_URL, timeout=15).json()
proxies = []
for item in data:
    ip = item.get("ip")
    port = item.get("port")
    if not ip or not port:
        continue
    # 可选：丢弃已过期
    expired_sec = item.get("expired_sec")
    if expired_sec is not None and expired_sec <= 0:
        continue

    proxies.append({
        "ip": ip,
        "port": int(port),
        "remote_ip": item.get("remote_ip"),
        "expired_sec": expired_sec,
        "expired_abs": item.get("expired_abs"),
        "expired_str": item.get("expired_str"),
    })

print(proxies)
```

#### 4.3 端到端最小示例：获取列表 → 取第一个 → 通过代理访问 `https://httpbin.org/ip`

```python
#!/usr/bin/env python3
import requests
from typing import Dict, Any, List, Optional

EXTRACT_URL_BASE = "<后台提取链接>"   # 不含 format/count
EXTRACT_FORMAT   = "json"          # "json" 或 "line"
EXTRACT_COUNT    = 5
TARGET_URL       = "https://httpbin.org/ip"
TIMEOUT          = 15

def build_extract_url(base: str, fmt: str, count: int) -> str:
    return f"{base}?format={fmt}&count={count}"

def fetch_proxy_list() -> List[Dict[str, Any]]:
    """仅负责拉取与解析，并将错误码按“提取API链接错误”归类"""
    url = build_extract_url(EXTRACT_URL_BASE, EXTRACT_FORMAT, EXTRACT_COUNT)
    r = requests.get(url, timeout=TIMEOUT)

    # —— 提取API链接错误（按官方指引） ——
    if r.status_code != 200:
        if r.status_code == 400:
            raise SystemExit("提取API链接错误：HTTP 400 → 参数错误，请检查访问API链接的参数")
        if r.status_code == 403:
            raise SystemExit("提取API链接错误：HTTP 403 → 当前主机IP未在白名单，请添加到白名单")
        if r.status_code == 429:
            raise SystemExit("提取API链接错误：HTTP 429 → 访问频率过快，请降低提取频率或联系开通分发模式")
        raise SystemExit(f"提取API链接错误：HTTP {r.status_code}")

    if EXTRACT_FORMAT == "line":
        proxies = []
        for ln in r.text.splitlines():
            ln = ln.strip()
            if not ln:
                continue
            parts = ln.split(":")
            if len(parts) == 2:
                ip, port = parts
                proxies.append({"ip": ip, "port": int(port)})
        if not proxies:
            raise SystemExit("提取成功但没有可用代理（line）")
        return proxies

    else:  # json
        arr = r.json()
        proxies = []
        for it in arr:
            ip, port = it.get("ip"), it.get("port")
            if not ip or not port:
                continue
            expired_sec = it.get("expired_sec")
            if expired_sec is not None and expired_sec <= 0:
                continue
            proxies.append({
                "ip": ip,
                "port": int(port),
                "remote_ip": it.get("remote_ip"),
                "expired_sec": expired_sec,
            })
        if not proxies:
            raise SystemExit("提取成功但没有可用代理（json）")
        return proxies

def visit_via_proxy(proxy: Dict[str, Any]) -> str:
    """使用单个代理访问目标站，并将错误码按“访问代理错误”归类"""
    ip, port = proxy["ip"], proxy["port"]
    
    proxy_url = f"http://{ip}:{port}"
    ps = {"http": proxy_url, "https": proxy_url}

    try:
        resp = requests.get(TARGET_URL, proxies=ps, timeout=TIMEOUT)
    except requests.Timeout:
        raise SystemExit("访问代理错误：HTTP 408（等价）→ 网速过慢导致请求超时，请检查带宽/目标站响应")
    except requests.RequestException as e:
        # 网络抖动/连接失败等，这里做统一兜底
        raise SystemExit(f"访问代理错误：连接异常 → {e}")

    # —— 访问代理错误（按官方指引） ——
    if resp.status_code == 200:
        return resp.text
    if resp.status_code == 403:
        raise SystemExit("访问代理错误：HTTP 403 → 访问代理的主机IP不在白名单，请确认白名单设置")
    if resp.status_code == 408:
        raise SystemExit("访问代理错误：HTTP 408 → 网速过慢导致超时，请检查带宽/目标站响应或增大超时")
    if resp.status_code == 429:
        raise SystemExit("访问代理错误：HTTP 429 → 访问代理频率过快，降低并发并在每次请求间增加 >300ms 的等待")
    if resp.status_code == 504:
        raise SystemExit("访问代理错误：HTTP 504 → 代理在切换IP或目标站不可达；少量属正常，若频繁请直连自测目标站并补充UA/Referer/Cookie")
    raise SystemExit(f"访问代理错误：HTTP {resp.status_code}")

def main():
    proxies = fetch_proxy_list()       # 1) 获取代理列表（可能触发“提取API链接错误”）
    first = proxies[0]                 # 2) 选择第一个代理
    body = visit_via_proxy(first)      # 3) 通过该代理访问 httpbin（可能触发“访问代理错误”）
    print("OK → httpbin 响应：\n", body)

if __name__ == "__main__":
    main()
```

---

### 五、并发与可用性建议

* **频率与并发控制**：若出现 `429 Too Many Requests`，降低提取/访问频率与并发；**单请求之间建议等待 >300ms**。
* **重试与退避**：对超时/连接错误做**指数退避**（如 `0.5s → 1s → 2s`），最大重试次数与全局 QPS 需受控。
* **过期过滤**：优先使用 `expired_sec` 较大的代理；对即将过期（如 `<15s`）的代理谨慎使用。
* **目标站策略**：合理设置 `User-Agent`、`Referer`、`Cookie`，按需维持会话；必要时引入随机抖动与连接池上限。

---

### 六、错误码与排障（按类别）

#### 6.1 提取 API 链接错误

| 错误       | 原因            | 解决方法                                 |
| -------- | ------------- | ------------------------------------ |
| HTTP 400 | 访问 API 链接参数错误 | 检查 `format`/`count`/`part` 等参数是否正确   |
| HTTP 403 | 当前主机 IP 不在白名单 | 将当前公网 IP 添加到订单白名单；或开启自动更新白名单（遵守频率限制） |
| HTTP 429 | 访问 API 链接频率过快 | 降低提取频率；或联系支持开启**分发模式**               |

#### 6.2 访问代理错误

| 错误       | 原因               | 解决方法                                                                |
| -------- | ---------------- | ------------------------------------------------------------------- |
| HTTP 403 | 访问代理的主机 IP 不在白名单 | 确认本机公网 IP 在白名单中（不同订单白名单独立）                                          |
| HTTP 408 | 网速过慢导致请求超时       | 检查带宽与目标站响应时间；适当增大超时并减少并发                                            |
| HTTP 429 | 访问代理频率过快         | 减少线程数/并发；为每次请求加入等待（**建议 >300ms**）                                   |
| HTTP 504 | 代理切换 IP 或目标站不可达  | 少量 504 属正常；若频繁出现，请在**不使用代理**情况下自测目标站可达，并补充 `UA/Referer/Cookie` 等请求头 |

> 说明：不同语言/HTTP 库在代理认证失败时可能表现不一；若遇到库返回的其他码（如连接异常），请结合日志与抓包定位。

更多说明见：[常见问题(爬虫代理)](https://www.16yun.cn/help/s_fq/)

---

### 七、与隧道代理的互补

* 若希望**最小化本地池管理**或需要**会话级保持/切换**能力，请考虑隧道代理，参考 `../tunnel-proxy/README.md`。
