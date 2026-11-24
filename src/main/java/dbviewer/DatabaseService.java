package dbviewer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/** Owns the JDBC connection and provides metadata/query operations for the UI. */
public final class DatabaseService {

    /** Results larger than this are truncated so the table stays usable. */
    public static final int MAX_ROWS = 10_000;

    private Connection connection;
    private ConnectionProfile profile;
    private SshTunnelSession tunnel;

    /** Opens a new connection, replacing the current one only after the new one succeeds. */
    public void connect(ConnectionProfile newProfile) throws SQLException {
        loadDriver(newProfile.driverClass());
        String jdbcUrl = newProfile.jdbcUrl();
        SshTunnelSession openedTunnel = null;
        Connection opened;
        try {
            if (newProfile.ssh().enabled()) {
                openedTunnel = SshTunnelSession.open(newProfile.ssh(), jdbcUrl, newProfile.dbType());
                jdbcUrl = openedTunnel.localJdbcUrl();
            }
            opened = DriverManager.getConnection(jdbcUrl, credentials(newProfile));
        } catch (SQLException e) {
            if (openedTunnel != null) {
                openedTunnel.close();
            }
            throw e;
        }
        disconnect();
        connection = opened;
        profile = newProfile;
        tunnel = openedTunnel;
    }

    /** Opens and immediately closes a connection, used by the "Test Connection" button. */
    public static void testConnection(ConnectionProfile candidate) throws SQLException {
        loadDriver(candidate.driverClass());
        String jdbcUrl = candidate.jdbcUrl();
        SshTunnelSession openedTunnel = null;
        try {
            if (candidate.ssh().enabled()) {
                openedTunnel = SshTunnelSession.open(candidate.ssh(), jdbcUrl, candidate.dbType());
                jdbcUrl = openedTunnel.localJdbcUrl();
            }
            try (Connection c = DriverManager.getConnection(jdbcUrl, credentials(candidate))) {
                if (!c.isValid(2)) {
                    throw new SQLException("The connection reported as invalid.");
                }
            }
        } finally {
            if (openedTunnel != null) {
                openedTunnel.close();
            }
        }
    }

    public void disconnect() {
        if (connection != null) {
            closeQuietly(connection);
        }
        connection = null;
        profile = null;
        if (tunnel != null) {
            tunnel.close();
            tunnel = null;
        }
    }

    public boolean isConnected() {
        return connection != null;
    }

    public ConnectionProfile profile() {
        return profile;
    }

