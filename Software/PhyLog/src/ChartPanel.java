import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Zeichnet ein X/Y-Diagramm (i. d. R. Zeit/Messwert, siehe {@link #setXAxisTitle} für Ausnahmen
 * wie das Frequenzspektrum) mit Zoom, Freihand-Auswahl und Fadenkreuz, und legt bei
 * Bedarf eine Fit-Kurve samt Chi²-Gütebewertung darüber. Die eigentliche Ausgleichsrechnung
 * übernimmt {@link CurveFitting}, die Bewertung über das reduzierte Chi-Quadrat und die
 * Sigma-Schätzung {@link GoodnessOfFit} - diese Klasse fügt beides nur noch zur Anzeige
 * zusammen und cached die (teuren) Ergebnisse.
 *
 * <p>Diese Klasse kennt keine Sensoren oder Hardware. Sie bekommt ausschließlich fertige
 * (Zeit, Messwert)-Paare über {@link #setData(List)} übergeben - unabhängig davon, ob diese
 * aus einer CSV-Datei importiert oder live von einem angeschlossenen Sensor geliefert wurden.</p>
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

    /** Wie die Messpunkte einer Kurve verbunden werden: gar nicht, gerade (Polylinie) oder als
     *  glatte Spline (Catmull-Rom) durch alle Punkte - siehe {@link #setLineMode}. */
    public enum LineMode { NONE, STRAIGHT, SPLINE }

    /** Auf welche Messgröße(n) sich Fit und Chi² beziehen sollen - siehe {@link #setFitTarget}.
     *  Betrifft ausschließlich die Ausgleichsrechnung; Zoom/Freihand-Auswahl bleiben weiterhin
     *  exklusiv an die Hauptgröße (Kanal A, siehe {@link #originalData}) gebunden, siehe
     *  {@link #computeFitData()}. */
    public enum FitTarget {
        /** Nur die Hauptgröße ({@link #displayData}, i. d. R. Kanal A) - bisheriges Verhalten. */
        A,
        /** Nur die erste Extra-Serie (i. d. R. Kanal B, siehe {@link #extraSeries}). */
        B,
        /** Beide Größen gemeinsam, nach X aufsteigend zusammengeführt - z. B. sinnvoll, wenn
         *  beide Kanäle dieselbe physikalische Größe leicht versetzt messen und ein gemeinsamer
         *  Fit über beide Punktwolken gebildet werden soll. */
        BOTH
    }

    /** Höhe der Chi²-Anzeige-Box (siehe {@link #drawChiSquareOverlay}) - als Konstante geteilt,
     *  falls andere Stellen sie einmal mit einbeziehen müssen. */
    private static final int CHI_OVERLAY_HEIGHT = 26;

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

    /**
     * Fasst die für einen einzelnen Zeichendurchlauf benötigte Pixel- und Datenraum-Geometrie
     * zusammen (Panelgröße, Innenabstand, Plotfläche, sichtbarer Datenbereich inkl. Zoom).
     * Wird einmal pro {@link #paintComponent(Graphics)} berechnet und an alle Zeichenschritte
     * weitergereicht, damit sie konsistent dieselbe Koordinatenabbildung verwenden.
     */
    private static class PlotGeometry {
        final int width, height, padding, rightPadding, plotWidth, plotHeight;
        final double minX, maxX, minY, maxY, rangeX, rangeY, visibleMaxX;
        /** {@code true}, wenn eine zweite, unabhängig skalierte Y-Achse für Kanal B gezeichnet
         *  wird (siehe {@link #dualYAxisMode}) - nur dann sind {@link #minY2}/{@link #maxY2}/
         *  {@link #rangeY2} gültig. */
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

    /** Einfacher (double-x, double-y)-Bildschirmpunkt, um Rundungsfehler bei kleinen Panels zu vermeiden. */
    private static class Point2DDouble {
        double x, y;
        Point2DDouble(double x, double y) { this.x = x; this.y = y; }
    }

    /** Unveränderte, zuletzt über {@link #setData(List)} gesetzte Messdaten (Hauptgröße - einzige,
     *  die Zoom, Freihand-Auswahl, Fit und Chi² einbezieht). Wird von Zoom/Freihand-Auswahl NIE
     *  verändert - siehe {@link #viewport} für den eigentlichen Zoom-Zustand. */
    private List<double[]> originalData = new ArrayList<>();
    /** Auf das aktuelle Zoom-/Auswahlfenster eingeschränkte Teilmenge von {@link #originalData}
     *  (siehe {@link #recomputeDisplayData()}) - diese, nicht {@link #originalData}, geht in
     *  Achsenbereich, Fit und Chi² ein, damit "näher heranzoomen" auch die Anpassung auf den
     *  sichtbaren Bereich eingrenzt (siehe Hinweistext in {@link ChiSquareInfoDialog}). */
    private List<double[]> displayData = new ArrayList<>();

    /** Aktuelles Zoom-/Auswahlfenster (Rubber-Band- oder Freihand-Auswahl) sowie Zoom-Faktor und
     *  der flüchtige Zustand einer laufenden Maus-Interaktion - siehe {@link ChartViewport}.
     *  Anders als früher wird dabei nie ein Datenpunkt aus {@link #originalData} gelöscht:
     *  {@link #recomputeDisplayData()} leitet {@link #displayData} bei jeder Änderung (neue
     *  Messwerte, neues Fenster, Reset) frisch aus {@link #originalData} ab, das Fenster bleibt
     *  bis zum expliziten Zurücksetzen erhalten und übersteht so auch eintreffende Live-Messwerte. */
    private final ChartViewport viewport = new ChartViewport();

    /** Zusätzliche, gleichzeitig dargestellte Kurven (siehe {@link Series}). Nehmen nicht an
     *  Zoom, Freihand-Auswahl, Fit oder Chi² teil - das bleibt der Hauptgröße vorbehalten. */
    private List<Series> extraSeries = new ArrayList<>();

    private String xUnit = "s";
    /** Titelwort vor der Einheit auf der X-Achse (z. B. "Zeit" oder "Frequenz", siehe
     *  {@link #setXAxisTitle}) - getrennt von {@link #xUnit}, damit Achsentitel und Einheit
     *  unabhängig voneinander gesetzt werden können (z. B. für die Frequenzspektrum-Anzeige). */
    private String xAxisTitle = "Zeit";
    private String yUnit = "Messwert";
    /** Bezeichnung der Hauptmessgröße (Kanal A) für die Legende - unabhängig vom Achsentitel,
     *  damit dieser bei zwei Kanälen generisch bleiben kann (siehe {@link #setUnits}). */
    private String mainLabel = "Kanal A";

    /** Beschriftung der (optionalen) zweiten Y-Achse für Kanal B, nur relevant, solange
     *  {@link #dualYAxisMode} aktiv ist und eine Extra-Serie mit Daten existiert - siehe
     *  {@link #setSecondaryUnits}. */
    private String secondaryYUnit = "Messwert";

    private boolean showPoints = true;
    private LineMode lineMode = LineMode.NONE;
    /** {@code true}, wenn Messpunkte statt in ihrer festen Serienfarbe nach ihrem Y-Wert gefärbt
     *  werden sollen (siehe {@link #magnitudeColor}) - genutzt für die Frequenzspektrum-Anzeige,
     *  wo die Farbe zusätzlich zur Höhe auf einen Blick zeigt, wie laut ein Frequenzanteil ist. */
    private boolean colorByMagnitude = false;
    private FitMode fitMode = FitMode.NONE;
    private int polynomialDegree = 2;

    /** Auf welche Messgröße(n) sich der aktuelle Fit bezieht, siehe {@link FitTarget}. */
    private FitTarget fitTarget = FitTarget.A;

    /** Die tatsächlich für Fit/Chi²/Sigma-Schätzung verwendeten Datenpunkte - je nach
     *  {@link #fitTarget} identisch zu {@link #displayData} oder daraus per
     *  {@link #computeFitData()} abgeleitet. Getrennt von {@link #displayData} gehalten, damit
     *  Letzteres unverändert für die Punkt-/Linien-Darstellung von Kanal A zuständig bleibt,
     *  auch wenn sich der Fit gerade auf Kanal B oder beide Kanäle bezieht. */
    private List<double[]> fitData = new ArrayList<>();

    /**
     * {@code true}, wenn Kanal B (falls aktiv, siehe {@link #extraSeries}) über eine eigene,
     * unabhängig skalierte zweite Y-Achse dargestellt wird, statt sich - wie im bisherigen,
     * weiterhin verfügbaren Standardverhalten - dieselbe Achse mit Kanal A zu teilen. Betrifft
     * ausschließlich die Darstellung: Zoom, Freihand-Auswahl, Fit und Chi² bleiben unabhängig
     * davon exklusiv an Kanal A (die Hauptgröße) gebunden, siehe {@link #computeSecondaryRange}.
     * Ist diese zweite Achse aktiv, zeigen ihre farbig markierten Achsentitel bereits an, welche
     * Farbe zu welchem Kanal gehört - die separate Farb-Legende (siehe {@link #drawLegend}) wäre
     * dann redundant und bleibt ausgeblendet, solange nur eine gemeinsame Achse genutzt wird.
     */
    private boolean dualYAxisMode = false;

    private Point mousePoint = null;

    /** Klickfläche des kleinen "i"-Symbols neben der Chi²-Anzeige, wird bei jedem Zeichnen aktualisiert. */
    private Rectangle infoButtonBounds = new Rectangle();
    private double currentReducedChiSquare = 0.0;
    private int currentDegreesOfFreedom = 1;

    /** Angenommene (konstante) Standardabweichung der Messwerte, geht als sigma in Chi^2 ein.
     *  Dient außerdem als Rückfallebene für die automatischen Modi, solange kein Fit aktiv ist. */
    private double standardDeviation = 1.0;

    /** Wie sigma bestimmt wird (siehe {@link GoodnessOfFit.SigmaMode}). */
    private GoodnessOfFit.SigmaMode sigmaMode = GoodnessOfFit.SigmaMode.CONSTANT;
    /** Nachbarschaftsgröße k für die automatischen Modi (siehe {@link GoodnessOfFit.SigmaMode}). */
    private int localSigmaNeighbors = 8;
    /** {@code true}, wenn die aus Residuen abgeleiteten Sigma-Werte neu berechnet werden müssen
     *  (siehe {@link #ensureSigmaComputed}) - analog zu {@link #fitDirty}, aber unabhängig davon
     *  auch bei einem reinen Moduswechsel gesetzt. */
    private boolean sigmaCacheDirty = true;
    /** Zwischengespeicherte, je Punkt in {@link #displayData} passende Sigma-Werte für
     *  {@link GoodnessOfFit.SigmaMode#RESIDUAL_LOCAL}, {@code null} in den anderen Modi. */
    private double[] cachedLocalSigmas = null;
    /** Zwischengespeicherte Residuen samt Bandbreite für {@link GoodnessOfFit.SigmaMode#RESIDUAL_LOCAL_GAUSSIAN},
     *  {@code null} in den anderen Modi (siehe {@link GoodnessOfFit#gaussianWeightedSigma}). */
    private double[] cachedGaussianResiduals = null;
    private double cachedGaussianBandwidth = 0;

    // --- Fit-Cache ---
    // Die komplette Regression (inkl. der iterativen Sinus-Anpassung) ist teuer und muss nicht
    // bei jedem repaint() neu berechnet werden - z. B. nicht nur wegen einer Mausbewegung fürs
    // Fadenkreuz. Es wird nur neu gefittet, wenn sich Daten, Fit-Typ oder Polynomgrad tatsächlich
    // geändert haben. Die Standardabweichung beeinflusst nur Chi^2, nicht die Kurvenparameter
    // selbst, und löst deshalb bewusst KEINEN Refit aus.
    private boolean fitDirty = true;
    private CurveFitting.FitResult cachedFit = null;
    private FitMode cachedFitModeUsed = null;
    private int cachedDegreeUsed = -1;
    private FitTarget cachedFitTargetUsed = null;

    /** Beschreibung des zuletzt gezeichneten Fits (siehe {@link #drawCachedFitIfPresent}),
     *  {@code null} solange kein Fit aktiv/berechenbar ist. */
    private CurveFitting.FitDescription currentFitDescription = null;

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
     * Setzt die anzuzeigenden Messdaten komplett neu (z. B. nach einem CSV-Import oder nach dem
     * Empfang neuer Live-Messwerte). Ein aktives Zoom-/Auswahlfenster (siehe {@link #viewport})
     * bleibt dabei bewusst erhalten - sonst würde jeder neu eintreffende Live-Messwert den
     * gerade gesetzten Zoom sofort wieder aufheben. Zum Zurücksetzen dient {@link #resetZoom()}.
     *
     * @param data Liste von (Zeit, Messwert)-Paaren, {@code null} wird als leere Liste behandelt
     */
    public void setData(List<double[]> data) {
        this.originalData = (data != null) ? new ArrayList<>(data) : new ArrayList<>();
        recomputeDisplayData();
        fitDirty = true;
        sigmaCacheDirty = true;
        repaint();
    }

    /** Leitet {@link #displayData} aus {@link #originalData} ab: unverändert ohne aktives
     *  Zoom-/Auswahlfenster, sonst auf das Fenster aus {@link #viewport} eingeschränkt.
     *  {@link #originalData} selbst wird dabei nie verändert - ein Zoom kann also jederzeit über
     *  {@link #resetZoom()} rückgängig gemacht werden, ohne dass zwischenzeitlich Messwerte
     *  verloren gegangen wären. */
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

    /** Wird aufgerufen, nachdem {@link #viewport} ein neues Zoom-/Auswahlfenster gesetzt hat
     *  (Rubber-Band- oder Freihand-Auswahl) - zieht {@link #displayData} nach und invalidiert
     *  Fit- und Sigma-Cache, analog zu {@link #resetZoom()}. */
    private void onViewportWindowChanged() {
        recomputeDisplayData();
        fitDirty = true;
        sigmaCacheDirty = true;
    }

    /** Schränkt {@code data} auf das aktuelle Zoom-/Auswahlfenster ein, nach demselben Prinzip
     *  wie {@link #recomputeDisplayData()} es für die Hauptgröße tut - Zoom/Freihand-Auswahl
     *  selbst bleiben weiterhin exklusiv an Kanal A gebunden (siehe {@link ChartViewport}), ein
     *  gesetztes Fenster schränkt aber sinnvollerweise auch einen auf Kanal B bezogenen Fit auf
     *  denselben sichtbaren Bereich ein, statt Punkte außerhalb des Zooms einzubeziehen. */
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

    /** Daten der ersten Extra-Serie (i. d. R. Kanal B), oder eine leere Liste, falls (noch)
     *  keine Extra-Serie gesetzt ist - siehe {@link #setExtraSeries}. */
    private List<double[]> firstExtraSeriesData() {
        return (extraSeries != null && !extraSeries.isEmpty() && extraSeries.get(0).data != null)
                ? extraSeries.get(0).data : new ArrayList<>();
    }

    /**
     * Baut den für den aktuellen {@link #fitTarget} tatsächlich zu fittenden Datensatz:
     * unverändert {@link #displayData} für {@link FitTarget#A}, die (aufs Zoom-Fenster
     * eingeschränkte) erste Extra-Serie für {@link FitTarget#B}, bzw. beides nach X aufsteigend
     * zusammengeführt für {@link FitTarget#BOTH} - {@link GoodnessOfFit}s fensterbasierte
     * Sigma-Schätzung setzt aufsteigend sortierte Daten voraus (siehe dort).
     */
    private List<double[]> computeFitData() {
        return switch (fitTarget) {
            case B -> filterToViewport(firstExtraSeriesData());
            case BOTH -> {
                List<double[]> combined = new ArrayList<>(displayData);
                combined.addAll(filterToViewport(firstExtraSeriesData()));
                combined.sort((p1, p2) -> Double.compare(p1[0], p2[0]));
                yield combined;
            }
            default -> displayData;
        };
    }

    /** Baut die für {@link ChartViewport}s Pixel-zu-Daten-Umrechnung nötige, reduzierte Geometrie
     *  aus der aktuellen {@link PlotGeometry}, oder {@code null}, wenn das Panel gerade zu klein
     *  zum Zeichnen ist (siehe {@link #computePlotGeometry()}). */
    private ChartViewport.Geometry viewportGeometry() {
        PlotGeometry geo = computePlotGeometry();
        if (geo == null) return null;
        return new ChartViewport.Geometry(geo.padding, geo.plotWidth, geo.plotHeight, geo.height,
                geo.minX, geo.rangeX, geo.minY, geo.rangeY);
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
        // Nur invalidieren, wenn der aktuelle Fit überhaupt von Kanal-B-Daten abhängt (siehe
        // #fitTarget) - sonst würde jede Aktualisierung von Kanal B (z. B. während einer
        // laufenden Aufzeichnung) unnötig einen teuren Refit von Kanal A erzwingen, den
        // {@link #fitDirty} eigentlich gerade vermeiden soll.
        if (fitTarget != FitTarget.A) {
            fitDirty = true;
            sigmaCacheDirty = true;
        }
        repaint();
    }

    /**
     * Legt die Achsenbeschriftungen fest. Der Aufrufer entscheidet über den Text der Y-Achse -
     * bei mehreren gleichzeitig dargestellten Größen (siehe {@link #setExtraSeries}) sollte er
     * bewusst generisch bleiben ("Messwerte"), da die Achse nicht mehreren Einheiten zugleich
     * gerecht werden kann. Welche Einheit zu welcher Kurve gehört, zeigt stattdessen die Legende
     * (siehe {@link #setMainLabel} und {@link #drawLegend}).
     *
     * @param xUnit  Einheit der X-Achse (z. B. "s"), {@code null} fällt auf "s" zurück
     * @param yLabel Beschriftung der Y-Achse, {@code null}/leer fällt auf "Messwert" zurück
     */
    public void setUnits(String xUnit, String yLabel) {
        this.xUnit = (xUnit != null) ? xUnit : "s";
        this.yUnit = (yLabel != null && !yLabel.isBlank()) ? yLabel.trim() : "Messwert";
        repaint();
    }

    /** Setzt das Titelwort vor der X-Achsen-Einheit (Standard: "Zeit"), z. B. "Frequenz" für die
     *  Frequenzspektrum-Anzeige (siehe {@link #setUnits}, das nur die Einheit selbst setzt). */
    public void setXAxisTitle(String title) {
        this.xAxisTitle = (title != null && !title.isBlank()) ? title.trim() : "Zeit";
        repaint();
    }

    /** Setzt die Bezeichnung der Hauptmessgröße (Kanal A) für die Legende. */
    public void setMainLabel(String label) {
        this.mainLabel = (label != null && !label.isBlank()) ? label.trim() : "Kanal A";
        repaint();
    }

    /** Setzt die Beschriftung der zweiten Y-Achse (Kanal B), wirkt sich nur aus, solange
     *  {@link #dualYAxisMode} aktiv ist (siehe {@link #setDualYAxisMode}).
     *
     * @param yLabel Beschriftung, {@code null}/leer fällt auf "Messwert" zurück
     */
    public void setSecondaryUnits(String yLabel) {
        this.secondaryYUnit = (yLabel != null && !yLabel.isBlank()) ? yLabel.trim() : "Messwert";
        repaint();
    }

    /**
     * Legt fest, ob Kanal B (falls über {@link #setExtraSeries} mit Daten belegt) eine eigene,
     * unabhängig skalierte zweite Y-Achse bekommt ({@code true}), oder wie bisher dieselbe Achse
     * wie Kanal A teilt ({@code false}, Standard). Reine Darstellungsoption - Fit, Zoom, Freihand-
     * Auswahl und Chi² bleiben in beiden Fällen exklusiv an Kanal A gebunden.
     */
    public void setDualYAxisMode(boolean dualYAxisMode) {
        this.dualYAxisMode = dualYAxisMode;
        repaint();
    }

    public boolean isDualYAxisMode() {
        return dualYAxisMode;
    }

    /** @param showPoints ob die einzelnen Messpunkte als Kreise gezeichnet werden sollen */
    public void setShowPoints(boolean showPoints) {
        this.showPoints = showPoints;
        repaint();
    }

    /** @param lineMode wie die Messpunkte verbunden werden sollen (siehe {@link LineMode}) */
    public void setLineMode(LineMode lineMode) {
        this.lineMode = (lineMode != null) ? lineMode : LineMode.NONE;
        repaint();
    }

    /** @param colorByMagnitude ob Messpunkte nach ihrem Y-Wert statt in fester Serienfarbe
     *                          gefärbt werden sollen (siehe {@link #magnitudeColor}) */
    public void setColorByMagnitude(boolean colorByMagnitude) {
        this.colorByMagnitude = colorByMagnitude;
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
        sigmaCacheDirty = true;
        repaint();
    }

    public FitMode getFitMode() { return fitMode; }

    /**
     * Legt fest, auf welche Messgröße(n) sich Fit und Chi² beziehen sollen (siehe
     * {@link FitTarget}) und markiert Fit- und Sigma-Cache als veraltet, damit beim nächsten
     * Zeichnen mit dem neuen Datensatz neu gefittet wird.
     *
     * @param fitTarget das gewünschte Ziel, {@code null} fällt auf {@link FitTarget#A} zurück
     */
    public void setFitTarget(FitTarget fitTarget) {
        this.fitTarget = (fitTarget != null) ? fitTarget : FitTarget.A;
        fitDirty = true;
        sigmaCacheDirty = true;
        repaint();
    }

    public FitTarget getFitTarget() { return fitTarget; }

    /**
     * Legt den Polynomgrad für {@link FitMode#POLYNOMIAL} fest (wird auf 1..10 begrenzt)
     * und markiert den Fit-Cache als veraltet.
     *
     * @param degree gewünschter Polynomgrad
     */
    public void setPolynomialDegree(int degree) {
        this.polynomialDegree = Math.max(1, Math.min(10, degree));
        fitDirty = true;
        sigmaCacheDirty = true;
        repaint();
    }

    /**
     * Legt den konstanten Sigma-Wert fest, der bei {@link GoodnessOfFit.SigmaMode#CONSTANT} direkt
     * verwendet wird und in den automatischen Modi als Rückfallebene ohne aktiven Fit dient.
     * Beeinflusst nur Chi²/Toleranzband (sigma steht als Faktor in der Summe), nicht die
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

    /**
     * Legt fest, wie sigma bestimmt wird (siehe {@link GoodnessOfFit.SigmaMode}). Markiert den
     * Sigma-Cache als veraltet, damit ein automatischer Modus beim nächsten Zeichnen aus dem
     * aktuellen Fit neu geschätzt wird - löst bewusst keinen Refit aus, da sich die
     * Kurvenparameter dadurch nicht ändern.
     *
     * @param sigmaMode der gewünschte Modus, {@code null} fällt auf {@link GoodnessOfFit.SigmaMode#CONSTANT} zurück
     */
    public void setSigmaMode(GoodnessOfFit.SigmaMode sigmaMode) {
        this.sigmaMode = (sigmaMode != null) ? sigmaMode : GoodnessOfFit.SigmaMode.CONSTANT;
        sigmaCacheDirty = true;
        repaint();
    }

    public GoodnessOfFit.SigmaMode getSigmaMode() { return sigmaMode; }

    /**
     * Legt die Anzahl der je Punkt einbezogenen Nachbarn für {@link GoodnessOfFit.SigmaMode#RESIDUAL_LOCAL}
     * fest (mindestens 2) und markiert den Sigma-Cache als veraltet.
     *
     * @param neighbors gewünschte Nachbarn-Anzahl
     */
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

    /** Verkleinert den angezeigten Ausschnitt um den Faktor 1.2 (Mindestfaktor 0.1). */
    public void zoomOut() {
        viewport.zoomOut();
        repaint();
    }

    /** Setzt Zoom-Faktor und Zoom-/Auswahlfenster zurück auf die vollständigen Messdaten. */
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

        // Prüfen, ob IRGENDWELCHE Daten vorliegen (Hauptdaten oder Extra-Serien)
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

        // Legende (Kanal-Einheiten) hat, sofern sichtbar (siehe #legendVisible), Vorrang oben
        // rechts; die Chi²-Anzeige rutscht darunter - bzw. bleibt oben in der Ecke, wenn keine
        // Legende gezeichnet wird.
        int legendTopY = geo.padding + 6;
        drawLegend(g2, geo, legendTopY);

        if (fitMode != FitMode.NONE) {
            int chiOverlayY = legendTopY;
            if (legendVisible()) {
                chiOverlayY += legendHeight() + 6;
            }
            drawChiSquareOverlay(g2, geo.width, geo.rightPadding, chiOverlayY);

            // Zoom bleibt immer exklusiv an Kanal A gebunden (siehe ChartViewport), Fit und Chi²
            // dagegen je nach {@link #fitTarget} an Kanal A, Kanal B oder beide zugleich - sobald
            // eine Extra-Kurve (Kanal B) mitgezeichnet wird, macht ein kurzer Hinweis unmissver-
            // ständlich, welche Größe(n) gerade gemeint sind, statt es der Doku zu überlassen.
            if (!extraSeries.isEmpty()) {
                drawFitScopeNote(g2, geo, chiOverlayY + CHI_OVERLAY_HEIGHT + 4);
            }
        }

        drawSelectionRectangle(g2);
        drawFreehandStroke(g2);

        drawCrosshair(g2, geo);
        g2.dispose();
    }

    /**
     * Zeichnet zusätzliche, gleichzeitig dargestellte Kurven (siehe {@link #setExtraSeries}) in
     * ihrer jeweils eigenen Farbe - als Punkte und (falls aktiviert) Verbindungslinie, auf
     * derselben (die Extra-Kurven bereits einschließenden) Achsenskalierung wie die Hauptgröße.
     */
    private void drawExtraSeries(Graphics2D g2, PlotGeometry geo) {
        double pointSize = 6;
        // Bei aktiver zweiter Achse (siehe {@link #dualYAxisMode}) projizieren die Extra-Serien
        // auf deren eigene Skala (minY2/rangeY2), statt - wie im Standardfall - dieselbe Skala
        // wie Kanal A zu verwenden.
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

    /**
     * Ob die Farb-Legende (Hauptgröße + Extra-Kurven) gezeichnet werden soll: nur, wenn
     * tatsächlich mehr als eine Größe gleichzeitig dargestellt wird UND die Achsen sich eine
     * gemeinsame Skala teilen ({@link #dualYAxisMode} = false) UND für die Hauptgröße auch
     * wirklich Daten vorliegen. Ohne diese letzte Bedingung würde z. B. bei nur aktivem Kanal B
     * (Hauptgröße/Kanal A leer, Kanal B nur als Extra-Kurve gesetzt) trotzdem eine Legende mit
     * einem bedeutungslosen "Kanal A"-Eintrag erscheinen, obwohl nur eine einzige Größe zu sehen
     * ist - deren Bezeichnung steht in diesem Fall bereits direkt am Achsentitel (siehe
     * {@code GUI#updateChartUnits}). Mit aktiver zweiter Y-Achse zeigen deren farbig markierte
     * Achsentitel bereits an, welche Farbe zu welchem Kanal gehört (siehe {@link #drawGridAndAxes}) -
     * die Legende wäre dort ebenfalls nur redundant.
     */
    private boolean legendVisible() {
        return !extraSeries.isEmpty() && !dualYAxisMode && !originalData.isEmpty();
    }

    /** Höhe der Legendenbox in Pixeln, oder 0 ohne sichtbare Legende (siehe {@link #legendVisible()})
     *  - gemeinsam von {@link #drawLegend} und {@link #paintComponent} genutzt, damit Chi²-Anzeige
     *  und Legende nie überlappen, unabhängig davon, welche der beiden oben steht. */
    private int legendHeight() {
        if (!legendVisible()) return 0;
        int rowHeight = 16;
        return (1 + extraSeries.size()) * rowHeight + 8;
    }

    /**
     * Zeichnet eine kleine Legende (Farbe → Messgröße) oben rechts im Plot, sofern
     * {@link #legendVisible()} das verlangt.
     *
     * @param topY Obere Kante der Legende in Bildschirmkoordinaten
     */
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
        int boxY = topY;

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

        boolean hasExtraData = false;
        if (extraSeries != null) {
            for (Series s : extraSeries) {
                if (s.data != null && !s.data.isEmpty()) {
                    hasExtraData = true;
                    break;
                }
            }
        }
        // Zweite Achse nur zeichnen, wenn der Modus aktiv ist UND es überhaupt etwas gibt, das
        // sie beschriften würde - sonst bräuchte man den zusätzlichen rechten Rand umsonst.
        boolean secondaryAxisActive = dualYAxisMode && hasExtraData;
        int rightPadding = secondaryAxisActive ? 95 : padding;

        int plotWidth = width - padding - rightPadding;
        int plotHeight = height - 2 * padding;

        if (plotWidth <= 0 || plotHeight <= 0) return null;

        double minX, maxX, minY, maxY;
        double minY2 = 0, maxY2 = 10;

        if (viewport.isActive()) {
            // Ein Zoom-/Auswahlfenster ist aktiv: DAS ist die Achsen-Spanne für Kanal A (bzw. für
            // beide, im bisherigen Ein-Achsen-Modus). Würde man sie stattdessen wie unten aus den
            // Daten neu berechnen, würde die volle Ausdehnung der (nicht gefilterten) Zusatzserien
            // die Achse sofort wieder auf die Gesamtbreite aufziehen und den Zoom optisch
            // aufheben, obwohl displayData korrekt gefiltert ist.
            minX = viewport.getMinX(); maxX = viewport.getMaxX();
            minY = viewport.getMinY(); maxY = viewport.getMaxY();

            if (secondaryAxisActive) {
                // Die zweite Achse ist bewusst NICHT an das (auf Kanal A bezogene) Zoom-Fenster
                // gekoppelt, sondern skaliert sich innerhalb des sichtbaren X-Bereichs frisch auf
                // die tatsächlichen Kanal-B-Werte - ein Zoom auf Kanal A soll Kanal B nicht
                // willkürlich mit stauchen oder strecken.
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
                                if (!secondaryAxisActive) {
                                    // Legacy-Verhalten: Extra-Kurven teilen sich die Y-Achse mit
                                    // Kanal A, gehen also mit in deren Wertebereich ein. Mit
                                    // eigener zweiter Achse (siehe unten) bleiben sie hiervon
                                    // bewusst ausgenommen.
                                    if (point[1] < minY) minY = point[1];
                                    if (point[1] > maxY) maxY = point[1];
                                }
                            }
                        }
                    }
                }

                if (minX == Double.MAX_VALUE) minX = 0; // Fallback falls doch etwas schiefgeht
                if (minY == Double.MAX_VALUE) { minY = 0; maxY = 10; } // nur Kanal-B-Daten, keine Kanal-A-Werte
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

    /**
     * Bestimmt den Y-Wertebereich der Extra-Serien (Kanal B) innerhalb des sichtbaren
     * X-Fensters [{@code minX}, {@code maxX}] - Grundlage der unabhängig skalierten zweiten
     * Y-Achse (siehe {@link #dualYAxisMode}). Liegt (noch) kein Kanal-B-Punkt im sichtbaren
     * Fenster, weicht die Methode auf den gesamten Kanal-B-Datensatz aus, damit die Achse nicht
     * grundlos auf 0..10 zurückfällt.
     */
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

    /**
     * Zeichnet das Hintergrundgitter, die Achsenlinien, die Tick-Beschriftungen und die
     * Achsentitel ("Zeit (Einheit)" / Messwert-Einheit).
     */
    private void drawGridAndAxes(Graphics2D g2, PlotGeometry geo) {
        int padding = geo.padding;
        int height = geo.height;
        int width = geo.width;
        int plotWidth = geo.plotWidth;
        int plotHeight = geo.plotHeight;
        int rightEdge = width - geo.rightPadding;
        // Die linke Achse färbt sich nur dann in Kanal-A-Farbe ein, wenn wirklich beide Kanäle
        // gleichzeitig genutzt werden (aktive zweite Achse, siehe #hasSecondaryAxis) - bei nur
        // einem aktiven Sensor bleibt sie wie bisher neutral weiß, da es dort nichts von Kanal B
        // abzugrenzen gibt.
        Color primaryAxisColor = geo.hasSecondaryAxis ? Theme.POINT_A : Theme.TEXT;
        // Sehr schwache, nur angedeutete Variante der jeweiligen Achsenfarbe für die
        // horizontalen Gitterlinien (siehe unten) - dezent statt dominant.
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

            // Im Ein-Achsen-Modus (kein zweiter Sensor aktiv) bleibt die klassische, neutrale
            // Gitterlinie erhalten. Erst mit aktiver zweiter Achse (siehe #hasSecondaryAxis)
            // zeichnen beide Kanäle ihre eigene, ganz schwach angedeutete Gitterlinie in ihrer
            // jeweiligen Farbe an derselben Bildschirmzeile - dezent statt dominant, damit es bei
            // zwei gleichzeitig sichtbaren (weil unterschiedlich skalierten) Referenzlinien pro
            // Reihe nicht überladen wirkt.
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

                // Zusätzliche, ebenso schwache Gitterlinie für Kanal B in dessen Farbe, an
                // derselben Bildschirmzeile - beide Linien treffen sich hier nur auf dem Papier
                // (an dieser Pixelreihe), nicht inhaltlich (Kanal A und B haben an dieser Stelle
                // i. A. unterschiedliche tatsächliche Werte, siehe Tick-Beschriftungen).
                g2.setColor(withAlpha(secondaryAxisColor(), 40));
                g2.drawLine(padding, y, rightEdge, y);

                g2.setColor(secondaryAxisColor());
                g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
                g2.drawString(String.format("%.2f", valY2), rightEdge + 8, y + 4);
            }
        }

        // Zeitachse (unten) bleibt neutral eingefärbt, da sie für beide Kanäle gemeinsam gilt.
        g2.setColor(Theme.TEXT);
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawLine(padding, height - padding, rightEdge, height - padding);

        // Linke Y-Achse in Kanal-A-Farbe (nur bei aktiver zweiter Achse, siehe oben), rechte
        // (falls vorhanden) in Kanal-B-Farbe.
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

    /** Liefert {@code color} mit auf {@code alpha} (0..255) gesetzter Deckkraft - Hilfsmethode
     *  für die dezenten, farbigen horizontalen Gitterlinien (siehe {@link #drawGridAndAxes}). */
    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    /** Mehrstufiger, kühl-nach-warm-Farbverlauf für {@link #colorByMagnitude}: leise/niedrige
     *  Werte (normalized nahe 0) erscheinen kühl-blau, laute/hohe Werte (nahe 1) warm-rot -
     *  gedacht für die Frequenzspektrum-Anzeige, wo Farbe zusätzlich zur Balkenhöhe auf einen
     *  Blick zeigt, welche Frequenzanteile dominieren.
     *
     * @param normalized Wert zwischen 0 und 1 (wird andernfalls dorthin begrenzt)
     */
    private static Color magnitudeColor(double normalized) {
        double t = Math.max(0.0, Math.min(1.0, normalized));
        Color[] stops = {
                new Color(60, 70, 200),   // leise: kühles Blau
                new Color(40, 180, 190),  // Türkis
                new Color(250, 170, 40),  // Orange
                new Color(230, 60, 60)    // laut: Rot
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

    /** Farbe der zweiten Y-Achse - dieselbe wie die (aktuell einzige mögliche) Kanal-B-Kurve,
     *  damit auf einen Blick klar ist, welche Achse zu welcher Kurve gehört. */
    private Color secondaryAxisColor() {
        return (extraSeries != null && !extraSeries.isEmpty()) ? extraSeries.get(0).color : Theme.TEXT;
    }

    /** Zeigt den Platzhaltertext an, solange keine Messdaten vorliegen. */
    private void drawEmptyDataMessage(Graphics2D g2, PlotGeometry geo) {
        g2.setColor(Theme.TEXT);
        g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g2.drawString("Keine Messdaten vorhanden", geo.width / 2 - 70, geo.height / 2);
    }

    /** Projiziert die übergebenen Datenpunkte (typischerweise {@link #displayData} bzw. dessen
     *  über {@link #downsampleForRendering} reduzierte Fassung) in Bildschirmkoordinaten. */
    private List<Point2DDouble> projectDataToScreen(PlotGeometry geo, List<double[]> data) {
        List<Point2DDouble> points = new ArrayList<>();
        for (double[] point : data) {
            double px = geo.padding + ((point[0] - geo.minX) / geo.rangeX) * geo.plotWidth;
            double py = (geo.height - geo.padding) - ((point[1] - geo.minY) / geo.rangeY) * geo.plotHeight;
            points.add(new Point2DDouble(px, py));
        }
        return points;
    }

    /**
     * Reduziert sehr lange Datenreihen vor dem Zeichnen auf ein Min/Max-Envelope je Pixel-Spalte:
     * bei Zehntausenden Messpunkten (z. B. Mikrofon oder Lichtsensor über mehrere Minuten bei
     * hoher Abtastrate, siehe {@link MicrophoneSensor}/{@link VEML7700Sensor}) würde sonst bei
     * jedem Repaint über die volle Reihe iteriert und jeder Punkt gezeichnet, obwohl auf dem
     * Bildschirm ohnehin nur {@code geo.plotWidth} Pixelspalten sichtbar sind. Je Spalte werden
     * nur kleinster und größter Y-Wert behalten (in ihrer ursprünglichen zeitlichen Reihenfolge,
     * damit die Verbindungslinie nicht rückwärts läuft) - eine reine "jeden n-ten Punkt nehmen"-
     * Reduktion würde dagegen kurze Spitzen/Ausschläge zwischen den erhaltenen Punkten verschlucken.
     *
     * <p>Betrifft nur die Darstellung: Fit, Chi² und alle übrigen Berechnungen rechnen weiterhin
     * direkt auf {@link #displayData} (siehe {@link #ensureFitComputed}), nicht auf dem hier
     * reduzierten Ergebnis.</p>
     *
     * @param data Datenpunkte in Datenraum, nach X aufsteigend sortiert (Zeitreihen sind das
     *             bauartbedingt immer)
     * @param geo  aktuelle Plot-Geometrie, liefert Pixelbreite und sichtbaren X-Bereich
     * @return {@code data} unverändert, wenn sich eine Reduktion nicht lohnt (im Schnitt nicht
     *         mehr als 2 Punkte je Pixelspalte), sonst die auf höchstens 2 Punkte je Spalte reduzierte Liste
     */
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

    /** Zeichnet die (optionale) Verbindungslinie zwischen aufeinanderfolgenden Messpunkten. */
    private void drawConnectingLine(Graphics2D g2, List<Point2DDouble> points) {
        g2.setColor(Theme.POINT_A.darker());
        g2.setStroke(new BasicStroke(1.5f));
        g2.draw(buildLinePath(points));
    }

    /**
     * Baut den Pfad durch eine Punktfolge passend zu {@link #lineMode}: bei
     * {@link LineMode#STRAIGHT} (oder bei nur zwei Punkten) eine einfache Polylinie, bei
     * {@link LineMode#SPLINE} eine glatte Catmull-Rom-Spline (als kubische Bézier-Segmente
     * gezeichnet), die exakt durch jeden Messpunkt läuft statt ihn nur anzunähern.
     */
    private Path2D buildLinePath(List<Point2DDouble> points) {
        Path2D path = new Path2D.Double();
        path.moveTo(points.get(0).x, points.get(0).y);

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

    /**
     * Zeichnet die Fit-Kurve samt Toleranzband für den aktuell gewählten {@link #fitMode},
     * beschränkt auf die Plotfläche (Clip wird davor gesetzt und danach wiederhergestellt).
     * Ruft bei Bedarf {@link #ensureFitComputed(FitMode, int)} auf, um den zwischengespeicherten
     * Fit auf dem neuesten Stand zu halten, und aktualisiert anschließend Chi².
     */
    private void drawFitOverlayClipped(Graphics2D g2, PlotGeometry geo) {
        currentFitDescription = null;
        // Nur bei tatsächlicher Änderung neu bilden (Daten, Zoom-Fenster oder Fit-Ziel), nicht
        // bei jedem repaint() - {@link #fitDirty} wird bereits bei jeder dafür relevanten
        // Änderung gesetzt (siehe #setData, #setFitTarget, #onViewportWindowChanged, sowie
        // #setExtraSeries für Ziele ungleich Kanal A).
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
        }

        g2.setClip(originalClip);
    }

    /**
     * Berechnet Chi² für den zwischengespeicherten Fit neu (billig, hängt von der aktuellen
     * Standardabweichung ab) und zeichnet die Fit-Kurve samt Toleranzband, sofern ein
     * gültiger Fit vorliegt.
     */
    private void drawCachedFitIfPresent(Graphics2D g2, PlotGeometry geo) {
        if (cachedFit == null) return;
        ensureSigmaComputed(cachedFit);
        calculateChiSquare(cachedFit.function, cachedFit.parameterCount);
        currentFitDescription = cachedFit.description;
        drawFunctionPathWithTolerance(g2, cachedFit.function, geo.minX, geo.visibleMaxX, geo.minY,
                geo.rangeX, geo.rangeY, geo.padding, geo.height, geo.plotWidth, geo.plotHeight, Theme.ACCENT);
    }

    /** Zeichnet alle sichtbaren Messpunkte als kleine Kreise, wahlweise (siehe
     *  {@link #colorByMagnitude}) nach ihrem Y-Wert statt in {@link Theme#POINT_A} eingefärbt.
     *  {@code data} muss index-parallel zu {@code points} sein (siehe {@link #downsampleForRendering}). */
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

    /** Zeichnet das halbtransparente Auswahlrechteck während einer laufenden
     *  Rubber-Band-Zoom-Auswahl (falls der Nutzer gerade zieht). */
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

    private void drawFreehandStroke(Graphics2D g2) {
        List<Point> freehandPoints = viewport.getFreehandPoints();
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

    /** Fittet neu, sofern Daten, Fit-Typ oder Polynomgrad sich seit dem letzten Aufruf geändert
     *  haben (siehe {@link #fitDirty}); delegiert die eigentliche Regression an {@link CurveFitting}. */
    private void ensureFitComputed(FitMode mode, int degree) {
        if (!fitDirty && cachedFit != null && cachedFitModeUsed == mode && cachedDegreeUsed == degree
                && cachedFitTargetUsed == fitTarget) return;

        cachedFit = switch (mode) {
            case LINEAR, POLYNOMIAL -> CurveFitting.fitPolynomial(fitData, degree, xUnit, yUnit);
            case SINUS -> CurveFitting.fitSinus(fitData, xUnit, yUnit);
            case EXPONENTIAL -> CurveFitting.fitExponential(fitData, xUnit, yUnit);
            default -> null;
        };

        cachedFitModeUsed = mode;
        cachedDegreeUsed = degree;
        cachedFitTargetUsed = fitTarget;
        fitDirty = false;
    }

    /**
     * Zeichnet die kleine Chi²-Anzeige mit farbigem Status-Icon oben rechts im Plot und
     * merkt sich dessen Klickfläche in {@link #infoButtonBounds} für {@link #showChiSquareInfoDialog()}.
     *
     * @param topY obere Kante der Box - liegt unterhalb der Legende, falls diese sichtbar ist
     *             (siehe {@link #paintComponent}), sonst direkt oben in der Ecke
     */
    private void drawChiSquareOverlay(Graphics2D g2, int width, int rightPadding, int topY) {
        // Bei nicht-positiven Freiheitsgraden liefert GoodnessOfFit#calculateReducedChiSquare
        // bewusst NaN statt eines irreführenden Zahlenwerts (siehe dort) - "%.4f" auf NaN würde
        // sonst wörtlich "χ²_red = NaN" anzeigen.
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
        int boxY = topY;

        Color statusColor = GoodnessOfFit.colorFor(currentReducedChiSquare);

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

    /**
     * Zeichnet einen kleinen, rechtsbündigen Hinweis unterhalb der Chi²-Anzeige, auf welche
     * Messgröße(n) sich Fit und Chi² gerade beziehen (siehe {@link #fitTarget}) - nur sichtbar,
     * solange mindestens eine Extra-Serie (Kanal B) gleichzeitig dargestellt wird (siehe
     * {@link #paintComponent}), da der Hinweis ohne eine zweite Größe keinen Mehrwert hätte.
     */
    private void drawFitScopeNote(Graphics2D g2, PlotGeometry geo, int topY) {
        String note = "Fit & \u03c7\u00b2 beziehen sich auf " + fitTargetLabel();
        g2.setFont(new Font("SansSerif", Font.ITALIC, 10));
        FontMetrics fm = g2.getFontMetrics();
        int textWidth = fm.stringWidth(note);
        int boxX = geo.width - geo.rightPadding - textWidth - 11;

        g2.setColor(Theme.MUTED);
        g2.drawString(note, boxX, topY + 10);
    }

    /** Menschenlesbare Beschreibung des aktuellen {@link #fitTarget}, für den Hinweistext in
     *  {@link #drawFitScopeNote} - bewusst nur die Kanalbezeichnung ("Kanal A"/"Kanal B"), nicht
     *  {@link #mainLabel}/das Label der Extra-Serie, die zusätzlich die jeweilige Messgröße samt
     *  Einheit enthalten (z. B. "Kanal A: Temperatur (°C)") und den kurzen Hinweis unnötig
     *  aufblähen würden. */
    private String fitTargetLabel() {
        return switch (fitTarget) {
            case A -> "Kanal A";
            case B -> "Kanal B";
            case BOTH -> "Kanal A + Kanal B";
        };
    }

    /** Öffnet den Detail-Dialog mit einer Erklärung des aktuellen Chi²-Gütewerts. */
    private void showChiSquareInfoDialog() {
        Window parentWindow = SwingUtilities.getWindowAncestor(this);
        ChiSquareInfoDialog dialog = new ChiSquareInfoDialog(parentWindow, currentReducedChiSquare, currentDegreesOfFreedom, currentFitDescription, sigmaMode);
        dialog.setVisible(true);
    }

    /** Aktualisiert {@link #currentReducedChiSquare} und {@link #currentDegreesOfFreedom} über
     *  {@link GoodnessOfFit#calculateReducedChiSquare}, mit je Punkt passendem Sigma aus
     *  {@link #sigmaForDataPoint}. */
    private void calculateChiSquare(CurveFitting.FunctionEvaluator func, int parameterCount) {
        GoodnessOfFit.ChiSquareResult result =
                GoodnessOfFit.calculateReducedChiSquare(fitData, func, parameterCount, this::sigmaForDataPoint);
        this.currentReducedChiSquare = result.reducedChiSquare;
        this.currentDegreesOfFreedom = result.degreesOfFreedom;
    }

    /**
     * Liefert die für Chi² am Datenpunkt mit Index {@code i} in {@link #fitData} zu
     * verwendende Standardabweichung, abhängig vom gewählten {@link #sigmaMode}. Setzt voraus,
     * dass {@link #ensureSigmaComputed} zuvor gelaufen ist.
     */
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

    /**
     * Liefert die Standardabweichung an einer beliebigen X-Stelle (nicht notwendigerweise ein
     * Messpunkt) für das Toleranzband der Fit-Kurve - im Fenster-Modus über
     * {@link GoodnessOfFit#interpolateLocalSigma} zwischen den benachbarten Messpunkten
     * interpoliert, im Gauß-Modus direkt an der Stelle ausgewertet (keine Interpolation nötig).
     */
    private double sigmaForToleranceBand(double x) {
        return switch (sigmaMode) {
            case RESIDUAL_LOCAL -> GoodnessOfFit.interpolateLocalSigma(fitData, cachedLocalSigmas, x, standardDeviation);
            case RESIDUAL_LOCAL_GAUSSIAN -> (cachedGaussianResiduals != null)
                    ? GoodnessOfFit.gaussianWeightedSigma(fitData, cachedGaussianResiduals, cachedGaussianBandwidth, x)
                    : standardDeviation;
            default -> standardDeviation;
        };
    }

    /**
     * Aktualisiert, falls {@link #sigmaCacheDirty}, die aus den Fit-Residuen abgeleiteten
     * Sigma-Werte über {@link GoodnessOfFit#estimateSigma}. Für {@link GoodnessOfFit.SigmaMode#CONSTANT}
     * sowie ohne gültigen Fit passiert nichts weiter, als dass die Rückfallebene
     * {@link #standardDeviation} verwendet wird (siehe {@link #sigmaForDataPoint}/{@link #sigmaForToleranceBand}).
     *
     * @param fit der aktuell zwischengespeicherte Fit, oder {@code null} ohne aktiven Fit
     */
    private void ensureSigmaComputed(CurveFitting.FitResult fit) {
        if (!sigmaCacheDirty) return;
        sigmaCacheDirty = false;

        CurveFitting.FunctionEvaluator func = (fit != null) ? fit.function : null;
        int paramCount = (fit != null) ? fit.parameterCount : 0;
        GoodnessOfFit.SigmaEstimate estimate =
                GoodnessOfFit.estimateSigma(fitData, func, paramCount, sigmaMode, localSigmaNeighbors, standardDeviation);

        cachedLocalSigmas = estimate.localSigmas;
        cachedGaussianResiduals = estimate.residuals;
        cachedGaussianBandwidth = estimate.gaussianBandwidth;
    }

    /**
     * Zeichnet eine Modellfunktion als gestrichelte Kurve inklusive eines halbtransparenten
     * Toleranzbands der Breite +/- sigma um die Kurve herum. Sigma stammt je nach
     * {@link #sigmaMode} entweder vom konstanten Wert, vom global geschätzten Wert, oder
     * ortsabhängig aus {@link #sigmaForToleranceBand(double)} - im letzten Fall ändert sich
     * die Bandbreite dadurch sichtbar entlang der X-Achse.
     */
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

    /**
     * Zeichnet ein Fadenkreuz mit Koordinatenanzeige an der aktuellen Mausposition, sofern
     * sich die Maus innerhalb der Plotfläche befindet. Ist eine zweite Y-Achse aktiv (siehe
     * {@link #dualYAxisMode}), zeigt die Koordinatenbox zusätzlich den Y-Wert auf dieser Achse
     * an derselben Bildschirmhöhe an - die beiden Kurven haben an dieser Stelle ja i. A.
     * unterschiedliche tatsächliche Werte.
     */
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

        // Abgerundet statt eckig, damit alle Overlay-Boxen im Diagramm (Legende, Chi²-Anzeige,
        // Fadenkreuz-Koordinaten) einheitlich aussehen.
        g2.setColor(Theme.PANEL);
        g2.fillRoundRect(boxX, boxY - 12, strWidth + 8, 16, 6, 6);
        g2.setColor(Theme.BORDER);
        g2.drawRoundRect(boxX, boxY - 12, strWidth + 8, 16, 6, 6);
        g2.setColor(Theme.TEXT);
        g2.drawString(coordStr, boxX + 4, boxY);
    }
}