/*
 * PhyLog ESP32 Firmware v9.0
 *
 * Steuert zwei unabhängige Messkanäle (A/B). Ein Kanal bekommt seinen Sensortyp per
 * SET,<Kanal>,<Typ>[,<Konfiguration>] (siehe GUI.pushSensorSelectionToFirmware), startet immer
 * bei TYPE_NONE.
 *
 * Die Firmware kennt nur noch generische Buskategorien, keine konkreten Sensormodelle mehr:
 *   ANALOG    Pin2=Eingang                          keine Konfiguration
 *   DIGITAL   Pin2=Eingang                           keine Konfiguration
 *   I2C       Pin2=SDA, Pin3=SCL                     Adresse + Init-/Lese-Register, siehe parseI2CSetPayload
 *   I2S       Pin2=WS, Pin3=BCLK, Pin4=SD            Modus RAW|SPEC
 *   ONEWIRE   Pin2=Datenleitung (ext. Pull-up 4,7kΩ) Konversions-/Lesekommando + Byte-Layout, siehe parseOneWireSetPayload
 *   HX711     Pin2=DOUT, Pin3=SCK                    keine Konfiguration (eigenes Protokoll, kein generisches Bus-Muster)
 * Ein neuer Sensor mit einer dieser Schnittstellen (z.B. ein zweiter I2C-Sensor) braucht deshalb
 * kein Firmware-Update - nur eine neue Java-Klasse (siehe I2CSensor.java/OneWireSensor.java).
 *
 * Kanal A und B hängen an physisch getrennten Bussen (I2C: Wire/Wire1, I2S: Port 0/1), damit
 * beide gleichzeitig denselben Typ nutzen können.
 *
 * Hardware-Notizen:
 * - I2S nutzt den neuen Treiber (driver/i2s_std.h) statt des alten driver/i2s.h - der alte bringt
 *   einen Legacy-ADC-Treiber mit, der mit dem von analogRead() genutzten Treiber kollidiert.
 * - Kanal-A-Pins liegen auf GPIO32/33/35 (ADC1-fähig, keine Strapping-Pins), Kanal B auf
 *   GPIO27/26/25 - siehe PINS_CHANNEL_A/B. Nicht ändern, ohne die ADC-Tauglichkeit/Strapping-Pin-
 *   Eigenschaften der Ziel-GPIOs zu prüfen.
 * - HX711 und 1-Wire nutzen direkten GPIO-Registerzugriff (gpioWriteFast/-ReadFast, owLow/
 *   owRelease/owRead) statt digitalWrite()/digitalRead() - deren IO-MUX-Overhead würde das
 *   µs-genaue Timing beider Protokolle sprengen.
 * - Arduino-IDE: "Partition Scheme" braucht Platz für den Bluetooth-Stack (z.B. "Default", nicht
 *   "No OTA (2MB APP...)").
 */

#include <Wire.h>
#include <driver/i2s_std.h>
#include <math.h>
#include "soc/gpio_struct.h"
#include "BluetoothSerial.h"

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error Bluetooth ist im Board-/Menuconfig-Profil deaktiviert - Partition Scheme mit \
       Bluetooth-Unterstützung wählen (siehe Dateikopf).
#endif

enum SensorType {
  TYPE_NONE = 0,
  TYPE_ANALOG = 1,
  TYPE_I2C = 2,
  TYPE_HX711 = 4,   // eigenes Protokoll, kein generisches Bus-Muster wie I2C/1-Wire
  TYPE_I2S = 5,
  TYPE_DIGITAL = 7,
  TYPE_ONEWIRE = 8
};

SensorType configChannelA = TYPE_NONE;
SensorType configChannelB = TYPE_NONE;

/** Ein Registerschreibvorgang der I2C-Init-Sequenz. */
struct I2CWriteSpec {
  uint8_t reg = 0;
  uint8_t data[4] = {0, 0, 0, 0};
  uint8_t dataLen = 0;
};

/** Ein Register-Lesevorgang je Abtastzyklus eines I2C-Sensors. */
struct I2CReadSpec {
  uint8_t reg = 0;
  uint8_t len = 0;      // 1-4 Byte
  bool bigEndian = true;
  uint8_t slot = 0;
};

const uint8_t MAX_I2C_WRITES = 4;
const uint8_t MAX_I2C_READS = 4;

/** Komplette, vom Host per SET-Kommando übertragene I2C-Sensorkonfiguration eines Kanals. */
struct I2CSensorConfig {
  uint8_t address = 0;
  I2CWriteSpec initWrites[MAX_I2C_WRITES];
  uint8_t initWriteCount = 0;
  I2CReadSpec reads[MAX_I2C_READS];
  uint8_t readCount = 0;
};

I2CSensorConfig i2cConfigChannelA;
I2CSensorConfig i2cConfigChannelB;
I2CSensorConfig &i2cConfigForChannel(char channelName);

/** Modus eines TYPE_I2S-Kanals: Einzelwert pro Zyklus oder laufendes Spektrum. Die
 *  I2S-Hardwarekonfiguration selbst ist für beide Modi identisch. */
struct I2SSensorConfig {
  bool spectrumMode = false;
};

I2SSensorConfig i2sConfigChannelA;
I2SSensorConfig i2sConfigChannelB;
I2SSensorConfig &i2sConfigForChannel(char channelName);

/** Generische 1-Wire-Sensorbeschreibung: Konversion anstoßen (convertCmd), warten
 *  (conversionDelayMs), Ergebnis lesen (readCmd, readLen Byte), Rohwert ab valueOffset/
 *  valueLen extrahieren, optional per CRC8 prüfen. "Skip ROM" (0xCC) nimmt die Firmware selbst
 *  an - unterstützt wird nur ein Sensor pro Bus. */
struct OneWireSensorConfig {
  uint8_t convertCmd = 0;
  unsigned long conversionDelayMs = 0;
  uint8_t readCmd = 0;
  uint8_t readLen = 0;
  uint8_t valueOffset = 0;
  uint8_t valueLen = 0;
  bool littleEndian = true;
  bool checkCrc = true;
  uint8_t slot = 0;
};

OneWireSensorConfig oneWireConfigChannelA;
OneWireSensorConfig oneWireConfigChannelB;
OneWireSensorConfig &oneWireConfigForChannel(char channelName);

// Arduino generiert für alle unten definierten Funktionen automatisch Prototypen ganz am
// Dateianfang - noch bevor die obigen Structs bekannt sind. Für Funktionen, die einen dieser
// Struct-Typen in Parametern/Rückgabewert verwenden, schlägt die automatisch generierte
// Deklaration deshalb fehl ("does not name a type"). Fix: eigene Prototypen HIER, direkt nach
// den Structs - Arduino erkennt vorhandene Prototypen und generiert dafür keinen eigenen mehr.
bool readOneWireResult(int pin, const OneWireSensorConfig &cfg, long &outValue);
bool parseI2CWriteEntry(const String &entry, I2CWriteSpec &out);
bool parseI2CReadEntry(const String &entry, I2CReadSpec &out);
bool parseI2CWriteList(const String &list, I2CWriteSpec specs[], uint8_t &countOut, uint8_t maxCount);
bool parseI2CReadList(const String &list, I2CReadSpec specs[], uint8_t &countOut, uint8_t maxCount);

I2CSensorConfig &i2cConfigForChannel(char channelName) {
  return (channelName == 'A') ? i2cConfigChannelA : i2cConfigChannelB;
}

I2SSensorConfig &i2sConfigForChannel(char channelName) {
  return (channelName == 'A') ? i2sConfigChannelA : i2sConfigChannelB;
}

OneWireSensorConfig &oneWireConfigForChannel(char channelName) {
  return (channelName == 'A') ? oneWireConfigChannelA : oneWireConfigChannelB;
}

/** Die drei Signal-Pins eines Kanal-Ports (Steckerposition 2, 3, 4), Rolle je nach Sensortyp
 *  (siehe {@link #configureChannelHardware}): I2C=SDA/SCL, HX711=DOUT/SCK, I2S=WS/BCLK/SD,
 *  Analog/Digital/1-Wire nutzen nur [0].
 *
 *  GPIO 0, 2, 5, 12, 15 sind ESP32-Strapping-Pins (Pegel beim Reset beeinflusst Boot-Modus/
 *  Flash-Spannung) - bewusst keiner davon hier verwendet, sonst droht ein Boot-Loop, sobald
 *  beim Reset bereits ein Sensor angeschlossen ist.
 *
 *  Kanal A: GPIO 32/33/35 (alle ADC1-fähig, nötig für TYPE_ANALOG - GPIO16/17 haben keine
 *  ADC-Hardware). Kanal B: GPIO 27/26/25 (ebenfalls ADC-fähig). */
const int PINS_CHANNEL_A[3] = {32, 33, 35};
const int PINS_CHANNEL_B[3] = {27, 26, 25};

/** Maximale Wartezeit in ms auf ein bereites HX711-Modul, bevor der Zyklus als Fehler gilt. */
const unsigned long HX711_TIMEOUT_MS = 100;

