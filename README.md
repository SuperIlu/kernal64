[![Build Status](https://travis-ci.org/abbruzze/kernal64.svg?branch=master)](https://travis-ci.org/abbruzze/kernal64)

SwinSIDuino-Patch
========
This repository contains a patched version of kernal64 which adds support to use a nano SwinSID connected via an Arduino nano. See the SwinSIDuino folder for more info and schematics.

Kernal64 ver 1.4.0
========
![](https://github.com/abbruzze/kernal64/blob/master/images/c64.jpg)![](https://github.com/abbruzze/kernal64/blob/master/images/c128.jpg)

### What's new in 1.4.0 (Jul 14th 2017)
* New Commodore 128 emulator
* General refactoring and minor bug fixing
* Fix of severe bug on CIA when timer B counts timer A
* Added keyboard layout editor in order to customize keyboard mapping
* New scripts for launching emulators
* Scala 2.12 & jre 1.8

Main c128 emulator's features:
* Z80 and CP/M
* 2Mhz mode (still not cycle exact)
* new keys and keypad
* VDC support: interlace mode supported (known issues: 8x1 mode supported but still not working properly, interlaced color mode has some minor visualization problems)
* VICii (interlaced mode not supported)
* 1571 drive not yet implemented (JiffyDOS for 128 works fine)
* MMU panel to check Z80, C64 mode and 2Mhz use

<img src="https://github.com/abbruzze/kernal64/blob/master/images/c128_cpm.jpg" width="600" height="454" alt="Commodore 128 CP/M 3.0"/>
<img src="https://github.com/abbruzze/kernal64/blob/master/images/c128_interlaced.jpg" width="600" height="454" alt="Commodore 128 interlaced mono"/>
<img src="https://github.com/abbruzze/kernal64/blob/master/images/c128_rfo.jpg" width="600" height="454" alt="Commodore 128 Risen from oblivion demo"/>

### What's new in 1.3.1 (Dic 2nd 2016)
* RS-232 improvements. Added baud rate in the configuration parameters
* New ZIP format: now you can open directly zip files containing C64 files, like D64, PRG, CRT ...
* New "game jukebox" feature: a new plug & play components used to collect games, demos, intros, etc. from internet and play with them easily and immetiately. Out of the box you can find Gamebase (http://www.gb64.com/) component and Pouet demo (http://www.pouet.net/)  component.

<img src="https://github.com/abbruzze/kernal64/blob/master/images/pouet.jpg" width="724" height="427" alt="Pouet demos"/>
<img src="https://github.com/abbruzze/kernal64/blob/master/images/gamebase.jpg" width="724" height="427" alt="Gamebase"/>

### What's new in 1.3.0 (Sep 23th 2016)
* Fixed VIC xcoord bug: now sprites can be viewed properly on sideborders
* Fixed IRQ delay cycles that caused image flickering
* Fixed display size
* New feature: now it ca be possible to save and reload the emulator's state.

### What's new in 1.2.7 (Aug 15th 2016)
* Improved CPU & VIC timings. Now nufli images can be displayed properly as well as nuvie videos.

### What's new in 1.2.6 (May 26th 2016)
* Remoting (experimental) : share the emulator's screen with your friend on the network to play games in two-player mode
* Removed jiffydos env variable: now the user must provide the JiffyDOS ROMS

### What's new in 1.2.5 (May 5th 2016)
* Added support for [Flyer](http://www.retroswitch.com/products/flyer/) internet modem (see Wiki)
* Minor RS-232 improvements and fixes

### What's new in 1.2.4 (Apr 1st 2016)
* Added support for stereo dual SID chip

### What's new in 1.2.3 (Dic 6th 2015)
* Added support for EasyFlash cart: eprom flashing is not emulated

### What's new in 1.2.2
* Added support for dual drive (device 9)
* Local driver and Dropbox support moved to device 10 
* Added D64 extended format with errors

### What's new in 1.2.1
* Added support for **CP/M** cartridge (see wiki for details)

### A Scala Commodore 64 & 128 emulator

Written in the Scala language (http://www.scala-lang.org/) and available for any Java JRE 1.8 environment.

Here the main features:
* Cycle based: exact cycle emulation using the PAL frequency
* VIC emulation based on the Christian Bauer's article: "The MOS 6567/6569 video controller (VIC-II) and its application in the Commodore 64" and the VIC-Addendum.
* 6510 CPU full emulation (with illegal opcodes too). The user can choose between a cycle exact cpu and a faster cpu not cycle exact.
* CIA1, CIA2 chips emulation: almost full.
* IEC Serial Bus
* Keyboard: for key mapping I'll provide documentation
* SID chip: for this chip I used the RSID Java library by Ken Händel
* Joystick emulation for port 1 and 2: the joystick can be emulated via keypad or via an USB real joystick (thanks to jinput library)
* Commodore 1351 mouse emulation
* Light pen emulation
* Datassette: full emulation using TAP file (read/write)
* 1541 Drive: exact cycle emulation (read/write) using 6502 CPU (1Mhz). Supports D64 and G64. In the Settings menù it's possible to turn off the full drive emulation and turn on the faster one.
  Only the G64 format can be formatted by Dos, while for the D64, the emulator intercepts the format routine call and bypasses it, using a pre-formatted empty disk.
* Local drive emulation on device 10: choose a local directory and let it your drive 10.
* **Dropbox** drive emulation on device 10: connect your Dropbox account to Kernal64 and let access it using device 10!
* Cartridges emulation (some CRT types are not emulated). Supports CRT format.
* MPS803 printer emulation. Preview panel updated while printing.
* Fast program loading of PRG/T64 local file or D64's file entry.
* Debug panel for main CPU and for 1541's cpu (break point, step by step execution, disassembler, assembler, etc.)
* Component panel: shows all internal components while running
* Drag & Drop support
* REU support (128,256,512,16M)
* Support for external roms, both for C1541 kernal and C64 kernal. The roms must be put in the roms directory. Use the switches -Dkernal=rom name and -D1541_kernal=rom name (you can put JiffyDOS ROMS here, for example, to use the fastloader).
* Support for 1541-VIA1 <-> CIA2 parallel cable, used by many fastloaders. Tested on Speed Dos and Dolphin Dos 2.
* Support for 1541's expanded memory (tested with Dolphin Dos 2).
* RS-232 3-way UserPort implementations useful to connect with BBS on internet. The Telnet implementation can be used to connect to a telnet server (like BBSs); the TCP implementation can be used to connect with a generic TCP/IP server. The File implementation can be used to read/write to local files.
* RS-232 **SwiftLink** cartridge implementation. Tried with NovaTerm 9.6 and other terminal software. 
* ... and more

### Installation
Go to https://github.com/abbruzze/kernal64/tree/master/Kernal64/dist/kernal64_install.zip download and unzip on your computer.
Be sure to have a jre (1.8 or above) in the path and launch the **k64.bat** or **k64.sh** or **k128** equivalent for 128 emulator batch file, depending on your operating system.

The scripts start java with the -server option for performance reason. It could happen that your Java installation does not support the server option. In this case edit the script and remove the -server option.

If you want to load a 16M REU modify the memory settings inside the start scripts: for example, you could set -Xmx128M.

### Wiki
Wiki pages are available here: https://github.com/abbruzze/kernal64/wiki
