import java.util.List;

/**
 * Basisklasse für Sensoren an einem I2S-Bus (digitale MEMS-Mikrofone und ähnliche Module im
 * Standard-Philips-I2S-Format). Die Firmware kennt seit v9.1 kein einzelnes I2S-Sensormodell mehr
 * namentlich (kein spezifisch "INMP441" mehr) - sie steuert nur noch generisch "I2S-Gerät mit
 * dieser Abtastrate, diesem Slot, dieser Bit-Ausrichtung" an (siehe {@code TYPE_I2S} und
 * {@code parseI2SSetPayload} in phylog_firmware.ino). Vorausgesetzt wird dabei weiterhin ein
 * Standard-Philips-I2S-Gerät mit 32-Bit-Slots, wie es praktisch alle gängigen I2S-MEMS-Mikrofone
 * verwenden - alles Modellspezifische (Vollausschlag, physikalische Umrechnung wie dB SPL) bleibt
 * vollständig hier auf der Software-Seite.
 *
 * <p>Zwei Betriebsarten teilen sich dieselbe I2S-Hardwarekonfiguration: Einzelwert pro Zyklus
 * (geglätteter Spitzenwert, wie jeder andere Sensor über {@link Sensor#decode}) oder laufend
 * aktualisiertes Frequenzspektrum. Welche Betriebsart ein konkreter Sensor nutzt, entscheidet
 * {@link Sensor#producesSpectrum()} - siehe die beiden Mikrofon-Unterklassen in
 * SensorImplementations.java für ein Beispiel desselben physischen Sensors in beiden Modi.</p>
 *
 * <p>Konkrete Sensoren müssen nur {@link #getSampleRateHz()} und {@link #getShiftBits()}
 * implementieren - {@link #useRightSlot()} und {@link #isZeroValueAnError()} haben für den
 * Standardfall (linker I2S-Slot, ein durchgängiger Nullwert gilt als Verkabelungsfehler)
 * sinnvolle Vorgaben und müssen nur überschrieben werden, wenn ein Modul davon abweicht.</p>
 */
public abstract class I2SSensor extends Sensor {

    public I2SSensor(String name, String unit, List<String> unitAliases) {
        super(name, unit, unitAliases);
    }

    /** @return Abtastrate in Hz, mit der die Firmware den I2S-Bus für diesen Sensor betreibt
     *  (z. B. 16000 für ein typisches Sprachband-MEMS-Mikrofon wie das INMP441). */
    public abstract int getSampleRateHz();

    /** @return Rechts-Shift in Bit, um aus dem 32-Bit-I2S-Wort die gültigen, vorzeichenrichtig
     *  linksbündigen Datenbits zu isolieren (z. B. 8 für ein Mikrofon mit 24 gültigen Bits in
     *  einem 32-Bit-Datenwort). Bestimmt auf der Firmware-Seite sowohl die Rohwert-Extraktion als
     *  auch die Vollausschlag-Referenz für das Frequenzspektrum. */
    public abstract int getShiftBits();

    /** @return {@code true}, falls das Modul auf den rechten statt den linken I2S-Slot verdrahtet
     *  ist (abhängig vom SEL-Pin des jeweiligen Breakout-Boards). Standard: linker Slot, wie bei
     *  den meisten INMP441-Modulen mit SEL auf GND. */
    public boolean useRightSlot() {
        return false;
    }

    /** @return {@code true}, falls ein durchgängig exakt 0 gelesenes Abtastfenster als
     *  Verkabelungs-/Stromversorgungsfehler gemeldet werden soll (Standard: ja - ein reales,
     *  angeschlossenes und versorgtes Modul hat praktisch immer ein Eigenrauschen über Null). Bei
     *  Modulen, für die ein echter Nullwert ein gültiges Messergebnis ist, hier {@code false}
     *  zurückgeben. */
    public boolean isZeroValueAnError() {
        return true;
    }

    @Override
    public final String getFirmwareTypeName() {
        return "I2S";
    }

    @Override
    public final String getFirmwareSetPayload() {
        return "I2S," + (producesSpectrum() ? "SPEC" : "RAW") + "," + getSampleRateHz() + ","
                + (useRightSlot() ? "R" : "L") + "," + getShiftBits() + ","
                + (isZeroValueAnError() ? "1" : "0");
    }
}
