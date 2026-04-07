/*
 * 16YUN(亿牛云) C++ API 提取式示例（libcurl 版）
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

#include "json.hpp"
#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdlib>
#include <curl/curl.h>
#include <iostream>
#include <map>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

using json = nlohmann::json;

/**
 * 统一的代理记录结构
 */
/**
 * 统一的代理记录结构
 */
struct ProxyRecord {
  std::string scheme = "http";
  std::string ip;
  int port = 0;
  std::string remote_ip;
  int expired_sec = -1;
  long expired_abs = -1;
  std::string expired_str;

  bool isValid() const { return !ip.empty() && port > 0; }

  bool isExpired() const { return expired_sec >= 0 && expired_sec <= 0; }

  std::string toProxyUrl() const {
    return scheme + "://" + ip + ":" + std::to_string(port);
  }
};

/**
 * HTTP响应结构
 */
struct HttpResponse {
  long status_code = 0;
  std::string body;
};

/**
 * 用于接收curl响应数据的回调函数
 */
static size_t WriteCallback(void *contents, size_t size, size_t nmemb,
                            HttpResponse *response) {
  size_t totalSize = size * nmemb;
  response->body.append(static_cast<char *>(contents), totalSize);
  return totalSize;
}

/**
 * 字符串trim函数
 */
std::string trim(const std::string &str) {
  size_t first = str.find_first_not_of(' ');
  if (first == std::string::npos)
    return "";
  size_t last = str.find_last_not_of(' ');
  return str.substr(first, (last - first + 1));
}

/**
 * 字符串分割函数
 */
std::vector<std::string> split(const std::string &str, char delimiter) {
  std::vector<std::string> tokens;
  std::stringstream ss(str);
  std::string token;
  while (std::getline(ss, token, delimiter)) {
    tokens.push_back(token);
  }
  return tokens;
}

/**
 * 获取环境变量值
 */
std::string getEnv(const std::string &name,
                   const std::string &defaultValue = "") {
  const char *value = std::getenv(name.c_str());
  if (value != nullptr) {
    std::string result = trim(std::string(value));
    return result.empty() ? defaultValue : result;
  }
  return defaultValue;
}

/**
 * 获取提取链接，支持别名
 */
std::string getExtractUrl() {
  std::string extractUrl = getEnv("EXTRACT_URL");
  if (extractUrl.empty()) {
    extractUrl = getEnv("API_EXTRACT_URL");
  }
  if (extractUrl.empty()) {
    throw std::runtime_error("未设置 EXTRACT_URL 环境变量");
  }
  return extractUrl;
}

/**
 * 获取目标URL
 */
std::string getTargetUrl() {
  return getEnv("TARGET_URL", "https://httpbin.org/ip");
}

/**
 * 指数退避重试等待
 */
void backoffSleep(int attempt) {
  int sleepSec =
      std::min(static_cast<int>(std::pow(2, std::max(0, attempt - 1))),
               8); // 1s, 2s, 4s, 8s
  if (sleepSec > 0) {
    std::cout << "等待 " << sleepSec << "s 后重试..." << std::endl;
    std::this_thread::sleep_for(std::chrono::seconds(sleepSec));
  }
}

/**
 * 简单HTTP GET请求，返回HttpResponse
 */
HttpResponse httpGet(const std::string &url) {
  HttpResponse response;
  CURL *curl = curl_easy_init();

  if (!curl) {
    throw std::runtime_error("初始化curl失败");
  }

  curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
  curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, WriteCallback);
  curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);
  curl_easy_setopt(curl, CURLOPT_TIMEOUT, 15L);
  curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 1L);
  curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0L);
  curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 0L);

  CURLcode res = curl_easy_perform(curl);
  if (res != CURLE_OK) {
    curl_easy_cleanup(curl);
    throw std::runtime_error("请求异常: " +
                             std::string(curl_easy_strerror(res)));
  }

  curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &response.status_code);
  curl_easy_cleanup(curl);

  return response;
}

/**
 * 通过代理进行HTTP GET请求
 */
