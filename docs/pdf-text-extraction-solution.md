# PDF 文本层直抽方案：替代 OCR 解析多页 PDF

> 状态：**已实施（2026-11-24）**。按用户确认的范围落地：PDF 文本层走 PDFBox 直抽；
> 无文本层（扫描件）或解析失败时**回退 OCR 流程，OCR 具体实现继续 mock**（§8 决策点 2 选择保留桩）。
> 交付物：`pom.xml` 加 `pdfbox:2.0.22`（与部署环境既有版本对齐）；新增 `mcp/util/PdfTextExtractor.java`；
> `OcrRecognizeService` 增 PDF 分支（空文本层回退 `recognizeByOcr()`）；测试 50/50 全绿。
> 截断采用追加 `[已截断]` 文本行（决策点 3），阈值 MAX_PAGES=50 / MAX_TEXT_CHARS=30000（决策点 4）。
> 日期：2026-11-24
> 适用工程：`xin_test`（Spring Boot 2.4.3 / JDK 8 / 内置 MCP Streamable HTTP 服务端）
> 前置文档：`docs/binary-to-text-solution.md`（其 4.4 中规划的 PDFBox 路线即本文主线）

---

## 1. 背景与结论

### 1.1 问题

`ocr_recognize` 工具当前对 PDF 的"识别"是 `OcrRecognizeService#fixedRecognizedLines()` 硬编码的
固定数据（`recognizeByOcr()` 不解析真实字节）。用户侧另接的真实 OCR 只能识别 PDF 第一页。

### 1.2 关键事实（2026-11-24 本机实测）

内置示例 PDF `src/main/resources/file/wufang-survey-report-fixed.pdf`（110,790 字节）
**存在完整文本层**，用 `pypdf` 逐页提取结果：

| 页 | 字符数 | 非空行数 | 页脚 |
| --- | --- | --- | --- |
| 1 | 436 | 81 | 第 1 页 / 共 6 页 |
| 2 | 530 | 86 | 第 2 页 / 共 6 页 |
| 3 | 432 | 69 | 第 3 页 / 共 6 页 |
| 4 | 341 | 51 | 第 4 页 / 共 6 页 |
| 5 | 384 | 59 | 第 5 页 / 共 6 页 |
| 6 | 319 | 39 | 第 6 页 / 共 6 页 |

合计约 2,400 字符 / 385 行，毫秒级完成，与原文 100% 一致。

### 1.3 结论

> **有文本层的 PDF 不需要 OCR**：服务端用 PDFBox 直接抽文本层，天然按页处理，
> 不存在"单图 OCR 接口只认第一页"的问题；速度快、零成本、无数据出域、无错字。
> 只有"提取为空的扫描件"才需要 OCR 兜底（本期不做，仅显式报错提示）。

---

## 2. 方案选型

| 路线 | 做法 | 评价 |
| --- | --- | --- |
| **A. PDFBox 文本层直抽（推荐）** | 服务端按页提取文本，`[第 N 页]` 分组返回 | 模型零改造、契约不变、JDK 8 兼容 |
| B. 真实 OCR 逐页识别 | 渲染成图 + 逐页调 OCR | 仅扫描件需要；慢、有错字、有"第一页"类接入坑 |
| C. 外部文档解析服务 | 云厂商 / MinerU | 数据出域 + 密钥 + 计费，本期不需要 |
| D. pdfplumber 表格结构化 | 拿单元格坐标 | JDK 侧无成熟等价物，Python 链路可选，二期再说 |

本期采用 A；B 作为扫描件兜底留二期；C/D 不采用。

---

## 3. 详细设计

### 3.1 流程与接入点

在 `OcrRecognizeService#recognize()` 现有扩展名分派中，**与 Word / Excel 分支并列新增 PDF 分支**
（结构一致：直抽、不转图、不经过 OCR）：

```
recognize(fileId)
  → downloadFileDetail(fileId) → FileContentDTO(byte[] + fileName + fileType + mimeType)
  → resolveExtension(file)
      ├─ doc/docx  → WordDataExtractor（不变）
      ├─ xls/xlsx  → ExcelDataExtractor（不变）
      ├─ pdf       → PdfTextExtractor.extract(content)          ← 本期新增
      └─ 其它      → recognizeByOcr() 桩（不变，维持现状）
```

