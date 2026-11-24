package dbviewer;

import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Non-UI state for editing one result set: the rows as loaded, the cells the user has changed
 * and any new rows. It turns those edits into {@link DatabaseService.RowUpdate} and
 * {@link DatabaseService.RowInsert} objects and builds the summary shown before saving.
 */
final class TableEditModel {

    /** One row from the result set, with its edited cell texts alongside the original values. */
    private static final class Row {
        private final Object[] original;
        private final boolean isNew;
        private final boolean[] edited;
        private final String[] texts;

        private Row(int columnCount, Object[] original, boolean isNew) {
            this.original = new Object[columnCount];
            if (original != null) {
                System.arraycopy(original, 0, this.original, 0, Math.min(original.length, columnCount));
            }
            this.isNew = isNew;
            this.edited = new boolean[columnCount];
            this.texts = new String[columnCount];
        }
    }

    /** Everything needed to confirm and apply the pending changes. */
    record Changes(List<DatabaseService.RowUpdate> updates,
            List<DatabaseService.RowInsert> inserts, String summary) {

        boolean isEmpty() {
            return updates.isEmpty() && inserts.isEmpty();
        }

        int count() {
            return updates.size() + inserts.size();
        }
    }

    private final QueryResult.SourceTable source;
    private final List<String> columns;
    private final List<String> tableColumns;
    private final List<Integer> columnTypes;
    private final List<Row> rows = new ArrayList<>();

    private TableEditModel(QueryResult.SourceTable source, List<String> columns,
            List<String> tableColumns, List<Integer> columnTypes) {
        this.source = source;
        this.columns = List.copyOf(columns);
        this.tableColumns = List.copyOf(tableColumns);
        this.columnTypes = List.copyOf(columnTypes);
    }

    /**
     * Creates an edit model when {@code source} is a single table whose primary key columns are
     * part of the result. Returns null for read-only results.
     */
    static TableEditModel create(QueryResult.SourceTable source, List<String> columns,
            List<Object[]> rawRows) {
        if (source == null || source.keyColumns().isEmpty() || columns.isEmpty()) {
            return null;
        }

        int columnCount = columns.size();
        List<String> tableColumns = new ArrayList<>(source.columnNames());
        if (tableColumns.size() > columnCount) {
            tableColumns = new ArrayList<>(tableColumns.subList(0, columnCount));
        }
        while (tableColumns.size() < columnCount) {
            tableColumns.add("");
        }
        List<Integer> types = new ArrayList<>(source.columnTypes());
        if (types.size() > columnCount) {
            types = new ArrayList<>(types.subList(0, columnCount));
        }
        while (types.size() < columnCount) {
            types.add(Types.OTHER);
        }

        boolean editableColumn = false;
        for (int i = 0; i < columnCount; i++) {
            if (!tableColumns.get(i).isBlank()) {
                editableColumn = true;
                break;
            }
        }
        if (!editableColumn) {
            return null;
        }
        for (String key : source.keyColumns()) {
            if (key == null || key.isBlank() || indexOf(tableColumns, key) < 0) {
                return null;
            }
        }

        TableEditModel model = new TableEditModel(source, columns, tableColumns, types);
        for (Object[] row : rawRows) {
            model.rows.add(new Row(columnCount, row, false));
        }
        return model;
    }

    QueryResult.SourceTable source() {
        return source;
    }

    boolean isDirty() {
        for (Row row : rows) {
            if (row.isNew) {
                if (hasNewContent(row)) {
                    return true;
                }
            } else if (hasEdits(row)) {
                return true;
            }
        }
        return false;
    }

    int rowCount() {
        return rows.size();
    }

    int newRowCount() {
        int count = 0;
        for (Row row : rows) {
            if (row.isNew && hasNewContent(row)) {
                count++;
            }
        }
        return count;
    }

    boolean isNewRow(int row) {
        return validRow(row) && rows.get(row).isNew;
    }

    boolean isCellEdited(int row, int column) {
        return validRow(row) && validColumn(column) && rows.get(row).edited[column];
    }

    boolean isColumnEditable(int column) {
        return validColumn(column) && !tableColumns.get(column).isBlank();
    }

    /** The text shown in a cell, including pending edits and blank new rows. */
    String displayText(int row, int column) {
        if (!validRow(row) || !validColumn(column)) {
            return "";
        }
        Row entry = rows.get(row);
        if (entry.edited[column] && entry.texts[column] != null) {
            return entry.texts[column];
        }
        if (entry.isNew) {
            return "";
        }
        return CellFormatter.format(entry.original[column]);
    }

    /**
     * Records a cell's new text. Passing the displayed original text again clears the edit.
     * A row index one past the last row creates a new row first.
     */
    void setCell(int row, int column, String text) {
        if (!validColumn(column)) {
            return;
        }
        String value = text == null ? "" : text;
        if (row == rows.size()) {
            if (value.isEmpty()) {
                return;
            }
            rows.add(new Row(columns.size(), null, true));
        }
        if (!validRow(row)) {
            return;
        }
        if (!isColumnEditable(column)) {
            throw new IllegalArgumentException(
                    "Column " + columns.get(column) + " is not a table column and cannot be edited.");
        }

        Row entry = rows.get(row);
        if (!entry.isNew && value.equals(CellFormatter.format(entry.original[column]))) {
            entry.edited[column] = false;
            entry.texts[column] = null;
            return;
        }
        if (entry.isNew && value.isEmpty()) {
            entry.edited[column] = false;
            entry.texts[column] = null;
            if (!hasNewContent(entry)) {
                rows.remove(row);
            }
            return;
        }
        entry.edited[column] = true;
        entry.texts[column] = value;
    }

