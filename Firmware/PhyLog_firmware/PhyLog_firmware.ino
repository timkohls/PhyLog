/*
 * PhyLog ESP32 Firmware v7.9
 *
 * Sensortypen: I2C (INA219, VEML7700), HX711 (DOUT/SCK, kein I2C), Analog-Pin, sowie ein
 * INMP441-Mikrofon (I2S). Welcher Sensortyp auf Kanal A/B aktiv ist, wird ausschließlich über
 * das serielle Kommando SET,<Kanal>,<Typ> gesetzt (siehe GUI.pushSensorSelectionToFirmware).
 * Beide Kanäle starten bei TYPE_NONE, bis die Software eine Auswahl sendet.
 *
 * <p>Jeder Kanal-Port (RJ45, 9 Pins) hat genau drei Signal-Pins (Pin 2, 3, 4 - siehe
 * pinout.md), deren Rolle sich erst beim SET-Kommando aus dem gewählten Sensortyp ergibt (siehe
 * {@link #configureChannelHardware}):
 *   I2C:       Pin2=SDA,  Pin3=SCL
 *   HX711:     Pin2=DOUT, Pin3=SCK
 *   I2S (Mic): Pin2=WS,   Pin3=BCLK, Pin4=SD
 *   Analog:    Pin2=Eingang
 * Nie mehr als drei Adern pro Sensor, unabhängig vom Typ.</p>
 *
 * <p>Das Mikrofon nutzt bewusst den NEUEN I2S-Standardtreiber (driver/i2s_std.h), nicht den
 * alten driver/i2s.h: der alte Treiber bringt intern noch den Legacy-ADC-Treiber mit, der auf
 * aktuellen arduino-esp32-Versionen mit dem von analogRead() genutzten ADC-Treiber
 * ("driver_ng") kollidiert - das führte zu einem abort() beim Start, sobald irgendein Kanal auf
 * TYPE_ANALOG stand. Der neue Treiber betrifft den ADC-Pfad nicht.</p>
 *
 * <p>Kanal A und Kanal B hängen an physisch getrennten Bussen (I2C: Wire/Wire1, I2S: Port 0/1) -
 * das ist notwendig, sobald beide Kanäle gleichzeitig einen Sensor betreiben, auch denselben
 * Typ zweimal.</p>
 *
 * <p>v7.4: Kanal A, Pin 2 (SCL/SCK/BCLK) lag bisher auf GPIO12 - einem Strapping-Pin, der beim
 * Reset die Flash-Spannung festlegt. War beim Reset bereits ein Sensor angeschlossen, konnte der
 * ESP32 dadurch mit falscher Flash-Spannung booten und landete in einer Watchdog-Reset-Schleife
 * (TG0WDT_SYS_RESET), noch bevor setup() lief - nur per Reflash (zufällig verbunden mit einem
 * sauberen Reset) zu beheben. Kanal A liegt jetzt komplett auf GPIO 17/16/4 (Pin 2/3/4) - eine
 * auf dem 38-Pin-DevKitC zusammenhängende Dreiergruppe ohne Strapping-Pins, analog zu Kanal B
 * auf 27/26/25. Zusätzlich: automatischer I2C-Bus-Reset nach mehreren Fehlern in Folge (siehe
 * {@link #resetI2CBus}).</p>
 *
 * <p>v7.5: Neuer Live-Modus fürs Mikrofon - statt (bzw. zusätzlich zu) dem einzelnen dB-Wert pro
 * Zyklus liefert das SPEC-Kommando laufend ein 512-Bin-Amplitudenspektrum (siehe
 * {@link #captureAndSendSpectrum}), berechnet über eine selbst geschriebene Radix-2-FFT
 * ({@link #computeFFT}) auf 1024 Samples je Bild.</p>
 *
 * <p>v7.6: Das separate SPEC-Kommando ist wieder weg - das Spektrum ist jetzt schlicht ein
 * eigener Sensortyp (TYPE_MIC_SPECTRUM, GUI-seitig "MICSPEC"), der sich wie jeder andere Sensor
 * über SET auswählen lässt und ganz normal an START/STOP hängt, statt eine eigene
 * Start/Stopp-Logik zu brauchen. captureAndSendSpectrum() läuft dabei weiterhin mit eigener
 * Taktung (SPECTRUM_INTERVAL_MS), unabhängig von der für normale Sensoren gedachten Abtastrate.</p>
 *
 * <p>v7.7: Baudrate auf 460800 vervierfacht (siehe BAUD_RATE) und SPECTRUM_INTERVAL_MS von
 * 250ms auf 60ms verkürzt - das Spektrum ist damit an der tatsächlich erreichbaren Grenze statt
 * an einer für die alte, langsamere Baudrate konservativ gewählten Konstante. GUI-seitig
 * erzwingt der Sensor-Dialog jetzt außerdem, dass ein Kanal automatisch auf "kein Sensor"
 * zurückfällt, sobald der andere Kanal ein Frequenzspektrum aufnimmt - zwei Mikrofon-Captures
 * gleichzeitig ergäben ohnehin nur unnötige Konkurrenz um dieselbe, jetzt knappere Bandbreite.</p>
 *
 * <p>v7.8: Neuer Sensortyp TYPE_HALL für das KY-003-Hall-Sensor-Modul - ein einfacher digitaler
 * Eingang (nur Pin 0 genutzt), Rohwert ist direkt digitalRead(). Wie bei allen anderen Sensoren
 * übernimmt die Java-Seite (HallEffectSensor) die Interpretation des Rohwerts, hier inklusive
 * der Invertierung, da das Modul active-low ist.</p>
 *
 * <p>v7.9: Zwei Sensoren konnten die eingestellte Abtastrate faktisch nicht einhalten, egal wie
 * hoch sie in der GUI gewählt wurde: Das Mikrofon (dB-Modus) las immer 256 I2S-Samples pro
 * Zyklus, was allein schon 16ms dauert und die Rate hart auf ~62 Hz deckelte - jetzt liest
 * {@link #microphoneReadSampleCount} dynamisch so viele Samples, wie in das aktuell eingestellte
 * Intervall passen. Der VEML7700 hatte eine Integrationszeit von 100ms (max. 10 Hz) - jetzt auf
 * die kürzeste verfügbare Einstellung (25ms, max. 40 Hz) reduziert. Java-seitig kennt jeder
 * Sensor jetzt seine realistische Obergrenze ({@link Sensor#getMaxSampleRateHz}), damit die GUI
 * gar nicht erst höhere Raten anbietet, als der jeweilige Sensor tatsächlich liefern kann.</p>
 */

