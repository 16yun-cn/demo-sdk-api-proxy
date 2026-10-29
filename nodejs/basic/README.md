# Node.js Basic API代理示例

演示使用 Node.js 原生模块通过 API 提取模式使用代理 IP。

## 功能特性
- 支持 API 提取链接对接
- 支持 JSON/Line 格式解析
- 自动重试与错误处理

## 环境要求
- Node.js 16+
- 依赖库: 无 (原生 http/https)

## 环境变量配置
| 变量名 | 默认值 | 说明 |
|---|---|---|
| EXTRACT_URL | - | 后台"代理提取链接" |
| TARGET_URL | https://httpbin.org/ip | 目标验证 URL |

## 运行方式
```bash
# 1. 进入目录
cd api-proxy/nodejs/basic

# 2. 安装依赖 (无)

# 3. 运行
export EXTRACT_URL="http://..."
node main.js
```
