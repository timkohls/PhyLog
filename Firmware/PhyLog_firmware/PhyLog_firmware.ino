/*
 * PhyLog Firmware v9.2
 *
 * Steuert zwei unabhängige Messkanäle (A/B) über USB und Bluetooth. Ein Kanal bekommt seinen
 * Sensortyp per SET,<Kanal>,<Typ>[,<Konfiguration>] vom Host zugewiesen und startet immer bei
 * TYPE_NONE. Die Firmware kennt dabei keine konkreten Sensormodelle, nur generische Bus-Muster:
 *
 *   ANALOG    Pin2=Eingang                           keine Konfiguration
 *   DIGITAL   Pin2=Eingang                           keine Konfiguration
 *   I2C       Pin2=SDA, Pin3=SCL                     Adresse + Init-/Lese-Register, siehe parseI2CSetPayload
 *   I2S       Pin2=WS, Pin3=BCLK, Pin4=SD            Modus, Abtastrate, Slot, Bit-Shift, Null-Check, siehe parseI2SSetPayload
 *   ONEWIRE   Pin2=Datenleitung (ext. Pull-up 4,7kΩ) opt. Init-Writes + Konversions-/Lesekommando + Byte-Layout, siehe parseOneWireSetPayload
 *   HX711     Pin2=DOUT, Pin3=SCK                    keine Konfiguration (eigenes Protokoll)
 *
 * Ein neuer Sensor mit einer dieser Schnittstellen braucht deshalb kein Firmware-Update, nur eine
 * neue Java-Klasse (siehe I2CSensor.java/OneWireSensor.java/I2SSensor.java). Vorausgesetzt wird
 * dabei das jeweilige generische Muster - ein grundlegend anderes Protokoll bräuchte weiterhin ein Firmware-Update.
 *
 * Kanal A und B hängen an physisch getrennten Bussen (I2C: Wire/Wire1, I2S: Port 0/1), damit
 * beide gleichzeitig denselben Typ nutzen können.
 *
 * Host-Protokoll (zeilenbasiert, '\n'-terminiert, identisch über USB und Bluetooth):
 *   Host -> ESP32:  PING | START | STOP | RATE,<Hz> | SET,<Kanal>,<Typ>[,<Konfiguration>]
 *   ESP32 -> Host:  #HELLO,...              Antwort auf PING sowie einmalig beim Start
 *                   #OK,<Kommando>[,...]    Bestätigung
 *                   #ERR,<Tag>,<Kanal>      Sensor-/Konfigurationsfehler (gedrosselt, siehe reportSensorError)
 *                   D,<ms>,<Kanal>,<Slot>,<Rohwert>          ein Messwert (siehe sendDataPacket)
 *                   #SPEC,<Kanal>,<Bins>,<Rate>,<dB*10>,...   ein Frequenzspektrum (siehe sendSpectrumPacket)
 *
 * Aufbau dieser Datei:
 *   1. Konstanten    2. Typen/Structs    3. Laufzeitzustand    4. Host-Kommunikation
 *   5. Low-Level-GPIO    6. Sensortreiber (I2C, HX711, 1-Wire, I2S inkl. FFT)
 *   7. Kanal-Hardware (Freigabe/Konfiguration)    8. Parser für SET-Payloads
 *   9. Kommando-Verarbeitung    10. Abtast-Dispatcher    11. setup()/loop()
 *
 * Hardware-Notizen:
 * - Kanal-A-Pins: GPIO32/33/35 (ADC1-fähig, keine Strapping-Pins). Kanal B: GPIO27/26/25.
 *   Nicht ändern, ohne ADC-Tauglichkeit/Strapping-Pin-Eigenschaften der Ziel-GPIOs zu prüfen.
 * - HX711 und 1-Wire nutzen direkten GPIO-Registerzugriff statt digitalWrite()/digitalRead(),
 *   da deren IO-MUX-Overhead das µs-genaue Timing beider Protokolle sprengen würde.
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


// =====================================================================================
// 1. KONSTANTEN
// =====================================================================================

// --- Allgemein / Host-Verbindung ---

/** Begrüßungszeile, Antwort auf PING und einmalige Meldung beim Start. */
const char *HELLO_MESSAGE = "#HELLO,PhyLog-ESP32,fw=9.2\n";

/** Serielle Baudrate zum PC - muss mit GUI.java (DeviceConnection.connect) und Terminal.java
 *  übereinstimmen, sonst verbindet sich nichts mehr. */
const long BAUD_RATE = 460800;

/** Name in der Bluetooth-Geräteliste des PCs. */
const char *BT_DEVICE_NAME = "PhyLog Bluetooth";

/** Mindestabstand zwischen zwei an den Host gemeldeten Fehlern desselben Kanals, damit ein
 *  dauerhaft fehlschlagender Sensor den Host nicht mit #ERR-Zeilen flutet. */
const unsigned long ERROR_REPORT_MIN_INTERVAL_MS = 120;

// --- Pinbelegung ---

/** Die drei Signal-Pins eines Kanal-Ports, Rolle je nach Sensortyp (siehe
 *  configureChannelHardware): I2C=SDA/SCL, HX711=DOUT/SCK, I2S=WS/BCLK/SD, Analog/Digital/
 *  1-Wire nutzen nur [0]. GPIO 0/2/5/12/15 sind ESP32-Strapping-Pins und bewusst nicht belegt. */
const int PINS_CHANNEL_A[3] = {32, 33, 35};
const int PINS_CHANNEL_B[3] = {27, 26, 25};

// --- I2C ---

/** Anzahl aufeinanderfolgender I2C-Fehler je Kanal, ab der der Bus automatisch neu
 *  initialisiert wird (siehe noteI2CResult). */
const int I2C_FAIL_STREAK_RESET_THRESHOLD = 20;

// --- HX711 ---

/** Maximale Zeit in ms, die DOUT durchgehend HIGH bleiben darf, bevor ein HX711-Fehler gemeldet
 *  wird (siehe sampleHX711). */
const unsigned long HX711_TIMEOUT_MS = 1000;

// --- I2S-Mikrofon ---

/** Wie viele I2S-Rohsamples je Zyklus für den Spitzenwert gelesen werden, richtet sich dynamisch
 *  nach der konfigurierten Abtastrate (siehe i2sReadSampleCount), begrenzt auf diesen Bereich. */
const int MIC_MIN_READ_SAMPLES = 16;
const int MIC_MAX_READ_SAMPLES = 512;

/** Anzahl aufeinanderfolgender komplett nullwertiger I2S-Fenster, ab der erst von einer echten
 *  Diskonnektion statt einer legitimen kurzen Stille ausgegangen wird (siehe sampleI2SRaw/
 *  captureAndSendSpectrum). */
const int I2S_ZERO_STREAK_THRESHOLD = 5;

// --- Frequenzspektrum (I2S im Modus SPEC) ---

/** FFT-Größe für den Live-Frequenzspektrum-Modus - Zweierpotenz (Voraussetzung der iterativen
 *  Radix-2-FFT in computeFFT). Ein reelles Signal liefert nur n/2 unabhängige Frequenz-Bins. */
const int SPECTRUM_FFT_SIZE = 1024;
const int SPECTRUM_OUTPUT_BINS = SPECTRUM_FFT_SIZE / 2;

