import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Zustandsbehafteter Ausreißerfilter auf Basis eines gleitenden Medians samt Robustheitsmaß
 * (Median Absolute Deviation, MAD). Gedacht für Sensoren, deren Rohprotokoll keine eigene
 * Datenintegritätsprüfung hat (z. B. der bit-gebangte HX711 ohne CRC - siehe
 * {@link OneWireSensor#isCrcChecked()} für das Gegenbeispiel beim DS18B20) und die deshalb
 * gelegentlich einzelne, stark abweichende Störwerte liefern - z. B. eingekoppeltes Netzbrumm
 * oder eine kleine elektrostatische Entladung beim Berühren einer Wägezelle.
 *
 * <p>Arbeitet bewusst auf dem Rohwert (vor jeder physikalischen Umrechnung/Kalibrierung), damit
 * eine spätere Änderung eines Kalibrierfaktors zur Laufzeit (siehe
 * {@link Sensor.CalibrationParameter}) den bisherigen Fensterinhalt nicht ungültig macht.</p>
 *
 * <p>Ein neuer Wert gilt als Ausreißer, wenn er weiter als {@code thresholdFactor} auf
 * Standardabweichung skalierte MADs vom Median der zuletzt akzeptierten Werte entfernt liegt -
 * mindestens aber {@code minAbsoluteThreshold}, damit eine nahezu rauschfreie/konstante Messreihe
 * (MAD ≈ 0) nicht überempfindlich wird. Ausreißer werden NICHT ins Fenster aufgenommen, damit ein
 * einzelner Störwert nicht sofort als neue "Normallage" akzeptiert wird und darauffolgende, echte
 * Werte fälschlich verworfen werden.</p>
 *
 * <p>Nicht threadsicher - für die Verwendung aus {@link Sensor#decode} ist das unkritisch, da
 * {@code decode} pro Sensorinstanz immer aus demselben Thread (Leseschleife der
 * {@link DeviceConnection}) aufgerufen wird.</p>
 */
public class OutlierFilter {

    /** Skalierungsfaktor von MAD auf eine zur Standardabweichung vergleichbare Größe, gültig für
     *  näherungsweise normalverteiltes Rauschen (Standardkonstante aus der MAD-Literatur). */
    private static final double MAD_TO_SIGMA = 1.4826;

    /** Mindestanzahl an Werten im Fenster, bevor überhaupt gefiltert wird - vorher wird jeder
     *  Wert unverändert akzeptiert, da noch keine belastbare Statistik existiert (u. a. direkt
     *  nach dem Umschalten auf den Sensor, siehe {@link #reset()}). */
    private static final int MIN_SAMPLES_BEFORE_FILTERING = 3;

    private final int windowSize;
    private final double thresholdFactor;
    private final double minAbsoluteThreshold;
    private final Deque<Double> window = new ArrayDeque<>();

    /**
     * @param windowSize            Anzahl zuletzt akzeptierter Werte, aus denen Median/MAD
     *                              berechnet werden (z. B. 7 - ein Kompromiss zwischen
     *                              Robustheit und Reaktionsträgheit).
     * @param thresholdFactor       Vielfaches der auf Standardabweichung skalierten MAD, ab dem
     *                              ein Wert als Ausreißer gilt (z. B. 6 - großzügig genug, um
     *                              normales Sensorrauschen nicht fälschlich zu verwerfen).
     * @param minAbsoluteThreshold  Bodenwert für die Schwelle in der Einheit des Rohwerts, damit
     *                              eine nahezu konstante Messreihe (MAD ≈ 0) nicht
     *                              überempfindlich wird.
     */
    public OutlierFilter(int windowSize, double thresholdFactor, double minAbsoluteThreshold) {
        if (windowSize < MIN_SAMPLES_BEFORE_FILTERING) {
            throw new IllegalArgumentException("windowSize muss mindestens " + MIN_SAMPLES_BEFORE_FILTERING + " sein");
        }
        this.windowSize = windowSize;
        this.thresholdFactor = thresholdFactor;
        this.minAbsoluteThreshold = minAbsoluteThreshold;
    }

    /** Prüft {@code value} gegen das aktuelle Fenster.
     *
     * @return {@code value} unverändert, falls er als plausibel gilt (wird dann auch ins Fenster
     *         aufgenommen), sonst {@link Double#NaN} als Markierung für "verwerfen". */
    public double filter(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return Double.NaN;
        }

        if (window.size() < MIN_SAMPLES_BEFORE_FILTERING) {
            accept(value);
            return value;
        }

        double median = median(window);
        double mad = medianAbsoluteDeviation(window, median);
        double threshold = Math.max(mad * MAD_TO_SIGMA * thresholdFactor, minAbsoluteThreshold);

        if (Math.abs(value - median) > threshold) {
            return Double.NaN;
        }

        accept(value);
        return value;
    }

    /** Leert das Fenster, z. B. beim Umschalten auf einen anderen physischen Sensor oder nach
     *  einer längeren Pause, in der sich die Messgröße bekanntermaßen stark verändert haben kann. */
    public void reset() {
        window.clear();
    }

    private void accept(double value) {
        window.addLast(value);
        if (window.size() > windowSize) {
            window.removeFirst();
        }
    }

    private static double median(Deque<Double> values) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int n = sorted.length;
        return (n % 2 == 1) ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    private static double medianAbsoluteDeviation(Deque<Double> values, double median) {
        double[] deviations = values.stream().mapToDouble(v -> Math.abs(v - median)).sorted().toArray();
        int n = deviations.length;
        return (n % 2 == 1) ? deviations[n / 2] : (deviations[n / 2 - 1] + deviations[n / 2]) / 2.0;
    }
}
