import java.awt.Color;
import java.util.List;
import java.util.function.IntToDoubleFunction;

/**
 * Bewertet die Güte eines Fits über das reduzierte Chi-Quadrat und schätzt bei Bedarf die dafür
 * nötige Messunsicherheit (sigma) aus den Fit-Residuen. Kennt weder Zeichen- noch Sensor-Logik -
 * {@link ChartPanel} liefert nur Datenpunkte und eine {@link CurveFitting.FunctionEvaluator} und
 * erhält ein fertiges Ergebnis zurück.
 *
 * <p>Einzige Quelle der Wahrheit für die Chi²-Schwellenwerte - {@link ChiSquareInfoDialog}
 * fragt ausschließlich hier ab, damit Diagramm-Overlay und Detail-Dialog niemals unterschiedliche
 * Grenzwerte verwenden können.</p>
 */
public final class GoodnessOfFit {

    private GoodnessOfFit() {
    }

    public static final double CHI_OVERFIT_THRESHOLD = 0.8;
    public static final double CHI_GOOD_THRESHOLD = 1.5;
    public static final double CHI_MODERATE_THRESHOLD = 3.0;

    /** Bewertungsklassen für das reduzierte Chi-Quadrat. */
    public enum ChiRating {
        /** &lt; {@link #CHI_OVERFIT_THRESHOLD}: Fehler wahrscheinlich überschätzt / Überanpassung. */
        OVERFIT,
        /** Zwischen {@link #CHI_OVERFIT_THRESHOLD} und {@link #CHI_GOOD_THRESHOLD}: guter Fit. */
        GOOD,
        /** Zwischen {@link #CHI_GOOD_THRESHOLD} und {@link #CHI_MODERATE_THRESHOLD}: mäßiger Fit. */
        MODERATE,
        /** &gt; {@link #CHI_MODERATE_THRESHOLD}: Modell passt schlecht (Unteranpassung). */
        UNDERFIT,
        /** Freiheitsgrade &le; 0 (siehe {@link #calculateReducedChiSquare}) - zu wenige Datenpunkte
         *  für die Anzahl der Modellparameter, kein sinnvoller Chi²-Wert berechenbar. Tritt in der
         *  Praxis vor allem nach starkem Hineinzoomen auf, z. B. ein Rubber-Band-Fenster mit genau
         *  den laut {@link ChartViewport} minimal nötigen zwei Punkten bei einem linearen Fit. */
        NOT_EVALUABLE
    }

    /**
     * Wie die Messunsicherheit sigma bestimmt wird, die in Chi²-Berechnung und Toleranzband
     * eingeht (siehe {@link StandardDeviationDialog}). Die automatischen Modi setzen einen
     * aktiven Fit voraus - ist keiner aktiv, fällt {@link #estimateSigma} auf den konstanten
     * Rückfallwert zurück.
     */
    public enum SigmaMode {
        /** Ein einziger, manuell eingegebener Wert für alle Punkte (bisheriges Verhalten). */
        CONSTANT,
        /** Ortsabhängiger Wert je Punkt, aus der Streuung der k nächsten Nachbarn (hartes Fenster,
         *  Indexabstand) um den Fit geschätzt - passt sich ungleichmäßig verteiltem Rauschen
         *  entlang der Messreihe an, springt an den Fenstergrenzen aber sprunghaft statt sanft. */
        RESIDUAL_LOCAL,
        /** Wie {@link #RESIDUAL_LOCAL}, aber statt eines harten Fensters mit fließendem
         *  Gauß-Kernel über den tatsächlichen X-Abstand gewichtet - jeder Punkt trägt zu jedem
         *  sigma(x) bei, mit abnehmendem Gewicht je weiter er von x entfernt liegt. Dadurch
         *  verläuft sigma(x) glatt statt stufig, und ist (anders als {@link #RESIDUAL_LOCAL})
         *  auch bei ungleichmäßig verteilten Messpunkten sauber definiert, da nach X-Abstand statt
         *  nach Index gewichtet wird (siehe {@link #gaussianWeightedSigma}). */
        RESIDUAL_LOCAL_GAUSSIAN
    }

    /** Ergebnis von {@link #calculateReducedChiSquare}. */
    public static final class ChiSquareResult {
        public final double reducedChiSquare;
        public final int degreesOfFreedom;

