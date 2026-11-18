package com.xin.test.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MimeTypeResolverTest {

    @Test
    void resolveByFileType() {
        assertEquals("application/pdf", MimeTypeResolver.resolve(".pdf", null));
        assertEquals("application/pdf", MimeTypeResolver.resolve("PDF", null));
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                MimeTypeResolver.resolve(".DOCX", null));
        assertEquals("text/plain;charset=UTF-8", MimeTypeResolver.resolve("txt", null));
    }

    @Test
    void resolveFallsBackToFileNameExtension() {
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                MimeTypeResolver.resolve(null, "月度报告.XLSX"));
        assertEquals("application/pdf", MimeTypeResolver.resolve("  ", "scan.pdf"));
    }

    @Test
    void resolveUnknownOrMissingType() {
        assertEquals(MimeTypeResolver.DEFAULT_MIME_TYPE, MimeTypeResolver.resolve(".zzzzz", null));
        assertEquals(MimeTypeResolver.DEFAULT_MIME_TYPE, MimeTypeResolver.resolve(null, null));
        assertEquals(MimeTypeResolver.DEFAULT_MIME_TYPE, MimeTypeResolver.resolve(null, "无扩展名"));
    }

    @Test
    void normalizeExtension() {
        assertEquals("pdf", MimeTypeResolver.normalizeExtension(".PDF", null));
        assertEquals("docx", MimeTypeResolver.normalizeExtension("docx", null));
        assertEquals("pdf", MimeTypeResolver.normalizeExtension(null, "报告.pdf"));
        assertEquals(MimeTypeResolver.DEFAULT_EXTENSION, MimeTypeResolver.normalizeExtension(null, "无扩展名"));
        assertEquals(MimeTypeResolver.DEFAULT_EXTENSION, MimeTypeResolver.normalizeExtension("../../etc/passwd", null));
    }

    @Test
    void safeFileNameStripsPathAndControlChars() {
        assertEquals("secret.pdf", MimeTypeResolver.safeFileName("../secret.pdf", ".pdf", "F1"));
        assertEquals("passwd", MimeTypeResolver.safeFileName("../../etc/passwd", ".pdf", "F1"));
        assertEquals("report.pdf", MimeTypeResolver.safeFileName("a/b/report\u0001.pdf", ".pdf", "F1"));
    }

    @Test
    void safeFileNameFallsBackWhenBlankOrDangerous() {
        assertEquals("F1.pdf", MimeTypeResolver.safeFileName(null, ".pdf", "F1"));
        assertEquals("F2.xlsx", MimeTypeResolver.safeFileName("  ", "xlsx", "F2"));
        assertEquals("F3.pdf", MimeTypeResolver.safeFileName("..", ".pdf", "F3"));
        assertEquals("F4.bin", MimeTypeResolver.safeFileName("", null, "F4"));
    }
}
