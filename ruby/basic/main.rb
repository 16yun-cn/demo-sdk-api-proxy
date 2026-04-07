#!/usr/bin/env ruby
require 'httparty'
require 'json'

extract = ENV['EXTRACT_URL']
abort('缺少 EXTRACT_URL') if !extract || extract.empty?
target = ENV['TARGET_URL'] || 'https://httpbin.org/ip'

response = HTTParty.get(extract, timeout: 15)
body = response.body

proxy_info = nil

# 检测是否为 JSON 格式
begin
  data = JSON.parse(body)
  if data.is_a?(Hash)
    # JSON 格式，支持 {proxy: [...]} 等格式
    proxy_list = data['data'] || data['list'] || data['proxy_list'] || data['proxy'] || data
    if proxy_list.is_a?(Array) && !proxy_list.empty?
      item = proxy_list[0]
      if item.is_a?(Hash) && item['ip'] && item['port']
        proxy_info = "#{item['ip']}:#{item['port']}"
      end
    end
  end
rescue JSON::ParserError
  # Line 格式
  lines = body.split(/\r?\n/).map(&:strip).reject(&:empty?)
  if !lines.empty?
    proxy_info = lines[0]
    # 如果是 user:pass:ip:port 格式，提取 ip:port
    parts = proxy_info.split(':')
    if parts.length == 4
      proxy_info = "#{parts[2]}:#{parts[3]}"
    end
  end
end

abort('未获取到可用代理') if !proxy_info || proxy_info.empty?

# 解析代理地址和端口
proxy_parts = proxy_info.split(':')
abort('代理格式错误') if proxy_parts.length != 2

proxy_host = proxy_parts[0]
proxy_port = proxy_parts[1].to_i

resp = HTTParty.get(target, http_proxyaddr: proxy_host, http_proxyport: proxy_port, timeout: 15)
puts resp.code
puts resp.body

