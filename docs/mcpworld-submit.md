# MCPWorld 提交指南

> 目标地址：https://www.mcpworld.com/mcp-submit （登录百度账号 → 右上角「Add MCP」）
> 官方规则：https://www.mcpworld.com/zh/help
> 本文件给出本服务提交 MCPWorld 所需的**全部表单内容**，`<>` 占位项提交前必须替换。

## 1. 提交前置条件（必须全部满足）

| # | 条件 | 当前状态 |
| --- | --- | --- |
| 1 | 服务部署在**公网可访问**地址（建议 HTTPS） | ✅ 已部署：`https://connectivity-rugby-colleges-achievements.trycloudflare.com/mcp` |
| 2 | `mcp.server.auth-token` 已配置非空预共享密钥 | ✅ 已配置（systemd 启动参数） |
| 3 | `/mcp/tools` 调试端点已受同一密钥保护 | ✅ 已完成 |
| 4 | 工具可被平台实际调用并返回合法结果 | ✅ 公网全链路（health / 401 / initialize / tools/call）均已通过 |
| 5 | 准备 1:1 Logo（<200KB，jpeg/jpg/png/gif） | ❌ 需设计提供 |
| 6 | 联系方式（手机号 + 邮箱） | ❌ 需填写 |

## 2. 基本信息栏（照抄即可）

| 表单项 | 填写内容 |
| --- | --- |
| 名称（英文，字母/数字/中划线，≤30 字符） | `xin-test-mcp` |
| 功能描述（禁止 Emoji） | 按文件标识下载业务系统中的非结构化文件（支持 PDF / Word / Excel / 图片 / 文本等），以嵌入式资源返回文件内容与元数据，供大模型读取和分析文件。 |
| 分发范围 | 首次建议选「在 MCP 广场展示」；接入真实文件服务后再考虑百度搜索等场景 |

## 3. 配置栏（类型：流式传输的 HTTP / Streamable HTTP）

| 表单项 | 填写内容 |
| --- | --- |
| 英文名称 | `xin-test-mcp` |
| URL | `https://connectivity-rugby-colleges-achievements.trycloudflare.com/mcp` |
| 请求头（选填） | `Authorization=Bearer <预共享密钥>`（每行一个 key=value） |
| 审核测试用鉴权参数（选填） | `<预共享密钥>`，与请求头一致，供平台审核时验证服务可用性 |
| 平台调用鉴权参数（选填） | `<预共享密钥>`，供百度渠道分发调用使用 |

> 兼容写法：若平台请求头不支持 `Authorization`，可使用 `X-MCP-Key=<预共享密钥>`，服务端两者均支持。

### 为什么 URL 必须用 HTTPS 隧道地址

实测结论（2026-11-17）：

1. 提交 `http://62.234.131.142/mcp`（裸 IP + HTTP）时，表单报 **「URL格式不正确」**。前端只校验是否以 `http://`/`https://` 开头（已从页面 JS 反查确认），因此该提示来自**后端**校验，说明服务端要求域名 + HTTPS。
2. 想用「域名 + Let's Encrypt」直接签发证书会失败：大陆轻量服务器上**未备案域名**在 80/443 会被腾讯云劫持到 `dnspod.qcloud.com/static/webblock.html`（实测任意端口同样被拦），ACME 的 HTTP-01 / TLS-ALPN-01 均无法通过。
3. 因此采用 **Cloudflare Quick Tunnel**：由隧道出网到 Cloudflare 边缘，对外提供 `https://*.trycloudflare.com` 域名，源站无需公网端口与备案。

隧道由 systemd 单元 `cf-mcp.service` 托管（`cloudflared tunnel --url http://127.0.0.1:9090`），进程常驻、异常自动重启。

> ⚠️ Quick Tunnel 的域名在**进程重启后会变化**。若服务重启，需用下面的命令取新域名，并在 MCPWorld「My MCP」中修改后重新提交：
>
> ```bash
> journalctl -u cf-mcp --no-pager | grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' | tail -1
> ```
>
> 若长期使用，建议购买域名并完成 ICP 备案（大陆实例），或把服务迁到境外/香港实例，即可用自己的固定域名。