/** I2S-Abtastrate für das INMP441-Mikrofon. Wie viele Rohsamples je Zyklus für den Spitzenwert
 *  gelesen werden, ist NICHT fest, sondern richtet sich dynamisch nach der eingestellten
 *  Abtastrate (siehe {@link #microphoneReadSampleCount}) - eine feste Anzahl hätte bei hoher
 *  Abtastrate selbst zur Bremse werden können: 256 Samples brauchen bei 16kHz allein schon 16ms
 *  Lesezeit, was die erreichbare Rate unabhängig von der GUI-Einstellung auf ca. 62 Hz gedeckelt
 *  hätte. {@link #MIC_MIN_READ_SAMPLES} sorgt dafür, dass bei sehr hoher Abtastrate trotzdem noch
 *  mindestens ein paar Samples für den Spitzenwert bleiben, {@link #MIC_MAX_READ_SAMPLES} dafür,
 *  dass ein einzelner Lesevorgang bei niedriger Abtastrate nicht unnötig lange blockiert. */
const int MIC_SAMPLE_RATE_HZ = 16000;
const int MIC_MIN_READ_SAMPLES = 16;
const int MIC_MAX_READ_SAMPLES = 512;

/** Serielle Baudrate zum PC. War lange 115200 - das begrenzte das Frequenzspektrum auf
 *  ~4 Bilder/Sekunde, da 512 Bins pro Bild schon ein paar KB sind (siehe SPECTRUM_INTERVAL_MS).
 *  460800 ist auf allen gängigen USB-Seriell-Chips (CP210x, CH340, native USB-CDC) zuverlässig
 *  nutzbar und vervierfacht die Übertragungsgeschwindigkeit. Muss mit dem Baudrate-Wert in
 *  GUI.java (DeviceConnection.connect-Aufruf) und dem Vorgabewert in Terminal.java übereinstimmen -
 *  sonst verbindet sich nichts mehr. Bei zuverlässiger Verbindung kann versuchsweise auch
 *  921600 probiert werden (weitere Verdopplung), das ist aber chipabhängig weniger garantiert. */
const long BAUD_RATE = 460800;

/** Name, unter dem der ESP32 beim Pairing in der Bluetooth-Geräteliste des PCs auftaucht -
 *  landet je nach Betriebssystem/Treiber meist auch in der Beschreibung des daraus entstehenden
 *  virtuellen COM-Ports (z. B. Windows: "Standard Serial over Bluetooth link (COMx)" plus
 *  Gerätename in der Systemsteuerung; macOS/Linux oft direkt im Portnamen selbst) - siehe
 *  {@code getDescriptivePortName()}-Hinweis in DeviceConnection.java. Bewusst unterscheidbar vom
 *  reinen USB-Verbindungsnamen gewählt (der vom USB-Seriell-Chip vorgegeben wird, z. B. "CP2102
 *  USB to UART Bridge", und sich firmware-seitig nicht umbenennen lässt). */
const char *BT_DEVICE_NAME = "PhyLog Bluetooth";

BluetoothSerial SerialBT;

/** FFT-Größe für den Live-Frequenzspektrum-Modus (siehe {@link #captureAndSendSpectrum}) - eine
 *  Zweierpotenz, wie sie die iterative Radix-2-FFT ({@link #computeFFT}) voraussetzt. Ein reelles
 *  Signal liefert nur n/2 unabhängige Frequenz-Bins (die obere Hälfte ist bei reellem Eingang nur
 *  das gespiegelte Konjugat), 1024 Punkte ergeben also die gewünschten 512 nutzbaren Bins. */
const int SPECTRUM_FFT_SIZE = 1024;
const int SPECTRUM_OUTPUT_BINS = SPECTRUM_FFT_SIZE / 2;

/** Puffergröße für ein komplettes, im BSS-Bereich statisch gehaltenes Spektrum-Paket (siehe
 *  {@link #sendSpectrumPacket}): Header ("#SPEC,X,512,16000") plus je Bin bis zu 6 Byte
 *  (",-1234") plus etwas Marge - großzügig genug, ohne bei jedem Bild neu berechnet werden zu
 *  müssen. */
const size_t SPECTRUM_PACKET_BUF_SIZE = 32 + (size_t) SPECTRUM_OUTPUT_BINS * 7;

/** Mindestabstand zwischen zwei gesendeten Spektren. 512 Bins als kompakte Ganzzahlen sind
 *  trotzdem noch rund 2,5 KB pro Bild - bei BAUD_RATE=460800 (~46 KB/s) dauert allein die
 *  Übertragung davon schon knapp 55ms, die FFT selbst nur wenige ms. 60ms liegt knapp darüber
 *  (Sicherheitsspielraum für FFT-Zeit und Schleifen-Overhead) und ergibt damit ~16 Bilder/Sekunde -
 *  spürbar "live" statt der ~4 Bilder/Sekunde, die bei der alten Baudrate (115200) das Maximum
 *  waren. Absichtlich keine feste Wartezeit weit über dem physikalischen Minimum: Serial.print()
 *  blockiert ohnehin, sobald der Sende-Puffer voll ist, ein zu kleiner Wert würde also nicht zu
 *  einem Rückstau führen, sondern höchstens ungenutzt bleiben. */
const unsigned long SPECTRUM_INTERVAL_MS = 60;

/** Letzter Zeitpunkt eines gesendeten Spektrums je Kanal, um dessen Taktung ({@link #SPECTRUM_INTERVAL_MS})
 *  unabhängig von der (für normale Sensoren gedachten, ggf. viel höheren) Abtastrate zu halten. */
unsigned long lastSpectrumTimeMsA = 0;
unsigned long lastSpectrumTimeMsB = 0;

bool isStreaming = false;
unsigned long sampleIntervalMs = 50; // Standard: 20 Hz
unsigned long lastSampleTimeMs = 0;

/** Letzter Zeitpunkt einer gemeldeten Fehlermeldung je Kanal, um das serielle Log bei
 *  dauerhaften Fehlern nicht mit Meldungen zu fluten (siehe {@link #reportSensorError}). */
unsigned long lastErrorReportMsA = 0;
unsigned long lastErrorReportMsB = 0;

/** Anzahl aufeinanderfolgender I2C-Fehler je Kanal, um einen dauerhaft "hängenden" Bus (z. B.
 *  nach einem Wackelkontakt) automatisch neu zu initialisieren, statt nur endlos Fehler zu
 *  loggen (siehe {@link #noteI2CResult}). Wird bei jedem erfolgreichen I2C-Zugriff zurückgesetzt. */
int i2cFailStreakA = 0;
int i2cFailStreakB = 0;
const int I2C_FAIL_STREAK_RESET_THRESHOLD = 20;

// --- Host-Kommunikation (USB + Bluetooth gleichzeitig, siehe hostWrite/hostPrint) ---
//
// Ab hier läuft jede Ausgabe an den PC über hostWrite()/hostPrint() statt direkter Serial.*-
// Aufrufe: beide schreiben immer auf die USB-Verbindung und zusätzlich auf SerialBT, sofern
// dort gerade ein Client (die PhyLog-Software) verbunden ist - die Firmware unterscheidet nicht,
// über welchen Weg sie tatsächlich gerade "benutzt" wird, sondern schickt konsequent an beide.

/** Schreibt {@code len} Bytes ab {@code data} auf die USB-Verbindung sowie, falls verbunden, auf
 *  Bluetooth. {@code SerialBT.write()} ohne verbundenen Client kostet nur die interne
 *  hasClient()-Prüfung und blockiert nicht - das explizite Prüfen hier spart trotzdem den
 *  (unnötigen) Aufruf in den Bluetooth-Stack im reinen USB-Betrieb. */
void hostWrite(const char *data, size_t len) {
  Serial.write((const uint8_t *) data, len);
  if (SerialBT.hasClient()) {
    SerialBT.write((const uint8_t *) data, len);
  }
}

/** Wie {@link #hostWrite}, aber für einen nullterminierten String (spart an den Aufrufstellen das
 *  explizite Mitführen einer Länge für Konstanten wie {@code "#OK,START\n"}). */
void hostPrint(const char *s) {
  hostWrite(s, strlen(s));
}

/** Meldet einen fehlgeschlagenen Sensorzugriff auf einen Kanal, höchstens einmal pro Sekunde je
 *  Kanal, statt einen solchen Fehler stillschweigend zu verschlucken.
 *
 * @param channelName betroffener Kanal ('A' oder 'B')
 * @param errorTag    Fehlerart für das Log, z. B. "I2C", "HX711" oder "I2S"
 */
void reportSensorError(char channelName, const char *errorTag) {
  unsigned long &lastReport = (channelName == 'A') ? lastErrorReportMsA : lastErrorReportMsB;
  unsigned long now = millis();
  if (now - lastReport >= 1000) {
    lastReport = now;
    char buf[48];
    int len = snprintf(buf, sizeof(buf), "#ERR,%s,%c\n", errorTag, channelName);
    hostWrite(buf, len);
  }
}

/** @return den I2C-Bus, der physisch zu diesem Kanal gehört. */
TwoWire &busForChannel(char channelName) {
  return (channelName == 'A') ? Wire : Wire1;
}

