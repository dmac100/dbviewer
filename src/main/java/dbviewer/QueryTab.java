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
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.TableEditor;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

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
    private MenuItem openForeignKeyItem;
    private MenuItem deleteRowItem;

    private ForeignKeyLookup foreignKeyLookup = new ForeignKeyLookup(List.of(), false, false);

    private Button cancelChangesButton;
    private Button saveChangesButton;
    private Label editHint;

    private TableEditor cellEditor;
    private Text cellText;
    private int editRow = -1;
    private int editColumn = -1;
    private boolean closingCellEditor;
    private TableEditModel editModel;

    private List<String> columns = List.of();
    private List<String[]> rows = List.of();
    private List<Object[]> rawRows = List.of();
    private QueryResult currentResult;
    private String lastSql = "";
    private boolean truncated;
    private long elapsedMillis;
    private int selectedRow = -1;
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
            scheduleFinish(sql, owner.database().execute(sql, cancel), null, cancel);
        } catch (SQLException e) {
            scheduleFinish(sql, null, e, cancel);
        } catch (RuntimeException e) {
            scheduleFinish(sql, null, new SQLException("Unexpected error: " + e.getMessage(), e), cancel);
        }
    }

    private void scheduleFinish(String sql, QueryResult result, SQLException failure, QueryCancel cancel) {
        if (!owner.display().isDisposed()) {
            owner.display().asyncExec(() -> finishQuery(sql, result, failure, cancel));
        }
    }

    /** Applies a finished query on the UI thread and then releases the running-query state. */
    private void finishQuery(String sql, QueryResult result, SQLException failure, QueryCancel cancel) {
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
                lastSql = sql;
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
            } else if ((e.stateMask & runModifier) != 0 && e.keyCode == 'a') {
                text.selectAll();
                e.doit = false;
            }
        });
        return text;
    }

    private void createResultToolbar(Composite parent) {
        Composite toolbar = new Composite(parent, SWT.NONE);
        toolbar.setLayout(new GridLayout(5, false));
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label header = new Label(toolbar, SWT.NONE);
        header.setText("Results");
        header.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        editHint = new Label(toolbar, SWT.NONE);
        editHint.setForeground(owner.display().getSystemColor(SWT.COLOR_DARK_GRAY));
        editHint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        cancelChangesButton = new Button(toolbar, SWT.PUSH);
        cancelChangesButton.setText("Cancel Changes");
        cancelChangesButton.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        cancelChangesButton.addListener(SWT.Selection, e -> cancelChanges());

        saveChangesButton = new Button(toolbar, SWT.PUSH);
        saveChangesButton.setText("Save Changes");
        saveChangesButton.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        saveChangesButton.addListener(SWT.Selection, e -> saveChanges());

        resultInfo = new Label(toolbar, SWT.NONE);
        resultInfo.setText("No results");
        resultInfo.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        updateEditControls();
    }

    private Table createResultTable(Composite parent) {
        Table table = new Table(parent,
                SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI | SWT.VIRTUAL
                        | SWT.V_SCROLL | SWT.H_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        table.addListener(SWT.SetData, e -> fillResultItem((TableItem) e.item));
        table.addListener(SWT.MouseDown, e -> {
            if (e.button == 3) {
                TableItem item = table.getItem(new Point(e.x, e.y));
                selectedRow = item == null ? -1 : table.indexOf(item);
                selectedColumn = columnAt(e.x);
                commitCellEdit();
                return;
            }
            if (e.button != 1) {
                return;
            }
            TableItem item = table.getItem(new Point(e.x, e.y));
            if (item == null) {
                return;
            }
            selectedRow = table.indexOf(item);
            selectedColumn = columnAt(e.x);
            if (editModel == null
                    || (e.stateMask & (SWT.CTRL | SWT.SHIFT | SWT.ALT | SWT.COMMAND)) != 0) {
                return;
            }
            if (selectedRow > editModel.rowCount()) {
                return;
            }
            int row = selectedRow;
            int column = selectedColumn;
            // Let SWT finish its own selection handling before the editor takes focus.
            owner.display().asyncExec(() -> {
                if (!table.isDisposed() && editModel != null) {
                    beginCellEdit(row, column);
                }
            });
        });
        table.addListener(SWT.KeyDown, e -> {
            if (editModel == null) {
                return;
            }
            if (e.keyCode == SWT.F2 || e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) {
                int row = table.getSelectionIndex();
                if (row >= 0) {
                    beginCellEdit(row, selectedColumn);
                }
                e.doit = false;
            }
        });
        table.addListener(SWT.KeyDown, e -> {
            if ((e.stateMask & SWT.MOD1) != 0 && (e.keyCode == 'c' || e.keyCode == 'C')) {
                copySelectionAsCsv();
                e.doit = false;
            }
        });
        return table;
    }

    /** Fills one virtual table item from the committed rows or the pending edits. */
    private void fillResultItem(TableItem rowItem) {
        int index = resultTable.indexOf(rowItem);
        if (index < 0) {
            return;
        }
        Color background = resultTable.getBackground();
        Color foreground = resultTable.getForeground();
        int dataRows = dataRowCount();

        if (editModel != null && index == dataRows) {
            for (int i = 0; i < resultTable.getColumnCount(); i++) {
                rowItem.setBackground(i, background);
                rowItem.setForeground(i, i == 0
                        ? owner.display().getSystemColor(SWT.COLOR_DARK_GRAY) : foreground);
                rowItem.setText(i, i == 0 ? "(new row)" : "");
            }
            return;
        }
        if (index >= dataRows) {
            return;
        }

        for (int i = 0; i < resultTable.getColumnCount(); i++) {
            String text;
            boolean edited = false;
            if (editModel != null) {
                text = editModel.displayText(index, i);
                edited = editModel.isCellEdited(index, i);
            } else {
                String[] row = rows.get(index);
                text = i < row.length && row[i] != null ? row[i] : "";
            }
            rowItem.setText(i, text);
            rowItem.setForeground(i, foreground);
            rowItem.setBackground(i, edited
                    ? owner.display().getSystemColor(SWT.COLOR_INFO_BACKGROUND) : background);
        }
    }

    private int dataRowCount() {
        return editModel == null ? rows.size() : editModel.rowCount();
    }

    /** Redraws all rows, including the extra "new row" slot when the result is editable. */
    private void refreshResultTable() {
        closeCellEditor();
        int count = dataRowCount() + (editModel == null ? 0 : 1);
        resultTable.setItemCount(count);
        resultTable.clearAll();
        resultTable.redraw();
        updateResultInfo();
        updateEditControls();
    }

    private void updateEditControls() {
        if (cancelChangesButton == null || cancelChangesButton.isDisposed()) {
            return;
        }
        boolean dirty = editModel != null && editModel.isDirty();
        cancelChangesButton.setEnabled(dirty);
        saveChangesButton.setEnabled(dirty);
        editHint.setText(editHintText());
    }

    private String editHintText() {
        if (editModel != null) {
            return editModel.isDirty()
                    ? "Unsaved changes. Click a cell to keep editing, or the last row to add one."
                    : "Click a cell to edit; click the last row to add one.";
        }
        if (rows.isEmpty() || currentResult == null) {
            return "";
        }
        QueryResult.SourceTable source = currentResult.sourceTable();
        if (source == null) {
            return "Read-only: the result does not come from a single table.";
        }
        if (source.keyColumns().isEmpty()) {
            return "Read-only: this table has no primary key.";
        }
        return "Read-only: include the primary key column(s) in the query to edit.";
    }

    /** Opens an inline text editor over one cell. */
    private void beginCellEdit(int row, int column) {
        if (editModel == null || resultTable.isDisposed()) {
            return;
        }
        if (row < 0 || row > editModel.rowCount()
                || column < 0 || column >= resultTable.getColumnCount()) {
            return;
        }
        if (!editModel.isColumnEditable(column)) {
            owner.setStatus("Column " + columns.get(column)
                    + " is not a plain table column and cannot be edited.", true);
            return;
        }
        closeCellEditor();

        TableItem item = resultTable.getItem(row);
        if (item == null) {
            return;
        }
        editRow = row;
        editColumn = column;
        cellEditor = new TableEditor(resultTable);
        cellEditor.horizontalAlignment = SWT.LEFT;
        cellEditor.grabHorizontal = true;
        cellEditor.minimumWidth = Math.max(30, resultTable.getColumn(column).getWidth());

        cellText = new Text(resultTable, SWT.NONE);
        cellText.setFont(resultTable.getFont());
        cellText.setText(row == editModel.rowCount() ? "" : editModel.displayText(row, column));
        cellText.selectAll();
        cellText.addListener(SWT.FocusOut, e -> {
            if (!closingCellEditor) {
                commitCellEdit();
            }
        });
        cellText.addListener(SWT.Traverse, e -> {
            if (e.detail == SWT.TRAVERSE_ESCAPE) {
                cancelCellEdit();
                e.doit = false;
            } else if (e.detail == SWT.TRAVERSE_RETURN) {
                commitCellEdit();
                e.doit = false;
            }
        });
        cellText.addListener(SWT.KeyDown, e -> {
            if (e.keyCode == SWT.ESC) {
                cancelCellEdit();
                e.doit = false;
            } else if (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) {
                commitCellEdit();
                e.doit = false;
            }
        });
        cellEditor.setEditor(cellText, item, column);
        cellText.setFocus();
    }

    /** Stores the text in the open cell editor and closes it. */
    private void commitCellEdit() {
        if (closingCellEditor || cellText == null || cellText.isDisposed()) {
            return;
        }
        String text = cellText.getText();
        int row = editRow;
        int column = editColumn;
        closingCellEditor = true;
        try {
            if (cellEditor != null) {
                cellEditor.dispose();
            }
            cellText.dispose();
        } finally {
            cellEditor = null;
            cellText = null;
            editRow = -1;
            editColumn = -1;
            closingCellEditor = false;
        }
        if (editModel != null) {
            try {
                editModel.setCell(row, column, text);
            } catch (IllegalArgumentException e) {
                owner.setStatus(e.getMessage(), true);
            }
        }
        refreshResultTable();
    }

    /** Discards the value being typed in the open cell editor. */
    private void cancelCellEdit() {
        closingCellEditor = true;
        try {
            if (cellEditor != null) {
                cellEditor.dispose();
            }
            if (cellText != null) {
                cellText.dispose();
            }
        } finally {
            cellEditor = null;
            cellText = null;
            editRow = -1;
            editColumn = -1;
            closingCellEditor = false;
        }
        resultTable.setFocus();
    }

    /** Closes the open editor, keeping whatever was typed (used before other actions). */
    private void closeCellEditor() {
        if (!closingCellEditor && cellText != null && !cellText.isDisposed()) {
            commitCellEdit();
        }
    }

    private void rebuildEditModel() {
        editModel = TableEditModel.create(
                currentResult == null ? null : currentResult.sourceTable(), columns, rawRows);
    }

    private void cancelChanges() {
        if (editModel == null || !editModel.isDirty()) {
            return;
        }
        cancelCellEdit();
        if (!owner.confirm("Cancel Changes", "Discard the unsaved changes?",
                "The database will not be modified.")) {
            return;
        }
        rebuildEditModel();
        refreshResultTable();
        owner.setStatus("Changes discarded.", false);
    }

    private void saveChanges() {
        if (editModel == null || !editModel.isDirty()) {
            return;
        }
        closeCellEditor();
        if (!editModel.isDirty()) {
            refreshResultTable();
            return;
        }

        ConnectionProfile profile = owner.database().profile();
        String dbType = profile == null ? "" : profile.dbType();
        TableEditModel.Changes changes;
        try {
            changes = editModel.changes(dbType);
        } catch (IllegalArgumentException e) {
            owner.showInfo(e.getMessage());
            owner.setStatus(e.getMessage(), true);
            return;
        }
        if (changes.isEmpty()) {
            refreshResultTable();
            return;
        }
        if (!new SaveChangesDialog(owner.shell(), owner.monoFont()).open(changes.summary())) {
            return;
        }

        try {
            int affected = owner.database().applyChanges(
                    editModel.source(), changes.updates(), changes.inserts());
            owner.setStatus("Saved " + changes.count()
                    + (changes.count() == 1 ? " changed row" : " changed rows")
                    + " (" + affected + " affected).", false);
            reloadAfterSave();
        } catch (SQLException e) {
            owner.reportError("Could not save changes", e);
            owner.setStatus("Could not save changes: " + DbViewer.messageOf(e), true);
        }
    }

    /** Refreshes a single SELECT after saving; otherwise adopts the saved values locally. */
    private void reloadAfterSave() {
        if (isSingleSelect(lastSql)) {
            try {
                showResult(owner.database().execute(lastSql));
                return;
            } catch (SQLException e) {
                owner.reportError("Changes were saved, but the result could not be refreshed", e);
            }
        }

        List<Object[]> saved = editModel.committedRows();
        List<String[]> formatted = new ArrayList<>(saved.size());
        for (Object[] raw : saved) {
            String[] texts = new String[columns.size()];
            for (int i = 0; i < texts.length; i++) {
                texts[i] = CellFormatter.format(i < raw.length ? raw[i] : null);
            }
            formatted.add(texts);
        }
        rows = List.copyOf(formatted);
        rawRows = List.copyOf(saved);
        rebuildEditModel();
        refreshResultTable();
    }

    private static boolean isSingleSelect(String sql) {
        List<String> statements = SqlSplitter.split(sql);
        if (statements.size() != 1) {
            return false;
        }
        String statement = statements.get(0).stripLeading().toLowerCase(Locale.ROOT);
        return statement.startsWith("select") || statement.startsWith("with");
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
        closeCellEditor();
        if (editModel != null && editModel.isDirty()) {
            owner.setStatus("Save or cancel the pending changes before sorting.", true);
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
        rebuildEditModel();
        refreshResultTable();
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
        openForeignKeyItem = contextAction(menu, "Open Referenced Rows...", this::openForeignKey);
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
        boolean pendingEdits = editModel != null && editModel.isDirty();
        boolean existingRow = false;
        boolean anyRow = false;
        for (int row : selection) {
            if (row >= 0 && row < dataRowCount()) {
                anyRow = true;
            }
            if (row >= 0 && row < rows.size()) {
                existingRow = true;
            }
        }
        copyFieldItem.setEnabled(anyRow && selectedColumn < columns.size());
        copyRowItem.setEnabled(anyRow);
        copyAllItem.setEnabled(dataRowCount() > 0);
        exportCsvItem.setEnabled(hasResults());
        deleteRowItem.setEnabled(existingRow && !pendingEdits);

        QueryResult.SourceTable source = currentResult == null ? null : currentResult.sourceTable();
        String column = source != null && selectedColumn >= 0
                && selectedColumn < source.columnNames().size()
                        ? source.columnNames().get(selectedColumn) : "";
        foreignKeyLookup = owner.foreignKeyLookup(source, column);
        openForeignKeyItem.setEnabled(source != null && !column.isBlank()
                && selectedRow >= 0 && selectedRow < rows.size() && selectedRow < rawRows.size()
                && selectedColumn >= 0 && selectedColumn < rawRows.get(selectedRow).length
                && rawRows.get(selectedRow)[selectedColumn] != null
                && !foreignKeyLookup.options().isEmpty());
    }

    /** Follows the selected foreign-key value into the referenced table in a new tab. */
    private void openForeignKey() {
        closeCellEditor();
        QueryResult.SourceTable source = currentResult == null ? null : currentResult.sourceTable();
        if (source == null || selectedRow < 0 || selectedRow >= rawRows.size()
                || selectedColumn < 0 || selectedColumn >= source.columnNames().size()) {
            return;
        }
        String column = source.columnNames().get(selectedColumn);
        owner.openForeignKeyRows(source, column, rawRows.get(selectedRow), foreignKeyLookup);
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
        List<Integer> chosen = new ArrayList<>();
        for (int row : selection) {
            if (row >= 0 && row < dataRowCount()) {
                chosen.add(row);
            }
        }
        if (chosen.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (int row : chosen) {
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
        owner.setStatus(chosen.size() + (chosen.size() == 1 ? " row" : " rows")
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
        int[] indices = new int[dataRowCount()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = i;
        }
        return indices;
    }

    private String cell(int row, int column) {
        if (editModel != null && row >= 0 && row < editModel.rowCount()) {
            return editModel.displayText(row, column);
        }
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
        if (editModel != null && editModel.isDirty()) {
            owner.showInfo("Save or cancel the pending changes before deleting rows.");
            return;
        }
        List<Integer> chosen = new ArrayList<>();
        for (int row : selection) {
            if (row >= 0 && row < rows.size()) {
                chosen.add(row);
            }
        }
        if (chosen.isEmpty()) {
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
        String question = "Delete " + chosen.size()
                + (chosen.size() == 1 ? " row" : " rows") + " from " + table + "?";
        if (!owner.confirm(question, "This cannot be undone.")) {
            return;
        }

        List<Object[]> values = new ArrayList<>();
        for (int index : chosen) {
            if (index >= 0 && index < rawRows.size()) {
                values.add(rawRows.get(index));
            }
        }
        try {
            int deleted = owner.database().deleteRows(source, values);
            Set<Integer> removed = new HashSet<>(chosen);
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
            resultTable.deselectAll();
            rebuildEditModel();
            refreshResultTable();
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
            rebuildEditModel();
            refreshResultTable();
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
        int count = dataRowCount();
        String info = count + (count == 1 ? " row" : " rows");
        if (editModel != null && editModel.newRowCount() > 0) {
            info += " (" + editModel.newRowCount() + " new)";
        }
        if (truncated) {
            info += " (first " + DatabaseService.MAX_ROWS + " shown)";
        }
        resultInfo.setText(info + "   •   " + elapsedMillis + " ms");
    }

    private void clearResultTable() {
        cancelCellEdit();
        for (TableColumn column : resultTable.getColumns()) {
            column.dispose();
        }
        resultTable.removeAll();
        resultTable.setItemCount(0);
        columns = List.of();
        rows = List.of();
        rawRows = List.of();
        currentResult = null;
        editModel = null;
        truncated = false;
        elapsedMillis = 0;
        selectedRow = -1;
        selectedColumn = 0;
        foreignKeyLookup = new ForeignKeyLookup(List.of(), false, false);
        sortColumn = -1;
        sortAscending = true;
        resultInfo.setText("No results");
        owner.updateConnectionUi();
        updateEditControls();
    }
}
