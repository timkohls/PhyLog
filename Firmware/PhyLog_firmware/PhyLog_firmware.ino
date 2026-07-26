/*
 * PhyLog ESP32 Firmware v6.1
 * Flexible Kanal-Konfiguration für I2C-Sensoren (INA219, VEML7700) und Analog-Pins.
 * Welcher Sensortyp auf Kanal A/B aktiv ist, wird ausschließlich über das serielle
 * Kommando SET,<Kanal>,<Typ> von der Software gesetzt (siehe GUI.applySensorSelectionToFirmware).
 * Beide Kanäle starten daher mit TYPE_NONE, bis die Software eine Auswahl sendet.
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

const int PINS_CHANNEL_A[2] = {13, 12}; // GPIO13 (SDA), GPIO12 (SCL)
const int PINS_CHANNEL_B[2] = {15, 2};  // GPIO15 (SDA/ADC1), GPIO2 (SCL/ADC2)

const uint8_t ADDR_INA219   = 0x40;
const uint8_t ADDR_VEML7700 = 0x10;

bool isStreaming = false;
unsigned long sampleIntervalMs = 50; // Standard: 20 Hz
unsigned long lastSampleTimeMs = 0;

/**
 * Liest ein 16-Bit-I2C-Register. Gibt bei Erfolg true zurück und schreibt den Rohwert nach
 * outValue; bei einem Übertragungsfehler wird false zurückgegeben und outValue nicht verändert.
 * Wichtig: früher gab diese Funktion im Fehlerfall stillschweigend 0 zurück, was am PC nicht von
 * einer echten Nullmessung zu unterscheiden war und als Phantom-Messwert in der Software landete.
 */
bool readI2CRegister16(uint8_t addr, uint8_t reg, int16_t &outValue) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) return false;

  if (Wire.requestFrom((int)addr, 2) != 2) return false;

  uint16_t raw = ((uint16_t)Wire.read() << 8) | Wire.read();
  outValue = (int16_t)raw;
  return true;
}

void setupI2CSensors() {
  if (configChannelA == TYPE_INA219 || configChannelB == TYPE_INA219) {
    Wire.beginTransmission(ADDR_INA219);
    Wire.write(0x00); // Config Register
    Wire.write(0x39); Wire.write(0x9F); // 32V, Gain 8, 12-bit ADC
    Wire.endTransmission();

    Wire.beginTransmission(ADDR_INA219);
    Wire.write(0x05); // Calibration Register
    Wire.write(0x10); Wire.write(0x00);
    Wire.endTransmission();
  }

  if (configChannelA == TYPE_VEML7700 || configChannelB == TYPE_VEML7700) {
    Wire.beginTransmission(ADDR_VEML7700);
    Wire.write(0x00); // ALS_CONF Register
    Wire.write(0x00); Wire.write(0x00); // ALS enable, Gain 1, IT 100ms
    Wire.endTransmission();
  }
}

void processCommand(String command) {
  command.trim();

  if (command.equalsIgnoreCase("PING")) {
    Serial.println("#HELLO,PhyScope-ESP32,fw=6.1");
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

/** Tastet den konfigurierten Sensor eines Kanals ab. Bei einem I2C-Fehler wird für das
 *  betroffene Register kein Datenpaket verschickt, statt einen falschen 0-Wert zu senden. */
void sampleChannel(char channelName, SensorType type, const int pins[2]) {
  if (type == TYPE_ANALOG) {
    int analogVal = analogRead(pins[0]);
    sendDataPacket(channelName, 0, analogVal);
  } else if (type == TYPE_INA219) {
    int16_t rawVoltage, rawCurrent;
    if (readI2CRegister16(ADDR_INA219, 0x02, rawVoltage)) {
      sendDataPacket(channelName, 0, rawVoltage);
    }
    if (readI2CRegister16(ADDR_INA219, 0x04, rawCurrent)) {
      sendDataPacket(channelName, 1, rawCurrent);
    }
  } else if (type == TYPE_VEML7700) {
    int16_t rawLux;
    if (readI2CRegister16(ADDR_VEML7700, 0x04, rawLux)) {
      sendDataPacket(channelName, 0, rawLux);
    }
  }
}

void setup() {
  Serial.begin(115200);
  delay(200);

  Wire.begin(PINS_CHANNEL_A[0], PINS_CHANNEL_A[1], 400000);

  setupI2CSensors();
  Serial.println("#HELLO,PhyScope-ESP32,fw=6.1");
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
