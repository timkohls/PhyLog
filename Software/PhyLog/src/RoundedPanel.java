import javax.swing.*;
import java.awt.*;

/** Panel mit abgerundeten Ecken und eigener Hintergrundfarbe, unabhängig vom gesetzten Layout. */
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
