import java.util.List;

/**
 * Basisklasse aller Sensoren. Kennt Namen, Einheit(en) und entscheidet, wie ein roher
 * Firmware-Wert (Slot + Rohwert) in physikalische Größen umgerechnet wird.
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

    /**
     * Erkennt bekannte Störmuster (z. B. schwebende/nicht angeschlossene Leitungen), die zwar
     * als plausibler Zahlenwert dekodiert werden, aber keine echte Messung sind. Standard:
     * alles gilt als echte Messung.
     */
    public boolean isPhantomReading(int slot, double decodedValue) {
        return false;
    }

    /**
     * Eine benannte Messgröße eines Sensorprofils, z. B. "Spannung (V)". Wird als Spaltenkopf in
     * der Tabelle verwendet.
     */
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

    /**
     * Messgröße(n) dieses Sensorprofils - aktuell liefert jeder Sensor genau eine (oder keine,
     * siehe {@code NoSensor}). Als Liste modelliert, damit Tabellen-Spaltenaufbau einheitlich
     * bleibt, falls künftig doch wieder mehrere Größen gleichzeitig sinnvoll werden.
     */
    public abstract List<Quantity> getQuantities();

    @Override
    public String toString() {
        return name;
    }
}