/** Puffergröße für ein komplettes Spektrum-Paket (Header + je Bin bis zu 6 Byte, siehe
 *  sendSpectrumPacket), großzügig genug ohne Neuberechnung pro Bild. */
const size_t SPECTRUM_PACKET_BUF_SIZE = 32 + (size_t) SPECTRUM_OUTPUT_BINS * 7;

/** Mindestabstand zwischen zwei gesendeten Spektren (~16 Bilder/Sekunde bei BAUD_RATE=460800). */
const unsigned long SPECTRUM_INTERVAL_MS = 60;


// =====================================================================================
// 2. TYPEN UND STRUCTS
// =====================================================================================
// Alle Typen stehen bewusst vor jeder Funktion/jedem Prototyp: Die Arduino-IDE fügt
// automatisch generierte Prototypen vor den ersten Funktionen ein, und die müssen die hier
// definierten Typen bereits kennen.

/** Sensortyp eines Kanals. Die Zahlenwerte werden nicht übertragen (das Protokoll nutzt
 *  Klartext-Typnamen), die Lücken bei 3 und 6 haben daher keine Funktion. */
enum SensorType {
  TYPE_NONE = 0,
  TYPE_ANALOG = 1,
  TYPE_I2C = 2,
  TYPE_HX711 = 4,
  TYPE_I2S = 5,
  TYPE_DIGITAL = 7,
  TYPE_ONEWIRE = 8
};

// --- I2C ---

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

/** Vom Host per SET-Kommando übertragene I2C-Sensorkonfiguration eines Kanals. */
struct I2CSensorConfig {
  uint8_t address = 0;
  I2CWriteSpec initWrites[MAX_I2C_WRITES];
  uint8_t initWriteCount = 0;
  I2CReadSpec reads[MAX_I2C_READS];
  uint8_t readCount = 0;
};

// --- I2S ---

/** Vom Host per SET-Kommando übertragene I2S-Sensorkonfiguration eines Kanals. */
struct I2SSensorConfig {
  bool spectrumMode = false;
  uint32_t sampleRateHz = 16000;
  bool selectRightSlot = false;  // Slot-Auswahl je nach SEL-Pin-Verdrahtung des Moduls
  uint8_t shiftBits = 8;         // Shift zur Extraktion der gültigen Bits, siehe parseI2SSetPayload
  bool zeroIsError = true;       // durchgängiger Nullwert = Verkabelungsfehler
};

// --- 1-Wire ---

/** Ein einzelner Kommando-Schreibvorgang der 1-Wire-Init-Sequenz (z.B. "Write Scratchpad" beim
 *  DS18B20 zur Auflösungseinstellung). Jeder Eintrag bekommt vor dem Senden von {@code command}
 *  und {@code data} eine eigene Reset+Skip-ROM-Sequenz vorangestellt, siehe configureOneWireSensor. */
struct OneWireWriteSpec {
  uint8_t command = 0;
  uint8_t data[4] = {0, 0, 0, 0};
  uint8_t dataLen = 0;
};

const uint8_t MAX_ONEWIRE_WRITES = 2;

/** Generische 1-Wire-Sensorbeschreibung. "Skip ROM" (0xCC) nimmt die Firmware selbst an -
 *  unterstützt wird nur ein Sensor pro Bus. */
