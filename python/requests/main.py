#!/usr/bin/env python3
"""
16YUN(亿牛云) API 提取式示例（requests 版）

按照伪代码规范实现：
- 统一的 ProxyRecord 结构
- 支持 line 和 json 两种格式解析
- 规范的错误处理和中文提示
- 指数退避重试机制
- 单代理认证信息解析

环境变量：
- EXTRACT_URL：后台"代理提取链接"（必需）
- TARGET_URL：目标网站（可选，默认 https://httpbin.org/ip）
"""

from __future__ import annotations

import json
import logging
import os
import random
import sys
import time
from dataclasses import dataclass
from typing import List, Optional

import requests
from requests.auth import HTTPProxyAuth


@dataclass
class ProxyRecord:
    """统一的代理记录结构"""
    scheme: str = "http"
    ip: str = ""
    port: int = 0
    remote_ip: Optional[str] = None
    expired_sec: Optional[int] = None
    expired_abs: Optional[int] = None
    expired_str: Optional[str] = None

    def is_valid(self) -> bool:
        """检查代理记录是否有效"""
        return bool(self.ip and self.port > 0)
    
    def is_expired(self) -> bool:
        """检查代理是否已过期"""
        return self.expired_sec is not None and self.expired_sec <= 0
    
    def to_proxy_url(self) -> str:
        """转换为代理URL"""
        return f"{self.scheme}://{self.ip}:{self.port}"


def setup_logging() -> None:
    """设置日志格式"""
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
    )


def get_env(name: str, default: Optional[str] = None) -> Optional[str]:
    """获取环境变量值"""
    value = os.getenv(name, default)
    if value is not None and isinstance(value, str):
        value = value.strip()
        if not value:  # 空字符串视为None
            return None
    return value


def get_extract_url() -> str:
    """获取提取链接，支持别名"""
    extract_url = get_env("EXTRACT_URL") or get_env("API_EXTRACT_URL")
    if not extract_url:
        raise ValueError("未设置 EXTRACT_URL 环境变量")
    return extract_url


def get_target_url() -> str:
    """获取目标URL"""
    return get_env("TARGET_URL") or "https://httpbin.org/ip"


def backoff_sleep(attempt: int) -> None:
    """指数退避重试等待"""
    sleep_sec = min(2 ** max(0, attempt - 1), 8)  # 1s, 2s, 4s, 8s
    if sleep_sec > 0:
        logging.info(f"等待 {sleep_sec}s 后重试...")
        time.sleep(sleep_sec)


def http_get(url: str, timeout: int = 15) -> tuple[int, str]:
    """简单HTTP GET请求，返回(状态码, 响应体)"""
    try:
        resp = requests.get(url, timeout=timeout)
        return resp.status_code, resp.text
    except requests.Timeout:
        raise requests.exceptions.RequestException("请求超时")
    except Exception as e:
        raise requests.exceptions.RequestException(f"请求异常: {e}")


def http_get_via_proxy(url: str, proxy: ProxyRecord, timeout: int = 15) -> tuple[int, str]:
    """通过代理进行HTTP GET请求"""
    proxy_url = proxy.to_proxy_url()
    proxies = {"http": proxy_url, "https": proxy_url}
    
    try:
        resp = requests.get(url, proxies=proxies, timeout=timeout)
        return resp.status_code, resp.text
    except requests.Timeout:
        raise requests.exceptions.RequestException("请求超时")
    except Exception as e:
        raise requests.exceptions.RequestException(f"请求异常: {e}")


def parse_line_item_to_proxy(line: str) -> Optional[ProxyRecord]:
    """解析line格式的代理记录"""
    line = line.strip()
    if not line:
        return None
    
    parts = line.split(":")
    if len(parts) == 4:
        # username:password:ip:port -> 提取 ip:port
        _, _, ip, port_str = parts
        try:
            port = int(port_str)
            return ProxyRecord(ip=ip, port=port)
        except ValueError:
            return None
    elif len(parts) == 2:
        # ip:port
        ip, port_str = parts
        try:
            port = int(port_str)
            return ProxyRecord(ip=ip, port=port)
        except ValueError:
            return None
    else:
        return None


def parse_json_item_to_proxy(item: dict) -> Optional[ProxyRecord]:
    """解析json格式的代理记录"""
    if not isinstance(item, dict):
        return None
    
    ip = item.get("ip")
    port = item.get("port")
    if not ip or not port:
        return None
    
    try:
        port = int(port)
    except (ValueError, TypeError):
        return None
    
    proxy = ProxyRecord(
        ip=ip,
        port=port,
        remote_ip=item.get("remote_ip"),
        expired_sec=item.get("expired_sec"),
        expired_abs=item.get("expired_abs"),
        expired_str=item.get("expired_str")
    )
    
    # 过滤已过期代理
    if proxy.is_expired():
        return None
    
    return proxy


