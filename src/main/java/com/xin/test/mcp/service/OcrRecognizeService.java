package com.xin.test.mcp.service;

import com.xin.test.mcp.dto.FileContentDTO;
import com.xin.test.mcp.util.ExcelDataExtractor;
import com.xin.test.mcp.util.PdfTextExtractor;
import com.xin.test.mcp.util.WordDataExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文件内容识别工具：按 fileId 读取文件字节与 MIME 类型；下载完成后先按扩展名判断类型，
 * {@code .doc}/{@code .docx} 用 Apache POI 抽取段落与表格，{@code .xls}/{@code .xlsx}
 * 用 POI 抽取工作表单元格，{@code .pdf} 用 PDFBox 逐页抽取文本层（均不转图、不经过 OCR）；
 * PDF 无文本层（原件为扫描件）或解析失败时回退 OCR 识别流程，其它类型保持原有直接识别流程。
 * 非直抽类型的识别结果仍为内置五方踏勘 PDF 的固定数据，统一整理为 {@code List<String>} 返回。
 *
 * <p>核心工具方法 {@link #recognize(String)} 的契约：
 * <ul>
 *   <li>返回类型恒为 {@code List<String>}，永不为 {@code null}、永不向调用方抛异常；</li>
 *   <li>入参校验失败（null / 空白 / 长度 &gt; 128）→ 返回空列表；</li>
 *   <li>取数失败、文件为空、Word / Excel / PDF 解析失败且无 OCR 兜底结果、识别异常、识别无结果 → 返回空列表，不降级返回原始字节；</li>
 *   <li>识别成功 → 返回按字段顺序整理的文本行列表。</li>
 * </ul>
 *
 * <p>{@code fileId → byte[] + mimeType →（doc/docx、xls/xlsx 走 POI 直抽 / pdf 走 PDFBox 文本层直抽，
 * 扫描件回退与其它类型走 OCR 桩）→ List<String>} 的完整流程封装在 {@link #recognize(String)} 内部。
 * Word / Excel / PDF 抽取为纯 Java 实现，OCR 具体实现仍为桩，调用方式与返回结构保持不变。
 */
@Service
public class OcrRecognizeService {

    private static final Logger log = LoggerFactory.getLogger(OcrRecognizeService.class);

    /** fileId 最大长度，与 MCP 工具 inputSchema 保持一致 */
    public static final int MAX_FILE_ID_LENGTH = 128;

    private final McpFileDownloadService downloadService;

    @Autowired
    public OcrRecognizeService(McpFileDownloadService downloadService) {
        this.downloadService = downloadService;
    }

    /** 无参构造：便于脱离 Spring 容器直接调用与单测。 */
    public OcrRecognizeService() {
        this(new McpFileDownloadService());
    }

    /**
     * 工具方法：按 fileId 识别对应文件中的内容，返回整理后的文本行列表。
     *
     * @param fileId 文件标识（必填，1~128 字符）
     * @return 识别结果，恒为 {@code List<String>}；入参非法、文件为空或识别异常时为空列表
     */
    public List<String> recognize(String fileId) {
        // 1. 基本入参校验：不抛异常，统一返回空列表
        if (!isValidFileId(fileId)) {
            log.warn("OCR 入参校验失败，fileId={}", fileId);
            return emptyResult();
        }
        String normalized = fileId.trim();

        long start = System.currentTimeMillis();
        try {
            // 2. 按 fileId 获取文件 byte[] 内容与 MIME 类型
            FileContentDTO file = downloadService.downloadFileDetail(normalized);
            if (file == null || file.getContent() == null || file.getContent().length == 0) {
                log.warn("OCR 文件内容为空，fileId={}", normalized);
                return emptyResult();
            }

            // 3. 下载完成后先判断类型：doc/docx、xls/xlsx 直接用 POI 抽数据，不转 PDF、不走 OCR
            String extension = resolveExtension(file);
            if (isWordExtension(extension)) {
                List<String> extracted = extractWordText(file, extension);
                if (extracted == null || extracted.isEmpty()) {
                    log.warn("Word 数据抽取无结果，fileId={}, ext={}, fileName={}",
                            normalized, extension, file.getFileName());
                    return emptyResult();
                }
                log.info("Word 数据抽取完成，fileId={}, ext={}, fileName={}, lines={}, cost={}ms",
                        normalized, extension, file.getFileName(), extracted.size(),
                        System.currentTimeMillis() - start);
                return extracted;
            }
            if (isExcelExtension(extension)) {
                List<String> extracted = extractExcelText(file, extension);
                if (extracted == null || extracted.isEmpty()) {
                    log.warn("Excel 数据抽取无结果，fileId={}, ext={}, fileName={}",
                            normalized, extension, file.getFileName());
                    return emptyResult();
                }
                log.info("Excel 数据抽取完成，fileId={}, ext={}, fileName={}, lines={}, cost={}ms",
                        normalized, extension, file.getFileName(), extracted.size(),
                        System.currentTimeMillis() - start);
                return extracted;
            }

            // 3.5 PDF：先抽文本层（有文本层的 PDF 无需 OCR）；为空（原件为扫描件 / 解析失败）时回退 OCR 流程
            if (isPdfExtension(extension)) {
                List<String> extracted = extractPdfText(file, extension);
                if (extracted != null && !extracted.isEmpty()) {
                    log.info("PDF 文本层抽取完成，fileId={}, ext={}, fileName={}, lines={}, cost={}ms",
                            normalized, extension, file.getFileName(), extracted.size(),
                            System.currentTimeMillis() - start);
                    return extracted;
                }
                log.warn("PDF 无文本层（疑似扫描件或损坏），回退 OCR 识别，fileId={}, fileName={}",
                        normalized, file.getFileName());
            }

            // 4. 其它类型文件 / PDF 扫描件回退：原有直接识别流程（OCR 桩，当前返回固定数据）
            List<String> recognized = recognizeByOcr(
                    file.getContent(), file.getMimeType(), file.getFileName());
            if (recognized == null || recognized.isEmpty()) {
                log.warn("OCR 无识别结果，fileId={}, mimeType={}, fileName={}",
                        normalized, file.getMimeType(), file.getFileName());
                return emptyResult();
            }

            log.info("OCR 识别完成，fileId={}, fileName={}, mimeType={}, size={}B, lines={}, cost={}ms",
                    normalized, file.getFileName(), file.getMimeType(), file.getContent().length,
                    recognized.size(), System.currentTimeMillis() - start);
            return recognized;
        } catch (Exception e) {
            // 取数 / 解析 / 识别链路任意异常都不外抛，统一收敛为空列表
            log.error("OCR 识别失败，fileId={}, cost={}ms",
                    normalized, System.currentTimeMillis() - start, e);
            return emptyResult();
        }
    }

    /**
     * OCR 识别桩方法（mock）：不解析真实字节，按内容与 MIME 类型生成确定性的模拟字段，
     * 再转换为 {@code List<String>}。线上替换为 PDFBox / POI / OCR 服务调用即可。
     *
     * @param content  文件字节内容（允许为 null，桩实现按空内容处理）
     * @param mimeType 文件 MIME 类型（允许为 null，桩实现按未知类型处理）
     * @return 模拟的识别文本行，恒为非 null 的 {@code List<String>}
     */
    public List<String> recognizeFile(byte[] content, String mimeType) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("文件类型", isBlank(mimeType) ? "未知" : mimeType.trim());
        fields.put("文件大小", (content == null ? 0 : content.length) + " 字节");
        fields.put("内容指纹", Integer.toHexString(Arrays.hashCode(content)));
        fields.put("识别标题", "模拟 OCR 识别结果");
        fields.put("识别正文", "项目名称：示例项目；踏勘地点：示例地点；参与单位：甲、乙、丙、丁、戊");
        fields.put("识别结论", "内容识别完成（mock 数据，未接入真实 OCR 服务）");
        return toLines(fields);
    }

    /**
     * Word 数据抽取：doc/docx 直接用 POI 读段落与表格，不转 PDF、不经过 OCR。
     * 包可见便于单测直接校验抽取结果。
     */
    List<String> extractWordText(FileContentDTO file, String extension) {
        return WordDataExtractor.extract(file.getContent(), extension);
    }

    /**
     * Excel 数据抽取：xls/xlsx 直接用 POI 读工作表单元格，不转 PDF、不经过 OCR。
     * 包可见便于单测直接校验抽取结果。
     */
    List<String> extractExcelText(FileContentDTO file, String extension) {
        return ExcelDataExtractor.extract(file.getContent(), extension);
    }

    /**
     * PDF 文本层抽取：pdf 直接用 PDFBox 逐页读文本层，不渲染图片、不经过 OCR；
     * 无文本层（扫描件）或解析失败时返回空列表，由 recognize 回退 OCR 流程。
     * 包可见便于单测直接校验抽取结果。
     */
    List<String> extractPdfText(FileContentDTO file, String extension) {
        return PdfTextExtractor.extract(file.getContent());
    }

    /** 判断是否 Word 文档扩展名，调用方已统一转小写。 */
    private static boolean isWordExtension(String extension) {
        return "doc".equals(extension) || "docx".equals(extension);
    }

    /** 判断是否 Excel 表格扩展名，调用方已统一转小写。 */
    private static boolean isExcelExtension(String extension) {
        return "xls".equals(extension) || "xlsx".equals(extension);
    }

    /** 判断是否 PDF 扩展名，调用方已统一转小写。 */
    private static boolean isPdfExtension(String extension) {
        return "pdf".equals(extension);
    }

    /** 取扩展名：fileType 优先，其次 fileName；统一小写并去掉前导点。 */
    private static String resolveExtension(FileContentDTO file) {
        String ext = trimLeadingDot(file.getFileType());
        if (ext.isEmpty() && file.getFileName() != null) {
            int dot = file.getFileName().lastIndexOf('.');
            if (dot >= 0 && dot < file.getFileName().length() - 1) {
                ext = trimLeadingDot(file.getFileName().substring(dot + 1));
            }
        }
        return ext.toLowerCase(Locale.ROOT);
    }

    private static String trimLeadingDot(String value) {
        if (value == null) {
            return "";
        }
        String v = value.trim();
        return v.startsWith(".") ? v.substring(1).trim() : v;
    }

    /**
     * OCR 识别入口：处理非 Word / Excel / PDF 直抽文件，以及 PDF 无文本层（扫描件）的回退识别，
     * content/fileName 为原始下载内容。具体实现仍为桩，暂返回内置固定数据；
     * 接入真实 OCR 时只改此方法（渲染图片 + 逐页调用 + 按页拼接）。
     */
    private List<String> recognizeByOcr(byte[] content, String mimeType, String fileName) {
        log.debug("OCR 入参：fileName={}, mimeType={}, size={}B",
                fileName, mimeType, content == null ? 0 : content.length);
        return fixedRecognizedLines();
    }

    /**
     * 内置五方踏勘 PDF（file/wufang-survey-report-fixed.pdf，6 页）对应的固定识别数据。
     * 字段名与取值均取自该 PDF；输出格式与原有桩实现一致，仍为 "字段名: 值" 文本行。
     */
    private static List<String> fixedRecognizedLines() {
        List<String> lines = new ArrayList<>();

        // 原有字段结构保持不变，取值改为内置 PDF 对应内容
        lines.add("文件类型: application/pdf");
        lines.add("文件大小: 110790 字节");
        lines.add("内容指纹: 9a490180");
        lines.add("识别标题: 五方踏勘报告");
        lines.add("识别正文: 勘察人员：周奔；勘察日期：2026-11-12；项目所属建筑（物业）：东湖物管；联系人：周奔；电话：15507176316");
        lines.add("识别结论: 五方踏勘报告共 6 页，已按页面字段完成识别（固定数据）");

        // 第 1 页：基本勘察信息 / 装修房屋建筑结构现状及改造
        lines.add("勘察人员: 周奔");
        lines.add("勘察日期: 2026-11-12");
        lines.add("项目所属建筑（物业）: 东湖物管");
        lines.add("联系人: 周奔");
        lines.add("电话: 15507176316");
        lines.add("新址目前状况: 建设中 / 毛坯建筑 / 简单装修 / 二次装修 / 其他");
        lines.add("新址目前状况其他描述: 无");
        lines.add("建筑物分类: 多层（单层） / 一类高层 / 二类高层 / 超高层 / 地下 / 其他");
        lines.add("建筑物分类其他描述: 无");
        lines.add("建筑物性质: 住宅楼 / 宾馆 / 办公楼 / 商场（卖场） / 医院 / 展馆（体育馆） / 综合楼 / 其他");
        lines.add("建筑物性质其他描述: 无");
        lines.add("建筑物结构类型: 钢+混凝土 / 砖混结构 / 钢结构 / 砖木结构 / 其他");
        lines.add("建筑物结构类型其他描述: 无");
        lines.add("建筑物总楼层数（地上）: 10");
        lines.add("建筑物总楼层数（地下）: 1");
        lines.add("地面建筑总高: 32.5 m");
        lines.add("租赁面积（项目所在项目）: 123 ㎡");
        lines.add("项目所在项目层数: 8");
        lines.add("项目所在项目总层数: 10");

        // 第 2 页：楼层租用面积 / 结构现状 / 周围情况及改造
        lines.add("8F 楼层租用面积: 123.00 ㎡，楼层高度 3.60 m，主梁底高度 3.20 m，次梁底高度 2.90 m，板底高度 2.70 m，实测最低高度 2.60 m");
        lines.add("9F 楼层租用面积: 123.00 ㎡，楼层高度 3.60 m，主梁底高度 3.20 m，次梁底高度 2.90 m，板底高度 2.70 m，实测最低高度 2.60 m");
        lines.add("10F 楼层租用面积: 123.00 ㎡，楼层高度 3.60 m，主梁底高度 3.20 m，次梁底高度 2.90 m，板底高度 2.70 m，实测最低高度 2.60 m");
        lines.add("区域内是否包含悬梁结构: 是 / 否");
        lines.add("悬梁结构位置: 项目北侧梁下");
        lines.add("区域内是否包含楼梯结构: 是，无需改动 / 是，需要改动 / 否，无需新建 / 否，需要新建");
        lines.add("区域内是否有地下室: 没有 / 全部 / 部分");
        lines.add("地下室位置: 地下一层东北角");
        lines.add("与相邻建筑分隔: 砖墙分隔 / 玻璃墙分隔 / 轻钢龙骨墙 / 新砌砖墙 / 其他");
        lines.add("建筑分隔（周围情况）其他: 无");
        lines.add("除主入口外是否有第二出入口: 是，需改动 / 是，不改动 / 否，需新设 / 否，不新设");
        lines.add("消防疏散通道及门: 有 / 无，无需预留 / 无，需要预留");
        lines.add("消防疏散通道预留位置: 无");
        lines.add("与相邻建筑分隔（卫生间）: 既有无需改建 / 既有需改建 / 既有需移位 / 新建卫生间");
        lines.add("新建卫生间位置描述: 无");

        // 第 3 页：外立面现状及改造 / 电气设施现状及改造
        lines.add("与相邻建筑分隔（外立面）: 玻璃幕墙 / 金属幕墙 / 石材饰面 / 涂料 / 砖墙 / 其他");
        lines.add("建筑分隔（外立面）其他: 无");
        lines.add("外立面改动权限: 原有外立面可改动 / 原有外立面不可改动 / 主入口改动 / 否，不新设 / 其他");
        lines.add("外立面改动权限其他: 无");
        lines.add("外立面是否有挑廊（挑檐）: 无 / 有");
        lines.add("挑廊（挑檐）进深尺寸: 1200 mm");
        lines.add("挑廊（挑檐）离地高度: 3000 mm");
        lines.add("新建招牌形式: 横挑招牌 / 竖挑招牌 / 独立式招牌 / 置顶招牌 / 小型识别招牌 / 引路招牌 / 其他");
        lines.add("招牌形式其他: 无");
        lines.add("招牌灯光形式: 内光源灯箱 / 外置光源 / 不需要光源");
        lines.add("入口处是否有台阶: 无 / 有");
        lines.add("室内外高差: 150 mm");
        lines.add("残疾人坡道: 既有残疾人坡道 / 无需新设 / 需新设");
        lines.add("现场是否有条件新设: 具备新设条件，坡道净宽 1.2 米，坡度 1:12");
        lines.add("物业需提供需方电容量: 111 kw");

        // 第 4 页：电气 / 给排水设施
        lines.add("几路进线: 2");
        lines.add("电容量是否扩容: 不需扩容 / 需扩容");
        lines.add("扩容量: 30 kw");
        lines.add("电源提供方式: 双电源 / 单电源");
        lines.add("配电箱状况: 有 / 无，需自建，电缆至物业指定位置");
        lines.add("配电箱个数: 2");
        lines.add("箱内总开关1: 施耐德 NSX250N 3P 200A");
        lines.add("箱内总开关2: 施耐德 NSX160N 3P 125A");
        lines.add("箱内总开关3: 施耐德 iC65N 3P 63A");
        lines.add("箱体位置描述: 一层东侧弱电间内，靠北墙安装");
        lines.add("电缆预估长度: 35 m");
        lines.add("配电箱照片: WF-20261112-EL-01.jpg");
        lines.add("供水点位置: 一层东侧管井内");
        lines.add("供水管径: DN32 / DN40 / DN50 / DN65 / 其他");
        lines.add("其他管径: 无");

        // 第 5 页：排水排污 / 消防 / 空调系统设置
        lines.add("排水排污条件: 排入物业指定的排污管道 / 自行设置排水管道排入市政管网");
        lines.add("自行设置排水管道位置描述: 无");
        lines.add("喷淋系统: 无 / 有");
        lines.add("喷淋主管位置: 不可移位 / 可移位");
        lines.add("消火栓现状: 既有消火栓 / 无消火栓，需新建 / 无消火栓，无需新建");
        lines.add("消火栓数量: 4");
        lines.add("消火栓是否可移位: 可移位 / 不可移位");
        lines.add("消防报警系统设置: 独立设置 / 接入原系统 / 增设手动消防报警系统");
        lines.add("消防广播系统设置: 有 / 无");
        lines.add("消防排烟设施现状: 无，不需新建 / 无，需新建 / 既有消防排烟设施");
        lines.add("既有消防排烟设施描述: 一层北侧排烟风管及排烟口");
        lines.add("是否有泵房（泵房是否两路进水）: 无");
        lines.add("冷凝管离地净高: 2.8 m");
        lines.add("空调系统现状: 接入空调原有系统 / 既有空调系统改建 / 新建空调系统");

        // 第 6 页：空调系统 / 弱电系统 / 设计限制
        lines.add("空调系统类型: 风管式中央空调 / 盘管式中央空调 / VRV空调");
        lines.add("空调系统外机位置，管线长度及对周围环境造成影响进行描述: 外机位于三层屋面，管线长度约 20 米，对周围环境影响较小");
        lines.add("新排风口在建筑物所处位置描述: 新排风口位于建筑物北侧外墙");
        lines.add("请写明旧机移机的型号和数量: 旧机 2 台，型号 KFR-72LW");
        lines.add("需新设弱电系统: 监控系统 / 门禁系统 / 110报警系统 / 网络系统 / 有线电视系统 / 背景音响系统 / 电话系统 / 其他");
        lines.add("其他弱电系统: 无");
        lines.add("现况对设计上的限制: 无");
        lines.add("机电设备对设计上的限制: 无");

        return lines;
    }

    /** 字段 Map → List<String>：按插入顺序输出 "字段名: 值"，跳过空值。 */
    private static List<String> toLines(Map<String, String> fields) {
        List<String> lines = new ArrayList<>();
        if (fields == null) {
            return lines;
        }
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (!value.isEmpty()) {
                lines.add(entry.getKey() + ": " + value);
            }
        }
        return lines;
    }

    private static boolean isValidFileId(String fileId) {
        return fileId != null
                && !fileId.trim().isEmpty()
                && fileId.trim().length() <= MAX_FILE_ID_LENGTH;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 恒返回可变的空列表，避免调用方追加元素时抛 UnsupportedOperationException。 */
    private static List<String> emptyResult() {
        return new ArrayList<>();
    }
}
