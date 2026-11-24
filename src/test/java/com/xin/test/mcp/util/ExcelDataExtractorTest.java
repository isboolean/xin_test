package com.xin.test.mcp.util;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcelDataExtractorTest {

    @Test
    void xlsxCellsAreExtractedWithSheetRowColumnLabels() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("踏勘信息");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("勘察人员");
            row.createCell(1).setCellValue("张三");
            row.createCell(2).setCellValue(123);
            workbook.write(out);
            bytes = out.toByteArray();
        }

        List<String> result = ExcelDataExtractor.extract(bytes, "xlsx");

        assertFalse(result.isEmpty());
        assertTrue(result.get(0).startsWith("工作表1[踏勘信息] 共"));
        assertTrue(result.stream().anyMatch(s -> s.contains("第1行 第1列: 勘察人员")));
        assertTrue(result.stream().anyMatch(s -> s.contains("第1行 第2列: 张三")));
        assertTrue(result.stream().anyMatch(s -> s.contains("第1行 第3列: 123")));
    }

    @Test
    void xlsIsSupportedByHssf() throws Exception {
        byte[] bytes;
        try (HSSFWorkbook workbook = new HSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("老表").createRow(0).createCell(0).setCellValue("旧格式数据");
            workbook.write(out);
            bytes = out.toByteArray();
        }

        List<String> result = ExcelDataExtractor.extract(bytes, "xls");

        assertTrue(result.stream().anyMatch(s -> s.contains("工作表1[老表]")));
        assertTrue(result.stream().anyMatch(s -> s.contains("旧格式数据")));
    }

    @Test
    void uppercaseExtensionIsAccepted() throws Exception {
        byte[] bytes = emptyXlsxBytes();
        assertFalse(ExcelDataExtractor.extract(bytes, "XLSX").isEmpty());
    }

    @Test
    void dateAndFormulaCellsAreRendered() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("计算");
            CreationHelper helper = workbook.getCreationHelper();
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));

            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(2);
            row.createCell(1).setCellValue(3);
            Cell formulaCell = row.createCell(2);
            formulaCell.setCellFormula("A1+B1");
            Cell dateCell = row.createCell(3);
            dateCell.setCellValue(new Date(120L * 24L * 60L * 60L * 1000L));
            dateCell.setCellStyle(dateStyle);

            workbook.write(out);
            bytes = out.toByteArray();
        }

        List<String> result = ExcelDataExtractor.extract(bytes, "xlsx");

        assertTrue(result.stream().anyMatch(s -> s.contains("第1行 第3列: 5")),
                "公式应求值为 5，实际结果：" + result);
        assertTrue(result.stream().anyMatch(s -> s.matches(".*第1行 第4列: \\d{4}-\\d{2}-\\d{2}.*")),
                "日期应按格式渲染，实际结果：" + result);
    }

    @Test
    void mergedCellsAreNotDuplicated() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("合并");
            sheet.createRow(0).createCell(0).setCellValue("跨行值");
            sheet.createRow(1);
            sheet.addMergedRegion(new CellRangeAddress(0, 1, 0, 0));
            workbook.write(out);
            bytes = out.toByteArray();
        }

        List<String> result = ExcelDataExtractor.extract(bytes, "xlsx");

        assertEquals(1, result.stream().filter(s -> s.contains("跨行值")).count());
    }

    @Test
    void multipleSheetsAreExtractedInOrder() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("第一张").createRow(0).createCell(0).setCellValue("A表");
            workbook.createSheet("第二张").createRow(0).createCell(0).setCellValue("B表");
            workbook.write(out);
            bytes = out.toByteArray();
        }

        List<String> result = ExcelDataExtractor.extract(bytes, "xlsx");

        assertTrue(result.stream().anyMatch(s -> s.contains("工作表1[第一张]")));
        assertTrue(result.stream().anyMatch(s -> s.contains("工作表2[第二张]")));
        assertTrue(result.stream().anyMatch(s -> s.contains("A表")));
        assertTrue(result.stream().anyMatch(s -> s.contains("B表")));
    }

    @Test
    void oversizedSheetIsTruncated() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("大表");
            for (int i = 0; i <= ExcelDataExtractor.MAX_ROWS_PER_SHEET; i++) {
                sheet.createRow(i).createCell(0).setCellValue("行" + i);
            }
            workbook.write(out);
            bytes = out.toByteArray();
        }

        List<String> result = ExcelDataExtractor.extract(bytes, "xlsx");

        assertTrue(result.stream().anyMatch(s -> s.contains("内容已截断")));
    }

    @Test
    void invalidInputsReturnEmptyList() {
        assertNotNull(ExcelDataExtractor.extract(null, "xlsx"));
        assertTrue(ExcelDataExtractor.extract(null, "xlsx").isEmpty());
        assertTrue(ExcelDataExtractor.extract(new byte[0], "xlsx").isEmpty());
        assertTrue(ExcelDataExtractor.extract(new byte[]{1, 2, 3}, "xlsx").isEmpty());
        assertTrue(ExcelDataExtractor.extract(new byte[]{1, 2, 3}, "pdf").isEmpty());
        assertTrue(ExcelDataExtractor.extract(new byte[]{1, 2, 3}, null).isEmpty());
    }

    private static byte[] emptyXlsxBytes() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("空表");
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
