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

/** Gemeinsame Dekodierlogik der beiden INA219-Register (Busspannung, Strom), genutzt von
 *  {@link INA219VoltageSensor} und {@link INA219CurrentSensor}. */
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

/** INA219-Profil: nur Spannung (Slot 0). */
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

/** INA219-Profil: nur Strom (Slot 1). */
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

/** VEML7700: dekodiert Umgebungslicht in Lux (Slot 0) via I2C. Rohwert ist vorzeichenlos
 *  16-Bit; falsche Vorzeichen-Interpretation würde große Rohwerte in negative Lux kippen. */
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

/** HX711: dekodiert die Messwerte einer Wägezelle/Kraftsensors (Slot 0). */
class HX711Sensor extends Sensor {
    public HX711Sensor() {
        super("HX711 (Kraft / Gewicht)", "N", List.of("N", "G", "KG"));
    }

    @Override
    public double decode(int slot, long rawValue) {
        // TODO: eigenen Kalibrierungsfaktor der Wägezelle eintragen (z. B. rawValue / 2280.0).
        return rawValue / 1000.0;
    }

    @Override
    public List<Quantity> getQuantities() {
        return List.of(new Quantity("Kraft", "N", 0));
    }

    @Override
    public String getFirmwareTypeName() {
        return "HX711";
    }
}

/**
 * INMP441: I2S-MEMS-Mikrofon (Slot 0). Die Firmware liest die hohe I2S-Abtastrate intern und
 * schickt je Zyklus nur den Spitzenbetrag (Peak-Amplitude, 0..8388607 für 24 Bit) - der
 * serielle Kanal bleibt dadurch identisch zu allen anderen Sensoren (ein Wert pro Intervall).
 * Ohne Kalibrierung auf ein Referenz-Schallpegelmessgerät ist das eine relative Lautstärke,
 * kein kalibrierter dB(SPL)-Wert.
 */
class MicrophoneSensor extends Sensor {
    private static final double FULL_SCALE = 8_388_607.0; // 2^23 - 1, größter 24-Bit-Betrag

    public MicrophoneSensor() {
        super("INMP441 (Mikrofon)", "%", List.of("%", "REL"));
    }

    @Override
    public double decode(int slot, long rawValue) {
        return (rawValue / FULL_SCALE) * 100.0;
    }

    @Override
    public List<Quantity> getQuantities() {
        return List.of(new Quantity("Lautstärke (rel.)", "%", 0));
    }

    @Override
    public String getFirmwareTypeName() {
        return "MIC";
    }
}
