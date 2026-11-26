package dbviewer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Owns the JDBC connection and provides metadata/query operations for the UI. */
public final class DatabaseService {

    /** Results larger than this are truncated so the table stays usable. */
    public static final int MAX_ROWS = 10_000;

    private Connection connection;
    private ConnectionProfile profile;
    private SshTunnelSession tunnel;
    private String defaultDatabaseSql;

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
        defaultDatabaseSql = null;
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
        defaultDatabaseSql = null;
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

    /**
     * Selects a catalog or schema as the default for subsequent queries. A statement is issued now
     * and repeated before each query, so manually changing the session does not change the default.
     */
    public void useDatabase(String scope, boolean schema) throws SQLException {
        requireConnection();
        String sql = defaultDatabaseStatement(scope, schema, profile.dbType());
        try (Statement stmt = connection.createStatement()) {
            if (sql != null) {
                stmt.execute(sql);
            }
        }
        defaultDatabaseSql = sql;
    }

    /** Returns the session statement that selects the default catalog or schema. */
    static String defaultDatabaseStatement(String scope, boolean schema, String dbType) {
        if (scope == null || scope.isBlank()) {
            return null;
        }
        if ("sqlite".equalsIgnoreCase(dbType)) {
            // A SQLite connection has one database; attached databases have no USE statement.
            return null;
        }
        String quoted = TableInfo.quoteIdentifier(scope, dbType);
        if ("mysql".equalsIgnoreCase(dbType)) {
            return "USE " + quoted;
        }
        if ("postgresql".equalsIgnoreCase(dbType) || schema) {
            return "SET search_path TO " + quoted;
        }
        return "USE " + quoted;
    }

    private void applyDefaultDatabase() throws SQLException {
        if (defaultDatabaseSql == null) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(defaultDatabaseSql);
        }
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

    /**
     * Returns the foreign keys declared on a table, grouped by constraint. Each reference lists
     * source columns and the columns they point at, in key sequence order.
     */
    public List<ForeignKeyReference> importedKeys(TableInfo table) throws SQLException {
        requireConnection();
        Map<String, List<KeyPart>> grouped = new LinkedHashMap<>();
        try (ResultSet rs = connection.getMetaData().getImportedKeys(
                nullable(table.catalog()), nullable(table.schema()), table.name())) {
            while (rs.next()) {
                String sourceTable = rs.getString("FKTABLE_NAME");
                if (!sameIdentifier(table.name(), sourceTable)
                        || (!table.catalog().isBlank()
                                && !sameIdentifier(table.catalog(), rs.getString("FKTABLE_CAT")))
                        || (!table.schema().isBlank()
                                && !sameIdentifier(table.schema(), rs.getString("FKTABLE_SCHEM")))) {
                    continue;
                }
                TableInfo target = new TableInfo(rs.getString("PKTABLE_CAT"),
                        rs.getString("PKTABLE_SCHEM"), rs.getString("PKTABLE_NAME"), "TABLE");
                if (target.name().isBlank()) {
                    continue;
                }
                String constraint = rs.getString("FK_NAME");
                String group = (constraint == null ? "" : constraint) + '\u0000'
                        + target.catalog() + '\u0000' + target.schema() + '\u0000' + target.name();
                grouped.computeIfAbsent(group, key -> new ArrayList<>()).add(new KeyPart(
                        rs.getShort("KEY_SEQ"), rs.getString("FKCOLUMN_NAME"), target,
                        rs.getString("PKCOLUMN_NAME")));
            }
        }

        List<ForeignKeyReference> references = new ArrayList<>();
        for (Map.Entry<String, List<KeyPart>> entry : grouped.entrySet()) {
            List<KeyPart> parts = entry.getValue();
            parts.sort(Comparator.comparingInt(KeyPart::sequence));
            List<String> foreignColumns = new ArrayList<>();
            List<String> referencedColumns = new ArrayList<>();
            for (KeyPart part : parts) {
                if (part.column() != null && !part.column().isBlank()
                        && part.referencedColumn() != null && !part.referencedColumn().isBlank()) {
                    foreignColumns.add(part.column());
                    referencedColumns.add(part.referencedColumn());
                }
            }
            if (!foreignColumns.isEmpty()) {
                String name = entry.getKey().substring(0, entry.getKey().indexOf('\u0000'));
                references.add(new ForeignKeyReference(name, parts.get(0).target(),
                        foreignColumns, referencedColumns));
            }
        }
        return references;
    }

    private record KeyPart(short sequence, String column, TableInfo target, String referencedColumn) {
    }

