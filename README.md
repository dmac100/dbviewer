# dbviewer

A small desktop database viewer built with **Java 21**, **SWT**, and **JDBC**.

## Features

- Menu bar: connect/disconnect, new/close query tab, run/stop queries, export results as CSV,
  refresh tables, exit, about.
- **Left pane** — lists tables and views in a tree grouped by catalog or schema (whichever the
  database exposes), with a filter box. Groups start collapsed; double-click a group or table to
  expand or collapse it. Expanding a table loads its columns as child items. Databases/schemas,
  tables/views and columns are distinguished by icons; data types and primary-key details are
  available in tooltips. Right-click a database/schema group and choose **Use as default** to render
  it bold and select it for unqualified query table names. The **Select All** context menu item
  builds and immediately runs a `SELECT *` query for the chosen table(s).
- **Main pane** — multiple query tabs (Ctrl+T to add, Ctrl+W to close), each with a monospace
  SQL editor featuring syntax highlighting and indentation-preserving Enter/Tab.
  Drag a tab by its tab strip: an insertion line shows where it will land, while dropping on
  a folder body near an edge splits the main panel horizontally or vertically. Tabs can also
  be dragged between panes; the editor text, cursor position and results follow the tab.
  **Ctrl+Enter** runs the statement under the cursor; **Ctrl+Shift+Enter** (or F5 / *Run Query*)
  runs every statement in the editor; **Ctrl+A** selects the whole query. The SQL colours adapt to
  the system's dark theme with bright keywords, strings and numbers.
- **Bottom pane** — a virtualized results table in each tab, with column headers that sort the
  rows ascending/descending using natural ordering (`item2` before `item10`), and a context menu
  to copy a field, copy rows (tab-separated), paste rows, follow a foreign-key value to the matching
  rows in a new query tab, or delete rows from the database after a confirmation.
  **Ctrl+C** copies the selected rows as CSV; *Query → Export Results as CSV...* saves all rows
  with a header line. Shows row count and execution time; update statements show the affected-row count.
- **Editable results** — when a query returns a single table and includes its primary key column(s),
  click any cell to edit it (Enter/F2 also works). Type in the **`(new row)`** line under the results
  to add rows; new cells start as `[NULL]`. SQL NULL is displayed as `[NULL]` in grey and an empty
  string as `''`, so they can be told apart. Clearing a cell stores an empty string; choose *Set Cell to NULL*
  in the results context menu to store NULL instead. **Ctrl+V** (or *Paste* in the context menu)
  pastes tab-separated or CSV text as whole rows starting from the first column of the selected row.
  Each pasted row must have one value per column, and every value must be valid for its column type,
  otherwise a warning explains the problem and nothing is pasted. Changed cells are highlighted;
  **Cancel Changes** discards them and **Save Changes** shows a summary of the updates and inserts
  before writing them to the database in one transaction. Results from joins, views without keys and
  queries that omit the primary key stay read-only.
- JDBC drivers bundled for **SQLite**, **MySQL**, and **PostgreSQL**. Any other JDBC driver
  can be used by choosing *Generic JDBC* and adding its jar to the classpath/dependencies.
- Optional **SSH tunnel**: enable it in the connection dialog to reach a remote database through
  an SSH jump host, using either a password or a private key. Host keys are verified against
  `known_hosts` (default `~/.ssh/known_hosts`).
- Multiple semicolon-separated statements can be run at once (quotes and comments are
  handled correctly); the last result set is displayed.

## Build and run

```bash
cd ~/multipass/dbviewer
mvn clean package
java -jar target/dbviewer.jar
```

Or run directly from Maven during development:

```bash
mvn exec:java
```

The Maven profiles pick the correct SWT native fragment for Linux x86_64, Windows x86_64,
macOS x86_64, and macOS aarch64. If you are on another architecture, change the
`swt-*` profile in `pom.xml` to the matching `org.eclipse.swt.*` artifact.

## Using it