**替换 / 保留范围：**

- `isPdfExtension(extension)` + `extractPdfText(file, extension)` 新增，位置在 Excel 分支之后、OCR 桩之前。
- PDF 分支命中时**不再走** `recognizeByOcr()`；`fixedRecognizedLines()` 与 PDF 的关联删除
  （保留方法供过渡期其它类型使用与否见 §8 决策点）。
- 顺带修正已知不一致：`README.md` 3.2 中「线上只需替换 `recognizeFile`」的表述改为
  实际链路（`recognizeByOcr` 为非 Word/Excel/PDF 兜底入口）。

### 3.2 新增类：`mcp/util/PdfTextExtractor.java`

纯 Java 静态工具，风格对齐 `WordDataExtractor` / `ExcelDataExtractor`：

```java
public final class PdfTextExtractor {

    /** 页数上限，防御异常结构文档（对应 binary-to-text-solution 6 章风险） */
    static final int MAX_PAGES = 50;
    /** 文本总字符上限，防止撑爆模型上下文 */
    static final int MAX_TEXT_CHARS = 30000;

    /** 提取 PDF 全部页文本层；提取结果为空 / 解析异常 → 恒返回空列表 */
    static List<String> extract(byte[] content) {
        if (content == null || content.length == 0) return new ArrayList<>();
        try (PDDocument doc = PDDocument.load(content)) {
            int pageCount = Math.min(doc.getNumberOfPages(), MAX_PAGES);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);   // 按版面位置排序，字段与值不易错位
            List<String> lines = new ArrayList<>();
            int totalChars = 0;
            boolean truncated = false;
            for (int p = 1; p <= pageCount; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String pageText = stripper.getText(doc);
                if (pageText == null || pageText.trim().isEmpty()) continue; // 无文本层的页（扫描页）
                lines.add("[第 " + p + " 页]");
                for (String line : pageText.split("\\R+")) {
                    String t = line.trim();
                    if (t.isEmpty()) continue;
                    totalChars += t.length();
                    if (totalChars > MAX_TEXT_CHARS) { truncated = true; break; }
                    lines.add(t);
                }
                if (truncated) break;
            }
            if (truncated && !lines.isEmpty()) {
                lines.add("[已截断：超过 " + MAX_TEXT_CHARS + " 字符上限]");
            }
            return lines;
        } catch (Exception e) {
            // 损坏 / 加密 / 非法结构 → 收敛为空列表，不外抛（与既有契约一致）
            return new ArrayList<>();
        }
    }
}
```

设计要点：

- 每页单独 `setStartPage/setEndPage`，输出以 `[第 N 页]` 行分组，模型可定位字段来源页。
- 单页提取为空不中断整份（混合型 PDF：部分文本页 + 部分扫描页时，文本页仍可用）。
- 全部页均为空 → 整体返回空列表，由上层判定为扫描件（§3.3）。
- `setSortByPosition(true)`：按 (x, y) 排序输出，表单类文档"字段名 值"顺序基本正确；
  实测中 pypdf 抽出 `* 勘察人员 周奔 * 勘察日期 2026-11-12` 同行结构，Stripper 行为类似。
- 截断用追加提示行表达（`texts` 契约不新增字段；`truncated` 结构化字段见 §8 决策点）。

### 3.3 扫描件判定与错误语义

`recognize()` 中 PDF 分支的返回处理：

| 场景 | 返回 | 工具层表现 |
| --- | --- | --- |
| 提取到文本 | 行列表（含 `[第 N 页]` 标记） | 正常成功，`count > 0` |
| 提取为空（扫描件 / 全部页无文本层） | 空列表 | `isError: true`，errorCode 见下 |
| 解析异常（损坏 / 加密） | 空列表 | 同上 |

错误码建议新增 `SCANNED_PDF_NO_TEXT`（或 `PDF_NO_TEXT`），message：
`"该 PDF 无文本层（扫描件），当前版本不支持 OCR，请上传文字版或接入 OCR 服务"`。
若不想扩错误码集合，可复用 `TEXT_EMPTY` 但必须在 message 中显式区分"扫描件"与"文件为空"，
否则排障困难（binary-to-text-solution §6 已列入此风险）。