    /** Lists tables and views in a stable, case-insensitive order. */
    public List<TableInfo> listTables() throws SQLException {
        requireConnection();
        List<TableInfo> tables = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData()
                .getTables(null, null, "%", new String[] {"TABLE", "VIEW"})) {
            while (rs.next()) {
                tables.add(new TableInfo(
                        rs.getString("TABLE_CAT"),
                        rs.getString("TABLE_SCHEM"),
                        rs.getString("TABLE_NAME"),
                        rs.getString("TABLE_TYPE")));
            }
        }
        tables.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return tables;
    }

    /** Lists the columns of a table or view in ordinal order, flagging primary key columns. */
    public List<ColumnInfo> listColumns(TableInfo table) throws SQLException {
        requireConnection();
        Set<String> primaryKeys = new HashSet<>();
        try (ResultSet rs = connection.getMetaData().getPrimaryKeys(null, null, table.name())) {
            while (rs.next()) {
                String column = rs.getString("COLUMN_NAME");
                if (column != null && !column.isBlank()) {
                    primaryKeys.add(column.toLowerCase(Locale.ROOT));
                }
            }
        }

        List<ColumnInfo> columns = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData().getColumns(null, null, "%", null)) {
            while (rs.next()) {
                String catalog = rs.getString("TABLE_CAT");
                String schema = rs.getString("TABLE_SCHEM");
                String tableName = rs.getString("TABLE_NAME");
                if (!sameIdentifier(table.name(), tableName)
                        || (!table.catalog().isBlank() && !sameIdentifier(table.catalog(), catalog))
                        || (!table.schema().isBlank() && !sameIdentifier(table.schema(), schema))) {
                    continue;
                }
                String name = rs.getString("COLUMN_NAME");
                String type = rs.getString("TYPE_NAME");
                int ordinal = rs.getInt("ORDINAL_POSITION");
                if (rs.wasNull()) {
                    ordinal = columns.size() + 1;
                }
                columns.add(new ColumnInfo(name, type,
                        name != null && primaryKeys.contains(name.toLowerCase(Locale.ROOT)), ordinal));
            }
        }
        columns.sort(Comparator.comparingInt(ColumnInfo::ordinal));
        return columns;
    }

    private static boolean sameIdentifier(String expected, String actual) {
        if (expected == null || expected.isBlank()) {
            return actual == null || actual.isBlank();
        }
        return expected.equalsIgnoreCase(actual);
    }

    /** Executes a SQL script. If it produces result sets, the last one is returned. */
    public QueryResult execute(String script) throws SQLException {
        requireConnection();
        long startedAt = System.nanoTime();

        List<String> statements = SqlSplitter.split(script);
        if (statements.isEmpty()) {
            throw new SQLException("No SQL statement to run.");
        }

        List<String> columns = List.of();
        List<String[]> rows = List.of();
        List<Object[]> rawRows = List.of();
        QueryResult.SourceTable source = null;
        List<Integer> updates = new ArrayList<>();
        boolean truncated = false;

        for (String sql : statements) {
            try (Statement stmt = connection.createStatement()) {
                boolean hasResultSet = stmt.execute(sql);
                while (true) {
                    if (hasResultSet) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            QueryResult part = readResultSet(rs);
                            // Last result set wins, as in most SQL clients.
                            columns = part.columns();
                            rows = part.rows();
                            rawRows = part.rawRows();
                            source = part.sourceTable();
                            truncated = part.truncated();
                        }
                    } else {
                        int count = stmt.getUpdateCount();
                        if (count == -1) {
                            break;
                        }
                        updates.add(count);
                    }
                    hasResultSet = stmt.getMoreResults();
                }
            }
        }

        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
        return new QueryResult(columns, rows, rawRows, updates, elapsedMillis,
                truncated, statements.size(), source);
    }

    /**
     * Deletes the given rows from the table the result set came from. Primary key columns are
     * used when available; otherwise all columns in the result must identify the row.
     *
     * @return the number of rows actually deleted
     */
    public int deleteRows(QueryResult.SourceTable source, List<Object[]> rows) throws SQLException {
        requireConnection();
        if (rows.isEmpty()) {
            return 0;
        }
        if (source == null || source.table() == null || source.table().isBlank()) {
            throw new SQLException("This result set cannot be mapped to a table.");
        }

        List<String> keyColumns = deleteColumns(source);
        String dbType = profile == null ? "" : profile.dbType();
        String table = new TableInfo(source.catalog(), source.schema(), source.table(), "TABLE")
                .qualifiedName(dbType);
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(table).append(" WHERE ");
        for (int i = 0; i < keyColumns.size(); i++) {
            if (i > 0) {
                sql.append(" AND ");
            }
            sql.append(TableInfo.quoteIdentifier(keyColumns.get(i), dbType)).append(" = ?");
        }

        int deleted = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (Object[] row : rows) {
                for (int i = 0; i < keyColumns.size(); i++) {
                    int column = columnIndex(source.columnNames(), keyColumns.get(i));
                    Object value = column >= 0 && column < row.length ? row[column] : null;
                    statement.setObject(i + 1, value);
                }
                statement.addBatch();
            }
            for (int count : statement.executeBatch()) {
                if (count == Statement.SUCCESS_NO_INFO) {
                    deleted++;
                } else if (count > 0) {
                    deleted += count;
                }
            }
        }
        return deleted;
    }

    private QueryResult readResultSet(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();

        List<String> columns = new ArrayList<>(columnCount);
        List<String> columnNames = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            String label = meta.getColumnLabel(i);
            columns.add(label == null || label.isBlank() ? "Column " + i : label);
            String name = meta.getColumnName(i);
            columnNames.add(name == null ? "" : name);
        }

        List<String[]> rows = new ArrayList<>();
        List<Object[]> rawRows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() >= MAX_ROWS) {
                truncated = true;
                break;
            }
            String[] row = new String[columnCount];
            Object[] rawRow = new Object[columnCount];
            for (int i = 1; i <= columnCount; i++) {
                Object value = rs.getObject(i);
                rawRow[i - 1] = value;
                row[i - 1] = CellFormatter.format(value);
            }
            rows.add(row);
            rawRows.add(rawRow);
        }

        QueryResult.SourceTable source = null;
        if (columnCount > 0) {
            String table = meta.getTableName(1);
            if (table != null && !table.isBlank()) {
                String catalog = tablePart(meta.getCatalogName(1), table);
                String schema = tablePart(meta.getSchemaName(1), table);
                source = new QueryResult.SourceTable(catalog, schema, table,
                        columnNames, primaryKeys(catalog, schema, table));
            }
        }

        return new QueryResult(columns, rows, rawRows, List.of(), 0, truncated, 1, source);
    }

    /** SQLite reports the table name as the catalog; ignore such redundant qualifiers. */
    private static String tablePart(String value, String table) {
        return value == null || value.isBlank() || value.equalsIgnoreCase(table) ? "" : value;
    }

    private List<String> primaryKeys(String catalog, String schema, String table) {
        List<String> keys = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData().getPrimaryKeys(catalog, schema, table)) {
            record Key(short sequence, String name) {
            }
            List<Key> found = new ArrayList<>();
            while (rs.next()) {
                found.add(new Key(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME")));
            }
            found.sort(Comparator.comparingInt(Key::sequence));
            for (Key key : found) {
                if (key.name() != null && !key.name().isBlank()) {
                    keys.add(key.name());
                }
            }
        } catch (SQLException ignored) {
            // Views and some drivers have no key metadata; fall back to all result columns.
        }
        return keys;
    }

    private static List<String> deleteColumns(QueryResult.SourceTable source) throws SQLException {
        if (!source.keyColumns().isEmpty()) {
            List<String> keys = new ArrayList<>();
            for (String key : source.keyColumns()) {
                if (columnIndex(source.columnNames(), key) >= 0 && !keys.contains(key)) {
                    keys.add(key);
                }
            }
            if (keys.isEmpty()) {
                throw new SQLException("The query must include the primary key column(s): "
                        + String.join(", ", source.keyColumns()));
            }
            return keys;
        }

        List<String> all = new ArrayList<>();
        for (String name : source.columnNames()) {
            if (!name.isBlank() && !all.contains(name)) {
                all.add(name);
            }
        }
        if (all.isEmpty()) {
            throw new SQLException("The result has no columns to identify rows for deletion.");
        }
        return all;
    }

    private static int columnIndex(List<String> names, String column) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(column)) {
                return i;
            }
        }
        return -1;
    }

    private static Properties credentials(ConnectionProfile candidate) {
        Properties props = new Properties();
        if (!candidate.user().isBlank()) {
            props.setProperty("user", candidate.user());
        }
        if (!candidate.password().isBlank()) {
            props.setProperty("password", candidate.password());
        }
        return props;
    }

    private static void loadDriver(String driverClass) throws SQLException {
        if (driverClass == null || driverClass.isBlank()) {
            return; // Let JDBC's ServiceLoader find any driver on the classpath.
        }
        try {
            Class.forName(driverClass, true, DatabaseService.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new SQLException("JDBC driver class not found: " + driverClass, e);
        }
    }

    private void requireConnection() throws SQLException {
        if (connection != null) {
            try {
                if (connection.isClosed()) {
                    connection = null;
                    profile = null;
                }
            } catch (SQLException ignored) {
                // Fall through to the "not connected" error below.
            }
        }
        if (connection == null) {
            throw new SQLException("Not connected to a database.");
        }
    }

    private static void closeQuietly(Connection c) {
        try {
            c.close();
        } catch (SQLException ignored) {
            // Nothing useful to do while closing.
        }
    }
}
