package dbviewer;

/** A column discovered through JDBC metadata. */
public record ColumnInfo(String name, String type, boolean primaryKey, int ordinal) {

    public ColumnInfo {
        name = name == null ? "" : name;
        type = type == null ? "" : type;
    }

    /** Text shown for the column in the database tree. */
    public String displayLabel() {
        StringBuilder label = new StringBuilder(name);
        if (!type.isBlank()) {
            label.append("  [").append(type).append(']');
        }
        if (primaryKey) {
            label.append("  (PK)");
        }
        return label.toString();
    }
}
