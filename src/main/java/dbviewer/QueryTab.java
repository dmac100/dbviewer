package dbviewer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

/**
 * A query editor and its result table. The widgets live in one control that can be reparented
 * between tab folders, so moving a tab keeps its editor text, selection and results intact.
 */
final class QueryTab {

    private final DbViewer owner;
    private final int id;
    private final String title;

    private final SashForm control;
    private final StyledText editor;
    private final SqlSyntaxHighlighter highlighter;
    private final Table resultTable;
    private Label resultInfo;

    private MenuItem copyFieldItem;
    private MenuItem copyRowItem;
    private MenuItem copyAllItem;
    private MenuItem exportCsvItem;
    private MenuItem deleteRowItem;

    private List<String> columns = List.of();
    private List<String[]> rows = List.of();
    private List<Object[]> rawRows = List.of();
    private QueryResult currentResult;
    private boolean truncated;
    private long elapsedMillis;
    private int selectedColumn;
    private int sortColumn = -1;
    private boolean sortAscending = true;

    /** True while the old item is being disposed during a move, so the control survives. */
    private boolean moving;
    private CTabItem item;

    QueryTab(DbViewer owner, int id, String title, CTabFolder folder) {
        this.owner = owner;
        this.id = id;
        this.title = title;

        item = new CTabItem(folder, SWT.CLOSE);
        control = new SashForm(folder, SWT.VERTICAL);
        editor = createEditor(control);
        highlighter = new SqlSyntaxHighlighter(editor);

        Composite resultPane = new Composite(control, SWT.NONE);
        resultPane.setLayout(new GridLayout(1, false));
        createResultToolbar(resultPane);
        resultTable = createResultTable(resultPane);
        createResultContextMenu();
        control.setWeights(new int[] {45, 55});

        item.setControl(control);
        bind(item);
    }

    private void bind(CTabItem bound) {
        bound.setText(title);
        bound.setData("tab", this);
        bound.addListener(SWT.Dispose, e -> {
            if (!moving) {
                highlighter.dispose();
            }
        });
    }

    int id() {
        return id;
    }

    CTabItem item() {
        return item;
    }

    /** Moves this tab (and its existing control) into {@code folder} at {@code index}. */
    void moveTo(CTabFolder folder, int index) {
        CTabItem old = item;
        boolean sameFolder = old.getParent() == folder;
        moving = true;
        old.dispose();
        item = new CTabItem(folder, SWT.CLOSE, Math.max(0, Math.min(index, folder.getItemCount())));
        if (!sameFolder) {
            control.setParent(folder);
        }
        item.setControl(control);
        bind(item);
        moving = false;
        folder.setSelection(item);
    }

    void dispose() {
        moving = false;
        if (item != null && !item.isDisposed()) {
            item.dispose();
        }
        if (!control.isDisposed()) {
            control.dispose();
        }
    }

    void focusEditor() {
        if (!editor.isDisposed()) {
            editor.setFocus();
        }
    }

    /** Inserts a query into this tab, replacing its current contents. */
    void insertQuery(String sql) {
        editor.setText(sql);
        editor.setFocus();
        editor.setSelection(sql.length());
    }

    void clearEditor() {
        editor.setText("");
        editor.setFocus();
    }

    /** Runs the statement under the cursor (Ctrl+Enter). */
    void runCurrent() {
        String script = editor.getText();
        SqlSplitter.Statement statement = SqlSplitter.statementAt(
                SqlSplitter.splitWithPositions(script), editor.getCaretOffset());
        if (statement == null) {
            owner.setStatus("Enter a SQL query first.", true);
            return;
        }
        execute(statement.sql());
    }

    /** Runs every statement in the editor (Ctrl+Shift+Enter, F5, Run Query). */
    void runAll() {
        execute(editor.getText());
    }

