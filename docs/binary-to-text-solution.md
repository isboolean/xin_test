# 文件二进制 → 大模型可读文本：方案选型与改造清单

> 状态：**方案评审稿**（保留为选型记录）。2026-11-20 已按一期最小范围落地 mock 实现：
> `OcrRecognizeService#recognize(String)`（返回 `List<String>`）+ `recognizeFile(byte[], String)` 桩方法
> + `OcrRecognizeTool`（`ocr_recognize`），详见 README 3.2。
> 本文 4.4 / 5.1 中的 PDFBox 路线已于 2026-11-24 落地（`PdfTextExtractor` 文本层直抽，
> 扫描件回退 OCR 桩），详见 `docs/pdf-text-extraction-solution.md`；
> 真实 OCR（扫描件）与 Tika 仍属后续期次；工具名以代码中的 `ocr_recognize` 为准。
> 日期：2026-11-20
> 适用工程：`xin_test`（Spring Boot 2.4.3 / JDK 8 / 内置 MCP Streamable HTTP 服务端）

---

## 1. 问题与根因

### 1.1 现状链路

大模型调用 MCP 工具 `download_file_unstructured`，传入 `fileId`。服务端经
`McpFileDownloadService#downloadFileDetail(fileId)` 取到 `byte[]`，由
`McpToolResult#embedResource()` 包装成 `{ type: "resource", resource: { uri, mimeType, blob: base64 } }`
返回，同时 `structuredContent` 给出 `{ fileId, mimeType, sizeBytes, fileName }`。

**当前仓库中只有这一个 MCP 工具**，没有任何文本解析或 OCR 能力。

### 1.2 为什么把 byte[] 交给文本模型不成立

| # | 原因 | 说明 |
| --- | --- | --- |
| 1 | 无语义 | Base64 进上下文后只是一串字符。PDF 的压缩对象流、字体与 CID 编码、Word 的 OOXML zip 结构、图片像素，模型都无法解码还原成文字或版面。 |
| 2 | 体积膨胀 | Base64 编码约为原始字节的 4/3。仓库内示例 PDF 约 110KB，编码后约 148KB，折合**数万 token 量级**（具体取决于分词器），既挤占上下文又推高费用与延迟。 |
| 3 | 结构性缺失 | 即便模型能"猜"出部分文字，也拿不到页码、段落、表格、键值对等结构，无法支撑后续的智能填单与字段映射。 |

结论：**这不是模型能力问题，而是数据形态问题**。转换必须发生在进入 LLM 上下文之前。

### 1.3 目标

把「二进制 → 文本」的转换下沉到 MCP 服务端，让模型只接收结构化文本；原始字节通道保留给存档 / 转存，但不再进入模型上下文。

---

## 2. 约束条件（选型时必须满足）

| 约束 | 来源 | 影响 |
| --- | --- | --- |
| JDK 8 | `pom.xml` `java.version=1.8` | 解析库必须选 JDK 8 兼容版本（见 5.3） |
| Lombok 1.18.38 | `pom.xml` | 新增 DTO 可沿用 Lombok，但注意 IDE 插件 |
| MCP 规范 | `McpToolResult` | `structuredContent` 顶层必须是 object；`content` 目前仅支持 `resource` / `text` 两种 |
| 工具注册 | `McpToolRegistry` | 实现类加 `@Component` 即自动暴露；工具名重复会抛异常 |
| 平台注册参数 | `docs/mcpworld-submit.md` / README 3.4 | `tools` 数组是白名单，新增工具必须同步登记，否则平台不暴露 |
| 文件大小上限 | `McpServerProperties#maxFileSizeBytes` 默认 10MB | 解析前复用同一上限做防护 |
| 可选 TongWeb 容器 | `pom.xml` profile `tongweb` | 引入重依赖会增大发行包，需评估 |
| 纯文本链路 | 当前 MCP 客户端 | 方案 B 需要客户端 / 模型侧同时支持 vision |

---

## 3. 方案选型

### 3.1 五条路线总览

| 方案 | 核心做法 | 模型改造 | 上下文成本 | 版面还原 | 数据出域 | 推荐度 |
| --- | --- | --- | --- | --- | --- | --- |
| **A 服务端文本化** | MCP 侧按 MIME 解析成文本再返回 | 无 | 低（可截断） | 中 | 无 | ★★★★★ |
| **B 多模态原生输入** | 图片走 `type:image`，PDF 走 document part | 需 vision / 文件输入 | 中~高 | 高 | 视通道 | ★★★ |
| **C 客户端解码** | Agent 侧落临时文件 + 本地解析器 | 无（但 Agent 要改） | 低 | 中 | 无 | ★★ |
| **D 外部解析服务** | MinerU / Unstructured / 云厂商文档解析 | 无 | 低 | 高 | **有** | ★★★ |
| **E 只传引用** | 返回 URL / fileId + 摘要，下游自取 | 需下游具备读文件能力 | 最低 | 取决于下游 | 视情况 | ★★ |

