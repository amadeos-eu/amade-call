package eu.amadeos.apps.call.ui;

import eu.amadeos.commons.i18n.Mnemonics;
import eu.amadeos.commons.swing.ToolIcon;

import javax.swing.AbstractButton;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/** The icons of the call buttons, drawn like the toolbar icons of the other amadeOS programs. */
public enum CallGlyph implements ToolIcon.Glyph {
    MICROPHONE, MICROPHONE_OFF, CAMERA, CAMERA_OFF, SCREEN, CHAT, VERIFY, AUDIO_CALL, HANG_UP, SEND, ATTACH;

    @Override
    public void paint(ToolIcon.Pen pen) {
        switch (this) {
            case MICROPHONE -> microphone(pen);
            case MICROPHONE_OFF -> {
                microphone(pen);
                crossOut(pen);
            }
            case CAMERA -> camera(pen);
            case CAMERA_OFF -> {
                camera(pen);
                crossOut(pen);
            }
            case SCREEN -> {
                pen.fillAndDraw(new Rectangle2D.Double(1.5, 2.5, 13, 9), pen.paper());
                pen.line(8, 11.5, 8, 13.5);
                pen.line(5, 13.5, 11, 13.5);
                pen.bold(1.5f);
                pen.line(5, 9, 9.5, 5.5);
                pen.thin();
                pen.triangle(11.5, 4, 7.5, 4.5, 10.5, 7.5);
            }
            case CHAT -> {
                Area bubble = new Area(new RoundRectangle2D.Double(1.5, 2.5, 13, 8.5, 5, 5));
                Path2D tail = new Path2D.Double();
                tail.moveTo(4, 10);
                tail.lineTo(3.5, 14.5);
                tail.lineTo(8, 10);
                tail.closePath();
                bubble.add(new Area(tail));
                pen.fillAndDraw(bubble, pen.paper());
                for (double x : new double[] {5, 8, 11}) pen.fill(new Ellipse2D.Double(x - 1, 5.75, 2, 2), pen.ink());
            }
            case VERIFY -> {
                Path2D shield = new Path2D.Double();
                shield.moveTo(8, 1.5);
                shield.lineTo(13.5, 3.5);
                shield.lineTo(13.5, 8);
                shield.curveTo(13.5, 11.5, 11, 13.5, 8, 14.5);
                shield.curveTo(5, 13.5, 2.5, 11.5, 2.5, 8);
                shield.lineTo(2.5, 3.5);
                shield.closePath();
                pen.fillAndDraw(shield, pen.light());
                pen.bold(1.8f);
                Path2D check = new Path2D.Double();
                check.moveTo(5.5, 8);
                check.lineTo(7.5, 10);
                check.lineTo(10.75, 5.5);
                pen.draw(check);
                pen.thin();
            }
            case AUDIO_CALL -> handset(pen);
            case HANG_UP -> {
                AffineTransform before = pen.g().getTransform();
                pen.g().rotate(Math.toRadians(135), 8, 8);
                pen.g().translate(0, 1);
                handset(pen);
                pen.g().setTransform(before);
            }
            case ATTACH -> {
                // A paper clip, standing at a slant.
                AffineTransform before = pen.g().getTransform();
                pen.g().rotate(Math.toRadians(40), 8, 8);
                pen.bold(1.4f);
                Path2D clip = new Path2D.Double();
                clip.moveTo(9.5, 5);
                clip.lineTo(9.5, 12.5);
                clip.curveTo(9.5, 15, 6.5, 15, 6.5, 12.5);
                clip.lineTo(6.5, 3.5);
                clip.curveTo(6.5, 0.5, 11.5, 0.5, 11.5, 3.5);
                clip.lineTo(11.5, 12.5);
                clip.curveTo(11.5, 16.5, 4.5, 16.5, 4.5, 12.5);
                clip.lineTo(4.5, 5.5);
                pen.draw(clip);
                pen.thin();
                pen.g().setTransform(before);
            }
            case SEND -> {
                Path2D plane = new Path2D.Double();
                plane.moveTo(1.5, 7);
                plane.lineTo(14.5, 1.5);
                plane.lineTo(10, 14.5);
                plane.lineTo(7.5, 9);
                plane.closePath();
                pen.fillAndDraw(plane, pen.light());
                pen.line(7.5, 9, 14.5, 1.5);
            }
        }
    }

    private static void microphone(ToolIcon.Pen pen) {
        pen.fillAndDraw(new RoundRectangle2D.Double(5.5, 1.5, 5, 8.5, 5, 5), pen.light());
        pen.draw(new Arc2D.Double(3, 3, 10, 9.5, 180, 180, Arc2D.OPEN));
        pen.line(8, 12.5, 8, 14.5);
        pen.line(5, 14.5, 11, 14.5);
    }

    private static void camera(ToolIcon.Pen pen) {
        pen.fillAndDraw(new RoundRectangle2D.Double(1.5, 4.5, 9, 7, 2, 2), pen.light());
        Path2D lens = new Path2D.Double();
        lens.moveTo(10.5, 7);
        lens.lineTo(14.5, 4.5);
        lens.lineTo(14.5, 11.5);
        lens.lineTo(10.5, 9);
        lens.closePath();
        pen.fillAndDraw(lens, pen.light());
    }

    /** A slash over the icon: switched off. */
    private static void crossOut(ToolIcon.Pen pen) {
        pen.bold(3.5f);
        pen.g().setColor(pen.paper());
        pen.g().draw(new java.awt.geom.Line2D.Double(2, 2, 14, 14));
        pen.bold(1.6f);
        pen.line(2, 2, 14, 14);
        pen.thin();
    }

    /** An old telephone receiver, held at an angle. */
    private static void handset(ToolIcon.Pen pen) {
        Path2D receiver = new Path2D.Double();
        receiver.moveTo(2.5, 4);
        receiver.lineTo(5, 1.5);
        receiver.lineTo(7.5, 4.5);
        receiver.lineTo(6, 6.5);
        receiver.curveTo(7, 8.5, 7.5, 9, 9.5, 10);
        receiver.lineTo(11.5, 8.5);
        receiver.lineTo(14.5, 11);
        receiver.lineTo(12, 13.5);
        receiver.curveTo(7, 13.5, 2.5, 9, 2.5, 4);
        receiver.closePath();
        pen.fillAndDraw(receiver, pen.ink());
    }

    /**
     * Makes a button show only this icon: the text (with its mnemonic, which keeps working with Alt) goes
     * into the tooltip, above the longer hint if there is one, and stays the button's accessible name.
     */
    public <B extends AbstractButton> B on(B button, String marked, String hint) {
        Mnemonics.apply(button, marked);
        String label = Mnemonics.strip(marked);
        button.setText(null);
        button.setIcon(new ToolIcon(this));
        button.setToolTipText(tip(label, hint));
        button.getAccessibleContext().setAccessibleName(label);
        return button;
    }

    public static String tip(String label, String hint) {
        return hint == null || hint.isBlank() ? label
                : "<html><b>" + html(label) + "</b><br>" + html(hint) + "</html>";
    }

    private static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
