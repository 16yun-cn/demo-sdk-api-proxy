#!/usr/bin/env python3
"""
16YUN(亿牛云) API 提取式示例（urllib 版）
"""

import json
import os
import random
import sys
import time
import urllib.error
import urllib.request
from typing import List, Optional


def get_env(name: str, default: Optional[str] = None) -> Optional[str]:
    value = os.getenv(name, default)
    if value is not None and isinstance(value, str):
        value = value.strip()
    return value


def http_get(url: str, timeout: int = 15) -> bytes:
    # 不强制请求压缩编码，避免服务端返回 gzip 导致解析失败
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def fetch_line(url: str) -> List[str]:
    body = http_get(url)
    lines = [ln.strip() for ln in body.decode("utf-8", errors="ignore").splitlines() if ln.strip()]
    proxies = []
    for ln in lines:
        parts = ln.split(":")
        if len(parts) == 4:
            proxies.append(f"http://{parts[2]}:{parts[3]}")
        elif len(parts) == 2:
            proxies.append(f"http://{ln}")
    return proxies


def fetch_json(url: str) -> List[str]:
    body = http_get(url)
    data = json.loads(body.decode("utf-8", errors="ignore"))
    if isinstance(data, dict):
        data = data.get("data") or data.get("list") or data.get("proxy_list") or data.get("proxy") or data
    proxies: List[str] = []
    for item in (data or []):
        ip = (item or {}).get("ip")
        port = (item or {}).get("port")
        if ip and port:
            proxies.append(f"http://{ip}:{port}")
    return proxies


def try_proxy(proxy: str, timeout: int = 15) -> bool:
    target_url = get_env("TARGET_URL", "https://httpbin.org/ip")
    ph = urllib.request.ProxyHandler({"http": proxy, "https": proxy})
    opener = urllib.request.build_opener(ph)
    try:
        with opener.open(target_url, timeout=timeout) as resp:
            print("OK", proxy, resp.read()[:200])
            return True
    except urllib.error.HTTPError as he:  # 4xx/5xx
        if he.code in (401, 403, 407):
            print("认证/白名单问题:", he.code)
        else:
            print("HTTP 错误:", he.code)
    except urllib.error.URLError as e:
        print("URL 错误:", e)
    return False


def main() -> int:
    extract_url = get_env("EXTRACT_URL")
    if not extract_url:
        print("未设置 EXTRACT_URL")
        return 2

    proxies: List[str] = []
    try:
        if "format=json" in extract_url:
            proxies = fetch_json(extract_url)
        elif "format=line" in extract_url:
            proxies = fetch_line(extract_url)
        else:
            try:
                proxies = fetch_line(extract_url + ("&" if "?" in extract_url else "?") + "format=line")
            except Exception:
                proxies = fetch_json(extract_url + ("&" if "?" in extract_url else "?") + "format=json")
    except Exception as e:
        print("提取失败:", e)
        return 3

    if not proxies:
        print("未获取到代理")
        return 4

    random.shuffle(proxies)
    for p in proxies[:5]:
        if try_proxy(p):
            break
    return 0


if __name__ == "__main__":
    sys.exit(main())
