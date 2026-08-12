import java.awt.Point;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Kapselt den Zoom-/Auswahlzustand von {@link ChartPanel}: das aktuelle Zoom-/Auswahlfenster in
 * Datenraum-Koordinaten (gesetzt per Rubber-Band- oder Freihand-Auswahl), den kontinuierlichen
 * Zoom-Faktor (Buttons, siehe {@link ChartPanel#zoomIn()}/{@link ChartPanel#zoomOut()}) sowie den
 * flüchtigen Zustand einer gerade laufenden Maus-Interaktion (Drag-Rechteck bzw. Freihand-Stift).
 *
 * <p>Kennt weder Zeichen- noch Fit-/Chi²-Logik: {@link ChartPanel} liest nur das aktuelle Fenster
 * aus ({@link #isActive()}, {@link #getMinX()} etc.) und stößt bei jeder Änderung selbst ein
 * Neuberechnen von {@code displayData} sowie des Fit-/Sigma-Caches an - {@link #applySelectionZoom}
 * und {@link #applyFreehandSelection} geben dafür lediglich per Rückgabewert zurück, ob sich das
 * Fenster tatsächlich geändert hat.</p>
 */
class ChartViewport {

    /** Minimale Pixel-Ausdehnung eines Rubber-Band-Rechtecks, damit ein winziger, versehentlicher
     *  Klick nicht bereits als Zoom-Auswahl gilt. */
    private static final int MIN_DRAG_PIXELS = 10;
    private static final double MIN_ZOOM_FACTOR = 0.1;
    private static final double ZOOM_STEP = 1.2;

    /** Für die Pixel-zu-Daten-Umrechnung von {@link #applySelectionZoom}/{@link #applyFreehandSelection}
     *  benötigte Ausschnitts-Geometrie - ein schmaler, auf das Nötigste reduzierter Ausschnitt aus
     *  {@link ChartPanel}s vollständiger Plot-Geometrie, damit diese Klasse deren privaten
     *  PlotGeometry-Typ nicht kennen muss. Von {@link ChartPanel} bei Bedarf frisch gebaut. */
    static final class Geometry {
        final int padding, plotWidth, plotHeight, height;
        final double minX, rangeX, minY, rangeY;

        Geometry(int padding, int plotWidth, int plotHeight, int height,
                 double minX, double rangeX, double minY, double rangeY) {
            this.padding = padding;
            this.plotWidth = plotWidth;
            this.plotHeight = plotHeight;
            this.height = height;
            this.minX = minX;
            this.rangeX = rangeX;
            this.minY = minY;
            this.rangeY = rangeY;
        }
    }

    /** Aktuelles Zoom-/Auswahlfenster, {@code null} (bei {@code minX}) = kein Fenster gesetzt, es
     *  wird der volle Datenbereich verwendet. */
    private Double minX = null, maxX = null, minY = null, maxY = null;
    private double zoomFactor = 1.0;

    private Point dragStart = null;
    private Point dragEnd = null;
    private final List<Point> freehandPoints = new ArrayList<>();
    private boolean rightButtonDragging = false;
    private boolean rightClickTriggered = false;

    /** @return {@code true}, wenn aktuell ein Zoom-/Auswahlfenster aktiv ist. */
    boolean isActive() {
        return minX != null;
    }

    double getMinX() { return minX; }
    double getMaxX() { return maxX; }
    double getMinY() { return minY; }
    double getMaxY() { return maxY; }
    double getZoomFactor() { return zoomFactor; }

    void zoomIn() {
        zoomFactor *= ZOOM_STEP;
    }

    void zoomOut() {
        zoomFactor = Math.max(MIN_ZOOM_FACTOR, zoomFactor / ZOOM_STEP);
    }

    /** Setzt Zoom-Faktor und Zoom-/Auswahlfenster zurück auf die vollständigen Messdaten. */
    void reset() {
        zoomFactor = 1.0;
        minX = null;
        maxX = null;
        minY = null;
        maxY = null;
    }

    // --- Rubber-Band-Auswahl (linke Maustaste) ---

    void beginRubberBand(Point p) {
        dragStart = p;
        dragEnd = p;
    }

    void updateRubberBand(Point p) {
        dragEnd = p;
    }

    Point getDragStart() { return dragStart; }
    Point getDragEnd() { return dragEnd; }

    boolean hasRubberBand() {
        return dragStart != null && dragEnd != null;
    }

    void clearRubberBand() {
        dragStart = null;
        dragEnd = null;
    }

    // --- Freihand-Auswahl (rechte Maustaste) ---

    void beginFreehand(Point p) {
        rightButtonDragging = true;
        rightClickTriggered = false;
        freehandPoints.clear();
        freehandPoints.add(p);
    }

    void addFreehandPoint(Point p) {
        rightClickTriggered = true;
        freehandPoints.add(p);
    }

    void endFreehandDrag() {
        rightButtonDragging = false;
    }

    boolean isRightButtonDragging() { return rightButtonDragging; }
    boolean isRightClickTriggered() { return rightClickTriggered; }
    List<Point> getFreehandPoints() { return freehandPoints; }

    void clearFreehand() {
        freehandPoints.clear();
    }

    /**
     * Wertet ein per linker Maustaste gezogenes Rechteck aus: die Bounding-Box wird zum neuen
     * Zoom-Fenster, sofern mindestens zwei Punkte aus {@code originalData} hineinfallen. Die
     * Pixel-zu-Daten-Umrechnung nutzt dieselbe Geometrie wie das Zeichnen selbst, damit die
     * Auswahl exakt dem entspricht, was gerade sichtbar ist - auch wenn schon vorher gezoomt war.
     *
     * @param p1           erste Ecke des gezogenen Rechtecks (Bildschirmkoordinaten)
     * @param p2           gegenüberliegende Ecke (Bildschirmkoordinaten)
     * @param geo          aktuelle Plot-Geometrie des Aufrufers
     * @param originalData ungefilterte Messdaten, gegen die die Mindestpunktzahl geprüft wird
     * @return {@code true}, wenn ein neues Zoom-Fenster gesetzt wurde (der Aufrufer muss dann
     *         Anzeige-Daten sowie Fit-/Sigma-Cache selbst neu berechnen)
     */
    boolean applySelectionZoom(Point p1, Point p2, Geometry geo, List<double[]> originalData) {
        if (originalData == null || originalData.isEmpty() || geo == null) return false;

        int rectX = Math.min(p1.x, p2.x);
        int rectY = Math.min(p1.y, p2.y);
        int rectW = Math.abs(p1.x - p2.x);
        int rectH = Math.abs(p1.y - p2.y);

        if (rectW < MIN_DRAG_PIXELS || rectH < MIN_DRAG_PIXELS) return false;

        double selMinX = geo.minX + ((double) (rectX - geo.padding) / geo.plotWidth) * geo.rangeX;
        double selMaxX = geo.minX + ((double) (rectX + rectW - geo.padding) / geo.plotWidth) * geo.rangeX;

        double selMaxY = geo.minY + ((double) ((geo.height - geo.padding) - rectY) / geo.plotHeight) * geo.rangeY;
        double selMinY = geo.minY + ((double) ((geo.height - geo.padding) - (rectY + rectH)) / geo.plotHeight) * geo.rangeY;

        int pointsInWindow = 0;
        for (double[] pt : originalData) {
            if (pt[0] >= selMinX && pt[0] <= selMaxX && pt[1] >= selMinY && pt[1] <= selMaxY) {
                pointsInWindow++;
            }
        }
        if (pointsInWindow < 2) return false;

        minX = selMinX;
        maxX = selMaxX;
        minY = selMinY;
        maxY = selMaxY;
        zoomFactor = 1.0;
        return true;
    }

    /**
     * Wertet eine per rechter Maustaste gezogene Freihand-Linie aus: die Bounding-Box der davon
     * eingeschlossenen Messpunkte wird zum neuen Zoom-Fenster - {@code originalData} bleibt
     * unverändert, die Freihandform dient nur der (ggf. nicht-rechteckigen) Auswahl des
     * Zoom-Bereichs, nicht dem dauerhaften Verwerfen der übrigen Punkte.
     *
     * @param strokePoints vom Nutzer gezogene Freihand-Linie (Bildschirmkoordinaten)
     * @param geo          aktuelle Plot-Geometrie des Aufrufers
     * @param originalData ungefilterte Messdaten, aus denen die eingeschlossenen Punkte bestimmt werden
     * @return {@code true}, wenn ein neues Zoom-Fenster gesetzt wurde
     */
    boolean applyFreehandSelection(List<Point> strokePoints, Geometry geo, List<double[]> originalData) {
        if (originalData == null || originalData.isEmpty() || strokePoints.size() < 3 || geo == null) return false;

        Path2D polygonPath = new Path2D.Double();
        polygonPath.moveTo(strokePoints.get(0).x, strokePoints.get(0).y);
        for (int i = 1; i < strokePoints.size(); i++) {
            polygonPath.lineTo(strokePoints.get(i).x, strokePoints.get(i).y);
        }
        polygonPath.closePath();

        double selMinX = Double.MAX_VALUE, selMaxX = -Double.MAX_VALUE;
        double selMinY = Double.MAX_VALUE, selMaxY = -Double.MAX_VALUE;
        int enclosedCount = 0;

        for (double[] point : originalData) {
            double px = geo.padding + ((point[0] - geo.minX) / geo.rangeX) * geo.plotWidth;
            double py = (geo.height - geo.padding) - ((point[1] - geo.minY) / geo.rangeY) * geo.plotHeight;
            if (polygonPath.contains(px, py)) {
                enclosedCount++;
                if (point[0] < selMinX) selMinX = point[0];
                if (point[0] > selMaxX) selMaxX = point[0];
                if (point[1] < selMinY) selMinY = point[1];
                if (point[1] > selMaxY) selMaxY = point[1];
            }
        }
        if (enclosedCount < 2) return false;

        if (selMinX == selMaxX) selMaxX = selMinX + 1.0;
        if (selMinY == selMaxY) { selMinY -= 1.0; selMaxY += 1.0; }

        minX = selMinX;
        maxX = selMaxX;
        minY = selMinY;
        maxY = selMaxY;
        zoomFactor = 1.0;
        return true;
    }
}
