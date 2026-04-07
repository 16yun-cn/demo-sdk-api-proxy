#!/usr/bin/env ruby
# frozen_string_literal: true

# 16YUN(亿牛云) Ruby API 提取式示例（Faraday 版）
#
# 按照伪代码规范实现：
# - 统一的 ProxyRecord 结构
# - 支持 line 和 json 两种格式解析
# - 规范的错误处理和中文提示
# - 指数退避重试机制
# - 单代理认证信息解析
#
# 环境变量：
# - EXTRACT_URL：后台"代理提取链接"（必需）
# - TARGET_URL：目标网站（可选，默认 https://httpbin.org/ip）

require 'faraday'
require 'json'
require 'uri'

# 统一的代理记录结构

# 统一的代理记录结构
class ProxyRecord
  attr_accessor :scheme, :ip, :port, :remote_ip,
                :expired_sec, :expired_abs, :expired_str

  def initialize(ip: '', port: 0)
    @scheme = 'http'
    @ip = ip
    @port = port
    @remote_ip = nil
    @expired_sec = nil
    @expired_abs = nil
    @expired_str = nil
  end

  def valid?
    !@ip.empty? && @port > 0
  end

  def expired?
    !@expired_sec.nil? && @expired_sec <= 0
  end

  def to_proxy_url
    "#{@scheme}://#{@ip}:#{@port}"
  end

  def to_s
    "ProxyRecord{ip='#{@ip}', port=#{@port}}"
  end
end

# 获取环境变量值
def get_env(name, default_value = '')
  value = ENV[name]
  return default_value if value.nil? || value.strip.empty?
  value.strip
end

# 获取提取链接，支持别名
def get_extract_url
  extract_url = get_env('EXTRACT_URL')
  extract_url = get_env('API_EXTRACT_URL') if extract_url.empty?
  raise '未设置 EXTRACT_URL 环境变量' if extract_url.empty?
  extract_url
end

# 获取目标URL
def get_target_url
  get_env('TARGET_URL', 'https://httpbin.org/ip')
end

# 指数退避重试等待
def backoff_sleep(attempt)
  sleep_sec = [2**(attempt - 1), 8].min # 1s, 2s, 4s, 8s
  if sleep_sec > 0
    puts "等待 #{sleep_sec}s 后重试..."
    sleep(sleep_sec)
  end
end

# 简单HTTP GET请求，返回[状态码, 响应体]
def http_get(url, timeout: 15)
  conn = Faraday.new do |f|
    f.options.timeout = timeout
    f.options.open_timeout = timeout
    f.ssl.verify = false
    f.adapter Faraday.default_adapter
  end

  response = conn.get(url)
  [response.status, response.body]
rescue Faraday::TimeoutError
  raise '请求超时'
rescue => e
  raise "请求异常: #{e.message}"
end

# 通过代理进行HTTP GET请求
def http_get_via_proxy(url, proxy, timeout: 15)
  proxy_url = proxy.to_proxy_url
  
  conn = Faraday.new do |f|
    f.options.timeout = timeout
    f.options.open_timeout = timeout
    f.ssl.verify = false
    f.proxy = proxy_url
    f.adapter Faraday.default_adapter
  end

  response = conn.get(url)
  [response.status, response.body]
rescue Faraday::TimeoutError
  raise '请求超时'
rescue => e
  raise "请求异常: #{e.message}"
end

# 解析line格式的代理记录
def parse_line_item_to_proxy(line)
  line = line.strip
  return nil if line.empty?

  if (match = line.match(/(\d{1,3}(?:\.\d{1,3}){3}):(\d{1,5})/))
    return ProxyRecord.new(ip: match[1], port: match[2].to_i)
  end

  parts = line.gsub(/\A"+|"+\z/, "").split(':').map { |part| part.strip.gsub(/\A"+|"+\z/, "") }
  if parts.length == 4
    # username:password:ip:port
    port = parts[3].to_i
    return nil if port <= 0
    ProxyRecord.new(ip: parts[2], port: port)
  elsif parts.length == 2
    # ip:port
    port = parts[1].to_i
    return nil if port <= 0
    ProxyRecord.new(ip: parts[0], port: port)
  end
  
  nil
end

# 解析json格式的代理记录
def parse_json_item_to_proxy(item)
  return nil unless item.is_a?(Hash)
  
  ip = item['ip']
  port = item['port']
  return nil if ip.nil? || ip.empty? || port.nil?
  
  port = port.to_i
  return nil if port <= 0
  
  proxy = ProxyRecord.new(ip: ip, port: port)
  
  # 解析可选字段
  proxy.remote_ip = item['remote_ip'] if item['remote_ip']
  proxy.expired_sec = item['expired_sec'].to_i if item['expired_sec']
  proxy.expired_abs = item['expired_abs'].to_i if item['expired_abs']
  proxy.expired_str = item['expired_str'] if item['expired_str']
  
  # 过滤已过期代理
  return nil if proxy.expired?
  
  proxy