    /** Builds the changes to apply and the human-readable summary shown in the confirmation. */
    Changes changes(String dbType) {
        List<DatabaseService.RowUpdate> updates = new ArrayList<>();
        List<DatabaseService.RowInsert> inserts = new ArrayList<>();
        List<String> updateLines = new ArrayList<>();
        List<String> insertLines = new ArrayList<>();

        int newRowNumber = 0;
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Row row = rows.get(rowIndex);
            if (row.isNew) {
                if (!hasNewContent(row)) {
                    continue;
                }
                newRowNumber++;
                LinkedHashMap<String, Object> values = new LinkedHashMap<>();
                for (int column = 0; column < columns.size(); column++) {
                    if (row.edited[column] && row.texts[column] != null
                            && !row.texts[column].isEmpty()) {
                        values.put(columnName(column),
                                JdbcValues.parse(row.texts[column], columnType(column)));
                    }
                }
                if (!values.isEmpty()) {
                    inserts.add(new DatabaseService.RowInsert(values));
                    insertLines.add("New row " + newRowNumber + ": " + describeValues(values));
                }
                continue;
            }

            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            LinkedHashMap<String, Object> oldValues = new LinkedHashMap<>();
            for (int column = 0; column < columns.size(); column++) {
                if (row.edited[column]) {
                    String name = columnName(column);
                    values.put(name, JdbcValues.parse(row.texts[column], columnType(column)));
                    oldValues.put(name, row.original[column]);
                }
            }
            if (values.isEmpty()) {
                continue;
            }

            List<String> keyColumns = source.keyColumns();
            List<Object> keyValues = new ArrayList<>(keyColumns.size());
            for (String key : keyColumns) {
                int column = indexOf(tableColumns, key);
                keyValues.add(column >= 0 && column < row.original.length
                        ? row.original[column] : null);
            }
            updates.add(new DatabaseService.RowUpdate(keyColumns, keyValues, values));

            StringBuilder line = new StringBuilder("Row ").append(rowIndex + 1)
                    .append(" (").append(describeKeys(keyColumns, keyValues)).append(")");
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                line.append("\n    ").append(entry.getKey()).append(": ")
                        .append(valueText(oldValues.get(entry.getKey()))).append(" \u2192 ")
                        .append(valueText(entry.getValue()));
            }
            updateLines.add(line.toString());
        }

        return new Changes(updates, inserts,
                summary(dbType, updateLines, insertLines));
    }

    /** The rows after a successful save, including the inserted rows. */
    List<Object[]> committedRows() {
        List<Object[]> result = new ArrayList<>(rows.size());
        for (Row row : rows) {
            if (row.isNew && !hasNewContent(row)) {
                continue;
            }
            Object[] values = new Object[columns.size()];
            for (int column = 0; column < columns.size(); column++) {
                if (row.edited[column]) {
                    values[column] = JdbcValues.parse(row.texts[column], columnType(column));
                } else {
                    values[column] = row.isNew ? null : row.original[column];
                }
            }
            result.add(values);
        }
        return result;
    }

    private String summary(String dbType, List<String> updateLines, List<String> insertLines) {
        String table = new TableInfo(source.catalog(), source.schema(), source.table(), "TABLE")
                .qualifiedName(dbType);
        int total = updateLines.size() + insertLines.size();
        StringBuilder text = new StringBuilder("Table: ").append(table).append("\n\n")
                .append(total).append(total == 1 ? " row will change." : " rows will change.");
        if (!updateLines.isEmpty()) {
            text.append("\n\n").append(updateLines.size())
                    .append(updateLines.size() == 1 ? " updated row:" : " updated rows:");
            for (String line : updateLines) {
                text.append("\n  ").append(line.replace("\n", "\n  "));
            }
        }
        if (!insertLines.isEmpty()) {
            text.append("\n\n").append(insertLines.size())
                    .append(insertLines.size() == 1 ? " new row:" : " new rows:");
            for (String line : insertLines) {
                text.append("\n  ").append(line);
            }
        }
        text.append("\n\nSave these changes to the database?");
        return text.toString();
    }

    private static String describeKeys(List<String> keyColumns, List<Object> keyValues) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < keyColumns.size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(keyColumns.get(i)).append('=')
                    .append(valueText(i < keyValues.size() ? keyValues.get(i) : null));
        }
        return text.toString();
    }

    private static String describeValues(Map<String, Object> values) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(entry.getKey()).append('=').append(valueText(entry.getValue()));
        }
        return text.toString();
    }

    private static String valueText(Object value) {
        if (value == null) {
            return JdbcValues.NULL_TEXT;
        }
        if (value instanceof CharSequence || value instanceof Character) {
            String text = CellFormatter.format(value).replace("'", "''");
            return "'" + text + "'";
        }
        return CellFormatter.format(value);
    }

    private String columnName(int column) {
        String name = tableColumns.get(column);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "Column " + columns.get(column) + " is not a table column and cannot be edited.");
        }
        return name;
    }

    private int columnType(int column) {
        return column >= 0 && column < columnTypes.size() ? columnTypes.get(column) : Types.OTHER;
    }

    private static boolean hasEdits(Row row) {
        for (boolean edit : row.edited) {
            if (edit) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNewContent(Row row) {
        for (int column = 0; column < row.edited.length; column++) {
            if (row.edited[column] && row.texts[column] != null && !row.texts[column].isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean validRow(int row) {
        return row >= 0 && row < rows.size();
    }

    private boolean validColumn(int column) {
        return column >= 0 && column < columns.size();
    }

    private static int indexOf(List<String> names, String name) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i) != null && names.get(i).equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }
}
