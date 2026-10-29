# C++ Basic API代理示例

先按[产品接入说明](../../README.md)取得订单信息。运行步骤从对应独立仓库根目录执行；使用总仓库时先进入 `api-proxy`。将运行程序的公网出口 IP 加入订单白名单，并设置完整的 `EXTRACT_URL`。

演示使用 C++ (libcurl + nlohmann/json) 通过 API 提取模式使用代理 IP。

## 功能特性

- 支持 API 提取链接对接
- 支持 JSON/Line 格式解析
- 自动重试与错误处理

## 环境要求

- C++17
- 依赖库: libcurl, nlohmann/json

## 环境变量配置

| 变量名 | 默认值 | 说明 |
|---|---|---|
| EXTRACT_URL | - | 后台"代理提取链接" |
| TARGET_URL | https://httpbin.org/ip | 目标验证 URL |

## 运行方式

```bash
# 1. 进入目录
cd cpp/basic

# 2. 编译
make

# 3. 运行
export EXTRACT_URL="http://..."
./main
```

## 官方帮助

[帮助首页](https://www.16yun.cn/help/) · [首次接入](https://www.16yun.cn/help/quickstart/api-extract/) · [开发手册](https://www.16yun.cn/help/dev-manual/api-proxy-guide/) · [开发 FAQ](https://www.16yun.cn/help/dev-manual/api-proxy-faq/) · [代码示例与 SDK](https://www.16yun.cn/help/code-sdk/api-proxy/) · [错误码说明](https://www.16yun.cn/help/dev-manual/errcode/)