    /**
     * Guesses targets when the database has no declared constraint: tables with a primary key
     * column whose name matches the column, e.g. {@code owner_id} to {@code owners(id)}.
     */
    public List<ForeignKeyReference> candidateReferences(TableInfo source, String column)
            throws SQLException {
        requireConnection();
        if (column == null || column.isBlank()) {
            return List.of();
        }
        Map<String, ForeignKeyReference> found = new LinkedHashMap<>();
        for (String candidate : candidateColumnNames(column)) {
            int before = found.size();
            try (ResultSet rs = connection.getMetaData().getColumns(
                    nullable(source.catalog()), nullable(source.schema()), "%", candidate)) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    String columnName = rs.getString("COLUMN_NAME");
                    if (name == null || name.isBlank() || !sameIdentifier(candidate, columnName)
                            || sameIdentifier(source.name(), name)) {
                        continue;
                    }
                    String catalog = rs.getString("TABLE_CAT");
                    String schema = rs.getString("TABLE_SCHEM");
                    if (!source.catalog().isBlank() && !sameIdentifier(source.catalog(), catalog)) {
                        continue;
                    }
                    if (!source.schema().isBlank() && !sameIdentifier(source.schema(), schema)) {
                        continue;
                    }
                    List<String> keys = primaryKeys(catalog, schema, name);
                    String key = keys.stream()
                            .filter(k -> sameIdentifier(k, columnName))
                            .findFirst().orElse(null);
                    if (key == null) {
                        continue;
                    }
                    TableInfo target = new TableInfo(catalog, schema, name, "TABLE");
                    found.putIfAbsent(targetKey(target), new ForeignKeyReference("",
                            target, List.of(column), List.of(key)));
                }
            }
            // A nearer match (team_id) makes generic ones (id) unnecessary noise.
            if (found.size() > before) {
                break;
            }
        }
        List<ForeignKeyReference> references = new ArrayList<>(found.values());
        references.sort(Comparator
                .comparingInt((ForeignKeyReference ref) -> nameRelevance(ref.referencedTable().name(), column))
                .thenComparing(ref -> ref.referencedTable().name(), String.CASE_INSENSITIVE_ORDER));
        return references;
    }

    private static List<String> candidateColumnNames(String column) {
        Set<String> names = new LinkedHashSet<>();
        names.add(column);
        String base = baseName(column);
        if (!base.isEmpty() && !base.equalsIgnoreCase(column)) {
            names.add(base);
            names.add(base + "_id");
        }
        String lower = column.toLowerCase(Locale.ROOT);
        if (lower.endsWith("_id") || lower.endsWith("id")) {
            names.add("id");
        }
        return new ArrayList<>(names);
    }

    private static String baseName(String column) {
        String lower = column.toLowerCase(Locale.ROOT);
        if (lower.endsWith("_id")) {
            return lower.substring(0, lower.length() - 3);
        }
        if (lower.endsWith("id") && lower.length() > 2) {
            return lower.substring(0, lower.length() - 2);
        }
        return lower;
    }

    private static int nameRelevance(String tableName, String column) {
        String base = baseName(column);
        String name = tableName.toLowerCase(Locale.ROOT);
        if (!base.isEmpty() && name.equals(base)) {
            return 0;
        }
        if (!base.isEmpty() && (name.equals(base + "s") || name.equals(base + "ies"))) {
            return 1;
        }
        return 2;
    }

    private static String targetKey(TableInfo table) {
        return (table.catalog() + '\u0000' + table.schema() + '\u0000' + table.name())
                .toLowerCase(Locale.ROOT);
    }

    /** Builds a SELECT for the referenced rows, using the values in one result row. */
    public String referencedRowsSql(ForeignKeyReference reference, QueryResult.SourceTable source,
            Object[] row) throws SQLException {
        requireConnection();
        if (source == null) {
            throw new SQLException("This result set cannot be mapped to a table.");
        }
        String dbType = profile == null ? "" : profile.dbType();
        StringBuilder sql = new StringBuilder("SELECT * FROM ")
                .append(reference.referencedTable().qualifiedName(dbType)).append(" WHERE ");
        for (int i = 0; i < reference.foreignColumns().size(); i++) {
            if (i > 0) {
                sql.append(" AND ");
            }
            String foreign = reference.foreignColumns().get(i);
            int index = columnIndex(source.columnNames(), foreign);
            if (index < 0) {
                throw new SQLException("The query must include the foreign key column(s): "
                        + String.join(", ", reference.foreignColumns()));
            }
            Object value = row != null && index < row.length ? row[index] : null;
            sql.append(TableInfo.quoteIdentifier(reference.referencedColumns().get(i), dbType));
            if (value == null) {
                sql.append(" IS NULL");
            } else {
                sql.append(" = ").append(sqlLiteral(value, dbType));
            }
        }
        return sql.toString();
    }

    /** Returns a SQL literal for a value read from a result set. */
    public static String sqlLiteral(Object value) {
        return sqlLiteral(value, "");
    }

    private static String sqlLiteral(Object value, String dbType) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof byte[] bytes) {
            StringBuilder hex = new StringBuilder("X'");
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16))
                        .append(Character.forDigit(b & 0xf, 16));
            }
            return hex.append('\'').toString();
        }
        String text = value.toString();
        if ("mysql".equalsIgnoreCase(dbType)) {
            text = text.replace("\\", "\\\\");
        }
        return "'" + text.replace("'", "''") + "'";
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Executes a SQL script. If it produces result sets, the last one is returned. */
    public QueryResult execute(String script) throws SQLException {
        return execute(script, null);
    }

    /**
     * Executes a SQL script, stopping between statements/rows if {@code cancel} is requested.
     * The token also receives the active {@link Statement} so it can interrupt a blocked call.
     */
    public QueryResult execute(String script, QueryCancel cancel) throws SQLException {
        requireConnection();
        applyDefaultDatabase();
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
            if (cancel != null && cancel.isCancelled()) {
                break;
            }
            try (Statement stmt = connection.createStatement()) {
                if (cancel != null) {
                    cancel.watch(stmt);
                }
                try {
                    boolean hasResultSet = stmt.execute(sql);
                    while (true) {
                        if (cancel != null && cancel.isCancelled()) {
                            break;
                        }
                        if (hasResultSet) {
                            try (ResultSet rs = stmt.getResultSet()) {
                                QueryResult part = readResultSet(rs, cancel);
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
                } finally {
                    if (cancel != null) {
                        cancel.unwatch(stmt);
                    }
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

    /** One existing row to update, matched by its original primary key values. */
    public record RowUpdate(List<String> keyColumns, List<Object> keyValues,
            Map<String, Object> values) {

        public RowUpdate {
            keyColumns = List.copyOf(keyColumns);
            keyValues = Collections.unmodifiableList(new ArrayList<>(keyValues));
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    /** One new row to insert. Columns with no value are left to the database default. */
    public record RowInsert(Map<String, Object> values) {

        public RowInsert {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    /**
     * Applies row updates and inserts in a single transaction. Updates and inserts are prepared
     * from the column metadata of {@code source}, so values are bound with their JDBC types.
     *
     * @return the number of rows changed or added
     */
    public int applyChanges(QueryResult.SourceTable source, List<RowUpdate> updates,
            List<RowInsert> inserts) throws SQLException {
        requireConnection();
        if (updates.isEmpty() && inserts.isEmpty()) {
            return 0;
        }
        if (source == null || source.table() == null || source.table().isBlank()) {
            throw new SQLException("This result set cannot be mapped to a table.");
        }

        String dbType = profile == null ? "" : profile.dbType();
        String table = new TableInfo(source.catalog(), source.schema(), source.table(), "TABLE")
                .qualifiedName(dbType);
        Map<String, Integer> types = columnTypes(source);
        boolean previousAutoCommit = connection.getAutoCommit();
        int changed = 0;
        try {
            connection.setAutoCommit(false);
            for (RowUpdate update : updates) {
                changed += executeUpdate(connection, table, dbType, types, update);
            }
            for (RowInsert insert : inserts) {
                changed += executeInsert(connection, table, dbType, types, insert);
            }
            connection.commit();
            return changed;
        } catch (SQLException | RuntimeException e) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // Keep the original failure.
            }
            throw e;
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException ignored) {
                // The connection is still usable for the next statement.
            }
        }
    }

    private static int executeUpdate(Connection connection, String table, String dbType,
            Map<String, Integer> types, RowUpdate update) throws SQLException {
        if (update.values().isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("UPDATE ").append(table).append(" SET ");
        int index = 0;
        for (String column : update.values().keySet()) {
            if (index++ > 0) {
                sql.append(", ");
            }
            sql.append(TableInfo.quoteIdentifier(column, dbType)).append(" = ?");
        }
        sql.append(" WHERE ");
        List<Object> keyValues = update.keyValues();
        for (int i = 0; i < update.keyColumns().size(); i++) {
            if (i > 0) {
                sql.append(" AND ");
            }
            sql.append(TableInfo.quoteIdentifier(update.keyColumns().get(i), dbType)).append(" = ?");
        }
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameter = 1;
            for (Map.Entry<String, Object> entry : update.values().entrySet()) {
                bind(statement, parameter++, entry.getValue(), typeOf(types, entry.getKey()));
            }
            for (int i = 0; i < update.keyColumns().size(); i++) {
                Object value = i < keyValues.size() ? keyValues.get(i) : null;
                bind(statement, parameter++, value, typeOf(types, update.keyColumns().get(i)));
            }
            return Math.max(0, statement.executeUpdate());
        }
    }

    private static int executeInsert(Connection connection, String table, String dbType,
            Map<String, Integer> types, RowInsert insert) throws SQLException {
        if (insert.values().isEmpty()) {
            return 0;
        }
        StringBuilder columns = new StringBuilder();
        StringBuilder placeholders = new StringBuilder();
        int index = 0;
        for (String column : insert.values().keySet()) {
            if (index++ > 0) {
                columns.append(", ");
                placeholders.append(", ");
            }
            columns.append(TableInfo.quoteIdentifier(column, dbType));
            placeholders.append('?');
        }
        String sql = "INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            for (Map.Entry<String, Object> entry : insert.values().entrySet()) {
                bind(statement, parameter++, entry.getValue(), typeOf(types, entry.getKey()));
            }
            return Math.max(0, statement.executeUpdate());
        }
    }

    private static void bind(PreparedStatement statement, int index, Object value, Integer sqlType)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, sqlType == null ? java.sql.Types.NULL : sqlType);
        } else if (value instanceof String text) {
            statement.setString(index, text);
        } else {
            statement.setObject(index, value);
        }
    }

    private static Map<String, Integer> columnTypes(QueryResult.SourceTable source) {
        Map<String, Integer> types = new LinkedHashMap<>();
        for (int i = 0; i < source.columnNames().size(); i++) {
            String name = source.columnNames().get(i);
            if (!name.isBlank() && !types.containsKey(name) && i < source.columnTypes().size()) {
                types.put(name, source.columnTypes().get(i));
            }
        }
        return types;
    }

    private static Integer typeOf(Map<String, Integer> types, String column) {
        for (Map.Entry<String, Integer> entry : types.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(column)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private QueryResult readResultSet(ResultSet rs, QueryCancel cancel) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();

        List<String> columns = new ArrayList<>(columnCount);
        List<String> columnNames = new ArrayList<>(columnCount);
        List<Integer> columnTypes = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            String label = meta.getColumnLabel(i);
            columns.add(label == null || label.isBlank() ? "Column " + i : label);
            String name = meta.getColumnName(i);
            columnNames.add(name == null ? "" : name);
            columnTypes.add(meta.getColumnType(i));
        }

        List<String[]> rows = new ArrayList<>();
        List<Object[]> rawRows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (cancel != null && cancel.isCancelled()) {
                break;
            }
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

        QueryResult.SourceTable source = singleTableSource(meta, columnCount, columnNames, columnTypes);
        return new QueryResult(columns, rows, rawRows, List.of(), 0, truncated, 1, source);
    }

    /** SQLite reports the table name as the catalog; ignore such redundant qualifiers. */
    private static String tablePart(String value, String table) {
        return value == null || value.isBlank() || value.equalsIgnoreCase(table) ? "" : value;
    }

    /**
     * Returns the table a result set came from, or null when the columns do not all belong to
     * one table. Editing a result is only safe when it maps back to a single table.
     */
    private QueryResult.SourceTable singleTableSource(ResultSetMetaData meta, int columnCount,
            List<String> columnNames, List<Integer> columnTypes) throws SQLException {
        String table = "";
        String catalog = "";
        String schema = "";
        for (int i = 1; i <= columnCount; i++) {
            String name = meta.getTableName(i);
            if (name == null || name.isBlank()) {
                continue;
            }
            String partCatalog = tablePart(meta.getCatalogName(i), name);
            String partSchema = tablePart(meta.getSchemaName(i), name);
            if (table.isEmpty()) {
                table = name;
                catalog = partCatalog;
                schema = partSchema;
            } else if (!table.equalsIgnoreCase(name)
                    || !sameIdentifier(catalog, partCatalog)
                    || !sameIdentifier(schema, partSchema)) {
                return null;
            }
        }
        if (table.isEmpty()) {
            return null;
        }
        return new QueryResult.SourceTable(catalog, schema, table,
                columnNames, columnTypes, primaryKeys(catalog, schema, table));
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