struct OneWireSensorConfig {
  OneWireWriteSpec initWrites[MAX_ONEWIRE_WRITES];
  uint8_t initWriteCount = 0;
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

// --- Prototypen für Funktionen mit Struct-Parametern/-Rückgabewerten ---
// Arduino generiert für alle unten definierten Funktionen automatisch Prototypen, noch bevor
// die obigen Structs bekannt sind - das schlägt für Funktionen mit Struct-Typen in der
// Signatur fehl. Fix: eigene Prototypen hier, direkt nach den Structs (die Arduino-IDE
// erzeugt für bereits deklarierte Funktionen keinen zweiten).
I2CSensorConfig &i2cConfigForChannel(char channelName);
I2SSensorConfig &i2sConfigForChannel(char channelName);
OneWireSensorConfig &oneWireConfigForChannel(char channelName);
bool readOneWireResult(int pin, const OneWireSensorConfig &cfg, long &outValue);
bool parseI2CWriteEntry(const String &entry, I2CWriteSpec &out);
bool parseI2CReadEntry(const String &entry, I2CReadSpec &out);
bool parseI2CWriteList(const String &list, I2CWriteSpec specs[], uint8_t &countOut, uint8_t maxCount);
bool parseI2CReadList(const String &list, I2CReadSpec specs[], uint8_t &countOut, uint8_t maxCount);
bool parseI2SSetPayload(char channelName, const String &params);
bool parseOneWireWriteEntry(const String &entry, OneWireWriteSpec &out);
bool parseOneWireWriteList(const String &list, OneWireWriteSpec specs[], uint8_t &countOut, uint8_t maxCount);


// =====================================================================================
// 3. LAUFZEITZUSTAND
// =====================================================================================

BluetoothSerial SerialBT;

// --- Streaming und Abtasttakt ---

bool isStreaming = false;
unsigned long sampleIntervalMs = 50; // Standard: 20 Hz
unsigned long lastSampleTimeMs = 0;

// --- Sensortyp und Konfiguration je Kanal ---

SensorType configChannelA = TYPE_NONE;
SensorType configChannelB = TYPE_NONE;

I2CSensorConfig i2cConfigChannelA;
I2CSensorConfig i2cConfigChannelB;

I2SSensorConfig i2sConfigChannelA;
I2SSensorConfig i2sConfigChannelB;

OneWireSensorConfig oneWireConfigChannelA;
OneWireSensorConfig oneWireConfigChannelB;

// --- Fehlerdrosselung (siehe reportSensorError) ---

/** Letzter Zeitpunkt einer gemeldeten Fehlermeldung je Kanal. */
unsigned long lastErrorReportMsA = 0;
unsigned long lastErrorReportMsB = 0;

// --- I2C (siehe noteI2CResult) ---

/** Aufeinanderfolgende I2C-Fehler je Kanal. */
int i2cFailStreakA = 0;
int i2cFailStreakB = 0;

// --- HX711 (siehe sampleHX711) ---

/** Ob DOUT aktuell durchgehend HIGH ist (noch kein neuer Wert bereit) und seit wann - Basis für
 *  den Timeout in sampleHX711. Bei Kanalwechsel über releaseChannelHardware zurückgesetzt. */
bool hx711WaitingA = false;
bool hx711WaitingB = false;
unsigned long hx711WaitStartMsA = 0;
unsigned long hx711WaitStartMsB = 0;

// --- 1-Wire (siehe sampleOneWire) ---

/** Ob für den Kanal aktuell eine Konversion läuft, deren Ergebnis noch nicht abgeholt wurde,
 *  und wann sie gestartet wurde. Bei Kanalwechsel über releaseChannelHardware zurückgesetzt. */
bool oneWireConversionPendingA = false;
bool oneWireConversionPendingB = false;
unsigned long oneWireConversionStartMsA = 0;
unsigned long oneWireConversionStartMsB = 0;

// --- I2S (siehe configureI2S, sampleI2SRaw, captureAndSendSpectrum) ---

i2s_chan_handle_t micHandleA = NULL;
i2s_chan_handle_t micHandleB = NULL;

/** Aufeinanderfolgende komplett nullwertige I2S-Fenster je Kanal, siehe I2S_ZERO_STREAK_THRESHOLD. */
int i2sZeroStreakA = 0;
int i2sZeroStreakB = 0;

/** Zeitpunkt des zuletzt gesendeten Spektrums je Kanal. */
unsigned long lastSpectrumTimeMsA = 0;
unsigned long lastSpectrumTimeMsB = 0;

/** Vorberechnetes Hann-Fenster für captureAndSendSpectrum - identisch für jeden Frame, spart
 *  Neuberechnung je Frame. */
float hannWindow[SPECTRUM_FFT_SIZE];
bool hannWindowReady = false;


// =====================================================================================
// 4. HOST-KOMMUNIKATION UND KANAL-ZUORDNUNG
// =====================================================================================

/** hostWrite()/hostPrint() schreiben immer auf USB und zusätzlich auf Bluetooth, sofern dort
 *  ein Client verbunden ist. */
void hostWrite(const char *data, size_t len) {
  Serial.write((const uint8_t *) data, len);
  if (SerialBT.hasClient()) {
    SerialBT.write((const uint8_t *) data, len);
  }
}

void hostPrint(const char *s) {
  hostWrite(s, strlen(s));
}

/** Verschickt einen Messwert als Zeile {@code D,<millis>,<Kanal>,<Slot>,<Rohwert>}. Der Slot
 *  unterscheidet mehrere Werte eines Sensors (z.B. Strom/Spannung beim INA219). */
void sendDataPacket(char channel, int slot, long rawValue) {
  char buf[48];
  int len = snprintf(buf, sizeof(buf), "D,%lu,%c,%d,%ld\n", millis(), channel, slot, rawValue);
  hostWrite(buf, len);
}

/** Meldet einen fehlgeschlagenen Sensorzugriff als {@code #ERR,<Tag>,<Kanal>} (gedrosselt auf
 *  ERROR_REPORT_MIN_INTERVAL_MS je Kanal). */
void reportSensorError(char channelName, const char *errorTag) {
  unsigned long &lastReport = (channelName == 'A') ? lastErrorReportMsA : lastErrorReportMsB;
  unsigned long now = millis();
  if (now - lastReport >= ERROR_REPORT_MIN_INTERVAL_MS) {
    lastReport = now;
    char buf[48];
    int len = snprintf(buf, sizeof(buf), "#ERR,%s,%c\n", errorTag, channelName);
    hostWrite(buf, len);
  }
}

// --- Zuordnung Kanalname -> physische Ressource ---

I2CSensorConfig &i2cConfigForChannel(char channelName) {
  return (channelName == 'A') ? i2cConfigChannelA : i2cConfigChannelB;
}

I2SSensorConfig &i2sConfigForChannel(char channelName) {
  return (channelName == 'A') ? i2sConfigChannelA : i2sConfigChannelB;
}

OneWireSensorConfig &oneWireConfigForChannel(char channelName) {
  return (channelName == 'A') ? oneWireConfigChannelA : oneWireConfigChannelB;
}

/** @return den I2C-Bus, der physisch zu diesem Kanal gehört. */
TwoWire &busForChannel(char channelName) {
  return (channelName == 'A') ? Wire : Wire1;
}

/** @return den I2S-Port, der physisch zu diesem Kanal gehört. */
i2s_port_t i2sPortForChannel(char channelName) {
  return (channelName == 'A') ? I2S_NUM_0 : I2S_NUM_1;
}

/** @return das I2S-Channel-Handle, das physisch zu diesem Kanal gehört. */
i2s_chan_handle_t &micHandleForChannel(char channelName) {
  return (channelName == 'A') ? micHandleA : micHandleB;
}


// =====================================================================================
// 5. LOW-LEVEL-GPIO (direkter Registerzugriff)
// =====================================================================================
// GPIO32-39 hängen beim ESP32 an einem zweiten Register-Satz (out1/enable1/in1), daher die
// Fallunterscheidung "pin < 32" in allen Helfern.

/** Setzt {@code pin} direkt über das Register auf HIGH/LOW - kostet nur wenige Taktzyklen statt
 *  mehrerer hundert ns (IO-MUX-Overhead von digitalWrite()), was bei den µs-genauen HX711-
 *  Zeitfenstern zählt. Setzt voraus, dass der Pin bereits per pinMode(pin, OUTPUT) konfiguriert
 *  ist. */
static inline void gpioWriteFast(int pin, bool high) {
  if (pin < 32) {
    if (high) GPIO.out_w1ts = (1U << pin);
    else      GPIO.out_w1tc = (1U << pin);
  } else {
    if (high) GPIO.out1_w1ts.val = (1U << (pin - 32));
    else      GPIO.out1_w1tc.val = (1U << (pin - 32));
  }
}

/** Liest den aktuellen Pegel von {@code pin} (0 oder 1). */
static inline int gpioReadFast(int pin) {
  if (pin < 32) {
    return (GPIO.in >> pin) & 0x1;
  } else {
    return (GPIO.in1.data >> (pin - 32)) & 0x1;
  }
}

/** Zieht {@code pin} aktiv auf LOW (Open-Drain-Charakter von 1-Wire: nur Treiben nach LOW,
 *  niemals aktiv nach HIGH - siehe owRelease). */
static inline void owLow(int pin) {
  if (pin < 32) {
    GPIO.out_w1tc = (1U << pin);
    GPIO.enable_w1ts = (1U << pin);
  } else {
    GPIO.out1_w1tc.val = (1U << (pin - 32));
    GPIO.enable1_w1ts.val = (1U << (pin - 32));
  }
}

/** Gibt {@code pin} wieder als Eingang frei - der externe Pull-up zieht den Bus auf HIGH. */
static inline void owRelease(int pin) {
  if (pin < 32) {
    GPIO.enable_w1tc = (1U << pin);
  } else {
    GPIO.enable1_w1tc.val = (1U << (pin - 32));
  }
}

/** Liest den aktuellen Pegel von {@code pin} (0 oder 1). */
static inline int owRead(int pin) {
  if (pin < 32) {
    return (GPIO.in >> pin) & 0x1;
  } else {
    return (GPIO.in1.data >> (pin - 32)) & 0x1;
  }
}


// =====================================================================================
// 6a. SENSORTREIBER: I2C
// =====================================================================================

/** Liest 1-4 Byte ab Register {@code reg} und setzt sie je nach {@code bigEndian} zu einem
 *  Rohwert zusammen. Vorzeicheninterpretation macht die Java-Sensor-Klasse. */
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

/** Schreibt die vom Host konfigurierte Init-Sequenz auf den Bus. */
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

/** Initialisiert den I2C-Bus eines Kanals neu und schreibt die Init-Sequenz erneut (nach
 *  Fehlern, um einen "hängen gebliebenen" Bus bzw. einen zwischenzeitlich stromlosen Sensor
 *  wieder in einen definierten Zustand zu bringen). */
void resetI2CBus(char channelName) {
  TwoWire &bus = busForChannel(channelName);
  bus.end();
  delay(5);
  const int *pins = (channelName == 'A') ? PINS_CHANNEL_A : PINS_CHANNEL_B;
  bus.begin(pins[0], pins[1], 400000);
  configureSensorOnBus(bus, channelName);
}

/** Wertet das Ergebnis eines kompletten I2C-Abtastzyklus aus. Nach jedem I2C-Zugriff
 *  (erfolgreich oder nicht) aufzurufen. Der Bus wird über resetI2CBus neu initialisiert
 *  - nach I2C_FAIL_STREAK_RESET_THRESHOLD Fehlern in Folge, sowie
 *  - beim ersten erfolgreichen Zyklus nach mindestens einem Fehler (Wiederanlauf: ein
 *    zwischenzeitlich abgesteckter Sensor hat seine Init-Register verloren). */
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


// =====================================================================================
// 6b. SENSORTREIBER: HX711
// =====================================================================================

/** Liest einen 24-Bit-Rohwert per Bit-Banging (eigenes DOUT/SCK-Protokoll, kein I2C) und
 *  verschickt ihn.
 *
 *  Nicht-blockierend: Ist DOUT noch HIGH (kein neuer Wert bereit), kehrt die Funktion sofort
 *  zurück und sendet nichts. Ein blockierendes Warten würde sonst auch die Abtastung des anderen
 *  Kanals lahmlegen und unregelmäßige Zeitabstände erzeugen, sobald die eingestellte Abtastrate
 *  über der tatsächlichen Ausgaberate des Chips liegt - was praktisch immer der Fall ist. Anders als bei
 *  1-Wire gibt es beim HX711 keinen "Konversion starten"-Schritt: der Chip misst durchgehend im
 *  Hintergrund und zieht DOUT von selbst auf LOW, sobald ein Ergebnis bereitsteht.
 *
 *  Ein Fehler wird erst gemeldet, wenn DOUT durchgehend länger als HX711_TIMEOUT_MS auf HIGH
 *  bleibt (Kabel ab/Modul ohne Strom), nicht schon bei jedem einzelnen "noch nicht bereit".
 *
 *  Die 24+1 Taktflanken laufen ohne Interrupts (der HX711 schläft ein, wenn SCK länger als
 *  ~60µs HIGH bleibt). Die 25. Taktflanke wählt Kanal A mit Gain 128 für den nächsten Zyklus. */
void sampleHX711(char channelName, int doutPin, int sckPin) {
  bool &waiting = (channelName == 'A') ? hx711WaitingA : hx711WaitingB;
  unsigned long &waitStart = (channelName == 'A') ? hx711WaitStartMsA : hx711WaitStartMsB;

  if (digitalRead(doutPin) == HIGH) {
    if (!waiting) {
      waiting = true;
      waitStart = millis();
    } else if (millis() - waitStart > HX711_TIMEOUT_MS) {
      reportSensorError(channelName, "HX711");
    }
    return;
  }
  waiting = false;

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
  sendDataPacket(channelName, 0, value);
}


// =====================================================================================
// 6c. SENSORTREIBER: GENERISCHES 1-WIRE (TYPE_ONEWIRE)
// =====================================================================================
// Aktuell ist nur der DS18B20 als Sensor implementiert. Das 1-Wire-Protokoll ist von Hand
// bit-gebangt, nach den Timing-Vorgaben aus dem DS18B20-Datenblatt. Alle Grundoperationen
// laufen ohne Interrupts, da die engsten Zeitfenster nur 1-2µs betragen - direkter GPIO-
// Registerzugriff (owLow/owRelease/owRead) aus demselben Grund wie beim HX711.

/** Sendet den 1-Wire-Reset-Puls und wertet den Presence-Puls des Sensors aus.
 * @return {@code true}, wenn ein Gerät geantwortet hat.
 */
bool oneWireReset(int pin) {
  owLow(pin);
  delayMicroseconds(480);

  noInterrupts();
  owRelease(pin);
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

/** Liest ein einzelnes Bit per 1-Wire-Zeitschlitz. */
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

/** Schreibt ein Byte, niederwertigstes Bit zuerst. */
void oneWireWriteByte(int pin, uint8_t value) {
  for (int i = 0; i < 8; i++) {
    oneWireWriteBit(pin, value & 0x01);
    value >>= 1;
  }
}

/** Liest ein Byte, niederwertigstes Bit zuerst. */
uint8_t oneWireReadByte(int pin) {
  uint8_t value = 0;
  for (int i = 0; i < 8; i++) {
    value |= (oneWireReadBit(pin) << i);
  }
  return value;
}

/** Dallas/Maxim-CRC8 (Polynom x^8+x^5+x^4+1, reflektiert) zur Absicherung gegen durch Störungen
 *  verfälschte 1-Wire-Übertragungen. */
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

/** Schreibt die vom Host konfigurierte 1-Wire-Init-Sequenz einmalig beim Umschalten auf diesen
 *  Sensor (z.B. "Write Scratchpad" beim DS18B20 zur Auflösungseinstellung, siehe
 *  OneWireWriteSpec) - analog zu configureSensorOnBus bei I2C. Jeder Init-Write bekommt eine
 *  eigene Reset+Skip-ROM-Sequenz vorangestellt, da nach einem 1-Wire-Kommandobyte alle folgenden
 *  Bits als dessen Daten interpretiert werden. Ohne konfigurierte Init-Writes (leere Liste, der
 *  Normalfall für die meisten 1-Wire-Sensoren) tut diese Funktion nichts. */
void configureOneWireSensor(char channelName, int pin) {
  const OneWireSensorConfig &cfg = oneWireConfigForChannel(channelName);
  bool ok = true;

  for (int i = 0; i < cfg.initWriteCount; i++) {
    const OneWireWriteSpec &w = cfg.initWrites[i];
    if (!oneWireReset(pin)) {
      ok = false;
      break;
    }
    oneWireWriteByte(pin, 0xCC); // Skip ROM
    oneWireWriteByte(pin, w.command);
    for (int b = 0; b < w.dataLen; b++) {
      oneWireWriteByte(pin, w.data[b]);
    }
  }

  if (!ok) {
    reportSensorError(channelName, "1WIRE");
  }
}

/** Liest (ohne neue Konversion anzustoßen) das Ergebnis einer bereits abgeschlossenen 1-Wire-
 *  Konversion gemäß {@code cfg} und prüft optional die CRC8.
 * @return {@code true} bei Erfolg */
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

/** Tastet einen 1-Wire-Sensor gemäß {@code cfg} nicht-blockierend ab: löst bei laufender
 *  Konversion nur ab (kein delay(), das würde den anderen Kanal einfrieren), holt das Ergebnis
 *  sobald conversionDelayMs vergangen ist und startet sofort die nächste Konversion. Liefert
 *  deshalb nicht bei jedem Aufruf ein Datenpaket. */
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

  if (!oneWireReset(pin)) {
    reportSensorError(channelName, "1WIRE");
    return;
  }
  oneWireWriteByte(pin, 0xCC); // Skip ROM
  oneWireWriteByte(pin, cfg.convertCmd);
  startMs = millis();
  pending = true;
}


// =====================================================================================
// 6d. SENSORTREIBER: I2S (Einzelwert und Frequenzspektrum)
// =====================================================================================

/** Startet den I2S-Kanal im Empfangsmodus über den neuen I2S-Standardtreiber. Abtastrate und Slot-Auswahl 
 *  kommen aus der vom Host übertragenen I2SSensorConfig - die Firmware kennt kein konkretes I2S-Sensormodell. */
void configureI2S(char channelName, const int pins[3]) {
  i2s_chan_handle_t &handle = micHandleForChannel(channelName);
  const I2SSensorConfig &cfg = i2sConfigForChannel(channelName);

  i2s_chan_config_t chanConfig = I2S_CHANNEL_DEFAULT_CONFIG(i2sPortForChannel(channelName), I2S_ROLE_MASTER);
  if (i2s_new_channel(&chanConfig, NULL, &handle) != ESP_OK) {
    reportSensorError(channelName, "I2S");
    return;
  }

  i2s_std_config_t stdConfig = {
      .clk_cfg = I2S_STD_CLK_DEFAULT_CONFIG(cfg.sampleRateHz),
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
  stdConfig.slot_cfg.slot_mask = cfg.selectRightSlot ? I2S_STD_SLOT_RIGHT : I2S_STD_SLOT_LEFT;

  if (i2s_channel_init_std_mode(handle, &stdConfig) != ESP_OK || i2s_channel_enable(handle) != ESP_OK) {
    reportSensorError(channelName, "I2S");
  }
}

/** Bestimmt, wie viele I2S-Rohsamples sampleI2SRaw pro Aufruf liest: so viele, wie in ein
 *  Intervall bei der aktuell eingestellten Abtastrate (sampleIntervalMs) passen, begrenzt auf
 *  MIC_MIN_READ_SAMPLES/MIC_MAX_READ_SAMPLES. */
int i2sReadSampleCount(uint32_t sampleRateHz) {
  long samplesPerInterval = ((long) sampleRateHz * sampleIntervalMs) / 1000;
  return (int) constrain(samplesPerInterval, MIC_MIN_READ_SAMPLES, MIC_MAX_READ_SAMPLES);
}

/** Liest einen kurzen Block Rohsamples vom konfigurierten I2S-Sensor und bildet daraus den
 *  Spitzenbetrag (Peak-Amplitude) - ein einzelner Wert pro Aufrufzyklus, wie bei allen anderen
 *  Sensortypen. Bit-Ausrichtung (cfg.shiftBits) und Null-Check (cfg.zeroIsError) kommen aus der
 *  generischen I2SSensorConfig - die Firmware kennt kein konkretes Sensormodell. */
void sampleI2SRaw(char channelName) {
  i2s_chan_handle_t handle = micHandleForChannel(channelName);
  if (handle == NULL) {
    reportSensorError(channelName, "I2S");
    return;
  }

  const I2SSensorConfig &cfg = i2sConfigForChannel(channelName);
  int samplesToRead = i2sReadSampleCount(cfg.sampleRateHz);
  int32_t buffer[MIC_MAX_READ_SAMPLES];
  size_t bytesRead = 0;

  // Timeout skaliert mit der Abtastrate, sonst würde ein Sensor mit niedrigerer Rate hier
  // fälschlich in jedem Zyklus einen Fehler auslösen.
  unsigned long timeoutMs = ((unsigned long) samplesToRead * 1000UL) / cfg.sampleRateHz + 10;
  esp_err_t err = i2s_channel_read(handle, buffer, samplesToRead * sizeof(int32_t), &bytesRead, pdMS_TO_TICKS(timeoutMs));
  if (err != ESP_OK || bytesRead == 0) {
    reportSensorError(channelName, "I2S");
    return;
  }

  int sampleCount = bytesRead / sizeof(int32_t);
  int32_t peak = 0;
  for (int i = 0; i < sampleCount; i++) {
    int32_t sample = buffer[i] >> cfg.shiftBits; // gültige Bits liegen linksbündig im 32-Bit-Wort
    int32_t magnitude = (sample < 0) ? -sample : sample;
    if (magnitude > peak) peak = magnitude;
  }

  // Nullwert-Prüfung: siehe Erklärung bei I2S_ZERO_STREAK_THRESHOLD.
  if (peak == 0) {
    int &streak = (channelName == 'A') ? i2sZeroStreakA : i2sZeroStreakB;
    streak++;
    if (cfg.zeroIsError && streak >= I2S_ZERO_STREAK_THRESHOLD) {
      reportSensorError(channelName, "I2S");
      return;
    }
  } else {
    (channelName == 'A' ? i2sZeroStreakA : i2sZeroStreakB) = 0;
  }

  sendDataPacket(channelName, 0, peak);
}

// --- FFT und Spektrum-Modus ---

/** Kehrt die Bit-Reihenfolge eines {@code bitCount}-Bit-Wertes um - Hilfsfunktion für die
 *  Bit-Reversal-Permutation am Anfang der FFT. */
uint16_t reverseBits(uint16_t value, int bitCount) {
  uint16_t result = 0;
  for (int i = 0; i < bitCount; i++) {
    result = (result << 1) | (value & 1);
    value >>= 1;
  }
  return result;
}

/** Iterative, in-place Radix-2-Cooley-Tukey-FFT über {@code n} (Zweierpotenz) komplexe Werte,
 *  Ergebnis in {@code real}/{@code imag} zurückgeschrieben. */
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

  // k-Schleife außen, start-Schleife innen: wr/wi hängen nur von size/k ab, nicht von start -
  // spart wiederholte cosf()/sinf()-Aufrufe für dieselbe Kombination.
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

/** Berechnet das Hann-Fenster einmalig beim ersten Bedarf. */
void ensureHannWindow() {
  if (hannWindowReady) return;
  for (int i = 0; i < SPECTRUM_FFT_SIZE; i++) {
    hannWindow[i] = 0.5f - 0.5f * cosf(2.0f * PI * i / (SPECTRUM_FFT_SIZE - 1));
  }
  hannWindowReady = true;
}

/** Sendet ein zuvor über computeFFT berechnetes Spektrum als ein Paket:
 *  {@code #SPEC,<Kanal>,<Bins>,<Abtastrate>,<mag_0>,<mag_1>,...}. Magnituden als dBFS·10.
 *  Baut das Paket in einem Puffer zusammen und verschickt es mit einem einzigen hostWrite().
 *
 * @param sampleRateHz für diesen Kanal konfigurierte Abtastrate (Teil des Pakets, damit die
 *                      Software die Frequenzachse berechnen kann)
 * @param fullScale     Vollausschlag-Referenz für 0 dBFS, aus cfg.shiftBits abgeleitet (siehe
 *                      Aufrufer captureAndSendSpectrum) */
void sendSpectrumPacket(char channelName, float *real, float *imag, uint32_t sampleRateHz, float fullScale) {
  static char packetBuf[SPECTRUM_PACKET_BUF_SIZE];

  int offset = snprintf(packetBuf, SPECTRUM_PACKET_BUF_SIZE, "#SPEC,%c,%d,%lu",
                         channelName, SPECTRUM_OUTPUT_BINS, (unsigned long) sampleRateHz);

  for (int i = 0; i < SPECTRUM_OUTPUT_BINS && offset < (int) SPECTRUM_PACKET_BUF_SIZE - 8; i++) {
    float magnitude = sqrtf(real[i] * real[i] + imag[i] * imag[i]) / SPECTRUM_FFT_SIZE;
    float amplitude = fmaxf(magnitude / fullScale, 1e-9f); // Division durch 0 im log10 vermeiden
    int dbTimes10 = (int) roundf(20.0f * log10f(amplitude) * 10.0f);
    offset += snprintf(packetBuf + offset, SPECTRUM_PACKET_BUF_SIZE - offset, ",%d", dbTimes10);
  }

  packetBuf[offset++] = '\n';
  hostWrite(packetBuf, offset);
}

/** Nimmt SPECTRUM_FFT_SIZE Samples vom I2S-Sensor des Kanals auf, wendet ein Hann-Fenster an
 *  (reduziert den Leckeffekt durch den scharfen Rand des Ausschnitts), berechnet per FFT das
 *  Amplitudenspektrum und verschickt es. Wird für Kanäle mit TYPE_I2S im Spektrum-Modus
 *  aufgerufen (siehe I2SSensorConfig#spectrumMode). */
void captureAndSendSpectrum(char channelName) {
  i2s_chan_handle_t handle = micHandleForChannel(channelName);
  if (handle == NULL) {
    reportSensorError(channelName, "I2S");
    return;
  }

  const I2SSensorConfig &cfg = i2sConfigForChannel(channelName);

  static int32_t rawBuffer[SPECTRUM_FFT_SIZE];
  size_t bytesRead = 0;
  // Timeout skaliert mit der konfigurierten Abtastrate (SPECTRUM_FFT_SIZE Samples brauchen
  // entsprechend lang), plus fixer Sicherheitsmarge.
  unsigned long timeoutMs = ((unsigned long) SPECTRUM_FFT_SIZE * 1000UL) / cfg.sampleRateHz + 40;
  esp_err_t err = i2s_channel_read(handle, rawBuffer, sizeof(rawBuffer), &bytesRead, pdMS_TO_TICKS(timeoutMs));
  int sampleCount = bytesRead / sizeof(int32_t);
  if (err != ESP_OK || sampleCount < SPECTRUM_FFT_SIZE) {
    reportSensorError(channelName, "I2S");
    return;
  }

  // real/imag als "static" statt lokal: 2 * 1024 * 4 Byte wären auf dem Stack riskant knapp.
  static float real[SPECTRUM_FFT_SIZE];
  static float imag[SPECTRUM_FFT_SIZE];

  ensureHannWindow();
  int32_t peak = 0;
  for (int i = 0; i < SPECTRUM_FFT_SIZE; i++) {
    int32_t sample = rawBuffer[i] >> cfg.shiftBits; // gültige Bits liegen linksbündig, siehe sampleI2SRaw
    int32_t magnitude = (sample < 0) ? -sample : sample;
    if (magnitude > peak) peak = magnitude;
    real[i] = sample * hannWindow[i];
    imag[i] = 0;
  }

  // Nullwert-Prüfung: siehe Erklärung bei I2S_ZERO_STREAK_THRESHOLD.
  if (peak == 0) {
    int &streak = (channelName == 'A') ? i2sZeroStreakA : i2sZeroStreakB;
    streak++;
    if (cfg.zeroIsError && streak >= I2S_ZERO_STREAK_THRESHOLD) {
      reportSensorError(channelName, "I2S");
      return;
    }
  } else {
    (channelName == 'A' ? i2sZeroStreakA : i2sZeroStreakB) = 0;
  }

  computeFFT(real, imag, SPECTRUM_FFT_SIZE);

  // Vollausschlag-Referenz aus shiftBits ableiten: nach einem arithmetischen Rechts-Shift um
  // shiftBits belegt der gültige Wertebereich (31 - shiftBits) Bit. max(...,1.0f) ist reine
  // Absicherung gegen Division durch 0 (shiftBits ist in parseI2SSetPayload auf <= 24 begrenzt).
  float fullScale = fmaxf((float) ((1UL << (31 - cfg.shiftBits)) - 1), 1.0f);
  sendSpectrumPacket(channelName, real, imag, cfg.sampleRateHz, fullScale);
}


// =====================================================================================
// 7. KANAL-HARDWARE: FREIGABE UND KONFIGURATION
// =====================================================================================

/** Gibt die Hardware frei, die {@code oldType} auf diesem Kanal belegt hat, damit die
 *  gemeinsam genutzten Signal-Pins für einen anderen Sensortyp neu konfiguriert werden können.
 *  Setzt außerdem den typspezifischen Laufzeitzustand des Kanals zurück. */
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
    (channelName == 'A' ? i2sZeroStreakA : i2sZeroStreakB) = 0;
  } else if (oldType == TYPE_ONEWIRE) {
    (channelName == 'A' ? oneWireConversionPendingA : oneWireConversionPendingB) = false;
  } else if (oldType == TYPE_HX711) {
    (channelName == 'A' ? hx711WaitingA : hx711WaitingB) = false;
  }
}

