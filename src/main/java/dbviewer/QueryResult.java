package dbviewer;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/** The outcome of running one or more SQL statements. */
public record QueryResult(
        List<String> columns,
        List<String[]> rows,
        List<Object[]> rawRows,
        List<Integer> updateCounts,
        long elapsedMillis,
        boolean truncated,
        int statementCount,
        SourceTable sourceTable) {

    /** Where the displayed result set came from, used to update/delete rows in the database. */
    public record SourceTable(
            String catalog, String schema, String table,
            List<String> columnNames, List<Integer> columnTypes, List<String> keyColumns) {

        public SourceTable {
            columnNames = List.copyOf(columnNames);
            columnTypes = List.copyOf(columnTypes);
            keyColumns = List.copyOf(keyColumns);
            if (columnTypes.size() < columnNames.size()) {
                List<Integer> padded = new ArrayList<>(columnTypes);
                while (padded.size() < columnNames.size()) {
                    padded.add(Types.OTHER);
                }
                columnTypes = List.copyOf(padded);
            }
        }

        /** Constructor for callers that do not know the JDBC column types. */
        public SourceTable(String catalog, String schema, String table,
                List<String> columnNames, List<String> keyColumns) {
            this(catalog, schema, table, columnNames, List.of(), keyColumns);
        }
    }

    public QueryResult {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
        rawRows = List.copyOf(rawRows);
        updateCounts = List.copyOf(updateCounts);
    }

    /** Convenience constructor for results that have no table to delete from. */
    public QueryResult(List<String> columns, List<String[]> rows, List<Integer> updateCounts,
            long elapsedMillis, boolean truncated, int statementCount) {
        this(columns, rows, List.of(), updateCounts, elapsedMillis, truncated, statementCount, null);
    }

    public boolean hasRows() {
        return !columns.isEmpty();
    }

    public int totalUpdates() {
        return updateCounts.stream().mapToInt(Integer::intValue).sum();
    }
}
