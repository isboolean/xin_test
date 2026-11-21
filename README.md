# xin_test

Spring Boot 2.4.3 + JDK 8 + MyBatis 示例工程，内置**业务 MCP 服务端**，向 Agent 统一管理平台暴露
`download_file_unstructured` 下载工具与 `ocr_recognize` OCR 识别工具（AG-UI 智能填单方案 BE03 / BE04）。

## 1. 运行

```bash
mvn spring-boot:run
# 或
mvn clean package -DskipTests && java -jar target/xin_test-1.0.0-SNAPSHOT.jar
```

数据库为可选依赖（`sql/init.sql` + `application.yml`），仅用于演示 MyBatis 链路；
下载链路的元数据来自文件系统接口返回的 `fileType` / `fileName`，不依赖数据库。

## 2. 业务 REST 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/file/download?fileNo=F20261116001` | 返回 `byte[]`；本地示例为内置 PDF，线上由文件系统接口返回任意类型文件 |
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
| content | 嵌入式资源：`{ type: "resource", resource: { uri, mimeType: 由 fileType 映射，未知类型为 application/octet-stream, blob: base64 } }` |
| structuredContent | `{ fileId, mimeType, sizeBytes, fileName }` |
| 失败 | `isError: true` + `structuredContent.errorCode`：`INVALID_ARGUMENT` / `FILE_EMPTY` / `FILE_TOO_LARGE` / `DOWNLOAD_FAILED` |

工具实现复用 mcp 侧下载能力 `McpFileDownloadService#downloadFileDetail`。线上文件系统接口返回
`byte[] + fileType + fileName`，mcp 侧用 `MimeTypeResolver` 把扩展名映射为 MIME、
清洗文件名后组装 `FileContentDTO`。本地 `mcp/util/RawFileFetcher#fetchRawFile` 为可运行占位实现，读取随应用打包的示例 PDF
`src/main/resources/file/wufang-survey-report-fixed.pdf`；线上只需替换该取数方法，上层工具无需改动。

### 3.2 OCR 识别工具

| 项 | 内容 |
| --- | --- |
| name | `ocr_recognize` |
| 入参 | `{ "fileId": "F20261116001" }`（必填，1~128） |
| 核心方法 | `OcrRecognizeService#recognize(String fileId)` → `List<String>`，封装 `fileId → byte[] + mimeType → recognizeFile(byte[], String) → 文本行` |
| structuredContent | `{ fileId, texts: List<String>, count }`；失败时附加 `errorCode` / `message` |
| 失败 | `isError: true` + `errorCode`：`INVALID_ARGUMENT` / `TEXT_EMPTY` / `OCR_FAILED` |

`recognizeFile(byte[], String)` 当前为 mock 桩实现，返回模拟文本行，不对接真实 OCR 服务；线上只需替换它，上层方法签名不变。
**`texts` 恒为字符串数组**：入参非法、文件为空、取数或识别异常时返回 `[]`；`recognize` 方法永不返回 `null`、永不抛异常。
原始字节仍由 `download_file_unstructured` 提供，但该通道用于存档 / 转存，不直接交给大模型。

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
  "url": "https://connectivity-rugby-colleges-achievements.trycloudflare.com/mcp",
  "headers": { "Authorization": "Bearer <预共享密钥>" },
  "tools": ["download_file_unstructured", "ocr_recognize"]
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

# 调用 OCR 识别工具（返回结构化文本行，不返回二进制）
curl -s -X POST http://localhost:8080/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"ocr_recognize","arguments":{"fileId":"F20261116001"}}}'
```

## 4. 目录结构

```
src/main/java/com/xin/test
├── controller      业务 HTTP 接口
├── service         REST 文件服务（downloadFile 委托给 mcp 侧下载能力）
├── mapper          MyBatis Mapper
├── dto / vo / entity
├── mcp
│   ├── transport   MCP 传输层（POST /mcp）
│   ├── protocol    JSON-RPC 分发（initialize / tools/list / tools/call）
│   ├── tool        McpTool 契约、注册中心、download_file_unstructured / ocr_recognize 实现
│   ├── service     McpFileDownloadService（下载取数）、OcrRecognizeService（OCR 工具方法 + recognizeFile 桩）
│   ├── context     Agent 平台上下文 Header 传递
│   ├── dto         FileContentDTO / RawFileDTO（文件内容与上游报文）
│   └── util        RawFileFetcher（上游取数）、MimeTypeResolver（扩展名→MIME、文件名清洗）、JSON 工具
└── util            MockPdfBuilder 为历史遗留
```
