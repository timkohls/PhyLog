/*
 * PhyLog ESP32 Firmware v6.4
 * Flexible Kanal-Konfiguration für I2C-Sensoren (INA219, VEML7700) und Analog-Pins.
 * Welcher Sensortyp auf Kanal A/B aktiv ist, wird ausschließlich über das serielle
 * Kommando SET,<Kanal>,<Typ> von der Software gesetzt (siehe GUI.applySensorSelectionToFirmware).
 * Beide Kanäle starten daher mit TYPE_NONE, bis die Software eine Auswahl sendet.
 *
 * Kanal A und Kanal B hängen an PHYSISCH GETRENNTEN I2C-Bussen (Wire für A, Wire1 für B) - beide
 * haben eigene SDA/SCL-Pins, siehe PINS_CHANNEL_A/B. Das ist notwendig, sobald man auf beiden
 * Kanälen gleichzeitig einen I2C-Sensor (auch denselben Typ zweimal) betreiben will: ein einzelner
 * gemeinsamer Bus könnte einen an Kanal B angeschlossenen Sensor gar nicht erreichen.
 */

#include <Wire.h>

enum SensorType {
  TYPE_NONE = 0,
  TYPE_ANALOG = 1,
  TYPE_INA219 = 2,
  TYPE_VEML7700 = 3
};

SensorType configChannelA = TYPE_NONE;
SensorType configChannelB = TYPE_NONE;

const int PINS_CHANNEL_A[2] = {13, 12}; // GPIO13 (SDA), GPIO12 (SCL) - Bus "Wire"
const int PINS_CHANNEL_B[2] = {15, 2};  // GPIO15 (SDA), GPIO2 (SCL)  - Bus "Wire1"

const uint8_t ADDR_INA219   = 0x40;
const uint8_t ADDR_VEML7700 = 0x10;

bool isStreaming = false;
unsigned long sampleIntervalMs = 50; // Standard: 20 Hz
unsigned long lastSampleTimeMs = 0;

/** Letzter Zeitpunkt einer gemeldeten I2C-Fehlermeldung je Kanal, um das serielle Log bei
 *  dauerhaften Fehlern nicht mit Meldungen zu fluten (siehe {@link #reportI2CError}). */
unsigned long lastErrorReportMsA = 0;
unsigned long lastErrorReportMsB = 0;

/** Meldet einen fehlgeschlagenen I2C-Zugriff auf einen Kanal, höchstens einmal pro Sekunde je
 *  Kanal. Vorher wurde ein solcher Fehler komplett verschluckt (kein Datenpaket, aber auch keine
 *  Meldung) - dadurch war von der PC-Seite aus nicht zu unterscheiden, ob der Sensor gerade
 *  nichts Neues zu melden hat oder ob die I2C-Kommunikation grundsätzlich fehlschlägt. */
void reportI2CError(char channelName) {
  unsigned long &lastReport = (channelName == 'A') ? lastErrorReportMsA : lastErrorReportMsB;
  unsigned long now = millis();
  if (now - lastReport >= 1000) {
    lastReport = now;
    Serial.print("#ERR,I2C,");
    Serial.println(channelName);
  }
}

/** @return den I2C-Bus, der physisch zu diesem Kanal gehört (siehe Datei-Kommentar oben). */
TwoWire &busForChannel(char channelName) {
  return (channelName == 'A') ? Wire : Wire1;
}

/**
 * Liest ein 16-Bit-I2C-Register MSB-zuerst (big-endian, TI-Konvention - passend für den
 * INA219) über den angegebenen Bus. Gibt bei Erfolg true zurück und schreibt den Rohwert nach
 * outValue; bei einem Übertragungsfehler wird false zurückgegeben und outValue nicht verändert.
 *
 * <p>outValue ist bewusst uint16_t (nicht int16_t): der Rohwert wird unverändert als 0..65535
 * über die serielle Schnittstelle geschickt, die softwareseitige Sensor-Klasse entscheidet dann
 * je nach physikalischer Größe, ob und wie er vorzeichenbehaftet zu interpretieren ist (siehe
 * z. B. INA219CurrentSensor.decode in der Software).</p>
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

/** Schreibt die Init-Konfiguration eines Sensortyps auf den angegebenen Bus (No-Op für
 *  TYPE_NONE/TYPE_ANALOG, die keine I2C-Konfiguration brauchen). Meldet einen Fehler, falls
 *  einer der Schreibvorgänge fehlschlägt - das würde den Sensor unbrauchbar machen, noch bevor
 *  überhaupt eine Messung versucht wird. */
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
    reportI2CError(channelName);
  }
}

/** Konfiguriert beide Busse unabhängig - jeder Kanal bekommt nur den Init-Schreibvorgang für
 *  seinen EIGENEN Sensortyp, nicht den des anderen Kanals. */
void setupI2CSensors() {
  configureSensorOnBus(Wire, configChannelA, 'A');
  configureSensorOnBus(Wire1, configChannelB, 'B');
}

void processCommand(String command) {
  command.trim();

  if (command.equalsIgnoreCase("PING")) {
    Serial.println("#HELLO,PhyLog-ESP32,fw=6.4");
  } else if (command.equalsIgnoreCase("START")) {
    isStreaming = true;
    Serial.println("#OK,START");
  } else if (command.equalsIgnoreCase("STOP")) {
    isStreaming = false;
    Serial.println("#OK,STOP");
  } else if (command.startsWith("RATE,")) {
    long rateHz = command.substring(5).toInt();
    if (rateHz >= 1 && rateHz <= 200) {
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

      if (targetChannel == 'A') configChannelA = newType;
      else if (targetChannel == 'B') configChannelB = newType;

      setupI2CSensors();
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

/** Tastet den konfigurierten Sensor eines Kanals über dessen EIGENEN I2C-Bus ab (siehe
 *  {@link #busForChannel}). Bei einem I2C-Fehler wird für das betroffene Register kein
 *  Datenpaket verschickt (siehe {@link #reportI2CError}), statt einen falschen 0-Wert zu senden. */
void sampleChannel(char channelName, SensorType type, const int pins[2]) {
  if (type == TYPE_ANALOG) {
    int analogVal = analogRead(pins[0]);
    sendDataPacket(channelName, 0, analogVal);
  } else if (type == TYPE_INA219) {
    TwoWire &bus = busForChannel(channelName);
    uint16_t rawVoltage, rawCurrent;
    if (readI2CRegister16(bus, ADDR_INA219, 0x02, rawVoltage)) {
      sendDataPacket(channelName, 0, rawVoltage);
    } else {
      reportI2CError(channelName);
    }
    if (readI2CRegister16(bus, ADDR_INA219, 0x04, rawCurrent)) {
      sendDataPacket(channelName, 1, rawCurrent);
    } else {
      reportI2CError(channelName);
    }
  } else if (type == TYPE_VEML7700) {
    TwoWire &bus = busForChannel(channelName);
    uint16_t rawLux;
    if (readI2CRegister16LE(bus, ADDR_VEML7700, 0x04, rawLux)) {
      sendDataPacket(channelName, 0, rawLux);
    } else {
      reportI2CError(channelName);
    }
  }
}

void setup() {
  Serial.begin(115200);
  delay(200);

  Wire.begin(PINS_CHANNEL_A[0], PINS_CHANNEL_A[1], 400000);
  Wire1.begin(PINS_CHANNEL_B[0], PINS_CHANNEL_B[1], 400000);

  setupI2CSensors();
  Serial.println("#HELLO,PhyLog-ESP32,fw=6.4");
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