### 3.4 契约不变项

- `OcrRecognizeService#recognize(String)` 返回类型仍恒为 `List<String>`，永不 null、永不抛异常；
  入参校验、下载失败、空文件行为全部不变。
- `OcrRecognizeTool`（`ocr_recognize`）的 inputSchema / outputSchema / annotations **零改动**
  （`errorCode` / `message` 本就是 structuredContent 的附加字段）。
- `download_file_unstructured`、Word / Excel 分支、平台注册参数（`tools` 数组）不变。

### 3.5 行为变化（评审需知）

唯一对外可见变化：**传 PDF 文件时，`texts` 从固定 92 行 mock 数据变为真实提取的文本行**。
依赖 mock 内容的断言需同步修改（见 §6 测试清单）。内容中不再包含
`文件类型/文件大小/内容指纹/识别标题/识别正文/识别结论` 六个桩字段——真实文本行以页为单位。

---

## 4. 依赖

| 项 | 值 | 说明 |
| --- | --- | --- |
| groupId / artifactId | `org.apache.pdfbox:pdfbox` | 只需此一个，`fontbox` / `commons-logging` 传递引入 |
| 版本 | **2.0.22**（与部署环境对齐） | **3.x 要求 JDK 11+，禁止引入**（同 binary-to-text-solution 5.3 结论）；2.0.x 全线 API 稳定，本方案所用 `PDDocument.load` / `PDFTextStripper` / `setSortByPosition` 均可用 |
| 体积 | jar 约 2.7MB + fontbox 1.6MB | 发行包增量可接受 |
| 与 POI 5.0.0 共存 | 无冲突 | 无重叠包名；`commons-logging` 与 Spring 管理的版本兼容 |

---

## 5. 改造清单

### 5.1 新增

| 路径 | 职责 |
| --- | --- |
| `mcp/util/PdfTextExtractor.java` | PDF 文本层逐页提取、截断、异常收敛（§3.2） |
| `src/test/java/com/xin/test/mcp/util/PdfTextExtractorTest.java` | 提取 / 分页 / 损坏 / 截断单测（§6） |

### 5.2 修改

| 路径 | 改动 |
| --- | --- |
| `pom.xml` | 增加 `pdfbox:2.0.22` |
| `mcp/service/OcrRecognizeService.java` | 新增 `isPdfExtension` + `extractPdfText` 分支（Excel 分支后、OCR 桩前）；PDF 命中时跳过 `recognizeByOcr`；Javadoc 同步 |
| `src/test/.../OcrRecognizeServiceTest.java` | `validFileIdReturnsNonEmptyStringList` 改为断言真实文本（如含"五方踏勘报告"与 `[第 1 页]`）；新增 pdf 分支 / 损坏 PDF / 扫描件空文本用例 |
| `README.md` 3.2 | 流程图补 PDF 分支；修正「替换 `recognizeFile`」的不实表述 |
| `docs/binary-to-text-solution.md` | 顶部状态注记：一期 PDFBox 已按本文落地 |
| `docs/mcp-code-walkthrough.md` | 类清单同步 |

### 5.3 不改

`OcrRecognizeTool`、`McpToolResult`、`McpProtocolHandler`、`McpFileDownloadService`、
`RawFileFetcher`、Word / Excel 提取器、平台注册参数。

---

## 6. 测试设计

`PdfTextExtractorTest`（新增，用 classpath 内置 PDF 做真实样本）：

1. 内置 PDF 提取成功：行数 > 0，含 6 个 `[第 N 页]` 标记，N 连续 1..6；
2. 首行内容包含"五方踏勘报告"（与 pypdf 实测一致，形成交叉验证）；
3. 损坏字节（`new byte[]{1,2,3}`）→ 空列表，不抛异常；
4. 空字节 / null → 空列表；
5. 截断：构造超 `MAX_TEXT_CHARS` 输入（反射或包可见 setter 缩小阈值）→ 末行含 `[已截断]`。

`OcrRecognizeServiceTest`（修改 + 新增）：

