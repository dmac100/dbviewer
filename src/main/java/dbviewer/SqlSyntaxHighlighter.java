package dbviewer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.LineStyleEvent;
import org.eclipse.swt.custom.LineStyleListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.VerifyKeyListener;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Display;

/** SQL colouring and indentation-aware Enter/Tab handling for a {@link StyledText} editor. */
public final class SqlSyntaxHighlighter implements LineStyleListener, VerifyKeyListener {

    /** Indentation inserted by Tab and carried over to the next line by Enter. */
    public static final String INDENT = "    ";

    private static final Set<String> KEYWORDS = Set.of(
            "ADD", "ALL", "ALTER", "AND", "ANY", "AS", "ASC", "BETWEEN", "BY", "CASE", "CAST",
            "CHECK", "COLUMN", "COMMIT", "CONSTRAINT", "CREATE", "CROSS", "CURRENT_DATE",
            "CURRENT_TIME", "CURRENT_TIMESTAMP", "DATABASE", "DEFAULT", "DELETE", "DESC",
            "DISTINCT", "DROP", "ELSE", "END", "EXISTS", "FALSE", "FOREIGN", "FROM", "FULL",
            "GROUP", "HAVING", "IN", "INDEX", "INNER", "INSERT", "INTO", "IS", "JOIN", "KEY",
            "LEFT", "LIKE", "LIMIT", "NOT", "NULL", "OFFSET", "ON", "OR", "ORDER", "OUTER",
            "PRIMARY", "REFERENCES", "RETURNING", "RIGHT", "ROLLBACK", "SELECT", "SET", "TABLE",
            "THEN", "TRUE", "UNION", "UNIQUE", "UPDATE", "VALUES", "VIEW", "WHEN", "WHERE", "WITH");

    private enum LexState { NORMAL, SINGLE_QUOTE, DOUBLE_QUOTE, BACKTICK, BLOCK_COMMENT }

    private final StyledText editor;
    private final Color keywordColor;
    private final Color stringColor;
    private final Color identifierColor;
    private final Color numberColor;
    private final Color commentColor;
    private final Color editorBackground;
    private final Color editorForeground;

    /** End-of-line states, so multi-line strings and comments stay coloured correctly. */
    private final List<LexState> endStates = new ArrayList<>();

    public SqlSyntaxHighlighter(StyledText editor) {
        this(editor, isDarkTheme(editor));
    }

    /** Package-visible so the dark palette can be exercised without a dark system theme. */
    SqlSyntaxHighlighter(StyledText editor, boolean dark) {
        this.editor = editor;
        Display display = editor.getDisplay();
        if (dark) {
            keywordColor = color(display, 0x82, 0xAA, 0xFF);
            stringColor = color(display, 0x98, 0xC3, 0x79);
            identifierColor = color(display, 0x56, 0xB6, 0xC2);
            numberColor = color(display, 0xD1, 0x9A, 0x66);
            commentColor = color(display, 0x9A, 0xA0, 0xA6);
            editorBackground = color(display, 0x1E, 0x1E, 0x1E);
            editorForeground = color(display, 0xD4, 0xD4, 0xD4);
            editor.setBackground(editorBackground);
            editor.setForeground(editorForeground);
        } else {
            keywordColor = color(display, 0, 0, 170);
            stringColor = color(display, 0, 115, 0);
            identifierColor = color(display, 0, 110, 130);
            numberColor = color(display, 150, 0, 150);
            commentColor = color(display, 120, 120, 120);
            editorBackground = null;
            editorForeground = null;
        }

        editor.addLineStyleListener(this);
        editor.addVerifyKeyListener(this);
        editor.addModifyListener(e -> {
            endStates.clear();
            editor.redraw();
        });
        editor.setTabs(INDENT.length());
    }

    /**
     * Uses the platform's dark-theme setting, falling back to sampling the editor background
     * when SWT has not initialised the theme flag.
     */
    private static boolean isDarkTheme(StyledText editor) {
        if (Display.isSystemDarkTheme()) {
            return true;
        }
        Color background = editor.getBackground();
        if (background == null) {
            return false;
        }
        int brightness = (background.getRed() + background.getGreen() + background.getBlue()) / 3;
        return brightness < 128;
    }

    private static Color color(Display display, int red, int green, int blue) {
        return new Color(display, red, green, blue);
    }

    /** Releases the colours allocated by this highlighter. */
    public void dispose() {
        keywordColor.dispose();
        stringColor.dispose();
        identifierColor.dispose();
        numberColor.dispose();
        commentColor.dispose();
        if (editorBackground != null) {
            editorBackground.dispose();
        }
        if (editorForeground != null) {
            editorForeground.dispose();
        }
    }

    @Override
    public void lineGetStyle(LineStyleEvent event) {
        int lineIndex = Math.max(0,
                Math.min(editor.getLineAtOffset(event.lineOffset), editor.getLineCount() - 1));
        List<StyleRange> styles = new ArrayList<>();
        LexState endState = scanLine(event.lineText, event.lineOffset, stateAt(lineIndex), styles);
        if (lineIndex < endStates.size()) {
            endStates.set(lineIndex, endState);
        } else {
            endStates.add(endState);
        }
        event.styles = styles.toArray(new StyleRange[0]);
    }