### 3.2 逐项说明

**A. 服务端文本化（推荐主线）**
复用 `downloadFileDetail(fileId)` 拿 `byte[] + mimeType`，按类型分派解析器，输出带页码 / 段落的结构化文本，通过 `content: [{type:"text"}]` 与 `structuredContent.texts` 返回。
- 优点：模型零改造、上下文可控、可脱敏、可缓存、可分页、解析逻辑集中在服务端一处。
- 代价：需要引入解析依赖；扫描件（无文本层 PDF / 图片）单靠 PDFBox 提取为空，必须叠 OCR。

**B. 多模态原生输入**
给 `McpToolResult` 增加 `addImage(mimeType, base64)`，图片 / 扫描页转成 `type: "image"` 交给 vision 模型。
- 优点：版面、印章、手写体还原最好，无需自建 OCR。
- 代价：需要扩展 `McpToolResult`；当前客户端是纯文本链路；页数与单次请求成本受限；PDF 通常仍需先转图片。

**C. Agent 客户端侧解码**
Agent 运行时把 blob 落盘，调本地脚本 / 解析器提取文本后注入 LLM。
- 优点：敏感文件不出域，服务端零改动。
- 代价：解析逻辑散落在每个 Agent，版本与结果格式难以统一，本项目作为 MCP 服务端反而帮不上忙。

**D. 外部文档解析服务**
调用云厂商或开源解析服务，返回 Markdown / 结构化 JSON。
- 优点：表格、公式、多栏版面还原最好，开发量小。
- 代价：需要网络与密钥，按页计费，数据出域需合规评估；线上还要处理超时与重试。

**E. 只传引用**
`download_file_unstructured` 保留，返回可访问 URL 或 fileId + 摘要，由具备文件读取能力的下游模型自取。
- 优点：改动最小。
- 代价：依赖下游能力，本项目当前客户端不具备，等于把问题转移而非解决。

### 3.3 推荐结论

> **以 A 为主线，B / D 作为可选增强，E 作为原有通道的定位调整，不采用 C。**
>
> - 一期：A（PDF 文本层）+ 明确兜底；原下载工具降级为「原始字节通道」，不再喂给 LLM。
> - 二期：扫描件 / 图片接 OCR（A 的扩展），或按客户端能力开 vision 通道（B）。
> - 可选：表格 / 公式要求高时，把 D 作为可插拔解析器，通过配置切换，且默认关闭以避免数据出域。

---

## 4. 推荐方案设计（A）

### 4.1 分层与数据流

```
LLM  →  tools/call(recognize_file_text, {fileId})
     →  RecognizeFileTextTool#call()            入参校验 / 结果封装
     →  FileTextExtractService#extract(fileId)  核心：恒返回 List<String>
     →  McpFileDownloadService#downloadFileDetail(fileId)   byte[] + mimeType
     →  按 mimeType 分派  PdfTextExtractor / OfficeTextExtractor / OcrTextExtractor
     →  List<String>（按页码、段落整理，超长截断）
     ←  structuredContent.texts + content[{type:"text"}]
```

`McpCallContextHolder` 透传的平台上下文 Header 在工具层记录日志，行为与现有工具一致。

### 4.2 核心方法签名与契约

```java
/**
 * 按 fileId 读取并解析文件内容，整理为文本行列表。
 * 契约：返回类型恒为 List<String>，永不为 null、永不抛异常。
 *   - 入参校验失败（null / 空白 / 长度 > 128）→ 空列表
 *   - 取数失败、文件为空、解析异常、无有效文本 → 空列表
 *   - 成功 → 按页码 / 段落顺序整理的文本行（跳过空行与空白值）
 */
public List<String> extract(String fileId);
```

设计要点：
- 空结果统一返回**可变的空 `ArrayList`**，不用 `Collections.emptyList()`，避免调用方追加时抛 `UnsupportedOperationException`。
- 解析异常在 service 层吞掉并记 `log.error`，工具层再做一次防御性兜底（`texts == null` 时置空列表）。
- 失败路径的 `structuredContent.texts` 同样是 `[]`，保证「恒为 List<String>」在协议层也成立。

### 4.3 MCP 工具契约

