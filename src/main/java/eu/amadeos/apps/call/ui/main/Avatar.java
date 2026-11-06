package eu.amadeos.apps.call.ui.main;

import eu.amadeos.apps.call.domain.Person;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Graphics;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.util.Locale;

/**
 * The round picture of a person: their initials on a colour taken from their name, so that the same person has
 * the same colour on every computer.
 */
public record Avatar(String name, int size) implements Icon {
    private static final Color[] COLOURS = {
            new Color(0x5c7cab), new Color(0x7a9a4f), new Color(0xb5643c), new Color(0x8a5c9e),
            new Color(0x3f8a8a), new Color(0xa8823a), new Color(0x9e4f5c), new Color(0x4f6a9e)};

    public static Color colour(String name) {
        return COLOURS[Math.floorMod(name.strip().toLowerCase(Locale.ROOT).hashCode(), COLOURS.length)];
    }

    /** Paints the avatar centred at ({@code cx}, {@code cy}). */
    public static void paint(Graphics2D g, String name, int cx, int cy, int size) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(colour(name));
        g.fill(new Ellipse2D.Double(cx - size / 2.0, cy - size / 2.0, size, size));
        String initials = Person.initials(name);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(8, size * 2 / 5)));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(initials, cx - fm.stringWidth(initials) / 2, cy + (fm.getAscent() - fm.getDescent()) / 2);
    }

    @Override
    public void paintIcon(Component c, Graphics g0, int x, int y) {
        Graphics2D g = (Graphics2D) g0.create();
        paint(g, name, x + size / 2, y + size / 2, size);
        g.dispose();
    }

    @Override
    public int getIconWidth() {
        return size;
    }

    @Override
    public int getIconHeight() {
        return size;
    }
}
