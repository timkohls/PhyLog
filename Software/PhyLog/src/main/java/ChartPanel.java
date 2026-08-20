import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Zeichnet ein X/Y-Diagramm (i. d. R. Zeit/Messwert, siehe {@link #setXAxisTitle}) mit Zoom,
 * Freihand-Auswahl und Fadenkreuz, und legt bei Bedarf eine Fit-Kurve samt Chi²-Gütebewertung
 * darüber. Die Ausgleichsrechnung übernimmt {@link CurveFitting}, die Bewertung
 * {@link GoodnessOfFit} - diese Klasse fügt beides nur zur Anzeige zusammen und cached die
 * (teuren) Ergebnisse.
 *
 * <p>Kennt keine Sensoren oder Hardware: bekommt ausschließlich fertige (x, y)-Paare über
 * {@link #setData(List)}.</p>
 *
 * <p>Interaktion: Linke Maustaste zieht = Zoom (Rubber-Band), rechte Maustaste zieht =
 * Freihand-Auswahl, einfacher Rechtsklick setzt den Zoom zurück, Klick auf das "i"-Symbol
 * neben der Chi²-Anzeige öffnet {@link ChiSquareInfoDialog}.</p>
 *
 * <p>Die Regression ist teuer und wird nur bei tatsächlicher Änderung neu berechnet, nicht bei
 * jedem repaint() (siehe {@link #fitDirty}).</p>
 */
public class ChartPanel extends JPanel {

    /** Verfügbare Regressionsmodelle. */
    public enum FitMode {
        /** Kein Fit, nur Rohdaten. */
        NONE,
        /** f(x) = m*x + b. */
        LINEAR,
        /** f(x) = a_n*x^n + ... + a_0, Grad einstellbar. */
        POLYNOMIAL,
        /** f(x) = A*sin(w*x + phi) + offset. */
        SINUS,
        /** f(x) = a*exp(b*x). */
        EXPONENTIAL,
        /** f(x) = a*x^n. */
        POWER_LAW
    }

    /** Wie Messpunkte verbunden werden: gar nicht, gerade, oder als glatte Catmull-Rom-Spline. */
    public enum LineMode { NONE, STRAIGHT, SPLINE }

    /** Auf welche Messgröße(n) sich Fit und Chi² beziehen (siehe {@link #setFitTarget}); Zoom
     *  und Freihand-Auswahl bleiben davon unabhängig immer an die Hauptgröße gebunden. */
    public enum FitTarget {
        /** Nur die Hauptgröße (Kanal A). */
        A,
        /** Nur die erste Extra-Serie (Kanal B). */
        B,
        /** Beide Größen gemeinsam, nach X aufsteigend zusammengeführt. */
        BOTH
    }

    /** Höhe der Chi²-Anzeige-Box (siehe {@link #drawChiSquareOverlay}). */
    private static final int CHI_OVERLAY_HEIGHT = 26;

    /** Eine zusätzlich eingezeichnete Messgröße (z. B. Kanal B), rein zur Darstellung -
     *  siehe {@link #setExtraSeries}. */
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

    /** Fasst die für einen Zeichendurchlauf nötige Pixel-/Datenraum-Geometrie zusammen; einmal
     *  je {@link #paintComponent(Graphics)} berechnet und an alle Zeichenschritte weitergereicht. */
    private static class PlotGeometry {
        final int width, height, padding, rightPadding, plotWidth, plotHeight;
        final double minX, maxX, minY, maxY, rangeX, rangeY, visibleMaxX;
        /** {@code true}, wenn eine zweite Y-Achse für Kanal B gezeichnet wird - nur dann sind
         *  {@link #minY2}/{@link #maxY2}/{@link #rangeY2} gültig. */
        final boolean hasSecondaryAxis;
        final double minY2, maxY2, rangeY2;

        PlotGeometry(int width, int height, int padding, int rightPadding, int plotWidth, int plotHeight,
                     double minX, double maxX, double minY, double maxY,
                     double rangeX, double rangeY, double visibleMaxX,
                     boolean hasSecondaryAxis, double minY2, double maxY2, double rangeY2) {
            this.width = width;
            this.height = height;
            this.padding = padding;
            this.rightPadding = rightPadding;
            this.plotWidth = plotWidth;
            this.plotHeight = plotHeight;
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.rangeX = rangeX;
            this.rangeY = rangeY;
            this.visibleMaxX = visibleMaxX;
            this.hasSecondaryAxis = hasSecondaryAxis;
            this.minY2 = minY2;
            this.maxY2 = maxY2;
            this.rangeY2 = rangeY2;
        }
    }

    /** (double-x, double-y)-Bildschirmpunkt, um Rundungsfehler bei kleinen Panels zu vermeiden. */
    private static class Point2DDouble {
        double x, y;
        Point2DDouble(double x, double y) { this.x = x; this.y = y; }
    }

    /** Zuletzt über {@link #setData(List)} gesetzte Messdaten (Hauptgröße). Wird von Zoom/
     *  Freihand-Auswahl nie verändert - siehe {@link #viewport} für den Zoom-Zustand. */
    private List<double[]> originalData = new ArrayList<>();
    /** Auf das aktuelle Zoom-/Auswahlfenster eingeschränkte Teilmenge von {@link #originalData}
     *  (siehe {@link #recomputeDisplayData()}); geht in Achsenbereich, Fit und Chi² ein. */
    private List<double[]> displayData = new ArrayList<>();

    /** Aktuelles Zoom-/Auswahlfenster sowie Zoom-Faktor und flüchtiger Mausinteraktions-
     *  Zustand - siehe {@link ChartViewport}. Neu eintreffende Messwerte löschen dabei nie
     *  Punkte aus {@link #originalData}; {@link #recomputeDisplayData()} leitet
     *  {@link #displayData} bei jeder Änderung frisch daraus ab. */
    private final ChartViewport viewport = new ChartViewport();

    /** Zusätzliche, gleichzeitig dargestellte Kurven (siehe {@link Series}); nehmen nicht an
     *  Zoom, Freihand-Auswahl, Fit oder Chi² teil. */
    private List<Series> extraSeries = new ArrayList<>();

    private String xUnit = "s";
    /** Titelwort vor der X-Achsen-Einheit (z. B. "Zeit" oder "Frequenz"), siehe {@link #setXAxisTitle}. */
    private String xAxisTitle = "Zeit";
    private String yUnit = "Messwert";

    /** Bezeichnung der Hauptmessgröße (Kanal A) für die Legende. */
    private String mainLabel = "Kanal A";

    /** Beschriftung der optionalen zweiten Y-Achse für Kanal B, siehe {@link #setSecondaryUnits}. */
    private String secondaryYUnit = "Messwert";

    private boolean showPoints = true;
    private LineMode lineMode = LineMode.NONE;
    /** {@code true}, wenn Messpunkte nach ihrem Y-Wert statt in fester Serienfarbe gefärbt
     *  werden (siehe {@link #magnitudeColor}) - genutzt für die Frequenzspektrum-Anzeige. */
    private boolean colorByMagnitude = false;
    private FitMode fitMode = FitMode.NONE;
    private int polynomialDegree = 2;

    /** Auf welche Messgröße(n) sich der aktuelle Fit bezieht, siehe {@link FitTarget}. */
    private FitTarget fitTarget = FitTarget.A;

    /** Tatsächlich für Fit/Chi²/Sigma verwendete Datenpunkte - je nach {@link #fitTarget}
     *  identisch zu {@link #displayData} oder per {@link #computeFitData()} daraus abgeleitet. */
    private List<double[]> fitData = new ArrayList<>();

    /** {@code true}, wenn Kanal B eine eigene, unabhängig skalierte zweite Y-Achse bekommt,
     *  statt sich die Achse mit Kanal A zu teilen. Betrifft nur die Darstellung - Zoom,
     *  Freihand-Auswahl, Fit und Chi² bleiben immer an Kanal A gebunden. */
    private boolean dualYAxisMode = false;

    private Point mousePoint = null;

    /** Klickfläche des "i"-Symbols neben der Chi²-Anzeige, bei jedem Zeichnen aktualisiert. */
    private Rectangle infoButtonBounds = new Rectangle();
    private double currentReducedChiSquare = 0.0;
    private int currentDegreesOfFreedom = 1;

    /** Angenommene Standardabweichung der Messwerte für Chi²; Rückfallebene für die
     *  automatischen Sigma-Modi ohne aktiven Fit. */
    private double standardDeviation = 1.0;

    /** Wie sigma bestimmt wird, siehe {@link GoodnessOfFit.SigmaMode}. */
    private GoodnessOfFit.SigmaMode sigmaMode = GoodnessOfFit.SigmaMode.CONSTANT;
    /** Nachbarschaftsgröße k für {@link GoodnessOfFit.SigmaMode#RESIDUAL_LOCAL}. */
    private int localSigmaNeighbors = 8;
    /** {@code true}, wenn die aus Residuen abgeleiteten Sigma-Werte neu berechnet werden müssen. */
    private boolean sigmaCacheDirty = true;
    /** Zwischengespeicherte Sigma-Werte für {@link GoodnessOfFit.SigmaMode#RESIDUAL_LOCAL}. */
    private double[] cachedLocalSigmas = null;
    /** Zwischengespeicherte Residuen samt Bandbreite für
     *  {@link GoodnessOfFit.SigmaMode#RESIDUAL_LOCAL_GAUSSIAN}. */
    private double[] cachedGaussianResiduals = null;
    private double cachedGaussianBandwidth = 0;

    // --- Fit-Cache: Regression ist teuer, wird nur bei tatsächlicher Änderung neu berechnet
    // (nicht bei jedem repaint(), z. B. wegen einer Mausbewegung). Die Standardabweichung
    // beeinflusst nur Chi², nicht die Kurvenparameter, und löst deshalb keinen Refit aus.
    private boolean fitDirty = true;
    private CurveFitting.FitResult cachedFit = null;
    private FitMode cachedFitModeUsed = null;
    private int cachedDegreeUsed = -1;
    private FitTarget cachedFitTargetUsed = null;

    /** Beschreibung des zuletzt gezeichneten Fits, {@code null} ohne aktiven Fit. */
    private CurveFitting.FitDescription currentFitDescription = null;

    /** Erstellt das leere Diagramm-Panel und registriert die Maus-Interaktion für Zoom,
     *  Freihand-Auswahl, Fadenkreuz und den Klick auf das Chi²-Info-Symbol. */
    public ChartPanel() {
        setBackground(Theme.BG);

        MouseAdapter mouseHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    viewport.beginRubberBand(e.getPoint());
                    repaint();
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    viewport.beginFreehand(e.getPoint());
                    repaint();
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && viewport.getDragStart() != null) {
                    viewport.updateRubberBand(e.getPoint());
                    repaint();
                } else if (SwingUtilities.isRightMouseButton(e) && viewport.isRightButtonDragging()) {
                    viewport.addFreehandPoint(e.getPoint());
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && viewport.hasRubberBand()) {
                    if (viewport.applySelectionZoom(viewport.getDragStart(), viewport.getDragEnd(),
                            viewportGeometry(), originalData)) {
                        onViewportWindowChanged();
                    }
                    viewport.clearRubberBand();
                    repaint();
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    viewport.endFreehandDrag();
                    if (viewport.isRightClickTriggered() && viewport.getFreehandPoints().size() > 2) {
                        if (viewport.applyFreehandSelection(viewport.getFreehandPoints(), viewportGeometry(), originalData)) {
                            onViewportWindowChanged();
                        }
                    } else {
                        resetZoom();
                    }
                    viewport.clearFreehand();
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
     * Setzt die anzuzeigenden Messdaten komplett neu (z. B. nach CSV-Import oder neuen
     * Live-Messwerten). Ein aktives Zoom-/Auswahlfenster bleibt bewusst erhalten, damit
     * jeder neue Live-Messwert den Zoom nicht sofort wieder aufhebt - siehe {@link #resetZoom()}.
     *
     * @param data Liste von (x, y)-Paaren, {@code null} wird als leere Liste behandelt
     */
    public void setData(List<double[]> data) {
        List<double[]> newData = (data != null) ? new ArrayList<>(data) : new ArrayList<>();
        boolean dataChanged = !dataEquivalent(this.originalData, newData);
        this.originalData = newData;
        recomputeDisplayData();
        if (dataChanged) {
            fitDirty = true;
            sigmaCacheDirty = true;
        }
        repaint();
    }

    /** Günstiger Vergleich zweier Datensätze (Größe plus erster/letzter Punkt) statt eines
     *  vollständigen Elementvergleichs - unterscheidet ein bloßes "unverändert erneut
     *  gesetzt" von einer echten Änderung, ohne bei tausenden Punkten selbst teuer zu sein. */
    private static boolean dataEquivalent(List<double[]> a, List<double[]> b) {
        if (a.size() != b.size()) return false;
        if (a.isEmpty()) return true;
        return Arrays.equals(a.getFirst(), b.getFirst()) && Arrays.equals(a.getLast(), b.getLast());
    }

    /** Leitet {@link #displayData} aus {@link #originalData} ab: unverändert ohne aktives
     *  Zoom-Fenster, sonst auf das Fenster aus {@link #viewport} eingeschränkt. */
    private void recomputeDisplayData() {
        if (!viewport.isActive()) {
            displayData = new ArrayList<>(originalData);
            return;
        }

        List<double[]> filtered = new ArrayList<>();
        for (double[] pt : originalData) {
            if (pt[0] >= viewport.getMinX() && pt[0] <= viewport.getMaxX()
                    && pt[1] >= viewport.getMinY() && pt[1] <= viewport.getMaxY()) {
                filtered.add(pt);
            }
        }
        displayData = filtered;
    }

    /** Nach einer neuen Zoom-/Auswahlfenster-Setzung durch {@link #viewport}: zieht
     *  {@link #displayData} nach und invalidiert Fit- und Sigma-Cache. */
    private void onViewportWindowChanged() {
        recomputeDisplayData();
        fitDirty = true;
        sigmaCacheDirty = true;
    }

    /** Schränkt {@code data} auf das aktuelle Zoom-/Auswahlfenster ein, analog zu
     *  {@link #recomputeDisplayData()} für beliebige (z. B. Kanal-B-)Daten. */
    private List<double[]> filterToViewport(List<double[]> data) {
        if (data == null) return new ArrayList<>();
        if (!viewport.isActive()) return new ArrayList<>(data);

        List<double[]> filtered = new ArrayList<>();
        for (double[] pt : data) {
            if (pt[0] >= viewport.getMinX() && pt[0] <= viewport.getMaxX()
                    && pt[1] >= viewport.getMinY() && pt[1] <= viewport.getMaxY()) {
                filtered.add(pt);
            }
        }
        return filtered;
    }

    /** Daten der ersten Extra-Serie (Kanal B), oder eine leere Liste ohne Extra-Serie. */
    private List<double[]> firstExtraSeriesData() {
        return (extraSeries != null && !extraSeries.isEmpty() && extraSeries.getFirst().data != null)
                ? extraSeries.getFirst().data : new ArrayList<>();
    }

    /** Baut den für {@link #fitTarget} tatsächlich zu fittenden Datensatz: {@link #displayData}
     *  für {@link FitTarget#A}, die Extra-Serie für {@link FitTarget#B}, bzw. beides nach X
     *  aufsteigend zusammengeführt für {@link FitTarget#BOTH}. */
    private List<double[]> computeFitData() {
        return switch (fitTarget) {
            case B -> filterToViewport(firstExtraSeriesData());
            case BOTH -> {
                List<double[]> combined = new ArrayList<>(displayData);
                combined.addAll(filterToViewport(firstExtraSeriesData()));
                combined.sort(Comparator.comparingDouble(p -> p[0]));
                yield combined;
            }
            default -> displayData;
        };
    }

    /** Baut die für {@link ChartViewport} nötige reduzierte Geometrie, oder {@code null} bei
     *  zu kleinem Panel. */
    private ChartViewport.Geometry viewportGeometry() {
        PlotGeometry geo = computePlotGeometry();
        if (geo == null) return null;
        return new ChartViewport.Geometry(geo.padding, geo.plotWidth, geo.plotHeight, geo.height,
                geo.minX, geo.rangeX, geo.minY, geo.rangeY);
    }

    /**
     * Setzt zusätzliche, gleichzeitig darzustellende Kurven (z. B. Kanal B). Rein visuell:
     * beeinflusst weder Zoom noch Freihand-Auswahl, Fit oder Chi².
     *
     * @param series Liste zusätzlicher Kurven, {@code null} wird als leere Liste behandelt
     */
    public void setExtraSeries(List<Series> series) {
        this.extraSeries = (series != null) ? new ArrayList<>(series) : new ArrayList<>();
        // Nur invalidieren, wenn der Fit überhaupt von Kanal-B-Daten abhängt.
        if (fitTarget != FitTarget.A) {
            fitDirty = true;
            sigmaCacheDirty = true;
        }
        repaint();
    }

    /**
     * Legt die Achsenbeschriftungen fest. Bei mehreren gleichzeitig dargestellten Größen
     * sollte {@code yLabel} generisch bleiben ("Messwerte") - welche Einheit zu welcher Kurve
     * gehört, zeigt die Legende (siehe {@link #setMainLabel}).
     *
     * @param xUnit  Einheit der X-Achse, {@code null} fällt auf "s" zurück
     * @param yLabel Beschriftung der Y-Achse, {@code null}/leer fällt auf "Messwert" zurück
     */
    public void setUnits(String xUnit, String yLabel) {
        this.xUnit = (xUnit != null) ? xUnit : "s";
        this.yUnit = (yLabel != null && !yLabel.isBlank()) ? yLabel.trim() : "Messwert";
        repaint();
    }

    /** Setzt das Titelwort vor der X-Achsen-Einheit (Standard: "Zeit"). */
    public void setXAxisTitle(String title) {
        this.xAxisTitle = (title != null && !title.isBlank()) ? title.trim() : "Zeit";
        repaint();
    }

    /** Setzt die Bezeichnung der Hauptmessgröße (Kanal A) für die Legende. */
    public void setMainLabel(String label) {
        this.mainLabel = (label != null && !label.isBlank()) ? label.trim() : "Kanal A";
        repaint();
    }

    /** Setzt die Beschriftung der zweiten Y-Achse; wirkt nur bei aktivem {@link #dualYAxisMode}. */
    public void setSecondaryUnits(String yLabel) {
        this.secondaryYUnit = (yLabel != null && !yLabel.isBlank()) ? yLabel.trim() : "Messwert";
        repaint();
    }

    /** Legt fest, ob Kanal B eine eigene, unabhängig skalierte zweite Y-Achse bekommt. */
    public void setDualYAxisMode(boolean dualYAxisMode) {
        this.dualYAxisMode = dualYAxisMode;
        repaint();
    }

    /** @param showPoints ob die Messpunkte als Kreise gezeichnet werden sollen */
    public void setShowPoints(boolean showPoints) {
        this.showPoints = showPoints;
        repaint();
    }

    /** @param lineMode wie die Messpunkte verbunden werden (siehe {@link LineMode}) */
    public void setLineMode(LineMode lineMode) {
        this.lineMode = (lineMode != null) ? lineMode : LineMode.NONE;
        repaint();
    }

    /** @param colorByMagnitude ob Messpunkte nach Y-Wert statt fester Farbe eingefärbt werden */
    public void setColorByMagnitude(boolean colorByMagnitude) {
        this.colorByMagnitude = colorByMagnitude;
        repaint();
    }

    /** Wählt das Regressionsmodell und markiert den Fit-Cache als veraltet. */
    public void setFitMode(FitMode fitMode) {
        this.fitMode = fitMode;
        fitDirty = true;
        sigmaCacheDirty = true;
        repaint();
    }

    /** Legt fest, auf welche Messgröße(n) sich Fit und Chi² beziehen, siehe {@link FitTarget}. */
    public void setFitTarget(FitTarget fitTarget) {
        this.fitTarget = (fitTarget != null) ? fitTarget : FitTarget.A;
        fitDirty = true;
        sigmaCacheDirty = true;
        repaint();
    }

    /** @param degree Polynomgrad für {@link FitMode#POLYNOMIAL}, wird auf 1..10 begrenzt */
    public void setPolynomialDegree(int degree) {
        this.polynomialDegree = Math.clamp(degree, 1, 10);
        fitDirty = true;
        sigmaCacheDirty = true;
        repaint();
    }

    /** @param standardDeviation neue Standardabweichung (min. 1e-6); beeinflusst nur Chi², kein Refit */
    public void setStandardDeviation(double standardDeviation) {
        this.standardDeviation = Math.max(1e-6, standardDeviation);
        repaint();
    }

    public double getStandardDeviation() { return standardDeviation; }

    /** @param sigmaMode Modus zur Sigma-Bestimmung, {@code null} fällt auf CONSTANT zurück */
    public void setSigmaMode(GoodnessOfFit.SigmaMode sigmaMode) {
        this.sigmaMode = (sigmaMode != null) ? sigmaMode : GoodnessOfFit.SigmaMode.CONSTANT;
        sigmaCacheDirty = true;
        repaint();
    }

    public GoodnessOfFit.SigmaMode getSigmaMode() { return sigmaMode; }

    /** @param neighbors Nachbarn-Anzahl für {@link GoodnessOfFit.SigmaMode#RESIDUAL_LOCAL} (min. 2) */
    public void setLocalSigmaNeighbors(int neighbors) {
        this.localSigmaNeighbors = Math.max(2, neighbors);
        sigmaCacheDirty = true;
        repaint();
    }

    public int getLocalSigmaNeighbors() { return localSigmaNeighbors; }

    public void zoomIn() {
        viewport.zoomIn();
        repaint();
    }

    /** Verkleinert den angezeigten Ausschnitt um Faktor 1.2 (Mindestfaktor 0.1). */
    public void zoomOut() {
        viewport.zoomOut();
        repaint();
    }

    /** Setzt Zoom-Faktor und Zoom-/Auswahlfenster auf die vollständigen Messdaten zurück. */
    public void resetZoom() {
        viewport.reset();
        recomputeDisplayData();
        fitDirty = true;
        sigmaCacheDirty = true;
        repaint();
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

        // Prüfen, ob irgendwelche Daten vorliegen (Hauptdaten oder Extra-Serien).
        boolean hasAnyData = (displayData != null && !displayData.isEmpty());
        if (!hasAnyData && extraSeries != null) {
            for (Series s : extraSeries) {
                if (s.data != null && !s.data.isEmpty()) {
                    hasAnyData = true;
                    break;
                }
            }
        }

        if (!hasAnyData) {
            drawEmptyDataMessage(g2, geo);
            drawCrosshair(g2, geo);
            g2.dispose();
            return;
        }

        List<double[]> renderData = downsampleForRendering(displayData, geo);
        List<Point2DDouble> screenPoints = projectDataToScreen(geo, renderData);

        if (lineMode != LineMode.NONE && screenPoints.size() > 1) {
            drawConnectingLine(g2, screenPoints);
        }

        drawFitOverlayClipped(g2, geo);

        if (showPoints) {
            drawDataPoints(g2, geo, screenPoints, renderData);
        }

        drawExtraSeries(g2, geo);

        // Legende hat (falls sichtbar) Vorrang oben rechts; die Chi²-Anzeige rutscht darunter.
        int legendTopY = geo.padding + 6;
        drawLegend(g2, geo, legendTopY);

        if (fitMode != FitMode.NONE) {
            int chiOverlayY = legendTopY;
            if (legendVisible()) {
                chiOverlayY += legendHeight() + 6;
            }
            drawChiSquareOverlay(g2, geo.width, geo.rightPadding, chiOverlayY);

            if (!extraSeries.isEmpty()) {
                drawFitScopeNote(g2, geo, chiOverlayY + CHI_OVERLAY_HEIGHT + 4);
            }
        }

        drawSelectionRectangle(g2);
        drawFreehandStroke(g2);

        drawCrosshair(g2, geo);
        g2.dispose();
    }

    /** Zeichnet Extra-Kurven (siehe {@link #setExtraSeries}) in ihrer jeweiligen Farbe, auf der
     *  Skala der (aktiven) zweiten Achse oder sonst auf der Skala der Hauptgröße. */
    private void drawExtraSeries(Graphics2D g2, PlotGeometry geo) {
        double pointSize = 6;

        double seriesMinY = geo.hasSecondaryAxis ? geo.minY2 : geo.minY;
        double seriesRangeY = geo.hasSecondaryAxis ? geo.rangeY2 : geo.rangeY;
        int rightEdge = geo.width - geo.rightPadding;

        for (Series series : extraSeries) {
            List<double[]> renderData = downsampleForRendering(series.data, geo);
            List<Point2DDouble> points = new ArrayList<>();
            for (double[] point : renderData) {
                double px = geo.padding + ((point[0] - geo.minX) / geo.rangeX) * geo.plotWidth;
                double py = (geo.height - geo.padding) - ((point[1] - seriesMinY) / seriesRangeY) * geo.plotHeight;
                points.add(new Point2DDouble(px, py));
            }

            if (lineMode != LineMode.NONE && points.size() > 1) {
                g2.setColor(series.color.darker());
                g2.setStroke(new BasicStroke(1.5f));
                g2.draw(buildLinePath(points));
            }

            if (showPoints) {
                g2.setColor(series.color);
                for (int i = 0; i < points.size(); i++) {
                    Point2DDouble pt = points.get(i);
                    if (pt.x >= geo.padding && pt.x <= rightEdge
                            && pt.y >= geo.padding && pt.y <= geo.height - geo.padding) {
                        if (colorByMagnitude) {
                            double normalized = (seriesRangeY > 1e-9) ? (renderData.get(i)[1] - seriesMinY) / seriesRangeY : 0.5;
                            g2.setColor(magnitudeColor(normalized));
                        }
                        g2.fill(new Ellipse2D.Double(pt.x - pointSize / 2, pt.y - pointSize / 2, pointSize, pointSize));
                    }
                }
            }
        }
    }

    /** Ob die Farb-Legende gezeichnet wird: nur mit mehr als einer gleichzeitig dargestellten
     *  Größe, gemeinsamer Y-Achse und tatsächlichen Daten in der Hauptgröße - sonst wäre der
     *  Bezug entweder redundant (Achsentitel/zweite Achse zeigen es schon) oder bedeutungslos. */
    private boolean legendVisible() {
        return !extraSeries.isEmpty() && !dualYAxisMode && !originalData.isEmpty();
    }

    /** Höhe der Legendenbox in Pixeln, oder 0 ohne sichtbare Legende. */
    private int legendHeight() {
        if (!legendVisible()) return 0;
        int rowHeight = 16;
        return (1 + extraSeries.size()) * rowHeight + 8;
    }

    /** Zeichnet eine kleine Legende (Farbe -> Messgröße) oben rechts, sofern {@link #legendVisible()}.
     *
     * @param topY obere Kante der Legende in Bildschirmkoordinaten */
    private void drawLegend(Graphics2D g2, PlotGeometry geo, int topY) {
        if (!legendVisible()) return;

        g2.setFont(Theme.FONT_HINT);
        FontMetrics fm = g2.getFontMetrics();

        List<String> labels = new ArrayList<>();
        List<Color> colors = new ArrayList<>();
        labels.add(mainLabel);
        colors.add(Theme.POINT_A);
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
        int boxHeight = legendHeight();
        int boxX = geo.width - geo.rightPadding - boxWidth - 6;

        g2.setColor(Theme.PANEL);
        g2.fillRoundRect(boxX, topY, boxWidth, boxHeight, 8, 8);
        g2.setColor(Theme.BORDER);
        g2.drawRoundRect(boxX, topY, boxWidth, boxHeight, 8, 8);

        for (int i = 0; i < labels.size(); i++) {
            int rowY = topY + 6 + i * rowHeight;
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
     * @return die Plot-Geometrie, oder {@code null} bei zu kleinem Panel
     */
    private PlotGeometry computePlotGeometry() {
        int width = getWidth();
        int height = getHeight();
        int padding = 65;

        boolean hasExtraData = false;
        if (extraSeries != null) {
            for (Series s : extraSeries) {
                if (s.data != null && !s.data.isEmpty()) {
                    hasExtraData = true;
                    break;
                }
            }
        }

        // Zweite Achse nur zeichnen, wenn aktiv UND es Kanal-B-Daten gibt, die sie beschriften.
        boolean secondaryAxisActive = dualYAxisMode && hasExtraData;
        int rightPadding = secondaryAxisActive ? 95 : padding;

        int plotWidth = width - padding - rightPadding;
        int plotHeight = height - 2 * padding;

        if (plotWidth <= 0 || plotHeight <= 0) return null;

        double minX, maxX, minY, maxY;
        double minY2 = 0, maxY2 = 10;

        if (viewport.isActive()) {
            // Das Zoom-/Auswahlfenster ist die Achsen-Spanne - aus den (unfilterten) Daten neu
            // berechnen würde den Zoom optisch aufheben.
            minX = viewport.getMinX(); maxX = viewport.getMaxX();
            minY = viewport.getMinY(); maxY = viewport.getMaxY();

            if (secondaryAxisActive) {
                // Zweite Achse bewusst nicht ans (auf Kanal A bezogene) Zoom-Fenster gekoppelt,
                // sondern frisch auf die sichtbaren Kanal-B-Werte skaliert.
                double[] secRange = computeSecondaryRange(minX, maxX);
                minY2 = secRange[0];
                maxY2 = secRange[1];
            }
        } else {
            minX = 0; maxX = 10;
            minY = 0; maxY = 10;

            boolean hasMainData = (displayData != null && !displayData.isEmpty());

            if (hasMainData || hasExtraData) {
                minX = Double.MAX_VALUE; maxX = -Double.MAX_VALUE;
                minY = Double.MAX_VALUE; maxY = -Double.MAX_VALUE;

                if (hasMainData) {
                    for (double[] point : displayData) {
                        if (point[0] < minX) minX = point[0];
                        if (point[0] > maxX) maxX = point[0];
                        if (point[1] < minY) minY = point[1];
                        if (point[1] > maxY) maxY = point[1];
                    }
                }

                if (extraSeries != null) {
                    for (Series series : extraSeries) {
                        if (series.data != null) {
                            for (double[] point : series.data) {
                                if (point[0] < minX) minX = point[0];
                                if (point[0] > maxX) maxX = point[0];
                                // Ohne eigene zweite Achse teilen sich Extra-Kurven die Y-Achse
                                // mit Kanal A und gehen mit in dessen Wertebereich ein.
                                if (!secondaryAxisActive) {
                                    if (point[1] < minY) minY = point[1];
                                    if (point[1] > maxY) maxY = point[1];
                                }
                            }
                        }
                    }
                }

                if (minX == Double.MAX_VALUE) minX = 0;
                if (minY == Double.MAX_VALUE) { minY = 0; maxY = 10; } // nur Kanal-B-Daten vorhanden
            }
            if (minX == maxX) maxX = minX + 1.0;
            if (minY == maxY) { minY -= 1.0; maxY += 1.0; }

            if (secondaryAxisActive) {
                double[] secRange = computeSecondaryRange(minX, maxX);
                minY2 = secRange[0];
                maxY2 = secRange[1];
            }
        }

        double rangeX = (maxX - minX) / viewport.getZoomFactor();
        double rangeY = (maxY - minY) / viewport.getZoomFactor();
        double rangeY2 = secondaryAxisActive ? (maxY2 - minY2) / viewport.getZoomFactor() : 0;
        double visibleMaxX = minX + rangeX;

        return new PlotGeometry(width, height, padding, rightPadding, plotWidth, plotHeight,
                minX, maxX, minY, maxY, rangeX, rangeY, visibleMaxX,
                secondaryAxisActive, minY2, maxY2, rangeY2);
    }

    /** Y-Wertebereich der Extra-Serien (Kanal B) innerhalb des sichtbaren X-Fensters; weicht
     *  ohne Punkte im Fenster auf den gesamten Kanal-B-Datensatz aus, statt grundlos auf 0..10
     *  zurückzufallen. */
    private double[] computeSecondaryRange(double minX, double maxX) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (Series series : extraSeries) {
            if (series.data == null) continue;
            for (double[] point : series.data) {
                if (point[0] < minX || point[0] > maxX) continue;
                if (point[1] < lo) lo = point[1];
                if (point[1] > hi) hi = point[1];
            }
        }
        if (lo == Double.MAX_VALUE) {
            for (Series series : extraSeries) {
                if (series.data == null) continue;
                for (double[] point : series.data) {
                    if (point[1] < lo) lo = point[1];
                    if (point[1] > hi) hi = point[1];
                }
            }
        }
        if (lo == Double.MAX_VALUE) { lo = 0; hi = 10; }
        if (lo == hi) { lo -= 1.0; hi += 1.0; }
        return new double[]{lo, hi};
    }

    /** Zeichnet Hintergrundgitter, Achsenlinien, Tick-Beschriftungen und Achsentitel. */
    private void drawGridAndAxes(Graphics2D g2, PlotGeometry geo) {
        int padding = geo.padding;
        int height = geo.height;
        int width = geo.width;
        int plotWidth = geo.plotWidth;
        int plotHeight = geo.plotHeight;
        int rightEdge = width - geo.rightPadding;
        // Linke Achse färbt sich nur bei aktiver zweiter Achse in Kanal-A-Farbe ein.
        Color primaryAxisColor = geo.hasSecondaryAxis ? Theme.POINT_A : Theme.TEXT;
        Color primaryGridColor = withAlpha(primaryAxisColor, 40);

        g2.setStroke(new BasicStroke(1.0f));
        int gridDivisions = 5;

        for (int i = 0; i <= gridDivisions; i++) {
            double ratio = (double) i / gridDivisions;
            int x = padding + (int) (ratio * plotWidth);
            double valX = geo.minX + ratio * geo.rangeX;
            g2.setColor(Theme.BORDER);
            g2.drawLine(x, padding, x, height - padding);
            g2.setColor(Theme.TEXT);
            g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
            g2.drawString(String.format("%.2f", valX), x - 12, height - padding + 20);
        }

        for (int i = 0; i <= gridDivisions; i++) {
            double ratio = (double) i / gridDivisions;
            int y = (height - padding) - (int) (ratio * plotHeight);
            double valY = geo.minY + ratio * geo.rangeY;

            if (!geo.hasSecondaryAxis) {
                g2.setColor(Theme.BORDER);
                g2.drawLine(padding, y, rightEdge, y);
            } else {
                g2.setColor(primaryGridColor);
                g2.drawLine(padding, y, rightEdge, y);
            }

            g2.setColor(primaryAxisColor);
            g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
            g2.drawString(String.format("%.2f", valY), padding - 50, y + 4);

            if (geo.hasSecondaryAxis) {
                double valY2 = geo.minY2 + ratio * geo.rangeY2;

                g2.setColor(withAlpha(secondaryAxisColor(), 40));
                g2.drawLine(padding, y, rightEdge, y);

                g2.setColor(secondaryAxisColor());
                g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
                g2.drawString(String.format("%.2f", valY2), rightEdge + 8, y + 4);
            }
        }

        g2.setColor(Theme.TEXT);
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawLine(padding, height - padding, rightEdge, height - padding);

        g2.setColor(primaryAxisColor);
        g2.drawLine(padding, padding, padding, height - padding);

        if (geo.hasSecondaryAxis) {
            g2.setColor(secondaryAxisColor());
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawLine(rightEdge, padding, rightEdge, height - padding);
        }

        g2.setColor(Theme.TEXT);
        g2.setFont(new Font("SansSerif", Font.BOLD, 11));
        g2.drawString(xAxisTitle + " (" + xUnit + ")", padding + plotWidth / 2 - 30, height - padding + 35);

        g2.setColor(primaryAxisColor);
        g2.drawString(yUnit, padding - 50, padding - 15);

        if (geo.hasSecondaryAxis) {
            g2.setColor(secondaryAxisColor());
            FontMetrics fm = g2.getFontMetrics();
            int labelWidth = fm.stringWidth(secondaryYUnit);
            g2.drawString(secondaryYUnit, rightEdge - labelWidth + 55, padding - 15);
        }
    }

    /** Kopiert {@code color} mit neuem Alpha-Wert (0-255). */
    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    /** Blau-Türkis-Orange-Rot-Farbverlauf für {@link #colorByMagnitude} (z. B. Spektrum-dB-Werte).
     *
     * @param normalized Wert in [0, 1], außerhalb wird geklemmt */
    private static Color magnitudeColor(double normalized) {
        double t = Math.clamp(normalized, 0.0, 1.0);
        Color[] stops = {
                new Color(60, 70, 200),
                new Color(40, 180, 190),
                new Color(250, 170, 40),
                new Color(230, 60, 60)
        };

        double scaled = t * (stops.length - 1);
        int index = Math.min(stops.length - 2, (int) scaled);
        double localT = scaled - index;

        Color a = stops[index], b = stops[index + 1];
        int r = (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * localT);
        int g = (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * localT);
        int bl = (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * localT);
        return new Color(r, g, bl);
    }

    /** Farbe der zweiten Y-Achse: die der ersten Extra-Serie, sonst {@link Theme#TEXT}. */
    private Color secondaryAxisColor() {
        return (extraSeries != null && !extraSeries.isEmpty()) ? extraSeries.getFirst().color : Theme.TEXT;
    }

    private void drawEmptyDataMessage(Graphics2D g2, PlotGeometry geo) {
        g2.setColor(Theme.TEXT);
        g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g2.drawString("Keine Messdaten vorhanden", geo.width / 2 - 70, geo.height / 2);
    }

    /** Wandelt Datenpunkte anhand der Geometrie in Bildschirmkoordinaten um (Hauptgröße/Kanal A). */
    private List<Point2DDouble> projectDataToScreen(PlotGeometry geo, List<double[]> data) {
        List<Point2DDouble> points = new ArrayList<>();
        for (double[] point : data) {
            double px = geo.padding + ((point[0] - geo.minX) / geo.rangeX) * geo.plotWidth;
            double py = (geo.height - geo.padding) - ((point[1] - geo.minY) / geo.rangeY) * geo.plotHeight;
            points.add(new Point2DDouble(px, py));
        }
        return points;
    }

    /** Reduziert sehr dichte Datensätze auf Min/Max je Pixel-Spalte (Min-Max-Downsampling),
     *  damit Zeichnen bei tausenden Punkten performant bleibt, ohne sichtbare Ausschläge zu
     *  verlieren. Ändert nichts an Fit/Chi² - nur an dem, was tatsächlich gezeichnet wird. */
    private List<double[]> downsampleForRendering(List<double[]> data, PlotGeometry geo) {
        int n = data.size();
        int columns = Math.max(1, geo.plotWidth);
        if (n <= columns * 2) return data;

        double[] colMinY = new double[columns];
        double[] colMaxY = new double[columns];
        int[] colMinIdx = new int[columns];
        int[] colMaxIdx = new int[columns];
        boolean[] colHasData = new boolean[columns];

        for (int i = 0; i < n; i++) {
            double[] point = data.get(i);
            int col = (int) (((point[0] - geo.minX) / geo.rangeX) * columns);
            if (col < 0) col = 0;
            if (col >= columns) col = columns - 1;

            if (!colHasData[col]) {
                colHasData[col] = true;
                colMinY[col] = point[1];
                colMaxY[col] = point[1];
                colMinIdx[col] = i;
                colMaxIdx[col] = i;
            } else {
                if (point[1] < colMinY[col]) {
                    colMinY[col] = point[1];
                    colMinIdx[col] = i;
                }
                if (point[1] > colMaxY[col]) {
                    colMaxY[col] = point[1];
                    colMaxIdx[col] = i;
                }
            }
        }

        List<double[]> reduced = new ArrayList<>(columns * 2);
        for (int c = 0; c < columns; c++) {
            if (!colHasData[c]) continue;
            if (colMinIdx[c] == colMaxIdx[c]) {
                reduced.add(data.get(colMinIdx[c]));
            } else if (colMinIdx[c] < colMaxIdx[c]) {
                reduced.add(data.get(colMinIdx[c]));
                reduced.add(data.get(colMaxIdx[c]));
            } else {
                reduced.add(data.get(colMaxIdx[c]));
                reduced.add(data.get(colMinIdx[c]));
            }
        }
        return reduced;
    }

    /** Zeichnet die Verbindungslinie der Hauptgröße gemäß {@link #lineMode}. */
    private void drawConnectingLine(Graphics2D g2, List<Point2DDouble> points) {
        g2.setColor(Theme.POINT_A.darker());
        g2.setStroke(new BasicStroke(1.5f));
        g2.draw(buildLinePath(points));
    }

    /** Baut den Linienpfad durch {@code points}: gerade Segmente, oder bei
     *  {@link LineMode#SPLINE} und mind. 3 Punkten eine glatte Catmull-Rom-Spline. */
    private Path2D buildLinePath(List<Point2DDouble> points) {
        Path2D path = new Path2D.Double();
        path.moveTo(points.getFirst().x, points.getFirst().y);

        if (lineMode == LineMode.SPLINE && points.size() > 2) {
            int last = points.size() - 1;
            for (int i = 0; i < last; i++) {
                Point2DDouble p0 = points.get(Math.max(i - 1, 0));
                Point2DDouble p1 = points.get(i);
                Point2DDouble p2 = points.get(i + 1);
                Point2DDouble p3 = points.get(Math.min(i + 2, last));

                double cp1x = p1.x + (p2.x - p0.x) / 6.0;
                double cp1y = p1.y + (p2.y - p0.y) / 6.0;
                double cp2x = p2.x - (p3.x - p1.x) / 6.0;
                double cp2y = p2.y - (p3.y - p1.y) / 6.0;

                path.curveTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y);
            }
        } else {
            for (int i = 1; i < points.size(); i++) {
                path.lineTo(points.get(i).x, points.get(i).y);
            }
        }

        return path;
    }

    /** Berechnet (bei Bedarf, siehe {@link #fitDirty}) und zeichnet die Fit-Kurve, begrenzt auf
     *  die Plotfläche. Jedes Modell braucht eine Mindestanzahl an Punkten, um überhaupt
     *  eindeutig lösbar zu sein (z. B. Grad n Polynom braucht n+1 Stützstellen). */
    private void drawFitOverlayClipped(Graphics2D g2, PlotGeometry geo) {
        currentFitDescription = null;

        if (fitDirty) {
            fitData = computeFitData();
        }

        Shape originalClip = g2.getClip();
        g2.clipRect(geo.padding, geo.padding, geo.plotWidth, geo.plotHeight);

        if (fitMode == FitMode.LINEAR || fitMode == FitMode.POLYNOMIAL) {
            int degree = (fitMode == FitMode.LINEAR) ? 1 : polynomialDegree;
            if (fitData.size() >= (degree + 1)) {
                ensureFitComputed(fitMode, degree);
                drawCachedFitIfPresent(g2, geo);
            }
        } else if (fitMode == FitMode.SINUS && fitData.size() >= 4) {
            ensureFitComputed(fitMode, 0);
            drawCachedFitIfPresent(g2, geo);
        } else if (fitMode == FitMode.EXPONENTIAL && fitData.size() >= 2) {
            ensureFitComputed(fitMode, 0);
            drawCachedFitIfPresent(g2, geo);
        } else if (fitMode == FitMode.POWER_LAW && fitData.size() >= 2) {
            ensureFitComputed(fitMode, 0);
            drawCachedFitIfPresent(g2, geo);
        }

        g2.setClip(originalClip);
    }

    /** Zeichnet den gecachten Fit samt Toleranzband und stößt die Chi²-Berechnung an. */
    private void drawCachedFitIfPresent(Graphics2D g2, PlotGeometry geo) {
        if (cachedFit == null) return;
        ensureSigmaComputed(cachedFit);
        calculateChiSquare(cachedFit.function, cachedFit.parameterCount);
        currentFitDescription = cachedFit.description;
        drawFunctionPathWithTolerance(g2, cachedFit.function, geo.minX, geo.visibleMaxX, geo.minY,
                geo.rangeX, geo.rangeY, geo.padding, geo.height, geo.plotWidth, geo.plotHeight, Theme.ACCENT);
    }

    /** Zeichnet die Messpunkte der Hauptgröße als Kreise, nur innerhalb der Plotfläche. */
    private void drawDataPoints(Graphics2D g2, PlotGeometry geo, List<Point2DDouble> points, List<double[]> data) {
        double pointSize = 7;
        int rightEdge = geo.width - geo.rightPadding;
        g2.setColor(Theme.POINT_A);
        for (int i = 0; i < points.size(); i++) {
            Point2DDouble pt = points.get(i);
            if (pt.x >= geo.padding && pt.x <= rightEdge
                    && pt.y >= geo.padding && pt.y <= geo.height - geo.padding) {
                if (colorByMagnitude) {
                    double normalized = (geo.rangeY > 1e-9) ? (data.get(i)[1] - geo.minY) / geo.rangeY : 0.5;
                    g2.setColor(magnitudeColor(normalized));
                }
                g2.fill(new Ellipse2D.Double(pt.x - pointSize / 2, pt.y - pointSize / 2, pointSize, pointSize));
            }
        }
    }

    /** Zeichnet das Rubber-Band-Auswahlrechteck während eines Linksklick-Ziehens. */
    private void drawSelectionRectangle(Graphics2D g2) {
        Point dragStart = viewport.getDragStart();
        Point dragEnd = viewport.getDragEnd();
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

    /** Zeichnet den Freihand-Lasso-Pfad während eines Rechtsklick-Ziehens. */
    private void drawFreehandStroke(Graphics2D g2) {
        List<Point> freehandPoints = viewport.getFreehandPoints();
        if (freehandPoints.isEmpty()) return;

        Path2D path = new Path2D.Double();
        path.moveTo(freehandPoints.getFirst().x, freehandPoints.getFirst().y);
        for (int i = 1; i < freehandPoints.size(); i++) {
            path.lineTo(freehandPoints.get(i).x, freehandPoints.get(i).y);
        }

        g2.setColor(new Color(Theme.ACCENT.getRed(), Theme.ACCENT.getGreen(), Theme.ACCENT.getBlue(), 50));
        g2.fill(path);
        g2.setColor(Theme.ACCENT);
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{4.0f}, 0));
        g2.draw(path);
    }

    /** Führt die Regression via {@link CurveFitting} nur aus, wenn Modus, Grad, Fit-Ziel oder
     *  Daten sich seit dem letzten Aufruf geändert haben (siehe {@link #fitDirty}) - die
     *  eigentliche Berechnung ist teuer genug, um sie nicht bei jedem repaint() zu wiederholen. */
    private void ensureFitComputed(FitMode mode, int degree) {
        if (!fitDirty && cachedFit != null && cachedFitModeUsed == mode && cachedDegreeUsed == degree
                && cachedFitTargetUsed == fitTarget) return;

        cachedFit = switch (mode) {
            case LINEAR, POLYNOMIAL -> CurveFitting.fitPolynomial(fitData, degree, xUnit, yUnit);
            case SINUS -> CurveFitting.fitSinus(fitData, xUnit, yUnit);
            case EXPONENTIAL -> CurveFitting.fitExponential(fitData, xUnit, yUnit);
            case POWER_LAW -> CurveFitting.fitPowerLaw(fitData, xUnit, yUnit);
            default -> null;
        };

        cachedFitModeUsed = mode;
        cachedDegreeUsed = degree;
        cachedFitTargetUsed = fitTarget;
        fitDirty = false;
    }

    /** Zeichnet die χ²_red-Anzeige-Box mit Info-Symbol oben rechts; aktualisiert dabei
     *  {@link #infoButtonBounds} für den Klick-Handler in {@link #ChartPanel()}. */
    private void drawChiSquareOverlay(Graphics2D g2, int width, int rightPadding, int topY) {
        boolean evaluable = !Double.isNaN(currentReducedChiSquare);
        String chiText = evaluable
                ? String.format("χ²_red = %.4f", currentReducedChiSquare)
                : "χ²_red = n/a";
        g2.setFont(new Font("SansSerif", Font.BOLD, 12));
        FontMetrics fm = g2.getFontMetrics();

        int textWidth = fm.stringWidth(chiText);
        int iconSize = 16;
        int totalWidth = textWidth + iconSize + 16;
        int boxHeight = CHI_OVERLAY_HEIGHT;

        int boxX = width - rightPadding - totalWidth - 5;

        Color statusColor = GoodnessOfFit.colorFor(currentReducedChiSquare);

        g2.setColor(Theme.PANEL);
        g2.fillRoundRect(boxX, topY, totalWidth, boxHeight, 8, 8);
        g2.setColor(Theme.BORDER);
        g2.drawRoundRect(boxX, topY, totalWidth, boxHeight, 8, 8);

        g2.setColor(statusColor);
        g2.drawString(chiText, boxX + 8, topY + 17);

        int iconX = boxX + textWidth + 10;
        int iconY = topY + 5;
        infoButtonBounds = new Rectangle(iconX, iconY, iconSize, iconSize);

        g2.setColor(statusColor);
        g2.fillOval(iconX, iconY, iconSize, iconSize);

        g2.setColor(Theme.BG);
        g2.setFont(new Font("Serif", Font.BOLD | Font.ITALIC, 11));
        g2.drawString("i", iconX + 6, iconY + 12);
    }

    /** Zeigt bei aktivem Fit und vorhandenen Extra-Serien an, worauf sich Fit/Chi² beziehen -
     *  sonst wäre bei zwei sichtbaren Kurven unklar, welche gemeint ist. */
    private void drawFitScopeNote(Graphics2D g2, PlotGeometry geo, int topY) {
        String note = "Fit & χ² beziehen sich auf " + fitTargetLabel();
        g2.setFont(new Font("SansSerif", Font.ITALIC, 10));
        FontMetrics fm = g2.getFontMetrics();
        int textWidth = fm.stringWidth(note);
        int boxX = geo.width - geo.rightPadding - textWidth - 11;

        g2.setColor(Theme.MUTED);
        g2.drawString(note, boxX, topY + 10);
    }

    private String fitTargetLabel() {
        return switch (fitTarget) {
            case A -> "Kanal A";
            case B -> "Kanal B";
            case BOTH -> "Kanal A + Kanal B";
        };
    }

    /** Öffnet den Detaildialog zur aktuellen Chi²-Bewertung (siehe {@link ChiSquareInfoDialog}). */
    private void showChiSquareInfoDialog() {
        Window parentWindow = SwingUtilities.getWindowAncestor(this);
        ChiSquareInfoDialog dialog = new ChiSquareInfoDialog(parentWindow, currentReducedChiSquare, currentDegreesOfFreedom, currentFitDescription, sigmaMode);
        dialog.setVisible(true);
    }

    /** Berechnet reduziertes Chi² und Freiheitsgrade über {@link GoodnessOfFit} und speichert
     *  sie für {@link #drawChiSquareOverlay} und {@link #showChiSquareInfoDialog}. */
    private void calculateChiSquare(CurveFitting.FunctionEvaluator func, int parameterCount) {
        GoodnessOfFit.ChiSquareResult result =
                GoodnessOfFit.calculateReducedChiSquare(fitData, func, parameterCount, this::sigmaForDataPoint);
        this.currentReducedChiSquare = result.reducedChiSquare;
        this.currentDegreesOfFreedom = result.degreesOfFreedom;
    }

    /** Liefert sigma für Datenpunkt {@code i} gemäß {@link #sigmaMode}; fällt ohne gültigen
     *  Cache auf {@link #standardDeviation} zurück. */
    private double sigmaForDataPoint(int i) {
        return switch (sigmaMode) {
            case RESIDUAL_LOCAL -> (cachedLocalSigmas != null && i < cachedLocalSigmas.length)
                    ? cachedLocalSigmas[i] : standardDeviation;
            case RESIDUAL_LOCAL_GAUSSIAN -> (cachedGaussianResiduals != null)
                    ? GoodnessOfFit.gaussianWeightedSigma(fitData, cachedGaussianResiduals, cachedGaussianBandwidth, fitData.get(i)[0])
                    : standardDeviation;
            default -> standardDeviation;
        };
    }

    /** Wie {@link #sigmaForDataPoint}, aber für einen beliebigen X-Wert entlang der Fit-Kurve
     *  interpoliert (für das Toleranzband, siehe {@link #drawFunctionPathWithTolerance}). */
    private double sigmaForToleranceBand(double x) {
        return switch (sigmaMode) {
            case RESIDUAL_LOCAL -> GoodnessOfFit.interpolateLocalSigma(fitData, cachedLocalSigmas, x, standardDeviation);
            case RESIDUAL_LOCAL_GAUSSIAN -> (cachedGaussianResiduals != null)
                    ? GoodnessOfFit.gaussianWeightedSigma(fitData, cachedGaussianResiduals, cachedGaussianBandwidth, x)
                    : standardDeviation;
            default -> standardDeviation;
        };
    }

    /** Berechnet die residuenbasierten Sigma-Schätzungen nur bei Bedarf neu (siehe
     *  {@link #sigmaCacheDirty}) - wie {@link #ensureFitComputed}, aber für die Sigma-Modi. */
    private void ensureSigmaComputed(CurveFitting.FitResult fit) {
        if (!sigmaCacheDirty) return;
        sigmaCacheDirty = false;

        CurveFitting.FunctionEvaluator func = (fit != null) ? fit.function : null;
        int paramCount = (fit != null) ? fit.parameterCount : 0;
        GoodnessOfFit.SigmaEstimate estimate =
                GoodnessOfFit.estimateSigma(fitData, func, sigmaMode, localSigmaNeighbors);

        cachedLocalSigmas = estimate.localSigmas;
        cachedGaussianResiduals = estimate.residuals;
        cachedGaussianBandwidth = estimate.gaussianBandwidth;
    }

    /** Zeichnet die Fit-Kurve als gestrichelte Linie mit einem sigma-breiten Toleranzband
     *  (400 Stützstellen über den sichtbaren X-Bereich). */
    private void drawFunctionPathWithTolerance(Graphics2D g2, CurveFitting.FunctionEvaluator func, double minX, double maxX, double minY,
                                               double rangeX, double rangeY, int padding, int height, int plotWidth, int plotHeight, Color color) {
        int steps = 400;
        double stepSize = (maxX - minX) / steps;

        double[] xVals = new double[steps + 1];
        double[] yVals = new double[steps + 1];
        double[] sigmaVals = new double[steps + 1];

        for (int i = 0; i <= steps; i++) {
            double x = minX + i * stepSize;
            xVals[i] = x;
            yVals[i] = func.eval(x);
            sigmaVals[i] = sigmaForToleranceBand(x);
        }

        Polygon bandPolygon = new Polygon();
        for (int i = 0; i <= steps; i++) {
            double yUpper = yVals[i] + sigmaVals[i];
            double px = padding + ((xVals[i] - minX) / rangeX) * plotWidth;
            double pyUpper = (height - padding) - ((yUpper - minY) / rangeY) * plotHeight;
            bandPolygon.addPoint((int) px, (int) pyUpper);
        }
        for (int i = steps; i >= 0; i--) {
            double yLower = yVals[i] - sigmaVals[i];
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

    /** Zeichnet Fadenkreuz und Koordinatenanzeige an der Mausposition; zeigt bei aktiver
     *  zweiter Achse beide Y-Werte (Kanal A und B) gleichzeitig an. */
    private void drawCrosshair(Graphics2D g2, PlotGeometry geo) {
        if (mousePoint == null) return;

        int mx = mousePoint.x;
        int my = mousePoint.y;
        int rightEdge = geo.width - geo.rightPadding;

        if (mx < geo.padding || mx > rightEdge || my < geo.padding || my > geo.height - geo.padding) return;

        g2.setColor(Theme.BORDER);
        g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{4.0f, 4.0f}, 0.0f));

        g2.drawLine(mx, geo.padding, mx, geo.height - geo.padding);
        g2.drawLine(geo.padding, my, rightEdge, my);

        double realX = geo.minX + ((double) (mx - geo.padding) / geo.plotWidth) * geo.rangeX;
        double realY = geo.minY + ((double) ((geo.height - geo.padding) - my) / geo.plotHeight) * geo.rangeY;

        String coordStr;
        if (geo.hasSecondaryAxis) {
            double realY2 = geo.minY2 + ((double) ((geo.height - geo.padding) - my) / geo.plotHeight) * geo.rangeY2;
            coordStr = String.format("X: %.2f %s | A: %.2f | B: %.2f", realX, xUnit, realY, realY2);
        } else {
            coordStr = String.format("X: %.2f %s | Y: %.2f", realX, xUnit, realY);
        }

        g2.setFont(Theme.FONT_HINT);
        FontMetrics fm = g2.getFontMetrics();
        int strWidth = fm.stringWidth(coordStr);

        int boxX = mx + 10;
        int boxY = my - 10;
        if (boxX + strWidth + 10 > rightEdge) boxX = mx - strWidth - 15;
        if (boxY - 15 < geo.padding) boxY = my + 20;

        g2.setColor(Theme.PANEL);
        g2.fillRoundRect(boxX, boxY - 12, strWidth + 8, 16, 6, 6);
        g2.setColor(Theme.BORDER);
        g2.drawRoundRect(boxX, boxY - 12, strWidth + 8, 16, 6, 6);
        g2.setColor(Theme.TEXT);
        g2.drawString(coordStr, boxX + 4, boxY);
    }
}