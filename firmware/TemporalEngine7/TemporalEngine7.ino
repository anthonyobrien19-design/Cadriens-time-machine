/*
  ============================================================================
   TEMPORAL ENGINE No. 7  —  ESP32 firmware for Eli's Time Machine
  ============================================================================
   Board:   ESP32 Dev Module (AYWHP 38-pin, ESP32-WROOM-32, CP2102)
   Talks to the Time Machine app on the phone over Bluetooth Low Energy.
   No Wi-Fi is ever turned on.

   Arduino IDE settings:
     Board:            ESP32 Dev Module   (esp32 by Espressif Systems, 3.x)
     Partition Scheme: Huge APP (3MB No OTA/1MB SPIFFS)    <-- needed for Bluetooth
     Library:          Adafruit NeoPixel  (Library Manager)

   Serial Monitor (115200 baud, "Newline") commands:
     help            list commands
     status          show every input live (type again to stop)
     test            run every output once (LEDs, neons, gauge, rumble)
     gauge 0..100    hold the gauge needle at a value (for the trimmer)
     sync 2026       tell the firmware what the drums show right now
     flip 1..5       reverse the direction of drum 1-4 or the valve (5)
     clicks 1|2|4    encoder steps per click (if drums move 2 digits per click, etc.)
     pulses N        crank magnet pulses for a full charge (default 36)
     launch          pretend the knife switch was thrown (needs full charge)
     charge          fill the pressure to 100%
  ============================================================================ */

#include <Arduino.h>
#include <Preferences.h>
#include <Adafruit_NeoPixel.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ----------------------------------------------------------------------------
//  PIN PLAN  (matches the wiring guide)
// ----------------------------------------------------------------------------
// Year drums: rotary encoders (A, B). Drum 1 = thousands ... drum 4 = ones.
// Pins 34, 35, 36, 39 are input-only with NO internal pull-ups: they need 10 kΩ to 3.3 V.
const uint8_t ENC_A[5] = {36, 34, 32, 27, 16};   // drums 1-4, then the valve wheel
const uint8_t ENC_B[5] = {39, 35, 33, 14, 17};
const bool    ENC_NEEDS_EXTERNAL_PULLUP[5] = {true, true, false, false, false};

const uint8_t PIN_CRANK_HALL = 13;   // A3144 output, 10 kΩ pull-up to 3.3 V (LOW when a magnet passes)
const uint8_t PIN_KNIFE      = 4;    // roller microswitch: LOW = knife switch thrown (launch)
const uint8_t PIN_ABORT      = 18;   // mushroom button:    LOW = pressed
const uint8_t PIN_LEVER_UP   = 19;   // ON-OFF-ON toggle:   LOW = lever up
const uint8_t PIN_LEVER_DOWN = 21;   //                     LOW = lever down (middle = neither)
const uint8_t PIN_AUX        = 22;   // spare roller microswitch (reported to the app, optional)

const uint8_t PIN_LEDS       = 23;   // WS2812: orb ring (12) first, then drum strip (8)
const uint8_t PIN_GAUGE      = 25;   // DAC1 -> trimmer -> panel meter
const uint8_t PIN_RUMBLE     = 26;   // IRLZ44N gate (both rumble motors)
const uint8_t PIN_NEON[3]    = {5, 15, 2};   // MPSA42 bases: bottom, middle, top neon lamps

// ----------------------------------------------------------------------------
//  SETTINGS  (most can be changed from the Serial Monitor and are remembered)
// ----------------------------------------------------------------------------
const uint8_t  ORB_LEDS        = 12;
const uint8_t  DRUM_LEDS       = 8;      // 2 per drum
const uint8_t  LED_BRIGHTNESS  = 140;    // 0-255; keep below ~160 to stay cool and inside the 5 V budget
const uint8_t  RUMBLE_MAX      = 155;    // PWM 0-255; ~60% matches the Xbox motors on 5 V
const uint32_t WARP_FAILSAFE_MS = 9000;  // end warp effects if the app never says "arrived"
const char*    BLE_NAME        = "TemporalEngine7";

