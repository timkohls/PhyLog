import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

/**
 * Dialog zur visuellen Aufbereitung der Anpassungsgüte (reduziertes Chi²) mittels Informationskarten.
 */
public class ChiSquareInfoDialog extends JDialog {

    private static final int CARD_ARC = 12;
    private static final int CONTENT_WIDTH = 420;
    /** Obere Grenze der animierten Farbskala (siehe {@link #buildScaleBar}); Werte darüber
     *  werden auf diesen Wert begrenzt dargestellt. */
    private static final double BAR_REFERENCE_SCALE = 4.0;

    /**
     * Erstellt den Dialog mit Standard-Fehlermodus.
     *
     * @param ownerWindow        Übergeordnetes Fenster.
     * @param reducedChiSquare   Berechnetes reduziertes Chi².
     * @param degreesOfFreedom   Anzahl der Freiheitsgrade.
     * @param fitDescription     Beschreibung der gefitteten Funktion.
     */
    public ChiSquareInfoDialog(Window ownerWindow, double reducedChiSquare, int degreesOfFreedom,
                               CurveFitting.FitDescription fitDescription) {
        this(ownerWindow, reducedChiSquare, degreesOfFreedom, fitDescription, GoodnessOfFit.SigmaMode.CONSTANT);
    }

    /**
     * Erstellt den Dialog unter Berücksichtigung des gewählten Sigma-Modus.
     *
     * @param ownerWindow        Übergeordnetes Fenster.
     * @param reducedChiSquare   Berechnetes reduziertes Chi².
     * @param degreesOfFreedom   Anzahl der Freiheitsgrade.
     * @param fitDescription     Beschreibung der gefitteten Funktion.
     * @param sigmaMode          Aktuell gewählter Modus für Fehlergrenzen.
     */
    public ChiSquareInfoDialog(Window ownerWindow, double reducedChiSquare, int degreesOfFreedom,
                               CurveFitting.FitDescription fitDescription, GoodnessOfFit.SigmaMode sigmaMode) {
        super(ownerWindow, "Anpassungsgüte (\u03C7\u00B2_red)", ModalityType.APPLICATION_MODAL);
        initUI(ownerWindow, reducedChiSquare, degreesOfFreedom, fitDescription, sigmaMode);
    }

