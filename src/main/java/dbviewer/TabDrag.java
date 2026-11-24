package dbviewer;

import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;

/**
 * Mouse-based tab dragging for {@link CTabFolder}s, modelled on javapad's TabDrag.
 * A press on a tab becomes a drag after the pointer moves far enough; a line or frame
 * shows where the tab will land. Dropping on a tab strip inserts the tab there; dropping
 * on the body near an edge splits that folder on that side.
 */
final class TabDrag {

    static final int NONE = 0;
    static final int INSERT = 1;
    static final int LEFT = 2;
    static final int RIGHT = 3;
    static final int TOP = 4;
    static final int BOTTOM = 5;

    /** Pixels the pointer must move before a press becomes a drag. */
    private static final int THRESHOLD = 8;
    /** Fraction of a folder's body, from each edge, that triggers a split. */
    private static final double EDGE = 0.3;

    interface Host {
        /** All folders a tab can be dropped on, including the one it came from. */
        List<CTabFolder> folders();

        /** Whether dropping on a folder's body may split it. */
        boolean splits();

        /**
         * Moves the tab. {@code zone} is one of the constants above; {@code index} is the
         * position in the target folder, numbered after the tab has left its old folder.
         */
        void drop(CTabItem item, CTabFolder target, int zone, int index);
    }

    private final Host host;
    private final Display display;
    private final DropOutline outline;

    private CTabItem item;
    private Point start;
    private boolean dragging;
    private CTabFolder target;
    private int zone;
    private int index;

    TabDrag(Shell shell, Host host) {
        this.host = host;
        this.display = shell.getDisplay();
        this.outline = new DropOutline(shell);
    }

    void attach(CTabFolder folder) {
        folder.addListener(SWT.MouseDown, e -> press(folder, e));
        folder.addListener(SWT.MouseMove, e -> move(e));
        folder.addListener(SWT.MouseUp, e -> release());
    }

    void press(CTabFolder folder, Event e) {
        item = e.button == 1 ? folder.getItem(new Point(e.x, e.y)) : null;
        start = new Point(e.x, e.y);
        dragging = false;
    }

    void move(Event e) {
        if (item == null || item.isDisposed() || (e.stateMask & SWT.BUTTON1) == 0) {
            return;
        }
        if (!dragging && Math.abs(e.x - start.x) < THRESHOLD
                && Math.abs(e.y - start.y) < THRESHOLD) {
            return;
        }
        dragging = true;
        locate(display.getCursorLocation());
    }

    void release() {
        CTabItem dropped = item;
        CTabFolder to = target;
        int where = zone;
        int at = index;
        boolean wasDragging = dragging;
        reset();
        if (wasDragging && dropped != null && !dropped.isDisposed()
                && to != null && !to.isDisposed() && where != NONE) {
            host.drop(dropped, to, where, at);
        }
    }

    private void reset() {
        item = null;
        dragging = false;
        target = null;
        zone = NONE;
        outline.hide();
    }

    /** Finds the folder under {@code cursor} (display coordinates) and shows the drop target. */
    void locate(Point cursor) {
        target = null;
        zone = NONE;
        for (CTabFolder folder : host.folders()) {
            if (folder.isDisposed() || !folder.isVisible()) {
                continue;
            }
            Rectangle bounds = display.map(folder.getParent(), null, folder.getBounds());
            if (bounds.contains(cursor)) {
                target = folder;
                place(folder, bounds, cursor);
                break;
            }
        }
        if (zone == NONE) {
            outline.hide();
        }
    }

    private void place(CTabFolder folder, Rectangle bounds, Point cursor) {
        Point local = new Point(cursor.x - bounds.x, cursor.y - bounds.y);
        int strip = folder.getTabHeight() + 4;
        CTabFolder from = item.getParent();
        int own = from == folder ? folder.indexOf(item) : -1;

        if (local.y < strip) {
            CTabItem[] items = folder.getItems();
            int at = items.length;
            for (int i = 0; i < items.length; i++) {
                Rectangle itemBounds = items[i].getBounds();
                if (local.x < itemBounds.x + itemBounds.width / 2) {
                    at = i;
                    break;
                }
            }
            // Dropping a tab just in front of or behind itself changes nothing.
            if (own >= 0 && (at == own || at == own + 1)) {
                return;
            }
            zone = INSERT;
            index = own >= 0 && at > own ? at - 1 : at;
            int x = at < items.length
                    ? items[at].getBounds().x
                    : items.length == 0 ? 0
                            : items[items.length - 1].getBounds().x
                                    + items[items.length - 1].getBounds().width;
            Point top = display.map(folder, null, x - 1, 0);
            outline.show(new Rectangle(top.x, top.y, DropOutline.THICKNESS, strip - 2), false);
            return;
        }

        // Splitting a folder that contains only the dragged tab would just move it.
        if (!host.splits() || (own >= 0 && folder.getItemCount() == 1)) {
            return;
        }

        double fx = (double) local.x / Math.max(1, bounds.width);
        double fy = (double) (local.y - strip) / Math.max(1, bounds.height - strip);
        Rectangle body = new Rectangle(bounds.x, bounds.y + strip,
                bounds.width, bounds.height - strip);
        zone = INSERT;
        index = folder.getItemCount() - (own >= 0 ? 1 : 0);
        Rectangle shown = body;
        double best = EDGE;
        if (fx < best) {
            best = fx;
            zone = LEFT;
            shown = new Rectangle(body.x, body.y, body.width / 2, body.height);
        }
        if (1 - fx < best) {
            best = 1 - fx;
            zone = RIGHT;
            shown = new Rectangle(body.x + body.width / 2, body.y,
                    body.width - body.width / 2, body.height);
        }
        if (fy < best) {
            best = fy;
            zone = TOP;
            shown = new Rectangle(body.x, body.y, body.width, body.height / 2);
        }
        if (1 - fy < best) {
            zone = BOTTOM;
            shown = new Rectangle(body.x, body.y + body.height / 2,
                    body.width, body.height - body.height / 2);
        }
        if (zone == INSERT && own >= 0) {
            // The middle of the tab's own folder: nothing to do.
            zone = NONE;
            return;
        }
        outline.show(shown, true);
    }
}
