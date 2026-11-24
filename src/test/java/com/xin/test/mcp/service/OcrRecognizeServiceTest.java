package com.xin.test.mcp.service;

import com.xin.test.mcp.dto.FileContentDTO;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OcrRecognizeServiceTest {

    private final OcrRecognizeService service = new OcrRecognizeService();

    @Test
    void nullFileIdReturnsEmptyList() {
        List<String> result = service.recognize(null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
        result.add("可追加，说明返回的是可变 List，而非不可变空集合");
        assertEquals(1, result.size());
    }

    @Test
    void blankFileIdReturnsEmptyList() {
        List<String> result = service.recognize("   ");
        assertNotNull(result);
        assertTrue(result.isEmpty());
        assertTrue(service.recognize("").isEmpty());
    }

    @Test
    void tooLongFileIdReturnsEmptyList() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < OcrRecognizeService.MAX_FILE_ID_LENGTH + 1; i++) {
            sb.append('a');
        }
        List<String> result = service.recognize(sb.toString());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void validFileIdReturnsNonEmptyStringList() {
        List<String> result = service.recognize("F20261116001");
        assertNotNull(result);
        assertFalse(result.isEmpty());
        for (String line : result) {
            assertNotNull(line);
            assertFalse(line.trim().isEmpty());
        }
        assertTrue(result.stream().anyMatch(s -> s.contains("文件类型")));
        assertTrue(result.stream().anyMatch(s -> s.contains("识别正文")));
    }

    @Test
    void sameFileIdReturnsDeterministicResult() {
        assertEquals(service.recognize("F1"), service.recognize("F1"));
    }

    @Test
    void downloadFailureReturnsEmptyList() {
        McpFileDownloadService broken = mock(McpFileDownloadService.class);
        when(broken.downloadFileDetail(anyString())).thenThrow(new IllegalStateException("上游故障"));
        OcrRecognizeService ocr = new OcrRecognizeService(broken);

        List<String> result = ocr.recognize("F404");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void emptyFileContentReturnsEmptyList() {
        McpFileDownloadService stub = mock(McpFileDownloadService.class);
        when(stub.downloadFileDetail(anyString()))
                .thenReturn(new FileContentDTO("F2", "a.pdf", "application/pdf", new byte[0]));
        OcrRecognizeService ocr = new OcrRecognizeService(stub);

        List<String> result = ocr.recognize("F2");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void recognizeFileStubAlwaysReturnsStringList() {
        List<String> result = service.recognizeFile(new byte[]{1, 2, 3}, "application/pdf");
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertTrue(result.stream().anyMatch(s -> s.contains("application/pdf")));
        assertTrue(result.stream().anyMatch(s -> s.contains("3 字节")));
        for (String line : result) {
            assertNotNull(line);
        }
    }

    @Test
    void recognizeFileStubHandlesNullInputs() {
        List<String> result = service.recognizeFile(null, null);
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertTrue(result.stream().anyMatch(s -> s.contains("未知")));
    }

    @Test
    void docxDataIsExtractedWithoutOcr() throws Exception {
        byte[] docxBytes;
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph paragraph = doc.createParagraph();
            paragraph.createRun().setText("勘察人员：张三");
            XWPFTable table = doc.createTable(1, 2);
            table.getRow(0).getCell(0).setText("电话");
            table.getRow(0).getCell(1).setText("13800000000");
            doc.write(out);
            docxBytes = out.toByteArray();
        }

        McpFileDownloadService stub = mock(McpFileDownloadService.class);
        when(stub.downloadFileDetail(anyString())).thenReturn(new FileContentDTO(
                "F-DOCX", "报告.DOCX",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "docx", docxBytes));

        List<String> result = new OcrRecognizeService(stub).recognize("F-DOCX");

        assertFalse(result.isEmpty());
        assertTrue(result.stream().anyMatch(s -> s.contains("勘察人员：张三")));
        assertTrue(result.stream().anyMatch(s -> s.contains("表格1 第1行 第2列: 13800000000")));
        assertFalse(result.stream().anyMatch(s -> s.contains("模拟 OCR")));
    }

    @Test
    void hwpfDependencyIsAvailableForLegacyDoc() throws Exception {
        // 防止 poi-scratchpad 被误删后老式 .doc 静默降级为空
        assertNotNull(Class.forName("org.apache.poi.hwpf.HWPFDocument"));
    }

    @Test
    void invalidWordContentReturnsEmptyList() {
        McpFileDownloadService stub = mock(McpFileDownloadService.class);
        when(stub.downloadFileDetail(anyString())).thenReturn(new FileContentDTO(
                "F-DOC", "报告.DOC", "application/msword", "doc", new byte[]{1, 2, 3}));

        assertTrue(new OcrRecognizeService(stub).recognize("F-DOC").isEmpty());
    }

    @Test
    void xlsxDataIsExtractedWithoutOcr() throws Exception {
        byte[] xlsxBytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("踏勘表");
            sheet.createRow(0).createCell(0).setCellValue("联系人");
            sheet.createRow(0).createCell(1).setCellValue("周奔");
            workbook.write(out);
            xlsxBytes = out.toByteArray();
        }

        McpFileDownloadService stub = mock(McpFileDownloadService.class);
        when(stub.downloadFileDetail(anyString())).thenReturn(new FileContentDTO(
                "F-XLSX", "报告.XLSX",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                null, xlsxBytes));

        List<String> result = new OcrRecognizeService(stub).recognize("F-XLSX");

        assertFalse(result.isEmpty());
        assertTrue(result.stream().anyMatch(s -> s.contains("工作表1[踏勘表]")));
        assertTrue(result.stream().anyMatch(s -> s.contains("第1行 第2列: 周奔")));
        assertFalse(result.stream().anyMatch(s -> s.contains("模拟 OCR")));
    }

    @Test
    void xlsDataIsExtractedWithoutOcr() throws Exception {
        byte[] xlsBytes;
        try (HSSFWorkbook workbook = new HSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("老式表").createRow(0).createCell(0).setCellValue("旧格式数据");
            workbook.write(out);
            xlsBytes = out.toByteArray();
        }

        McpFileDownloadService stub = mock(McpFileDownloadService.class);
        when(stub.downloadFileDetail(anyString())).thenReturn(new FileContentDTO(
                "F-XLS", "数据.XLS", "application/vnd.ms-excel", "xls", xlsBytes));

        List<String> result = new OcrRecognizeService(stub).recognize("F-XLS");

        assertFalse(result.isEmpty());
        assertTrue(result.stream().anyMatch(s -> s.contains("旧格式数据")));
        assertFalse(result.stream().anyMatch(s -> s.contains("模拟 OCR")));
    }

    @Test
    void invalidExcelContentReturnsEmptyList() {
        McpFileDownloadService stub = mock(McpFileDownloadService.class);
        when(stub.downloadFileDetail(anyString())).thenReturn(new FileContentDTO(
                "F-BAD-XLSX", "坏表.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "xlsx", new byte[]{1, 2, 3}));

        assertTrue(new OcrRecognizeService(stub).recognize("F-BAD-XLSX").isEmpty());
    }

    @Test
    void nonWordDocumentKeepsOriginalOcrFlow() {
        McpFileDownloadService stub = mock(McpFileDownloadService.class);
        when(stub.downloadFileDetail(anyString())).thenReturn(new FileContentDTO(
                "F-PDF", "a.pdf", "application/pdf", "pdf", new byte[]{9, 9}));

        List<String> result = new OcrRecognizeService(stub).recognize("F-PDF");

        assertFalse(result.isEmpty());
        assertTrue(result.stream().anyMatch(s -> s.contains("文件类型")));
        assertFalse(result.stream().anyMatch(s -> s.contains("表格1")));
        assertFalse(result.stream().anyMatch(s -> s.contains("工作表")));
    }
}
