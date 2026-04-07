# Go API代理示例

演示使用 Go 标准库 net/http 通过 API 提取模式使用代理 IP。

## 功能特性
- 支持 API 提取链接对接
- 支持 JSON/Line 格式解析
- 自动重试与错误处理

## 环境要求
- Go 1.19+
- 依赖库: 无 (标准库)

## 环境变量配置
| 变量名 | 默认值 | 说明 |
|---|---|---|
| EXTRACT_URL | - | 后台"代理提取链接" |
| TARGET_URL | https://httpbin.org/ip | 目标验证 URL |

## 运行方式
```bash
# 1. 进入目录
cd api-proxy/golang/basic

# 2. 编译/运行
go run .
```