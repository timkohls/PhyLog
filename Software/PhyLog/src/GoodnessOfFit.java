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
        UNDERFIT
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
        /** Ein einziger Wert, aus der Streuung aller Punkte um den aktuellen Fit geschätzt
         *  (empirische Standardabweichung der Residuen unter Annahme gaußverteilten Rauschens). */
        RESIDUAL_GLOBAL,
        /** Ortsabhängiger Wert je Punkt, aus der Streuung der nächsten Nachbarn um den Fit
         *  geschätzt - passt sich ungleichmäßig verteiltem Rauschen entlang der Messreihe an. */
        RESIDUAL_LOCAL
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

    /** Ergebnis von {@link #estimateSigma}: globaler Wert sowie (nur im lokalen Modus) ein
     *  Sigma-Wert je Datenpunkt. */
    public static final class SigmaEstimate {
        public final double globalSigma;
        public final double[] localSigmas;

        SigmaEstimate(double globalSigma, double[] localSigmas) {
            this.globalSigma = globalSigma;
            this.localSigmas = localSigmas;
        }
    }

    /**
     * Ordnet einen reduzierten Chi-Quadrat-Wert einer {@link ChiRating} zu.
     */
    public static ChiRating rate(double reducedChiSquare) {
        if (reducedChiSquare < CHI_OVERFIT_THRESHOLD) return ChiRating.OVERFIT;
        if (reducedChiSquare <= CHI_GOOD_THRESHOLD) return ChiRating.GOOD;
        if (reducedChiSquare <= CHI_MODERATE_THRESHOLD) return ChiRating.MODERATE;
        return ChiRating.UNDERFIT;
    }

    /**
     * Liefert die Anzeigefarbe für einen reduzierten Chi-Quadrat-Wert, konsistent mit
     * {@link #rate(double)}.
     *
     * @return Grün für einen guten Fit, Gelb für Über-/mäßige Anpassung, Rot für Unteranpassung
     */
    public static Color colorFor(double reducedChiSquare) {
        return switch (rate(reducedChiSquare)) {
            case OVERFIT, MODERATE -> Theme.WARNING;
            case GOOD -> Theme.SUCCESS;
            case UNDERFIT -> Theme.DANGER;
        };
    }

    /**
     * Berechnet das reduzierte Chi-Quadrat für eine gegebene Fit-Funktion:
     * chi²_red = (1 / DOF) * Summe((y_i - f(x_i))² / sigma_i²), DOF = n - Parameteranzahl.
     *
     * <p>Hinweis: Bei {@link SigmaMode#RESIDUAL_GLOBAL} liegt chi²_red durch die Art der
     * Schätzung (sigma wird ja gerade so gewählt, dass die Residuen im Mittel genau sigma
     * entsprechen) rechnerisch immer nahe 1 - dort dient die Anzeige eher der Kontrolle, dass
     * die Schätzung funktioniert hat, als einer echten Gütebewertung. Bei
     * {@link SigmaMode#RESIDUAL_LOCAL} bleibt chi²_red dagegen aussagekräftig, da sigma_i aus
     * den Nachbarn, nicht aus dem Punkt selbst geschätzt wird.</p>
     *
     * @param data           die Datenpunkte, auf denen der Fit beruht
     * @param func           die angepasste Funktion
     * @param parameterCount Anzahl der freien Parameter des Modells (für die Freiheitsgrade)
     * @param sigmaAt        liefert die Standardabweichung für den Punkt mit gegebenem Index
     */
    public static ChiSquareResult calculateReducedChiSquare(List<double[]> data, CurveFitting.FunctionEvaluator func,
                                                             int parameterCount, IntToDoubleFunction sigmaAt) {
        int n = data.size();
        int dof = n - parameterCount;

        if (dof <= 0) {
            return new ChiSquareResult(0.0, 1);
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
     * gaußverteilten Rauschens ist die empirische Standardabweichung der Residuen genau dieser
     * Schätzer. Bei {@link SigmaMode#RESIDUAL_GLOBAL} geschieht das einmal für den gesamten
     * Datensatz; bei {@link SigmaMode#RESIDUAL_LOCAL} lokal je Punkt aus dessen nächsten
     * Nachbarn (siehe {@link #localWindowStdDev}).
     *
     * @param data              die Datenpunkte, auf denen der Fit beruht
     * @param func              die angepasste Funktion, oder {@code null} ohne aktiven Fit
     * @param parameterCount    Anzahl der freien Parameter (für die Freiheitsgrade)
     * @param mode              der gewünschte Sigma-Modus
     * @param localNeighbors    Anzahl der je Punkt einbezogenen Nachbarn ({@link SigmaMode#RESIDUAL_LOCAL})
     * @param fallbackConstant  Rückfallwert für {@link SigmaMode#CONSTANT} bzw. ohne Fit
     */
    public static SigmaEstimate estimateSigma(List<double[]> data, CurveFitting.FunctionEvaluator func,
                                               int parameterCount, SigmaMode mode, int localNeighbors,
                                               double fallbackConstant) {
        int n = data.size();
        if (func == null || n == 0 || mode == SigmaMode.CONSTANT) {
            return new SigmaEstimate(fallbackConstant, null);
        }

        double[] residuals = new double[n];
        for (int i = 0; i < n; i++) {
            double[] pt = data.get(i);
            residuals[i] = pt[1] - func.eval(pt[0]);
        }

        if (mode == SigmaMode.RESIDUAL_GLOBAL) {
            int dof = Math.max(1, n - parameterCount);
            double sumSq = 0;
            for (double r : residuals) sumSq += r * r;
            return new SigmaEstimate(Math.max(1e-6, Math.sqrt(sumSq / dof)), null);
        }

        // RESIDUAL_LOCAL
        int k = Math.max(1, Math.min(localNeighbors, n - 1));
        double[] localSigmas = new double[n];
        for (int i = 0; i < n; i++) {
            localSigmas[i] = Math.max(1e-6, localWindowStdDev(residuals, i, k));
        }
        return new SigmaEstimate(fallbackConstant, localSigmas);
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
     * Lineare Interpolation der je Punkt geschätzten lokalen Sigma-Werte zwischen den beiden
     * Datenpunkten, die {@code x} einschließen - für Stellen zwischen echten Messpunkten (z. B.
     * beim Zeichnen des Toleranzbands mit vielen Zwischenschritten). Außerhalb des Datenbereichs
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