#include <Wire.h>
#include <driver/i2s_std.h>
#include <math.h>

enum SensorType {
  TYPE_NONE = 0,
  TYPE_ANALOG = 1,
  TYPE_INA219 = 2,
  TYPE_VEML7700 = 3,
  TYPE_HX711 = 4,
  TYPE_MICROPHONE = 5,
  TYPE_MIC_SPECTRUM = 6,
  TYPE_HALL = 7
};

SensorType configChannelA = TYPE_NONE;
SensorType configChannelB = TYPE_NONE;

/** Die drei Signal-Pins eines Kanal-Ports (Steckerposition 2, 3, 4 - siehe pinout.md), deren
 *  Rolle vom gewählten Sensortyp abhängt (siehe {@link #configureChannelHardware}):
 *  I2C: [0]=SDA, [1]=SCL   HX711: [0]=DOUT, [1]=SCK   I2S: [0]=WS, [1]=BCLK, [2]=SD
 *  Analog: [0]=Eingang   Hall (KY-003): [0]=Signal
 *
 *  ACHTUNG bei künftigen Pin-Änderungen: GPIO 0, 2, 5, 12 und 15 sind beim ESP32
 *  "Strapping-Pins" - ihr Pegel wird nur im Moment des Resets ausgelesen und beeinflusst u. a.
 *  Boot-Modus bzw. Flash-Spannung. Hängt hier bereits ein Sensor (z. B. ein I2C-Pull-up) an
 *  einem solchen Pin, wenn der ESP32 einen Reset macht, kann der Chip mit falscher
 *  Flash-Spannung booten und landet in einer Watchdog-Reset-Schleife (TG0WDT_SYS_RESET), noch
 *  bevor setup() überhaupt läuft - genau das Bild "geht nur nach Reflash weg, tritt vor allem
 *  auf, wenn Sensoren schon beim Einstecken angeschlossen sind". Deshalb bewusst KEINER dieser
 *  Pins hier verwendet. */
