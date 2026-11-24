package dbviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.sql.Blob;
import javax.sql.rowset.serial.SerialBlob;

import org.junit.jupiter.api.Test;

class CellFormatterTest {

    @Test
    void keepsShortTextAndNullEmpty() {
        assertEquals("Ada", CellFormatter.format("Ada"));
        assertEquals(123, Integer.parseInt(CellFormatter.format(123)));
        assertEquals("", CellFormatter.format(null));
    }

    @Test
    void marksBinaryValues() throws Exception {
        assertEquals(CellFormatter.BINARY_TEXT, CellFormatter.format(new byte[] {1, 2, 3}));
        assertEquals(CellFormatter.BINARY_TEXT, CellFormatter.format(ByteBuffer.wrap(new byte[] {1})));
        Blob blob = new SerialBlob(new byte[] {1, 2});
        assertEquals(CellFormatter.BINARY_TEXT, CellFormatter.format(blob));
    }

    @Test
    void escapesMultilineValues() {
        assertEquals("first\\nsecond\\ttabbed\\rreturn", CellFormatter.format("first\nsecond\ttabbed\rreturn"));
        assertTrue(CellFormatter.format("a\u0001b").contains("\\u0001"));
    }

    @Test
    void truncatesLongValues() {
        String result = CellFormatter.format("x".repeat(500));
        assertEquals(CellFormatter.MAX_CELL_LENGTH, result.length());
        assertTrue(result.endsWith("…"));
    }
}
