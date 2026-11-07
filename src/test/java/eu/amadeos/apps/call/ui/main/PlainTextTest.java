package eu.amadeos.apps.call.ui.main;

import org.junit.jupiter.api.Test;

import javax.swing.JTextArea;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The text of the dialogs asks for the height of all its lines, so that the buttons below stay visible. */
class PlainTextTest {
    @Test
    void aLongMessageAsksForAllItsLines() {
        JTextArea text = MainWindow.plain("Die Adresse des anderen Computers: ein Name wie anna-pc.local, eine "
                + "IP-Adresse wie 192.168.1.20, oder eines davon mit Port, wie 192.168.1.20:47475.");
        int line = text.getFontMetrics(text.getFont()).getHeight();
        assertTrue(text.getPreferredSize().height >= 2 * line, "height " + text.getPreferredSize().height);
    }
}
