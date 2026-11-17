# xin_test

Spring Boot 2.4.3 + JDK 8 + MyBatis 示例工程，内置**业务 MCP 服务端**，向 Agent 统一管理平台暴露
`download_file_unstructured` 下载工具（AG-UI 智能填单方案 BE03 / BE04）。

## 1. 运行

```bash
mvn spring-boot:run
# 或
mvn clean package -DskipTests && java -jar target/xin_test-1.0.0-SNAPSHOT.jar
```

数据库为可选依赖：`sql/init.sql` 建库建表后按 `application.yml` 配置即可；数据库不可用时
`FileServiceImpl` 自动降级为纯 mock 元数据，不影响下载能力。

## 2. 业务 REST 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/file/download?fileNo=F20261116001` | 返回 `byte[]`，内容为结构合法的 mock PDF |
| GET | `/mcp/health` | 健康检查（免鉴权），返回服务名、版本、传输类型 |
| GET | `/mcp/tools` | 调试用：查看已注册 MCP 工具与 Schema；鉴权策略与 `POST /mcp` 一致（`mcp.server.auth-token` 非空时必须携带密钥） |

## 3. MCP 服务端

- 传输方式：**Streamable HTTP**，入口 `POST /mcp`（JSON-RPC 2.0），响应 `application/json`
- 协议版本：协商支持 `2024-11-05` / `2025-03-26` / `2025-06-18` / `2025-11-25`
- 支持方法：`initialize`、`notifications/initialized`、`ping`、`tools/list`、`tools/call`（含批量请求）
- 鉴权：`mcp.server.auth-token` 非空时，要求 `Authorization: Bearer <token>` 或 `X-MCP-Key: <token>`
- 平台上下文 Header（BE04）：`Agent-Tenant-Id`、`Agent-Id`、`Agent-User-Id`、`Agent-Session-Id`、
  `Agent-Run-Id`，通过 `McpCallContextHolder` 透传给工具并记录日志

### 3.1 工具定义

| 项 | 内容 |
| --- | --- |
| name | `download_file_unstructured` |
| 入参 | `{ "fileId": "F20261116001" }`（必填，等同业务 `fileNo`，长度 1~128） |
| content | 嵌入式资源：`{ type: "resource", resource: { uri, mimeType: "application/pdf", blob: base64 } }` |
| structuredContent | `{ fileId, mimeType, sizeBytes, fileName }` |
| 失败 | `isError: true` + `structuredContent.errorCode`：`INVALID_ARGUMENT` / `FILE_EMPTY` / `FILE_TOO_LARGE` / `DOWNLOAD_FAILED` |

工具实现直接复用业务下载能力 `FileService#downloadFileDetail`（当前为 mock PDF 生成），未调用外部服务。

### 3.3 提交 MCPWorld

MCPWorld（百度旗下 MCP 分发平台，https://www.mcpworld.com/mcp-submit ）支持本服务的
Streamable HTTP 传输与预共享密钥鉴权。**完整的表单填写内容与提交前验证清单见
`docs/mcpworld-submit.md`**，要点：

1. 部署到公网（建议 HTTPS），`localhost` 无法通过平台审核
2. `mcp.server.auth-token` 配置非空预共享密钥，同一密钥填入表单「请求头」与「鉴权参数」
3. 按 `docs/mcpworld-submit.md` 第 6 节验证清单逐条通过后提交

### 3.4 平台注册参数

```json
{
  "name": "xin-test-mcp",
  "transport": "streamable-http",
  "url": "http://<业务域名>/mcp",
  "headers": { "Authorization": "Bearer <预共享密钥>" },
  "tools": ["download_file_unstructured"]
}
```

### 3.5 联调示例

```bash
# 初始化
curl -s -X POST http://localhost:8080/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"agent","version":"1.0"}}}'

# 调用下载工具（带平台上下文 Header）
curl -s -X POST http://localhost:8080/mcp -H 'Content-Type: application/json' \
  -H 'Agent-Tenant-Id: tenant_nbhr_001' -H 'Agent-Id: agent_fill_form_01' \
  -H 'Agent-User-Id: u_10086' -H 'Agent-Session-Id: sess_abc' -H 'Agent-Run-Id: run_001' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"download_file_unstructured","arguments":{"fileId":"F20261116001"}}}'
```

## 4. 目录结构

```
src/main/java/com/xin/test
├── controller      业务 HTTP 接口
├── service         业务服务（含 mock PDF 生成）
├── mapper          MyBatis Mapper
├── dto / vo / entity
├── mcp
│   ├── transport   MCP 传输层（POST /mcp）
│   ├── protocol    JSON-RPC 分发（initialize / tools/list / tools/call）
│   ├── tool        McpTool 契约、注册中心、download_file_unstructured 实现
│   ├── context     Agent 平台上下文 Header 传递
│   └── util        JSON 工具
└── util            mock PDF 构造器
```