/** Konfiguriert die drei Kanal-Pins für den neu gewählten Sensortyp. Vorher muss die alte
 *  Hardware über releaseChannelHardware freigegeben worden sein. */
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
      // Interner Pull-up auf DOUT: ohne ihn floatet der Pin bei abgestecktem Modul undefiniert
      // und sampleHX711() liest dann teils zufällig "bereit" (LOW) und damit Datenmüll statt
      // zuverlässig in den Timeout zu laufen. Der HX711 selbst treibt DOUT aktiv (Push-Pull),
      // der schwache interne Pull-up stört das im angeschlossenen Zustand nicht.
      pinMode(pins[0], INPUT_PULLUP);
      pinMode(pins[1], OUTPUT);
      digitalWrite(pins[1], LOW);
      break;
    case TYPE_DIGITAL:
      pinMode(pins[0], INPUT_PULLUP);
      break;
    case TYPE_ONEWIRE:
      // Interner Pull-up als Rückfallebene: der Bus braucht primär einen externen Pull-up
      // (typisch 4,7kΩ nach 3,3V, siehe Dateikopf), der beim Betrieb klar dominiert. Sitzt
      // dieser Pull-up aber auf dem Sensormodul selbst statt auf der MCU-Seite, floatet der Pin
      // bei abgestecktem Sensor ohne ihn undefiniert und oneWireReset() erkennt eine fehlende
      // Präsenz nicht zuverlässig. Der schwache interne Pull-up (~45kΩ) sorgt dann wenigstens für
      // einen deterministischen HIGH-Pegel.
      pinMode(pins[0], INPUT_PULLUP);
      configureOneWireSensor(channelName, pins[0]);
      break;
    case TYPE_I2S:
      configureI2S(channelName, pins);
      break;
    case TYPE_NONE:
    default:
      break;
  }
}