/** @return den I2S-Port, der physisch zu diesem Kanal gehört (analog zu {@link #busForChannel}). */
i2s_port_t i2sPortForChannel(char channelName) {
  return (channelName == 'A') ? I2S_NUM_0 : I2S_NUM_1;
}

/** Channel-Handle des neuen I2S-Treibers (driver/i2s_std.h) je Kanal - {@code NULL}, solange
 *  kein Mikrofon konfiguriert ist. Der alte, mit driver/i2s.h installierte Legacy-Treiber
 *  kollidiert auf aktuellen arduino-esp32-Versionen mit dem für analogRead() genutzten
 *  ADC-Treiber ("driver_ng") und führt zu einem Absturz beim Start - der neue Treiber betrifft
 *  den ADC-Pfad nicht und ist deshalb mit TYPE_ANALOG auf dem jeweils anderen Kanal kombinierbar. */
i2s_chan_handle_t micHandleA = NULL;
i2s_chan_handle_t micHandleB = NULL;

/** @return das I2S-Channel-Handle, das physisch zu diesem Kanal gehört. */
i2s_chan_handle_t &micHandleForChannel(char channelName) {
  return (channelName == 'A') ? micHandleA : micHandleB;
}

/** Liest 1-4 Byte ab Register {@code reg} und setzt sie je nach {@code bigEndian} zu einem
 *  Rohwert zusammen (Länge/Reihenfolge kommen von der Software, siehe {@link I2CReadSpec}).
 *  Rückgabewert false bei Übertragungsfehler, outValue bleibt dann unverändert. outValue ist
 *  bewusst unsigned - Vorzeicheninterpretation macht die Java-Sensor-Klasse. */
bool readI2CRegisterN(TwoWire &bus, uint8_t addr, uint8_t reg, uint8_t len, bool bigEndian, uint32_t &outValue) {
  if (len == 0 || len > 4) return false;

  bus.beginTransmission(addr);
  bus.write(reg);
  if (bus.endTransmission(false) != 0) return false;

  if (bus.requestFrom((int)addr, (int)len) != len) return false;

  uint8_t bytes[4];
  for (int i = 0; i < len; i++) {
    bytes[i] = bus.read();
  }

  uint32_t value = 0;
  if (bigEndian) {
    for (int i = 0; i < len; i++) value = (value << 8) | bytes[i];
  } else {
    for (int i = len - 1; i >= 0; i--) value = (value << 8) | bytes[i];
  }
  outValue = value;
  return true;
}

/** Direkter Registerzugriff für reguläre Push-Pull-Pins (anders als {@link #owLow}/
 *  {@link #owRelease}, die bewusst nur nach LOW treiben und für HIGH in den hochohmigen
 *  Eingangszustand wechseln - passend für den Open-Drain-Charakter von 1-Wire, aber falsch für
 *  einen Pin wie HX711-SCK, der aktiv auf HIGH UND LOW getrieben werden muss). Setzt voraus, dass
 *  der Pin bereits per {@code pinMode(pin, OUTPUT)} als Ausgang konfiguriert ist (siehe
 *  {@link #configureChannelHardware}, Fall {@code TYPE_HX711}) - hier wird nur noch der
 *  Ausgangspegel selbst geschrieben, ohne bei jedem Aufruf erneut die Pin-Richtung anzufassen.
 *  Gleicher Grund wie bei {@link #owLow}: {@code digitalWrite()} kostet auf dem ESP32 mehrere
 *  hundert ns bis über 1µs (IO-MUX-Rekonfiguration, Bounds-Checks), direkter Registerzugriff nur
 *  wenige Taktzyklen - bei den hier genutzten 1µs-Zeitfenstern (siehe {@link #readHX711}) macht
 *  das den Unterschied zwischen einer sauberen und einer verzerrten Taktflanke. */
static inline void gpioWriteFast(int pin, bool high) {
  if (pin < 32) {
    if (high) GPIO.out_w1ts = (1U << pin);
    else      GPIO.out_w1tc = (1U << pin);
  } else {
    if (high) GPIO.out1_w1ts.val = (1U << (pin - 32));
    else      GPIO.out1_w1tc.val = (1U << (pin - 32));
  }
}

/** Liest den aktuellen Pegel von {@code pin} (0 oder 1) - inhaltlich identisch zu {@link #owRead},
 *  hier als eigener Name, damit {@link #readHX711} nicht von einer für 1-Wire benannten Funktion
 *  abhängt, obwohl beide Stellen rein technisch dasselbe Zustandsregister lesen. */
static inline int gpioReadFast(int pin) {
  if (pin < 32) {
    return (GPIO.in >> pin) & 0x1;
  } else {
    return (GPIO.in1.data >> (pin - 32)) & 0x1;
  }
}

/** Liest einen 24-Bit-Rohwert vom HX711 per Bit-Banging (eigenes DOUT/SCK-Protokoll, kein I2C).
 *  Wartet auf DOUT=LOW (Wert bereit); Timeout {@link #HX711_TIMEOUT_MS} -> Zyklus fehlgeschlagen.
 *  Die 24+1 Taktflanken laufen in {@code noInterrupts()}/{@code interrupts()} über
 *  {@link #gpioWriteFast}/{@link #gpioReadFast} (direkter Registerzugriff) statt
 *  {@code digitalWrite()}/{@code digitalRead()} - der HX711 schläft ein, wenn SCK länger als
 *  ~60µs HIGH bleibt, und sowohl HAL-Overhead als auch ein dazwischenfunkender Interrupt könnten
 *  das reißen. Die Schleife bleibt mit ~50µs klar darunter. Die 25. Taktflanke wählt Kanal A mit
 *  Gain 128 für den nächsten Zyklus (feste Standardkonfiguration dieser Firmware).
 *
 * @param doutPin  GPIO, an dem das Modul die Daten ausgibt
 * @param sckPin   GPIO, über den der Takt an das Modul gesendet wird
 * @param outValue Ziel für den auf 32 Bit vorzeichenrichtig erweiterten Rohwert
 * @return {@code true} bei Erfolg, {@code false} bei Timeout
 */
bool readHX711(int doutPin, int sckPin, long &outValue) {
  unsigned long waitStart = millis();
  while (digitalRead(doutPin) == HIGH) {
    if (millis() - waitStart > HX711_TIMEOUT_MS) return false;
  }

  long value = 0;
  noInterrupts();
  for (int i = 0; i < 24; i++) {
    gpioWriteFast(sckPin, true);
    delayMicroseconds(1);
    value = (value << 1) | gpioReadFast(doutPin);
    gpioWriteFast(sckPin, false);
    delayMicroseconds(1);
  }

  gpioWriteFast(sckPin, true); // 25. Flanke: Gain 128 / Kanal A für den nächsten Zyklus
  delayMicroseconds(1);
  gpioWriteFast(sckPin, false);
  delayMicroseconds(1);
  interrupts();

  if (value & 0x800000) { // 24-Bit-Zweierkomplement auf 32 Bit vorzeichenrichtig erweitern
    value |= 0xFF000000;
  }
  outValue = value;
  return true;
}

// --- Generisches 1-Wire, TYPE_ONEWIRE (aktuell nur DS18B20 als Sensor implementiert) ---
//
// Von Hand bit-gebangtes 1-Wire-Protokoll nach den Timing-Vorgaben aus dem DS18B20-Datenblatt -
// keine externe OneWire-Bibliothek, analog zur bereits manuell implementierten HX711-Anbindung
// oben. Alle drei Grundoperationen (Reset, Bit schreiben, Bit lesen) laufen mit kurzzeitig
// deaktivierten Interrupts: die engsten hier genutzten Zeitfenster liegen bei nur 1-2µs, leicht
// zu reißen z. B. durch die I2S-DMA-ISR des Mikrofons auf dem jeweils anderen Kanal.
//
// owLow()/owRelease()/owRead() ersetzen dafür pinMode()/digitalWrite()/digitalRead() durch
// direkten Zugriff auf die GPIO-Register: Die Arduino-HAL-Funktionen kosten auf dem ESP32
// (anders als auf AVR) jeweils mehrere hundert ns bis über 1µs (IO-MUX-Rekonfiguration,
// Bounds-Checks) - bei den hier genutzten 1-2µs-Zeitfenstern verschiebt allein das
// Umschalten von Pinrichtung/Pegel den eigentlichen Abtast-/Flankenzeitpunkt erheblich und
// zerstört damit praktisch jede Übertragung. Direkter
// Registerzugriff kostet dagegen nur wenige CPU-Taktzyklen. GPIO32-39 hängen an einem zweiten
// Register-Satz (GPIO.out1/enable1/in1 statt .../in), daher die Fallunterscheidung nach Pin 32.

/** Zieht {@code pin} aktiv auf LOW (Open-Drain-Charakter des 1-Wire-Busses: nur Treiben nach
 *  LOW, niemals aktiv nach HIGH - siehe {@link #owRelease}).
 *
 *  <p>ACHTUNG bei den GPIO32-39-Registern (Pin &ge; 32): Die Schreib-Register {@code out1_w1ts}/
 *  {@code out1_w1tc}/{@code enable1_w1ts}/{@code enable1_w1tc} sind Unions mit Feld {@code .val}
 *  - anders als das reine Zustandsregister {@link #owRead}s {@code in1}, das tatsächlich
 *  {@code .data} heißt. Eine frühere Version dieser Funktion griff hier fälschlich überall auf
 *  {@code .data} zu; betraf ausschließlich Pins &ge; 32 (auf Kanal A z. B. Pin 32 selbst) und
 *  äußerte sich wie ein dauerhaft feststehender Bus - Kanal B (Pins 27/26/25, alle &lt; 32) war
 *  nie betroffen.</p> */
