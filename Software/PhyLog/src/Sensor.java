import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * Basisklasse aller Sensoren: Name, Einheit(en) und Umrechnung eines rohen Firmware-Werts
 * (Slot + Rohwert) in eine physikalische Größe.
 */
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

    /** Prüft, ob {@code unitStr} der Einheit oder einem Alias dieses Sensors entspricht. */
    public boolean matchesUnit(String unitStr) {
        if (unitStr == null) return false;
        String clean = unitStr.trim().toUpperCase();
        if (clean.equalsIgnoreCase(unit)) return true;
        return unitAliases.stream().anyMatch(a -> a.equalsIgnoreCase(clean));
    }

    /** Dekodiert einen Rohwert der Firmware anhand der Slot-Nummer in eine physikalische Größe. */
    public abstract double decode(int slot, long rawValue);

    /** Bezeichner, den die Firmware für diesen Sensor beim {@code SET}-Kommando erwartet. */
    public abstract String getFirmwareTypeName();

    /** Messgröße(n) dieses Sensorprofils (aktuell jeweils genau eine, siehe {@code NoSensor}). */
    public abstract List<Quantity> getQuantities();

    /**
     * Kalibrierwerte, die dieser Sensor über {@code CalibrationDialog} anpassbar macht - leer
     * per Default. Sensoren mit einem Umrechnungsfaktor oder einer Empfindlichkeit (z. B.
     * {@code HX711Sensor}, {@code MicrophoneSensor}) überschreiben das, statt einen eigenen
     * Dialog zu bauen - so profitiert jeder künftige Sensor mit Kalibrierbedarf vom selben,
     * generischen Dialog.
     */
    public List<CalibrationParameter> getCalibrationParameters() {
        return List.of();
    }

    @Override
    public String toString() {
        return name;
    }

    /**
     * Ein einzelner, benannter Kalibrierwert eines Sensors (z. B. Kalibrierfaktor,
     * Empfindlichkeit). get()/set() binden direkt an das interne Feld des Sensors, ohne dass
     * die aufrufende UI dessen konkreten Typ kennen muss.
     */
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

    /** Eine benannte Messgröße eines Sensorprofils, z. B. "Spannung (V)"; dient als Spaltenkopf. */
    public static final class Quantity {
        public final String label;
        public final String unit;
        /** Firmware-Slot, aus dem dieser Wert dekodiert wird. */
        public final int slot;

        public Quantity(String label, String unit, int slot) {
            this.label = label;
            this.unit = unit;
            this.slot = slot;
        }

        public String getColumnHeader() {
            return unit.isEmpty() ? label : label + " (" + unit + ")";
        }
    }
}
