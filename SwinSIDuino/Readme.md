# Contents
This folder contains the schematics, the Arduino-Sketch and this Readme.

# nano SwinSID
A tutorial to build one can be found here: http://www.tolaemon.com/nss/

# schematics
Please note that the speaker in the schematics is a placeholder for "audio out". I didn't actually connect a speaker directly to the SwinSID.
The wiring of the data lines is a little weird because I was having problems when I first tried this and after it worked I was to lazy to connect D) and D1 back to arduino pins 2 and 3 again.

# Arduino sketch
The arduino is connected via USB and uses a baudrate of 1Mbaud. This works for my Arduino clone.
The baudrate is hardcoded in the sketch and in the patched kernal64.

## Commands available on the serial connection
### "R\n"
Reset the SwinSID

### "XXYY\n"
Write YY to register XX with XX and YY being two digit hex-strings.

# Running kernal64 with SwinSIDuino
You need to add "-DSIDUINO=COMx" with COMx being the COM port where the Arduino is connected (e.g. COM4). The start-scripts in the ../build directory are already modified to use COM4.