static inline void owLow(int pin) {
  if (pin < 32) {
    GPIO.out_w1tc = (1U << pin);
    GPIO.enable_w1ts = (1U << pin);
  } else {
    GPIO.out1_w1tc.val = (1U << (pin - 32));
    GPIO.enable1_w1ts.val = (1U << (pin - 32));
  }
}

/** Gibt {@code pin} wieder als Eingang frei - der externe Pull-up zieht den Bus auf HIGH, kein
 *  aktives Treiben nach HIGH nötig (und für einen Open-Drain-Bus wie 1-Wire auch nicht zulässig,
 *  falls mehrere Teilnehmer gleichzeitig senden könnten). Zum Feldnamen-Hinweis siehe {@link #owLow}. */
static inline void owRelease(int pin) {
  if (pin < 32) {
    GPIO.enable_w1tc = (1U << pin);
  } else {
    GPIO.enable1_w1tc.val = (1U << (pin - 32));
  }
}

/** Liest den aktuellen Pegel von {@code pin} (0 oder 1). Nutzt bewusst {@code .data} (nicht
 *  {@code .val} wie die Schreib-Register in {@link #owLow}) - {@code in1} ist das reine
 *  Zustandsregister, dessen Payload-Bitfeld tatsächlich so heißt. */
static inline int owRead(int pin) {
  if (pin < 32) {
    return (GPIO.in >> pin) & 0x1;
  } else {
    return (GPIO.in1.data >> (pin - 32)) & 0x1;
  }
}

/** Sendet den 1-Wire-Reset-Puls und wertet den Presence-Puls des Sensors aus.
 *
 * @return {@code true}, wenn ein Gerät geantwortet hat.
 */
bool oneWireReset(int pin) {
  owLow(pin);
  delayMicroseconds(480);

  noInterrupts();
  owRelease(pin); // Bus loslassen - der externe Pull-up zieht ihn wieder auf HIGH
  delayMicroseconds(70);
  bool presence = (owRead(pin) == 0); // Gerät antwortet mit einem kurzen LOW-Puls
  interrupts();

  delayMicroseconds(410); // Rest des insgesamt >=480µs breiten Resetfensters abwarten
  return presence;
}

/** Schreibt ein einzelnes Bit per 1-Wire-Zeitschlitz (Länge des LOW-Pulses codiert 0/1). */
void oneWireWriteBit(int pin, uint8_t bitValue) {
  noInterrupts();
  owLow(pin);
  delayMicroseconds(bitValue ? 6 : 60);
  owRelease(pin);
  interrupts();
  delayMicroseconds(bitValue ? 64 : 10); // Zeitschlitz auf insgesamt >=70µs auffüllen
}

/** Liest ein einzelnes Bit per 1-Wire-Zeitschlitz: kurz selbst LOW ziehen, dann loslassen und
 *  innerhalb des vom Sensor ggf. verlängerten LOW-Fensters abtasten. */
uint8_t oneWireReadBit(int pin) {
  noInterrupts();
  owLow(pin);
  delayMicroseconds(2);
  owRelease(pin);
  delayMicroseconds(10);
  uint8_t bitValue = owRead(pin);
  interrupts();
  delayMicroseconds(50); // Zeitschlitz auf insgesamt >=60µs auffüllen
  return bitValue;
}

void oneWireWriteByte(int pin, uint8_t value) {
  for (int i = 0; i < 8; i++) {
    oneWireWriteBit(pin, value & 0x01);
    value >>= 1;
  }
}

uint8_t oneWireReadByte(int pin) {
  uint8_t value = 0;
  for (int i = 0; i < 8; i++) {
    value |= (oneWireReadBit(pin) << i);
  }
  return value;
}

/** Dallas/Maxim-CRC8 (Polynom x^8+x^5+x^4+1, reflektiert) über das Scratchpad, zur Absicherung
 *  gegen durch Störungen verfälschte 1-Wire-Übertragungen - anders als bei I2C (siehe
 *  {@link #readI2CRegisterN}) gibt es hier keine Hardware-Bestätigung auf Byte-Ebene. */
uint8_t oneWireCRC8(const uint8_t *data, uint8_t len) {
  uint8_t crc = 0;
  for (uint8_t i = 0; i < len; i++) {
    uint8_t inByte = data[i];
    for (uint8_t bit = 0; bit < 8; bit++) {
      uint8_t mix = (crc ^ inByte) & 0x01;
      crc >>= 1;
      if (mix) crc ^= 0x8C;
      inByte >>= 1;
    }
  }
  return crc;
}

/** Ob für den jeweiligen Kanal aktuell eine Konversion läuft, deren Ergebnis noch nicht
 *  abgeholt wurde - siehe {@link #sampleOneWire}. Bei einem Kanalwechsel weg von TYPE_ONEWIRE
 *  über {@link #releaseChannelHardware} zurückgesetzt, damit ein späteres erneutes Einschalten
 *  nicht versucht, das Ergebnis einer nie gestarteten (oder eines ganz anderen Sensors
 *  zugehörigen) Konversion zu lesen. */
bool oneWireConversionPendingA = false;
bool oneWireConversionPendingB = false;
unsigned long oneWireConversionStartMsA = 0;
unsigned long oneWireConversionStartMsB = 0;

/** Liest (ohne neue Konversion anzustoßen) das Ergebnis einer bereits abgeschlossenen 1-Wire-
 *  Konversion gemäß {@code cfg} und prüft optional die CRC8.
 * @return {@code true} bei Erfolg (Presence-Puls, plausible Länge, ggf. gültige CRC8) */
bool readOneWireResult(int pin, const OneWireSensorConfig &cfg, long &outValue) {
  if (cfg.readLen == 0 || cfg.readLen > 16) return false;
  if ((int) cfg.valueOffset + (int) cfg.valueLen > cfg.readLen) return false;

  if (!oneWireReset(pin)) return false;
  oneWireWriteByte(pin, 0xCC); // Skip ROM - setzt genau einen Sensor an diesem Pin voraus
  oneWireWriteByte(pin, cfg.readCmd);

  uint8_t buffer[16];
  for (int i = 0; i < cfg.readLen; i++) {
    buffer[i] = oneWireReadByte(pin);
  }

  if (cfg.checkCrc) {
    if (cfg.readLen < 2) return false;
    if (oneWireCRC8(buffer, cfg.readLen - 1) != buffer[cfg.readLen - 1]) return false;
  }

  uint32_t value = 0;
  if (cfg.littleEndian) {
    for (int i = cfg.valueLen - 1; i >= 0; i--) value = (value << 8) | buffer[cfg.valueOffset + i];
  } else {
    for (int i = 0; i < cfg.valueLen; i++) value = (value << 8) | buffer[cfg.valueOffset + i];
  }
  outValue = (long) value;
  return true;
}

/** Tastet einen 1-Wire-Sensor gemäß {@code cfg} nicht-blockierend ab: löst bei einer laufenden
 *  Konversion nur ab (kein {@code delay()}, das würde den anderen Kanal einfrieren), holt das
 *  Ergebnis erst, sobald {@code conversionDelayMs} vergangen ist, und startet dann sofort die
 *  nächste Konversion. Liefert deshalb nicht bei jedem Aufruf ein Datenpaket. */
void sampleOneWire(char channelName, int pin) {
  bool &pending = (channelName == 'A') ? oneWireConversionPendingA : oneWireConversionPendingB;
  unsigned long &startMs = (channelName == 'A') ? oneWireConversionStartMsA : oneWireConversionStartMsB;
  const OneWireSensorConfig &cfg = oneWireConfigForChannel(channelName);

  if (pending) {
    if (millis() - startMs < cfg.conversionDelayMs) return; // Konversion läuft noch

    long rawValue;
    bool ok = readOneWireResult(pin, cfg, rawValue);
    pending = false;

    if (ok) {
      sendDataPacket(channelName, cfg.slot, rawValue);
    } else {
      reportSensorError(channelName, "1WIRE");
    }
  }

  // Nächste Konversion sofort anstoßen, statt erst beim nächsten Aufruf.
  if (!oneWireReset(pin)) {
    reportSensorError(channelName, "1WIRE");
    return;
  }
  oneWireWriteByte(pin, 0xCC); // Skip ROM
  oneWireWriteByte(pin, cfg.convertCmd);
  startMs = millis();
  pending = true;
}

/** Schreibt die vom Host konfigurierte Init-Sequenz ({@link I2CSensorConfig}) auf den Bus.
 *  Meldet einen Fehler, falls ein Schreibvorgang fehlschlägt. */
