package ucesoft.cbm.peripheral.bus

import ucesoft.cbm.Log

class BusSnoop(bus:IECBus) extends IECBusDevice(bus,0xFF) {
  val busid = "BusSnoop"
  // register itself to bus
  bus.registerListener(this)
   
  protected def isDeviceReady = true
  protected def loadData(fileName:String) = None
  
  override protected def onCommand(cmd:IECBusDevice.Command.Value,secondaryAddress:Int) {
    Log.info("Bus Snoop: %10s %d".format(cmd.toString,secondaryAddress))
  }
  override protected def byteJustRead(byte:Int,isLast:Boolean) {
    Log.info(s"Bus Snoop: byte read ${Integer.toHexString(byte)}")
  }
}