        ChiSquareResult(double reducedChiSquare, int degreesOfFreedom) {
            this.reducedChiSquare = reducedChiSquare;
            this.degreesOfFreedom = degreesOfFreedom;
        }
    }

    /**
     * Ergebnis von {@link #estimateSigma}. Je nach {@link SigmaMode} ist nur eines der beiden
     * Felder belegt (das jeweils andere {@code null}) - {@link #localSigmas} für
     * {@link SigmaMode#RESIDUAL_LOCAL}, {@link #residuals} (zusammen mit {@link #gaussianBandwidth})
     * für {@link SigmaMode#RESIDUAL_LOCAL_GAUSSIAN}. Für {@link SigmaMode#CONSTANT} sind beide
     * {@code null} - dort wird direkt der konstante Wert verwendet, ohne diese Klasse.
     */
    public static final class SigmaEstimate {
        public final double[] localSigmas;
        public final double[] residuals;
        public final double gaussianBandwidth;

        SigmaEstimate(double[] localSigmas, double[] residuals, double gaussianBandwidth) {
            this.localSigmas = localSigmas;
            this.residuals = residuals;
            this.gaussianBandwidth = gaussianBandwidth;
        }
    }

    /**
     * Ordnet einen reduzierten Chi-Quadrat-Wert einer {@link ChiRating} zu.
     *
     * @param reducedChiSquare Wert aus {@link #calculateReducedChiSquare}; {@link Double#NaN}
     *                         (Freiheitsgrade &le; 0, siehe dort) liefert {@link ChiRating#NOT_EVALUABLE}.
     */
    public static ChiRating rate(double reducedChiSquare) {
        if (Double.isNaN(reducedChiSquare)) return ChiRating.NOT_EVALUABLE;
        if (reducedChiSquare < CHI_OVERFIT_THRESHOLD) return ChiRating.OVERFIT;
        if (reducedChiSquare <= CHI_GOOD_THRESHOLD) return ChiRating.GOOD;
        if (reducedChiSquare <= CHI_MODERATE_THRESHOLD) return ChiRating.MODERATE;
        return ChiRating.UNDERFIT;
    }

    /**
     * Liefert die Anzeigefarbe für einen reduzierten Chi-Quadrat-Wert, konsistent mit
     * {@link #rate(double)}.
     *
     * @return Grün für einen guten Fit, Gelb für Über-/mäßige Anpassung, Rot für Unteranpassung,
     *         Grau für {@link ChiRating#NOT_EVALUABLE}
     */
    public static Color colorFor(double reducedChiSquare) {
        return switch (rate(reducedChiSquare)) {
            case OVERFIT, MODERATE -> Theme.WARNING;
            case GOOD -> Theme.SUCCESS;
            case UNDERFIT -> Theme.DANGER;
            case NOT_EVALUABLE -> Theme.MUTED;
        };
    }

    /**
     * Berechnet das reduzierte Chi-Quadrat für eine gegebene Fit-Funktion:
     * chi²_red = (1 / DOF) * Summe((y_i - f(x_i))² / sigma_i²), DOF = n - Parameteranzahl.
     *
     * @param data           die Datenpunkte, auf denen der Fit beruht
     * @param func           die angepasste Funktion
     * @param parameterCount Anzahl der freien Parameter des Modells (für die Freiheitsgrade)
     * @param sigmaAt        liefert die Standardabweichung für den Punkt mit gegebenem Index
     * @return bei nicht-positiven Freiheitsgraden (zu wenige Datenpunkte für die Parameterzahl,
     *         z. B. nach starkem Hineinzoomen) {@link ChiSquareResult#reducedChiSquare} als
     *         {@link Double#NaN} statt eines irreführenden Zahlenwerts - {@link #rate(double)}
     *         bildet das auf {@link ChiRating#NOT_EVALUABLE} ab; {@code degreesOfFreedom} bleibt
     *         dabei der tatsächliche (nicht-positive) Wert, nicht auf 1 gefälscht
     */
    public static ChiSquareResult calculateReducedChiSquare(List<double[]> data, CurveFitting.FunctionEvaluator func,
                                                            int parameterCount, IntToDoubleFunction sigmaAt) {
        int n = data.size();
        int dof = n - parameterCount;

        if (dof <= 0) {
            return new ChiSquareResult(Double.NaN, dof);
        }

        double sumChiSq = 0;
        for (int i = 0; i < n; i++) {
            double[] pt = data.get(i);
            double diff = pt[1] - func.eval(pt[0]);
            double sigma = sigmaAt.applyAsDouble(i);
            sumChiSq += (diff * diff) / (sigma * sigma);
        }

        return new ChiSquareResult(sumChiSq / dof, dof);
    }