def fetch_proxy_list() -> List[ProxyRecord]:
    """获取代理列表（含提取API错误分类）"""
    extract_url = get_extract_url()
    
    # 自动检测格式或使用URL中指定的格式
    extract_format = "json" if "format=json" in extract_url else "line"
    
    # 确保URL包含格式参数
    if "format=" not in extract_url:
        separator = "&" if "?" in extract_url else "?"
        extract_url = f"{extract_url}{separator}format={extract_format}"
    
    status, body = http_get(extract_url)
    
    # 提取API链接错误（按官方指引分类）
    if status != 200:
        if status == 400:
            raise Exception("提取API链接错误：HTTP 400 → 访问API链接的参数错误；请检查参数。")
        elif status == 403:
            raise Exception("提取API链接错误：HTTP 403 → 访问API的主机IP不在白名单；请把当前公网IP加入白名单。")
        elif status == 429:
            raise Exception("提取API链接错误：HTTP 429 → 访问频率过快；请降低提取频率或咨询开通分发模式。")
        else:
            raise Exception(f"提取API链接错误：HTTP {status}")
    
    proxies = []
    
    if extract_format == "line":
        for line in body.splitlines():
            if line.strip():
                proxy = parse_line_item_to_proxy(line)
                if proxy and proxy.is_valid():
                    proxies.append(proxy)
    elif extract_format == "json":
        try:
            data = json.loads(body)
            if isinstance(data, dict):
                # 兼容某些接口返回 {data: [...]} 形式
                data = data.get("data") or data.get("list") or data.get("proxy_list") or data.get("proxy") or data
            
            if isinstance(data, list):
                for item in data:
                    proxy = parse_json_item_to_proxy(item)
                    if proxy and proxy.is_valid():
                        proxies.append(proxy)
        except json.JSONDecodeError:
            raise Exception("JSON格式解析错误")
    
    if not proxies:
        raise Exception("提取成功但没有可用代理：可能已过期或字段不匹配。")
    
    logging.info(f"成功解析到 {len(proxies)} 个可用代理")
    return proxies


def pick_first_proxy(proxies: List[ProxyRecord]) -> ProxyRecord:
    """选择第一个代理"""
    return proxies[0]


def visit_target_via_proxy(proxy: ProxyRecord, target_url: str) -> str:
    """通过代理访问目标站（含"访问代理错误"分类）"""
    max_attempts = 4
    
    for attempt in range(1, max_attempts + 1):
        try:
            status, body = http_get_via_proxy(target_url, proxy)
            
            # 访问代理错误（按官方指引分类）
            if status == 200:
                return body
            elif status == 403:
                raise Exception("访问代理错误：HTTP 403 → 访问代理的主机IP不在白名单；请确认当前IP在白名单中。")
            elif status == 408:
                raise Exception("访问代理错误：HTTP 408 → 网速过慢导致超时；请检查带宽/目标站响应并适当增大超时。")
            elif status == 429:
                if attempt < max_attempts:
                    logging.warning(f"访问代理错误：HTTP 429 → 访问代理频率过快，正在重试... [第{attempt}次]")
                    backoff_sleep(attempt)
                    continue
                else:
                    raise Exception("访问代理错误：HTTP 429 → 访问代理频率过快；降低并发与请求频率（建议单请求等待 >300ms）。")
            elif status == 504:
                raise Exception("访问代理错误：HTTP 504 → 代理在切换IP或目标站不可达；少量属正常，若频繁请先直连自测目标站并补充 UA/Cookie/Referer。")
            else:
                raise Exception(f"访问代理错误：HTTP {status}")
                
        except requests.exceptions.RequestException as e:
            if "超时" in str(e) and attempt < max_attempts:
                logging.warning(f"请求超时，正在重试... [第{attempt}次]")
                backoff_sleep(attempt)
                continue
            else:
                raise Exception(f"访问代理错误：{e}")
    
    raise Exception("访问代理失败：已达到最大重试次数")


def main() -> int:
    """主流程"""
    setup_logging()
    
    try:
        # 1) 获取代理列表（可能抛出"提取API链接错误"）
        proxy_list = fetch_proxy_list()
        
        # 2) 选择第一个代理
        first_proxy = pick_first_proxy(proxy_list)
        logging.info(f"选择代理: {first_proxy.ip}:{first_proxy.port}")
        
        # 3) 通过该代理访问目标站（可能抛出"访问代理错误"）
        target_url = get_target_url()
        body = visit_target_via_proxy(first_proxy, target_url)
        
        print("OK → httpbin 响应：")
        print(body)
        return 0
        
    except Exception as e:
        logging.error(f"ERROR → {e}")
        return 1


if __name__ == "__main__":
    sys.exit(main())

