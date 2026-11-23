package com.xin.test.mcp.util;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableIterator;
import org.apache.poi.hwpf.usermodel.TableRow;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Word 文档数据直接抽取（纯 Java / Apache POI，不转 PDF、不经过 OCR）。
 *
 * <ul>
 *   <li>{@code .docx}：XWPF 按文档顺序读取段落与表格单元格；</li>
 *   <li>{@code .doc}：HWPF 读取段落与表格单元格。</li>
 * </ul>
 * 输出统一为 {@code List<String>}，段落为原文，表格行输出为
 * {@code 表格N 第R行 第C列: 值}，便于下游做字段映射。异常时返回已抽取内容或空列表，不外抛。
 */
public final class WordDataExtractor {

    private static final Logger log = LoggerFactory.getLogger(WordDataExtractor.class);

    private WordDataExtractor() {
    }

    public static List<String> extract(byte[] content, String extension) {
        List<String> lines = new ArrayList<>();
        if (content == null || content.length == 0) {
            return lines;
        }
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        if ("docx".equals(ext)) {
            extractDocx(content, lines);
        } else if ("doc".equals(ext)) {
            extractDoc(content, lines);
        }
        return lines;
    }

    private static void extractDocx(byte[] content, List<String> lines) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(content))) {
            int tableIndex = 0;
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph) {
                    addLine(lines, ((XWPFParagraph) element).getText());
                } else if (element instanceof XWPFTable) {
                    tableIndex++;
                    appendDocxTable(lines, tableIndex, ((XWPFTable) element).getRows());
                }
            }
        } catch (Exception e) {
            log.warn("docx 解析失败，已抽取 {} 行", lines.size(), e);
        }
    }

    private static void appendDocxTable(List<String> lines, int tableIndex, List<XWPFTableRow> rows) {
        for (int r = 0; r < rows.size(); r++) {
            List<XWPFTableCell> cells = rows.get(r).getTableCells();
            for (int c = 0; c < cells.size(); c++) {
                addCell(lines, "表格" + tableIndex + " 第" + (r + 1) + "行 第" + (c + 1) + "列: ",
                        cells.get(c).getText());
            }
        }
    }

    private static void extractDoc(byte[] content, List<String> lines) {
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(content))) {
            WordExtractor extractor = new WordExtractor(doc);
            String[] paragraphs = extractor.getParagraphText();
            if (paragraphs != null) {
                for (String paragraph : paragraphs) {
                    addLine(lines, paragraph);
                }
            }

            TableIterator iterator = new TableIterator(doc.getRange());
            int tableIndex = 0;
            while (iterator.hasNext()) {
                Table table = iterator.next();
                tableIndex++;
                for (int r = 0; r < table.numRows(); r++) {
                    TableRow row = table.getRow(r);
                    for (int c = 0; c < row.numCells(); c++) {
                        addCell(lines, "表格" + tableIndex + " 第" + (r + 1) + "行 第" + (c + 1) + "列: ",
                                row.getCell(c).text());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("doc 解析失败，已抽取 {} 行", lines.size(), e);
        }
    }

    private static void addLine(List<String> lines, String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (!trimmed.isEmpty()) {
            lines.add(trimmed);
        }
    }

    private static void addCell(List<String> lines, String prefix, String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (!trimmed.isEmpty()) {
            lines.add(prefix + trimmed);
        }
    }
}