    /**
     * Initialisiert die Benutzeroberfläche und baut das Karten-Layout auf.
     */
    private void initUI(Window ownerWindow, double reducedChiSquare, int degreesOfFreedom,
                        CurveFitting.FitDescription fitDescription, GoodnessOfFit.SigmaMode sigmaMode) {
        setLayout(new BorderLayout());
        setResizable(false);

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        mainPanel.setBackground(Theme.BG);

        GoodnessOfFit.ChiRating rating = GoodnessOfFit.rate(reducedChiSquare);
        Color ratingColor = GoodnessOfFit.colorFor(reducedChiSquare);
        String ratingText = ratingText(rating);

        mainPanel.add(buildHeader(sigmaMode));
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

    /**
     * Wandelt eine Chi-Bewertung in lesbaren Text um.
     */
    private String ratingText(GoodnessOfFit.ChiRating rating) {
        return switch (rating) {
            case OVERFIT -> "Zu niedrig (Überanpassung)";
            case GOOD -> "Sehr gut";
            case MODERATE -> "Mäßig";
            case UNDERFIT -> "Schlecht (Unteranpassung)";
        };
    }

    /**
     * Erzeugt Hinweistexte zur Verbesserung der Modellgüte basierend auf der Bewertung.
     */
    private String tipText(GoodnessOfFit.ChiRating rating) {
        return switch (rating) {
            case OVERFIT -> "Die Fehlerbalken sind möglicherweise überschätzt, oder das Modell passt sich an das Rauschen an.";
            case MODERATE -> "Prüfe, ob der Funktionstyp zum Datenverlauf passt.<br>Bei einem zu hohen Polynomgrad droht Überanpassung.<br>Zoome näher an den relevanten Bereich heran.";
            case UNDERFIT -> "Das Modell weicht stark von den Daten ab.<br>Überprüfe die Messfehler und das gewählte Modell.";
            case GOOD -> null;
        };
    }

    /**
     * Baut den Kopfbereich des Dialogs auf.
     */
    private JPanel buildHeader(GoodnessOfFit.SigmaMode sigmaMode) {
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
        subtitle.setForeground(Theme.MUTED);
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel modeLabel = new JLabel("\u03c3-Modus: " + sigmaModeText(sigmaMode));
        modeLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        modeLabel.setForeground(Theme.MUTED);
        modeLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        header.add(title);
        header.add(Box.createVerticalStrut(4));
        header.add(subtitle);
        header.add(modeLabel);
        return header;
    }

    /** Kurzbezeichnung des Sigma-Modus für die Kopfzeile. */
    private String sigmaModeText(GoodnessOfFit.SigmaMode sigmaMode) {
        return switch (sigmaMode) {
            case CONSTANT -> "konstant";
            case RESIDUAL_LOCAL -> "lokal, hartes Fenster";
            case RESIDUAL_LOCAL_GAUSSIAN -> "lokal, Gauß-gewichtet";
        };
    }

    /**
     * Baut die Reihe aus drei Kennzahl-Karten auf.
     */
    private JPanel buildStatRow(double reducedChiSquare, int degreesOfFreedom, String ratingText, Color ratingColor) {
        JPanel row = new JPanel(new GridLayout(1, 3, 10, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.CENTER_ALIGNMENT);
        row.setMaximumSize(new Dimension(CONTENT_WIDTH, 76));
        row.setPreferredSize(new Dimension(CONTENT_WIDTH, 76));

        row.add(buildStatCard("\u03C7\u00B2_RED", String.format("%.3f", reducedChiSquare), Theme.POINT_A));
        row.add(buildStatCard("FREIHEITSGRADE", String.valueOf(degreesOfFreedom), Theme.TEXT));
        row.add(buildStatCard("BEWERTUNG", ratingText, ratingColor));
        return row;
    }

    /**
     * Baut eine einzelne Kennzahl-Karte mit Beschriftung und Wert auf.
     */
    private JPanel buildStatCard(String caption, String value, Color valueColor) {
        RoundedPanel card = new RoundedPanel(Theme.CARD, CARD_ARC);
        card.setLayout(new BorderLayout(0, 4));
        card.setBorder(BorderFactory.createEmptyBorder(8, 6, 8, 6));

        JLabel captionLabel = new JLabel(caption, SwingConstants.CENTER);
        captionLabel.setFont(new Font("SansSerif", Font.PLAIN, 9));
        captionLabel.setForeground(Theme.MUTED);

        JLabel valueLabel = new JLabel("<html><div style='text-align:center;width:110px;'>" + value + "</div></html>");
        valueLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        valueLabel.setForeground(valueColor);
        valueLabel.setHorizontalAlignment(SwingConstants.CENTER);
        valueLabel.setVerticalAlignment(SwingConstants.CENTER);

        card.add(captionLabel, BorderLayout.NORTH);
        card.add(valueLabel, BorderLayout.CENTER);
        return card;
    }

    /**
     * Baut die Karte zur Darstellung der mathematischen Formel auf.
     */
    private JPanel buildFormulaCard() {
        RoundedPanel card = new RoundedPanel(Theme.CARD, CARD_ARC);
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
     * Erstellt die animierte Farbskala mit Marker zur visuellen Einordnung.
     */
    private JComponent buildScaleBar(double reducedChiSquare) {
        double[] markerValue = {0.0};

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

                g2.setPaint(new GradientPaint(0, 0, Theme.WARNING, w * 0.35f, 0, Theme.SUCCESS));
                g2.fillRoundRect(0, barY, (int) (w * 0.5f), barHeight, 6, 6);
                g2.setPaint(new GradientPaint(w * 0.35f, 0, Theme.SUCCESS, w, 0, Theme.DANGER));
                g2.fillRoundRect((int) (w * 0.35f) - 2, barY, w - (int) (w * 0.35f) + 2, barHeight, 6, 6);

                double normalized = Math.min(markerValue[0], BAR_REFERENCE_SCALE) / BAR_REFERENCE_SCALE;
                int markerX = (int) (normalized * (w - 6));
                g2.setColor(Theme.TEXT);
                g2.fillRoundRect(markerX, barY - 3, 6, barHeight + 6, 2, 2);

                g2.setFont(new Font("SansSerif", Font.PLAIN, 9));
                g2.setColor(Theme.MUTED);
                int labelY = barY + barHeight + 13;
                drawCenteredTick(g2, 0, "0", w, labelY);
                drawCenteredTick(g2, (int) Math.round(GoodnessOfFit.CHI_OVERFIT_THRESHOLD / BAR_REFERENCE_SCALE * w), "0.8", w, labelY);
                drawCenteredTick(g2, (int) Math.round(GoodnessOfFit.CHI_GOOD_THRESHOLD / BAR_REFERENCE_SCALE * w), "1.5", w, labelY);
                drawCenteredTick(g2, (int) Math.round(GoodnessOfFit.CHI_MODERATE_THRESHOLD / BAR_REFERENCE_SCALE * w), "3.0", w, labelY);
            }
        };
        panel.setOpaque(false);
        panel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.setPreferredSize(new Dimension(CONTENT_WIDTH, 36));
        panel.setMaximumSize(new Dimension(CONTENT_WIDTH, 36));

        int durationMs = 3000;

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {

                Timer settleDelay = new Timer(100, null);

                settleDelay.addActionListener(ev -> {

                    settleDelay.stop();

                    final long startTime = System.nanoTime();

                    final double targetValue =
                            Math.min(reducedChiSquare, BAR_REFERENCE_SCALE);

                    Timer animationTimer = new Timer(16, null);

                    animationTimer.addActionListener(tick -> {

                        double elapsed =
                                (System.nanoTime() - startTime) / 1_000_000_000.0;

                        double t = Math.min(
                                1.0,
                                elapsed / (durationMs / 1000.0)
                        );

                        double eased = easeInOutSine(t);

                        markerValue[0] = eased * targetValue;

                        panel.repaint();

                        if (t >= 1.0) {
                            animationTimer.stop();
                        }
                    });

                    animationTimer.start();
                });

                settleDelay.setRepeats(false);
                settleDelay.start();
            }
        });