// =====================================================================================
// 8. PARSER FÜR SET-PAYLOADS
// =====================================================================================

/** Parst eine Hex-Teilzeichenkette (ohne "0x"-Präfix). */
long parseHexToken(const String &s) {
  return strtol(s.c_str(), nullptr, 16);
}

// --- I2C ---

/** Parst einen einzelnen Init-Write-Eintrag "reg:byte:byte:..." (alles hex) in {@code out}.
 *  @return false bei erkennbar kaputtem Format */
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

/** Parst einen einzelnen Read-Eintrag "reg:len:B|L:slot" (reg hex, len/slot dezimal).
 *  @return false bei erkennbar kaputtem Format oder ungültiger Länge (nicht 1-4) */
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

/** Zerlegt eine ';'-getrennte Liste von Init-Write-Einträgen. Leere Liste ist gültig. */
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

/** Wie parseI2CWriteList, aber für Read-Einträge. */
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

/** Zerlegt das Payload eines "SET,<Kanal>,I2C,..."-Kommandos in die I2C-Konfiguration des
 *  Kanals. Format: "<Adresse hex>,<Init-Writes>,<Reads>".
 *  Init-Writes: "-" oder ';'-getrennt "reg:byte:byte:..." (alles hex).
 *  Reads: ';'-getrennt "reg:len:B|L:slot" (reg hex, len/slot dezimal).
 *  Beispiel INA219: "40,0:39:9f;5:10:0,2:2:B:0;4:2:B:1"
 *  @return false bei erkennbar kaputtem Format */
