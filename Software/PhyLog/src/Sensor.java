import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * Basisklasse aller Sensoren zur Dekodierung von Rohwerten in physikalische Größen.
 */
public abstract class Sensor {
    private final String name;
    private final String unit;
    private final List<String> unitAliases;

    /**
     * Erstellt ein neues Sensor-Profil.
     *
     * @param name        Name des Sensors.
     * @param unit        Primäre Einheit.
     * @param unitAliases Alternative Bezeichnungen der Einheit.
     */
    public Sensor(String name, String unit, List<String> unitAliases) {
        this.name = name;
        this.unit = unit;
        this.unitAliases = unitAliases;
    }

    /**
     * @return Der Name des Sensors.
     */
    public String getName() {
        return name;
    }

    /**
     * @return Die primäre Einheit.
     */
    public String getUnit() {
        return unit;
    }

    /**
     * Prüft, ob der String der Einheit oder einem Alias entspricht.
     *
     * @param unitStr Zu prüfende Einheit.
     * @return {@code true}, falls passend.
     */
    public boolean matchesUnit(String unitStr) {
        if (unitStr == null) return false;
        String clean = unitStr.trim().toUpperCase();
        if (clean.equalsIgnoreCase(unit)) return true;
        return unitAliases.stream().anyMatch(a -> a.equalsIgnoreCase(clean));
    }

    /**
     * Dekodiert einen Rohwert der Firmware in eine physikalische Größe.
     *
     * @param slot     Firmware-Slot-Nummer.
     * @param rawValue Rohwert der Firmware.
     * @return Der dekodierte Messwert.
     */
    public abstract double decode(int slot, long rawValue);

    /**
     * @return Firmware-Typbezeichnung für den Sensor.
     */
    public abstract String getFirmwareTypeName();

    /**
     * @return Liste der Messgrößen dieses Sensors.
     */
    public abstract List<Quantity> getQuantities();

    /**
     * Liefert dieser Sensor statt einzelner Zeitreihen-Messwerte ein laufend aktualisiertes
     * Frequenzspektrum (siehe {@link AcquisitionEngine.Listener#onSpectrumFrame})? Bestimmt in
     * {@link GUI}, ob ein Kanal über die normale Tabelle/Zeitachse oder über die
     * Frequenz-/Magnitude-Darstellung im {@link ChartPanel} angezeigt wird.
     *
     * @return {@code true} für Spektrum-Sensoren (Standard: {@code false})
     */
    public boolean producesSpectrum() {
        return false;
    }

    /**
     * Liefert die anpassbaren Kalibrierparameter (Standard: leere Liste).
     *
     * @return Liste der {@link CalibrationParameter}.
     */
    public List<CalibrationParameter> getCalibrationParameters() {
        return List.of();
    }

    @Override
    public String toString() {
        return name;
    }

    /**
     * Ein benannter Kalibrierwert eines Sensors (z. B. Faktor oder Empfindlichkeit).
     */
    public static final class CalibrationParameter {
        public final String label;
        public final String unit;
        private final DoubleSupplier getter;
        private final DoubleConsumer setter;

        /**
         * Erstellt einen neuen Kalibrierparameter.
         *
         * @param label  Beschriftung.
         * @param unit   Einheit des Parameters.
         * @param getter Funktion zum Auslesen.
         * @param setter Funktion zum Setzen.
         */
        public CalibrationParameter(String label, String unit, DoubleSupplier getter, DoubleConsumer setter) {
            this.label = label;
            this.unit = unit;
            this.getter = getter;
            this.setter = setter;
        }

        /**
         * @return Liest den aktuellen Kalibrierwert aus.
         */
        public double get() {
            return getter.getAsDouble();
        }

        /**
         * @param value Setzt den neuen Kalibrierwert.
         */
        public void set(double value) {
            setter.accept(value);
        }
    }

    /**
     * Eine benannte Messgröße (z. B. für Tabellenspalten).
     */
    public static final class Quantity {
        public final String label;
        public final String unit;
        public final int slot;

        /**
         * Erstellt eine Messgröße.
         *
         * @param label Beschriftung.
         * @param unit  Einheit.
         * @param slot  Firmware-Slot.
         */
        public Quantity(String label, String unit, int slot) {
            this.label = label;
            this.unit = unit;
            this.slot = slot;
        }

        /**
         * @return Formatierter Spaltenkopf (z. B. "Spannung (V)").
         */
        public String getColumnHeader() {
            return unit.isEmpty() ? label : label + " (" + unit + ")";
        }
    }
}