6. `validFileIdReturnsNonEmptyStringList`：断言改为含 `[第 1 页]` 与真实字段（不再断言"识别正文"桩字段）；
7. 新增：pdf 扩展名命中文本提取分支，结果不含"模拟 OCR"；
8. 新增：fileType 缺失但 fileName 以 `.pdf` 结尾仍命中（复用 `resolveExtension` 兜底）；
9. 新增：损坏 PDF → 空列表（`isError` / errorCode 断言放工具层测试）；
10. 既有 `nonWordDocumentKeepsOriginalOcrFlow` 中 `new byte[]{9,9}` 伪 PDF 用例**改用真实扩展名**
    （png / txt），避免"2 字节假 PDF 命中 PDF 分支"的歧义断言。

`OcrRecognizeToolTest`：现有用例全部保留；新增 pdf 空文本 → `isError: true` 且 texts 为 `[]`。

---

## 7. 风险与缓解

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 行内"字段名 值"同串（如 `* 勘察人员 周奔`） | 键值整理需后处理 | 本期保留原始行；二期按空格 / `*` 分隔配对，或用 mock ocr json 的 bbox 行合并 |
| 表格 / 多栏顺序偏差 | 字段顺序偶有错位 | `setSortByPosition(true)` 缓解；字段定位靠 `[第 N 页]` + 字段名而非顺序 |
| 加密 / 损坏 / 异常结构 PDF | 解析异常 | catch 收敛空列表 + 明确 errorCode，不崩服务 |
| 恶意超大文档 / zip bomb | 内存 / CPU | `MAX_PAGES=50` + `MAX_TEXT_CHARS=30000` + 既有 10MB 文件上限 |
| CID 字体缺映射 → 乱码或空 | 提取质量 | 空文本归入扫描件报错；乱码属 PDF 自身问题，如实返回 |
| 依赖 mock 固定数据的隐性依赖 | 行为变化 | 测试与文档同步（§5.2），平台侧无依赖（texts 仍是字符串数组） |

---

## 8. 待评审决策点

1. **扫描件错误码**：新增 `SCANNED_PDF_NO_TEXT`，还是复用 `TEXT_EMPTY` + 区分 message？（建议新增）
2. **`fixedRecognizedLines()` 与 `recognizeFile()` 桩的去留**：PDF 分支落地后，"其它类型"兜底仍用固定数据
   （会继续对 png / txt 等返回五方踏勘假数据），是否同步改为直接返回空列表 + 明确错误码？
   （建议：一并改为空列表，彻底消除假数据；但会改变其它类型的行为，需确认无下游依赖）
3. **截断表达**：追加 `[已截断]` 文本行（零契约改动），还是给 structuredContent 增加 `truncated` 布尔字段
   （outputSchema 需同步）？（建议本期用文本行，字段化留二期）
4. `MAX_PAGES` / `MAX_TEXT_CHARS` 默认值确认（建议 50 / 30000，可配置化放二期）。

---

## 9. 实施计划与验收

| 步骤 | 内容 | 验证 |
| --- | --- | --- |
| 1 | pom 引 pdfbox:2.0.22 | `mvn dependency:tree` 确认 2.0.22、无 3.x |
| 2 | 新增 PdfTextExtractor + 单测 | `mvn test` 新用例全绿 |
| 3 | Service 加 PDF 分支 + 改既有断言 | 全量 `mvn test` 全绿 |
| 4 | README / docs 同步 | 人工核对 3.2 流程与替换点表述 |
| 5 | 启动 jar 冒烟 `tools/call ocr_recognize` | texts 含 6 个页码标记，与 pypdf 实测行数同量级；无 base64 blob |

验收底线：**传 PDF 不再返回 mock 固定数据**；损坏 PDF 不抛异常；`texts` 契约恒为字符串数组。

---

## 10. 二期方向（本期不做）

- 扫描件 OCR 兜底（逐页渲染 + OCR，接真实服务时引用本方案 §3.3 错误码）；
- 字段键值配对整理（`ocr-field-formatter` 思路 / mock ocr json 的 bbox 参照）；
- `truncated` 结构化字段与分页入参（`page` / `offset`）；
- `MAX_PAGES` / `MAX_TEXT_CHARS` 配置化（`McpServerProperties`）。