    private void execute(String sql) {
        if (!owner.database().isConnected()) {
            owner.showInfo("Connect to a database first.");
            return;
        }
        if (sql.isBlank()) {
            owner.setStatus("Enter a SQL query first.", true);
            return;
        }
        if (owner.isQueryRunning()) {
            owner.setStatus("A query is already running.", true);
            return;
        }

        QueryCancel cancel = new QueryCancel();
        owner.beginQuery(cancel);
        owner.setStatus("Running query...", false);

        Thread worker = new Thread(() -> runInBackground(sql, cancel), "dbviewer-query");
        worker.setDaemon(true);
        worker.start();
    }

    /** Runs the query away from the UI thread so it can be stopped while in progress. */
    private void runInBackground(String sql, QueryCancel cancel) {
        try {
            scheduleFinish(owner.database().execute(sql, cancel), null, cancel);
        } catch (SQLException e) {
            scheduleFinish(null, e, cancel);
        } catch (RuntimeException e) {
            scheduleFinish(null, new SQLException("Unexpected error: " + e.getMessage(), e), cancel);
        }
    }

    private void scheduleFinish(QueryResult result, SQLException failure, QueryCancel cancel) {
        if (!owner.display().isDisposed()) {
            owner.display().asyncExec(() -> finishQuery(result, failure, cancel));
        }
    }

    /** Applies a finished query on the UI thread and then releases the running-query state. */
    private void finishQuery(QueryResult result, SQLException failure, QueryCancel cancel) {
        try {
            if (resultTable.isDisposed()) {
                return;
            }
            if (cancel.isCancelled()) {
                owner.setStatus("Query stopped.", false);
            } else if (failure != null) {
                owner.reportError("Query failed", failure);
                owner.setStatus("Query failed: " + DbViewer.messageOf(failure), true);
            } else {
                showResult(result);
                owner.setStatus("Query executed in " + result.elapsedMillis() + " ms.", false);
            }
        } finally {
            owner.endQuery();
        }
    }

    /** Clears this tab's result table. */
    void clearResults() {
        clearResultTable();
        owner.setStatus("Results cleared.", false);
    }

