import java.util.List;

/**
 * Basisklasse aller Sensoren. Kennt Namen, Einheit und Einheiten-Aliase und entscheidet,
 * wie ein roher Firmware-Wert (Slot + Rohwert) in eine physikalische Größe umgerechnet wird.
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
     * als plausibler Zahlenwert dekodiert werden, aber keine echte Messung sind. Sensoren mit
     * solchen bekannten Artefakten überschreiben diese Methode; Standardverhalten: alles gilt
     * als echte Messung.
     */
    public boolean isPhantomReading(int slot, double decodedValue) {
        return false;
    }

    @Override
    public String toString() {
        return name;
    }
}