const int PINS_CHANNEL_A[3] = {17, 16, 4};
const int PINS_CHANNEL_B[3] = {27, 26, 25};

const uint8_t ADDR_INA219   = 0x40;
const uint8_t ADDR_VEML7700 = 0x10;

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

/** FFT-Größe für den Live-Frequenzspektrum-Modus (siehe {@link #captureAndSendSpectrum}) - eine
 *  Zweierpotenz, wie sie die iterative Radix-2-FFT ({@link #computeFFT}) voraussetzt. Ein reelles
 *  Signal liefert nur n/2 unabhängige Frequenz-Bins (die obere Hälfte ist bei reellem Eingang nur
 *  das gespiegelte Konjugat), 1024 Punkte ergeben also die gewünschten 512 nutzbaren Bins. */
const int SPECTRUM_FFT_SIZE = 1024;
const int SPECTRUM_OUTPUT_BINS = SPECTRUM_FFT_SIZE / 2;

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
    Serial.print("#ERR,");
    Serial.print(errorTag);
    Serial.print(",");
    Serial.println(channelName);
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

/**
 * Liest ein 16-Bit-I2C-Register MSB-zuerst (big-endian, TI-Konvention - passend für den
 * INA219). Gibt bei Erfolg true zurück und schreibt den Rohwert nach outValue; bei einem
 * Übertragungsfehler wird false zurückgegeben und outValue nicht verändert.
 *
 * <p>outValue ist bewusst uint16_t (nicht int16_t): der Rohwert wird unverändert als 0..65535
 * über die serielle Schnittstelle geschickt, die softwareseitige Sensor-Klasse entscheidet dann
 * je nach physikalischer Größe, ob und wie er vorzeichenbehaftet zu interpretieren ist.</p>
 */
bool readI2CRegister16(TwoWire &bus, uint8_t addr, uint8_t reg, uint16_t &outValue) {
  bus.beginTransmission(addr);
  bus.write(reg);
  if (bus.endTransmission(false) != 0) return false;

  if (bus.requestFrom((int)addr, 2) != 2) return false;

  uint8_t msb = bus.read();
  uint8_t lsb = bus.read();
  outValue = ((uint16_t)msb << 8) | lsb;
  return true;
}

/**
 * Wie {@link #readI2CRegister16}, aber LSB-zuerst (little-endian). Vishay-Bausteine wie der
 * VEML7700 senden ihre 16-Bit-Register in dieser Reihenfolge, anders als der INA219.
 */
bool readI2CRegister16LE(TwoWire &bus, uint8_t addr, uint8_t reg, uint16_t &outValue) {
  bus.beginTransmission(addr);
  bus.write(reg);
  if (bus.endTransmission(false) != 0) return false;

  if (bus.requestFrom((int)addr, 2) != 2) return false;

  uint8_t lsb = bus.read();
  uint8_t msb = bus.read();
  outValue = ((uint16_t)msb << 8) | lsb;
  return true;
}