    private StyledText createEditor(Composite parent) {
        StyledText text = new StyledText(parent,
                SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
        text.setFont(owner.monoFont());
        text.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        text.addListener(SWT.KeyDown, e -> {
            int runModifier = SWT.CONTROL | SWT.COMMAND;
            if ((e.stateMask & runModifier) != 0
                    && (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR)) {
                if ((e.stateMask & SWT.SHIFT) != 0) {
                    runAll();
                } else {
                    runCurrent();
                }
                e.doit = false;
            }
        });
        return text;
    }

    private void createResultToolbar(Composite parent) {
        Composite toolbar = new Composite(parent, SWT.NONE);
        toolbar.setLayout(new GridLayout(2, false));
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label header = new Label(toolbar, SWT.NONE);
        header.setText("Results");
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        resultInfo = new Label(toolbar, SWT.NONE);
        resultInfo.setText("No results");
        resultInfo.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
    }

    private Table createResultTable(Composite parent) {
        Table table = new Table(parent,
                SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI | SWT.VIRTUAL
                        | SWT.V_SCROLL | SWT.H_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        table.addListener(SWT.SetData, e -> {
            TableItem rowItem = (TableItem) e.item;
            int index = table.indexOf(rowItem);
            if (index < 0 || index >= rows.size()) {
                return;
            }
            String[] row = rows.get(index);
            int count = Math.min(row.length, table.getColumnCount());
            for (int i = 0; i < count; i++) {
                rowItem.setText(i, row[i]);
            }
        });
        // Remember which field was clicked so "Copy Field" can copy that cell.
        table.addListener(SWT.MouseDown, e -> selectedColumn = columnAt(e.x));
        table.addListener(SWT.KeyDown, e -> {
            if ((e.stateMask & SWT.MOD1) != 0 && (e.keyCode == 'c' || e.keyCode == 'C')) {
                copySelectionAsCsv();
                e.doit = false;
            }
        });
        return table;
    }

    private void createColumn(String name) {
        int index = resultTable.getColumnCount();
        TableColumn column = new TableColumn(resultTable, SWT.NONE);
        column.setText(headerText(index));
        column.setWidth(Math.min(320, Math.max(90, name.length() * 9 + 20)));
        column.addListener(SWT.Selection, e -> sortBy(index));
    }

    /** Sorts result rows by one column, toggling between ascending and descending. */
    private void sortBy(int column) {
        if (column < 0 || column >= columns.size()) {
            return;
        }
        if (sortColumn == column) {
            sortAscending = !sortAscending;
        } else {
            sortColumn = column;
            sortAscending = true;
        }

        List<Integer> order = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            order.add(i);
        }
        order.sort((left, right) -> {
            int result = NatCmp.INSTANCE.compare(cell(left, column), cell(right, column));
            return sortAscending ? result : -result;
        });

        List<String[]> sortedRows = new ArrayList<>(rows.size());
        List<Object[]> sortedRawRows = new ArrayList<>(rows.size());
        for (int index : order) {
            sortedRows.add(rows.get(index));
            if (index < rawRows.size()) {
                sortedRawRows.add(rawRows.get(index));
            }
        }
        rows = List.copyOf(sortedRows);
        rawRows = List.copyOf(sortedRawRows);

        updateColumnHeaders();
        resultTable.deselectAll();
        resultTable.clearAll();
        resultTable.redraw();
        owner.setStatus("Sorted by " + columns.get(column)
                + (sortAscending ? " (ascending)." : " (descending)."), false);
    }

    private void updateColumnHeaders() {
        TableColumn[] tableColumns = resultTable.getColumns();
        for (int i = 0; i < tableColumns.length; i++) {
            tableColumns[i].setText(headerText(i));
        }
    }

    private String headerText(int column) {
        if (column < 0 || column >= columns.size()) {
            return "";
        }
        if (column != sortColumn) {
            return columns.get(column);
        }
        return columns.get(column) + (sortAscending ? "  \u25B2" : "  \u25BC");
    }

    private int columnAt(int x) {
        ScrollBar horizontalBar = resultTable.getHorizontalBar();
        int position = x + (horizontalBar == null ? 0 : horizontalBar.getSelection());
        int edge = 0;
        TableColumn[] tableColumns = resultTable.getColumns();
        for (int i = 0; i < tableColumns.length; i++) {
            edge += tableColumns[i].getWidth();
            if (position < edge) {
                return i;
            }
        }
        return Math.max(0, tableColumns.length - 1);
    }

    private void createResultContextMenu() {
        Menu menu = new Menu(resultTable);
        resultTable.setMenu(menu);
        copyFieldItem = contextAction(menu, "Copy Field", this::copyField);
        copyRowItem = contextAction(menu, "Copy Row", () -> copyRows(false));
        copyAllItem = contextAction(menu, "Copy All Rows", () -> copyRows(true));
        exportCsvItem = contextAction(menu, "Export Results as CSV...", this::exportCsv);
        new MenuItem(menu, SWT.SEPARATOR);
        deleteRowItem = contextAction(menu, "Delete Row(s)", this::deleteRows);
        menu.addListener(SWT.Show, e -> updateContextMenu());
    }

    private MenuItem contextAction(Menu menu, String text, Runnable action) {
        MenuItem menuItem = new MenuItem(menu, SWT.PUSH);
        menuItem.setText(text);
        menuItem.addListener(SWT.Selection, e -> action.run());
        return menuItem;
    }

    private void updateContextMenu() {
        int[] selection = resultTable.getSelectionIndices();
        copyFieldItem.setEnabled(selection.length > 0 && selectedColumn < columns.size());
        copyRowItem.setEnabled(selection.length > 0);
        copyAllItem.setEnabled(!rows.isEmpty());
        exportCsvItem.setEnabled(hasResults());
        deleteRowItem.setEnabled(selection.length > 0);
    }

    private void copyField() {
        int[] selection = resultTable.getSelectionIndices();
        if (selection.length == 0 || selectedColumn >= columns.size()) {
            return;
        }
        copyToClipboard(cell(selection[0], selectedColumn));
        owner.setStatus("Field copied to clipboard.", false);
    }

    private void copyRows(boolean all) {
        int[] selection = all ? allRowIndices() : resultTable.getSelectionIndices();
        if (selection.length == 0) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (int row : selection) {
            StringJoiner fields = new StringJoiner("\t");
            for (int column = 0; column < columns.size(); column++) {
                fields.add(cell(row, column));
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(fields);
        }
        copyToClipboard(text.toString());
        owner.setStatus(selection.length + (selection.length == 1 ? " row" : " rows")
                + " copied to clipboard.", false);
    }

    /** Ctrl+C on the results copies the selected rows as CSV records. */
    private void copySelectionAsCsv() {
        int[] selection = resultTable.getSelectionIndices();
        if (selection.length == 0) {
            owner.setStatus("Select rows in the results to copy as CSV.", true);
            return;
        }
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < selection.length; i++) {
            if (i > 0) {
                csv.append('\n');
            }
            csv.append(csvRow(selection[i]));
        }
        copyToClipboard(csv.toString());
        owner.setStatus(selection.length + (selection.length == 1 ? " row" : " rows")
                + " copied as CSV.", false);
    }

    /** CSV records use the underlying JDBC values, not the shortened display text. */
    private String csvRow(int row) {
        String[] fields = new String[columns.size()];
        for (int column = 0; column < fields.length; column++) {
            fields[column] = rawCell(row, column);
        }
        return Csv.line(fields);
    }

    private String rawCell(int row, int column) {
        if (row < 0 || row >= rawRows.size() || column < 0
                || column >= rawRows.get(row).length) {
            return "";
        }
        return CellFormatter.rawText(rawRows.get(row)[column]);
    }

    boolean hasResults() {
        return !columns.isEmpty();
    }

    /** Saves the displayed rows and column headers as a CSV file. */
    void exportCsv() {
        if (!hasResults()) {
            owner.showInfo("There are no results to export.");
            return;
        }

        FileDialog dialog = new FileDialog(owner.shell(), SWT.SAVE);
        dialog.setText("Export Results as CSV");
        dialog.setFilterNames(new String[] {"CSV files (*.csv)", "All files (*.*)"});
        dialog.setFilterExtensions(new String[] {"*.csv", "*.*"});
        dialog.setFileName(defaultExportName());
        String path = dialog.open();
        if (path == null || path.isBlank()) {
            return;
        }

        try (BufferedWriter writer = Files.newBufferedWriter(
                Path.of(path), StandardCharsets.UTF_8)) {
            Csv.write(writer, columns, rows);
        } catch (IOException | RuntimeException e) {
            owner.reportError("Could not export results", e);
            owner.setStatus("Could not export results: " + e.getMessage(), true);
            return;
        }
        owner.setStatus("Exported " + rows.size() + (rows.size() == 1 ? " row" : " rows")
                + " to " + path, false);
    }

    private String defaultExportName() {
        QueryResult.SourceTable source = currentResult == null ? null : currentResult.sourceTable();
        String name = source == null || source.table().isBlank() ? "results" : source.table();
        return name + ".csv";
    }

    private int[] allRowIndices() {
        int[] indices = new int[rows.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = i;
        }
        return indices;
    }

    private String cell(int row, int column) {
        if (row < 0 || row >= rows.size() || column < 0 || column >= rows.get(row).length) {
            return "";
        }
        String value = rows.get(row)[column];
        return value == null ? "" : value;
    }

    private void copyToClipboard(String text) {
        Clipboard clipboard = new Clipboard(resultTable.getDisplay());
        try {
            clipboard.setContents(new Object[] {text}, new Transfer[] {TextTransfer.getInstance()});
        } finally {
            clipboard.dispose();
        }
    }

    /** Deletes the selected rows from the database after confirmation, then from the view. */
    private void deleteRows() {
        int[] selection = resultTable.getSelectionIndices();
        if (selection.length == 0) {
            return;
        }
        QueryResult.SourceTable source = currentResult == null ? null : currentResult.sourceTable();
        if (source == null) {
            owner.showInfo("This result set cannot be mapped to a table, so its rows cannot be deleted.");
            return;
        }

        ConnectionProfile profile = owner.database().profile();
        String dbType = profile == null ? "" : profile.dbType();
        String table = new TableInfo(source.catalog(), source.schema(), source.table(), "TABLE")
                .qualifiedName(dbType);
        String question = "Delete " + selection.length
                + (selection.length == 1 ? " row" : " rows") + " from " + table + "?";
        if (!owner.confirm(question, "This cannot be undone.")) {
            return;
        }

        List<Object[]> values = new ArrayList<>();
        for (int index : selection) {
            if (index >= 0 && index < rawRows.size()) {
                values.add(rawRows.get(index));
            }
        }
        try {
            int deleted = owner.database().deleteRows(source, values);
            Set<Integer> removed = new HashSet<>();
            for (int index : selection) {
                removed.add(index);
            }
            List<String[]> remainingRows = new ArrayList<>();
            List<Object[]> remainingRaw = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                if (!removed.contains(i)) {
                    remainingRows.add(rows.get(i));
                    if (i < rawRows.size()) {
                        remainingRaw.add(rawRows.get(i));
                    }
                }
            }
            rows = List.copyOf(remainingRows);
            rawRows = List.copyOf(remainingRaw);
            resultTable.setItemCount(rows.size());
            resultTable.deselectAll();
            updateResultInfo();
            owner.setStatus("Deleted " + deleted + (deleted == 1 ? " row" : " rows")
                    + " from " + source.table() + ".", false);
        } catch (SQLException e) {
            owner.reportError("Could not delete rows", e);
            owner.setStatus("Could not delete rows: " + DbViewer.messageOf(e), true);
        }
    }

