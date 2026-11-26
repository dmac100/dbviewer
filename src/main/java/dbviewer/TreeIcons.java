package dbviewer;

import java.util.Arrays;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.widgets.Display;

/** Small, theme-aware icons drawn for the schema tree. */
final class TreeIcons {

    private final Display display;
    private final Image database;
    private final Image tableIcon;
    private final Image view;
    private final Image columnIcon;
    private final Image primaryKey;

    TreeIcons(Display display) {
        this.display = display;
        database = databaseIcon();
        tableIcon = gridIcon(false);
        view = gridIcon(true);
        columnIcon = columnIcon();
        primaryKey = keyIcon();
    }

    Image forGroup() {
        return database;
    }

    Image forTable(TableInfo table) {
        return "VIEW".equalsIgnoreCase(table.type()) ? view : tableIcon;
    }

    Image forColumn(ColumnInfo column) {
        return column.primaryKey() ? primaryKey : columnIcon;
    }

    void dispose() {
        database.dispose();
        tableIcon.dispose();
        view.dispose();
        columnIcon.dispose();
        primaryKey.dispose();
    }

    private Image databaseIcon() {
        return paint(gc -> {
            gc.fillRectangle(2, 4, 11, 8);
            gc.fillOval(2, 6, 11, 4);
            gc.fillOval(2, 10, 11, 4);
            gc.drawLine(2, 4, 2, 12);
            gc.drawLine(13, 4, 13, 12);
            // Positive SWT arc angles run counter-clockwise, so 180..360 is the front
            // (lower) half. The rear halves stay hidden behind the cylinder body.
            gc.drawArc(2, 6, 11, 4, 180, 180);
            gc.drawArc(2, 10, 11, 4, 180, 180);
            gc.fillOval(2, 2, 11, 4);
            gc.drawOval(2, 2, 11, 4);
        });
    }

    /** A small grid with a grey header row; views additionally get a magnifying glass. */
    private Image gridIcon(boolean isView) {
        return paint(gc -> {
            if (isView) {
                gc.fillRectangle(2, 2, 10, 9);
                shadeHeader(gc, 2, 2, 10, 2);
                gc.drawRectangle(1, 1, 11, 10);
                gc.drawLine(1, 4, 12, 4);
                gc.drawLine(5, 1, 5, 11);
                gc.drawLine(9, 1, 9, 11);
                gc.fillOval(9, 9, 5, 5);
                gc.drawOval(9, 9, 5, 5);
                gc.drawLine(13, 13, 15, 15);
            } else {
                gc.fillRectangle(3, 3, 11, 11);
                shadeHeader(gc, 3, 3, 11, 2);
                gc.drawRectangle(2, 2, 12, 12);
                gc.drawLine(2, 5, 14, 5);
                gc.drawLine(2, 9, 14, 9);
                gc.drawLine(6, 2, 6, 14);
                gc.drawLine(10, 2, 10, 14);
            }
        });
    }

    private void shadeHeader(GC gc, int x, int y, int width, int height) {
        gc.setBackground(display.getSystemColor(SWT.COLOR_GRAY));
        gc.fillRectangle(x, y, width, height);
        gc.setBackground(display.getSystemColor(SWT.COLOR_WHITE));
    }

    private Image columnIcon() {
        return paint(gc -> {
            gc.fillOval(5, 5, 6, 6);
            gc.drawOval(5, 5, 6, 6);
        });
    }

    /** A ring marks a primary-key column while keeping the same small-circle shape. */
    private Image keyIcon() {
        return paint(gc -> {
            gc.fillOval(4, 4, 8, 8);
            gc.drawOval(4, 4, 8, 8);
            gc.drawOval(6, 6, 4, 4);
        });
    }

    private Image paint(Consumer<GC> painter) {
        ImageData blank = new ImageData(16, 16, 32,
                new PaletteData(0xFF0000, 0x00FF00, 0x0000FF));
        blank.alphaData = new byte[16 * 16]; // fully transparent until painted
        Arrays.fill(blank.alphaData, (byte) 0);

        Image image = new Image(display, blank);
        GC gc = new GC(image);
        try {
            gc.setAntialias(SWT.ON);
            gc.setBackground(display.getSystemColor(SWT.COLOR_WHITE));
            gc.setForeground(display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
            painter.accept(gc);
        } finally {
            gc.dispose();
        }
        return image;
    }
}
