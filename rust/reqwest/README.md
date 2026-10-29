# Rust Reqwest API代理示例

演示使用 Rust Reqwest 通过 API 提取模式使用代理 IP。

## 功能特性
- 支持 API 提取链接对接
- 支持 JSON/Line 格式解析
- 自动重试与错误处理

## 环境要求
- Rust 1.60+
- 依赖库: reqwest, tokio, serde

## 环境变量配置
| 变量名 | 默认值 | 说明 |
|---|---|---|
| EXTRACT_URL | - | 后台"代理提取链接" |
| TARGET_URL | https://httpbin.org/ip | 目标验证 URL |

## 运行方式

以下命令从对应独立仓库根目录执行；使用总仓库 `demo-sdk` 时，先执行 `cd api-proxy`。

```bash
# 1. 进入目录
cd rust/reqwest

# 2. 编译/运行
export EXTRACT_URL="<控制台复制的完整提取链接>"
cargo run
```