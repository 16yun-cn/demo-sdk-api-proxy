# Ruby Faraday API代理示例

演示使用 Ruby Faraday 库通过 API 提取模式使用代理 IP。

## 功能特性
- 支持 API 提取链接对接
- 支持 JSON/Line 格式解析
- 自动重试与错误处理

## 环境要求
- Ruby 2.6+
- 依赖库: Faraday, Json

## 环境变量配置
| 变量名 | 默认值 | 说明 |
|---|---|---|
| EXTRACT_URL | - | 后台"代理提取链接" |
| TARGET_URL | https://httpbin.org/ip | 目标验证 URL |

## 运行方式

以下命令从对应独立仓库根目录执行；使用总仓库 `demo-sdk` 时，先执行 `cd api-proxy`。

```bash
# 1. 进入目录
cd ruby/faraday

# 2. 安装依赖
bundle install --path vendor/bundle

# 3. 运行
export EXTRACT_URL="http://..."
ruby main.rb
```
