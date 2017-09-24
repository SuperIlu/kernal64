package ucesoft.cbm.peripheral.sid

import java.io.ObjectInputStream
import java.io.ObjectOutputStream

import javax.swing.JFrame
import jssc.SerialPort
import ucesoft.cbm.ChipID

class SIDuino(port: String) extends SIDx {
  private[this] val REG_COUNT = 32;
  private[this] val regs: Array[Int] = new Array[Int](REG_COUNT);

  private[this] val com = {
    val com = new SerialPort(port);
    com.openPort();
    com.setParams(500000 * 2, SerialPort.DATABITS_8, SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);
    com.writeString("\n\n");
    System.out.println("SIDuino >>> opened " + port)
    com
  }

  def writeReg(reg: Int, v: Int) {
    val out = "%02X%02X\n".format(0xFF & reg, 0xFF & v);
    com.writeString(out);
    //System.out.println(out);
  }

  def setStereo(isStereo: Boolean) {
  }

  def getDriver = null
  def init = start
  def reset = {
    com.writeString("R\n");
    for (reg <- 0 to REG_COUNT) {
      writeReg(reg, 0);
    }
    System.out.println("SIDuino >>> RESET")
  }

  def setModel(is6581: Boolean) {
  }

  @inline private def decode(address: Int) = address & 0x1F

  def setMouseEnabled(enabled: Boolean) = {}

  final def read(address: Int, chipID: ChipID.ID) = {
    val dec = decode(address)
    regs(dec)
  }

  final def write(address: Int, value: Int, chipID: ChipID.ID) = {
    val dec = decode(address)
    regs(dec) = value
    writeReg(dec, value);
  }

  def stop = reset
  def start = reset

  def setFullSpeed(full: Boolean) = {
    if (full) stop else start
  }

  // state
  protected def saveState(out: ObjectOutputStream) {
  }
  protected def loadState(in: ObjectInputStream) {
  }
  protected def allowsStateRestoring(parent: JFrame): Boolean = false
}