1. Choose **File → Connect…** (Ctrl+O).
2. Pick a database type, enter/adjust the JDBC URL, user and password, then click **Connect**.
   - SQLite: click **File…** to pick a `.db` file, giving a URL such as
     `jdbc:sqlite:/path/to/mydb.db`.
   - MySQL: e.g. `jdbc:mysql://localhost:3306/mydb?serverTimezone=UTC`.
   - PostgreSQL: e.g. `jdbc:postgresql://localhost:5432/mydb`.
   - **Test Connection** checks the URL and credentials before you connect.
   - To connect through SSH, tick **Connect through SSH tunnel** and fill in the SSH host, port,
     user, and either a password or a private key file. The JDBC URL should still point at the
     remote database host; it is transparently rewritten to a local forwarded port. Add the SSH
     host key to `~/.ssh/known_hosts` first (for example, `ssh-keyscan host >> ~/.ssh/known_hosts`).
3. Tables appear in the left pane, grouped by schema or catalog with every group initially
   collapsed. Double-click a group or table to expand/collapse it; expanding a table shows its
   columns. Right-click a group and choose **Use as default** to make it bold and run unqualified
   queries against it. Select one or more tables and use **Select All** from the context menu to
   run queries showing all their rows.
4. Type or edit SQL in the active tab: **Ctrl+Enter** runs the statement at the cursor,
   **Ctrl+Shift+Enter** runs the whole script. Results appear below the editor in that tab.
   Use **Ctrl+T** for another query tab, drag tabs by their tab strips to reorder or split
   the main panel, and right-click the results for copy/paste/export/delete actions. When the result
   is editable, click a cell to change it, paste a block with **Ctrl+V**, or use the `(new row)` line
   to add one, then use **Cancel Changes** or **Save Changes** in the results toolbar.

## Notes

- Results are capped at 10,000 rows to keep the UI responsive; the label says when a
  result was truncated. Cell values with line breaks are escaped, long values are shortened,
  and binary values are shown as grey `[BINARY]`. CSV export and Ctrl+C copying use the underlying
  values instead: full text and line breaks are preserved, and binary data is Base64-encoded.
- **Delete Row(s)** asks for confirmation, then issues a `DELETE` for the selected rows. Tables
  with a primary key are matched by that key; otherwise all columns in the result are used. If a
  query omits the primary key column, deletion is refused.
- Editable cells use the values exactly as typed. SQL NULL is shown as `[NULL]` and can be set with
  the context menu; a blank edit stores an empty string, displayed as `''`. New rows show `[NULL]` in
  unedited cells, which are omitted from the INSERT so database defaults (for example an
  auto-incrementing key) still apply. After saving, a single-select query is re-run so generated keys
  appear. Numeric, boolean, date and time values are converted using the column's JDBC type.
- Pasting accepts tab-separated text (for example from a spreadsheet) or CSV, including quoted
  fields with commas and line breaks. The block is placed from the first column of the selected row
  downward, and rows past the end of the result become new rows. A block whose width does not match
  the table's columns, or a value that cannot be converted for its column, is rejected with a warning
  before anything changes.
- Dates and times are forgiving about input format: `2026-01-02`, `1/2/2026` and month names are
  accepted for dates; timestamps accept `T` or a space before the time, 24-hour or AM/PM clocks,
  optional seconds/fractions, and numeric offsets. Month names, AM/PM markers and the order of
  numeric dates follow the current locale (for example, `1/2/2026` is January 2 in the US and
  February 1 in the UK); digits stay ASCII. Local values without an offset are stored in UTC for
  columns with a time zone.
- **Open Referenced Rows** appears when a result cell can be followed to another table's primary
  key. Declared foreign keys are used when the database exposes them; otherwise compatible tables
  are offered as a list, with a *Remember this table for the session* checkbox. The generated
  `SELECT` runs in a new tab. Session choices are cleared when the connection changes.
- Status bar messages are shortened to fit the window; hover over them for the full text.
- Queries run on a background thread with a wait cursor, so the window stays responsive.
  Click **Stop Query** (toolbar or *Query* menu) to cancel the running statement; only one
  query runs at a time. Cancellation depends on the JDBC driver supporting `Statement.cancel()`.
- The Eclipse project files (`.project`, `.classpath`) are configured for m2e, so the
  project can also be imported into Eclipse as a Maven project.
