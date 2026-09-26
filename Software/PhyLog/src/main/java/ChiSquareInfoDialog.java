import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

/** Dialog zur visuellen Aufbereitung der Anpassungsgüte (reduziertes Chi²) mittels Informationskarten. */
public class ChiSquareInfoDialog extends JDialog {

    private static final int CONTENT_WIDTH = 420;

    private Timer settleDelay;
    private Timer animationTimer;

    /** Erstellt den Dialog unter Berücksichtigung des gewählten Sigma-Modus. */
    public ChiSquareInfoDialog(Window ownerWindow, double reducedChiSquare, int degreesOfFreedom,
                               CurveFitting.FitDescription fitDescription, GoodnessOfFit.SigmaMode sigmaMode) {
        super(ownerWindow, "Anpassungsgüte (χ²_red)", ModalityType.APPLICATION_MODAL);
        initUI(ownerWindow, reducedChiSquare, degreesOfFreedom, fitDescription, sigmaMode);
    }

    private void initUI(Window ownerWindow, double reducedChiSquare, int degreesOfFreedom,
                        CurveFitting.FitDescription fitDescription, GoodnessOfFit.SigmaMode sigmaMode) {
        setLayout(new BorderLayout());
        setResizable(false);

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        mainPanel.setBackground(Theme.BG);

        GoodnessOfFit.ChiRating rating = GoodnessOfFit.rate(reducedChiSquare);

        mainPanel.add(buildHeader(sigmaMode));
        mainPanel.add(Box.createVerticalStrut(16));
        mainPanel.add(buildSigmaGuideCard(sigmaMode));
        mainPanel.add(Box.createVerticalStrut(14));

        if (rating == GoodnessOfFit.ChiRating.NOT_EVALUABLE) {
            mainPanel.add(buildNotEvaluableCard(degreesOfFreedom));
        } else {
            // gradientColorFor statt colorFor, damit die Bewertungsfarbe hier exakt der Stelle auf
            // der Farbskala weiter unten entspricht (siehe #buildScaleBar) statt an den
            // rate()-Stufengrenzen abrupt zu springen.
            Color ratingColor = GoodnessOfFit.gradientColorFor(reducedChiSquare);
            String ratingText = ratingText(rating);

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
        }

        if (fitDescription != null) {
            mainPanel.add(Box.createVerticalStrut(14));
            mainPanel.add(buildFitDescriptionPanel(fitDescription));
        }

        JButton btnClose = new JButton("Schließen");
        btnClose.addActionListener(_ -> dispose());

        JPanel southPanel = new JPanel();
        southPanel.setBackground(Theme.BG);
        southPanel.add(btnClose);

        add(mainPanel, BorderLayout.CENTER);
        add(southPanel, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(ownerWindow);
    }

    /** Ersatzanzeige für den Fall, dass sich mit den aktuell sichtbaren Datenpunkten kein
     *  sinnvolles reduziertes Chi² berechnen lässt (Freiheitsgrade &le; 0), z. B. nach starkem
     *  Zoom auf nur zwei Punkte bei einem linearen Fit - Erklärung statt eines bedeutungslosen
     *  Zahlenpaars. */
    private JPanel buildNotEvaluableCard(int degreesOfFreedom) {
        RoundedPanel card = new RoundedPanel(Theme.CARD, Theme.CARD_ARC);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        card.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.setMaximumSize(new Dimension(CONTENT_WIDTH, Integer.MAX_VALUE));

        JLabel title = new JLabel("Nicht auswertbar");
        title.setFont(new Font("SansSerif", Font.BOLD, 13));
        title.setForeground(Theme.MUTED);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel detailLabel = getDetailLabel(degreesOfFreedom);

        card.add(title);
        card.add(Box.createVerticalStrut(8));
        card.add(detailLabel);
        return card;
    }

    private static JLabel getDetailLabel(int degreesOfFreedom) {
        String detail = "Für die gewählte Anpassung reichen die sichtbaren Datenpunkte nicht aus "
                + "(Freiheitsgrade: " + degreesOfFreedom + ", nötig: mindestens 1).<br>"
                + "Zoome heraus oder wähle einen Ausschnitt mit mehr Messpunkten.";
        JLabel detailLabel = new JLabel("<html><div style='width: 340px;'>" + detail + "</div></html>");
        detailLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        detailLabel.setForeground(Theme.TEXT);
        detailLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return detailLabel;
    }

    /** @param rating niemals {@link GoodnessOfFit.ChiRating#NOT_EVALUABLE} - dieser Fall wird in
     *                {@link #initUI} bereits vorher abgefangen. */
    private String ratingText(GoodnessOfFit.ChiRating rating) {
        return switch (rating) {
            case OVERFIT -> "Zu niedrig (Überanpassung)";
            case GOOD -> "Sehr gut";
            case MODERATE -> "Mäßig";
            case UNDERFIT -> "Schlecht (Unteranpassung)";
            case NOT_EVALUABLE -> "Nicht auswertbar";
        };
    }

    /** @param rating niemals {@link GoodnessOfFit.ChiRating#NOT_EVALUABLE}, siehe {@link #ratingText}. */
    private String tipText(GoodnessOfFit.ChiRating rating) {
        return switch (rating) {
            case OVERFIT -> "Die Fehlerbalken sind möglicherweise überschätzt, oder das Modell passt sich an das Rauschen an.";
            case MODERATE -> "Prüfe, ob der Funktionstyp zum Datenverlauf passt.<br>Bei einem zu hohen Polynomgrad droht Überanpassung.<br>Zoome näher an den relevanten Bereich heran.";
            case UNDERFIT -> "Das Modell weicht stark von den Daten ab.<br>Überprüfe die Messfehler und das gewählte Modell.";
            case GOOD, NOT_EVALUABLE -> null;
        };
    }

    private JPanel buildHeader(GoodnessOfFit.SigmaMode sigmaMode) {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);
        header.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel title = new JLabel("Wie gut passt die Funktion zu den Messdaten?");
        title.setFont(new Font("SansSerif", Font.BOLD, 15));
        title.setForeground(Theme.TEXT);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel subtitle = new JLabel("Bewertet über das reduzierte Chi² unter Berücksichtigung der Freiheitsgrade");
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 11));
        subtitle.setForeground(Theme.MUTED);
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel modeLabel = new JLabel("σ-Modus: " + sigmaModeText(sigmaMode));
        modeLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        modeLabel.setForeground(Theme.MUTED);
        modeLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        header.add(title);
        header.add(Box.createVerticalStrut(4));
        header.add(subtitle);
        header.add(modeLabel);
        return header;
    }

    private String sigmaModeText(GoodnessOfFit.SigmaMode sigmaMode) {
        return switch (sigmaMode) {
            case CONSTANT -> "konstant";
            case RESIDUAL_LOCAL -> "lokal, hartes Fenster";
            case RESIDUAL_LOCAL_GAUSSIAN -> "lokal, Gauß-gewichtet";
            case DIFFERENCE_BASED -> "differenzbasiert";
        };
    }

    /** Kurze, modusunabhängig immer sichtbare Entscheidungshilfe: wann welcher σ-Modus
     *  sinnvoll ist. Der aktuell gewählte Modus wird hervorgehoben, die anderen bleiben als
     *  Vergleich sichtbar - ansonsten müsste man den Dialog neu öffnen, um zwischen den
     *  Optionen abzuwägen. */
    private JPanel buildSigmaGuideCard(GoodnessOfFit.SigmaMode current) {
        RoundedPanel card = new RoundedPanel(Theme.CARD, Theme.CARD_ARC);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        card.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.setMaximumSize(new Dimension(CONTENT_WIDTH, Integer.MAX_VALUE));

        JLabel title = new JLabel("Welchen σ-Modus wählen?");
        title.setFont(new Font("SansSerif", Font.BOLD, 12));
        title.setForeground(Theme.TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(title);

        JLabel subtitle = new JLabel("Je nachdem, wie die Punkte um deine Kurve streuen:");
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 11));
        subtitle.setForeground(Theme.MUTED);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(subtitle);
        card.add(Box.createVerticalStrut(6));

        String html = "<html><div style='width: 340px;'>" + guideLine("Konstant", current == GoodnessOfFit.SigmaMode.CONSTANT,
                "Sensorgenauigkeit ist bekannt (Datenblatt, Kalibrierschein) - unabhängig davon, wie die "
                        + "Daten aussehen.") +
                guideLine("Differenzbasiert", current == GoodnessOfFit.SigmaMode.DIFFERENCE_BASED,
                        "Sensorgenauigkeit unbekannt, Streuung der Punkte um die Kurve sieht überall etwa gleich "
                                + "breit aus. Im Zweifel die einfachste automatische Wahl.") +
                guideLine("Lokal, stufig", current == GoodnessOfFit.SigmaMode.RESIDUAL_LOCAL,
                        "Streuung der Punkte ändert sich sichtbar über den Messbereich, x-Werte einigermaßen "
                                + "gleichmäßig verteilt.") +
                guideLine("Lokal, weich", current == GoodnessOfFit.SigmaMode.RESIDUAL_LOCAL_GAUSSIAN,
                        "Wie „lokal, stufig“, aber x-Werte ungleichmäßig verteilt oder glatter statt "
                                + "stufiger Verlauf gewünscht.") +
                "</div></html>";

        JLabel body = new JLabel(html);
        body.setFont(new Font("SansSerif", Font.PLAIN, 11));
        body.setForeground(Theme.TEXT);
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(body);

        return card;
    }

    /** Baut eine einzelne Zeile der Entscheidungshilfe; die aktive Auswahl wird fett und in der
     *  Akzentfarbe hervorgehoben, damit man sie unter den anderen Optionen sofort wiederfindet. */
    private String guideLine(String modeName, boolean isCurrent, String explanation) {
        String accentHex = String.format("#%02x%02x%02x", Theme.ACCENT.getRed(), Theme.ACCENT.getGreen(), Theme.ACCENT.getBlue());
        String label = isCurrent
                ? "<b style='color:" + accentHex + "'>" + modeName + " (aktuell)</b>"
                : "<b>" + modeName + "</b>";
        return label + " – " + explanation + "<br><br>";
    }

    private JPanel buildStatRow(double reducedChiSquare, int degreesOfFreedom, String ratingText, Color ratingColor) {
        JPanel row = new JPanel(new GridLayout(1, 3, 10, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.CENTER_ALIGNMENT);
        row.setMaximumSize(new Dimension(CONTENT_WIDTH, 76));
        row.setPreferredSize(new Dimension(CONTENT_WIDTH, 76));

        row.add(buildStatCard("χ²_RED", String.format("%.3f", reducedChiSquare), Theme.POINT_A));
        row.add(buildStatCard("FREIHEITSGRADE", String.valueOf(degreesOfFreedom), Theme.TEXT));
        row.add(buildStatCard("BEWERTUNG", ratingText, ratingColor));
        return row;
    }

    private JPanel buildStatCard(String caption, String value, Color valueColor) {
        RoundedPanel card = new RoundedPanel(Theme.CARD, Theme.CARD_ARC);
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

    private JPanel buildFormulaCard() {
        RoundedPanel card = new RoundedPanel(Theme.CARD, Theme.CARD_ARC);
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

    /** Zeichnet die Farbskala samt Marker und startet dessen Animation beim Öffnen des Fensters;
     *  {@link #settleDelay} und {@link #animationTimer} laufen beide über {@link Timer}, damit
     *  Verzögerung und Animation selbst sauber wieder gestoppt werden können. */
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

                // Geometrie bewusst aus denselben Konstanten wie GoodnessOfFit.gradientColorFor
                // abgeleitet (GRADIENT_REFERENCE_SCALE/GRADIENT_MIDPOINT_FRACTION) - so kann diese
                // gezeichnete Skala nie mehr von der dort berechneten Farbe abweichen, z. B. in der
                // Chi²-Überlagerung im Chart.
                float midpointX = w * (float) GoodnessOfFit.GRADIENT_MIDPOINT_FRACTION;
                double referenceScale = GoodnessOfFit.GRADIENT_REFERENCE_SCALE;

                g2.setPaint(new GradientPaint(0, 0, Theme.WARNING, midpointX, 0, Theme.SUCCESS));
                g2.fillRoundRect(0, barY, (int) (w * 0.5f), barHeight, 6, 6);
                g2.setPaint(new GradientPaint(midpointX, 0, Theme.SUCCESS, w, 0, Theme.DANGER));
                g2.fillRoundRect((int) midpointX - 2, barY, w - (int) midpointX + 2, barHeight, 6, 6);

                double normalized = Math.min(markerValue[0], referenceScale) / referenceScale;
                int markerX = (int) (normalized * (w - 6));
                g2.setColor(Theme.TEXT);
                g2.fillRoundRect(markerX, barY - 3, 6, barHeight + 6, 2, 2);

                g2.setFont(new Font("SansSerif", Font.PLAIN, 9));
                g2.setColor(Theme.MUTED);
                int labelY = barY + barHeight + 13;
                drawCenteredTick(g2, 0, "0", w, labelY);
                drawCenteredTick(g2, (int) Math.round(GoodnessOfFit.CHI_OVERFIT_THRESHOLD / referenceScale * w), "0.8", w, labelY);
                drawCenteredTick(g2, (int) Math.round(GoodnessOfFit.CHI_GOOD_THRESHOLD / referenceScale * w), "1.5", w, labelY);
                drawCenteredTick(g2, (int) Math.round(GoodnessOfFit.CHI_MODERATE_THRESHOLD / referenceScale * w), "3.0", w, labelY);
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

                settleDelay = new Timer(100, null);

                settleDelay.addActionListener(_ -> {

                    settleDelay.stop();

                    final long startTime = System.nanoTime();

                    final double targetValue =
                            Math.min(reducedChiSquare, GoodnessOfFit.GRADIENT_REFERENCE_SCALE);

                    animationTimer = new Timer(16, null);

                    animationTimer.addActionListener(_ -> {

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
            @Override
            public void windowClosed(WindowEvent e) {
                if (settleDelay != null) settleDelay.stop();
                if (animationTimer != null) animationTimer.stop();
            }
        });

        return panel;
    }

    private double easeInOutSine(double t) {
        return -(Math.cos(Math.PI * t) - 1) / 2;
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

        RoundedPanel card = new RoundedPanel(Theme.CARD, Theme.CARD_ARC);
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

    private JPanel buildFitDescriptionPanel(CurveFitting.FitDescription fitDescription) {
        RoundedPanel panel = new RoundedPanel(Theme.CARD, Theme.CARD_ARC);
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
}