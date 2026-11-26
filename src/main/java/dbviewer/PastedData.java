package dbviewer;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses clipboard text into rows of cells. Tab-separated text (as copied from a spreadsheet)
 * and CSV are both supported. A tab outside quotes selects tab-separated parsing, which is what
 * spreadsheet applications put on the clipboard.
 */
final class PastedData {

    private PastedData() {
    }

    /** Parses text into rows; quoted fields may contain the delimiter and line breaks. */
    static List<String[]> parse(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        char delimiter = detectDelimiter(text);
        List<String[]> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldStarted = false;
        int index = 0;

        while (index < text.length()) {
            char c = text.charAt(index);
            if (quoted) {
                if (c == '"') {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
                        field.append('"');
                        index += 2;
                        continue;
                    }
                    quoted = false;
                    index++;
                    continue;
                }
                field.append(c);
                index++;
                continue;
            }

            if (c == '"' && field.length() == 0 && !fieldStarted) {
                quoted = true;
                fieldStarted = true;
                index++;
                continue;
            }
            if (c == delimiter) {
                row.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
                index++;
                continue;
            }
            if (c == '\n' || c == '\r') {
                if (fieldStarted || field.length() > 0 || !row.isEmpty()) {
                    row.add(field.toString());
                    rows.add(row.toArray(new String[0]));
                }
                field.setLength(0);
                row = new ArrayList<>();
                fieldStarted = false;
                if (c == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    index++;
                }
                index++;
                continue;
            }

            field.append(c);
            fieldStarted = true;
            index++;
        }

        if (quoted) {
            throw new IllegalArgumentException("The pasted text has an unterminated quoted value.");
        }
        if (fieldStarted || field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row.toArray(new String[0]));
        }
        return rows;
    }

    /** Prefers a tab when one appears outside quotes; otherwise treats the text as CSV. */
    private static char detectDelimiter(String text) {
        boolean quoted = false;
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (c == '"') {
                if (quoted && index + 1 < text.length() && text.charAt(index + 1) == '"') {
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (!quoted) {
                if (c == '\t') {
                    return '\t';
                }
                if (c == ',') {
                    return ',';
                }
            }
        }
        return '\t';
    }
}