    @Override
    public void verifyKey(VerifyEvent event) {
        if (event.character == SWT.CR || event.character == SWT.LF) {
            if ((event.stateMask & (SWT.CONTROL | SWT.COMMAND)) != 0) {
                // Ctrl+Enter / Ctrl+Shift+Enter run a query; they must not insert a line break.
                event.doit = false;
                return;
            }
            int caret = editor.getCaretOffset();
            int lineIndex = editor.getLineAtOffset(caret);
            int lineStart = editor.getOffsetAtLine(lineIndex);
            String beforeCaret = editor.getTextRange(lineStart, Math.max(0, caret - lineStart));
            event.text = "\n" + leadingWhitespace(beforeCaret);
        } else if (event.keyCode == SWT.TAB && (event.stateMask & SWT.SHIFT) == 0) {
            // Keep indentation as spaces, independent of the platform's tab width.
            event.text = INDENT;
        }
    }

    private LexState stateAt(int lineIndex) {
        endStatesFor(lineIndex);
        return lineIndex == 0 ? LexState.NORMAL : endStates.get(lineIndex - 1);
    }

    /** Computes end states up to (but not including) the requested line. */
    private void endStatesFor(int lineIndex) {
        while (endStates.size() < lineIndex && endStates.size() < editor.getLineCount()) {
            int line = endStates.size();
            endStates.add(scanLine(editor.getLine(line), 0, stateAt(line), null));
        }
    }

    /**
     * Styles one line and returns the lexical state at its end. Pass {@code null} for
     * {@code styles} when only the resulting state is needed.
     */
    private LexState scanLine(String text, int offset, LexState initialState, List<StyleRange> styles) {
        LexState state = initialState;
        int i = 0;

        while (i < text.length()) {
            if (state != LexState.NORMAL) {
                int start = i;
                int result = continueToken(text, i, state);
                boolean closed = result >= 0;
                i = closed ? result : ~result;
                addStyle(styles, offset + start, i - start, colorFor(state), fontStyleFor(state));
                if (closed) {
                    state = LexState.NORMAL;
                }
                continue;
            }

            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';

            if ((c == '-' && next == '-') || c == '#') {
                addStyle(styles, offset + i, text.length() - i, commentColor, SWT.ITALIC);
                return LexState.NORMAL;
            }
            if (c == '/' && next == '*') {
                int start = i;
                int result = continueToken(text, i + 2, LexState.BLOCK_COMMENT);
                boolean closed = result >= 0;
                i = closed ? result : ~result;
                addStyle(styles, offset + start, i - start, commentColor, SWT.ITALIC);
                if (!closed) {
                    state = LexState.BLOCK_COMMENT;
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                LexState quoteState = switch (c) {
                    case '\'' -> LexState.SINGLE_QUOTE;
                    case '"' -> LexState.DOUBLE_QUOTE;
                    default -> LexState.BACKTICK;
                };
                int start = i;
                int result = continueToken(text, i + 1, quoteState);
                boolean closed = result >= 0;
                i = closed ? result : ~result;
                addStyle(styles, offset + start, i - start, colorFor(quoteState), SWT.NORMAL);
                if (!closed) {
                    state = quoteState;
                }
                continue;
            }
            if (isWordChar(c)) {
                int start = i;
                i++;
                while (i < text.length() && isWordChar(text.charAt(i))) {
                    i++;
                }
                String word = text.substring(start, i);
                String upper = word.toUpperCase(Locale.ROOT);
                if (KEYWORDS.contains(upper)) {
                    addStyle(styles, offset + start, word.length(), keywordColor, SWT.BOLD);
                } else if (looksNumeric(word)) {
                    addStyle(styles, offset + start, word.length(), numberColor, SWT.NORMAL);
                }
                continue;
            }
            i++;
        }
        return state;
    }

    /**
     * Consumes the rest of a token that is already open at {@code start}. Returns the index
     * after its closing delimiter, or the bitwise complement of the end index if it stays open.
     */
    private int continueToken(String text, int start, LexState state) {
        char quote = '\0';
        switch (state) {
            case SINGLE_QUOTE -> quote = '\'';
            case DOUBLE_QUOTE -> quote = '"';
            case BACKTICK -> quote = '`';
            default -> {
                // Block comments below.
            }
        }
        int i = Math.max(0, start);
        while (i < text.length()) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
            if (state == LexState.BLOCK_COMMENT) {
                if (c == '*' && next == '/') {
                    return i + 2;
                }
            } else if (state == LexState.SINGLE_QUOTE && c == '\\' && next != '\0') {
                i += 2;
                continue;
            } else if (c == quote && next == quote) {
                i += 2;
                continue;
            } else if (c == quote) {
                return i + 1;
            }
            i++;
        }
        return ~i;
    }

    private Color colorFor(LexState state) {
        return switch (state) {
            case SINGLE_QUOTE -> stringColor;
            case DOUBLE_QUOTE, BACKTICK -> identifierColor;
            case BLOCK_COMMENT -> commentColor;
            default -> null;
        };
    }

    private int fontStyleFor(LexState state) {
        return state == LexState.BLOCK_COMMENT ? SWT.ITALIC : SWT.NORMAL;
    }

    private static void addStyle(List<StyleRange> styles, int start, int length, Color color, int fontStyle) {
        if (styles == null || length <= 0 || color == null) {
            return;
        }
        StyleRange range = new StyleRange(start, length, color, null);
        range.fontStyle = fontStyle;
        styles.add(range);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '@';
    }

    private static boolean looksNumeric(String word) {
        if (word.isEmpty()) {
            return false;
        }
        char first = word.charAt(0);
        if (Character.isDigit(first)) {
            return true;
        }
        return first == '.' && word.length() > 1 && Character.isDigit(word.charAt(1));
    }

    private static String leadingWhitespace(String text) {
        int end = 0;
        while (end < text.length() && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) {
            end++;
        }
        return text.substring(0, end);
    }
}