HttpResponse httpGetViaProxy(const std::string &url, const ProxyRecord &proxy) {
  HttpResponse response;
  CURL *curl = curl_easy_init();

  if (!curl) {
    throw std::runtime_error("初始化curl失败");
  }

  curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
  curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, WriteCallback);
  curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);
  curl_easy_setopt(curl, CURLOPT_TIMEOUT, 15L);
  curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 1L);
  curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0L);
  curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 0L);

  // 设置代理
  std::string proxyUrl = proxy.toProxyUrl();
  curl_easy_setopt(curl, CURLOPT_PROXY, proxyUrl.c_str());

  CURLcode res = curl_easy_perform(curl);
  if (res != CURLE_OK) {
    curl_easy_cleanup(curl);
    throw std::runtime_error("请求异常: " +
                             std::string(curl_easy_strerror(res)));
  }

  curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &response.status_code);
  curl_easy_cleanup(curl);

  return response;
}

/**
 * 解析line格式的代理记录
 */
ProxyRecord parseLineItemToProxy(const std::string &line) {
  std::string trimmedLine = trim(line);
  if (trimmedLine.empty()) {
    return ProxyRecord();
  }

  std::vector<std::string> parts = split(trimmedLine, ':');

  if (parts.size() == 4) {
    // username:password:ip:port
    try {
      int port = std::stoi(parts[3]);
      ProxyRecord proxy;
      proxy.ip = parts[2];
      proxy.port = port;
      return proxy;
    } catch (const std::exception &) {
      return ProxyRecord();
    }
  } else if (parts.size() == 2) {
    // ip:port
    try {
      int port = std::stoi(parts[1]);
      ProxyRecord proxy;
      proxy.ip = parts[0];
      proxy.port = port;
      return proxy;
    } catch (const std::exception &) {
      return ProxyRecord();
    }
  }

  return ProxyRecord();
}

/**
 * 解析json格式的代理记录
 */
ProxyRecord parseJsonItemToProxy(const json &item) {
  if (!item.is_object()) {
    return ProxyRecord();
  }

  if (!item.contains("ip") || !item.contains("port")) {
    return ProxyRecord();
  }

  ProxyRecord proxy;
  proxy.ip = item["ip"].get<std::string>();

  if (item["port"].is_number()) {
    proxy.port = item["port"].get<int>();
  } else if (item["port"].is_string()) {
    try {
      proxy.port = std::stoi(item["port"].get<std::string>());
    } catch (const std::exception &) {
      return ProxyRecord();
    }
  } else {
    return ProxyRecord();
  }

  // 解析可选字段
  if (item.contains("remote_ip") && item["remote_ip"].is_string()) {
    proxy.remote_ip = item["remote_ip"].get<std::string>();
  }
  if (item.contains("expired_sec") && item["expired_sec"].is_number()) {
    proxy.expired_sec = item["expired_sec"].get<int>();
  }
  if (item.contains("expired_abs") && item["expired_abs"].is_number()) {
    proxy.expired_abs = item["expired_abs"].get<long>();
  }
  if (item.contains("expired_str") && item["expired_str"].is_string()) {
    proxy.expired_str = item["expired_str"].get<std::string>();
  }

  // 过滤已过期代理
  if (proxy.isExpired()) {
    return ProxyRecord();
  }

  return proxy;
}

/**
 * 获取代理列表（含提取API错误分类）
 */
std::vector<ProxyRecord> fetchProxyList() {
  std::string extractUrl = getExtractUrl();

  // 自动检测格式或使用URL中指定的格式
  std::string extractFormat =
      (extractUrl.find("format=json") != std::string::npos) ? "json" : "line";

  // 确保URL包含格式参数
  if (extractUrl.find("format=") == std::string::npos) {
    std::string separator =
        (extractUrl.find('?') != std::string::npos) ? "&" : "?";
    extractUrl = extractUrl + separator + "format=" + extractFormat;
  }

  HttpResponse response = httpGet(extractUrl);

  // 提取API链接错误（按官方指引分类）
  if (response.status_code != 200) {
    switch (response.status_code) {
    case 400:
      throw std::runtime_error(
          "提取API链接错误：HTTP 400 → 访问API链接的参数错误；请检查参数。");
    case 403:
      throw std::runtime_error(
          "提取API链接错误：HTTP 403 → "
          "访问API的主机IP不在白名单；请把当前公网IP加入白名单。");
    case 429:
      throw std::runtime_error(
          "提取API链接错误：HTTP 429 → "
          "访问频率过快；请降低提取频率或咨询开通分发模式。");
    default:
      throw std::runtime_error("提取API链接错误：HTTP " +
                               std::to_string(response.status_code));
    }
  }

  std::vector<ProxyRecord> proxies;

  if (extractFormat == "line") {
    std::istringstream stream(response.body);
    std::string line;
    while (std::getline(stream, line)) {
      if (!trim(line).empty()) {
        ProxyRecord proxy = parseLineItemToProxy(line);
        if (proxy.isValid()) {
          proxies.push_back(proxy);
        }
      }
    }
  } else if (extractFormat == "json") {
    try {
      json data = json::parse(response.body);

      // 兼容某些接口返回 {data: [...]} 形式
      if (data.is_object()) {
        if (data.contains("data")) {
          data = data["data"];
        } else if (data.contains("list")) {
          data = data["list"];
        } else if (data.contains("proxy_list")) {
          data = data["proxy_list"];
        } else if (data.contains("proxy")) {
          data = data["proxy"];
        }
      }

      if (data.is_array()) {
        for (const auto &item : data) {
          ProxyRecord proxy = parseJsonItemToProxy(item);
          if (proxy.isValid()) {
            proxies.push_back(proxy);
          }
        }
      }
    } catch (const json::exception &) {
      throw std::runtime_error("JSON格式解析错误");
    }
  }

  if (proxies.empty()) {
    throw std::runtime_error(
        "提取成功但没有可用代理：可能已过期或字段不匹配。");
  }

  std::cout << "成功解析到 " << proxies.size() << " 个可用代理" << std::endl;
  return proxies;
}

