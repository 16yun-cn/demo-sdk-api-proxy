# 亿牛云 API 代理使用说明

API 代理通过提取链接返回一批代理地址，由程序负责选择、缓存和更新代理。使用前需要将程序运行服务器的公网出口 IP 加入订单白名单，并在代理有效期内完成请求。

## 官方帮助

[帮助首页](https://www.16yun.cn/help/) · [首次接入](https://www.16yun.cn/help/quickstart/api-extract/) · [开发手册](https://www.16yun.cn/help/dev-manual/api-proxy-guide/) · [开发 FAQ](https://www.16yun.cn/help/dev-manual/api-proxy-faq/) · [代码示例与 SDK](https://www.16yun.cn/help/code-sdk/api-proxy/) · [错误码说明](https://www.16yun.cn/help/dev-manual/errcode/)

## 首次接入

1. 登录亿牛云控制台，开通 API 代理订单。
2. 将运行程序的公网出口 IP 加入该订单白名单；部署到另一台服务器后，需要核对新服务器的出口 IP。
3. 复制控制台提供的完整提取链接，保留其中的账号及签名参数。先访问该链接，确认可以获取代理列表。
4. 选择下面的语言示例，将完整链接写入 `EXTRACT_URL`，运行程序并检查请求出口 IP。

提取链接、参数和白名单操作见[API 代理开发手册](https://www.16yun.cn/help/dev-manual/api-proxy-guide/)。提取凭据用于访问提取接口；这里的代理访问流程使用白名单认证。

## 运行第一个示例

以下命令从独立仓库 `demo-sdk-api-proxy` 根目录执行；如果使用总仓库 `demo-sdk`，先执行 `cd api-proxy`。需要 Python 和 pip，版本及依赖见[requests 示例](python/requests/README.md)。

```bash
cd python/requests
pip install -r requirements.txt
export EXTRACT_URL='<控制台复制的完整提取链接>'
export TARGET_URL='https://httpbin.org/ip'
python main.py
```

示例提取代理列表、选择代理，再通过代理访问目标地址。检查响应中的出口 IP，确认请求经过代理。`EXTRACT_URL` 是提取接口，`TARGET_URL` 是通过代理访问的目标，两者用途不同。

## 提取参数与返回结果

| 参数 | 用途 |
| --- | --- |
| `format` | 返回格式：默认逐行 `IP:PORT`，也可选择 `json` |
| `part` | 自定义逐行格式的分隔符；使用前确认所选示例支持该分隔符 |
| `count` | 每次提取数量，上限由产品决定 |
| `index` | 提取指定索引的代理 |
| `countkey` / `countlimit` | 限制时间范围内的提取数量 |

优先复制控制台生成的链接。手动增加参数时，已有查询参数的链接使用 `&` 连接，避免重复添加 `?` 或同名参数。

JSON 中的 `ip`、`port` 用于连接代理；`remote_ip` 表示目标站所见出口 IP。接口返回 `expired_sec`、`expired_abs`、`expired_str` 时，可据此判断有效期。完整字段和白名单管理参数以开发手册为准，各语言的解析方式见对应示例。

## 语言与示例

| 语言 | 示例 |
| --- | --- |
| [C++](cpp/README.md) | [basic](cpp/basic/README.md) |
| [C#](csharp/basic/README.md) | [basic](csharp/basic/README.md) |
| [Go](golang/basic/README.md) | [basic](golang/basic/README.md) |
| [Java](java/basic/README.md) | [basic](java/basic/README.md)、[okhttp](java/okhttp/README.md) |
| [Kotlin](kotlin/basic/README.md) | [basic](kotlin/basic/README.md)、[okhttp](kotlin/okhttp/README.md) |
| [Node.js](nodejs/README.md) | [axios](nodejs/axios/README.md)、[basic](nodejs/basic/README.md) |
| [PHP](php/README.md) | [basic](php/basic/README.md)、[guzzle](php/guzzle/README.md) |
| [Python](python/README.md) | [httpx](python/httpx/README.md)、[requests](python/requests/README.md)、[urllib](python/urllib/README.md) |
| [Ruby](ruby/README.md) | [basic](ruby/basic/README.md)、[faraday](ruby/faraday/README.md) |
| [Rust](rust/README.md) | [reqwest](rust/reqwest/README.md) |

每份示例 README 说明自己的依赖、环境变量和运行命令。这里的多语言目录提供可运行代码，官方帮助站提供接入规则和排障说明。

## 常见问题

| 现象或状态码 | 排查动作 |
| --- | --- |
| 提取返回 401 | 检查提取链接中的登录信息是否正确 |
| 403 | 核对当前公网出口 IP 与订单白名单 |
| 406 或未获得可用代理 | 检查订单资源和代理有效期，保留提取接口的原始响应 |
| 408 或连接超时 | 检查网络、目标站响应和超时设置；客户端超时不一定收到 HTTP 状态码 |
| 429 | 降低提取频率或请求并发，按订单限制控制总请求量 |
| 450 | API 包量产品可能限制目标域名，按帮助站指引联系支持确认 |
| 504 | 先验证目标站能否直连；持续出现时检查网络及必要请求头 |

排障时区分“提取接口失败”和“代理访问目标失败”，记录发生阶段、状态码及响应内容。更多说明见[API 开发 FAQ](https://www.16yun.cn/help/dev-manual/api-proxy-faq/)和[错误码说明](https://www.16yun.cn/help/dev-manual/errcode/)。

如果需要固定入口、由平台调度出口的产品，请查看[爬虫代理开发手册](https://www.16yun.cn/help/dev-manual/crawler-proxy-guide/)。
