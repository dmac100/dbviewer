package dbviewer;

import java.util.ArrayList;
import java.util.List;

/** Splits a SQL script on semicolons, ignoring delimiters inside quotes and comments. */
public final class SqlSplitter {

    /** A statement together with its range in the original script. */
    public record Statement(String sql, int start, int end) {
    }

    private enum State {
        NORMAL, SINGLE_QUOTE, DOUBLE_QUOTE, BACKTICK, LINE_COMMENT, BLOCK_COMMENT
    }

    private SqlSplitter() {
    }

    public static List<String> split(String script) {
        return splitWithPositions(script).stream().map(Statement::sql).toList();
    }

    /** Like {@link #split(String)}, but keeps each statement's position in the script. */
    public static List<Statement> splitWithPositions(String script) {
        if (script == null || script.isBlank()) {
            return List.of();
        }

        List<Statement> statements = new ArrayList<>();
        State state = State.NORMAL;
        int start = 0;

        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            char next = i + 1 < script.length() ? script.charAt(i + 1) : '\0';

            switch (state) {
                case NORMAL -> {
                    if (c == '-' && next == '-') {
                        state = State.LINE_COMMENT;
                        i++;
                    } else if (c == '/' && next == '*') {
                        state = State.BLOCK_COMMENT;
                        i++;
                    } else if (c == '\'') {
                        state = State.SINGLE_QUOTE;
                    } else if (c == '"') {
                        state = State.DOUBLE_QUOTE;
                    } else if (c == '`') {
                        state = State.BACKTICK;
                    } else if (c == ';') {
                        addStatement(statements, script.substring(start, i), start, i);
                        start = i + 1;
                    }
                }
                case SINGLE_QUOTE -> {
                    if (c == '\\') {
                        // MySQL-style escaped character inside a string literal.
                        i++;
                    } else if (c == '\'' && next == '\'') {
                        i++; // SQL-style doubled quote.
                    } else if (c == '\'') {
                        state = State.NORMAL;
                    }
                }
                case DOUBLE_QUOTE -> {
                    if (c == '"' && next == '"') {
                        i++;
                    } else if (c == '"') {
                        state = State.NORMAL;
                    }
                }
                case BACKTICK -> {
                    if (c == '`' && next == '`') {
                        i++;
                    } else if (c == '`') {
                        state = State.NORMAL;
                    }
                }
                case LINE_COMMENT -> {
                    if (c == '\n' || c == '\r') {
                        state = State.NORMAL;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (c == '*' && next == '/') {
                        state = State.NORMAL;
                        i++;
                    }
                }
            }
        }

        addStatement(statements, script.substring(start), start, script.length());
        return List.copyOf(statements);
    }

    /**
     * Returns the statement containing {@code caret}, or the closest preceding statement when
     * the caret sits in whitespace between statements. Returns {@code null} for an empty script.
     */
    public static Statement statementAt(List<Statement> statements, int caret) {
        if (statements == null || statements.isEmpty()) {
            return null;
        }
        for (Statement statement : statements) {
            if (caret >= statement.start() && caret <= statement.end()) {
                return statement;
            }
        }
        Statement previous = null;
        for (Statement statement : statements) {
            if (statement.end() > caret) {
                break;
            }
            previous = statement;
        }
        return previous == null ? statements.get(0) : previous;
    }

    private static void addStatement(List<Statement> out, String raw, int start, int end) {
        String sql = raw.trim();
        if (!sql.isEmpty() && !isEffectivelyBlank(sql)) {
            out.add(new Statement(sql, start, end));
        }
    }

    /** True when the text contains nothing but whitespace and comments. */
    private static boolean isEffectivelyBlank(String sql) {
        StringBuilder code = new StringBuilder(sql.length());
        State state = State.NORMAL;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            switch (state) {
                case NORMAL -> {
                    if (c == '-' && next == '-') {
                        state = State.LINE_COMMENT;
                        i++;
                    } else if (c == '/' && next == '*') {
                        state = State.BLOCK_COMMENT;
                        i++;
                    } else {
                        code.append(c);
                    }
                }
                case LINE_COMMENT -> {
                    if (c == '\n' || c == '\r') {
                        state = State.NORMAL;
                        code.append(c);
                    }
                }
                case BLOCK_COMMENT -> {
                    if (c == '*' && next == '/') {
                        state = State.NORMAL;
                        i++;
                    }
                }
                default -> code.append(c);
            }
        }

        return code.toString().isBlank();
    }
}