end

# 获取代理列表（含提取API错误分类）
def fetch_proxy_list
  extract_url = get_extract_url
  
  # 自动检测格式或使用URL中指定的格式
  extract_format = extract_url.include?('format=json') ? 'json' : 'line'
  
  # 确保URL包含格式参数
  unless extract_url.include?('format=')
    separator = extract_url.include?('?') ? '&' : '?'
    extract_url = "#{extract_url}#{separator}format=#{extract_format}"
  end
  
  status, body = http_get(extract_url)
  
  # 提取API链接错误（按官方指引分类）
  if status != 200
    case status
    when 400
      raise '提取API链接错误：HTTP 400 → 访问API链接的参数错误；请检查参数。'
    when 403
      raise '提取API链接错误：HTTP 403 → 访问API的主机IP不在白名单；请把当前公网IP加入白名单。'
    when 429
      raise '提取API链接错误：HTTP 429 → 访问频率过快；请降低提取频率或咨询开通分发模式。'
    else
      raise "提取API链接错误：HTTP #{status}"
    end
  end
  
  proxies = []
  
  if extract_format == 'line'
    body.lines.each do |line|
      line_stripped = line.strip
      next if line_stripped.empty?
      
      proxy = parse_line_item_to_proxy(line_stripped)
      proxies << proxy if proxy&.valid?
    end
  elsif extract_format == 'json'
    begin
      data = JSON.parse(body)
      
      # 兼容某些接口返回 {data: [...]} 形式
      if data.is_a?(Hash)
        data = data['data'] || data['list'] || data['proxy_list'] || data['proxy'] || data
      end
      
      if data.is_a?(Array)
        data.each do |item|
          proxy = parse_json_item_to_proxy(item)
          proxies << proxy if proxy&.valid?
        end
      end
    rescue JSON::ParserError
      raise 'JSON格式解析错误'
    end
  end
  
  raise '提取成功但没有可用代理：可能已过期或字段不匹配。' if proxies.empty?
  
  puts "成功解析到 #{proxies.length} 个可用代理"
  proxies
end

# 选择第一个代理
def pick_first_proxy(proxies)
  proxies.first
end

# 通过代理访问目标站（含"访问代理错误"分类）
def visit_target_via_proxy(proxy, target_url)
  max_attempts = 4
  
  (1..max_attempts).each do |attempt|
    begin
      status, body = http_get_via_proxy(target_url, proxy)
      
      # 访问代理错误（按官方指引分类）
      case status
      when 200
        return body
      when 403
        raise '访问代理错误：HTTP 403 → 访问代理的主机IP不在白名单；请确认当前IP在白名单中。'
      when 408
        raise '访问代理错误：HTTP 408 → 网速过慢导致超时；请检查带宽/目标站响应并适当增大超时。'
      when 429
        if attempt < max_attempts
          puts "访问代理错误：HTTP 429 → 访问代理频率过快，正在重试... [第#{attempt}次]"
          backoff_sleep(attempt)
          next
        else
          raise '访问代理错误：HTTP 429 → 访问代理频率过快；降低并发与请求频率（建议单请求等待 >300ms）。'
        end
      when 504
        raise '访问代理错误：HTTP 504 → 代理在切换IP或目标站不可达；少量属正常，若频繁请先直连自测目标站并补充 UA/Cookie/Referer。'
      else
        raise "访问代理错误：HTTP #{status}"
      end
    rescue => e
      if e.message.include?('超时') && attempt < max_attempts
        puts "请求超时，正在重试... [第#{attempt}次]"
        backoff_sleep(attempt)
        next
      else
        raise "访问代理错误：#{e.message}"
      end
    end
  end
  
  raise '访问代理失败：已达到最大重试次数'
end

# 主流程
def main
  begin
    # 1) 获取代理列表（可能抛出"提取API链接错误"）
    proxy_list = fetch_proxy_list
    
    # 2) 选择第一个代理
    first_proxy = pick_first_proxy(proxy_list)
    puts "选择代理: #{first_proxy.ip}:#{first_proxy.port}"
    
    # 3) 通过该代理访问目标站（可能抛出"访问代理错误"）
    target_url = get_target_url
    body = visit_target_via_proxy(first_proxy, target_url)
    
    puts 'OK → httpbin 响应：'
    puts body
    
    0
  rescue => e
    warn "ERROR → #{e.message}"
    1
  end
end

# 运行主程序
exit main if $PROGRAM_NAME == __FILE__