bool parseI2CSetPayload(char channelName, const String &params) {
  I2CSensorConfig &cfg = i2cConfigForChannel(channelName);
  cfg = I2CSensorConfig();

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

// --- 1-Wire ---

/** Parst einen einzelnen 1-Wire-Init-Write-Eintrag "cmd:byte:byte:..." (alles hex) in
 *  {@code out}. Wie parseI2CWriteEntry, nur ohne I2C-Register-Semantik ({@code command} ist ein
 *  1-Wire-Kommandobyte wie 0x4E "Write Scratchpad", keine Registeradresse).
 *  @return false bei erkennbar kaputtem Format */
bool parseOneWireWriteEntry(const String &entry, OneWireWriteSpec &out) {
  int firstColon = entry.indexOf(':');
  if (firstColon == -1) return false;

  out.command = (uint8_t) parseHexToken(entry.substring(0, firstColon));
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

/** Zerlegt eine ';'-getrennte Liste von 1-Wire-Init-Write-Einträgen. Leere Liste ist gültig.
 *  Wie parseI2CWriteList. */
bool parseOneWireWriteList(const String &list, OneWireWriteSpec specs[], uint8_t &countOut, uint8_t maxCount) {
  countOut = 0;
  if (list.length() == 0) return true;

  int start = 0;
  while (start <= (int) list.length() && countOut < maxCount) {
    int sep = list.indexOf(';', start);
    String entry = (sep == -1) ? list.substring(start) : list.substring(start, sep);
    if (!parseOneWireWriteEntry(entry, specs[countOut])) return false;
    countOut++;
    if (sep == -1) break;
    start = sep + 1;
  }
  return true;
}

/** Zerlegt das Payload eines "SET,<Kanal>,ONEWIRE,..."-Kommandos in die OneWireSensorConfig.
 *  Format: "Init-Writes,ConvertCmd(hex),DelayMs,ReadCmd(hex),ReadLen,ValueOffset,ValueLen,B|L,0|1,Slot".
 *  Init-Writes: "-" oder ';'-getrennt "cmd:byte:byte:..." (alles hex), siehe parseOneWireWriteList -
 *  wird einmalig beim Umschalten auf den Sensor geschrieben (siehe configureOneWireSensor), z.B.
 *  für die Auflösungseinstellung des DS18B20 über "Write Scratchpad" (0x4E).
 *  Beispiel DS18B20 bei 12-Bit-Standardauflösung (keine Init-Writes nötig): "-,44,750,be,9,0,2,L,1,0"
 *  Beispiel DS18B20 bei 9-Bit-Auflösung (TH/TL=0, Konfigregister 0x1f): "4e:0:0:1f,44,94,be,9,0,2,L,1,0"
 *  @return false bei erkennbar kaputtem Format */
bool parseOneWireSetPayload(char channelName, const String &params) {
  OneWireSensorConfig &cfg = oneWireConfigForChannel(channelName);
  cfg = OneWireSensorConfig();

  const int fieldCount = 10;
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

  String initsStr = params.substring(fieldStart[0], fieldEnd[0]);
  if (initsStr == "-") initsStr = "";
  if (!parseOneWireWriteList(initsStr, cfg.initWrites, cfg.initWriteCount, MAX_ONEWIRE_WRITES)) {
    return false;
  }

  cfg.convertCmd = (uint8_t) parseHexToken(params.substring(fieldStart[1], fieldEnd[1]));
  cfg.conversionDelayMs = (unsigned long) params.substring(fieldStart[2], fieldEnd[2]).toInt();
  cfg.readCmd = (uint8_t) parseHexToken(params.substring(fieldStart[3], fieldEnd[3]));
  cfg.readLen = (uint8_t) params.substring(fieldStart[4], fieldEnd[4]).toInt();
  cfg.valueOffset = (uint8_t) params.substring(fieldStart[5], fieldEnd[5]).toInt();
  cfg.valueLen = (uint8_t) params.substring(fieldStart[6], fieldEnd[6]).toInt();
  cfg.littleEndian = params.substring(fieldStart[7], fieldEnd[7]).equalsIgnoreCase("L");
  cfg.checkCrc = params.substring(fieldStart[8], fieldEnd[8]) == "1";
  cfg.slot = (uint8_t) params.substring(fieldStart[9], fieldEnd[9]).toInt();

  if (cfg.readLen == 0 || cfg.readLen > 16) return false;
  if ((int) cfg.valueOffset + (int) cfg.valueLen > cfg.readLen) return false;
  return true;
}

// --- I2S ---

/** Zerlegt das Payload eines "SET,<Kanal>,I2S,..."-Kommandos in die I2SSensorConfig.
 *  Format: "RAW|SPEC,Abtastrate,L|R,ShiftBits,0|1".
 *  Beispiel INMP441 (Einzelwert, linker Slot, 24 gültige Bits, Nullwert=Fehler): "RAW,16000,L,8,1"
 *  Gleiches Mikrofon im Spektrum-Modus: "SPEC,16000,L,8,1"
 *  @return false bei erkennbar kaputtem Format oder Werten außerhalb des sinnvollen Bereichs */
bool parseI2SSetPayload(char channelName, const String &params) {
  I2SSensorConfig &cfg = i2sConfigForChannel(channelName);
  cfg = I2SSensorConfig();

  const int fieldCount = 5;
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

  String modeStr = params.substring(fieldStart[0], fieldEnd[0]);
  cfg.spectrumMode = modeStr.equalsIgnoreCase("SPEC");
  if (!cfg.spectrumMode && !modeStr.equalsIgnoreCase("RAW")) return false;

  long sampleRate = params.substring(fieldStart[1], fieldEnd[1]).toInt();
  if (sampleRate < 1000 || sampleRate > 48000) return false; // 48kHz: Obergrenze üblicher I2S-MEMS-Mikrofone
  cfg.sampleRateHz = (uint32_t) sampleRate;

  String slotStr = params.substring(fieldStart[2], fieldEnd[2]);
  cfg.selectRightSlot = slotStr.equalsIgnoreCase("R");
  if (!cfg.selectRightSlot && !slotStr.equalsIgnoreCase("L")) return false;

  long shift = params.substring(fieldStart[3], fieldEnd[3]).toInt();
  if (shift < 0 || shift > 24) return false; // >24 ließe keine sinnvolle Auflösung mehr übrig
  cfg.shiftBits = (uint8_t) shift;

  String zeroErrStr = params.substring(fieldStart[4], fieldEnd[4]);
  cfg.zeroIsError = (zeroErrStr == "1");
  if (zeroErrStr != "0" && zeroErrStr != "1") return false;

  return true;
}


// =====================================================================================
// 9. KOMMANDO-VERARBEITUNG
// =====================================================================================

/** Verarbeitet eine vollständige Kommandozeile vom Host (siehe Protokollübersicht im Dateikopf). */
void processCommand(String command) {
  command.trim();

  if (command.equalsIgnoreCase("PING")) {
    hostPrint(HELLO_MESSAGE);
  } else if (command.equalsIgnoreCase("START")) {
    isStreaming = true;
    hostPrint("#OK,START\n");
  } else if (command.equalsIgnoreCase("STOP")) {
    isStreaming = false;
    hostPrint("#OK,STOP\n");
  } else if (command.startsWith("RATE,")) {
    // Erlaubt sind 1-1000 Hz, andere Werte werden ohne Antwort ignoriert. Das Intervall wird
    // ganzzahlig berechnet (1000/Hz), die tatsächliche Rate kann daher leicht von der
    // bestätigten abweichen (z.B. 300 Hz -> 3 ms -> ~333 Hz).
    long rateHz = command.substring(5).toInt();
    if (rateHz >= 1 && rateHz <= 1000) {
      sampleIntervalMs = 1000 / rateHz;
      char buf[32];
      int len = snprintf(buf, sizeof(buf), "#OK,RATE,%ld\n", rateHz);
      hostWrite(buf, len);
    }
  } else if (command.startsWith("SET,")) {
    // Vier Formate:
    //   SET,<Kanal>,<SensorTyp>                          z.B. SET,A,HX711 / SET,A,DIGITAL / SET,A,ANALOG
    //   SET,<Kanal>,I2C,<Adresse>,<Init-Writes>,<Reads>   z.B. SET,A,I2C,40,0:39:9f;5:10:0,2:2:B:0;4:2:B:1
    //   SET,<Kanal>,I2S,<Modus>,<Abtastrate>,<Slot>,<ShiftBits>,<Null=Fehler>  siehe parseI2SSetPayload
    //                                                      z.B. SET,A,I2S,RAW,16000,L,8,1
    //   SET,<Kanal>,ONEWIRE,<10 Felder, siehe parseOneWireSetPayload>  z.B. SET,A,ONEWIRE,-,44,750,be,9,0,2,L,1,0
    // Ein unbekannter Typ (z.B. "NONE") setzt den Kanal auf TYPE_NONE. Bestätigt wird in jedem
    // Fall mit #OK,SET - auch wenn ein kaputtes Payload den Kanal auf TYPE_NONE zurückgesetzt hat
    // (der Fehler kommt dann zusätzlich als #ERR,...CFG).
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
        newType = TYPE_NONE; // kaputtes Payload: Kanal sicherheitshalber auf "kein Sensor"
        reportSensorError(targetChannel, "I2CCFG");
      }
      ackPayload = rest;
    } else if (firstToken.equalsIgnoreCase("I2S")) {
      newType = TYPE_I2S;
      if (!parseI2SSetPayload(targetChannel, extraParams)) {
        newType = TYPE_NONE;
        reportSensorError(targetChannel, "I2SCFG");
      }
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

/** Liest ein Kommandozeichen aus USB/Bluetooth und stößt bei Zeilenumbruch die Verarbeitung an. */
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

/** Liest alle wartenden Zeichen von USB und Bluetooth (beide teilen sich einen Zeilenpuffer). */
void handleSerialCommunication() {
  static String inputBuffer = "";
  while (Serial.available() > 0) {
    feedCommandChar(inputBuffer, (char) Serial.read());
  }
  while (SerialBT.available() > 0) {
    feedCommandChar(inputBuffer, (char) SerialBT.read());
  }
}


// =====================================================================================
// 10. ABTAST-DISPATCHER
// =====================================================================================

/** Tastet den konfigurierten Sensor eines Kanals im festen sampleIntervalMs-Takt ab. Bei einem
 *  Übertragungsfehler wird kein Datenpaket verschickt, statt einen falschen 0-Wert zu senden. */
void sampleChannel(char channelName, SensorType type, const int pins[3]) {
  if (type == TYPE_ANALOG) {
    int analogVal = analogRead(pins[0]);
    sendDataPacket(channelName, 0, analogVal);
  } else if (type == TYPE_I2C) {
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
    // Kein Aufruf hier: HX711 hat sein eigenes freilaufendes Timing, unabhängig von der über
    // RATE eingestellten globalen Abtastrate, und wird deshalb direkt bei jedem loop()-Durchlauf
    // behandelt statt im festen sampleIntervalMs-Takt (siehe loop() und sampleHX711).
  } else if (type == TYPE_DIGITAL) {
    int rawState = digitalRead(pins[0]);
    sendDataPacket(channelName, 0, rawState);
  } else if (type == TYPE_ONEWIRE) {
    sampleOneWire(channelName, pins[0]);
  } else if (type == TYPE_I2S) {
    if (i2sConfigForChannel(channelName).spectrumMode) {
      // Kein Aufruf hier: das Spektrum braucht eine eigene Taktung (SPECTRUM_INTERVAL_MS) und
      // wird direkt in loop() behandelt.
    } else {
      sampleI2SRaw(channelName);
    }
  }
}


// =====================================================================================
// 11. SETUP UND HAUPTSCHLEIFE
// =====================================================================================

void setup() {
  Serial.begin(BAUD_RATE);
  SerialBT.begin(BT_DEVICE_NAME);
  delay(200);

  // Bewusst keine Pin-/Bus-Initialisierung hier: die Pin-Rolle hängt vom gewählten Sensortyp ab
  // und wird erst bei SET über configureChannelHardware() hergestellt.
  hostPrint(HELLO_MESSAGE);
}

void loop() {
  handleSerialCommunication();

  if (!isStreaming) return;

  // HX711 liefert von sich aus 10 oder 80 Werte/Sekunde, unabhängig von der über RATE
  // eingestellten globalen Abtastrate - der Chip lässt sich nicht schneller machen, aber auch
  // nicht auf ein beliebiges Zeitraster zwingen. Deshalb hier bei jedem loop()-Durchlauf
  // (kostet im Leerlauf nur einen digitalRead) statt im festen sampleIntervalMs-Takt geprüft:
  // sonst hängt die Trefferquote von der zufälligen Phasenlage zwischen der Abfrage und dem
  // frei laufenden, nie exakt 10,000Hz genauen internen Oszillator des Chips ab - genau das
  // erzeugte die unregelmäßigen Lücken im aufgezeichneten Zeitstempel.
  if (configChannelA == TYPE_HX711) sampleHX711('A', PINS_CHANNEL_A[0], PINS_CHANNEL_A[1]);
  if (configChannelB == TYPE_HX711) sampleHX711('B', PINS_CHANNEL_B[0], PINS_CHANNEL_B[1]);

  // Alle übrigen Sensortypen im festen Takt der eingestellten Abtastrate.
  unsigned long currentTimeMs = millis();
  if (currentTimeMs - lastSampleTimeMs >= sampleIntervalMs) {
    lastSampleTimeMs = currentTimeMs;
    if (configChannelA != TYPE_HX711) sampleChannel('A', configChannelA, PINS_CHANNEL_A);
    if (configChannelB != TYPE_HX711) sampleChannel('B', configChannelB, PINS_CHANNEL_B);
  }

  // Das Frequenzspektrum braucht eine eigene, von der Abtastrate unabhängige Taktung.
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