// Nordic UART Service (a common "serial over Bluetooth" layout)
#define UART_SERVICE "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define UART_RX      "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"   // phone -> engine
#define UART_TX      "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"   // engine -> phone

// ----------------------------------------------------------------------------
Adafruit_NeoPixel leds(ORB_LEDS + DRUM_LEDS, PIN_LEDS, NEO_GRB + NEO_KHZ800);
Preferences prefs;

BLECharacteristic* txChar = nullptr;
volatile bool phoneConnected = false;
volatile bool restartAdvertising = false;
String rxBuffer;                // lines from the phone (filled in the BLE task)
portMUX_TYPE rxMux = portMUX_INITIALIZER_UNLOCKED;

// Encoders
int8_t  encDigit[5];            // drums: 0-9.  [4] = valve (unused digit)
int8_t  encAccum[5] = {0};
uint8_t encPrev[5]  = {0};
int8_t  encDir[5]   = {1, 1, 1, 1, 1};
uint8_t stepsPerClick = 4;      // EC11: usually 4 quadrature steps per click
uint8_t clicksPerDigit = 2;     // 20-click encoder = 2 clicks per digit; 30-click = 3
int8_t  drumClickCount[4] = {0};

// Engine state
enum Mode { IDLE, WARP, FIZZLE, ABORTED, ARRIVED_FX };
Mode     mode = IDLE;
uint32_t modeStart = 0;
float    pressure = 0;          // 0-100 %
uint16_t pulsesToFull = 36;     // 6 magnets x 6 gear turns
uint32_t lastCrankMs = 0;
bool     knifeWasDown = false;
bool     abortWasDown = false;
int8_t   lastLever = -1;
bool     lastAux = false;
bool     hallWasLow = false;
uint32_t lastYearSentMs = 0;
int      lastYearSent = -1;
bool     yearDirty = false;
uint32_t drumsSaveAt = 0;
uint32_t lastVentMsg = 0;
bool     narratorTalking = false;
int      gaugeHold = -1;        // serial "gauge N"
bool     statusMode = false;

// functions used before they are defined (declared here so every Arduino IDE version compiles it)
void flashDrum(uint8_t i);
void blinkRed();
void tryLaunch();
void startMode(Mode m);
void sendFullState();
uint32_t lastStatusMs = 0;

// ============================================================================
//  Bluetooth
// ============================================================================
class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer*) override { phoneConnected = true; }
  void onDisconnect(BLEServer*) override { phoneConnected = false; restartAdvertising = true; }
};
class RxCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    auto v = c->getValue();                       // String in core 3.x, std::string in 2.x
    String s = String(v.c_str());
    portENTER_CRITICAL(&rxMux);
    rxBuffer += s;
    if (rxBuffer.length() > 512) rxBuffer.remove(0, rxBuffer.length() - 512);
    portEXIT_CRITICAL(&rxMux);
  }
};

void sendToPhone(const String& msg) {
  Serial.print(F("-> phone: ")); Serial.println(msg);
  if (!phoneConnected || !txChar) return;
  String line = msg + "\n";
  txChar->setValue((uint8_t*)line.c_str(), line.length());
  txChar->notify();
}

