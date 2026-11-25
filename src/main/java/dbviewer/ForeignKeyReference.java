package dbviewer;

import java.util.List;

/** One possible target of a foreign key: columns in the source map to columns in another table. */
public record ForeignKeyReference(
        String name, TableInfo referencedTable, List<String> foreignColumns, List<String> referencedColumns) {

    public ForeignKeyReference {
        name = name == null ? "" : name;
        foreignColumns = List.copyOf(foreignColumns);
        referencedColumns = List.copyOf(referencedColumns);
    }

    /** True when this reference can be followed from the given source column. */
    public boolean hasColumn(String column) {
        return column != null && foreignColumns.stream().anyMatch(c -> c.equalsIgnoreCase(column));
    }

    /** Short description for the choice dialog. */
    public String label(String dbType) {
        return referencedTable.qualifiedName(dbType) + "  [" + String.join(", ", referencedColumns) + "]";
    }
}
