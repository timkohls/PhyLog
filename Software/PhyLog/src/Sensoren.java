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
    public List<Quantity> getQuantities() {
        return List.of();
    }

    @Override
    public String getFirmwareTypeName() {
        return "NONE";
    }
}

/**
 * Gemeinsame Dekodierlogik der beiden INA219-Register (Busspannung, Strom), genutzt von
 * {@link INA219VoltageSensor} und {@link INA219CurrentSensor}. Die Firmware kennt nur einen
 * INA219-Typ und schickt für ihn immer beide Register - welches davon angezeigt wird,
 * entscheidet allein das gewählte Software-Profil.
 */
abstract class AbstractINA219Sensor extends Sensor {
    private static final double CURRENT_LSB = 0.0001; // 0.1 mA pro Bit

    AbstractINA219Sensor(String name, String unit, List<String> unitAliases) {
        super(name, unit, unitAliases);
    }

    static double decodeVoltage(long rawValue) {
        long masked = rawValue & 0xFFFF;
        return ((masked >> 3) & 0x1FFF) * 0.004; // Bus-Spannung, 4 mV LSB
    }

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
 * INA219-Profil: nur Spannung (Slot 0). Strom wird von der Firmware zwar mitgeschickt, hier aber
 * ignoriert, damit keine Stromwerte versehentlich in die Spannungs-Spalte geraten.
 *
 * <p>Ein echter Messwert von 0.0 V gilt als gültig (zeigt, dass tatsächlich gemessen wird);
 * gefiltert wird nur der bekannte ~1,016 V-Störwert einer schwebenden Leitung.</p>
 */
class INA219VoltageSensor extends AbstractINA219Sensor {
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

/** INA219-Profil: nur Strom (Slot 1). Spannung wird ignoriert (siehe {@link INA219VoltageSensor}). */
class INA219CurrentSensor extends AbstractINA219Sensor {
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
 * VEML7700: dekodiert Umgebungslicht in Lux (Slot 0) via I2C.
 *
 * <p>Der Rohwert kommt von der Firmware als vorzeichenloser 16-Bit-Wert (0..65535) - anders als
 * beim INA219-Strom gibt es hier kein Vorzeichen zu rekonstruieren. Wird dieser Rohwert
 * irgendwo auf dem Weg fälschlich als vorzeichenbehaftet interpretiert, kippen große Rohwerte
 * (&gt; 32767) in negative Lux-Werte um - siehe Firmware-Fix in {@code readI2CRegister16}.</p>
 */
class VEML7700Sensor extends Sensor {
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
