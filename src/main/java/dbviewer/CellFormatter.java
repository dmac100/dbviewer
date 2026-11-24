package dbviewer;

import java.nio.ByteBuffer;
import java.sql.Blob;

/** Turns arbitrary JDBC values into compact, single-line text suitable for a table cell. */
public final class CellFormatter {

    /** Cells longer than this are shortened so one value cannot fill the whole table. */
    public static final int MAX_CELL_LENGTH = 200;

    /** Displayed for values that are not meaningful to show as text. */
    public static final String BINARY_TEXT = "[BINARY]";

    private CellFormatter() {
    }

    /**
     * Returns display text for a value: binary data becomes {@link #BINARY_TEXT},
     * line breaks and other control characters are escaped, and long values are cut short.
     */
    public static String format(Object value) {
        if (value == null) {
            return "";
        }
        if (isBinary(value)) {
            return BINARY_TEXT;
        }
        String text = escapeControlCharacters(value.toString());
        if (text.length() <= MAX_CELL_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_CELL_LENGTH - 1) + "…";
    }

    private static boolean isBinary(Object value) {
        return value instanceof Blob
                || value instanceof ByteBuffer
                || (value.getClass().isArray() && !(value instanceof char[]));
    }

    private static String escapeControlCharacters(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        result.append(String.format("\\u%04x", (int) c));
                    } else {
                        result.append(c);
                    }
                }
            }
        }
        return result.toString();
    }
}