    /**
     * Schätzt sigma aus den Fit-Residuen, sofern {@code mode} dies verlangt. Grundidee: Ohne
     * bekannte Messunsicherheit lässt sich sigma aus der tatsächlichen Streuung der Messwerte um
     * den aktuellen Fit schätzen - unter der (für viele Messungen plausiblen) Annahme
     * gaußverteilten Rauschens ist die (lokal gewichtete) empirische Standardabweichung der
     * Residuen genau dieser Schätzer.
     *
     * @param data              die Datenpunkte, auf denen der Fit beruht
     * @param func              die angepasste Funktion, oder {@code null} ohne aktiven Fit
     * @param parameterCount    Anzahl der freien Parameter (aktuell ungenutzt, für zukünftige Modi vorgehalten)
     * @param mode              der gewünschte Sigma-Modus
     * @param localNeighbors    Nachbarschaftsgröße k ({@link SigmaMode#RESIDUAL_LOCAL}: Fenstergröße;
     *                          {@link SigmaMode#RESIDUAL_LOCAL_GAUSSIAN}: Bandbreite, siehe {@link #gaussianBandwidthFor})
     * @param fallbackConstant  Rückfallwert ohne Fit bzw. bei zu wenig Daten
     */
    public static SigmaEstimate estimateSigma(List<double[]> data, CurveFitting.FunctionEvaluator func,
                                              int parameterCount, SigmaMode mode, int localNeighbors,
                                              double fallbackConstant) {
        int n = data.size();
        if (func == null || n == 0 || mode == SigmaMode.CONSTANT) {
            return new SigmaEstimate(null, null, 0);
        }

        double[] residuals = new double[n];
        for (int i = 0; i < n; i++) {
            double[] pt = data.get(i);
            residuals[i] = pt[1] - func.eval(pt[0]);
        }

        if (mode == SigmaMode.RESIDUAL_LOCAL_GAUSSIAN) {
            double bandwidth = gaussianBandwidthFor(data, localNeighbors);
            return new SigmaEstimate(null, residuals, bandwidth);
        }

        // RESIDUAL_LOCAL (hartes Fenster)
        int k = Math.max(1, Math.min(localNeighbors, n - 1));
        double[] localSigmas = new double[n];
        for (int i = 0; i < n; i++) {
            localSigmas[i] = Math.max(1e-6, localWindowStdDev(residuals, i, k));
        }
        return new SigmaEstimate(localSigmas, null, 0);
    }

    /**
     * Mittlere quadratische Residuenstreuung im Index-Fenster um Punkt {@code i}. Da die
     * Datenpunkte zeitlich aufsteigend sortiert sind, entsprechen benachbarte Indizes den in X
     * nächstgelegenen Nachbarn - deutlich günstiger als eine echte Abstandssuche und für
     * annähernd gleichmäßig abgetastete Messreihen äquivalent dazu.
     */
    private static double localWindowStdDev(double[] residuals, int i, int k) {
        int n = residuals.length;
        int half = Math.max(1, k / 2);
        int lo = Math.max(0, i - half);
        int hi = Math.min(n - 1, i + half);
        while (hi - lo + 1 < Math.min(k + 1, n)) {
            if (lo > 0) lo--;
            else if (hi < n - 1) hi++;
            else break;
        }

        double sumSq = 0;
        int count = 0;
        for (int j = lo; j <= hi; j++) {
            sumSq += residuals[j] * residuals[j];
            count++;
        }
        return Math.sqrt(sumSq / count);
    }

