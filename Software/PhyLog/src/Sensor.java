import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/** Basisklasse aller Sensoren zur Dekodierung von Rohwerten in physikalische Größen. */
public abstract class Sensor {
    private final String name;
    private final String unit;
    private final List<String> unitAliases;

    public Sensor(String name, String unit, List<String> unitAliases) {
        this.name = name;
        this.unit = unit;
        this.unitAliases = unitAliases;
    }

    public String getName() {
        return name;
    }

    public String getUnit() {
        return unit;
    }

    /** Prüft, ob der String der Einheit oder einem Alias entspricht. */
    public boolean matchesUnit(String unitStr) {
        if (unitStr == null) return false;
        String clean = unitStr.trim().toUpperCase();
        if (clean.equalsIgnoreCase(unit)) return true;
        return unitAliases.stream().anyMatch(a -> a.equalsIgnoreCase(clean));
    }

    /** Dekodiert einen Rohwert der Firmware in eine physikalische Größe. */
    public abstract double decode(int slot, long rawValue);

    /** @return Firmware-Typbezeichnung für den Sensor. */
    public abstract String getFirmwareTypeName();

    /** @return Liste der Messgrößen dieses Sensors. */
    public abstract List<Quantity> getQuantities();

    /** Liefert dieser Sensor statt einzelner Zeitreihen-Messwerte ein laufend aktualisiertes
     *  Frequenzspektrum? Bestimmt, ob ein Kanal über die normale Tabelle/Zeitachse oder über die
     *  Frequenz-/Magnitude-Darstellung angezeigt wird.
     *
     * @return {@code true} für Spektrum-Sensoren (Standard: {@code false}) */
    public boolean producesSpectrum() {
        return false;
    }

    /** Realistische Obergrenze der Abtastrate für diesen Sensor in Hz (Standard: 1000).
     *
     * @return maximale sinnvolle Abtastrate in Hz */
    public int getMaxSampleRateHz() {
        return 1000;
    }

    /** @return anpassbare Kalibrierparameter (Standard: leere Liste). */
    public List<CalibrationParameter> getCalibrationParameters() {
        return List.of();
    }

    @Override
    public String toString() {
        return name;
    }

    /** Ein benannter Kalibrierwert eines Sensors (z. B. Faktor oder Empfindlichkeit). */
    public static final class CalibrationParameter {
        public final String label;
        public final String unit;
        private final DoubleSupplier getter;
        private final DoubleConsumer setter;

        public CalibrationParameter(String label, String unit, DoubleSupplier getter, DoubleConsumer setter) {
            this.label = label;
            this.unit = unit;
            this.getter = getter;
            this.setter = setter;
        }

        public double get() {
            return getter.getAsDouble();
        }

        public void set(double value) {
            setter.accept(value);
        }
    }

    /** Eine benannte Messgröße (z. B. für Tabellenspalten). */
    public static final class Quantity {
        public final String label;
        public final String unit;
        public final int slot;

        public Quantity(String label, String unit, int slot) {
            this.label = label;
            this.unit = unit;
            this.slot = slot;
        }

        /** @return formatierter Spaltenkopf (z. B. "Spannung (V)"). */
        public String getColumnHeader() {
            return unit.isEmpty() ? label : label + " (" + unit + ")";
        }
    }
}
