package dbviewer;

/** A table or view discovered through JDBC metadata. */
public record TableInfo(String catalog, String schema, String name, String type) {

    public TableInfo {
        catalog = nullToEmpty(catalog);
        schema = nullToEmpty(schema);
        name = nullToEmpty(name);
        type = nullToEmpty(type);
    }

    /**
     * Returns a quoted, optionally qualified name suitable for use in a query.
     * MySQL uses backticks; other databases use standard double quotes.
     */
    public String qualifiedName(String dbType) {
        String quote = "mysql".equalsIgnoreCase(dbType) ? "`" : "\"";
        StringBuilder sb = new StringBuilder();
        appendPart(sb, catalog, quote);
        appendPart(sb, schema, quote);
        if (sb.length() > 0) {
            sb.append('.');
        }
        sb.append(quote).append(name.replace(quote, quote + quote)).append(quote);
        return sb.toString();
    }

    /** Quotes a single identifier using the database's quoting convention. */
    public static String quoteIdentifier(String identifier, String dbType) {
        String quote = "mysql".equalsIgnoreCase(dbType) ? "`" : "\"";
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    private static void appendPart(StringBuilder sb, String part, String quote) {
        if (part.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('.');
        }
        sb.append(quote).append(part.replace(quote, quote + quote)).append(quote);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