void configureSensorOnBus(TwoWire &bus, char channelName) {
  const I2CSensorConfig &cfg = i2cConfigForChannel(channelName);
  bool ok = true;

  for (int i = 0; i < cfg.initWriteCount; i++) {
    const I2CWriteSpec &w = cfg.initWrites[i];
    bus.beginTransmission(cfg.address);
    bus.write(w.reg);
    for (int b = 0; b < w.dataLen; b++) {
      bus.write(w.data[b]);
    }
    ok &= (bus.endTransmission() == 0);
  }

  if (!ok) {
    reportSensorError(channelName, "I2C");
  }
}

/** Initialisiert den I2C-Bus eines Kanals neu (Bus schließen, kurz warten, neu starten und den
 *  Sensor mit der zuletzt vom Host gesendeten Konfiguration neu initialisieren). Wird nach
 *  mehreren I2C-Fehlern in Folge aufgerufen, um einen durch einen Wackelkontakt "hängen
 *  gebliebenen" Bus wieder freizubekommen, statt dass der Kanal bis zum nächsten manuellen Reset
 *  dauerhaft Fehler meldet. */
void resetI2CBus(char channelName) {
  TwoWire &bus = busForChannel(channelName);
  bus.end();
  delay(5);
  const int *pins = (channelName == 'A') ? PINS_CHANNEL_A : PINS_CHANNEL_B;
  bus.begin(pins[0], pins[1], 400000);
  configureSensorOnBus(bus, channelName);
}

/** Zählt aufeinanderfolgende I2C-Fehler je Kanal und stößt ab {@link #I2C_FAIL_STREAK_RESET_THRESHOLD}
 *  einen automatischen Bus-Reset an (siehe {@link #resetI2CBus}). Nach jedem I2C-Zugriff
 *  (erfolgreich oder nicht) aufzurufen. */
void noteI2CResult(char channelName, bool success) {
  int &streak = (channelName == 'A') ? i2cFailStreakA : i2cFailStreakB;
  if (success) {
    if (streak > 0) {
      streak = 0;
      resetI2CBus(channelName);
    }
    return;
  }
  streak++;
  if (streak >= I2C_FAIL_STREAK_RESET_THRESHOLD) {
    streak = 0;
    resetI2CBus(channelName);
  }
}

/** Startet den I2S-Kanal im Empfangsmodus für das INMP441 (Philips-I2S, mono, 32-Bit-Slot -
 *  das Modul liefert 24 gültige Datenbits linksbündig in einem 32-Bit-Wort) über den neuen
 *  I2S-Standardtreiber (siehe Kommentar bei {@link #micHandleA} zum Grund). */
void configureMicrophone(char channelName, const int pins[3]) {
  i2s_chan_handle_t &handle = micHandleForChannel(channelName);

  i2s_chan_config_t chanConfig = I2S_CHANNEL_DEFAULT_CONFIG(i2sPortForChannel(channelName), I2S_ROLE_MASTER);
  if (i2s_new_channel(&chanConfig, NULL, &handle) != ESP_OK) {
    reportSensorError(channelName, "I2S");
    return;
  }

  i2s_std_config_t stdConfig = {
      .clk_cfg = I2S_STD_CLK_DEFAULT_CONFIG(MIC_SAMPLE_RATE_HZ),
      .slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_32BIT, I2S_SLOT_MODE_MONO),
      .gpio_cfg = {
          .mclk = I2S_GPIO_UNUSED,
          .bclk = (gpio_num_t) pins[1],
          .ws   = (gpio_num_t) pins[0],
          .dout = I2S_GPIO_UNUSED,
          .din  = (gpio_num_t) pins[2],
          .invert_flags = {
              .mclk_inv = false,
              .bclk_inv = false,
              .ws_inv = false
          }
      }
  };

  if (i2s_channel_init_std_mode(handle, &stdConfig) != ESP_OK || i2s_channel_enable(handle) != ESP_OK) {
    reportSensorError(channelName, "I2S");
  }
}

/** Gibt die Hardware frei, die {@code oldType} auf diesem Kanal belegt hat, damit die drei
 *  gemeinsam genutzten Signal-Pins (siehe {@link #PINS_CHANNEL_A}) anschließend für einen
 *  anderen Sensortyp neu konfiguriert werden können. Für HX711/Analog/NONE ist nichts
 *  freizugeben - die neue Konfiguration überschreibt deren Pin-Modi einfach direkt. */
void releaseChannelHardware(char channelName, SensorType oldType) {
  if (oldType == TYPE_I2C) {
    busForChannel(channelName).end();
  } else if (oldType == TYPE_I2S) {
    i2s_chan_handle_t &handle = micHandleForChannel(channelName);
    if (handle != NULL) {
      i2s_channel_disable(handle);
      i2s_del_channel(handle);
      handle = NULL;
    }
  } else if (oldType == TYPE_ONEWIRE) {
    // Siehe Kommentar bei oneWireConversionPendingA/B: eine noch laufende Konversion wird beim
    // Wegschalten verworfen, statt ihr Ergebnis später fälschlich einem neuen Sensor zuzuordnen.
    (channelName == 'A' ? oneWireConversionPendingA : oneWireConversionPendingB) = false;
  }
}

/** Konfiguriert die drei Kanal-Pins für den neu gewählten Sensortyp (siehe Klassenkommentar zur
 *  Pin-Belegung). Vorher muss die alte Hardware über {@link #releaseChannelHardware}
 *  freigegeben worden sein. */
void configureChannelHardware(char channelName, SensorType newType, const int pins[3]) {
  switch (newType) {
    case TYPE_ANALOG:
      break; // analogRead() braucht keine explizite pinMode()
    case TYPE_I2C: {
      TwoWire &bus = busForChannel(channelName);
      bus.begin(pins[0], pins[1], 400000);
      configureSensorOnBus(bus, channelName);
      break;
    }
    case TYPE_HX711:
      pinMode(pins[0], INPUT);
      pinMode(pins[1], OUTPUT);
      digitalWrite(pins[1], LOW);
      break;
    case TYPE_DIGITAL:
      // Nur Pin 0 genutzt. Interner Pull-up ist redundant, falls das Modul einen eigenen hat,
      // schadet aber nicht - macht die Beschaltung robuster gegen Module ohne eigenen.
      pinMode(pins[0], INPUT_PULLUP);
      break;
    case TYPE_ONEWIRE:
      // Bus in Ruhestellung: oneWireReset()/-WriteBit()/-ReadBit() schalten pinMode() für die
      // eigentliche Kommunikation ohnehin bei jedem Zugriff selbst um (siehe dort) - hier nur
      // der definierte Ausgangszustand. Kein interner Pull-up wie bei TYPE_DIGITAL: der
      // 1-Wire-Bus braucht einen externen Pull-up nach 3,3V (typisch 4,7kΩ), der interne
      // ESP32-Pull-up ist dafür in der Praxis zu hochohmig (siehe Hardware-Hinweis in
      // DS18B20Sensor.java).
      pinMode(pins[0], INPUT);
      break;
    case TYPE_I2S:
      // Identische I2S-Hardware unabhängig vom Modus - ob pro Zyklus ein Einzelwert oder in
      // festem Intervall ein Spektrum verschickt wird, entscheidet nur noch
      // i2sConfigForChannel(channelName).spectrumMode (siehe sampleChannel()/loop()), nicht mehr
      // der Sensortyp selbst.
      configureMicrophone(channelName, pins);
      break;
    case TYPE_NONE:
    default:
      break;
  }
}

/** Parst eine Hex-Teilzeichenkette (ohne "0x"-Präfix), z. B. aus {@link #parseI2CSetPayload}. */
long parseHexToken(const String &s) {
  return strtol(s.c_str(), nullptr, 16);
}

/** Parst einen einzelnen Init-Write-Eintrag "reg:byte:byte:..." (alles hex) in {@code out}.
 *  @return false bei erkennbar kaputtem Format (kein ':' oder keine Datenbytes) */
bool parseI2CWriteEntry(const String &entry, I2CWriteSpec &out) {
  int firstColon = entry.indexOf(':');
  if (firstColon == -1) return false;

  out.reg = (uint8_t) parseHexToken(entry.substring(0, firstColon));
  out.dataLen = 0;

  int start = firstColon + 1;
  while (start <= (int) entry.length() && out.dataLen < 4) {
    int sep = entry.indexOf(':', start);
    String byteStr = (sep == -1) ? entry.substring(start) : entry.substring(start, sep);
    out.data[out.dataLen++] = (uint8_t) parseHexToken(byteStr);
    if (sep == -1) break;
    start = sep + 1;
  }
  return out.dataLen > 0;
}

/** Parst einen einzelnen Read-Eintrag "reg:len:B|L:slot" (reg hex, len/slot dezimal) in
 *  {@code out}. @return false bei erkennbar kaputtem Format oder ungültiger Länge (nicht 1-4) */
bool parseI2CReadEntry(const String &entry, I2CReadSpec &out) {
  int c1 = entry.indexOf(':');
  int c2 = (c1 == -1) ? -1 : entry.indexOf(':', c1 + 1);
  int c3 = (c2 == -1) ? -1 : entry.indexOf(':', c2 + 1);
  if (c1 == -1 || c2 == -1 || c3 == -1) return false;

  out.reg = (uint8_t) parseHexToken(entry.substring(0, c1));
  out.len = (uint8_t) entry.substring(c1 + 1, c2).toInt();
  out.bigEndian = entry.substring(c2 + 1, c3).equalsIgnoreCase("B");
  out.slot = (uint8_t) entry.substring(c3 + 1).toInt();

  return out.len >= 1 && out.len <= 4;
}