        return panel;
    }

    /**
     * Sinus-basierte Easing-Funktion für die Weichheit der Marker-Animation.
     */
    private double easeInOutSine(double t) {
        return -(Math.cos(Math.PI * t) - 1) / 2;
    }

    /**
     * Zeichnet zentrierten Skalentext an der angegebenen X-Position.
     */
    private void drawCenteredTick(Graphics2D g2, int x, String text, int panelWidth, int y) {
        FontMetrics fm = g2.getFontMetrics();
        int textWidth = fm.stringWidth(text);
        int drawX = Math.max(0, Math.min(x - textWidth / 2, panelWidth - textWidth));
        g2.drawString(text, drawX, y);
    }

    /**
     * Baut eine Hinweis-Karte mit Akzentstreifen auf.
     */
    private JPanel buildTipCard(String tipHtml, Color accentColor) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setAlignmentX(Component.CENTER_ALIGNMENT);
        wrapper.setMaximumSize(new Dimension(CONTENT_WIDTH, Integer.MAX_VALUE));

        JPanel accentStripe = new JPanel();
        accentStripe.setBackground(accentColor);
        accentStripe.setPreferredSize(new Dimension(4, 10));

        RoundedPanel card = new RoundedPanel(Theme.CARD, CARD_ARC);
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

    /**
     * Baut den Detailbereich für die gefittete Funktion auf.
     */
    private JPanel buildFitDescriptionPanel(CurveFitting.FitDescription fitDescription) {
        RoundedPanel panel = new RoundedPanel(Theme.CARD, CARD_ARC);
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

    /**
     * Panel-Komponente mit abgerundeten Ecken und Hintergrundfarbe.
     */
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
