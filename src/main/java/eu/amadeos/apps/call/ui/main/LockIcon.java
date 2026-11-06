package eu.amadeos.apps.call.ui.main;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;

/** A small padlock: green for a verified person, grey for one whose safety code was not compared yet. */
public record LockIcon(Color colour) implements Icon {
    public static final Color VERIFIED = new Color(0x2e7d32);
    public static final Color VERIFIED_ON_DARK = new Color(0x81c784);
    public static final Color OPEN = new Color(0x8a8a8a);

    @Override
    public void paintIcon(Component c, Graphics g0, int x, int y) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(colour);
        g.setStroke(new BasicStroke(2));
        g.draw(new Arc2D.Double(x + 3, y, 8, 9, 0, 180, Arc2D.OPEN));
        g.drawLine(x + 3, y + 5, x + 3, y + 6);
        g.drawLine(x + 11, y + 5, x + 11, y + 6);
        g.fillRoundRect(x + 1, y + 6, 12, 8, 3, 3);
        g.dispose();
    }

    @Override
    public int getIconWidth() {
        return 14;
    }

    @Override
    public int getIconHeight() {
        return 14;
    }
}
