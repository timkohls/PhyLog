import javax.swing.*;
import java.awt.*;

public class ChiSquareInfoDialog extends JDialog {

    public ChiSquareInfoDialog(Window ownerWindow, double reducedChiSquare, int degreesOfFreedom) {
        super(ownerWindow, "Reduzierte Chi²-Fehler Details", ModalityType.APPLICATION_MODAL);
        initUI(ownerWindow, reducedChiSquare, degreesOfFreedom);
    }

    private void initUI(Window ownerWindow, double reducedChiSquare, int degreesOfFreedom) {
        setLayout(new BorderLayout());
        setResizable(false);

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(20, 25, 20, 25));
        mainPanel.setBackground(Theme.BG);

        // --- 1. TITEL (ZENTRIERT) ---
        JLabel titleLabel = new JLabel("Reduziertes Chi-Quadrat (\u03C7\u00B2_red) Bewertung");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 15));
        titleLabel.setForeground(Theme.TEXT);
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        // --- 2. BEWERTUNG (Schwellenwerte kommen jetzt ausschließlich aus ChartPanel,
        //     damit Farbe/Text hier nie von der Grafik-Anzeige abweichen können) ---
        ChartPanel.ChiRating rating = ChartPanel.rateChiSquare(reducedChiSquare);
        Color ratingColor = ChartPanel.getChiSquareColor(reducedChiSquare);

        String ratingText;
        boolean showTips = false;
        String tipText = "";

        switch (rating) {
            case OVERFIT:
                ratingText = "Zu niedrig (Überanpassung)";
                showTips = true;
                tipText = "• Die Fehlerbalken sind möglicherweise überschätzt oder das Modell passt sich an das Rauschen an.";
                break;
            case GOOD:
                ratingText = "Sehr gut";
                break;
            case MODERATE:
                ratingText = "Mäßig";
                showTips = true;
                tipText = "• Prüfe, ob der Funktionstyp zum Datenverlauf passt.<br>• Bei einem zu hohen Polynomgrad droht Überanpassung.<br>• Zoome näher an den relevanten Bereich heran.";
                break;
            case UNDERFIT:
            default:
                ratingText = "Schlecht (Unteranpassung)";
                showTips = true;
                tipText = "• Das Modell weicht stark von den Daten ab.<br>• Überprüfe die Messfehler und das gewählte Modell.";
                break;
        }

        String hexRatingColor = String.format("#%02x%02x%02x", ratingColor.getRed(), ratingColor.getGreen(), ratingColor.getBlue());
        String hexPointColor = String.format("#%02x%02x%02x", Theme.POINT.getRed(), Theme.POINT.getGreen(), Theme.POINT.getBlue());

        // --- 3. DETAILTEXT (Saubere Formeldarstellung) ---
        StringBuilder htmlText = new StringBuilder();
        htmlText.append("<html><body style='text-align: center;'><p style='width: 380px;'>")
                .append("Das reduzierte &#967;&#178; bewertet die Güte der Anpassung unter Berücksichtigung der Freiheitsgrade (DOF):<br><br>")
                .append("<div style='background-color: #353535; padding: 8px; border-radius: 6px; margin-bottom: 10px; font-family: monospace;'>")
                .append("<b>Formel:</b><br>")
                .append("&#967;<sub>red</sub>&sup2; = &chi;&sup2; / DOF = (1 / DOF) &middot; &sum; (y<sub>i</sub> &minus; f(x<sub>i</sub>))&sup2;")
                .append("</div>")
                .append("<b>Aktueller Wert:</b> <span style='color:").append(hexPointColor).append(";'>").append(String.format("%.6f", reducedChiSquare)).append("</span><br>")
                .append("<b>Freiheitsgrade (DOF):</b> ").append(degreesOfFreedom).append("<br>")
                .append("<b>Bewertung:</b> <b style='color:").append(hexRatingColor).append(";'>").append(ratingText).append("</b>");

        if (showTips) {
            htmlText.append("<br><br><div style='text-align: left;'><b>Hinweise:</b><br>").append(tipText).append("</div>");
        }

        htmlText.append("</p></body></html>");

        JLabel textLabel = new JLabel(htmlText.toString());
        textLabel.setForeground(Theme.TEXT);
        textLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        // --- 4. FARB-BALKEN ---
        int barWidth = 380;
        JPanel barPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth();
                int h = 16;
                int y = 5;

                // Farbverlauf von Gelb (links, <1) über Grün (optimal bei ~1) zu Rot (rechts, >3)
                g2.setPaint(new GradientPaint(0, 0, new Color(241, 196, 15), w * 0.35f, 0, new Color(46, 204, 113)));
                g2.fillRoundRect(0, y, (int)(w * 0.5f), h, 6, 6);

                g2.setPaint(new GradientPaint(w * 0.35f, 0, new Color(46, 204, 113), w, 0, new Color(231, 76, 60)));
                g2.fillRoundRect((int)(w * 0.35f) - 2, y, w - (int)(w * 0.35f) + 2, h, 6, 6);

                // Marker-Positionierung (Skala bis max. 5.0)
                double maxScale = Math.max(4.0, reducedChiSquare);
                double normalized = Math.min(reducedChiSquare, maxScale) / maxScale;
                int markerX = (int) (normalized * (w - 6));

                g2.setColor(Theme.TEXT);
                g2.fillRect(markerX, y - 2, 6, h + 4);
                g2.setColor(Theme.BG);
                g2.drawRect(markerX, y - 2, 6, h + 4);
            }
        };
        barPanel.setPreferredSize(new Dimension(barWidth, 30));
        barPanel.setMaximumSize(new Dimension(barWidth, 30));
        barPanel.setOpaque(false);
        barPanel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel labelPanel = new JPanel(new BorderLayout());
        labelPanel.setOpaque(false);
        labelPanel.setPreferredSize(new Dimension(barWidth, 20));
        labelPanel.setMaximumSize(new Dimension(barWidth, 20));
        labelPanel.setAlignmentX(Component.CENTER_ALIGNMENT);

        // Zonen-Beschriftungen: Farben stammen aus ChartPanel.getChiSquareColor(), damit sie
        // exakt zu den Zonen passen, die auch im Diagramm-Overlay verwendet werden.
        JLabel lblLow = new JLabel("0 (Überanp.)");
        JLabel lblGood = new JLabel("Gut (≈ 1)");
        JLabel lblBad = new JLabel("Schlecht (≥ 3)");
        lblLow.setFont(new Font("SansSerif", Font.PLAIN, 9));
        lblGood.setFont(new Font("SansSerif", Font.PLAIN, 9));
        lblBad.setFont(new Font("SansSerif", Font.PLAIN, 9));
        lblLow.setForeground(ChartPanel.getChiSquareColor(0.5));
        lblGood.setForeground(ChartPanel.getChiSquareColor(1.0));
        lblBad.setForeground(ChartPanel.getChiSquareColor(4.0));

        labelPanel.add(lblLow, BorderLayout.WEST);
        labelPanel.add(lblGood, BorderLayout.CENTER);
        lblGood.setHorizontalAlignment(SwingConstants.CENTER);
        labelPanel.add(lblBad, BorderLayout.EAST);

        // --- 5. COMPONENT ASSEMBLY ---
        mainPanel.add(titleLabel);
        mainPanel.add(Box.createVerticalStrut(12));
        mainPanel.add(textLabel);
        mainPanel.add(Box.createVerticalStrut(15));
        mainPanel.add(barPanel);
        mainPanel.add(labelPanel);

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
}