/**
 * Liest einen 24-Bit-Rohwert vom HX711 per Bit-Banging (kein I2C, sondern eigenes DOUT/SCK-
 * Protokoll). Wartet zunächst, bis das Modul über DOUT LOW signalisiert, dass ein Wert bereit
 * ist; ist das nach {@link #HX711_TIMEOUT_MS} nicht der Fall, gilt der Zyklus als fehlgeschlagen
 * (kein angeschlossenes Modul oder noch nicht bereit), statt einen falschen Wert zu senden.
 *
 * <p>Die 25. Taktflanke (nach den 24 Datenbits) wählt Kanal A mit Gain 128 für den nächsten
 * Messzyklus - die in dieser Firmware fest verwendete Standardkonfiguration des HX711.</p>
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
  for (int i = 0; i < 24; i++) {
    digitalWrite(sckPin, HIGH);
    delayMicroseconds(1);
    value = (value << 1) | digitalRead(doutPin);
    digitalWrite(sckPin, LOW);
    delayMicroseconds(1);
  }

  digitalWrite(sckPin, HIGH); // 25. Flanke: Gain 128 / Kanal A für den nächsten Zyklus
  delayMicroseconds(1);
  digitalWrite(sckPin, LOW);
  delayMicroseconds(1);

  if (value & 0x800000) { // 24-Bit-Zweierkomplement auf 32 Bit vorzeichenrichtig erweitern
    value |= 0xFF000000;
  }
  outValue = value;
  return true;
}

/** Schreibt die Init-Konfiguration eines I2C-Sensortyps auf den angegebenen (bereits über
 *  {@link #configureChannelHardware} gestarteten) Bus. Meldet einen Fehler, falls einer der
 *  Schreibvorgänge fehlschlägt. */
void configureSensorOnBus(TwoWire &bus, SensorType type, char channelName) {
  bool ok = true;

  if (type == TYPE_INA219) {
    bus.beginTransmission(ADDR_INA219);
    bus.write(0x00); // Config Register
    bus.write(0x39); bus.write(0x9F); // 32V, Gain 8, 12-bit ADC
    ok &= (bus.endTransmission() == 0);

    bus.beginTransmission(ADDR_INA219);
    bus.write(0x05); // Calibration Register
    bus.write(0x10); bus.write(0x00);
    ok &= (bus.endTransmission() == 0);
  } else if (type == TYPE_VEML7700) {
    bus.beginTransmission(ADDR_VEML7700);
    bus.write(0x00); // ALS_CONF Register
    // Gain 1x, Integrationszeit 25ms (kürzeste verfügbare Einstellung statt der 100ms im
    // Standard-Reset-Zustand) - der Sensor liefert dadurch maximal alle 25ms (~40 Hz) einen
    // neuen Messwert; schnelleres Abfragen würde nur wiederholt denselben Wert zurückgeben.
    // Kürzere Integrationszeit bedeutet weniger gesammeltes Licht pro Messung, also etwas mehr
    // Rauschen bzw. geringere Empfindlichkeit bei sehr wenig Licht - Tausch von Genauigkeit
    // gegen Reaktionsgeschwindigkeit.
    bus.write(0x00); bus.write(0x03);
    ok &= (bus.endTransmission() == 0);
  }

  if (!ok) {
    reportSensorError(channelName, "I2C");
  }
}

/** Initialisiert den I2C-Bus eines Kanals neu (Bus schließen, kurz warten, neu starten und den
 *  Sensor neu konfigurieren). Wird nach mehreren I2C-Fehlern in Folge aufgerufen, um einen durch
 *  einen Wackelkontakt "hängen gebliebenen" Bus wieder freizubekommen, statt dass der Kanal bis
 *  zum nächsten manuellen Reset dauerhaft Fehler meldet. */
void resetI2CBus(char channelName, SensorType type) {
  TwoWire &bus = busForChannel(channelName);
  bus.end();
  delay(5);
  const int *pins = (channelName == 'A') ? PINS_CHANNEL_A : PINS_CHANNEL_B;
  bus.begin(pins[0], pins[1], 400000);
  configureSensorOnBus(bus, type, channelName);
}

/** Zählt aufeinanderfolgende I2C-Fehler je Kanal und stößt ab {@link #I2C_FAIL_STREAK_RESET_THRESHOLD}
 *  einen automatischen Bus-Reset an (siehe {@link #resetI2CBus}). Nach jedem I2C-Zugriff
 *  (erfolgreich oder nicht) aufzurufen. */
