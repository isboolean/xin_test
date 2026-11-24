package com.xin.test.mcp.util;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Excel 表格数据直接抽取（纯 Java / Apache POI，不转 PDF、不经过 OCR）。
 *
 * <ul>
 *   <li>{@code .xlsx}：XSSF 读取工作簿；</li>
 *   <li>{@code .xls}：HSSF 读取工作簿。</li>
 * </ul>
 * 输出统一为 {@code List<String>}：每个工作表先输出表头行
 * {@code 工作表N[名称] 共R行}，单元格按
 * {@code 工作表N[名称] 第R行 第C列: 值} 逐行输出，行列号从 1 开始，便于下游字段映射。
 *
 * <p>数值 / 日期走 {@link DataFormatter}，公式先经 {@link FormulaEvaluator} 求值；
 * 合并单元格只保留左上角值；空白单元格与全空行跳过。每张表最多输出
 * {@value #MAX_ROWS_PER_SHEET} 行、每行最多 {@value #MAX_COLS_PER_ROW} 列，超限时追加截断提示行。
 * 解析异常收敛为空列表或已抽取内容，不外抛。
 */
public final class ExcelDataExtractor {

    private static final Logger log = LoggerFactory.getLogger(ExcelDataExtractor.class);

    /** 每张工作表最多输出的行数，避免大表撑爆模型上下文 */
    static final int MAX_ROWS_PER_SHEET = 2000;
    /** 每行最多输出的列数 */
    static final int MAX_COLS_PER_ROW = 50;

    private ExcelDataExtractor() {
    }

    /**
     * 抽取 Excel 内容。
     *
     * @param content   文件字节，允许为 null / 空
     * @param extension 扩展名（不带点，大小写不敏感）
     * @return 文本行列表，恒非 null；非 Excel 类型、空内容或解析失败时为空列表
     */
    public static List<String> extract(byte[] content, String extension) {
        List<String> lines = new ArrayList<>();
        if (content == null || content.length == 0) {
            return lines;
        }
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        boolean xlsx = "xlsx".equals(ext);
        boolean xls = "xls".equals(ext);
        if (!xlsx && !xls) {
            return lines;
        }

        try (InputStream in = new ByteArrayInputStream(content)) {
            Workbook workbook = xlsx ? new XSSFWorkbook(in) : new HSSFWorkbook(in);
            try {
                FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
                appendWorkbook(workbook, evaluator, lines);
            } finally {
                workbook.close();
            }
        } catch (Exception e) {
            log.warn("Excel 解析失败，已抽取 {} 行", lines.size(), e);
        }
        return lines;
    }

    private static void appendWorkbook(Workbook workbook, FormulaEvaluator evaluator, List<String> lines) {
        DataFormatter formatter = new DataFormatter(Locale.CHINA);
        for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
            Sheet sheet = workbook.getSheetAt(s);
            String label = "工作表" + (s + 1) + "[" + sheet.getSheetName() + "]";
            Set<String> mergedSkip = collectMergedSkip(sheet);

            List<String> sheetLines = new ArrayList<>();
            int lastRow = sheet.getLastRowNum();
            int rowLimit = Math.min(lastRow + 1, MAX_ROWS_PER_SHEET);
            for (int r = 0; r < rowLimit; r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                short lastCellNum = row.getLastCellNum();
                int colLimit = Math.min(lastCellNum < 0 ? 0 : lastCellNum, MAX_COLS_PER_ROW);
                for (int c = 0; c < colLimit; c++) {
                    if (mergedSkip.contains(r + ":" + c)) {
                        continue;
                    }
                    Cell cell = row.getCell(c);
                    if (cell == null) {
                        continue;
                    }
                    String value = formatCell(cell, formatter, evaluator);
                    if (value.isEmpty()) {
                        continue;
                    }
                    sheetLines.add(label + " 第" + (r + 1) + "行 第" + (c + 1) + "列: " + value);
                }
            }

            lines.add(label + " 共" + sheetLines.size() + "行");
            lines.addAll(sheetLines);
            if (lastRow + 1 > MAX_ROWS_PER_SHEET) {
                lines.add(label + " 内容已截断（最多 " + MAX_ROWS_PER_SHEET + " 行 / "
                        + MAX_COLS_PER_ROW + " 列）");
            }
        }
    }

    /** 合并区域除左上角外的单元格不再重复输出，避免同一值被数出多行。 */
    private static Set<String> collectMergedSkip(Sheet sheet) {
        Set<String> skip = new HashSet<>();
        for (CellRangeAddress range : sheet.getMergedRegions()) {
            for (int r = range.getFirstRow() + 1; r <= range.getLastRow(); r++) {
                if (r >= MAX_ROWS_PER_SHEET) {
                    break;
                }
                for (int c = range.getFirstColumn(); c <= range.getLastColumn(); c++) {
                    if (c >= MAX_COLS_PER_ROW) {
                        break;
                    }
                    skip.add(r + ":" + c);
                }
            }
        }
        return skip;
    }

    /** 单元格取值：公式先求值，日期 / 数值按格式渲染，空值统一为 ""。 */
    private static String formatCell(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        CellType type = cell.getCellType();
        if (type == CellType.BLANK) {
            return "";
        }
        if (type == CellType.FORMULA) {
            String evaluated = "";
            try {
                evaluated = formatter.formatCellValue(cell, evaluator);
            } catch (Exception e) {
                log.debug("公式求值失败，降级输出公式文本，sheet={}, row={}",
                        cell.getSheet().getSheetName(), cell.getRowIndex(), e);
            }
            if (evaluated != null && !evaluated.trim().isEmpty()) {
                return evaluated.trim();
            }
            String formula = cell.getCellFormula();
            return formula == null || formula.trim().isEmpty() ? "" : "=" + formula.trim();
        }
        String value = formatter.formatCellValue(cell);
        return value == null ? "" : value.trim();
    }
}