| 项 | 内容 |
| --- | --- |
| name | `recognize_file_text` |
| inputSchema | `fileId`（string，必填，1~128，`additionalProperties: false`） |
| outputSchema | `fileId`(string)、`texts`(array of string)、`count`(integer)、`truncated`(boolean) |
| content | `type:"text"`，文本行以换行拼接；空结果时给出「未识别到文本」提示 |
| structuredContent | `{ fileId, texts, count, truncated, [errorCode, message] }` |
| 错误码 | `INVALID_ARGUMENT` / `TEXT_EMPTY` / `PARSE_FAILED` / `FILE_TOO_LARGE` |
| annotations | `readOnlyHint: true`、`destructiveHint: false`、`idempotentHint: true`、`openWorldHint: false` |

> 注意：`structuredContent` 顶层必须是 object（MCP 规范），因此 `List<String>` 放在 `texts` 字段里，而不是直接作为 structuredContent。

### 4.4 按 MIME 的解析分派

| 类型 | 解析器 | 输出 | 兜底 |
| --- | --- | --- | --- |
| `application/pdf`（有文本层） | PDFBox `PDFTextStripper` | 按页切分、逐段成行，行首带页码 | 提取为空 → 判为扫描件，转 OCR 或返回 `TEXT_EMPTY` |
| `application/pdf`（扫描件）/ 图片 | OCR（二期） | 按区域 / 行识别文本 | 未启用 OCR → 返回 `TEXT_EMPTY` 并提示 |
| Word / Excel | Apache POI（二期） | 段落 / 工作表行 | 不支持类型 → 返回 `TEXT_EMPTY` |
| `text/plain` | 直接按 UTF-8 解码 | 按行切分 | 编码异常 → 记日志并返回空列表 |
| 未知类型 | 不解析 | 空列表 | 不猜测、不降级返回原始 Base64 |

### 4.5 大文件与截断策略

- 解析前复用 `McpServerProperties#maxFileSizeBytes`（10MB）做上限校验，超限返回 `FILE_TOO_LARGE`。
- 新增 `mcp.server.max-text-chars`（建议默认 30000）与 `truncated` 标记；超长时按页 / 按行截断，并在结果中显式告知模型「内容已截断」。
- 后续可增加 `page` / `offset` 入参做分页拉取，避免一次塞满上下文。

---

## 5. 改造清单

### 5.1 新增文件

| 路径 | 职责 | 期次 |
| --- | --- | --- |
| `mcp/service/FileTextExtractService.java` | 核心方法 `List<String> extract(String fileId)`，入参校验、取数、分派、异常兜底 | 一期 |
| `mcp/util/PdfTextExtractor.java` | PDFBox 按页提取文本、清洗空行 | 一期 |
| `mcp/tool/RecognizeFileTextTool.java` | `McpTool` 实现，注册为 `recognize_file_text` | 一期 |
| `mcp/util/OfficeTextExtractor.java` | POI 提取 Word / Excel 文本 | 二期 |
| `mcp/util/OcrTextExtractor.java` | OCR 接口 + 实现（扫描件 / 图片） | 二期 |
| `mcp/dto/FileTextDTO.java` | 可选，`texts` + `pageCount` + `truncated` 载体 | 二期 |
| `src/test/.../FileTextExtractServiceTest.java` | 校验 / 异常 / 空结果 / 正常提取单测 | 一期 |
| `src/test/.../RecognizeFileTextToolTest.java` | 工具契约、`toMap()` 结构、`texts` 恒为数组 | 一期 |

### 5.2 修改文件

| 路径 | 改动 | 期次 |
| --- | --- | --- |
| `pom.xml` | 增加解析依赖（见 5.3） | 一期 |
| `mcp/McpServerProperties.java` | 增加 `maxTextChars`、`ocrEnabled` 等配置 | 一期 |
| `mcp/tool/McpToolResult.java` | **仅方案 B 需要**：增加 `addImage(mimeType, data)` | 二期（可选） |
| `mcp/protocol/McpProtocolHandler.java` | `initialize` 的 `instructions` 文案补充新工具说明（可选） | 一期 |
| `README.md` | 3.1 工具定义、3.4 平台注册 `tools`、3.5 联调示例、4 目录结构 | 一期 |
| `docs/mcpworld-submit.md` | 功能描述、工具列表、联调示例 | 一期 |
| `docs/mcp-code-walkthrough.md` | 类数量、依赖图、「唯一具体工具」表述 | 一期 |

> 不改 `download_file_unstructured` 的行为；只调整它的定位说明：原始字节通道，供存档 / 转存，不直接喂给 LLM。