/** Zerlegt eine ';'-getrennte Liste von Init-Write-Einträgen in {@code specs} (siehe
 *  {@link #parseI2CWriteEntry}), bis zu {@code maxCount} Einträge. Leere Liste ist gültig (ein
 *  Sensor ohne Init-Sequenz). */
bool parseI2CWriteList(const String &list, I2CWriteSpec specs[], uint8_t &countOut, uint8_t maxCount) {
  countOut = 0;
  if (list.length() == 0) return true;

  int start = 0;
  while (start <= (int) list.length() && countOut < maxCount) {
    int sep = list.indexOf(';', start);
    String entry = (sep == -1) ? list.substring(start) : list.substring(start, sep);
    if (!parseI2CWriteEntry(entry, specs[countOut])) return false;
    countOut++;
    if (sep == -1) break;
    start = sep + 1;
  }
  return true;
}

/** Wie {@link #parseI2CWriteList}, aber für Read-Einträge (siehe {@link #parseI2CReadEntry}). */
bool parseI2CReadList(const String &list, I2CReadSpec specs[], uint8_t &countOut, uint8_t maxCount) {
  countOut = 0;
  if (list.length() == 0) return true;

  int start = 0;
  while (start <= (int) list.length() && countOut < maxCount) {
    int sep = list.indexOf(';', start);
    String entry = (sep == -1) ? list.substring(start) : list.substring(start, sep);
    if (!parseI2CReadEntry(entry, specs[countOut])) return false;
    countOut++;
    if (sep == -1) break;
    start = sep + 1;
  }
  return true;
}

/** Zerlegt das Payload eines "SET,<Kanal>,I2C,..."-Kommandos - alles nach "I2C," - in die
 *  generische I2C-Konfiguration des Kanals. Format: "<Adresse hex>,<Init-Writes>,<Reads>".
 *  Init-Writes: "-" oder ';'-getrennt "reg:byte:byte:..." (alles hex).
 *  Reads: ';'-getrennt "reg:len:B|L:slot" (reg hex, len/slot dezimal).
 *  Beispiel INA219: "40,0:39:9f;5:10:0,2:2:B:0;4:2:B:1"
 *  @return false bei erkennbar kaputtem Format */
bool parseI2CSetPayload(char channelName, const String &params) {
  I2CSensorConfig &cfg = i2cConfigForChannel(channelName);
  cfg = I2CSensorConfig(); // vorherige Konfiguration verwerfen

  int p1 = params.indexOf(',');
  if (p1 == -1) return false;
  int p2 = params.indexOf(',', p1 + 1);
  if (p2 == -1) return false;

  cfg.address = (uint8_t) parseHexToken(params.substring(0, p1));

  String initsStr = params.substring(p1 + 1, p2);
  if (initsStr == "-") initsStr = "";
  if (!parseI2CWriteList(initsStr, cfg.initWrites, cfg.initWriteCount, MAX_I2C_WRITES)) {
    return false;
  }

  String readsStr = params.substring(p2 + 1);
  if (!parseI2CReadList(readsStr, cfg.reads, cfg.readCount, MAX_I2C_READS)) {
    return false;
  }

  return cfg.readCount > 0;
}

/** Zerlegt das Payload eines "SET,<Kanal>,ONEWIRE,..."-Kommandos - alles nach "ONEWIRE," - in
 *  die generische {@link OneWireSensorConfig}. Format:
 *  "ConvertCmd(hex),DelayMs,ReadCmd(hex),ReadLen,ValueOffset,ValueLen,B|L,0|1,Slot".
 *  Beispiel DS18B20: "44,750,be,9,0,2,L,1,0"
 *  @return false bei erkennbar kaputtem Format */
bool parseOneWireSetPayload(char channelName, const String &params) {
  OneWireSensorConfig &cfg = oneWireConfigForChannel(channelName);
  cfg = OneWireSensorConfig(); // vorherige Konfiguration verwerfen

  const int fieldCount = 9;
  int fieldStart[fieldCount];
  int fieldEnd[fieldCount];
  int start = 0;
  for (int i = 0; i < fieldCount; i++) {
    int sep = (i == fieldCount - 1) ? params.length() : params.indexOf(',', start);
    if (sep == -1) return false;
    fieldStart[i] = start;
    fieldEnd[i] = sep;
    start = sep + 1;
  }

  cfg.convertCmd = (uint8_t) parseHexToken(params.substring(fieldStart[0], fieldEnd[0]));
  cfg.conversionDelayMs = (unsigned long) params.substring(fieldStart[1], fieldEnd[1]).toInt();
  cfg.readCmd = (uint8_t) parseHexToken(params.substring(fieldStart[2], fieldEnd[2]));
  cfg.readLen = (uint8_t) params.substring(fieldStart[3], fieldEnd[3]).toInt();
  cfg.valueOffset = (uint8_t) params.substring(fieldStart[4], fieldEnd[4]).toInt();
  cfg.valueLen = (uint8_t) params.substring(fieldStart[5], fieldEnd[5]).toInt();
  cfg.littleEndian = params.substring(fieldStart[6], fieldEnd[6]).equalsIgnoreCase("L");
  cfg.checkCrc = params.substring(fieldStart[7], fieldEnd[7]) == "1";
  cfg.slot = (uint8_t) params.substring(fieldStart[8], fieldEnd[8]).toInt();

  if (cfg.readLen == 0 || cfg.readLen > 16) return false;
  if ((int) cfg.valueOffset + (int) cfg.valueLen > cfg.readLen) return false;
  return true;
}

void processCommand(String command) {
  command.trim();

  if (command.equalsIgnoreCase("PING")) {
    hostPrint("#HELLO,PhyLog-ESP32,fw=9.0\n");
  } else if (command.equalsIgnoreCase("START")) {
    isStreaming = true;
    hostPrint("#OK,START\n");
  } else if (command.equalsIgnoreCase("STOP")) {
    isStreaming = false;
    hostPrint("#OK,STOP\n");
  } else if (command.startsWith("RATE,")) {
    long rateHz = command.substring(5).toInt();
    if (rateHz >= 1 && rateHz <= 1000) {
      sampleIntervalMs = 1000 / rateHz;
      char buf[32];
      int len = snprintf(buf, sizeof(buf), "#OK,RATE,%ld\n", rateHz);
      hostWrite(buf, len);
    }
  } else if (command.startsWith("SET,")) {
    // Vier Formate:
    //   SET,<Kanal>,<SensorTyp>                          z.B. SET,A,HX711 / SET,A,DIGITAL
    //   SET,<Kanal>,I2C,<Adresse>,<Init-Writes>,<Reads>   z.B. SET,A,I2C,40,0:39:9f;5:10:0,2:2:B:0;4:2:B:1
    //   SET,<Kanal>,I2S,RAW|SPEC                          z.B. SET,A,I2S,RAW
    //   SET,<Kanal>,ONEWIRE,<9 Felder, siehe parseOneWireSetPayload>  z.B. SET,A,ONEWIRE,44,750,be,9,0,2,L,1,0
    int firstComma = command.indexOf(',');
    int secondComma = command.indexOf(',', firstComma + 1);
    if (firstComma == -1 || secondComma == -1) return;

    char targetChannel = command.charAt(firstComma + 1);
    if (targetChannel != 'A' && targetChannel != 'B') return;

    String rest = command.substring(secondComma + 1); // "<SensorTyp>" bzw. "I2C,..."/"I2S,..."/"ONEWIRE,..."
    int thirdComma = rest.indexOf(',');
    String firstToken = (thirdComma == -1) ? rest : rest.substring(0, thirdComma);
    String extraParams = (thirdComma == -1) ? "" : rest.substring(thirdComma + 1);

    SensorType newType = TYPE_NONE;
    String ackPayload = firstToken;

    if (firstToken.equalsIgnoreCase("I2C")) {
      newType = TYPE_I2C;
      if (!parseI2CSetPayload(targetChannel, extraParams)) {
        // Kaputtes Payload: Kanal sicherheitshalber auf "kein Sensor" statt mit einer
        // halbfertigen Konfiguration weiterzumachen.
        newType = TYPE_NONE;
        reportSensorError(targetChannel, "I2CCFG");
      }
      ackPayload = rest;
    } else if (firstToken.equalsIgnoreCase("I2S")) {
      newType = TYPE_I2S;
      i2sConfigForChannel(targetChannel).spectrumMode = extraParams.equalsIgnoreCase("SPEC");
      ackPayload = rest;
    } else if (firstToken.equalsIgnoreCase("ONEWIRE")) {
      newType = TYPE_ONEWIRE;
      if (!parseOneWireSetPayload(targetChannel, extraParams)) {
        newType = TYPE_NONE;
        reportSensorError(targetChannel, "1WIRECFG");
      }
      ackPayload = rest;
    } else if (firstToken.equalsIgnoreCase("ANALOG")) newType = TYPE_ANALOG;
    else if (firstToken.equalsIgnoreCase("HX711")) newType = TYPE_HX711;
    else if (firstToken.equalsIgnoreCase("DIGITAL")) newType = TYPE_DIGITAL;

    if (targetChannel == 'A') {
      releaseChannelHardware('A', configChannelA);
      configChannelA = newType;
      configureChannelHardware('A', newType, PINS_CHANNEL_A);
    } else {
      releaseChannelHardware('B', configChannelB);
      configChannelB = newType;
      configureChannelHardware('B', newType, PINS_CHANNEL_B);
    }

    char buf[96];
    int len = snprintf(buf, sizeof(buf), "#OK,SET,%c,%s\n", targetChannel, ackPayload.c_str());
    hostWrite(buf, len);
  }
}

