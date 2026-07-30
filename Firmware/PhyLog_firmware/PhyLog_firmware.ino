/*
 * PhyLog ESP32 Firmware v7.3
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
 */

#include <Wire.h>
#include <driver/i2s_std.h>

enum SensorType {
  TYPE_NONE = 0,
  TYPE_ANALOG = 1,
  TYPE_INA219 = 2,
  TYPE_VEML7700 = 3,
  TYPE_HX711 = 4,
  TYPE_MICROPHONE = 5
};

SensorType configChannelA = TYPE_NONE;
SensorType configChannelB = TYPE_NONE;

/** Die drei Signal-Pins eines Kanal-Ports (Steckerposition 2, 3, 4 - siehe pinout.md), deren
 *  Rolle vom gewählten Sensortyp abhängt (siehe {@link #configureChannelHardware}):
 *  I2C: [0]=SDA, [1]=SCL   HX711: [0]=DOUT, [1]=SCK   I2S: [0]=WS, [1]=BCLK, [2]=SD
 *  Analog: [0]=Eingang */
const int PINS_CHANNEL_A[3] = {13, 12, 14};
const int PINS_CHANNEL_B[3] = {27, 26, 25};

const uint8_t ADDR_INA219   = 0x40;
const uint8_t ADDR_VEML7700 = 0x10;

/** Maximale Wartezeit in ms auf ein bereites HX711-Modul, bevor der Zyklus als Fehler gilt. */
const unsigned long HX711_TIMEOUT_MS = 100;

/** I2S-Abtastrate für das INMP441-Mikrofon und wie viele 32-Bit-Samples je Zyklus gelesen
 *  werden, um daraus einen einzelnen Spitzenwert zu bilden (siehe {@link #sampleMicrophone}). */
const int MIC_SAMPLE_RATE_HZ = 16000;
const int MIC_READ_SAMPLES = 256;

bool isStreaming = false;
unsigned long sampleIntervalMs = 50; // Standard: 20 Hz
unsigned long lastSampleTimeMs = 0;

/** Letzter Zeitpunkt einer gemeldeten Fehlermeldung je Kanal, um das serielle Log bei
 *  dauerhaften Fehlern nicht mit Meldungen zu fluten (siehe {@link #reportSensorError}). */
unsigned long lastErrorReportMsA = 0;
unsigned long lastErrorReportMsB = 0;

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
    bus.write(0x00); bus.write(0x00); // ALS enable, Gain 1, IT 100ms
    ok &= (bus.endTransmission() == 0);
  }

  if (!ok) {
    reportSensorError(channelName, "I2C");
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
  } else if (oldType == TYPE_MICROPHONE) {
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
    case TYPE_MICROPHONE:
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
    Serial.println("#HELLO,PhyLog-ESP32,fw=7.3");
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

/** Liest einen kurzen Block Rohsamples vom INMP441 und bildet daraus den Spitzenbetrag
 *  (Peak-Amplitude) - ein einzelner Wert pro Aufrufzyklus, genau wie bei allen anderen
 *  Sensortypen. Das hält das serielle Protokoll unverändert (ein Datenpaket pro Kanal und
 *  Intervall) - die hohe I2S-Abtastrate bleibt intern und wird nicht Sample für Sample über die
 *  serielle Verbindung geschickt, was bei 115200 Baud ohnehin nicht möglich wäre. */
void sampleMicrophone(char channelName) {
  i2s_chan_handle_t handle = micHandleForChannel(channelName);
  if (handle == NULL) {
    reportSensorError(channelName, "I2S");
    return;
  }

  int32_t buffer[MIC_READ_SAMPLES];
  size_t bytesRead = 0;

  esp_err_t err = i2s_channel_read(handle, buffer, sizeof(buffer), &bytesRead, pdMS_TO_TICKS(20));
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
    if (readI2CRegister16(bus, ADDR_INA219, 0x02, rawVoltage)) {
      sendDataPacket(channelName, 0, rawVoltage);
    } else {
      reportSensorError(channelName, "I2C");
    }
    if (readI2CRegister16(bus, ADDR_INA219, 0x04, rawCurrent)) {
      sendDataPacket(channelName, 1, rawCurrent);
    } else {
      reportSensorError(channelName, "I2C");
    }
  } else if (type == TYPE_VEML7700) {
    TwoWire &bus = busForChannel(channelName);
    uint16_t rawLux;
    if (readI2CRegister16LE(bus, ADDR_VEML7700, 0x04, rawLux)) {
      sendDataPacket(channelName, 0, rawLux);
    } else {
      reportSensorError(channelName, "I2C");
    }
  } else if (type == TYPE_HX711) {
    long rawWeight;
    if (readHX711(pins[0], pins[1], rawWeight)) {
      sendDataPacket(channelName, 0, rawWeight);
    } else {
      reportSensorError(channelName, "HX711");
    }
  } else if (type == TYPE_MICROPHONE) {
    sampleMicrophone(channelName);
  }
}

void setup() {
  Serial.begin(115200);
  delay(200);

  // Bewusst KEINE Pin-/Bus-Initialisierung hier: welche Rolle die drei Kanal-Pins spielen,
  // hängt vom gewählten Sensortyp ab und wird erst bei SET über configureChannelHardware()
  // hergestellt - beide Kanäle starten unkonfiguriert bei TYPE_NONE.
  Serial.println("#HELLO,PhyLog-ESP32,fw=7.3");
}

void loop() {
  handleSerialCommunication();

  if (isStreaming) {
    unsigned long currentTimeMs = millis();
    if (currentTimeMs - lastSampleTimeMs >= sampleIntervalMs) {
      lastSampleTimeMs = currentTimeMs;
      sampleChannel('A', configChannelA, PINS_CHANNEL_A);
      sampleChannel('B', configChannelB, PINS_CHANNEL_B);
    }
  }
}
