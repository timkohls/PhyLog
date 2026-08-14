import javax.swing.*;
import java.awt.*;

/**
 * Panel mit abgerundeten Ecken und eigener Hintergrundfarbe, unabhängig vom jeweils gesetzten
 * Inhalts-Layout (BoxLayout, GridBagLayout, ...) nutzbar. War ursprünglich eine private,
 * geschachtelte Klasse in {@link ChiSquareInfoDialog}; da dasselbe "Karten"-Aussehen inzwischen
 * auch in den übrigen Konfigurationsdialogen gebraucht wird (siehe {@link FormDialog}), liegt sie
 * jetzt hier zentral - eine Änderung am Karten-Look (z. B. Eckenradius) muss dadurch nur noch an
 * einer Stelle gepflegt werden.
 */
class RoundedPanel extends JPanel {

    private final Color background;
    private final int arc;

    /**
     * @param background Füllfarbe der Karte, typischerweise {@link Theme#CARD}.
     * @param arc        Eckenradius in Pixeln, siehe {@link Theme#CARD_ARC}.
     */
    RoundedPanel(Color background, int arc) {
        this.background = background;
        this.arc = arc;
        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(background);
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
        g2.dispose();
        super.paintComponent(g);
    }
}
