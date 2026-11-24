package com.xin.test.mcp.util;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * PDF 文本层数据直接抽取（纯 Java / Apache PDFBox 2.0.x，不渲染图片、不经过 OCR）。
 * <p>
 * 逐页提取有文本层 PDF 的文字，输出以 {@code [第 N 页]} 行分组，超长按字符上限截断并追加提示行。
 * <ul>
 *   <li>契约：结果恒为非 null 的 {@code List<String>}，不向调用方抛异常；</li>
 *   <li>入参为空、PDF 损坏 / 加密、全部页均无文本层（原件为扫描件）时返回空列表；</li>
 *   <li>空列表即「无文本层」信号，调用方（{@code OcrRecognizeService}）据此回退 OCR 识别流程。</li>
 * </ul>
 */
public final class PdfTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfTextExtractor.class);

    /** 页数上限：防御异常结构文档，避免超大 PDF 长时间占用解析线程。 */
    static final int MAX_PAGES = 50;

    /** 单次返回文本总字符上限：防止撑爆模型上下文。 */
    static final int MAX_TEXT_CHARS = 30000;

    private PdfTextExtractor() {
    }

    /** 默认上限入口。 */
    public static List<String> extract(byte[] content) {
        return extract(content, MAX_TEXT_CHARS);
    }

    /**
     * 逐页提取 PDF 文本层，按 {@code [第 N 页]} 分组输出，超长截断。
     *
     * @param content      PDF 字节内容（允许为 null，按空内容处理）
     * @param maxTextChars 文本总字符上限（包可见便于单测传入小值验证截断）
     * @return 文本行列表；无文本层 / 解析异常时为空列表
     */
    static List<String> extract(byte[] content, int maxTextChars) {
        List<String> lines = new ArrayList<>();
        if (content == null || content.length == 0) {
            return lines;
        }
        try (PDDocument doc = PDDocument.load(content)) {
            int pageCount = Math.min(doc.getNumberOfPages(), MAX_PAGES);
            PDFTextStripper stripper = new PDFTextStripper();
            // 按版面位置排序输出，表单类文档「字段名 值」的顺序基本正确
            stripper.setSortByPosition(true);
            int totalChars = 0;
            boolean truncated = false;
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(doc);
                if (pageText == null || pageText.trim().isEmpty()) {
                    // 该页无文本层（扫描页）：跳过，不中断其余页
                    continue;
                }
                lines.add("[第 " + page + " 页]");
                for (String line : pageText.split("\\R+")) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    totalChars += trimmed.length();
                    if (totalChars > maxTextChars) {
                        truncated = true;
                        break;
                    }
                    lines.add(trimmed);
                }
                if (truncated) {
                    break;
                }
            }
            if (truncated && !lines.isEmpty()) {
                lines.add("[已截断：超过 " + maxTextChars + " 字符上限]");
            }
            return lines;
        } catch (Exception e) {
            // 损坏 / 加密 / 非法结构：收敛为空列表，调用方据此回退 OCR 识别
            log.warn("PDF 文本层解析失败，按无文本层处理（回退 OCR），size={}B, cause={}",
                    content.length, e.getMessage());
            return lines;
        }
    }
}
