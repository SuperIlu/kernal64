#!/bin/bash

HOME=`dirname "$0"`
HOME=`cd "$HOME"; pwd -P`
LIB=$HOME/lib
ROMS=$HOME/roms
CP=$ROMS:$LIB/kernal64.jar:$LIB/jinput.jar:$LIB/scala-library.jar:$LIB/scala-parser-combinators_2.12-1.0.5.jar:$LIB/commons-net-3.3.jar:$LIB/dropbox-core-sdk-1.7.7.jar:$LIB/jackson-core-2.2.4.jar:$LIB/jssc-2.8.0.jar
if [ ! -x $JAVA_HOME/bin/java ]; then
        JAVA=java
else
        JAVA=$JAVA_HOME/bin/java
fi
# to add custom Kernals set the variable below adding inside quotes -Dkernal=<kernal file> -D1541kernal=<1541 kernal file>
# both kernal files must be placed under roms directory
# example: KERNALS_ROM="-Dkernal=jiffydos_kernal.bin -D1541kernal=jiffydos1541_kernal.bin"
KERNALS_ROMS=""
SID="-DSIDUINO=COM4"
$JAVA -server -Xms64M -Xmx128M -cp $CP -Djava.library.path=$LIB $KERNALS_ROMS $SID ucesoft.cbm.c64.C64 $*
