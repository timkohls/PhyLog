import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.List;

/** Zeigt Details zur Anpassungsgüte (reduziertes Chi²) als kompakte Karten statt eines
 *  einzelnen HTML-Textblocks: Kennzahlen, Formel, eine an den echten Bewertungsschwellen
 *  ausgerichtete Farbskala und - falls vorhanden - Hinweise sowie die Fit-Beschreibung. */
public class ChiSquareInfoDialog extends JDialog {

    private static final Color CARD_BG = new Color(45, 45, 45);
    private static final Color MUTED_TEXT = new Color(160, 160, 160);
    private static final int CARD_ARC = 12;
    private static final int CONTENT_WIDTH = 420;

    public ChiSquareInfoDialog(Window ownerWindow, double reducedChiSquare, int degreesOfFreedom,
                               ChartPanel.FitDescription fitDescription) {
        super(ownerWindow, "Anpassungsgüte (\u03C7\u00B2_red)", ModalityType.APPLICATION_MODAL);
        initUI(ownerWindow, reducedChiSquare, degreesOfFreedom, fitDescription);
    }

    private void initUI(Window ownerWindow, double reducedChiSquare, int degreesOfFreedom,
                        ChartPanel.FitDescription fitDescription) {
        setLayout(new BorderLayout());
        setResizable(false);

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        mainPanel.setBackground(Theme.BG);

        ChartPanel.ChiRating rating = ChartPanel.rateChiSquare(reducedChiSquare);
        Color ratingColor = ChartPanel.getChiSquareColor(reducedChiSquare);
        String ratingText = ratingText(rating);

        mainPanel.add(buildHeader());
        mainPanel.add(Box.createVerticalStrut(16));
        mainPanel.add(buildStatRow(reducedChiSquare, degreesOfFreedom, ratingText, ratingColor));
        mainPanel.add(Box.createVerticalStrut(14));
        mainPanel.add(buildFormulaCard());
        mainPanel.add(Box.createVerticalStrut(14));
        mainPanel.add(buildScaleBar(reducedChiSquare));

        String tipText = tipText(rating);
        if (tipText != null) {
            mainPanel.add(Box.createVerticalStrut(14));
            mainPanel.add(buildTipCard(tipText, ratingColor));
        }

        if (fitDescription != null) {
            mainPanel.add(Box.createVerticalStrut(14));
            mainPanel.add(buildFitDescriptionPanel(fitDescription));
        }

        JButton btnClose = new JButton("Schließen");
        btnClose.addActionListener(e -> dispose());

        JPanel southPanel = new JPanel();
        southPanel.setBackground(Theme.BG);
        southPanel.add(btnClose);

        add(mainPanel, BorderLayout.CENTER);
        add(southPanel, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(ownerWindow);
    }

    private String ratingText(ChartPanel.ChiRating rating) {
        return switch (rating) {
            case OVERFIT -> "Zu niedrig (Überanpassung)";
            case GOOD -> "Sehr gut";
            case MODERATE -> "Mäßig";
            case UNDERFIT -> "Schlecht (Unteranpassung)";
        };
    }

    /** @return Hinweistext für alles außer {@code GOOD}, sonst {@code null} (keine Hinweise nötig). */
    private String tipText(ChartPanel.ChiRating rating) {
        return switch (rating) {
            case OVERFIT -> "Die Fehlerbalken sind möglicherweise überschätzt, oder das Modell passt sich an das Rauschen an.";
            case MODERATE -> "Prüfe, ob der Funktionstyp zum Datenverlauf passt.<br>Bei einem zu hohen Polynomgrad droht Überanpassung.<br>Zoome näher an den relevanten Bereich heran.";
            case UNDERFIT -> "Das Modell weicht stark von den Daten ab.<br>Überprüfe die Messfehler und das gewählte Modell.";
            case GOOD -> null;
        };
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);
        header.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel title = new JLabel("Wie gut passt die Funktion zu den Messdaten?");
        title.setFont(new Font("SansSerif", Font.BOLD, 15));
        title.setForeground(Theme.TEXT);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel subtitle = new JLabel("Bewertet über das reduzierte Chi\u00B2 unter Berücksichtigung der Freiheitsgrade");
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 11));
        subtitle.setForeground(MUTED_TEXT);
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        header.add(title);
        header.add(Box.createVerticalStrut(4));
        header.add(subtitle);
        return header;
    }

    /** Drei nebeneinanderliegende Kennzahl-Karten (Wert, Freiheitsgrade, Bewertung) statt eines
     *  einzelnen HTML-Textblocks - macht die drei wichtigsten Zahlen auf einen Blick erfassbar. */
    private JPanel buildStatRow(double reducedChiSquare, int degreesOfFreedom, String ratingText, Color ratingColor) {
        JPanel row = new JPanel(new GridLayout(1, 3, 10, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.CENTER_ALIGNMENT);
        row.setMaximumSize(new Dimension(CONTENT_WIDTH, 76));
        row.setPreferredSize(new Dimension(CONTENT_WIDTH, 76));

        row.add(buildStatCard("\u03C7\u00B2_RED", String.format("%.3f", reducedChiSquare), Theme.POINT));
        row.add(buildStatCard("FREIHEITSGRADE", String.valueOf(degreesOfFreedom), Theme.TEXT));
        row.add(buildStatCard("BEWERTUNG", ratingText, ratingColor));
        return row;
    }

    private JPanel buildStatCard(String caption, String value, Color valueColor) {
        RoundedPanel card = new RoundedPanel(CARD_BG, CARD_ARC);
        card.setLayout(new BorderLayout(0, 4));
        card.setBorder(BorderFactory.createEmptyBorder(8, 6, 8, 6));

        JLabel captionLabel = new JLabel(caption, SwingConstants.CENTER);
        captionLabel.setFont(new Font("SansSerif", Font.PLAIN, 9));
        captionLabel.setForeground(MUTED_TEXT);

        // HTML statt Plain-Text, damit lange Bewertungstexte (z. B. "Schlecht (Unteranpassung)")
        // in der schmalen Karte umbrechen, statt über den Kartenrand hinauszulaufen.
        JLabel valueLabel = new JLabel("<html><div style='text-align:center;width:110px;'>" + value + "</div></html>");
        valueLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        valueLabel.setForeground(valueColor);
        valueLabel.setHorizontalAlignment(SwingConstants.CENTER);
        valueLabel.setVerticalAlignment(SwingConstants.CENTER);

        card.add(captionLabel, BorderLayout.NORTH);
        card.add(valueLabel, BorderLayout.CENTER);
        return card;
    }

    private JPanel buildFormulaCard() {
        RoundedPanel card = new RoundedPanel(CARD_BG, CARD_ARC);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        card.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.setMaximumSize(new Dimension(CONTENT_WIDTH, Integer.MAX_VALUE));

        JLabel formula = new JLabel("<html>&#967;<sub>red</sub>&sup2; = &chi;&sup2; / DOF = (1 / DOF) &middot; "
                + "&sum; (y<sub>i</sub> &minus; f(x<sub>i</sub>))&sup2;</html>");
        formula.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        formula.setForeground(Theme.TEXT);
        formula.setHorizontalAlignment(SwingConstants.CENTER);
        formula.setAlignmentX(Component.CENTER_ALIGNMENT);

        card.add(formula);
        return card;
    }

    /**
     * Farbskala, deren Bandgrenzen exakt den Schwellenwerten aus
     * {@link ChartPanel#rateChiSquare} entsprechen (0.8 / 1.5 / 3.0), statt wie zuvor einem
     * frei gewählten optischen Verlauf, der nicht immer zu den tatsächlichen Zonen passte.
     */
    private JComponent buildScaleBar(double reducedChiSquare) {
        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                int w = getWidth();
                int barHeight = 14;
                int barY = 4;

                double maxScale = Math.max(4.0, reducedChiSquare);
                int x1 = (int) Math.round((ChartPanel.CHI_OVERFIT_THRESHOLD / maxScale) * w);
                int x2 = (int) Math.round((ChartPanel.CHI_GOOD_THRESHOLD / maxScale) * w);
                int x3 = (int) Math.round(Math.min(ChartPanel.CHI_MODERATE_THRESHOLD / maxScale, 1.0) * w);

                Shape oldClip = g2.getClip();
                g2.setClip(new RoundRectangle2D.Double(0, barY, w, barHeight, 8, 8));
                g2.setColor(new Color(241, 196, 15));
                g2.fillRect(0, barY, x1, barHeight);
                g2.setColor(new Color(46, 204, 113));
                g2.fillRect(x1, barY, x2 - x1, barHeight);
                g2.setColor(new Color(241, 196, 15));
                g2.fillRect(x2, barY, x3 - x2, barHeight);
                g2.setColor(new Color(231, 76, 60));
                g2.fillRect(x3, barY, w - x3, barHeight);
                g2.setClip(oldClip);

                double normalized = Math.min(reducedChiSquare, maxScale) / maxScale;
                int markerX = (int) (normalized * w);
                g2.setColor(Theme.TEXT);
                g2.fillRoundRect(Math.max(0, Math.min(markerX - 2, w - 4)), barY - 3, 4, barHeight + 6, 2, 2);

                g2.setFont(new Font("SansSerif", Font.PLAIN, 9));
                g2.setColor(MUTED_TEXT);
                int labelY = barY + barHeight + 13;
                drawCenteredTick(g2, 0, "0", w, labelY);
                drawCenteredTick(g2, x1, "0.8", w, labelY);
                drawCenteredTick(g2, x2, "1.5", w, labelY);
                drawCenteredTick(g2, x3, "3.0", w, labelY);
            }
        };
        panel.setOpaque(false);
        panel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.setPreferredSize(new Dimension(CONTENT_WIDTH, 36));
        panel.setMaximumSize(new Dimension(CONTENT_WIDTH, 36));
        return panel;
    }

    private void drawCenteredTick(Graphics2D g2, int x, String text, int panelWidth, int y) {
        FontMetrics fm = g2.getFontMetrics();
        int textWidth = fm.stringWidth(text);
        int drawX = Math.max(0, Math.min(x - textWidth / 2, panelWidth - textWidth));
        g2.drawString(text, drawX, y);
    }

    private JPanel buildTipCard(String tipHtml, Color accentColor) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setAlignmentX(Component.CENTER_ALIGNMENT);
        wrapper.setMaximumSize(new Dimension(CONTENT_WIDTH, Integer.MAX_VALUE));

        JPanel accentStripe = new JPanel();
        accentStripe.setBackground(accentColor);
        accentStripe.setPreferredSize(new Dimension(4, 10));

        RoundedPanel card = new RoundedPanel(CARD_BG, CARD_ARC);
        card.setLayout(new BorderLayout());
        card.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JLabel tipLabel = new JLabel("<html><div style='width: 340px;'>" + tipHtml + "</div></html>");
        tipLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        tipLabel.setForeground(Theme.TEXT);
        card.add(tipLabel, BorderLayout.CENTER);

        wrapper.add(accentStripe, BorderLayout.WEST);
        wrapper.add(card, BorderLayout.CENTER);
        return wrapper;
    }

    /** Baut den Abschnitt "Gefittete Funktion": Gleichung plus physikalisch interpretierbare
     *  Kenngrößen (Steigung, Amplitude, Periodendauer, ...), siehe {@link ChartPanel.FitDescription}. */
    private JPanel buildFitDescriptionPanel(ChartPanel.FitDescription fitDescription) {
        RoundedPanel panel = new RoundedPanel(CARD_BG, CARD_ARC);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        panel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.setMaximumSize(new Dimension(CONTENT_WIDTH, Integer.MAX_VALUE));

        JLabel title = new JLabel("Gefittete Funktion");
        title.setFont(new Font("SansSerif", Font.BOLD, 12));
        title.setForeground(Theme.TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(title);
        panel.add(Box.createVerticalStrut(6));

        JLabel equationLabel = new JLabel(fitDescription.equation);
        equationLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        equationLabel.setForeground(Theme.ACCENT);
        equationLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(equationLabel);

        List<String> lines = fitDescription.parameterLines;
        if (!lines.isEmpty()) {
            panel.add(Box.createVerticalStrut(8));
            StringBuilder html = new StringBuilder("<html>");
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) html.append("<br>");
                html.append(lines.get(i));
            }
            html.append("</html>");

            JLabel paramsLabel = new JLabel(html.toString());
            paramsLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
            paramsLabel.setForeground(Theme.TEXT);
            paramsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(paramsLabel);
        }

        return panel;
    }

    /** Einfaches, abgerundetes Karten-Panel in einer einheitlichen Hintergrundfarbe - ersetzt
     *  die zuvor uneinheitliche Mischung aus Linienrahmen und einer einzelnen dunklen Fläche. */
    private static class RoundedPanel extends JPanel {
        private final Color background;
        private final int arc;

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
}