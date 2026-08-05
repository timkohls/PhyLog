import java.util.List;

/**
 * Repräsentiert einen unbelegten Sensorkanal.
 */
class NoSensor extends Sensor {
    /**
     * Erstellt einen Platzhalter-Sensor für unbelegte Kanäle.
     */
    public NoSensor() {
        super("-- Kein Sensor --", "", List.of());
    }

    @Override
    public double decode(int slot, long rawValue) {
        return 0.0;
    }

    @Override
    public List<Quantity> getQuantities() {
        return List.of();
    }

    @Override
    public String getFirmwareTypeName() {
        return "NONE";
    }
}

/**
 * Basisklasse für INA219-Sensoren zur Dekodierung der Registerwerte.
 */
abstract class AbstractINA219Sensor extends Sensor {
    private static final double CURRENT_LSB = 0.0001; // 0.1 mA pro Bit

    /**
     * Initialisiert den INA219-Sensor.
     *
     * @param name        Anzeigename des Sensors.
     * @param unit        Standard-Einheit.
     * @param unitAliases Liste alternativer Einheitenbezeichnungen.
     */
    AbstractINA219Sensor(String name, String unit, List<String> unitAliases) {
        super(name, unit, unitAliases);
    }

    /**
     * Dekodiert den Rohwert für die Busspannung in Volt.
     *
     * @param rawValue Der empfangene Rohwert.
     * @return Spannung in Volt.
     */
    static double decodeVoltage(long rawValue) {
        long masked = rawValue & 0xFFFF;
        return ((masked >> 3) & 0x1FFF) * 0.004; // Bus-Spannung, 4 mV LSB
    }

    /**
     * Dekodiert den Rohwert für den Strom in Ampere.
     *
     * @param rawValue Der empfangene Rohwert.
     * @return Stromstärke in Ampere.
     */
    static double decodeCurrent(long rawValue) {
        short signedRaw = (short) (rawValue & 0xFFFF);
        return signedRaw * CURRENT_LSB;
    }

    @Override
    public String getFirmwareTypeName() {
        return "INA219";
    }
}

/**
 * INA219-Sensorprofil für Spannungsmessungen.
 */
class INA219VoltageSensor extends AbstractINA219Sensor {
    /**
     * Erstellt einen INA219-Spannungssensor.
     */
    public INA219VoltageSensor() {
        super("INA219 (Spannung)", "V", List.of("V", "VOLT"));
    }

    @Override
    public double decode(int slot, long rawValue) {
        return decodeVoltage(rawValue);
    }

    @Override
    public List<Quantity> getQuantities() {
        return List.of(new Quantity("Spannung", "V", 0));
    }
}

/**
 * INA219-Sensorprofil für Strommessungen.
 */
class INA219CurrentSensor extends AbstractINA219Sensor {
    /**
     * Erstellt einen INA219-Stromsensor.
     */
    public INA219CurrentSensor() {
        super("INA219 (Strom)", "A", List.of("A", "AMP", "MA"));
    }

    @Override
    public double decode(int slot, long rawValue) {
        return decodeCurrent(rawValue);
    }

    @Override
    public List<Quantity> getQuantities() {
        return List.of(new Quantity("Strom", "A", 1));
    }
}

/**
 * VEML7700-Sensor zur Beleuchtungsstärkemessung in Lux.
 */
class VEML7700Sensor extends Sensor {
    /**
     * Erstellt einen VEML7700-Lichtsensor.
     */
    public VEML7700Sensor() {
        super("VEML7700 (Licht / Lux)", "lx", List.of("LX", "LUX"));
    }

    @Override
    public double decode(int slot, long rawValue) {
        return (rawValue & 0xFFFF) * 0.0576; // Skalierung auf Lux bei Gain 1x / IT 100ms
    }

    @Override
    public List<Quantity> getQuantities() {
        return List.of(new Quantity("Beleuchtungsstärke", "lx", 0));
    }

    @Override
    public String getFirmwareTypeName() {
        return "VEML7700";
    }
}

/**
 * HX711-Sensor zur Kraft- und Gewichtsmessung via Wägezelle.
 */
class HX711Sensor extends Sensor {
    private double calibrationFactor = 10000.0;

    /**
     * Erstellt einen HX711-Kraftsensor.
     */
    public HX711Sensor() {
        super("HX711 (Kraft / Gewicht)", "N", List.of("N", "G", "KG"));
    }

    @Override
    public double decode(int slot, long rawValue) {
        return rawValue / calibrationFactor;
    }

    @Override
    public List<Quantity> getQuantities() {
        return List.of(new Quantity("Kraft", "N", 0));
    }

    @Override
    public String getFirmwareTypeName() {
        return "HX711";
    }

    @Override
    public List<CalibrationParameter> getCalibrationParameters() {
        return List.of(new CalibrationParameter("Kalibrierfaktor", "Counts/N",
                () -> calibrationFactor, v -> calibrationFactor = v));
    }
}

/**
 * INMP441-Mikrofon als Frequenzspektrum statt einzelnem dB-Wert (siehe {@link MicrophoneSensor}
 * für die klassische Variante). Liefert selbst keine Zeitreihen-Messwerte - {@code decode} wird
 * nie aufgerufen, da die Firmware für diesen Sensortyp ausschließlich {@code #SPEC}-Pakete
 * schickt (siehe {@link AcquisitionEngine}), keine regulären Datenpakete.
 */
class MicrophoneSpectrumSensor extends Sensor {
    /**
     * Erstellt einen INMP441-Sensor im Spektrum-Modus.
     */
    public MicrophoneSpectrumSensor() {
        super("INMP441 (Mikrofon, Frequenzspektrum)", "dB", List.of("DB"));
    }

    @Override
    public double decode(int slot, long rawValue) {
        return 0.0; // ungenutzt, siehe Klassenkommentar
    }

    @Override
    public List<Quantity> getQuantities() {
        return List.of(new Quantity("Frequenzspektrum", "dB", 0));
    }

    @Override
    public String getFirmwareTypeName() {
        return "MICSPEC";
    }

    @Override
    public boolean producesSpectrum() {
        return true;
    }
}

/**
 * INMP441 I2S-Mikrofon zur Schätzung des Schalldruckpegels in dB.
 */
class MicrophoneSensor extends Sensor {
    private static final double FULL_SCALE = 8_388_607.0; // 2^23 - 1, größter 24-Bit-Betrag
    private static final double REFERENCE_SPL_DB = 94.0;

    private double sensitivityDbfsAt94db = 0.0;

    /**
     * Erstellt einen INMP441-Mikrofonsensor.
     */
    public MicrophoneSensor() {
        super("INMP441 (Mikrofon)", "dB", List.of("DB", "DBSPL"));
    }

    @Override
    public double decode(int slot, long rawValue) {
        double amplitude = Math.max(rawValue, 1) / FULL_SCALE;
        double dbFullScale = 20.0 * Math.log10(amplitude);
        return REFERENCE_SPL_DB + (dbFullScale - sensitivityDbfsAt94db);
    }

    @Override
    public List<Quantity> getQuantities() {
        return List.of(new Quantity("Schalldruckpegel (geschätzt)", "dB", 0));
    }

    @Override
    public String getFirmwareTypeName() {
        return "MIC";
    }

    @Override
    public List<CalibrationParameter> getCalibrationParameters() {
        return List.of(new CalibrationParameter("Empfindlichkeit @ 94 dB SPL", "dBFS",
                () -> sensitivityDbfsAt94db, v -> sensitivityDbfsAt94db = v));
    }
}
