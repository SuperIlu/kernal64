package ucesoft.cbm.peripheral.sid

import ucesoft.cbm.Chip
import ucesoft.cbm.ChipID

abstract class SIDx(override val startAddress: Int = 0xd400) extends Chip with SIDDevice {
  val id = ChipID.SID
  val name = "SID"
  val length = 1024
  val isRom = false

  val isActive = true

  def setStereo(isStereo: Boolean)
  def setModel(is6581: Boolean)
  def setMouseEnabled(enabled: Boolean)
}