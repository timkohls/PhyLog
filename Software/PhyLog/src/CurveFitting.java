import java.util.ArrayList;
import java.util.List;

/**
 * Reine Ausgleichsrechnung (lineare/polynomiale, exponentielle und sinusförmige Regression),
 * losgelöst von jeder Zeichen- oder Sensor-Logik. Nimmt fertige (Zeit, Messwert)-Paare entgegen
 * und liefert ein {@link FitResult} mit der angepassten Funktion sowie einer für Menschen
 * lesbaren Beschreibung der ermittelten Parameter (für {@link ChiSquareInfoDialog}).
 *
 * <p>Diese Klasse war früher Teil von {@link ChartPanel}; sie wurde herausgelöst, damit
 * {@link ChartPanel} sich auf Zeichnen und Interaktion beschränken kann, während die Mathematik
 * unabhängig davon test- und wiederverwendbar bleibt.</p>
 */
public final class CurveFitting {

    private CurveFitting() {
    }

    /** Funktionaler Typ für eine angepasste Modellfunktion f(x), unabhängig vom Fit-Typ. */
    @FunctionalInterface
    public interface FunctionEvaluator {
        double eval(double x);
    }

    /** Ergebnis einer Regression: die angepasste Funktion, Anzahl freier Parameter, sowie eine
     *  für Menschen lesbare Beschreibung der Funktion und ihrer Parameter. */
    public static final class FitResult {
        public final FunctionEvaluator function;
        public final int parameterCount;
        public final FitDescription description;

        FitResult(FunctionEvaluator function, int parameterCount, FitDescription description) {
            this.function = function;
            this.parameterCount = parameterCount;
            this.description = description;
        }
    }

    /** Textuelle Beschreibung einer gefitteten Funktion: die Gleichung mit den konkret
     *  ermittelten Koeffizienten sowie eine Liste physikalisch interpretierbarer Kenngrößen. */
    public static final class FitDescription {
        public final String equation;
        public final List<String> parameterLines;

        FitDescription(String equation, List<String> parameterLines) {
            this.equation = equation;
            this.parameterLines = parameterLines;
        }
    }

    /**
     * Polynomiale Ausgleichsrechnung (kleinste Quadrate) über {@code data}. Die X-Werte werden
     * vor dem Aufstellen der Normalgleichungen um ihren Mittelwert zentriert, was die Kondition
     * des Gleichungssystems deutlich verbessert (insbesondere bei höheren Polynomgraden).
     *
     * @param data   Messpunkte (Zeit, Messwert)
     * @param degree Polynomgrad (1 = linear)
     * @param xUnit  Einheit der X-Achse, für die Parameterbeschreibung
     * @param yUnit  Einheit der Y-Achse, für die Parameterbeschreibung
     * @return das Fit-Ergebnis, oder {@code null} falls das Gleichungssystem singulär ist
     */
    public static FitResult fitPolynomial(List<double[]> data, int degree, String xUnit, String yUnit) {
        int n = data.size();
        int m = degree + 1;

        double meanX = 0;
        for (double[] pt : data) meanX += pt[0];
        meanX /= n;

        double[][] A = new double[m][m];
        double[] B = new double[m];

        for (double[] pt : data) {
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
            for (double coeff : coeffCentered) {
                val += coeff * p;
                p *= xc;
            }
            return val;
        };

        double[] standardCoeffs = toStandardCoefficients(coeffCentered, meanX);
        return new FitResult(func, m, buildPolynomialDescription(standardCoeffs, xUnit, yUnit));
    }

