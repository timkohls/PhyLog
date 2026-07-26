import java.util.List;

/** Repräsentiert einen unbelegten Sensorkanal. */
class NoSensor extends Sensor {
    public NoSensor() {
        super("-- Kein Sensor --", "", List.of());
    }

    @Override
    public double decode(int slot, long rawValue) {
        return 0.0;
    }

    @Override
    public String getFirmwareTypeName() {
        return "NONE";
    }
}

/**
 * INA219: dekodiert Busspannung (Slot 0) und Strom (Slot 1) via I2C.
 *
 * <p>Ist die Sense-/Bus-Leitung nicht angeschlossen oder schwebt sie, liefert der ADC einen
 * stabilen, aber falschen Wert nahe {@link #PHANTOM_FLOATING_BIAS} statt einer echten Messung.
 * {@link #isPhantomReading} filtert genau diesen bekannten Störwert heraus. Ein echter Messwert
 * von 0.0 wird dagegen bewusst NICHT gefiltert - seit die Firmware bei einem I2C-Fehler gar kein
 * Datenpaket mehr schickt (statt früher fälschlich 0 zu senden), ist eine ankommende 0 eine
 * echte Messung und zeigt, dass gerade tatsächlich gemessen wird.</p>
 */
class INA219Sensor extends Sensor {
    private static final double CURRENT_LSB = 0.0001; // 0.1 mA pro Bit

    /** Bekannter Störwert einer schwebenden Leitung (siehe Klassenkommentar). Bei Bedarf anpassen. */
    private static final double PHANTOM_FLOATING_BIAS = 1.016;
    /** Toleranz um {@link #PHANTOM_FLOATING_BIAS} herum. */
    private static final double PHANTOM_TOLERANCE = 0.02;

    public INA219Sensor() {
        super("INA219 (Spannung & Strom)", "V", List.of("V", "VOLT", "A", "MA"));
    }

    @Override
    public double decode(int slot, long rawValue) {
        if (slot == 0) {
            long masked = rawValue & 0xFFFF;
            return ((masked >> 3) & 0x1FFF) * 0.004; // Bus-Spannung, 4 mV LSB
        } else {
            short signedRaw = (short) (rawValue & 0xFFFF);
            return signedRaw * CURRENT_LSB; // Strom, vorzeichenbehaftet
        }
    }

    @Override
    public boolean isPhantomReading(int slot, double decodedValue) {
        return Math.abs(decodedValue - PHANTOM_FLOATING_BIAS) < PHANTOM_TOLERANCE;
    }

    @Override
    public String getFirmwareTypeName() {
        return "INA219";
    }
}

/** VEML7700: dekodiert Umgebungslicht in Lux (Slot 0) via I2C. */
class VEML7700Sensor extends Sensor {
    public VEML7700Sensor() {
        super("VEML7700 (Licht / Lux)", "lx", List.of("LX", "LUX"));
    }

    @Override
    public double decode(int slot, long rawValue) {
        return rawValue * 0.0576; // Skalierung auf Lux bei Standard-Gain/Integration
    }

    @Override
    public String getFirmwareTypeName() {
        return "VEML7700";
    }
}