void noteI2CResult(char channelName, SensorType type, bool success) {
  int &streak = (channelName == 'A') ? i2cFailStreakA : i2cFailStreakB;
  if (success) {
    streak = 0;
    return;
  }
  streak++;
  if (streak >= I2C_FAIL_STREAK_RESET_THRESHOLD) {
    streak = 0;
    resetI2CBus(channelName, type);
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
  if (oldType == TYPE_INA219 || oldType == TYPE_VEML7700) {
    busForChannel(channelName).end();
  } else if (oldType == TYPE_MICROPHONE || oldType == TYPE_MIC_SPECTRUM) {
    i2s_chan_handle_t &handle = micHandleForChannel(channelName);
    if (handle != NULL) {
      i2s_channel_disable(handle);
      i2s_del_channel(handle);
      handle = NULL;
    }
  }
}

/** Konfiguriert die drei Kanal-Pins für den neu gewählten Sensortyp (siehe Klassenkommentar zur
 *  Pin-Belegung). Vorher muss die alte Hardware über {@link #releaseChannelHardware}
 *  freigegeben worden sein. */
void configureChannelHardware(char channelName, SensorType newType, const int pins[3]) {
  switch (newType) {
    case TYPE_ANALOG:
      break; // analogRead() braucht keine explizite pinMode()
    case TYPE_INA219:
    case TYPE_VEML7700: {
      TwoWire &bus = busForChannel(channelName);
      bus.begin(pins[0], pins[1], 400000);
      configureSensorOnBus(bus, newType, channelName);
      break;
    }
    case TYPE_HX711:
      pinMode(pins[0], INPUT);
      pinMode(pins[1], OUTPUT);
      digitalWrite(pins[1], LOW);
      break;
    case TYPE_HALL:
      // Nur Pin 0 genutzt (Signal-Pin des KY-003) - der interne Pull-up ist redundant, falls das
      // Modul bereits einen eigenen besitzt, schadet aber nicht und macht die Beschaltung
      // robuster gegen Module ohne eigenen Pull-up.
      pinMode(pins[0], INPUT_PULLUP);
      break;
    case TYPE_MICROPHONE:
    case TYPE_MIC_SPECTRUM:
      // Identische I2S-Hardware für beide Mikrofon-"Sensoren" - TYPE_MIC_SPECTRUM liefert nur
      // ein anderes Ausgabeformat (Spektrum statt Einzelwert), siehe sampleChannel()/loop().
      configureMicrophone(channelName, pins);
      break;
    case TYPE_NONE:
    default:
      break;
  }
}

void processCommand(String command) {
  command.trim();

  if (command.equalsIgnoreCase("PING")) {
    Serial.println("#HELLO,PhyLog-ESP32,fw=7.9");
  } else if (command.equalsIgnoreCase("START")) {
    isStreaming = true;
    Serial.println("#OK,START");
  } else if (command.equalsIgnoreCase("STOP")) {
    isStreaming = false;
    Serial.println("#OK,STOP");
  } else if (command.startsWith("RATE,")) {
    long rateHz = command.substring(5).toInt();
    if (rateHz >= 1 && rateHz <= 1000) {
      sampleIntervalMs = 1000 / rateHz;
      Serial.print("#OK,RATE,"); Serial.println(rateHz);
    }
  } else if (command.startsWith("SET,")) {
    // Format: SET,<Kanal>,<SensorTyp> (z.B. SET,A,INA219)
    int firstComma = command.indexOf(',');
    int secondComma = command.indexOf(',', firstComma + 1);

    if (firstComma != -1 && secondComma != -1) {
      char targetChannel = command.charAt(firstComma + 1);
      String sensorTypeName = command.substring(secondComma + 1);

      SensorType newType = TYPE_NONE;
      if (sensorTypeName.equalsIgnoreCase("INA219")) newType = TYPE_INA219;
      else if (sensorTypeName.equalsIgnoreCase("VEML7700")) newType = TYPE_VEML7700;
      else if (sensorTypeName.equalsIgnoreCase("ANALOG")) newType = TYPE_ANALOG;
      else if (sensorTypeName.equalsIgnoreCase("HX711")) newType = TYPE_HX711;
      else if (sensorTypeName.equalsIgnoreCase("MIC")) newType = TYPE_MICROPHONE;
      else if (sensorTypeName.equalsIgnoreCase("MICSPEC")) newType = TYPE_MIC_SPECTRUM;
      else if (sensorTypeName.equalsIgnoreCase("HALL")) newType = TYPE_HALL;

      if (targetChannel == 'A') {
        releaseChannelHardware('A', configChannelA);
        configChannelA = newType;
        configureChannelHardware('A', newType, PINS_CHANNEL_A);
      } else if (targetChannel == 'B') {
        releaseChannelHardware('B', configChannelB);
        configChannelB = newType;
        configureChannelHardware('B', newType, PINS_CHANNEL_B);
      }

      Serial.print("#OK,SET,"); Serial.print(targetChannel); Serial.print(","); Serial.println(sensorTypeName);
    }
  }
}

void handleSerialCommunication() {
  static String inputBuffer = "";
  while (Serial.available() > 0) {
    char incomingChar = (char) Serial.read();
    if (incomingChar == '\n' || incomingChar == '\r') {
      if (inputBuffer.length() > 0) {
        processCommand(inputBuffer);
        inputBuffer = "";
      }
    } else {
      inputBuffer += incomingChar;
    }
  }
}

void sendDataPacket(char channel, int slot, long rawValue) {
  Serial.print("D,");
  Serial.print(millis());
  Serial.print(",");
  Serial.print(channel);
  Serial.print(",");
  Serial.print(slot);
  Serial.print(",");
  Serial.println(rawValue);
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

  for (int size = 2; size <= n; size *= 2) {
    int halfSize = size / 2;
    float angleStep = -2.0f * PI / size;
    for (int start = 0; start < n; start += size) {
      for (int k = 0; k < halfSize; k++) {
        float angle = angleStep * k;
        float wr = cosf(angle), wi = sinf(angle);
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
 *  auf int gerundet - spart deutlich Bandbreite gegenüber Floats (bei 512 Werten pro Bild sonst
 *  schnell ein Vielfaches an Übertragungszeit). */
void sendSpectrumPacket(char channelName, float *real, float *imag) {
  static const float FULL_SCALE = 8388607.0f; // 2^23 - 1, wie in der Software-Sensorklasse

  Serial.print("#SPEC,");
  Serial.print(channelName);
  Serial.print(",");
  Serial.print(SPECTRUM_OUTPUT_BINS);
  Serial.print(",");
  Serial.print(MIC_SAMPLE_RATE_HZ);

  for (int i = 0; i < SPECTRUM_OUTPUT_BINS; i++) {
    float magnitude = sqrtf(real[i] * real[i] + imag[i] * imag[i]) / SPECTRUM_FFT_SIZE;
    float amplitude = fmaxf(magnitude / FULL_SCALE, 1e-9f); // Division durch 0 im log10 vermeiden
    int dbTimes10 = (int) roundf(20.0f * log10f(amplitude) * 10.0f);
    Serial.print(",");
    Serial.print(dbTimes10);
  }
  Serial.println();
}

/** Nimmt {@link #SPECTRUM_FFT_SIZE} Samples vom Mikrofon des angegebenen Kanals auf, wendet ein
 *  Hann-Fenster an (reduziert den "Leckeffekt" durch den scharfen Rand des Ausschnitts, der sonst
 *  als zusätzliche, falsche Frequenzanteile im Spektrum erscheinen würde), berechnet per FFT das
 *  Amplitudenspektrum und verschickt es. Wird für Kanäle mit TYPE_MIC_SPECTRUM aufgerufen. */
void captureAndSendSpectrum(char channelName) {
  i2s_chan_handle_t handle = micHandleForChannel(channelName);
  if (handle == NULL) {
    reportSensorError(channelName, "I2S");
    return;
  }

  static int32_t rawBuffer[SPECTRUM_FFT_SIZE];
  size_t bytesRead = 0;
  esp_err_t err = i2s_channel_read(handle, rawBuffer, sizeof(rawBuffer), &bytesRead, pdMS_TO_TICKS(200));
  int sampleCount = bytesRead / sizeof(int32_t);
  if (err != ESP_OK || sampleCount < SPECTRUM_FFT_SIZE) {
    reportSensorError(channelName, "I2S");
    return;
  }

  // real/imag als "static" statt lokal: 2 * 1024 * 4 Byte wären auf dem Stack des Loop-Tasks
  // riskant knapp (Standard-Stackgröße bei Arduino-ESP32 8 KB) - im BSS-Bereich unkritisch.
  static float real[SPECTRUM_FFT_SIZE];
  static float imag[SPECTRUM_FFT_SIZE];

  for (int i = 0; i < SPECTRUM_FFT_SIZE; i++) {
    int32_t sample = rawBuffer[i] >> 8; // 24 gültige Bits linksbündig, siehe sampleMicrophone
    float window = 0.5f - 0.5f * cosf(2.0f * PI * i / (SPECTRUM_FFT_SIZE - 1)); // Hann-Fenster
    real[i] = sample * window;
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

  esp_err_t err = i2s_channel_read(handle, buffer, samplesToRead * sizeof(int32_t), &bytesRead, pdMS_TO_TICKS(50));
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
  } else if (type == TYPE_INA219) {
    TwoWire &bus = busForChannel(channelName);
    uint16_t rawVoltage, rawCurrent;
    bool okVoltage = readI2CRegister16(bus, ADDR_INA219, 0x02, rawVoltage);
    if (okVoltage) {
      sendDataPacket(channelName, 0, rawVoltage);
    } else {
      reportSensorError(channelName, "I2C");
    }
    bool okCurrent = readI2CRegister16(bus, ADDR_INA219, 0x04, rawCurrent);
    if (okCurrent) {
      sendDataPacket(channelName, 1, rawCurrent);
    } else {
      reportSensorError(channelName, "I2C");
    }
    noteI2CResult(channelName, type, okVoltage && okCurrent);
  } else if (type == TYPE_VEML7700) {
    TwoWire &bus = busForChannel(channelName);
    uint16_t rawLux;
    bool okLux = readI2CRegister16LE(bus, ADDR_VEML7700, 0x04, rawLux);
    if (okLux) {
      sendDataPacket(channelName, 0, rawLux);
    } else {
      reportSensorError(channelName, "I2C");
    }
    noteI2CResult(channelName, type, okLux);
  } else if (type == TYPE_HX711) {
    long rawWeight;
    if (readHX711(pins[0], pins[1], rawWeight)) {
      sendDataPacket(channelName, 0, rawWeight);
    } else {
      reportSensorError(channelName, "HX711");
    }
  } else if (type == TYPE_HALL) {
    // Kein Übertragungsfehler möglich wie bei I2C/HX711 - digitalRead() liefert immer einen
    // Wert. Die Umrechnung "0/1 -> Magnetfeld ja/nein" (inkl. Invertierung, da das Modul
    // active-low ist) übernimmt bewusst erst die Java-Seite (HallEffectSensor.decode), wie bei
    // allen anderen Sensoren auch - die Firmware kennt nur Rohwerte.
    int rawState = digitalRead(pins[0]);
    sendDataPacket(channelName, 0, rawState);
  } else if (type == TYPE_MICROPHONE) {
    sampleMicrophone(channelName);
  } else if (type == TYPE_MIC_SPECTRUM) {
    // Bewusst kein Aufruf hier: das Spektrum braucht eine eigene, von der normalen Abtastrate
    // unabhängige Taktung (SPECTRUM_INTERVAL_MS) und wird deshalb direkt in loop() behandelt.
  }
}

void setup() {
  Serial.begin(BAUD_RATE);
  delay(200);

  // Bewusst KEINE Pin-/Bus-Initialisierung hier: welche Rolle die drei Kanal-Pins spielen,
  // hängt vom gewählten Sensortyp ab und wird erst bei SET über configureChannelHardware()
  // hergestellt - beide Kanäle starten unkonfiguriert bei TYPE_NONE.
  Serial.println("#HELLO,PhyLog-ESP32,fw=7.9");
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
  if (configChannelA == TYPE_MIC_SPECTRUM && currentTimeMs - lastSpectrumTimeMsA >= SPECTRUM_INTERVAL_MS) {
    lastSpectrumTimeMsA = currentTimeMs;
    captureAndSendSpectrum('A');
  }
  if (configChannelB == TYPE_MIC_SPECTRUM && currentTimeMs - lastSpectrumTimeMsB >= SPECTRUM_INTERVAL_MS) {
    lastSpectrumTimeMsB = currentTimeMs;
    captureAndSendSpectrum('B');
  }
}