### 5.3 依赖与 JDK 8 兼容矩阵（**引入前必须逐个验证**）

| 库 | 建议版本线 | JDK 8 兼容 | 备注 |
| --- | --- | --- | --- |
| Apache PDFBox | `2.0.x`（如 2.0.22） | ✅ | **3.x 要求 JDK 11+，不能直接用** |
| Apache POI | `5.2.x` | ✅（需实测） | 依赖较多，会明显增大 jar |
| Apache Tika | `1.28.x` | ✅ | **2.x 要求 JDK 11+**；若只做 PDF，可不用 Tika |
| tess4j / Tesseract | `4.x` 或 `5.x` | ✅（需实测） | 需额外部署本地 tesseract 与语言包，或改用云 OCR |
| 云 OCR / 文档解析 | SDK | ✅ | 需密钥、网络、出域评估 |

建议一期**只引 PDFBox 一个依赖**，把依赖面控制到最小。

### 5.4 配置与平台注册参数

```yaml
mcp.server:
  max-text-chars: 30000
  ocr-enabled: false
```

平台注册参数（README 3.4 / MCPWorld 表单）需把 `tools` 由
`["download_file_unstructured"]` 改为
`["download_file_unstructured", "recognize_file_text"]`，否则 `tools/list` 里有工具但平台侧不会暴露。

---

## 6. 成本与风险

| 风险 | 影响 | 缓解措施 |
| --- | --- | --- |
| 扫描件无文本层，PDFBox 提取为空 | 模型拿到空结果 | 检测「提取为空」并明确返回 `TEXT_EMPTY` + 提示；二期接 OCR |
| JDK 8 与新版解析库不兼容 | 编译 / 运行失败 | 锁定 2.0.x / 1.28.x 版本线，引入前用当前 JDK 验证 |
| 依赖体积增大 | TongWeb 发行包变大、启动变慢 | 一期只引 PDFBox；POI / OCR 按需引入 |
| 长文档撑爆上下文 | 超限、费用高 | `maxTextChars` 截断 + `truncated` 标记 + 后续分页 |
| 表格 / 公式 / 多栏还原差 | 字段映射不准 | 结构化输出保留页码与段落；高要求场景切方案 D |
| 恶意文件（超大、zip bomb、异常结构） | 服务阻塞 / OOM | 复用 10MB 上限、解析超时、异常捕获、限制页数 |
| 云 OCR / 解析服务数据出域 | 合规风险 | 默认关闭，配置开关控制；敏感文件走本地解析 |
| 重复解析同一 fileId | 资源浪费 | 后续按 `fileId + 文件指纹` 做缓存（工具标注幂等） |
| 平台侧未登记新工具 | 平台调不到 | 同步修改注册参数与提交文档，联调时验证 `tools/list` |

---

## 7. 实施计划

| 期次 | 范围 | 交付 |
| --- | --- | --- |
| 一期 | PDF 文本层解析 + 新 MCP 工具 + 入参校验 / 异常 / 空结果契约 + 单测 + 文档同步 | 模型可直接读到示例 PDF 的文本 |
| 二期 | Word / Excel（POI）、扫描件 OCR 或 vision 通道、分页与缓存 | 覆盖更多文件类型 |
| 三期（可选） | 可插拔外部解析服务、字段映射对接智能填单 | 面向业务表单的端到端链路 |

---

## 8. 验收标准

1. `mvn clean package` 全绿（现有 12 个测试 + 新增测试全部通过）。
2. `tools/list` 返回两个工具，`recognize_file_text` 的 `inputSchema` / `outputSchema` 符合 4.3。
3. `tools/call` 用 `fileId=F20261116001` 调用，返回 `structuredContent.texts` 为**非空字符串数组**，`count > 0`。
4. 入参为 `null` / 空白 / 超长时，`isError: true`，且 `texts` 仍为 `[]`（不是 `null`）。
5. 构造「取数抛异常」「文件内容为空」「解析无文本」三种场景，均返回空列表且不抛异常。
6. Base64 blob 不出现在返回给模型的 `content` 中。

---

## 9. 待评审决策点

1. 是否接受「新工具名 `recognize_file_text`」与「一期只支持有文本层的 PDF」？
2. 扫描件优先走**本地 OCR**还是**vision 通道（方案 B）**？这决定是否需要扩展 `McpToolResult`。
3. 是否允许引入**云解析服务（方案 D）**？涉及数据出域，需合规确认。
4. `maxTextChars` 默认值与是否需要二期做分页入参？

> 评审确认后再进入编码；本次评审稿不产生任何代码变更。
