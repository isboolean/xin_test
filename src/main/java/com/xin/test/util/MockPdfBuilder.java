package com.xin.test.util;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 生成结构合法的 mock PDF（单页，含文件元信息）
 * <p>
 * 目的：让 MCP 下载工具返回真实的 PDF 字节（而非纯文本），便于 Agent 端适配器按
 * mimeType=application/pdf 解析；内容为 mock，不涉及真实业务文件。
 */
public final class MockPdfBuilder {

    private MockPdfBuilder() {
    }

    public static byte[] build(String fileNo, String fileName) {
        String text = "BT /F1 16 Tf 60 780 Td (Mock Unstructured File) Tj ET\n"
                + "BT /F1 11 Tf 60 745 Td (File No: " + escape(fileNo) + ") Tj ET\n"
                + "BT /F1 11 Tf 60 725 Td (File Name: " + escape(fileName) + ") Tj ET\n"
                + "BT /F1 11 Tf 60 705 Td (Generated At: " + escape(LocalDateTime.now().toString()) + ") Tj ET\n"
                + "BT /F1 11 Tf 60 685 Td (Source: xin_test mock provider) Tj ET\n";

        List<Integer> offsets = new ArrayList<>();
        StringBuilder pdf = new StringBuilder(2048);
        pdf.append("%PDF-1.4\n");

        offsets.add(pdf.length());
        pdf.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

        offsets.add(pdf.length());
        pdf.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");

        offsets.add(pdf.length());
        pdf.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] ")
                .append("/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>\nendobj\n");

        offsets.add(pdf.length());
        pdf.append("4 0 obj\n<< /Length ").append(text.length()).append(" >>\nstream\n")
                .append(text)
                .append("endstream\nendobj\n");

        offsets.add(pdf.length());
        pdf.append("5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n");

        int xrefOffset = pdf.length();
        pdf.append("xref\n0 6\n0000000000 65535 f \n");
        for (Integer offset : offsets) {
            pdf.append(String.format("%010d 00000 n \n", offset));
        }
        pdf.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n")
                .append(xrefOffset)
                .append("\n%%EOF\n");

        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * PDF 字面量转义：括号、反斜杠转义，非 ASCII 字符降级为 '?'
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (char c : value.toCharArray()) {
            if (c == '(' || c == ')' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20 || c > 0x7E) {
                sb.append('?');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
