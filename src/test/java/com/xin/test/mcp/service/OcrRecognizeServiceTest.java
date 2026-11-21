package com.xin.test.mcp.service;

import com.xin.test.mcp.dto.FileContentDTO;
import org.junit.jupiter.api.Test;

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
}
