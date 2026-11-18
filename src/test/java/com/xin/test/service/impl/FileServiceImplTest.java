package com.xin.test.service.impl;

import com.xin.test.dto.FileContentDTO;
import com.xin.test.dto.RawFileDTO;
import com.xin.test.util.MimeTypeResolver;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileServiceImplTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void assemblePdf() {
        FileContentDTO dto = FileServiceImpl.assemble("F1",
                new RawFileDTO(bytes("pdf"), ".PDF", "月度报告.pdf"));
        assertEquals("月度报告.pdf", dto.getFileName());
        assertEquals("application/pdf", dto.getMimeType());
        assertEquals("pdf", dto.getFileType());
        assertEquals(3L, dto.getSizeBytes());
    }

    @Test
    void assembleXlsxFromFileNameOnly() {
        FileContentDTO dto = FileServiceImpl.assemble("F2",
                new RawFileDTO(bytes("xlsx"), null, "清单.XLSX"));
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                dto.getMimeType());
        assertEquals("xlsx", dto.getFileType());
    }

    @Test
    void assembleUnknownTypeFallsBack() {
        FileContentDTO dto = FileServiceImpl.assemble("F3",
                new RawFileDTO(bytes("bin"), ".zzzzz", null));
        assertEquals(MimeTypeResolver.DEFAULT_MIME_TYPE, dto.getMimeType());
        assertEquals("F3.zzzzz", dto.getFileName());
    }

    @Test
    void assembleStripsPathFromFileName() {
        FileContentDTO dto = FileServiceImpl.assemble("F4",
                new RawFileDTO(bytes("pdf"), ".pdf", "../../etc/secret.pdf"));
        assertEquals("secret.pdf", dto.getFileName());
    }

    @Test
    void assembleReturnsOriginalBytes() {
        byte[] content = bytes("original");
        FileContentDTO dto = FileServiceImpl.assemble("F5",
                new RawFileDTO(content, ".txt", "note.txt"));
        assertArrayEquals(content, dto.getContent());
        assertEquals("text/plain;charset=UTF-8", dto.getMimeType());
    }

    @Test
    void emptyOrNullPayloadFailsFast() {
        assertThrows(IllegalStateException.class, () -> FileServiceImpl.assemble("F6", null));
        assertThrows(IllegalStateException.class, () -> FileServiceImpl.assemble("F6",
                new RawFileDTO(new byte[0], ".pdf", "a.pdf")));
        assertThrows(IllegalStateException.class, () -> FileServiceImpl.assemble("F6",
                new RawFileDTO(null, ".pdf", "a.pdf")));
    }
}