/** Liest ein Kommandozeichen aus einer der beiden Host-Schnittstellen (USB/Bluetooth) in
 *  {@code inputBuffer} und stößt bei Zeilenumbruch die Verarbeitung an. Ein gemeinsamer Puffer
 *  für beide Quellen - in der Praxis ist ohnehin nur eine Verbindung aktiv genutzt, ein
 *  gleichzeitig sendender Client auf beiden Wegen würde die Kommandos mischen. */
void feedCommandChar(String &inputBuffer, char incomingChar) {
  if (incomingChar == '\n' || incomingChar == '\r') {
    if (inputBuffer.length() > 0) {
      processCommand(inputBuffer);
      inputBuffer = "";
    }
  } else {
    inputBuffer += incomingChar;
  }
}

void handleSerialCommunication() {
  static String inputBuffer = "";
  while (Serial.available() > 0) {
    feedCommandChar(inputBuffer, (char) Serial.read());
  }
  while (SerialBT.available() > 0) {
    feedCommandChar(inputBuffer, (char) SerialBT.read());
  }
}

void sendDataPacket(char channel, int slot, long rawValue) {
  char buf[48];
  int len = snprintf(buf, sizeof(buf), "D,%lu,%c,%d,%ld\n", millis(), channel, slot, rawValue);
  hostWrite(buf, len);
}

/** Kehrt die Bit-Reihenfolge eines {@code bitCount}-Bit-Wertes um - Hilfsfunktion für die
 *  Bit-Reversal-Permutation am Anfang der FFT (siehe {@link #computeFFT}). */
uint16_t reverseBits(uint16_t value, int bitCount) {
  uint16_t result = 0;
  for (int i = 0; i < bitCount; i++) {
    result = (result << 1) | (value & 1);
    value >>= 1;
  }
  return result;
}

/**
 * Iterative, in-place Radix-2-Cooley-Tukey-FFT über {@code n} (Zweierpotenz) komplexe Werte,
 * ergebnis in {@code real}/{@code imag} zurückgeschrieben. Bewusst selbst geschrieben statt eine
 * FFT-Bibliothek einzubinden, da nur eine einzige feste Größe ({@link #SPECTRUM_FFT_SIZE})
 * benötigt wird und damit keine zusätzliche Abhängigkeit im Projekt nötig ist.
 */
void computeFFT(float *real, float *imag, int n) {
  int bitCount = 0;
  while ((1 << bitCount) < n) bitCount++;

  for (int i = 0; i < n; i++) {
    int j = reverseBits(i, bitCount);
    if (j > i) {
      float tempReal = real[i]; real[i] = real[j]; real[j] = tempReal;
      float tempImag = imag[i]; imag[i] = imag[j]; imag[j] = tempImag;
    }
  }

  // k-Schleife bewusst außen, start-Schleife innen (nicht umgekehrt wie in einer früheren
  // Version): wr/wi hängen nur von size und k ab, nicht von start - bei start außen wurden sie
  // für dieselbe (size,k)-Kombination bei jedem Block per cosf()/sinf() neu berechnet, obwohl sie
  // block-unabhängig identisch sind. Mit k außen sinkt die Zahl der Trig-Aufrufe pro FFT von
  // sum(n/2 je Stufe) auf sum(halfSize je Stufe) - bei SPECTRUM_FFT_SIZE=1024 von 5120 auf 1023,
  // also etwa Faktor 5 weniger cosf()/sinf() (auf dem ESP32 ohne Hardware-Trig-Einheit spürbar
  // teurer als die reine Butterfly-Arithmetik) bei den ~16 FFTs/Sekunde im Spektrum-Modus.
  for (int size = 2; size <= n; size *= 2) {
    int halfSize = size / 2;
    float angleStep = -2.0f * PI / size;
    for (int k = 0; k < halfSize; k++) {
      float angle = angleStep * k;
      float wr = cosf(angle), wi = sinf(angle);
      for (int start = 0; start < n; start += size) {
        int evenIdx = start + k;
        int oddIdx = evenIdx + halfSize;

        float oddReal = real[oddIdx] * wr - imag[oddIdx] * wi;
        float oddImag = real[oddIdx] * wi + imag[oddIdx] * wr;

        real[oddIdx] = real[evenIdx] - oddReal;
        imag[oddIdx] = imag[evenIdx] - oddImag;
        real[evenIdx] += oddReal;
        imag[evenIdx] += oddImag;
      }
    }
  }
}

/** Sendet ein zuvor über {@link #computeFFT} berechnetes Spektrum als ein Paket:
 *  {@code #SPEC,<Kanal>,<Bins>,<Abtastrate>,<mag_0>,<mag_1>,...}. Magnituden als dBFS·10,
 *  auf int gerundet - spart Bandbreite gegenüber Floats.
 *
 * <p>Baut das gesamte Paket in {@link #SPECTRUM_PACKET_BUF_SIZE} zusammen und verschickt es mit
 * einem einzigen {@code hostWrite()} statt vieler einzelner {@code Serial.print()}-Aufrufe - das
 * spart bei ~16 Bildern/Sekunde CPU-Zeit, die sonst für die Abtastung des anderen Kanals fehlt.
 * Der Puffer ist {@code static}, um denselben BSS-Speicher wiederzuverwenden statt ~3,6 KB auf
 * dem Stack zu allozieren.</p>
 *
 * <p>Die Abbruchbedingung {@code offset < SPECTRUM_PACKET_BUF_SIZE - 8} ist eine reine
 * Sicherheitsgrenze gegen Pufferüberlauf, bei aktueller Puffergröße/Bin-Zahl praktisch nie
 * erreicht.</p> */
void sendSpectrumPacket(char channelName, float *real, float *imag) {
  static const float FULL_SCALE = 8388607.0f; // 2^23 - 1, wie in der Software-Sensorklasse
  static char packetBuf[SPECTRUM_PACKET_BUF_SIZE];

  int offset = snprintf(packetBuf, SPECTRUM_PACKET_BUF_SIZE, "#SPEC,%c,%d,%d",
                         channelName, SPECTRUM_OUTPUT_BINS, MIC_SAMPLE_RATE_HZ);

  for (int i = 0; i < SPECTRUM_OUTPUT_BINS && offset < (int) SPECTRUM_PACKET_BUF_SIZE - 8; i++) {
    float magnitude = sqrtf(real[i] * real[i] + imag[i] * imag[i]) / SPECTRUM_FFT_SIZE;
    float amplitude = fmaxf(magnitude / FULL_SCALE, 1e-9f); // Division durch 0 im log10 vermeiden
    int dbTimes10 = (int) roundf(20.0f * log10f(amplitude) * 10.0f);
    offset += snprintf(packetBuf + offset, SPECTRUM_PACKET_BUF_SIZE - offset, ",%d", dbTimes10);
  }

  packetBuf[offset++] = '\n';
  hostWrite(packetBuf, offset);
}

/** Vorberechnetes Hann-Fenster für {@link #captureAndSendSpectrum} - identisch für jeden Frame,
 *  spart ~16.000 unnötige {@code cosf()}-Aufrufe/Sekunde gegenüber Neuberechnung je Frame. */
float hannWindow[SPECTRUM_FFT_SIZE];
bool hannWindowReady = false;

void ensureHannWindow() {
  if (hannWindowReady) return;
  for (int i = 0; i < SPECTRUM_FFT_SIZE; i++) {
    hannWindow[i] = 0.5f - 0.5f * cosf(2.0f * PI * i / (SPECTRUM_FFT_SIZE - 1));
  }
  hannWindowReady = true;
}

/** Nimmt {@link #SPECTRUM_FFT_SIZE} Samples vom Mikrofon des angegebenen Kanals auf, wendet ein
 *  Hann-Fenster an (reduziert den "Leckeffekt" durch den scharfen Rand des Ausschnitts, der sonst
 *  als zusätzliche, falsche Frequenzanteile im Spektrum erscheinen würde), berechnet per FFT das
 *  Amplitudenspektrum und verschickt es. Wird für Kanäle mit TYPE_I2S im Spektrum-Modus
 *  (siehe {@link I2SSensorConfig#spectrumMode}) aufgerufen. */
