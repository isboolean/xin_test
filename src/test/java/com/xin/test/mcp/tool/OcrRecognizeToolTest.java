package com.xin.test.mcp.tool;

import com.xin.test.mcp.service.OcrRecognizeService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OcrRecognizeToolTest {

    private final OcrRecognizeTool tool = new OcrRecognizeTool(new OcrRecognizeService());

    @Test
    void toolNameAndSchemas() {
        assertEquals("ocr_recognize", tool.name());
        assertEquals("fileId", ((List<?>) tool.inputSchema().get("required")).get(0));

        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) tool.outputSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> texts = (Map<String, Object>) props.get("texts");
        assertEquals("array", texts.get("type"));
        assertEquals("string", ((Map<?, ?>) texts.get("items")).get("type"));
    }

    @Test
    void nullArgumentsReturnErrorWithEmptyTexts() {
        McpToolResult result = tool.call(null);
        assertTrue(result.isError());
        assertTextsAreEmptyStringList(result);
    }

    @Test
    void blankFileIdReturnsErrorWithEmptyTexts() {
        Map<String, Object> args = new HashMap<>();
        args.put("fileId", "   ");
        McpToolResult result = tool.call(args);
        assertTrue(result.isError());
        assertTextsAreEmptyStringList(result);
    }

    @Test
    void tooLongFileIdReturnsErrorWithEmptyTexts() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < OcrRecognizeService.MAX_FILE_ID_LENGTH + 1; i++) {
            sb.append('a');
        }
        Map<String, Object> args = new HashMap<>();
        args.put("fileId", sb.toString());
        McpToolResult result = tool.call(args);
        assertTrue(result.isError());
        assertTextsAreEmptyStringList(result);
    }

    @Test
    void validFileIdReturnsTextsAsListOfString() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("fileId", "F20261116001");
        McpToolResult result = tool.call(args);

        assertFalse(result.isError());
        Map<String, Object> structured = result.getStructuredContent();
        Object texts = structured.get("texts");
        assertTrue(texts instanceof List);
        assertFalse(((List<?>) texts).isEmpty());
        assertEquals(((List<?>) texts).size(), structured.get("count"));
        for (Object item : (List<?>) texts) {
            assertTrue(item instanceof String);
        }
        assertFalse(result.getContent().isEmpty());
        assertNotNull(result.toMap());
    }

    @Test
    void emptyRecognitionStillReturnsEmptyTextsArray() {
        OcrRecognizeService stub = mock(OcrRecognizeService.class);
        when(stub.recognize(anyString())).thenReturn(new ArrayList<String>());
        OcrRecognizeTool emptyTool = new OcrRecognizeTool(stub);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("fileId", "F-empty");
        McpToolResult result = emptyTool.call(args);
        assertTrue(result.isError());
        assertEquals("TEXT_EMPTY", result.getStructuredContent().get("errorCode"));
        assertTextsAreEmptyStringList(result);
    }

    private static void assertTextsAreEmptyStringList(McpToolResult result) {
        Object texts = result.getStructuredContent().get("texts");
        assertNotNull(texts);
        assertTrue(texts instanceof List);
        assertTrue(((List<?>) texts).isEmpty());
    }
}