## 4. 服务详情栏（Markdown）

```markdown
# xin-test-mcp

## 产品定位
面向 Agent / 大模型场景的非结构化文件下载服务：按 fileId 读取业务系统中的
非结构化文件（类型由 fileType 映射为 MIME），以 MCP 嵌入式资源（Base64 blob）返回文件内容与元数据，
供模型读取、理解并完成后续任务（如智能填单）。

## 核心功能
- 按文件标识下载非结构化文件，返回内容（Base64 编码的文件字节）与元数据（文件名、大小、MIME）
- 支持 MCP Streamable HTTP 传输（JSON-RPC 2.0），协议版本 2024-11-05 ~ 2025-11-25
- 支持平台上下文 Header 透传：Agent-Tenant-Id / Agent-Id / Agent-User-Id /
  Agent-Session-Id / Agent-Run-Id
- 预共享密钥鉴权（Authorization: Bearer 或 X-MCP-Key）

## 工具列表

### download_file_unstructured
按 fileId 从业务系统非结构化文件服务读取文件（支持多类型，本地示例为 DOCX）。

- 入参（JSON Schema）：
  - fileId（string，必填，1~128 字符）：非结构化文件标识，例如 F20261116001
- 返回：
  - content：嵌入式资源 `{ type: "resource", resource: { uri, mimeType: 由 fileType 映射（未知类型为 application/octet-stream）, blob: base64 } }`
  - structuredContent：`{ fileId, mimeType, sizeBytes, fileName }`
- 失败时 `isError: true`，errorCode：INVALID_ARGUMENT / FILE_EMPTY / FILE_TOO_LARGE / DOWNLOAD_FAILED
- 特性：只读、幂等、不遍历目录、不返回文件列表

## 配置信息
- 传输方式：Streamable HTTP
- 端点：`POST /mcp`（JSON-RPC 2.0）；GET /mcp 返回 405（不提供服务端推送流）
- 探活：`GET /mcp/health`（免鉴权）
- 鉴权：请求头 `Authorization: Bearer <token>` 或 `X-MCP-Key: <token>`

## 认证方式
预共享密钥（Pre-shared key）。平台调用时请携带上述请求头之一。

## 联调示例
curl -X POST https://connectivity-rugby-colleges-achievements.trycloudflare.com/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"mcpworld","version":"1.0"}}}'

curl -X POST https://connectivity-rugby-colleges-achievements.trycloudflare.com/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"download_file_unstructured","arguments":{"fileId":"F20261116001"}}}'
```

## 5. 其他栏

- **Logo**：1:1、<200KB 的 PNG（需另行设计，建议纯色底 + 文件/下载意象）
- **联系方式**：填写真实手机号 + 邮箱
- **提交后**：在「My MCP」页查看审核状态；被驳回时点问号查看原因，修改后重提

## 6. 提交前验证清单（已部署：北京轻量服务器 62.234.131.142 + Cloudflare 隧道）

> 服务已部署并通过全部验证（2026-11-17）。预共享密钥保存在服务器
> `/etc/systemd/system/xin-test-mcp.service` 的启动参数中（服务器 `/tmp/mcp_token.txt` 有副本，
> 重启后以 systemd 单元为准）。**密钥不要提交到本公开仓库。**

```bash
BASE=https://connectivity-rugby-colleges-achievements.trycloudflare.com
TOKEN=<预共享密钥>

# 1. 探活
curl -s $BASE/mcp/health

# 2. 未带密钥应返回 401
curl -s -o /dev/null -w "%{http_code}\n" -X POST $BASE/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"ping"}'

# 3. 带密钥 initialize
curl -s -X POST $BASE/mcp -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"check","version":"1.0"}}}'

# 4. tools/list 与 tools/call
curl -s -X POST $BASE/mcp -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
curl -s -X POST $BASE/mcp -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"download_file_unstructured","arguments":{"fileId":"F20261116001"}}}'

# 5. 调试端点未带密钥应返回 401
curl -s -o /dev/null -w "%{http_code}\n" $BASE/mcp/tools
```

全部符合预期即可登录 MCPWorld 按第 2~5 节内容提交。
