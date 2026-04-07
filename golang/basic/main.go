// 16YUN(亿牛云) Go API 提取式 Demo
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

package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"math"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

// ProxyRecord 统一的代理记录结构
type ProxyRecord struct {
	Scheme     string `json:"scheme"`
	IP         string `json:"ip"`
	Port       int    `json:"port"`
	RemoteIP   string `json:"remote_ip,omitempty"`
	ExpiredSec *int   `json:"expired_sec,omitempty"`
	ExpiredAbs *int64 `json:"expired_abs,omitempty"`
	ExpiredStr string `json:"expired_str,omitempty"`
}

// IsValid 检查代理记录是否有效
func (p *ProxyRecord) IsValid() bool {
	return p.IP != "" && p.Port > 0
}

// IsExpired 检查代理是否已过期
func (p *ProxyRecord) IsExpired() bool {
	return p.ExpiredSec != nil && *p.ExpiredSec <= 0
}

// ToProxyURL 转换为代理URL
func (p *ProxyRecord) ToProxyURL() string {
	return fmt.Sprintf("%s://%s:%d", p.Scheme, p.IP, p.Port)
}

// getEnv 获取环境变量值
func getEnv(name, defaultValue string) string {
	value := os.Getenv(name)
	if value != "" {
		value = strings.TrimSpace(value)
		if value != "" {
			return value
		}
	}
	return defaultValue
}

// getExtractURL 获取提取链接，支持别名
func getExtractURL() string {
	extractURL := getEnv("EXTRACT_URL", "")
	if extractURL == "" {
		extractURL = getEnv("API_EXTRACT_URL", "")
	}
	if extractURL == "" {
		log.Fatal("未设置 EXTRACT_URL 环境变量")
	}
	return extractURL
}

// getTargetURL 获取目标URL
func getTargetURL() string {
	return getEnv("TARGET_URL", "https://httpbin.org/ip")
}

// backoffSleep 指数退避重试等待
func backoffSleep(attempt int) {
	sleepSec := int(math.Min(math.Pow(2, math.Max(0, float64(attempt-1))), 8)) // 1s, 2s, 4s, 8s
	if sleepSec > 0 {
		log.Printf("等待 %ds 后重试...", sleepSec)
		time.Sleep(time.Duration(sleepSec) * time.Second)
	}
}

