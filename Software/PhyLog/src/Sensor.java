import java.util.List;

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

    @Override
    public String toString() {
        return name;
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