void setupBluetooth() {
  BLEDevice::init(BLE_NAME);
  BLEServer* server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  BLEService* svc = server->createService(UART_SERVICE);
  txChar = svc->createCharacteristic(UART_TX, BLECharacteristic::PROPERTY_NOTIFY);
  txChar->addDescriptor(new BLE2902());
  BLECharacteristic* rx = svc->createCharacteristic(UART_RX,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  rx->setCallbacks(new RxCallbacks());
  svc->start();
  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(UART_SERVICE);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
}

// ============================================================================
//  Inputs
// ============================================================================
int currentYear() {
  return encDigit[0] * 1000 + encDigit[1] * 100 + encDigit[2] * 10 + encDigit[3];
}
int8_t leverPosition() {            // 0 = up, 1 = middle, 2 = down
  bool up = digitalRead(PIN_LEVER_UP) == LOW, down = digitalRead(PIN_LEVER_DOWN) == LOW;
  return up ? 0 : (down ? 2 : 1);
}

// Quadrature decoder: valid transitions give +1 / -1, bounces give 0.
const int8_t QDEC[16] = {0, -1, 1, 0, 1, 0, 0, -1, -1, 0, 0, 1, 0, 1, -1, 0};

void pollEncoders() {
  for (uint8_t i = 0; i < 5; i++) {
    uint8_t now = (digitalRead(ENC_A[i]) << 1) | digitalRead(ENC_B[i]);
    if (now == encPrev[i]) continue;
    encAccum[i] += QDEC[(encPrev[i] << 2) | now];
    encPrev[i] = now;
    if (abs(encAccum[i]) < stepsPerClick) continue;
    int8_t click = (encAccum[i] > 0 ? 1 : -1) * encDir[i];
    encAccum[i] = 0;

    if (i < 4) {                                   // a year drum moved one click
      drumClickCount[i] += click;
      if (abs(drumClickCount[i]) >= clicksPerDigit) {
        int8_t step = drumClickCount[i] > 0 ? 1 : -1;
        drumClickCount[i] = 0;
        encDigit[i] = (encDigit[i] + step + 10) % 10;
        yearDirty = true;
        drumsSaveAt = millis() + 2000;            // save a moment later (saves flash wear)
        flashDrum(i);
      }
    } else {                                       // valve wheel: vent the pressure
      if (pressure > 0 && mode == IDLE) {
        pressure = max(0.0f, pressure - 6.0f);
        if (millis() - lastVentMsg > 1200) { sendToPhone("V"); lastVentMsg = millis(); }
      }
    }
  }
}

void pollButtons() {
  // Crank: count magnet passes (falling edge)
  bool hallLow = digitalRead(PIN_CRANK_HALL) == LOW;
  if (hallLow && !hallWasLow && mode == IDLE) {
    pressure = min(100.0f, pressure + 100.0f / pulsesToFull);
    lastCrankMs = millis();
    static uint8_t lastStep = 0;
    uint8_t step = (uint8_t)(pressure / 10);
    if (step != lastStep) { lastStep = step; sendToPhone("P:" + String((int)pressure)); }
    if (pressure >= 100) sendToPhone("P:100");
  }
  hallWasLow = hallLow;

  // Knife switch: launch on the moment it is thrown
  bool knifeDown = digitalRead(PIN_KNIFE) == LOW;
  if (knifeDown && !knifeWasDown) {
    delay(15);
    if (digitalRead(PIN_KNIFE) == LOW) {
      sendToPhone("K:1");
      tryLaunch();
    }
  } else if (!knifeDown && knifeWasDown) {
    sendToPhone("K:0");
  }
  knifeWasDown = knifeDown;

  // Abort button
  bool abortDown = digitalRead(PIN_ABORT) == LOW;
  if (abortDown && !abortWasDown) {
    delay(15);
    if (digitalRead(PIN_ABORT) == LOW) {
      sendToPhone("A");
      if (mode == WARP) startMode(ABORTED);
      else blinkRed();
    }
  }
  abortWasDown = abortDown;

  // Voice lever
  int8_t lever = leverPosition();
  if (lever != lastLever) { lastLever = lever; sendToPhone("S:" + String(lever)); }

  // Spare switch
  bool aux = digitalRead(PIN_AUX) == LOW;
  if (aux != lastAux) { lastAux = aux; sendToPhone(String("X:") + (aux ? "1" : "0")); }
}

void tryLaunch() {
  if (mode != IDLE) return;
  if (pressure < 99.5f || !phoneConnected) {      // not enough charge (or no phone): fizzle
    startMode(FIZZLE);
    sendToPhone(pressure < 99.5f ? "F" : "F:nolink");
    return;
  }
  sendToPhone("L:" + String(currentYear()));      // the app answers W:1 (go) or N (refused)
}

// ============================================================================
//  Messages from the phone
//    W:1   warp has started        W:0   arrived
//    N     launch refused (year out of range, app busy...)
//    T:1   narrator talking        T:0   narrator stopped
//    HELLO app connected; engine replies with its full state
// ============================================================================
void handlePhoneLine(String line) {
  line.trim();
  if (!line.length()) return;
  Serial.print(F("<- phone: ")); Serial.println(line);
  if (line == "W:1")       startMode(WARP);
  else if (line == "W:0")  { if (mode == WARP) startMode(ARRIVED_FX); }
  else if (line == "N")    startMode(FIZZLE);
  else if (line == "T:1")  narratorTalking = true;
  else if (line == "T:0")  narratorTalking = false;
  else if (line == "HELLO") sendFullState();
}

void sendFullState() {
  sendToPhone("Y:" + String(currentYear()));
  sendToPhone("P:" + String((int)pressure));
  sendToPhone("S:" + String(leverPosition()));
  sendToPhone(String("K:") + (digitalRead(PIN_KNIFE) == LOW ? "1" : "0"));
  lastYearSent = currentYear();
}

void readPhone() {
  String chunk;
  portENTER_CRITICAL(&rxMux);
  chunk = rxBuffer; rxBuffer = "";
  portEXIT_CRITICAL(&rxMux);
  static String pending;
  pending += chunk;
  int nl;
  while ((nl = pending.indexOf('\n')) >= 0) { handlePhoneLine(pending.substring(0, nl)); pending.remove(0, nl + 1); }
  if (pending.length() > 64 && pending.indexOf('\n') < 0) { handlePhoneLine(pending); pending = ""; }
}

// ============================================================================
//  Outputs and effects
// ============================================================================
void setNeons(bool a, bool b, bool c) {
  digitalWrite(PIN_NEON[0], a); digitalWrite(PIN_NEON[1], b); digitalWrite(PIN_NEON[2], c);
}
void setRumble(uint8_t level) { ledcWrite(PIN_RUMBLE, map(level, 0, 255, 0, RUMBLE_MAX)); }
void setGauge(float pct) { dacWrite(PIN_GAUGE, (uint8_t)constrain(pct * 2.55f, 0, 255)); }

uint32_t drumFlashUntil[4] = {0};
void flashDrum(uint8_t i) { drumFlashUntil[i] = millis() + 180; }
uint32_t redBlinkUntil = 0;
void blinkRed() { redBlinkUntil = millis() + 300; }

void startMode(Mode m) {
  mode = m; modeStart = millis();
  if (m == ARRIVED_FX) pressure = 0;
}

uint32_t orbColor(uint8_t r, uint8_t g, uint8_t b, float level) {
  return leds.Color(r * level, g * level, b * level);
}

void runEffects() {
  uint32_t now = millis(), t = now - modeStart;
  float gaugeShown = pressure;
  float wave = (sin(now / 700.0f) + 1) / 2;           // slow breathing 0..1

  switch (mode) {
    case IDLE: {
      // pressure slowly leaks if nobody cranks for a while
      if (pressure > 0 && now - lastCrankMs > 4000) pressure = max(0.0f, pressure - 0.04f);   // ~2% per second
      float lvl = 0.15f + 0.25f * wave + 0.6f * pressure / 100.0f;
      if (narratorTalking) lvl = 0.35f + 0.65f * ((sin(now / 90.0f) + 1) / 2);
      uint32_t base = phoneConnected ? orbColor(40, 210, 255, lvl)     // aether blue = linked
                                     : orbColor(255, 140, 20, lvl);    // amber = waiting for phone
      for (int i = 0; i < ORB_LEDS; i++) leds.setPixelColor(i, base);
      if (pressure >= 100) {                                           // full: a spark runs round
        int spark = (now / 60) % ORB_LEDS;
        leds.setPixelColor(spark, leds.Color(255, 255, 255));
      }
      setNeons(pressure >= 33, pressure >= 66, pressure >= 99.5f);
      setRumble(0);
      break;
    }
    case WARP: {                                         // ~3.5 s: spin, flicker, rumble rises
      float p = min(1.0f, t / 3500.0f);
      int head = (now / max(15, (int)(70 - 55 * p))) % ORB_LEDS;
      for (int i = 0; i < ORB_LEDS; i++) {
        int d = (head - i + ORB_LEDS) % ORB_LEDS;
        float tail = d < 4 ? 1.0f - d * 0.25f : 0.08f;
        leds.setPixelColor(i, leds.Color(120 * tail + 135 * p * tail, 230 * tail, 255 * tail));
      }
      bool flick = random(100) < 30 + 60 * p;
      setNeons(flick || random(2), random(100) < 50 + 40 * p, flick);
      setRumble(90 + 165 * p);
      gaugeShown = 100 * (1 - p);
      if (t > WARP_FAILSAFE_MS) startMode(ARRIVED_FX);
      break;
    }
    case ARRIVED_FX: {                                   // white flash, then calm down
      float fade = max(0.0f, 1.0f - t / 900.0f);
      for (int i = 0; i < ORB_LEDS; i++) leds.setPixelColor(i, orbColor(255, 255, 255, fade));
      setNeons(t < 150, t < 250, t < 350);
      setRumble(t < 200 ? 255 : 0);
      gaugeShown = 0;
      if (t > 900) startMode(IDLE);
      break;
    }
    case FIZZLE:                                         // sputter: not enough power
    case ABORTED: {                                      // sputter-and-fail
      bool on = (t / 110) % 2 == 0 && random(10) > 2;
      for (int i = 0; i < ORB_LEDS; i++) leds.setPixelColor(i, on ? leds.Color(255, 40, 10) : 0);
      setNeons(on && random(2), on && random(2), false);
      setRumble(on ? 200 : 0);
      gaugeShown = pressure * (on ? 1.0f : 0.6f);
      if (t > (mode == ABORTED ? 1600u : 1100u)) {
        if (mode == ABORTED) pressure = 0;
        startMode(IDLE);
      }
      break;
    }
  }

  if (now < redBlinkUntil) for (int i = 0; i < ORB_LEDS; i++) leds.setPixelColor(i, leds.Color(255, 0, 0));

  // drum backlights: warm brass glow, flash brighter when a drum turns
  for (int d = 0; d < 4; d++) {
    bool flash = now < drumFlashUntil[d];
    uint32_t c = mode == WARP ? ((now / 80 + d) % 2 ? leds.Color(255, 200, 90) : leds.Color(60, 30, 5))
                              : flash ? leds.Color(255, 230, 160) : leds.Color(150, 85, 20);
    leds.setPixelColor(ORB_LEDS + d * 2, c);
    leds.setPixelColor(ORB_LEDS + d * 2 + 1, c);
  }
  leds.show();

  // gauge needle eases toward its target
  static float needle = 0;
  float target = gaugeHold >= 0 ? gaugeHold : gaugeShown;
  needle += (target - needle) * 0.12f;
  setGauge(needle);
}

// ============================================================================
//  Saving drum positions (so the drums and the firmware agree after power-off)
// ============================================================================
void loadSettings() {
  prefs.begin("engine", false);
  static const int8_t firstYear[4] = {2, 0, 2, 6};   // drums are assumed to show 2026 the very first time
  for (int i = 0; i < 4; i++) encDigit[i] = prefs.getChar(("d" + String(i)).c_str(), firstYear[i]);
  for (int i = 0; i < 5; i++) encDir[i] = prefs.getChar(("f" + String(i)).c_str(), 1);
  stepsPerClick  = prefs.getUChar("spc", 4);
  clicksPerDigit = prefs.getUChar("cpd", 2);
  pulsesToFull   = prefs.getUShort("ptf", 36);
}
void saveDrums() {
  for (int i = 0; i < 4; i++) prefs.putChar(("d" + String(i)).c_str(), encDigit[i]);
}

// ============================================================================
//  Serial Monitor commands (testing and calibration)
// ============================================================================
void printHelp() {
  Serial.println(F("\nCommands: help | status | test | gauge 0-100 | gauge off | sync YYYY | flip 1-5 |"
                   " clicks 1|2|4 | digit 1|2|3 | pulses N | charge | launch"));
}

static const char* LEVER_NAMES[3] = {"UP", "middle", "DOWN"};
void printStatus() {
  Serial.printf("Year %d (drums %d %d %d %d) | pressure %3d%% | crank %s | knife %s | abort %s | lever %s | aux %s | phone %s\n",
    currentYear(), encDigit[0], encDigit[1], encDigit[2], encDigit[3], (int)pressure,
    digitalRead(PIN_CRANK_HALL) == LOW ? "MAGNET" : "-",
    digitalRead(PIN_KNIFE) == LOW ? "THROWN" : "up",
    digitalRead(PIN_ABORT) == LOW ? "PRESSED" : "-",
    LEVER_NAMES[leverPosition()],
    digitalRead(PIN_AUX) == LOW ? "ON" : "-",
    phoneConnected ? "connected" : "not connected");
}

void runTest() {
  Serial.println(F("TEST: orb red, green, blue..."));
  uint32_t cols[3] = {leds.Color(255, 0, 0), leds.Color(0, 255, 0), leds.Color(0, 0, 255)};
  for (auto c : cols) { for (int i = 0; i < ORB_LEDS + DRUM_LEDS; i++) leds.setPixelColor(i, c); leds.show(); delay(600); }
  Serial.println(F("TEST: LEDs one by one (orb 1-12, then drums 13-20)"));
  for (int i = 0; i < ORB_LEDS + DRUM_LEDS; i++) { leds.clear(); leds.setPixelColor(i, leds.Color(255, 255, 255)); leds.show(); delay(150); }
  leds.clear(); leds.show();
  Serial.println(F("TEST: neon 1 (bottom), 2 (middle), 3 (top)"));
  for (int i = 0; i < 3; i++) { setNeons(i == 0, i == 1, i == 2); delay(900); }
  setNeons(false, false, false);
  Serial.println(F("TEST: gauge sweep 0 -> 100 -> 0"));
  for (int v = 0; v <= 100; v += 2) { setGauge(v); delay(25); }
  delay(500);
  for (int v = 100; v >= 0; v -= 2) { setGauge(v); delay(25); }
  Serial.println(F("TEST: rumble 1 second"));
  setRumble(255); delay(1000); setRumble(0);
  Serial.println(F("TEST done."));
}

void readSerial() {
  static String line;
  while (Serial.available()) {
    char ch = Serial.read();
    if (ch == '\r') continue;
    if (ch != '\n') { line += ch; continue; }
    line.trim(); String cmd = line; line = "";
    if (!cmd.length()) continue;
    String arg = cmd.indexOf(' ') > 0 ? cmd.substring(cmd.indexOf(' ') + 1) : "";
    String word = cmd.indexOf(' ') > 0 ? cmd.substring(0, cmd.indexOf(' ')) : cmd;
    word.toLowerCase();

    if (word == "help") printHelp();
    else if (word == "status") { statusMode = !statusMode; Serial.println(statusMode ? F("Live status ON (type status again to stop)") : F("Live status OFF")); }
    else if (word == "test") runTest();
    else if (word == "gauge") { gaugeHold = arg == "off" ? -1 : constrain(arg.toInt(), 0, 100); Serial.printf("Gauge held at %d (type 'gauge off' to release)\n", gaugeHold); }
    else if (word == "sync" && arg.length() == 4) {
      for (int i = 0; i < 4; i++) { encDigit[i] = arg[i] - '0'; drumClickCount[i] = 0; }
      saveDrums(); yearDirty = true; Serial.printf("Drums synced to %d\n", currentYear());
    }
    else if (word == "flip") { int n = arg.toInt() - 1; if (n >= 0 && n < 5) { encDir[n] = -encDir[n]; prefs.putChar(("f" + String(n)).c_str(), encDir[n]); Serial.printf("Encoder %d direction flipped\n", n + 1); } }
    else if (word == "clicks") { stepsPerClick = constrain(arg.toInt(), 1, 4); prefs.putUChar("spc", stepsPerClick); Serial.printf("Steps per click: %d\n", stepsPerClick); }
    else if (word == "digit") { clicksPerDigit = constrain(arg.toInt(), 1, 6); prefs.putUChar("cpd", clicksPerDigit); Serial.printf("Clicks per digit: %d\n", clicksPerDigit); }
    else if (word == "pulses") { pulsesToFull = constrain(arg.toInt(), 6, 600); prefs.putUShort("ptf", pulsesToFull); Serial.printf("Crank pulses for full charge: %d\n", pulsesToFull); }
    else if (word == "charge") { pressure = 100; lastCrankMs = millis(); Serial.println(F("Pressure 100%")); }
    else if (word == "launch") tryLaunch();
    else Serial.println(F("Unknown command. Type help."));
  }
}

// ============================================================================
void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.println(F("\n=== TEMPORAL ENGINE No. 7 ==="));

  for (int i = 0; i < 5; i++) {
    pinMode(ENC_A[i], ENC_NEEDS_EXTERNAL_PULLUP[i] ? INPUT : INPUT_PULLUP);
    pinMode(ENC_B[i], ENC_NEEDS_EXTERNAL_PULLUP[i] ? INPUT : INPUT_PULLUP);
    encPrev[i] = (digitalRead(ENC_A[i]) << 1) | digitalRead(ENC_B[i]);
  }
  pinMode(PIN_CRANK_HALL, INPUT);           // external 10 kΩ pull-up
  pinMode(PIN_KNIFE, INPUT_PULLUP);
  pinMode(PIN_ABORT, INPUT_PULLUP);
  pinMode(PIN_LEVER_UP, INPUT_PULLUP);
  pinMode(PIN_LEVER_DOWN, INPUT_PULLUP);
  pinMode(PIN_AUX, INPUT_PULLUP);
  for (int i = 0; i < 3; i++) { pinMode(PIN_NEON[i], OUTPUT); digitalWrite(PIN_NEON[i], LOW); }
  ledcAttach(PIN_RUMBLE, 20000, 8);         // 20 kHz: silent motor PWM
  setRumble(0);
  setGauge(0);

  loadSettings();
  knifeWasDown = digitalRead(PIN_KNIFE) == LOW;   // don't launch just because it's down at power-up
  lastLever = leverPosition();

  leds.begin();
  leds.setBrightness(LED_BRIGHTNESS);
  leds.clear(); leds.show();

  setupBluetooth();
  Serial.printf("Drums show %d. Bluetooth name: %s\n", currentYear(), BLE_NAME);
  printHelp();
}

void loop() {
  pollEncoders();
  pollButtons();
  readPhone();
  readSerial();

  uint32_t now = millis();
  if (yearDirty && now - lastYearSentMs > 120) {
    yearDirty = false; lastYearSentMs = now;
    if (currentYear() != lastYearSent) { lastYearSent = currentYear(); sendToPhone("Y:" + String(lastYearSent)); }
  }
  if (drumsSaveAt && now > drumsSaveAt) { drumsSaveAt = 0; saveDrums(); }

  if (restartAdvertising) { restartAdvertising = false; delay(300); BLEDevice::startAdvertising(); }

  static uint32_t lastFx = 0;
  if (now - lastFx >= 20) { lastFx = now; runEffects(); }      // 50 frames per second

  if (statusMode && now - lastStatusMs > 400) { lastStatusMs = now; printStatus(); }
}
