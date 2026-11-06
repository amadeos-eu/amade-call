package eu.amadeos.apps.call.ui.call;

import eu.amadeos.apps.call.ui.main.Avatar;
import eu.amadeos.apps.call.ui.main.LockIcon;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * The picture of a call: the other side as large as fits, my own picture small and mirrored at the lower right,
 * and on top the name, the time, whether the call is verified, and what is happening ("Calling…"). When the
 * other side sends no picture (sound-only call, camera off), their avatar takes its place.
 */
final class VideoPanel extends JComponent {
    private static final Color BACKGROUND = new Color(0x1e1e1e);
    private static final Color PILL = new Color(0, 0, 0, 150);

    private final String name;
    private BufferedImage remote;
    private BufferedImage local;
    private boolean remoteCamera = true;
    private boolean remoteScreen;
    private boolean remoteMuted;
    private boolean showSelf = true;
    private boolean mirrorSelf = true;
    private boolean verified;
    private String phase = "";
    private String time = "";
    private String info = "";
    private String encrypted = "";
    private String mutedText = "";
    private String screenText = "";
    private String selfText = "";

    VideoPanel(String name) {
        this.name = name;
        setPreferredSize(new Dimension(800, 450));
        setOpaque(true);
    }

    void setTexts(String encrypted, String muted, String screen, String self) {
        this.encrypted = encrypted;
        this.mutedText = muted;
        this.screenText = screen;
        this.selfText = self;
    }

    void setRemote(BufferedImage frame) {
        remote = frame;
        repaint();
    }

    void setLocal(BufferedImage frame) {
        local = frame;
        repaint();
    }

    void setRemoteCamera(boolean on) {
        remoteCamera = on;
        if (!on) remote = null;
        repaint();
    }

    void setRemoteScreen(boolean on) {
        remoteScreen = on;
        repaint();
    }

    void setRemoteMuted(boolean muted) {
        remoteMuted = muted;
        repaint();
    }

    /** No self view in a sound-only call or while my camera is off and nothing is shared. */
    void setShowSelf(boolean show, boolean mirror) {
        showSelf = show;
        mirrorSelf = mirror;
        if (!show) local = null;
        repaint();
    }

    void setVerified(boolean value) {
        verified = value;
        repaint();
    }

    void setPhase(String text) {
        phase = text == null ? "" : text;
        repaint();
    }

    void setTime(String text) {
        time = text;
        repaint();
    }

    void setInfo(String text) {
        info = text == null ? "" : text;
        repaint();
    }

    String phase() {
        return phase;
    }

    BufferedImage remote() {
        return remote;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        int w = getWidth(), h = getHeight();
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, w, h);

        if (remote != null && remoteCamera) {
            double scale = Math.min((double) w / remote.getWidth(), (double) h / remote.getHeight());
            int iw = (int) Math.round(remote.getWidth() * scale), ih = (int) Math.round(remote.getHeight() * scale);
            g.drawImage(remote, (w - iw) / 2, (h - ih) / 2, iw, ih, null);
        } else {
            int size = Math.max(64, Math.min(w, h) / 3);
            Avatar.paint(g, name, w / 2, h / 2 - (phase.isEmpty() ? 0 : 20), size);
        }

        if (showSelf && local != null) {
            int sw = Math.max(120, w / 5), sh = sw * local.getHeight() / Math.max(1, local.getWidth());
            int sx = w - sw - 14, sy = h - sh - 14;
            g.setColor(PILL);
            g.fillRoundRect(sx - 3, sy - 3, sw + 6, sh + 6, 10, 10);
            if (mirrorSelf) {
                g.drawImage(local, sx + sw, sy, -sw, sh, null);
            } else {
                g.drawImage(local, sx, sy, sw, sh, null);
            }
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(2));
            g.drawRoundRect(sx, sy, sw, sh, 6, 6);
            pill(g, selfText, sx + 6, sy + 6);
        }

        // Top left: name and time, then encryption.
        Font bold = new Font(Font.SANS_SERIF, Font.BOLD, 15);
        g.setFont(bold);
        String title = time.isEmpty() ? name : name + "   " + time;
        FontMetrics fm = g.getFontMetrics();
        g.setColor(PILL);
        g.fillRoundRect(12, 12, fm.stringWidth(title) + 24, 30, 14, 14);
        g.setColor(Color.WHITE);
        g.drawString(title, 24, 32);
        if (!encrypted.isEmpty()) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            fm = g.getFontMetrics();
            g.setColor(PILL);
            g.fillRoundRect(12, 48, fm.stringWidth(encrypted) + 40, 24, 12, 12);
            new LockIcon(verified ? LockIcon.VERIFIED_ON_DARK : new Color(0xbdbdbd)).paintIcon(this, g, 20, 53);
            g.setColor(Color.WHITE);
            g.drawString(encrypted, 38, 65);
        }
        int y = 80;
        if (remoteMuted) {
            pill(g, mutedText, 12, y);
            y += 30;
        }
        if (remoteScreen) pill(g, screenText, 12, y);

        if (!phase.isEmpty()) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
            fm = g.getFontMetrics();
            int size = Math.max(64, Math.min(w, h) / 3);
            int ty = h / 2 - 20 + size / 2 + 34;
            g.setColor(Color.WHITE);
            g.drawString(phase, (w - fm.stringWidth(phase)) / 2, ty);
        }
        if (!info.isEmpty()) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            fm = g.getFontMetrics();
            pill(g, info, (w - fm.stringWidth(info) - 16) / 2, h - 40);
        }
        g.dispose();
    }

    private static void pill(Graphics2D g, String text, int x, int y) {
        if (text.isEmpty()) return;
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(PILL);
        g.fillRoundRect(x, y, fm.stringWidth(text) + 16, fm.getHeight() + 6, 12, 12);
        g.setColor(Color.WHITE);
        g.drawString(text, x + 8, y + fm.getAscent() + 3);
    }
}