// httpGet 简单HTTP GET请求，返回(状态码, 响应体)
func httpGet(url string) (int, string, error) {
	client := &http.Client{Timeout: 15 * time.Second}
	resp, err := client.Get(url)
	if err != nil {
		return 0, "", fmt.Errorf("请求异常: %v", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return resp.StatusCode, "", fmt.Errorf("读取响应失败: %v", err)
	}

	return resp.StatusCode, string(body), nil
}

// httpGetViaProxy 通过代理进行HTTP GET请求
func httpGetViaProxy(targetURL string, proxy *ProxyRecord) (int, string, error) {
	proxyURL, err := url.Parse(proxy.ToProxyURL())
	if err != nil {
		return 0, "", fmt.Errorf("代理URL解析失败: %v", err)
	}

	transport := &http.Transport{
		Proxy: http.ProxyURL(proxyURL),
	}

	client := &http.Client{
		Transport: transport,
		Timeout:   15 * time.Second,
	}

	resp, err := client.Get(targetURL)
	if err != nil {
		return 0, "", fmt.Errorf("请求异常: %v", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return resp.StatusCode, "", fmt.Errorf("读取响应失败: %v", err)
	}

	return resp.StatusCode, string(body), nil
}

// parseLineItemToProxy 解析line格式的代理记录
func parseLineItemToProxy(line string) *ProxyRecord {
	line = strings.TrimSpace(line)
	if line == "" {
		return nil
	}

	parts := strings.Split(line, ":")
	if len(parts) == 4 {
		// username:password:ip:port
		port, err := strconv.Atoi(parts[3])
		if err != nil {
			return nil
		}
		return &ProxyRecord{
			Scheme: "http",
			IP:     parts[2],
			Port:   port,
		}
	} else if len(parts) == 2 {
		// ip:port
		port, err := strconv.Atoi(parts[1])
		if err != nil {
			return nil
		}
		return &ProxyRecord{
			Scheme: "http",
			IP:     parts[0],
			Port:   port,
		}
	}
	return nil
}

// parseJSONItemToProxy 解析json格式的代理记录
func parseJSONItemToProxy(item map[string]interface{}) *ProxyRecord {
	ip, ok := item["ip"].(string)
	if !ok || ip == "" {
		return nil
	}

	var port int
	switch p := item["port"].(type) {
	case float64:
		port = int(p)
	case string:
		var err error
		port, err = strconv.Atoi(p)
		if err != nil {
			return nil
		}
	default:
		return nil
	}

	proxy := &ProxyRecord{
		Scheme: "http",
		IP:     ip,
		Port:   port,
	}

	// 解析可选字段
	if remoteIP, ok := item["remote_ip"].(string); ok {
		proxy.RemoteIP = remoteIP
	}
	if expiredSec, ok := item["expired_sec"].(float64); ok {
		sec := int(expiredSec)
		proxy.ExpiredSec = &sec
	}
	if expiredAbs, ok := item["expired_abs"].(float64); ok {
		abs := int64(expiredAbs)
		proxy.ExpiredAbs = &abs
	}
	if expiredStr, ok := item["expired_str"].(string); ok {
		proxy.ExpiredStr = expiredStr
	}

	// 过滤已过期代理
	if proxy.IsExpired() {
		return nil
	}

	return proxy
}

// fetchProxyList 获取代理列表（含提取API错误分类）
func fetchProxyList() ([]*ProxyRecord, error) {
	extractURL := getExtractURL()

	// 自动检测格式或使用URL中指定的格式
	extractFormat := "line"
	if strings.Contains(extractURL, "format=json") {
		extractFormat = "json"
	}

	// 确保URL包含格式参数
	if !strings.Contains(extractURL, "format=") {
		separator := "&"
		if !strings.Contains(extractURL, "?") {
			separator = "?"
		}
		extractURL = fmt.Sprintf("%s%sformat=%s", extractURL, separator, extractFormat)
	}

	status, body, err := httpGet(extractURL)
	if err != nil {
		return nil, err
	}

	// 提取API链接错误（按官方指引分类）
	if status != 200 {
		switch status {
		case 400:
			return nil, fmt.Errorf("提取API链接错误：HTTP 400 → 访问API链接的参数错误；请检查参数")
		case 403:
			return nil, fmt.Errorf("提取API链接错误：HTTP 403 → 访问API的主机IP不在白名单；请把当前公网IP加入白名单")
		case 429:
			return nil, fmt.Errorf("提取API链接错误：HTTP 429 → 访问频率过快；请降低提取频率或咨询开通分发模式")
		default:
			return nil, fmt.Errorf("提取API链接错误：HTTP %d", status)
		}
	}

	var proxies []*ProxyRecord

	if extractFormat == "line" {
		scanner := bufio.NewScanner(strings.NewReader(body))
		for scanner.Scan() {
			line := scanner.Text()
			if strings.TrimSpace(line) != "" {
				proxy := parseLineItemToProxy(line)
				if proxy != nil && proxy.IsValid() {
					proxies = append(proxies, proxy)
				}
			}
		}
	} else if extractFormat == "json" {
		var data interface{}
		if err := json.Unmarshal([]byte(body), &data); err != nil {
			return nil, fmt.Errorf("JSON格式解析错误: %v", err)
		}

		// 兼容某些接口返回 {data: [...]} 形式
		if obj, ok := data.(map[string]interface{}); ok {
			if dataField, exists := obj["data"]; exists {
				data = dataField
			} else if listField, exists := obj["list"]; exists {
				data = listField
			} else if proxyListField, exists := obj["proxy_list"]; exists {
				data = proxyListField
			} else if proxyField, exists := obj["proxy"]; exists {
				data = proxyField
			}
		}

		if array, ok := data.([]interface{}); ok {
			for _, item := range array {
				if itemMap, ok := item.(map[string]interface{}); ok {
					proxy := parseJSONItemToProxy(itemMap)
					if proxy != nil && proxy.IsValid() {
						proxies = append(proxies, proxy)
					}
				}
			}
		}
	}

	if len(proxies) == 0 {
		return nil, fmt.Errorf("提取成功但没有可用代理：可能已过期或字段不匹配")
	}

	log.Printf("成功解析到 %d 个可用代理", len(proxies))
	return proxies, nil
}

// pickFirstProxy 选择第一个代理
func pickFirstProxy(proxies []*ProxyRecord) *ProxyRecord {
	return proxies[0]
}

// visitTargetViaProxy 通过代理访问目标站（含"访问代理错误"分类）
func visitTargetViaProxy(proxy *ProxyRecord, targetURL string) (string, error) {
	maxAttempts := 4

	for attempt := 1; attempt <= maxAttempts; attempt++ {
		status, body, err := httpGetViaProxy(targetURL, proxy)
		if err != nil {
			if strings.Contains(err.Error(), "timeout") && attempt < maxAttempts {
				log.Printf("请求超时，正在重试... [第%d次]", attempt)
				backoffSleep(attempt)
				continue
			}
			return "", fmt.Errorf("访问代理错误：%v", err)
		}

		// 访问代理错误（按官方指引分类）
		switch status {
		case 200:
			return body, nil
		case 403:
			return "", fmt.Errorf("访问代理错误：HTTP 403 → 访问代理的主机IP不在白名单；请确认当前IP在白名单中")
		case 408:
			return "", fmt.Errorf("访问代理错误：HTTP 408 → 网速过慢导致超时；请检查带宽/目标站响应并适当增大超时")
		case 429:
			if attempt < maxAttempts {
				log.Printf("访问代理错误：HTTP 429 → 访问代理频率过快，正在重试... [第%d次]", attempt)
				backoffSleep(attempt)
				continue
			}
			return "", fmt.Errorf("访问代理错误：HTTP 429 → 访问代理频率过快；降低并发与请求频率（建议单请求等待 >300ms）")
		case 504:
			return "", fmt.Errorf("访问代理错误：HTTP 504 → 代理在切换IP或目标站不可达；少量属正常，若频繁请先直连自测目标站并补充 UA/Cookie/Referer")
		default:
			return "", fmt.Errorf("访问代理错误：HTTP %d", status)
		}
	}

	return "", fmt.Errorf("访问代理失败：已达到最大重试次数")
}

// main 主流程
func main() {
	// 1) 获取代理列表（可能抛出"提取API链接错误"）
	proxyList, err := fetchProxyList()
	if err != nil {
		log.Fatalf("ERROR → %v", err)
	}

	// 2) 选择第一个代理
	firstProxy := pickFirstProxy(proxyList)
	log.Printf("选择代理: %s:%d", firstProxy.IP, firstProxy.Port)

	// 3) 通过该代理访问目标站（可能抛出"访问代理错误"）
	targetURL := getTargetURL()
	body, err := visitTargetViaProxy(firstProxy, targetURL)
	if err != nil {
		log.Fatalf("ERROR → %v", err)
	}

	fmt.Println("OK → httpbin 响应：")
	fmt.Println(body)
}
