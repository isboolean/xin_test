package com.xin.test.mcp.util;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfTextExtractorTest {

    /** 读取随应用打包的示例 PDF（6 页、有文本层）作为真实样本。 */
    private static byte[] builtInPdf() throws Exception {
        try (InputStream in = PdfTextExtractorTest.class
                .getResourceAsStream("/file/wufang-survey-report-fixed.pdf")) {
            assertNotNull(in, "内置示例 PDF 应存在于 classpath");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** 用 PDFBox 现场生成一页空白 PDF（无文本层，模拟扫描件）。 */
    private static byte[] blankPdf() throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void builtInPdfExtractsAllSixPages() throws Exception {
        List<String> lines = PdfTextExtractor.extract(builtInPdf());

        assertFalse(lines.isEmpty());
        for (int page = 1; page <= 6; page++) {
            assertTrue(lines.contains("[第 " + page + " 页]"), "应包含第 " + page + " 页分组标记");
        }
        assertTrue(lines.stream().anyMatch(s -> s.contains("五方踏勘报告")));
        // 真实文本行不应出现 OCR 桩固定字段
        assertFalse(lines.stream().anyMatch(s -> s.contains("识别标题")));
    }

    @Test
    void nullAndEmptyContentReturnEmptyList() {
        assertTrue(PdfTextExtractor.extract(null).isEmpty());
        assertTrue(PdfTextExtractor.extract(new byte[0]).isEmpty());
    }

    @Test
    void corruptedBytesReturnEmptyList() {
        // 损坏字节（非合法 PDF）→ 空列表（回退 OCR 信号），不抛异常
        assertTrue(PdfTextExtractor.extract(new byte[]{1, 2, 3}).isEmpty());
    }

    @Test
    void blankPagePdfHasNoTextLayer() throws Exception {
        // 空白页 PDF：结构合法但无文本层 → 空列表，作为调用方回退 OCR 的信号
        assertTrue(PdfTextExtractor.extract(blankPdf()).isEmpty());
    }

    @Test
    void longTextIsTruncated() throws Exception {
        List<String> lines = PdfTextExtractor.extract(builtInPdf(), 100);

        assertFalse(lines.isEmpty());
        assertEquals("[已截断：超过 100 字符上限]", lines.get(lines.size() - 1));
    }
}