/**
 * 选择第一个代理
 */
ProxyRecord pickFirstProxy(const std::vector<ProxyRecord> &proxies) {
  return proxies[0];
}

/**
 * 通过代理访问目标站（含"访问代理错误"分类）
 */
std::string visitTargetViaProxy(const ProxyRecord &proxy,
                                const std::string &targetUrl) {
  int maxAttempts = 4;

  for (int attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      HttpResponse response = httpGetViaProxy(targetUrl, proxy);

      // 访问代理错误（按官方指引分类）
      switch (response.status_code) {
      case 200:
        return response.body;
      case 403:
        throw std::runtime_error(
            "访问代理错误：HTTP 403 → "
            "访问代理的主机IP不在白名单；请确认当前IP在白名单中。");
      case 408:
        throw std::runtime_error(
            "访问代理错误：HTTP 408 → "
            "网速过慢导致超时；请检查带宽/目标站响应并适当增大超时。");
      case 429:
        if (attempt < maxAttempts) {
          std::cout
              << "访问代理错误：HTTP 429 → 访问代理频率过快，正在重试... [第"
              << attempt << "次]" << std::endl;
          backoffSleep(attempt);
          continue;
        } else {
          throw std::runtime_error("访问代理错误：HTTP 429 → "
                                   "访问代理频率过快；降低并发与请求频率（建议"
                                   "单请求等待 >300ms）。");
        }
      case 504:
        throw std::runtime_error(
            "访问代理错误：HTTP 504 → "
            "代理在切换IP或目标站不可达；少量属正常，若频繁请先直连自测目标站并"
            "补充 UA/Cookie/Referer。");
      default:
        throw std::runtime_error("访问代理错误：HTTP " +
                                 std::to_string(response.status_code));
      }
    } catch (const std::runtime_error &e) {
      std::string errorMsg = e.what();
      if (errorMsg.find("timeout") != std::string::npos &&
          attempt < maxAttempts) {
        std::cout << "请求超时，正在重试... [第" << attempt << "次]"
                  << std::endl;
        backoffSleep(attempt);
        continue;
      } else {
        throw std::runtime_error("访问代理错误：" + errorMsg);
      }
    }
  }

  throw std::runtime_error("访问代理失败：已达到最大重试次数");
}

/**
 * 主流程
 */
int main() {
  // 初始化curl
  curl_global_init(CURL_GLOBAL_DEFAULT);

  try {
    // 1) 获取代理列表（可能抛出"提取API链接错误"）
    std::vector<ProxyRecord> proxyList = fetchProxyList();

    // 2) 选择第一个代理
    ProxyRecord firstProxy = pickFirstProxy(proxyList);
    std::cout << "选择代理: " << firstProxy.ip << ":" << firstProxy.port
              << std::endl;

    // 3) 通过该代理访问目标站（可能抛出"访问代理错误"）
    std::string targetUrl = getTargetUrl();
    std::string body = visitTargetViaProxy(firstProxy, targetUrl);

    std::cout << "OK → httpbin 响应：" << std::endl;
    std::cout << body << std::endl;

    // 清理curl
    curl_global_cleanup();
    return 0;

  } catch (const std::exception &e) {
    std::cerr << "ERROR → " << e.what() << std::endl;
    curl_global_cleanup();
    return 1;
  }
}