    /**
     * Exponentielle Ausgleichsrechnung f(x) = a * exp(b*x), indem der Fit im logarithmierten Raum
     * (ln(y) = ln(a) + b*x) linear gelöst wird. Punkte mit y &lt;= 0 werden übersprungen, da der
     * Logarithmus dort nicht definiert ist.
     *
     * <p>Hinweis: Dies minimiert die Fehlerquadrate im logarithmischen Raum, nicht im
     * Originalraum - ein Standard-Vorgehen, das große y-Werte gegenüber einem echten
     * nichtlinearen Fit tendenziell unterschätzt gewichtet. Für die meisten praktischen Zwecke
     * ausreichend genau.</p>
     *
     * @return das Fit-Ergebnis, oder {@code null} bei weniger als 2 positiven Messwerten
     */
    public static FitResult fitExponential(List<double[]> data, String xUnit, String yUnit) {
        double meanX = 0;
        int count = 0;
        for (double[] pt : data) {
            if (pt[1] > 0) {
                meanX += pt[0];
                count++;
            }
        }
        if (count < 2) return null;
        meanX /= count;

        double sumX = 0, sumLnY = 0, sumXLnY = 0, sumX2 = 0;

        for (double[] pt : data) {
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

        double y0 = a * Math.exp(-b * meanX); // Wert bei x=0 - "a" selbst bezieht sich wegen der
        // Zentrierung auf x=meanX, nicht auf x=0
        String equation = "f(x) = " + fmt(y0) + " \u00b7 e^(" + fmt(b) + "\u00b7x)";
        List<String> params = new ArrayList<>();
        params.add("Wert bei x=0: f(0) = " + fmt(y0) + " " + yUnit);
        params.add("Wachstumsrate b = " + fmt(b) + " 1/" + xUnit + (b < 0 ? " (Zerfall)" : " (Wachstum)"));
        if (Math.abs(b) > 1e-12) {
            double halfOrDoubleTime = Math.log(2) / Math.abs(b);
            params.add((b < 0 ? "Halbwertszeit" : "Verdopplungszeit") + " = " + fmt(halfOrDoubleTime) + " " + xUnit);
        }

        return new FitResult(func, 2, new FitDescription(equation, params));
    }

    /**
     * Sinusförmige Ausgleichsrechnung f(x) = A*sin(w*x + phi) + offset. Schätzt zunächst
     * Startwerte (Amplitude aus dem Wertebereich, Offset aus dem Mittelwert, Kreisfrequenz aus
     * dem Abstand der Nulldurchgänge, Phase aus dem ersten Datenpunkt) und verfeinert sie
     * anschließend nichtlinear über {@link #refineSinusFit}.
     *
     * @return das Fit-Ergebnis (4 Parameter), oder {@code null} bei weniger als 4 Datenpunkten
     */
    public static FitResult fitSinus(List<double[]> data, String xUnit, String yUnit) {
        if (data.size() < 4) return null;

        double minYVal = Double.MAX_VALUE;
        double maxYVal = -Double.MAX_VALUE;
        double sumY = 0;

        for (double[] pt : data) {
            sumY += pt[1];
            if (pt[1] < minYVal) minYVal = pt[1];
            if (pt[1] > maxYVal) maxYVal = pt[1];
        }
        double offset = sumY / data.size();
        double amplitude = (maxYVal - minYVal) / 2.0;

        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        for (double[] pt : data) {
            if (pt[0] < minX) minX = pt[0];
            if (pt[0] > maxX) maxX = pt[0];
        }

        int zeroCrossings = 0;
        double firstCrossingX = 0, lastCrossingX = 0;

        for (int i = 0; i < data.size() - 1; i++) {
            double y1 = data.get(i)[1] - offset;
            double y2 = data.get(i + 1)[1] - offset;

            if (y1 * y2 < 0) {
                double x1 = data.get(i)[0];
                double x2 = data.get(i + 1)[0];
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

        double firstX = data.get(0)[0];
        double firstYNorm = (data.get(0)[1] - offset) / (amplitude > 0 ? amplitude : 1.0);
        firstYNorm = Math.max(-1.0, Math.min(1.0, firstYNorm));
        double phi = Math.asin(firstYNorm) - omega * firstX;

        double[] params = refineSinusFit(data, amplitude, omega, phi, offset);
        double finalA = params[0];
        double finalW = params[1];
        double finalPhi = params[2];
        double finalC = params[3];

        FunctionEvaluator func = x -> finalA * Math.sin(finalW * x + finalPhi) + finalC;

        String equation = "f(x) = " + fmt(finalA) + " \u00b7 sin(" + fmt(finalW) + "\u00b7x + " + fmt(finalPhi) + ") + " + fmt(finalC);
        List<String> paramLines = new ArrayList<>();
        paramLines.add("Amplitude A = " + fmt(Math.abs(finalA)) + " " + yUnit);
        paramLines.add("Kreisfrequenz \u03c9 = " + fmt(finalW) + " rad/" + xUnit);
        paramLines.add("Frequenz f = " + fmt(Math.abs(finalW) / (2 * Math.PI)) + " Hz");
        if (Math.abs(finalW) > 1e-12) {
            paramLines.add("Periodendauer T = " + fmt(2 * Math.PI / Math.abs(finalW)) + " " + xUnit);
        }
        paramLines.add("Phase \u03c6 = " + fmt(finalPhi) + " rad");
        paramLines.add("Offset (Mittelwert) = " + fmt(finalC) + " " + yUnit);

        return new FitResult(func, 4, new FitDescription(equation, paramLines));
    }

    /**
     * Nichtlineare Ausgleichsrechnung für die Sinus-Anpassung mittels gedämpftem Gauss-Newton
     * (Levenberg-Marquardt-artig): Ein Schritt wird nur übernommen, wenn er die
     * Fehlerquadratsumme tatsächlich verringert; andernfalls wird der Dämpfungsfaktor lambda
     * erhöht (kleinerer, vorsichtigerer Schritt), statt bei einem ungünstigen Startwert zu
     * divergieren.
     *
     * @return die verfeinerten Parameter {A, w, phi, C}
     */
    private static double[] refineSinusFit(List<double[]> data, double A, double w, double phi, double C) {
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
                // Relativ statt absolut (siehe Klassenkommentar zu unterschiedlichen
                // Größenordnungen der vier Parameter, z. B. Amplitude in hunderten Lux vs.
                // Kreisfrequenz im Bereich von 0.001 rad/s): ein Schritt gilt erst als
                // vernachlässigbar, wenn er klein ist relativ zur Größe des jeweiligen
                // Parameters selbst - Math.max(1.0, ...) verhindert dabei nur, dass ein Parameter
                // nahe 0 die Schwelle auf (fast) 0 herunterzieht.
                boolean converged = Math.abs(dp[0]) < 1e-8 * Math.max(1.0, Math.abs(p[0]))
                        && Math.abs(dp[1]) < 1e-8 * Math.max(1.0, Math.abs(p[1]))
                        && Math.abs(dp[2]) < 1e-8 * Math.max(1.0, Math.abs(p[2]))
                        && Math.abs(dp[3]) < 1e-8 * Math.max(1.0, Math.abs(p[3]));
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

    /** Fehlerquadratsumme (nicht durch sigma normiert) der Sinus-Anpassung, nur zur
     *  Konvergenzprüfung in {@link #refineSinusFit} verwendet. */
    private static double sinusCost(List<double[]> data, double[] p) {
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
    private static double[] solveGaussian(double[][] A, double[] B) {
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

    private static final char[] SUPERSCRIPT_DIGITS =
            {'\u2070', '\u00b9', '\u00b2', '\u00b3', '\u2074', '\u2075', '\u2076', '\u2077', '\u2078', '\u2079'};

    /** Formatiert eine Zahl mit 4 Nachkommastellen für die Fit-Parameter-Anzeige. */
    private static String fmt(double v) {
        return String.format("%.4f", v);
    }

    /** Wandelt eine Zehnerpotenz in Unicode-Hochstellungsziffern um (z. B. 12 -&gt; "¹²"). */
    private static String superscript(int n) {
        StringBuilder sb = new StringBuilder();
        for (char c : String.valueOf(n).toCharArray()) {
            sb.append(SUPERSCRIPT_DIGITS[c - '0']);
        }
        return sb.toString();
    }

    /** Binomialkoeffizient "n über k" (für kleine, hier vorkommende n ausreichend genau als double). */
    private static double binomial(int n, int k) {
        double result = 1;
        for (int i = 0; i < k; i++) {
            result = result * (n - i) / (i + 1);
        }
        return result;
    }

    /**
     * Wandelt die um {@code meanX} zentrierten Fit-Koeffizienten (Basis (x-meanX)^i, wie sie
     * {@link #fitPolynomial} zur besseren Kondition verwendet) per Binomialentwicklung in
     * Standard-Koeffizienten der Basis x^j um, damit sich die gefittete Funktion als gewöhnliches
     * Polynom in x anzeigen lässt statt in der internen, für den Nutzer bedeutungslosen Basis.
     */
    private static double[] toStandardCoefficients(double[] centered, double meanX) {
        int n = centered.length - 1;
        double[] standard = new double[n + 1];
        for (int j = 0; j <= n; j++) {
            double sum = 0;
            double negMeanXPow = 1.0; // (-meanX)^(i-j), startet bei i=j mit Exponent 0
            for (int i = j; i <= n; i++) {
                sum += centered[i] * binomial(i, j) * negMeanXPow;
                negMeanXPow *= -meanX;
            }
            standard[j] = sum;
        }
        return standard;
    }

    /** Baut die Gleichung eines Polynoms als String, {@code a[j]} = Koeffizient von x^j. */
    private static String buildPolynomialEquation(double[] a) {
        int degree = a.length - 1;
        StringBuilder sb = new StringBuilder("f(x) = ");
        for (int power = degree; power >= 0; power--) {
            double coeff = a[power];
            String varPart = (power == 0) ? "" : (power == 1) ? "\u00b7x" : "\u00b7x" + superscript(power);
            if (power == degree) {
                sb.append(coeff < 0 ? "-" : "").append(fmt(Math.abs(coeff))).append(varPart);
            } else {
                sb.append(coeff < 0 ? " - " : " + ").append(fmt(Math.abs(coeff))).append(varPart);
            }
        }
        return sb.toString();
    }

    /**
     * Baut die für den Chi²-Dialog angezeigte Beschreibung eines Polynom-/linearen Fits: die
     * Gleichung in Standardform, plus physikalisch interpretierbare Kenngrößen (Steigung,
     * y-Achsenabschnitt bzw. bei Grad 2 zusätzlich Krümmung und Scheitelpunkt).
     */
    private static FitDescription buildPolynomialDescription(double[] standardCoeffs, String xUnit, String yUnit) {
        int degree = standardCoeffs.length - 1;
        String equation = buildPolynomialEquation(standardCoeffs);
        List<String> params = new ArrayList<>();

        if (degree == 1) {
            params.add("Steigung m = " + fmt(standardCoeffs[1]) + " " + yUnit + "/" + xUnit);
            params.add("y-Achsenabschnitt b = " + fmt(standardCoeffs[0]) + " " + yUnit);
        } else if (degree == 2) {
            double a2 = standardCoeffs[2], a1 = standardCoeffs[1], a0 = standardCoeffs[0];
            params.add("Krümmung a = " + fmt(a2) + " " + yUnit + "/" + xUnit + "\u00b2");
            params.add("Steigung bei x=0 (b) = " + fmt(a1) + " " + yUnit + "/" + xUnit);
            params.add("y-Achsenabschnitt c = " + fmt(a0) + " " + yUnit);
            if (Math.abs(a2) > 1e-12) {
                double xVertex = -a1 / (2 * a2);
                double yVertex = a0 - (a1 * a1) / (4 * a2);
                params.add("Scheitelpunkt: x = " + fmt(xVertex) + " " + xUnit + ", f(x) = " + fmt(yVertex) + " " + yUnit);
            }
        } else {
            for (int power = degree; power >= 0; power--) {
                String label = (power == 0) ? "Konstantes Glied a0"
                        : (power == 1) ? "Koeffizient von x (a1)"
                        : "Koeffizient von x" + superscript(power) + " (a" + power + ")";
                params.add(label + " = " + fmt(standardCoeffs[power]));
            }
        }

        return new FitDescription(equation, params);
    }
}
