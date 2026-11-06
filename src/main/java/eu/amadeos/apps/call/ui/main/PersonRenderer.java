package eu.amadeos.apps.call.ui.main;

import eu.amadeos.apps.call.domain.Person;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.swing.AmadeUI;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;

/**
 * A row of the list: the person's avatar with a dot (green: in the network now, grey: not seen), the name and,
 * smaller, where they are. A JLabel, so that it also paints in screenshots made with {@code printAll}.
 */
final class PersonRenderer implements ListCellRenderer<Person> {
    static final Color NEARBY = new Color(0x43a047);
    static final Color AWAY = new Color(0x9e9e9e);

    private final Messages msg;
    private final JLabel label = new JLabel();

    PersonRenderer(Messages msg) {
        this.msg = msg;
        label.setOpaque(true);
        label.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        label.setIconTextGap(8);
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends Person> list, Person p, int index,
                                                  boolean selected, boolean focus) {
        String where = (p.nearby() ? msg.get("people.nearby") : msg.get("people.away"))
                + (p.verified() ? " · " + msg.get("people.verified") : "");
        String colour = selected ? "" : " color='#" + String.format("%06x", AmadeUI.theme().textInactive().getRGB() & 0xffffff) + "'";
        label.setText("<html><b>" + MainWindow.escape(p.name()) + "</b>"
                + "<br><span style='font-size:90%'" + colour + ">" + where + "</span></html>");
        label.setIcon(new WithDot(new Avatar(p.name(), Math.round(30 * AmadeUI.fontScale())), p.nearby() ? NEARBY : AWAY));
        label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
        label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
        label.setName("person." + p.key().hex().substring(0, 8));
        return label;
    }

    /** The avatar with a small presence dot at the lower right. */
    private record WithDot(Icon avatar, Color dot) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            avatar.paintIcon(c, g0, x, y);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int d = Math.max(8, avatar.getIconWidth() / 3);
            int dx = x + avatar.getIconWidth() - d + 1, dy = y + avatar.getIconHeight() - d + 1;
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(dx - 1.5, dy - 1.5, d + 3, d + 3));
            g.setColor(dot);
            g.fill(new Ellipse2D.Double(dx, dy, d, d));
            g.dispose();
        }

        @Override
        public int getIconWidth() {
            return avatar.getIconWidth();
        }

        @Override
        public int getIconHeight() {
            return avatar.getIconHeight();
        }
    }
}
