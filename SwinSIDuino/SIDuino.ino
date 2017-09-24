#define D0 12
#define D1 A5
#define D2 4
#define D3 5
#define D4 6
#define D5 7
#define D6 8
#define D7 9

#define RESET 11
#define RW 10
#define CS 13

#define V1_FREQ_LOW   0
#define V1_FREQ_HIGH  1
#define V1_PW_LOW     2
#define V1_PW_HIGH    3
#define V1_CONTROL    4
#define V1_ATT_DEC    5
#define V1_SUS_REL    6
#define V2_FREQ_LOW   7
#define V2_FREQ_HIGH  8
#define V2_PW_LOW     9
#define V2_PW_HIGH    10
#define V2_CONTROL    11
#define V2_ATT_DEC    12
#define V2_SUS_REL    13
#define V3_FREQ_LOW   14
#define V3_FREQ_HIGH  15
#define V3_PW_LOW     16
#define V3_PW_HIGH    17
#define V3_CONTROL    18
#define V3_ATT_DEC    19
#define V3_SUS_REL    20
#define FC_LOW        21
#define FC_HIGH       22
#define RES_FILT      23
#define MODE_VOL      24
#define POT_X         25
#define POT_Y         26
#define OSC_RAND      27
#define ENV3          28

int addr_lines[] = {A0, A1, A2, A3, A4};
int data_lines[] = {D0, D1, D2, D3, D4, D5, D6, D7};

void setup() {
  pinMode(RESET, OUTPUT);
  pinMode(CS, OUTPUT);
  pinMode(RW, OUTPUT);
  digitalWrite(RW, LOW);

  for (int i = 0; i < 5; i++) {
    pinMode(addr_lines[i], OUTPUT);
  }
  address(0);

  for (int i = 0; i < 8; i++) {
    pinMode(data_lines[i], OUTPUT);
  }
  data(0);

  latch();
  reset();

  Serial.begin(500000*2);
}

char buff[5];
unsigned int pos = 0;

void loop() {
  if (Serial.available()) {
    int ch = Serial.read();
    if (ch < 0) {
      return;
    }
    if (ch == '\n') {
      handle();
      pos = 0;
    } else if (pos < 5) {
      buff[pos] = ch;
      pos++;
    }
  }
}

void handle() {
  if (buff[0] == 'R') {
    reset();
  } else {
    unsigned int addr = (digit(buff[0]) << 4) | digit(buff[1]);
    unsigned int val = (digit(buff[2]) << 4) | digit(buff[3]);
    writeReg(addr, val);
  }
}

int digit(char d) {
  if (d >= '0' && d <= '9') {
    return d - '0';
  } else if (d >= 'a' && d <= 'f') {
    return d - 'a' + 10;
  } else if (d >= 'A' && d <= 'F') {
    return d - 'A' + 10;
  } else {
    return 0;
  }
}

void writeReg(unsigned int reg, unsigned int dat) {
  address(reg);
  data(dat);
  latch();
}

void address(unsigned int addr) {
  for (unsigned int i = 0; i < 5; i++) {
    digitalWrite(addr_lines[i], addr & (1 << i));
  }
}

void data(unsigned int dat) {
  for (unsigned int i = 0; i < 8; i++) {
    digitalWrite(data_lines[i], dat & (1 << i));
  }
}

void reset() {
  digitalWrite(RESET, HIGH);
  delay(1);
  digitalWrite(RESET, LOW);
  delay(1);
  digitalWrite(RESET, HIGH);
}

void latch() {
  digitalWrite(CS, LOW);
  delayMicroseconds(1);
  digitalWrite(CS, HIGH);
  delayMicroseconds(1);
}