void captureAndSendSpectrum(char channelName) {
  i2s_chan_handle_t handle = micHandleForChannel(channelName);
  if (handle == NULL) {
    reportSensorError(channelName, "I2S");
    return;
  }

  static int32_t rawBuffer[SPECTRUM_FFT_SIZE];
  size_t bytesRead = 0;
  // SPECTRUM_FFT_SIZE Samples bei MIC_SAMPLE_RATE_HZ brauchen ~64ms; 100ms Timeout-Marge
  // begrenzt eine mögliche Verzögerung des anderen Kanals im Fehlerfall.
  esp_err_t err = i2s_channel_read(handle, rawBuffer, sizeof(rawBuffer), &bytesRead, pdMS_TO_TICKS(100));
  int sampleCount = bytesRead / sizeof(int32_t);
  if (err != ESP_OK || sampleCount < SPECTRUM_FFT_SIZE) {
    reportSensorError(channelName, "I2S");
    return;
  }

  // real/imag als "static" statt lokal: 2 * 1024 * 4 Byte wären auf dem Stack des Loop-Tasks
  // riskant knapp (Standard-Stackgröße bei Arduino-ESP32 8 KB) - im BSS-Bereich unkritisch.
  static float real[SPECTRUM_FFT_SIZE];
  static float imag[SPECTRUM_FFT_SIZE];

  ensureHannWindow();
  for (int i = 0; i < SPECTRUM_FFT_SIZE; i++) {
    int32_t sample = rawBuffer[i] >> 8; // 24 gültige Bits linksbündig, siehe sampleMicrophone
    real[i] = sample * hannWindow[i];
    imag[i] = 0;
  }

  computeFFT(real, imag, SPECTRUM_FFT_SIZE);
  sendSpectrumPacket(channelName, real, imag);
}

/** Bestimmt, wie viele I2S-Rohsamples {@link #sampleMicrophone} pro Aufruf liest: so viele, wie
 *  in ein Intervall bei der aktuell eingestellten Abtastrate ({@code sampleIntervalMs}) passen -
 *  mehr Samples ergeben einen über einen größeren Zeitraum gemittelten, "ruhigeren" Spitzenwert,
 *  weniger Samples einen unmittelbareren, aber verrauschteren. Nach unten/oben begrenzt auf
 *  {@link #MIC_MIN_READ_SAMPLES}/{@link #MIC_MAX_READ_SAMPLES}. */
int microphoneReadSampleCount() {
  long samplesPerInterval = ((long) MIC_SAMPLE_RATE_HZ * sampleIntervalMs) / 1000;
  return (int) constrain(samplesPerInterval, MIC_MIN_READ_SAMPLES, MIC_MAX_READ_SAMPLES);
}

/** Liest einen kurzen Block Rohsamples vom INMP441 und bildet daraus den Spitzenbetrag
 *  (Peak-Amplitude) - ein einzelner Wert pro Aufrufzyklus, genau wie bei allen anderen
 *  Sensortypen. Das hält das serielle Protokoll unverändert (ein Datenpaket pro Kanal und
 *  Intervall) - die hohe I2S-Abtastrate bleibt intern und wird nicht Sample für Sample über die
 *  serielle Verbindung geschickt, was bei dieser Baudrate ohnehin nicht möglich wäre. */
void sampleMicrophone(char channelName) {
  i2s_chan_handle_t handle = micHandleForChannel(channelName);
  if (handle == NULL) {
    reportSensorError(channelName, "I2S");
    return;
  }

  int samplesToRead = microphoneReadSampleCount();
  int32_t buffer[MIC_MAX_READ_SAMPLES];
  size_t bytesRead = 0;

  // Timeout knapp über der maximal benötigten Sammelzeit (MIC_MAX_READ_SAMPLES bei
  // MIC_SAMPLE_RATE_HZ = 32ms): ein blockierender Lesevorgang verzögert auch den anderen Kanal
  // (siehe loop()/sampleChannel), ein kleines Timeout begrenzt das im Fehlerfall.
  esp_err_t err = i2s_channel_read(handle, buffer, samplesToRead * sizeof(int32_t), &bytesRead, pdMS_TO_TICKS(20));
  if (err != ESP_OK || bytesRead == 0) {
    reportSensorError(channelName, "I2S");
    return;
  }

  int sampleCount = bytesRead / sizeof(int32_t);
  int32_t peak = 0;
  for (int i = 0; i < sampleCount; i++) {
    int32_t sample = buffer[i] >> 8; // 24 gültige Bits liegen linksbündig im 32-Bit-Wort
    int32_t magnitude = (sample < 0) ? -sample : sample;
    if (magnitude > peak) peak = magnitude;
  }

  sendDataPacket(channelName, 0, peak);
}

/** Tastet den konfigurierten Sensor eines Kanals ab. Bei einem Übertragungsfehler wird für das
 *  betroffene Register kein Datenpaket verschickt (siehe {@link #reportSensorError}), statt
 *  einen falschen 0-Wert zu senden. */
void sampleChannel(char channelName, SensorType type, const int pins[3]) {
  if (type == TYPE_ANALOG) {
    int analogVal = analogRead(pins[0]);
    sendDataPacket(channelName, 0, analogVal);
  } else if (type == TYPE_I2C) {
    // Register, Länge, Bytereihenfolge und Ziel-Slot kommen aus der vom Host per SET
    // übertragenen Konfiguration - die Firmware kennt kein konkretes Sensormodell.
    TwoWire &bus = busForChannel(channelName);
    const I2CSensorConfig &cfg = i2cConfigForChannel(channelName);
    bool allOk = (cfg.readCount > 0);

    for (int i = 0; i < cfg.readCount; i++) {
      const I2CReadSpec &r = cfg.reads[i];
      uint32_t rawValue;
      bool ok = readI2CRegisterN(bus, cfg.address, r.reg, r.len, r.bigEndian, rawValue);
      if (ok) {
        sendDataPacket(channelName, r.slot, (long) rawValue);
      } else {
        reportSensorError(channelName, "I2C");
      }
      allOk &= ok;
    }
    noteI2CResult(channelName, allOk);
  } else if (type == TYPE_HX711) {
    long rawWeight;
    if (readHX711(pins[0], pins[1], rawWeight)) {
      sendDataPacket(channelName, 0, rawWeight);
    } else {
      reportSensorError(channelName, "HX711");
    }
  } else if (type == TYPE_DIGITAL) {
    // Kein Übertragungsfehler möglich wie bei I2C/HX711 - digitalRead() liefert immer einen
    // Wert. Die Umrechnung "0/1 -> Magnetfeld ja/nein" (inkl. Invertierung, da das KY-003-Modul
    // active-low ist) übernimmt bewusst erst die Java-Seite (HallEffectSensor.decode), wie bei
    // allen anderen Sensoren auch - die Firmware kennt nur Rohwerte.
    int rawState = digitalRead(pins[0]);
    sendDataPacket(channelName, 0, rawState);
  } else if (type == TYPE_ONEWIRE) {
    sampleOneWire(channelName, pins[0]);
  } else if (type == TYPE_I2S) {
    if (i2sConfigForChannel(channelName).spectrumMode) {
      // Bewusst kein Aufruf hier: das Spektrum braucht eine eigene, von der normalen Abtastrate
      // unabhängige Taktung (SPECTRUM_INTERVAL_MS) und wird deshalb direkt in loop() behandelt.
    } else {
      sampleMicrophone(channelName);
    }
  }
}

void setup() {
  Serial.begin(BAUD_RATE);
  SerialBT.begin(BT_DEVICE_NAME);
  delay(200);

  // Bewusst KEINE Pin-/Bus-Initialisierung hier: welche Rolle die drei Kanal-Pins spielen,
  // hängt vom gewählten Sensortyp ab und wird erst bei SET über configureChannelHardware()
  // hergestellt - beide Kanäle starten unkonfiguriert bei TYPE_NONE.
  hostPrint("#HELLO,PhyLog-ESP32,fw=8.6\n");
}

void loop() {
  handleSerialCommunication();

  if (!isStreaming) return;

  unsigned long currentTimeMs = millis();
  if (currentTimeMs - lastSampleTimeMs >= sampleIntervalMs) {
    lastSampleTimeMs = currentTimeMs;
    sampleChannel('A', configChannelA, PINS_CHANNEL_A);
    sampleChannel('B', configChannelB, PINS_CHANNEL_B);
  }

  // Das Frequenzspektrum braucht eine eigene, von der (für normale Sensoren gedachten,
  // ggf. viel höheren) Abtastrate unabhängige Taktung - eine einzelne FFT dauert zwar nur
  // Millisekunden, aber 512 Bins pro Bild sind schon einige hundert Byte, die bei dieser Baudrate
  // nicht beliebig oft pro Sekunde übertragen werden können (siehe SPECTRUM_INTERVAL_MS).
  if (configChannelA == TYPE_I2S && i2sConfigChannelA.spectrumMode
      && currentTimeMs - lastSpectrumTimeMsA >= SPECTRUM_INTERVAL_MS) {
    lastSpectrumTimeMsA = currentTimeMs;
    captureAndSendSpectrum('A');
  }
  if (configChannelB == TYPE_I2S && i2sConfigChannelB.spectrumMode
      && currentTimeMs - lastSpectrumTimeMsB >= SPECTRUM_INTERVAL_MS) {
    lastSpectrumTimeMsB = currentTimeMs;
    captureAndSendSpectrum('B');
  }
}
