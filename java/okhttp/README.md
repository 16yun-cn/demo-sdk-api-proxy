# Java OkHttp API代理示例

演示使用 Java + OkHttp 库通过 API 提取模式使用代理 IP。

## 功能特性
- 支持 API 提取链接对接
- 支持 JSON/Line 格式解析：LINE 为逐行 `ip:port`；JSON 为数组或 `proxy`、`data`、`list`、`proxy_list` 数组容器
- 使用 `ip`/`port` 连接代理，跳过无效或已过期条目，选取首个有效代理；`remote_ip` 不作为连接地址
- 自动重试与错误处理：提取和代理访问分阶段报错；仅超时和 HTTP 429 最多尝试 4 次，重试等待 1、2、4 秒

提取链接使用 IP 白名单认证。空列表、解析失败、无有效代理及认证/权限错误会明确失败，不会绕过代理直连目标。

## 环境要求
- JDK 17（统一构建和验证环境）
- Maven 3+
- 依赖库: OkHttp, Gson

## 环境变量配置
| 变量名 | 默认值 | 说明 |
|---|---|---|
| EXTRACT_URL | 必填 | 后台完整的“代理提取链接”，保留其查询参数；优先于兼容别名 |
| API_EXTRACT_URL | - | `EXTRACT_URL` 未设置时使用的兼容别名 |
| TARGET_URL | https://httpbin.org/ip | 目标验证 URL |

## 运行方式
```bash
# 1. 从独立 API 仓库根目录进入；总仓库先执行 cd api-proxy
cd java/okhttp

# 2. 编译
mvn package -q -DskipTests

# 3. 运行
export EXTRACT_URL="你的完整代理提取链接"
mvn exec:java
```