    private void showResult(QueryResult result) {
        clearResultTable();
        currentResult = result;
        elapsedMillis = result.elapsedMillis();

        if (result.hasRows()) {
            columns = result.columns();
            for (String name : columns) {
                createColumn(name);
            }
            rows = result.rows();
            rawRows = result.rawRows();
            truncated = result.truncated();
            resultTable.setItemCount(rows.size());
            updateResultInfo();
        } else {
            int updates = result.totalUpdates();
            String info = result.statementCount()
                    + (result.statementCount() == 1 ? " statement" : " statements");
            if (updates > 0) {
                info += ", " + updates + (updates == 1 ? " row" : " rows") + " affected";
            }
            resultInfo.setText(info + "   •   " + elapsedMillis + " ms");
        }
        owner.updateConnectionUi();
    }

    private void updateResultInfo() {
        String info = rows.size() + (rows.size() == 1 ? " row" : " rows");
        if (truncated) {
            info += " (first " + DatabaseService.MAX_ROWS + " shown)";
        }
        resultInfo.setText(info + "   •   " + elapsedMillis + " ms");
    }

    private void clearResultTable() {
        for (TableColumn column : resultTable.getColumns()) {
            column.dispose();
        }
        resultTable.removeAll();
        resultTable.setItemCount(0);
        columns = List.of();
        rows = List.of();
        rawRows = List.of();
        currentResult = null;
        truncated = false;
        elapsedMillis = 0;
        selectedColumn = 0;
        sortColumn = -1;
        sortAscending = true;
        resultInfo.setText("No results");
        owner.updateConnectionUi();
    }
}
