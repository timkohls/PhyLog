import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Zeichnet ein Zeit/Messwert-Diagramm und übernimmt zugleich die Ausgleichsrechnung
 * (lineare/polynomiale, exponentielle und sinusförmige Regression) sowie die Berechnung
 * des reduzierten Chi-Quadrats als Gütemaß für den aktuell gewählten Fit.
 *
 * <p>Diese Klasse kennt keine Sensoren oder Hardware. Sie bekommt ausschließlich fertige
 * (Zeit, Messwert)-Paare über {@link #setData(List)} übergeben - unabhängig davon, ob diese
 * aus einer CSV-Datei importiert oder (perspektivisch) live von einem angeschlossenen Sensor
 * geliefert wurden. Sobald die echte Sensor-Anbindung existiert, muss hier nichts geändert
 * werden; es genügt, {@link #setData(List)} regelmäßig mit neuen Messwerten aufzurufen.</p>
 *
 * <p>Interaktion: Ziehen mit der linken Maustaste zoomt auf einen Ausschnitt (Rubber-Band-
 * Auswahl), Ziehen mit der rechten Maustaste ermöglicht eine Freihand-Auswahl von Messpunkten,
 * ein einfacher Rechtsklick ohne Ziehen setzt den Zoom zurück, und ein Klick auf das kleine
 * "i"-Symbol neben der Chi²-Anzeige öffnet {@link ChiSquareInfoDialog} mit einer ausführlichen
 * Erklärung des aktuellen Gütewerts.</p>
 *
 * <p>Performance-Hinweis: Die eigentliche Regression (inkl. der iterativen Sinus-Anpassung)
 * ist vergleichsweise teuer. Sie wird deshalb zwischengespeichert und nur neu berechnet, wenn
 * sich Daten, Fit-Typ oder Polynomgrad tatsächlich geändert haben (siehe {@link #fitDirty}),
 * nicht bei jedem repaint() (z. B. wegen einer reinen Mausbewegung fürs Fadenkreuz).</p>
 */
public class ChartPanel extends JPanel {

    /** Verfügbare Regressionsmodelle, die über das Diagramm gelegt werden können. */
    public enum FitMode {
        /** Kein Fit, nur die Rohdaten werden angezeigt. */
        NONE,
        /** f(x) = m*x + b (2 Parameter). */
        LINEAR,
        /** f(x) = a_n * x^n + ... + a_0 (n+1 Parameter, Grad n einstellbar). */
        POLYNOMIAL,
        /** f(x) = A * sin(w*x + phi) + offset (4 Parameter). */
        SINUS,
        /** f(x) = a * exp(b*x) (2 Parameter). */
        EXPONENTIAL
    }

    /**
     * Bewertungsklassen für das reduzierte Chi-Quadrat.
     * Einzige Quelle der Wahrheit für die Schwellenwerte - {@link ChiSquareInfoDialog}
     * fragt ausschließlich hier ab, damit Diagramm-Overlay und Detail-Dialog niemals
     * unterschiedliche Grenzwerte verwenden können.
     */
    public enum ChiRating {
        /** &lt; {@link #CHI_OVERFIT_THRESHOLD}: Fehler wahrscheinlich überschätzt / Überanpassung. */
        OVERFIT,
        /** Zwischen {@link #CHI_OVERFIT_THRESHOLD} und {@link #CHI_GOOD_THRESHOLD}: guter Fit. */
        GOOD,
        /** Zwischen {@link #CHI_GOOD_THRESHOLD} und {@link #CHI_MODERATE_THRESHOLD}: mäßiger Fit. */
        MODERATE,
        /** &gt; {@link #CHI_MODERATE_THRESHOLD}: Modell passt schlecht (Unteranpassung). */
        UNDERFIT
    }

    public static final double CHI_OVERFIT_THRESHOLD = 0.8;
    public static final double CHI_GOOD_THRESHOLD = 1.5;
    public static final double CHI_MODERATE_THRESHOLD = 3.0;

    /**
     * Ordnet einen reduzierten Chi-Quadrat-Wert einer {@link ChiRating} zu.
     *
     * @param reducedChiSquare der reduzierte Chi-Quadrat-Wert (chi^2 / Freiheitsgrade)
     * @return die zugehörige Bewertungsklasse
     */
    public static ChiRating rateChiSquare(double reducedChiSquare) {
        if (reducedChiSquare < CHI_OVERFIT_THRESHOLD) return ChiRating.OVERFIT;
        if (reducedChiSquare <= CHI_GOOD_THRESHOLD) return ChiRating.GOOD;
        if (reducedChiSquare <= CHI_MODERATE_THRESHOLD) return ChiRating.MODERATE;
        return ChiRating.UNDERFIT;
    }

    /**
     * Liefert die Anzeigefarbe für einen reduzierten Chi-Quadrat-Wert, konsistent mit
     * {@link #rateChiSquare(double)}.
     *
     * @param reducedChiSquare der reduzierte Chi-Quadrat-Wert
     * @return Grün für einen guten Fit, Gelb für Über-/mäßige Anpassung, Rot für Unteranpassung
     */
    public static Color getChiSquareColor(double reducedChiSquare) {
        switch (rateChiSquare(reducedChiSquare)) {
            case OVERFIT:
                return new Color(241, 196, 15);  // Gelb / Warnung
            case GOOD:
                return new Color(46, 204, 113);  // Grün
            case MODERATE:
                return new Color(241, 196, 15);  // Gelb
            case UNDERFIT:
            default:
                return new Color(231, 76, 60);   // Rot
        }
    }

    /** Unveränderte, zuletzt über {@link #setData(List)} gesetzte Messdaten (Hauptgröße - einzige,
     *  die Zoom, Freihand-Auswahl, Fit und Chi² einbezieht). */
    private List<double[]> originalData = new ArrayList<>();
    /** Aktuell angezeigte (ggf. per Rubber-Band-Auswahl zugeschnittene) Teilmenge der Messdaten. */
    private List<double[]> displayData = new ArrayList<>();

    /** Eine zusätzlich eingezeichnete Messgröße (z. B. Kanal B neben der Hauptgröße Kanal A),
     *  rein zur gleichzeitigen visuellen Darstellung - siehe {@link #setExtraSeries}. */
    public static final class Series {
        public final String label;
        public final Color color;
        public final List<double[]> data;

        public Series(String label, Color color, List<double[]> data) {
            this.label = label;
            this.color = color;
            this.data = (data != null) ? data : new ArrayList<>();
        }
    }

    /** Zusätzliche, gleichzeitig dargestellte Kurven (siehe {@link Series}). Nehmen nicht an
     *  Zoom, Freihand-Auswahl, Fit oder Chi² teil - das bleibt der Hauptgröße vorbehalten. */
    private List<Series> extraSeries = new ArrayList<>();

    private String xUnit = "s";
    private String yUnit = "Messwert";

    private boolean showPoints = true;
    private boolean showLine = false;
    private FitMode fitMode = FitMode.NONE;
    private int polynomialDegree = 2;

    private double zoomFactor = 1.0;
    private Point mousePoint = null;

    private Point dragStart = null;
    private Point dragEnd = null;
    private List<Point> freehandPoints = new ArrayList<>();
    private boolean isRightButtonDragging = false;
    private boolean rightClickTriggered = false;

    /** Klickfläche des kleinen "i"-Symbols neben der Chi²-Anzeige, wird bei jedem Zeichnen aktualisiert. */
    private Rectangle infoButtonBounds = new Rectangle();
    private double currentReducedChiSquare = 0.0;
    private int currentDegreesOfFreedom = 1;

    /** Angenommene (konstante) Standardabweichung der Messwerte, geht als sigma in Chi^2 ein. */
    private double standardDeviation = 0.07;

    // --- Fit-Cache ---
    // Die komplette Regression (inkl. der iterativen Sinus-Anpassung) ist teuer und muss nicht
    // bei jedem repaint() neu berechnet werden - z. B. nicht nur wegen einer Mausbewegung fürs
    // Fadenkreuz. Es wird nur neu gefittet, wenn sich Daten, Fit-Typ oder Polynomgrad tatsächlich
    // geändert haben. Die Standardabweichung beeinflusst nur Chi^2, nicht die Kurvenparameter
    // selbst, und löst deshalb bewusst KEINEN Refit aus.
    private boolean fitDirty = true;
    private FitResult cachedFit = null;
    private FitMode cachedFitModeUsed = null;
    private int cachedDegreeUsed = -1;

    /** Ergebnis einer Regression: die angepasste Funktion plus Anzahl ihrer freien Parameter. */
    private static class FitResult {
        final FunctionEvaluator func;
        final int paramCount;

        FitResult(FunctionEvaluator func, int paramCount) {
            this.func = func;
            this.paramCount = paramCount;
        }
    }

    /**
     * Fasst die für einen einzelnen Zeichendurchlauf benötigte Pixel- und Datenraum-Geometrie
     * zusammen (Panelgröße, Innenabstand, Plotfläche, sichtbarer Datenbereich inkl. Zoom).
     * Wird einmal pro {@link #paintComponent(Graphics)} berechnet und an alle Zeichenschritte
     * weitergereicht, damit sie konsistent dieselbe Koordinatenabbildung verwenden.
     */
    private static class PlotGeometry {
        final int width, height, padding, plotWidth, plotHeight;
        final double minX, maxX, minY, maxY, rangeX, rangeY, visibleMaxX;

        PlotGeometry(int width, int height, int padding, int plotWidth, int plotHeight,
                     double minX, double maxX, double minY, double maxY,
                     double rangeX, double rangeY, double visibleMaxX) {
            this.width = width;
            this.height = height;
            this.padding = padding;
            this.plotWidth = plotWidth;
            this.plotHeight = plotHeight;
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.rangeX = rangeX;
            this.rangeY = rangeY;
            this.visibleMaxX = visibleMaxX;
        }
    }

    /**
     * Erstellt das leere Diagramm-Panel und registriert die Maus-Interaktion für Zoom,
     * Freihand-Auswahl, Fadenkreuz sowie den Klick auf das Chi²-Info-Symbol.
     */
    public ChartPanel() {
        setBackground(Theme.BG);

        MouseAdapter mouseHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    dragStart = e.getPoint();
                    dragEnd = e.getPoint();
                    repaint();
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    isRightButtonDragging = true;
                    rightClickTriggered = false;
                    freehandPoints.clear();
                    freehandPoints.add(e.getPoint());
                    repaint();
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && dragStart != null) {
                    dragEnd = e.getPoint();
                    repaint();
                } else if (SwingUtilities.isRightMouseButton(e) && isRightButtonDragging) {
                    rightClickTriggered = true;
                    freehandPoints.add(e.getPoint());
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && dragStart != null && dragEnd != null) {
                    applySelectionZoom(dragStart, dragEnd);
                    dragStart = null;
                    dragEnd = null;
                    repaint();
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    isRightButtonDragging = false;
                    if (rightClickTriggered && freehandPoints.size() > 2) {
                        applyFreehandSelection(freehandPoints);
                    } else {
                        resetZoom();
                    }
                    freehandPoints.clear();
                    repaint();
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                mousePoint = e.getPoint();
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                mousePoint = null;
                repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (fitMode != FitMode.NONE && infoButtonBounds.contains(e.getPoint())) {
                    showChiSquareInfoDialog();
                }
            }
        };

        addMouseMotionListener(mouseHandler);
        addMouseListener(mouseHandler);
    }

    /**
     * Setzt die anzuzeigenden Messdaten komplett neu (z. B. nach einem CSV-Import oder -
     * perspektivisch - nach dem Empfang neuer Live-Messwerte). Setzt Zoom-Auswahl und
     * Fit-Cache zurück.
     *
     * @param data Liste von (Zeit, Messwert)-Paaren, {@code null} wird als leere Liste behandelt
     */
    public void setData(List<double[]> data) {
        this.originalData = (data != null) ? new ArrayList<>(data) : new ArrayList<>();
        this.displayData = new ArrayList<>(this.originalData);
        fitDirty = true;
        repaint();
    }

    /**
     * Setzt zusätzliche, gleichzeitig darzustellende Kurven (z. B. Kanal B neben der über
     * {@link #setData(List)} gesetzten Hauptgröße von Kanal A). Diese Kurven sind rein visuell:
     * sie beeinflussen weder Zoom noch Freihand-Auswahl, Fit oder Chi² - das bleibt exklusiv der
     * Hauptgröße vorbehalten, damit die Ausgleichsrechnung eindeutig bleibt.
     *
     * @param series Liste zusätzlicher Kurven, {@code null} wird als leere Liste behandelt
     */
    public void setExtraSeries(List<Series> series) {
        this.extraSeries = (series != null) ? new ArrayList<>(series) : new ArrayList<>();
        repaint();
    }

    /**
     * Legt die Achsenbeschriftungen fest.
     *
     * @param xUnit Einheit der X-Achse (z. B. "s"), {@code null} fällt auf "s" zurück
     * @param yUnit Einheit/Bezeichnung der Y-Achse; wird automatisch in "Messwert (Einheit)"
     *              eingebettet, sofern noch nicht in dieser Form vorliegt
     */
    public void setUnits(String xUnit, String yUnit) {
        this.xUnit = (xUnit != null) ? xUnit : "s";

        if (yUnit == null || yUnit.trim().isEmpty()) {
            this.yUnit = "Messwert";
        } else if (!yUnit.contains("Messwert") && !yUnit.contains("(")) {
            this.yUnit = "Messwert (" + yUnit.trim() + ")";
        } else {
            this.yUnit = yUnit.trim();
        }
        repaint();
    }

    /** @param showPoints ob die einzelnen Messpunkte als Kreise gezeichnet werden sollen */
    public void setShowPoints(boolean showPoints) {
        this.showPoints = showPoints;
        repaint();
    }

    /** @param showLine ob die Messpunkte zusätzlich durch eine Verbindungslinie verbunden werden sollen */
    public void setShowLine(boolean showLine) {
        this.showLine = showLine;
        repaint();
    }

    /**
     * Wählt das anzuzeigende Regressionsmodell aus und markiert den Fit-Cache als veraltet,
     * sodass beim nächsten Zeichnen neu gefittet wird.
     *
     * @param fitMode das gewünschte Fit-Modell, oder {@link FitMode#NONE} für keinen Fit
     */
    public void setFitMode(FitMode fitMode) {
        this.fitMode = fitMode;
        fitDirty = true;
        repaint();
    }

    /**
     * Legt den Polynomgrad für {@link FitMode#POLYNOMIAL} fest (wird auf 1..10 begrenzt)
     * und markiert den Fit-Cache als veraltet.
     *
     * @param degree gewünschter Polynomgrad
     */
    public void setPolynomialDegree(int degree) {
        this.polynomialDegree = Math.max(1, Math.min(10, degree));
        fitDirty = true;
        repaint();
    }

    /**
     * Legt die angenommene (konstante) Messunsicherheit sigma fest, die in die
     * Chi²-Berechnung und die Breite des Toleranzbands um die Fit-Kurve eingeht.
     * Beeinflusst nur Chi² (sigma steht als konstanter Faktor in der Summe), nicht die
     * Kurvenparameter selbst - löst deshalb bewusst KEINEN Refit aus, nur ein repaint().
     *
     * @param standardDeviation neue Standardabweichung, wird auf mindestens 1e-6 begrenzt
     */
    public void setStandardDeviation(double standardDeviation) {
        this.standardDeviation = Math.max(1e-6, standardDeviation);
        repaint();
    }

    public double getStandardDeviation() { return standardDeviation; }
    public int getPolynomialDegree() { return polynomialDegree; }

    public void zoomIn() {
        zoomFactor *= 1.2;
        repaint();
    }

    /** Verkleinert den angezeigten Ausschnitt um den Faktor 1.2 (Mindestfaktor 0.1). */
    public void zoomOut() {
        zoomFactor /= 1.2;
        if (zoomFactor < 0.1) zoomFactor = 0.1;
        repaint();
    }

    /** Setzt Zoom und Rubber-Band-Auswahl zurück auf die vollständigen Messdaten. */
    public void resetZoom() {
        zoomFactor = 1.0;
        displayData = new ArrayList<>(originalData);
        fitDirty = true;
        repaint();
    }

    /** Wertet eine per linker Maustaste gezogene Rubber-Band-Auswahl aus. */
    private void applySelectionZoom(Point p1, Point p2) {
        int padding = 65;
        int width = getWidth();
        int height = getHeight();

        int plotWidth = width - 2 * padding;
        int plotHeight = height - 2 * padding;

        if (plotWidth <= 0 || plotHeight <= 0 || originalData == null || originalData.isEmpty()) return;

        int rectX = Math.min(p1.x, p2.x);
        int rectY = Math.min(p1.y, p2.y);
        int rectW = Math.abs(p1.x - p2.x);
        int rectH = Math.abs(p1.y - p2.y);

        if (rectW < 10 || rectH < 10) return;

        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        for (double[] point : displayData) {
            if (point[0] < minX) minX = point[0];
            if (point[0] > maxX) maxX = point[0];
            if (point[1] < minY) minY = point[1];
            if (point[1] > maxY) maxY = point[1];
        }
        for (Series series : extraSeries) {
            for (double[] point : series.data) {
                if (point[0] < minX) minX = point[0];
                if (point[0] > maxX) maxX = point[0];
                if (point[1] < minY) minY = point[1];
                if (point[1] > maxY) maxY = point[1];
            }
        }

        if (minX == maxX) maxX = minX + 1.0;
        if (minY == maxY) { minY -= 1.0; maxY += 1.0; }

        double rangeX = (maxX - minX) / zoomFactor;
        double rangeY = (maxY - minY) / zoomFactor;

        double selMinX = minX + ((double) (rectX - padding) / plotWidth) * rangeX;
        double selMaxX = minX + ((double) (rectX + rectW - padding) / plotWidth) * rangeX;

        double selMaxY = minY + ((double) ((height - padding) - rectY) / plotHeight) * rangeY;
        double selMinY = minY + ((double) ((height - padding) - (rectY + rectH)) / plotHeight) * rangeY;

        List<double[]> filtered = new ArrayList<>();
        for (double[] pt : displayData) {
            if (pt[0] >= selMinX && pt[0] <= selMaxX && pt[1] >= selMinY && pt[1] <= selMaxY) {
                filtered.add(pt);
            }
        }

        if (filtered.size() >= 2) {
            displayData = filtered;
            zoomFactor = 1.0;
            fitDirty = true;
        }
    }

    /** Wertet eine per rechter Maustaste gezogene Freihand-Linie aus und filtert die Punkte. */
    private void applyFreehandSelection(List<Point> strokePoints) {
        if (originalData == null || originalData.isEmpty() || strokePoints.size() < 3) return;

        PlotGeometry geo = computePlotGeometry();
        if (geo == null) return;

        Path2D polygonPath = new Path2D.Double();
        polygonPath.moveTo(strokePoints.get(0).x, strokePoints.get(0).y);
        for (int i = 1; i < strokePoints.size(); i++) {
            polygonPath.lineTo(strokePoints.get(i).x, strokePoints.get(i).y);
        }
        polygonPath.closePath();

        List<double[]> filtered = new ArrayList<>();
        for (double[] point : originalData) {
            double px = geo.padding + ((point[0] - geo.minX) / geo.rangeX) * geo.plotWidth;
            double py = (geo.height - geo.padding) - ((point[1] - geo.minY) / geo.rangeY) * geo.plotHeight;
            if (polygonPath.contains(px, py)) {
                filtered.add(point);
            }
        }

        if (filtered.size() >= 2) {
            displayData = filtered;
            zoomFactor = 1.0;
            fitDirty = true;
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        PlotGeometry geo = computePlotGeometry();
        if (geo == null) {
            g2.dispose();
            return;
        }

        drawGridAndAxes(g2, geo);

        if (displayData == null || displayData.isEmpty()) {
            drawEmptyDataMessage(g2, geo);
            drawCrosshair(g2, geo.padding, geo.height, geo.plotWidth, geo.plotHeight, geo.minX, geo.rangeX, geo.minY, geo.rangeY);
            g2.dispose();
            return;
        }

        List<Point2DDouble> screenPoints = projectDataToScreen(geo);

        if (showLine && screenPoints.size() > 1) {
            drawConnectingLine(g2, screenPoints);
        }

        drawFitOverlayClipped(g2, geo);

        if (showPoints) {
            drawDataPoints(g2, geo, screenPoints);
        }

        drawExtraSeries(g2, geo);

        if (fitMode != FitMode.NONE) {
            drawChiSquareOverlay(g2, geo.width, geo.padding);
        }

        drawLegend(g2, geo);

        drawSelectionRectangle(g2);
        drawFreehandStroke(g2);

        drawCrosshair(g2, geo.padding, geo.height, geo.plotWidth, geo.plotHeight, geo.minX, geo.rangeX, geo.minY, geo.rangeY);
        g2.dispose();
    }

    /**
     * Zeichnet zusätzliche, gleichzeitig dargestellte Kurven (siehe {@link #setExtraSeries}) in
     * ihrer jeweils eigenen Farbe - als Punkte und (falls aktiviert) Verbindungslinie, auf
     * derselben (die Extra-Kurven bereits einschließenden) Achsenskalierung wie die Hauptgröße.
     */
    private void drawExtraSeries(Graphics2D g2, PlotGeometry geo) {
        double pointSize = 6;
        for (Series series : extraSeries) {
            List<Point2DDouble> points = new ArrayList<>();
            for (double[] point : series.data) {
                double px = geo.padding + ((point[0] - geo.minX) / geo.rangeX) * geo.plotWidth;
                double py = (geo.height - geo.padding) - ((point[1] - geo.minY) / geo.rangeY) * geo.plotHeight;
                points.add(new Point2DDouble(px, py));
            }

            if (showLine && points.size() > 1) {
                g2.setColor(series.color);
                g2.setStroke(new BasicStroke(1.5f));
                Path2D path = new Path2D.Double();
                path.moveTo(points.get(0).x, points.get(0).y);
                for (int i = 1; i < points.size(); i++) {
                    path.lineTo(points.get(i).x, points.get(i).y);
                }
                g2.draw(path);
            }

            if (showPoints) {
                g2.setColor(series.color);
                for (Point2DDouble pt : points) {
                    if (pt.x >= geo.padding && pt.x <= geo.width - geo.padding
                            && pt.y >= geo.padding && pt.y <= geo.height - geo.padding) {
                        g2.fill(new Ellipse2D.Double(pt.x - pointSize / 2, pt.y - pointSize / 2, pointSize, pointSize));
                    }
                }
            }
        }
    }

    /**
     * Zeichnet eine kleine Legende (Farbe → Messgröße) oben rechts im Plot, sofern mehr als
     * eine Größe gleichzeitig dargestellt wird (Hauptgröße + mind. eine Extra-Kurve).
     */
    private void drawLegend(Graphics2D g2, PlotGeometry geo) {
        if (extraSeries.isEmpty()) return;

        g2.setFont(new Font("SansSerif", Font.PLAIN, 11));
        FontMetrics fm = g2.getFontMetrics();

        List<String> labels = new ArrayList<>();
        List<Color> colors = new ArrayList<>();
        labels.add(yUnit);
        colors.add(Theme.POINT);
        for (Series series : extraSeries) {
            labels.add(series.label);
            colors.add(series.color);
        }

        int swatch = 10;
        int rowHeight = 16;
        int maxTextWidth = 0;
        for (String label : labels) {
            maxTextWidth = Math.max(maxTextWidth, fm.stringWidth(label));
        }

        int boxWidth = swatch + 6 + maxTextWidth + 10;
        int boxHeight = labels.size() * rowHeight + 8;
        int boxX = geo.width - geo.padding - boxWidth - 6;
        int boxY = geo.padding + 6;

        g2.setColor(Theme.PANEL);
        g2.fillRoundRect(boxX, boxY, boxWidth, boxHeight, 8, 8);
        g2.setColor(Theme.BORDER);
        g2.drawRoundRect(boxX, boxY, boxWidth, boxHeight, 8, 8);

        for (int i = 0; i < labels.size(); i++) {
            int rowY = boxY + 6 + i * rowHeight;
            g2.setColor(colors.get(i));
            g2.fillRect(boxX + 6, rowY, swatch, swatch);
            g2.setColor(Theme.TEXT);
            g2.drawString(labels.get(i), boxX + 6 + swatch + 6, rowY + swatch);
        }
    }

    /**
     * Berechnet Panelgröße, Innenabstand, Plotfläche und den (durch Zoom skalierten)
     * sichtbaren Datenbereich für den aktuellen Zeichendurchlauf.
     *
     * @return die Plot-Geometrie, oder {@code null} wenn das Panel aktuell zu klein ist,
     *         um überhaupt etwas zu zeichnen
     */
    private PlotGeometry computePlotGeometry() {
        int width = getWidth();
        int height = getHeight();
        int padding = 65;

        int plotWidth = width - 2 * padding;
        int plotHeight = height - 2 * padding;

        if (plotWidth <= 0 || plotHeight <= 0) return null;

        double minX = 0, maxX = 10;
        double minY = 0, maxY = 10;

        if (displayData != null && !displayData.isEmpty()) {
            minX = Double.MAX_VALUE; maxX = -Double.MAX_VALUE;
            minY = Double.MAX_VALUE; maxY = -Double.MAX_VALUE;

            for (double[] point : displayData) {
                if (point[0] < minX) minX = point[0];
                if (point[0] > maxX) maxX = point[0];
                if (point[1] < minY) minY = point[1];
                if (point[1] > maxY) maxY = point[1];
            }
            for (Series series : extraSeries) {
                for (double[] point : series.data) {
                    if (point[0] < minX) minX = point[0];
                    if (point[0] > maxX) maxX = point[0];
                    if (point[1] < minY) minY = point[1];
                    if (point[1] > maxY) maxY = point[1];
                }
            }
            if (minX == maxX) maxX = minX + 1.0;
            if (minY == maxY) { minY -= 1.0; maxY += 1.0; }
        }

        double rangeX = (maxX - minX) / zoomFactor;
        double rangeY = (maxY - minY) / zoomFactor;
        double visibleMaxX = minX + rangeX;

        return new PlotGeometry(width, height, padding, plotWidth, plotHeight,
                minX, maxX, minY, maxY, rangeX, rangeY, visibleMaxX);
    }

    /**
     * Zeichnet das Hintergrundgitter, die Achsenlinien, die Tick-Beschriftungen und die
     * Achsentitel ("Zeit (Einheit)" / Messwert-Einheit).
     *
     * @param g2  Ziel-Grafikkontext
     * @param geo aktuelle Plot-Geometrie
     */
    private void drawGridAndAxes(Graphics2D g2, PlotGeometry geo) {
        int padding = geo.padding;
        int height = geo.height;
        int width = geo.width;
        int plotWidth = geo.plotWidth;
        int plotHeight = geo.plotHeight;

        g2.setColor(Theme.BORDER);
        g2.setStroke(new BasicStroke(1.0f));
        int gridDivisions = 5;

        for (int i = 0; i <= gridDivisions; i++) {
            double ratio = (double) i / gridDivisions;
            int x = padding + (int) (ratio * plotWidth);
            double valX = geo.minX + ratio * geo.rangeX;
            g2.drawLine(x, padding, x, height - padding);
            g2.setColor(Theme.TEXT);
            g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
            g2.drawString(String.format("%.2f", valX), x - 12, height - padding + 20);
            g2.setColor(Theme.BORDER);
        }

        for (int i = 0; i <= gridDivisions; i++) {
            double ratio = (double) i / gridDivisions;
            int y = (height - padding) - (int) (ratio * plotHeight);
            double valY = geo.minY + ratio * geo.rangeY;
            g2.drawLine(padding, y, width - padding, y);
            g2.setColor(Theme.TEXT);
            g2.drawString(String.format("%.2f", valY), padding - 50, y + 4);
            g2.setColor(Theme.BORDER);
        }

        g2.setColor(Theme.TEXT);
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawLine(padding, height - padding, width - padding, height - padding);
        g2.drawLine(padding, padding, padding, height - padding);

        g2.setFont(new Font("SansSerif", Font.BOLD, 11));
        g2.drawString("Zeit (" + xUnit + ")", width - padding - 40, height - padding + 35);
        g2.drawString(yUnit, padding - 50, padding - 15);
    }

    /**
     * Zeigt den Platzhaltertext an, solange keine Messdaten vorliegen.
     *
     * @param g2  Ziel-Grafikkontext
     * @param geo aktuelle Plot-Geometrie
     */
    private void drawEmptyDataMessage(Graphics2D g2, PlotGeometry geo) {
        g2.setColor(Theme.TEXT);
        g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g2.drawString("Keine Messdaten vorhanden", geo.width / 2 - 70, geo.height / 2);
    }

    /**
     * Projiziert alle Punkte aus {@link #displayData} (Datenraum) in Bildschirmkoordinaten.
     *
     * @param geo aktuelle Plot-Geometrie
     * @return Bildschirmpunkte in derselben Reihenfolge wie {@link #displayData}
     */
    private List<Point2DDouble> projectDataToScreen(PlotGeometry geo) {
        List<Point2DDouble> points = new ArrayList<>();
        for (double[] point : displayData) {
            double px = geo.padding + ((point[0] - geo.minX) / geo.rangeX) * geo.plotWidth;
            double py = (geo.height - geo.padding) - ((point[1] - geo.minY) / geo.rangeY) * geo.plotHeight;
            points.add(new Point2DDouble(px, py));
        }
        return points;
    }

    /**
     * Zeichnet die (optionale) Verbindungslinie zwischen aufeinanderfolgenden Messpunkten.
     *
     * @param g2     Ziel-Grafikkontext
     * @param points bereits in Bildschirmkoordinaten projizierte Messpunkte
     */
    private void drawConnectingLine(Graphics2D g2, List<Point2DDouble> points) {
        g2.setColor(Theme.ACCENT);
        g2.setStroke(new BasicStroke(1.5f));
        Path2D path = new Path2D.Double();
        path.moveTo(points.get(0).x, points.get(0).y);
        for (int i = 1; i < points.size(); i++) {
            path.lineTo(points.get(i).x, points.get(i).y);
        }
        g2.draw(path);
    }

    /**
     * Zeichnet die Fit-Kurve samt Toleranzband für den aktuell gewählten {@link #fitMode},
     * beschränkt auf die Plotfläche (Clip wird davor gesetzt und danach wiederhergestellt).
     * Ruft bei Bedarf {@link #ensureFitComputed(FitMode, int)} auf, um den zwischengespeicherten
     * Fit auf dem neuesten Stand zu halten, und aktualisiert anschließend Chi².
     *
     * @param g2  Ziel-Grafikkontext
     * @param geo aktuelle Plot-Geometrie
     */
    private void drawFitOverlayClipped(Graphics2D g2, PlotGeometry geo) {
        Shape originalClip = g2.getClip();
        g2.clipRect(geo.padding, geo.padding, geo.plotWidth, geo.plotHeight);

        if (fitMode == FitMode.LINEAR || fitMode == FitMode.POLYNOMIAL) {
            int degree = (fitMode == FitMode.LINEAR) ? 1 : polynomialDegree;
            if (displayData.size() >= (degree + 1)) {
                ensureFitComputed(fitMode, degree);
                drawCachedFitIfPresent(g2, geo);
            }
        } else if (fitMode == FitMode.SINUS && displayData.size() >= 4) {
            ensureFitComputed(fitMode, 0);
            drawCachedFitIfPresent(g2, geo);
        } else if (fitMode == FitMode.EXPONENTIAL && displayData.size() >= 2) {
            ensureFitComputed(fitMode, 0);
            drawCachedFitIfPresent(g2, geo);
        }

        g2.setClip(originalClip);
    }

    /**
     * Berechnet Chi² für den zwischengespeicherten Fit neu (billig, hängt von der aktuellen
     * Standardabweichung ab) und zeichnet die Fit-Kurve samt Toleranzband, sofern ein
     * gültiger Fit vorliegt.
     *
     * @param g2  Ziel-Grafikkontext
     * @param geo aktuelle Plot-Geometrie
     */
    private void drawCachedFitIfPresent(Graphics2D g2, PlotGeometry geo) {
        if (cachedFit == null) return;
        calculateChiSquare(cachedFit.func, cachedFit.paramCount);
        drawFunctionPathWithTolerance(g2, cachedFit.func, geo.minX, geo.visibleMaxX, geo.minY,
                geo.rangeX, geo.rangeY, geo.padding, geo.height, geo.plotWidth, geo.plotHeight, Theme.ACCENT);
    }

    /**
     * Zeichnet alle sichtbaren Messpunkte als kleine Kreise.
     *
     * @param g2     Ziel-Grafikkontext
     * @param geo    aktuelle Plot-Geometrie
     * @param points bereits in Bildschirmkoordinaten projizierte Messpunkte
     */
    private void drawDataPoints(Graphics2D g2, PlotGeometry geo, List<Point2DDouble> points) {
        double pointSize = 7;
        for (Point2DDouble pt : points) {
            if (pt.x >= geo.padding && pt.x <= geo.width - geo.padding
                    && pt.y >= geo.padding && pt.y <= geo.height - geo.padding) {
                g2.setColor(Theme.POINT);
                g2.fill(new Ellipse2D.Double(pt.x - pointSize / 2, pt.y - pointSize / 2, pointSize, pointSize));
            }
        }
    }

    /**
     * Zeichnet das halbtransparente Auswahlrechteck während einer laufenden
     * Rubber-Band-Zoom-Auswahl (falls der Nutzer gerade zieht).
     *
     * @param g2 Ziel-Grafikkontext
     */
    private void drawSelectionRectangle(Graphics2D g2) {
        if (dragStart == null || dragEnd == null) return;

        int rectX = Math.min(dragStart.x, dragEnd.x);
        int rectY = Math.min(dragStart.y, dragEnd.y);
        int rectW = Math.abs(dragStart.x - dragEnd.x);
        int rectH = Math.abs(dragStart.y - dragEnd.y);

        g2.setColor(new Color(Theme.ACCENT.getRed(), Theme.ACCENT.getGreen(), Theme.ACCENT.getBlue(), 50));
        g2.fillRect(rectX, rectY, rectW, rectH);
        g2.setColor(Theme.ACCENT);
        g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{4.0f}, 0));
        g2.drawRect(rectX, rectY, rectW, rectH);
    }

    private void drawFreehandStroke(Graphics2D g2) {
        if (freehandPoints.isEmpty()) return;

        Path2D path = new Path2D.Double();
        path.moveTo(freehandPoints.get(0).x, freehandPoints.get(0).y);
        for (int i = 1; i < freehandPoints.size(); i++) {
            path.lineTo(freehandPoints.get(i).x, freehandPoints.get(i).y);
        }

        g2.setColor(new Color(Theme.ACCENT.getRed(), Theme.ACCENT.getGreen(), Theme.ACCENT.getBlue(), 50));
        g2.fill(path);
        g2.setColor(Theme.ACCENT);
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{4.0f}, 0));
        g2.draw(path);
    }

    private void ensureFitComputed(FitMode mode, int degree) {
        if (!fitDirty && cachedFit != null && cachedFitModeUsed == mode && cachedDegreeUsed == degree) return;

        switch (mode) {
            case LINEAR:
            case POLYNOMIAL:
                cachedFit = computePolynomialFit(degree);
                break;
            case SINUS:
                cachedFit = computeSinusFit();
                break;
            case EXPONENTIAL:
                cachedFit = computeExpFit();
                break;
            default:
                cachedFit = null;
        }

        cachedFitModeUsed = mode;
        cachedDegreeUsed = degree;
        fitDirty = false;
    }

    /**
     * Zeichnet die kleine Chi²-Anzeige mit farbigem Status-Icon oben rechts im Plot und
     * merkt sich dessen Klickfläche in {@link #infoButtonBounds} für {@link #showChiSquareInfoDialog()}.
     *
     * @param g2      Ziel-Grafikkontext
     * @param width   Panelbreite
     * @param padding Innenabstand des Plots
     */
    private void drawChiSquareOverlay(Graphics2D g2, int width, int padding) {
        String chiText = String.format("χ²_red = %.4f", currentReducedChiSquare);
        g2.setFont(new Font("SansSerif", Font.BOLD, 12));
        FontMetrics fm = g2.getFontMetrics();

        int textWidth = fm.stringWidth(chiText);
        int iconSize = 16;
        int totalWidth = textWidth + iconSize + 16;
        int boxHeight = 26;

        int boxX = width - padding - totalWidth - 5;
        int boxY = padding + 5;

        Color statusColor = getChiSquareColor(currentReducedChiSquare);

        g2.setColor(Theme.PANEL);
        g2.fillRoundRect(boxX, boxY, totalWidth, boxHeight, 8, 8);
        g2.setColor(Theme.BORDER);
        g2.drawRoundRect(boxX, boxY, totalWidth, boxHeight, 8, 8);

        g2.setColor(statusColor);
        g2.drawString(chiText, boxX + 8, boxY + 17);

        int iconX = boxX + textWidth + 10;
        int iconY = boxY + 5;
        infoButtonBounds = new Rectangle(iconX, iconY, iconSize, iconSize);

        g2.setColor(statusColor);
        g2.fillOval(iconX, iconY, iconSize, iconSize);

        g2.setColor(Theme.BG);
        g2.setFont(new Font("Serif", Font.BOLD | Font.ITALIC, 11));
        g2.drawString("i", iconX + 6, iconY + 12);
    }

    /** Öffnet den Detail-Dialog mit einer Erklärung des aktuellen Chi²-Gütewerts. */
    private void showChiSquareInfoDialog() {
        Window parentWindow = SwingUtilities.getWindowAncestor(this);
        ChiSquareInfoDialog dialog = new ChiSquareInfoDialog(parentWindow, currentReducedChiSquare, currentDegreesOfFreedom);
        dialog.setVisible(true);
    }

    /**
     * Berechnet das reduzierte Chi-Quadrat für eine gegebene Fit-Funktion:
     * chi²_red = (1 / DOF) * Summe((y_i - f(x_i))² / sigma²), DOF = n - Parameteranzahl.
     * Aktualisiert {@link #currentReducedChiSquare} und {@link #currentDegreesOfFreedom}.
     *
     * @param func           die angepasste Funktion
     * @param parameterCount Anzahl der freien Parameter des Modells (für die Freiheitsgrade)
     */
    private void calculateChiSquare(FunctionEvaluator func, int parameterCount) {
        int n = displayData.size();
        int dof = n - parameterCount;

        if (dof <= 0) {
            this.currentReducedChiSquare = 0.0;
            this.currentDegreesOfFreedom = 1;
            return;
        }

        double sigma = this.standardDeviation;
        double sigmaSq = sigma * sigma;

        double sumChiSq = 0;
        for (double[] pt : displayData) {
            double yExp = func.eval(pt[0]);
            double diff = pt[1] - yExp;
            sumChiSq += (diff * diff) / sigmaSq;
        }

        this.currentDegreesOfFreedom = dof;
        this.currentReducedChiSquare = sumChiSq / dof;
    }

    /**
     * Führt eine polynomiale Ausgleichsrechnung (kleinste Quadrate) über {@link #displayData}
     * durch. Die X-Werte werden vor dem Aufstellen der Normalgleichungen um ihren Mittelwert
     * zentriert, was die Kondition des Gleichungssystems deutlich verbessert (insbesondere bei
     * höheren Polynomgraden).
     *
     * @param degree Polynomgrad (1 = linear)
     * @return das Fit-Ergebnis, oder {@code null} falls das Gleichungssystem singulär ist
     */
    private FitResult computePolynomialFit(int degree) {
        int n = displayData.size();
        int m = degree + 1;

        double meanX = 0;
        for (double[] pt : displayData) meanX += pt[0];
        meanX /= n;

        double[][] A = new double[m][m];
        double[] B = new double[m];

        for (double[] pt : displayData) {
            double xCentered = pt[0] - meanX;
            double y = pt[1];

            double[] xPowers = new double[2 * m];
            xPowers[0] = 1.0;
            for (int k = 1; k < 2 * m; k++) xPowers[k] = xPowers[k - 1] * xCentered;

            for (int row = 0; row < m; row++) {
                for (int col = 0; col < m; col++) A[row][col] += xPowers[row + col];
                B[row] += y * xPowers[row];
            }
        }

        double[] coeffCentered = solveGaussian(A, B);
        if (coeffCentered == null) return null;

        final double finalMeanX = meanX;
        FunctionEvaluator func = x -> {
            double xc = x - finalMeanX;
            double val = 0;
            double p = 1.0;
            for (int i = 0; i < coeffCentered.length; i++) {
                val += coeffCentered[i] * p;
                p *= xc;
            }
            return val;
        };

        return new FitResult(func, m);
    }

    /**
     * Führt eine exponentielle Ausgleichsrechnung f(x) = a * exp(b*x) durch, indem der Fit im
     * logarithmierten Raum (ln(y) = ln(a) + b*x) linear gelöst wird. Punkte mit y &lt;= 0 werden
     * übersprungen, da der Logarithmus dort nicht definiert ist.
     *
     * <p>Hinweis: Dies minimiert die Fehlerquadrate im logarithmischen Raum, nicht im
     * Originalraum - ein Standard-Vorgehen, das große y-Werte gegenüber einem echten
     * nichtlinearen Fit tendenziell unterschätzt gewichtet. Für die meisten praktischen Zwecke
     * ausreichend genau.</p>
     *
     * @return das Fit-Ergebnis, oder {@code null} bei weniger als 2 positiven Messwerten
     */
    private FitResult computeExpFit() {
        double meanX = 0;
        int count = 0;
        for (double[] pt : displayData) {
            if (pt[1] > 0) {
                meanX += pt[0];
                count++;
            }
        }
        if (count < 2) return null;
        meanX /= count;

        double sumX = 0, sumLnY = 0, sumXLnY = 0, sumX2 = 0;

        for (double[] pt : displayData) {
            if (pt[1] > 0) {
                double xc = pt[0] - meanX;
                double lnY = Math.log(pt[1]);
                sumX += xc;
                sumLnY += lnY;
                sumXLnY += xc * lnY;
                sumX2 += xc * xc;
            }
        }

        double denom = (count * sumX2 - sumX * sumX);
        if (Math.abs(denom) <= 1e-9) return null;

        double b = (count * sumXLnY - sumX * sumLnY) / denom;
        double a = Math.exp((sumLnY - b * sumX) / count);

        final double finalMeanX = meanX;
        FunctionEvaluator func = x -> a * Math.exp(b * (x - finalMeanX));

        return new FitResult(func, 2);
    }

    /**
     * Schätzt Startwerte für eine sinusförmige Anpassung (Amplitude aus dem Wertebereich,
     * Offset aus dem Mittelwert, Kreisfrequenz aus dem Abstand der Nulldurchgänge, Phase aus
     * dem ersten Datenpunkt) und verfeinert sie anschließend nichtlinear über
     * {@link #refineSinusFit(List, double, double, double, double)}.
     *
     * @return das Fit-Ergebnis (4 Parameter: Amplitude, Kreisfrequenz, Phase, Offset),
     *         oder {@code null} bei weniger als 4 Datenpunkten
     */
    private FitResult computeSinusFit() {
        if (displayData.size() < 4) return null;

        double minYVal = Double.MAX_VALUE;
        double maxYVal = -Double.MAX_VALUE;
        double sumY = 0;

        for (double[] pt : displayData) {
            sumY += pt[1];
            if (pt[1] < minYVal) minYVal = pt[1];
            if (pt[1] > maxYVal) maxYVal = pt[1];
        }
        double offset = sumY / displayData.size();
        double amplitude = (maxYVal - minYVal) / 2.0;

        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        for (double[] pt : displayData) {
            if (pt[0] < minX) minX = pt[0];
            if (pt[0] > maxX) maxX = pt[0];
        }

        int zeroCrossings = 0;
        double firstCrossingX = 0, lastCrossingX = 0;

        for (int i = 0; i < displayData.size() - 1; i++) {
            double y1 = displayData.get(i)[1] - offset;
            double y2 = displayData.get(i + 1)[1] - offset;

            if (y1 * y2 < 0) {
                double x1 = displayData.get(i)[0];
                double x2 = displayData.get(i + 1)[0];
                double xCross = x1 + (0 - y1) * (x2 - x1) / (y2 - y1);

                if (zeroCrossings == 0) firstCrossingX = xCross;
                lastCrossingX = xCross;
                zeroCrossings++;
            }
        }

        double omega;
        if (zeroCrossings >= 2 && lastCrossingX > firstCrossingX) {
            double halfPeriods = zeroCrossings - 1;
            double totalDist = lastCrossingX - firstCrossingX;
            double period = (2.0 * totalDist) / halfPeriods;
            omega = (2 * Math.PI) / period;
        } else {
            omega = (2 * Math.PI) / Math.max(0.001, (maxX - minX));
        }

        double firstX = displayData.get(0)[0];
        double firstYNorm = (displayData.get(0)[1] - offset) / (amplitude > 0 ? amplitude : 1.0);
        firstYNorm = Math.max(-1.0, Math.min(1.0, firstYNorm));
        double phi = Math.asin(firstYNorm) - omega * firstX;

        double[] params = refineSinusFit(displayData, amplitude, omega, phi, offset);
        double finalA = params[0];
        double finalW = params[1];
        double finalPhi = params[2];
        double finalC = params[3];

        FunctionEvaluator func = x -> finalA * Math.sin(finalW * x + finalPhi) + finalC;

        return new FitResult(func, 4);
    }

    /**
     * Nichtlineare Ausgleichsrechnung für die Sinus-Anpassung mittels gedämpftem Gauss-Newton
     * (Levenberg-Marquardt-artig): Ein Schritt wird nur übernommen, wenn er die
     * Fehlerquadratsumme tatsächlich verringert; andernfalls wird der Dämpfungsfaktor lambda
     * erhöht (kleinerer, vorsichtigerer Schritt), statt bei einem ungünstigen Startwert zu
     * divergieren.
     *
     * @param data Datenpunkte, auf die gefittet wird
     * @param A    Startwert der Amplitude
     * @param w    Startwert der Kreisfrequenz
     * @param phi  Startwert der Phase
     * @param C    Startwert des Offsets
     * @return die verfeinerten Parameter {A, w, phi, C}
     */
    private double[] refineSinusFit(List<double[]> data, double A, double w, double phi, double C) {
        double[] p = new double[]{A, w, phi, C};
        int maxIter = 50;
        double lambda = 1e-3;
        double prevCost = sinusCost(data, p);

        for (int iter = 0; iter < maxIter; iter++) {
            double[][] J = new double[data.size()][4];
            double[] r = new double[data.size()];

            for (int i = 0; i < data.size(); i++) {
                double x = data.get(i)[0];
                double y = data.get(i)[1];

                double arg = p[1] * x + p[2];
                double sinVal = Math.sin(arg);
                double cosVal = Math.cos(arg);

                double yModel = p[0] * sinVal + p[3];
                r[i] = y - yModel;

                J[i][0] = sinVal;
                J[i][1] = p[0] * x * cosVal;
                J[i][2] = p[0] * cosVal;
                J[i][3] = 1.0;
            }

            double[][] JTJ = new double[4][4];
            double[] JTr = new double[4];

            for (int i = 0; i < data.size(); i++) {
                for (int row = 0; row < 4; row++) {
                    for (int col = 0; col < 4; col++) JTJ[row][col] += J[i][row] * J[i][col];
                    JTr[row] += J[i][row] * r[i];
                }
            }

            double[][] JTJdamped = new double[4][4];
            for (int row = 0; row < 4; row++) {
                System.arraycopy(JTJ[row], 0, JTJdamped[row], 0, 4);
                JTJdamped[row][row] += lambda * JTJ[row][row];
            }

            double[] dp = solveGaussian(JTJdamped, JTr);
            if (dp == null) {
                lambda *= 10;
                if (lambda > 1e8) break;
                continue;
            }

            double[] pTrial = new double[]{p[0] + dp[0], p[1] + dp[1], p[2] + dp[2], p[3] + dp[3]};
            double trialCost = sinusCost(data, pTrial);

            if (trialCost < prevCost) {
                boolean converged = Math.abs(dp[0]) < 1e-8 && Math.abs(dp[1]) < 1e-8
                        && Math.abs(dp[2]) < 1e-8 && Math.abs(dp[3]) < 1e-8;
                p = pTrial;
                prevCost = trialCost;
                lambda = Math.max(lambda / 10.0, 1e-10);
                if (converged) break;
            } else {
                lambda *= 10;
                if (lambda > 1e8) break;
            }
        }

        return p;
    }

    /**
     * Berechnet die Fehlerquadratsumme (nicht durch sigma normiert) der Sinus-Anpassung für
     * einen gegebenen Parametersatz. Wird nur intern in {@link #refineSinusFit} zur
     * Konvergenzprüfung verwendet.
     *
     * @param data Datenpunkte
     * @param p    Parameter {A, w, phi, C}
     * @return Summe der quadrierten Residuen
     */
    private double sinusCost(List<double[]> data, double[] p) {
        double sum = 0;
        for (double[] pt : data) {
            double yModel = p[0] * Math.sin(p[1] * pt[0] + p[2]) + p[3];
            double diff = pt[1] - yModel;
            sum += diff * diff;
        }
        return sum;
    }

    /**
     * Löst ein lineares Gleichungssystem A*x = B mittels Gauß-Elimination mit Spaltenpivot.
     *
     * @param A quadratische Koeffizientenmatrix (wird nicht verändert)
     * @param B rechte Seite
     * @return Lösungsvektor x, oder {@code null} wenn A (numerisch) singulär ist
     */
    private double[] solveGaussian(double[][] A, double[] B) {
        int n = B.length;
        double[][] M = new double[n][n + 1];

        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, M[i], 0, n);
            M[i][n] = B[i];
        }

        for (int p = 0; p < n; p++) {
            int max = p;
            for (int i = p + 1; i < n; i++) {
                if (Math.abs(M[i][p]) > Math.abs(M[max][p])) max = i;
            }

            double[] temp = M[p]; M[p] = M[max]; M[max] = temp;

            if (Math.abs(M[p][p]) < 1e-12) return null;

            for (int i = p + 1; i < n; i++) {
                double alpha = M[i][p] / M[p][p];
                for (int j = p; j <= n; j++) M[i][j] -= alpha * M[p][j];
            }
        }

        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double sum = 0.0;
            for (int j = i + 1; j < n; j++) sum += M[i][j] * x[j];
            x[i] = (M[i][n] - sum) / M[i][i];
        }
        return x;
    }

    /** Funktionaler Typ für eine angepasste Modellfunktion f(x), unabhängig vom konkreten Fit-Typ. */
    private interface FunctionEvaluator {
        double eval(double x);
    }

    /**
     * Zeichnet eine Modellfunktion als gestrichelte Kurve inklusive eines halbtransparenten
     * Toleranzbands der Breite +/- {@link #standardDeviation} um die Kurve herum.
     *
     * @param g2       Ziel-Grafikkontext
     * @param func     die zu zeichnende Modellfunktion
     * @param minX     kleinster darzustellender X-Wert (Datenraum)
     * @param maxX     größter darzustellender X-Wert (Datenraum)
     * @param minY     kleinster Y-Wert des sichtbaren Bereichs (Datenraum)
     * @param rangeX   Breite des sichtbaren X-Bereichs (Datenraum)
     * @param rangeY   Höhe des sichtbaren Y-Bereichs (Datenraum)
     * @param padding  Innenabstand des Plots (Pixel)
     * @param height   Panelhöhe (Pixel)
     * @param plotWidth  Breite der Plotfläche (Pixel)
     * @param plotHeight Höhe der Plotfläche (Pixel)
     * @param color    Farbe der Kurve (das Toleranzband wird in derselben Farbe, aber transparent, gefüllt)
     */
    private void drawFunctionPathWithTolerance(Graphics2D g2, FunctionEvaluator func, double minX, double maxX, double minY,
                                               double rangeX, double rangeY, int padding, int height, int plotWidth, int plotHeight, Color color) {
        int steps = 400;
        double stepSize = (maxX - minX) / steps;

        double[] xVals = new double[steps + 1];
        double[] yVals = new double[steps + 1];

        for (int i = 0; i <= steps; i++) {
            double x = minX + i * stepSize;
            xVals[i] = x;
            yVals[i] = func.eval(x);
        }

        Polygon bandPolygon = new Polygon();
        for (int i = 0; i <= steps; i++) {
            double yUpper = yVals[i] + standardDeviation;
            double px = padding + ((xVals[i] - minX) / rangeX) * plotWidth;
            double pyUpper = (height - padding) - ((yUpper - minY) / rangeY) * plotHeight;
            bandPolygon.addPoint((int) px, (int) pyUpper);
        }
        for (int i = steps; i >= 0; i--) {
            double yLower = yVals[i] - standardDeviation;
            double px = padding + ((xVals[i] - minX) / rangeX) * plotWidth;
            double pyLower = (height - padding) - ((yLower - minY) / rangeY) * plotHeight;
            bandPolygon.addPoint((int) px, (int) pyLower);
        }

        g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 35));
        g2.fillPolygon(bandPolygon);

        g2.setColor(color);
        g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{6.0f}, 0.0f));

        Path2D path = new Path2D.Double();
        boolean first = true;
        for (int i = 0; i <= steps; i++) {
            double px = padding + ((xVals[i] - minX) / rangeX) * plotWidth;
            double py = (height - padding) - ((yVals[i] - minY) / rangeY) * plotHeight;

            if (first) {
                path.moveTo(px, py);
                first = false;
            } else {
                path.lineTo(px, py);
            }
        }
        g2.draw(path);
    }

    /**
     * Zeichnet ein Fadenkreuz mit Koordinatenanzeige an der aktuellen Mausposition, sofern
     * sich die Maus innerhalb der Plotfläche befindet.
     *
     * @param g2         Ziel-Grafikkontext
     * @param padding    Innenabstand des Plots (Pixel)
     * @param height     Panelhöhe (Pixel)
     * @param plotWidth  Breite der Plotfläche (Pixel)
     * @param plotHeight Höhe der Plotfläche (Pixel)
     * @param minX       kleinster X-Wert des sichtbaren Bereichs (Datenraum)
     * @param rangeX     Breite des sichtbaren X-Bereichs (Datenraum)
     * @param minY       kleinster Y-Wert des sichtbaren Bereichs (Datenraum)
     * @param rangeY     Höhe des sichtbaren Y-Bereichs (Datenraum)
     */
    private void drawCrosshair(Graphics2D g2, int padding, int height, int plotWidth, int plotHeight,
                               double minX, double rangeX, double minY, double rangeY) {
        if (mousePoint != null) {
            int mx = mousePoint.x;
            int my = mousePoint.y;

            if (mx >= padding && mx <= getWidth() - padding && my >= padding && my <= height - padding) {
                g2.setColor(Theme.BORDER);
                g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{4.0f, 4.0f}, 0.0f));

                g2.drawLine(mx, padding, mx, height - padding);
                g2.drawLine(padding, my, getWidth() - padding, my);

                double realX = minX + ((double) (mx - padding) / plotWidth) * rangeX;
                double realY = minY + ((double) ((height - padding) - my) / plotHeight) * rangeY;

                String coordStr = String.format("X: %.2f %s | Y: %.2f", realX, xUnit, realY);
                g2.setFont(new Font("SansSerif", Font.PLAIN, 11));
                FontMetrics fm = g2.getFontMetrics();
                int strWidth = fm.stringWidth(coordStr);

                int boxX = mx + 10;
                int boxY = my - 10;
                if (boxX + strWidth + 10 > getWidth() - padding) boxX = mx - strWidth - 15;
                if (boxY - 15 < padding) boxY = my + 20;

                g2.setColor(Theme.PANEL);
                g2.fillRect(boxX, boxY - 12, strWidth + 8, 16);
                g2.setColor(Theme.BORDER);
                g2.drawRect(boxX, boxY - 12, strWidth + 8, 16);
                g2.setColor(Theme.TEXT);
                g2.drawString(coordStr, boxX + 4, boxY);
            }
        }
    }

    /** Einfacher (double-x, double-y)-Bildschirmpunkt, um Rundungsfehler bei kleinen Panels zu vermeiden. */
    private static class Point2DDouble {
        double x, y;
        Point2DDouble(double x, double y) { this.x = x; this.y = y; }
    }
}