    /**
     * Wandelt die Nachbarschaftsgröße k in eine Gauß-Bandbreite (Standardabweichung des Kernels,
     * in X-Einheiten) um: Bandbreite = k/2 mittlere Punktabstände, sodass k in etwa vergleichbar
     * mit der Fenstergröße von {@link SigmaMode#RESIDUAL_LOCAL} bleibt - ein Wechsel zwischen
     * beiden Modi bei gleichem k liefert also eine ähnlich "breite" Nachbarschaft, nur mit
     * weichem statt hartem Übergang an den Rändern.
     */
    private static double gaussianBandwidthFor(List<double[]> data, int k) {
        int n = data.size();
        double span = data.get(n - 1)[0] - data.get(0)[0];
        if (n < 2 || span <= 0) return 1.0;
        double avgSpacing = span / (n - 1);
        return Math.max(avgSpacing * 1e-3, (Math.max(1, k) / 2.0) * avgSpacing);
    }

    /**
     * Gauß-gewichtete Streuung der Residuen an der Stelle {@code x}: jeder Datenpunkt trägt
     * gemäß {@code exp(-(x_j - x)² / (2·bandwidth²))} bei, statt wie bei
     * {@link #localWindowStdDev} hart auf ein Fenster von k Nachbarn abzuschneiden. Das Ergebnis
     * ist eine glatte, für jedes x (auch zwischen Messpunkten, z. B. für das Toleranzband)
     * direkt auswertbare Funktion - anders als bei {@link SigmaMode#RESIDUAL_LOCAL} ist dafür
     * keine Interpolation zwischen vorab berechneten Stützstellen nötig.
     *
     * @param data      die Datenpunkte, deren {@code residuals} hier gewichtet einfließen
     * @param residuals Residuen (y - f(x)) je Datenpunkt, siehe {@link SigmaEstimate#residuals}
     * @param bandwidth Gauß-Bandbreite, siehe {@link #gaussianBandwidthFor}
     * @param x         die Stelle, an der sigma ausgewertet werden soll
     */
    public static double gaussianWeightedSigma(List<double[]> data, double[] residuals, double bandwidth, double x) {
        if (residuals == null || residuals.length == 0) return 0;

        double twoBandwidthSq = 2 * bandwidth * bandwidth;
        double weightedSumSq = 0;
        double weightSum = 0;

        for (int j = 0; j < residuals.length; j++) {
            double dx = data.get(j)[0] - x;
            double weight = Math.exp(-(dx * dx) / twoBandwidthSq);
            weightedSumSq += weight * residuals[j] * residuals[j];
            weightSum += weight;
        }

        if (weightSum < 1e-12) return 1e-6;
        return Math.max(1e-6, Math.sqrt(weightedSumSq / weightSum));
    }

    /**
     * Lineare Interpolation der je Punkt geschätzten lokalen Sigma-Werte zwischen den beiden
     * Datenpunkten, die {@code x} einschließen - für Stellen zwischen echten Messpunkten (z. B.
     * beim Zeichnen des Toleranzbands mit vielen Zwischenschritten), ausschließlich für
     * {@link SigmaMode#RESIDUAL_LOCAL} genutzt ({@link SigmaMode#RESIDUAL_LOCAL_GAUSSIAN}
     * benötigt das nicht, siehe {@link #gaussianWeightedSigma}). Außerhalb des Datenbereichs
     * wird der jeweilige Randwert fortgeschrieben.
     *
     * @param data        die Datenpunkte, zu denen {@code localSigmas} passt
     * @param localSigmas je Punkt geschätzte Sigma-Werte (siehe {@link SigmaEstimate#localSigmas})
     * @param x           die Stelle, an der interpoliert werden soll
     * @param fallback    Rückgabewert, falls keine lokalen Sigma-Werte vorliegen
     */
    public static double interpolateLocalSigma(List<double[]> data, double[] localSigmas, double x, double fallback) {
        if (localSigmas == null || data.isEmpty()) return fallback;
        int n = data.size();
        if (n == 1) return localSigmas[0];

        if (x <= data.get(0)[0]) return localSigmas[0];
        if (x >= data.get(n - 1)[0]) return localSigmas[n - 1];

        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) / 2;
            if (data.get(mid)[0] <= x) lo = mid; else hi = mid;
        }

        double x0 = data.get(lo)[0], x1 = data.get(hi)[0];
        double s0 = localSigmas[lo], s1 = localSigmas[hi];
        if (x1 == x0) return s0;
        double t = (x - x0) / (x1 - x0);
        return s0 + t * (s1 - s0);
    }
}