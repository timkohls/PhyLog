import java.awt.Color;
import java.util.List;
import java.util.function.IntToDoubleFunction;

/**
 * Bewertet die Güte eines Fits über das reduzierte Chi-Quadrat und schätzt bei Bedarf die dafür
 * nötige Messunsicherheit (sigma) aus den Fit-Residuen.
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
        /** Freiheitsgrade &le; 0: zu wenige Datenpunkte für die Anzahl der Modellparameter. */
        NOT_EVALUABLE
    }

    /** Wie die Messunsicherheit sigma bestimmt wird. Die automatischen Modi benötigen einen
     *  aktiven Fit; ohne Fit fällt {@link #estimateSigma} auf den konstanten Wert zurück. */
    public enum SigmaMode {
        /** Ein einziger, manuell eingegebener Wert für alle Punkte. */
        CONSTANT,
        /** Ortsabhängiger Wert je Punkt, aus der Streuung der k nächsten Nachbarn (hartes
         *  Indexfenster) um den Fit geschätzt. */
        RESIDUAL_LOCAL,
        /** Wie {@link #RESIDUAL_LOCAL}, aber mit fließendem Gauß-Kernel über den X-Abstand
         *  gewichtet statt hartem Fenster - dadurch glatt statt stufig. */
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

    /** Ergebnis von {@link #estimateSigma}. Je nach {@link SigmaMode} ist nur eines von
     *  {@link #localSigmas} und {@link #residuals} belegt; für {@link SigmaMode#CONSTANT} beide
     *  {@code null}. */
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
     * @param reducedChiSquare {@link Double#NaN} liefert {@link ChiRating#NOT_EVALUABLE}
     */
    public static ChiRating rate(double reducedChiSquare) {
        if (Double.isNaN(reducedChiSquare)) return ChiRating.NOT_EVALUABLE;
        if (reducedChiSquare < CHI_OVERFIT_THRESHOLD) return ChiRating.OVERFIT;
        if (reducedChiSquare <= CHI_GOOD_THRESHOLD) return ChiRating.GOOD;
        if (reducedChiSquare <= CHI_MODERATE_THRESHOLD) return ChiRating.MODERATE;
        return ChiRating.UNDERFIT;
    }

    /** Liefert die Anzeigefarbe für einen reduzierten Chi-Quadrat-Wert, konsistent mit {@link #rate(double)}. */
    public static Color colorFor(double reducedChiSquare) {
        return switch (rate(reducedChiSquare)) {
            case OVERFIT, MODERATE -> Theme.WARNING;
            case GOOD -> Theme.SUCCESS;
            case UNDERFIT -> Theme.DANGER;
            case NOT_EVALUABLE -> Theme.MUTED;
        };
    }

    /**
     * Berechnet das reduzierte Chi-Quadrat: chi²_red = (1/DOF) * Summe((y_i - f(x_i))²/sigma_i²),
     * DOF = n - Parameteranzahl.
     *
     * @return bei nicht-positiven Freiheitsgraden {@link Double#NaN} statt eines irreführenden
     *         Zahlenwerts; {@code degreesOfFreedom} bleibt dabei der tatsächliche, nicht-positive Wert
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
     * Schätzt sigma aus den Fit-Residuen, sofern {@code mode} dies verlangt.
     *
     * @param func             die angepasste Funktion, oder {@code null} ohne aktiven Fit
     * @param localNeighbors   Nachbarschaftsgröße k (Fenstergröße bzw. Bandbreiten-Basis)
     */
    public static SigmaEstimate estimateSigma(List<double[]> data, CurveFitting.FunctionEvaluator func,
                                              SigmaMode mode, int localNeighbors) {
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

        int k = Math.max(1, Math.min(localNeighbors, n - 1));
        double[] localSigmas = new double[n];
        for (int i = 0; i < n; i++) {
            localSigmas[i] = Math.max(1e-6, localWindowStdDev(residuals, i, k));
        }
        return new SigmaEstimate(localSigmas, null, 0);
    }

    /** Mittlere quadratische Residuenstreuung im Index-Fenster um Punkt {@code i}. */
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

    /** Wandelt die Nachbarschaftsgröße k in eine Gauß-Bandbreite (Standardabweichung des
     *  Kernels, in X-Einheiten) um. */
    private static double gaussianBandwidthFor(List<double[]> data, int k) {
        int n = data.size();
        double span = data.get(n - 1)[0] - data.getFirst()[0];
        if (n < 2 || span <= 0) return 1.0;
        double avgSpacing = span / (n - 1);
        return Math.max(avgSpacing * 1e-3, (Math.max(1, k) / 2.0) * avgSpacing);
    }

    /**
     * Gauß-gewichtete Streuung der Residuen an der Stelle {@code x}: jeder Datenpunkt trägt
     * gemäß {@code exp(-(x_j - x)²/(2·bandwidth²))} bei, statt hart auf k Nachbarn abzuschneiden.
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
     * Datenpunkten, die {@code x} einschließen; außerhalb des Datenbereichs wird der jeweilige
     * Randwert fortgeschrieben. Nur für {@link SigmaMode#RESIDUAL_LOCAL} genutzt.
     *
     * @param fallback Rückgabewert, falls keine lokalen Sigma-Werte vorliegen
     */
    public static double interpolateLocalSigma(List<double[]> data, double[] localSigmas, double x, double fallback) {
        if (localSigmas == null || data.isEmpty()) return fallback;
        int n = data.size();
        if (n == 1) return localSigmas[0];

        if (x <= data.getFirst()[0]) return localSigmas[0];
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
