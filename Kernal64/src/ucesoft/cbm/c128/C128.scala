package ucesoft.cbm.c128

import javax.swing._
import java.awt.event._
import ucesoft.cbm.game.GamePlayer
import ucesoft.cbm.trace._
import ucesoft.cbm._
import ucesoft.cbm.cpu._
import ucesoft.cbm.peripheral._
import ucesoft.cbm.misc._
import ucesoft.cbm.expansion._
import ucesoft.cbm.peripheral.drive._
import ucesoft.cbm.peripheral.rs232._
import ucesoft.cbm.util._
import java.awt._
import java.util.Properties
import java.io._
import ucesoft.cbm.formats._
import java.awt.datatransfer.DataFlavor
import scala.util.Success
import scala.util.Failure
import java.util.ServiceLoader
import ucesoft.cbm.game.GameUI
import ucesoft.cbm.peripheral.controlport.JoystickSettingDialog
import ucesoft.cbm.peripheral.bus.IECBusLine
import ucesoft.cbm.peripheral.bus.IECBus
import ucesoft.cbm.remote.RemoteC64

object C128 extends App {
  UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
  val c128 = new C128
  c128.run
  if (args.length > 0) {
    c128.handleDND(new File(args(0)))
  }
}

class C128 extends CBMComponent with ActionListener with GamePlayer with MMUChangeListener {
  val componentID = "Commodore 128"
  val componentType = CBMComponentType.INTERNAL
  
  private[this] val CONFIGURATION_FILENAME = "C128.config"
  private[this] val CONFIGURATION_LASTDISKDIR = "lastDiskDirectory"
  private[this] val CONFIGURATION_FRAME_XY = "frame.xy"  
  private[this] val CONFIGURATION_FRAME_DIM = "frame.dim"
  private[this] val CONFIGURATION_KEYB_MAP_FILE = "keyb.map.file"
  
  private[this] val configuration = {
    val props = new Properties
    val propsFile = new File(new File(scala.util.Properties.userHome),CONFIGURATION_FILENAME)
    if (propsFile.exists) {
      try {
        props.load(new FileReader(propsFile))
      }
      catch {
        case io:IOException =>
      }
    }
    props
  }
    
  private[this] val clock = Clock.setSystemClock(Some(errorHandler _))(mainLoop _)
  private[this] val mmu = new C128MMU(this)
  private[this] val cpu = CPU6510.make(mmu)  
  private[this] val z80 = new Z80(mmu,mmu)
  private[this] var cpuFrequency = 1
  private[this] var c64Mode = false
  private[this] var z80Active = true
  private[this] var clockSpeed = 1
  private[this] var vicChip : vic.VIC = _
  private[this] var cia1,cia2 : cia.CIA = _
  private[this] val vdc = new ucesoft.cbm.peripheral.vdc.VDC
  private[this] val sid = new ucesoft.cbm.peripheral.sid.SID
  private[this] var vicDisplay : vic.Display = _
  private[this] var vdcDisplay : vic.Display = _
  private[this] var vdcOnItsOwnThread = false
  private[this] val nmiSwitcher = new NMISwitcher(cpu.nmiRequest _)
  private[this] val irqSwitcher = new IRQSwitcher(irqRequest _)
  private[this] val keybMapper : keyboard.KeyboardMapper = keyboard.KeyboardMapperStore.loadMapper(Option(configuration.getProperty(CONFIGURATION_KEYB_MAP_FILE)),"/resources/default_keyboard_c128",C128KeyboardMapper)
  private[this] val keyb = new keyboard.Keyboard(keybMapper,nmiSwitcher.keyboardNMIAction _,true)	// key listener
  private[this] val vicDisplayFrame = {
    val f = new JFrame("Kernal128 emulator ver. " + ucesoft.cbm.Version.VERSION)
    f.addWindowListener(new WindowAdapter {
      override def windowClosing(e:WindowEvent) {
        close
      }
    })
    f.setIconImage(new ImageIcon(getClass.getResource("/resources/commodore.png")).getImage)    
    f
  }
  private[this] val vdcDisplayFrame = {
    val f = new JFrame("Kernal128 emulator ver. " + ucesoft.cbm.Version.VERSION) {
      override def getInsets = new Insets(0,0,0,0)
    }
    f.addWindowListener(new WindowAdapter {
      override def windowClosing(e:WindowEvent) {
        close
      }
    })
    f.setIconImage(new ImageIcon(getClass.getResource("/resources/commodore.png")).getImage)    
    f
  }
  // ----------------- REMOTE ------------------
  private[this] var remote : Option[RemoteC64] = None
  // ------------- MMU Status Panel ------------
  private[this] val mmuStatusPanel = new MMUStatusPanel
  // -------------- MENU ITEMS -----------------
  private[this] val maxSpeedItem = new JCheckBoxMenuItem("Maximum speed")
  private[this] val loadFileItems = Array(new JMenuItem("Load file from attached disk 8 ..."), new JMenuItem("Load file from attached disk 9 ..."))
  private[this] val tapeMenu = new JMenu("Tape control...")
  private[this] val detachCtrItem = new JMenuItem("Detach cartridge")
  private[this] val cartMenu = new JMenu("Cartridge")
  // -------------------------------------------
  private[this] val bus = new ucesoft.cbm.peripheral.bus.IECBus
  private[this] var baLow = false
  private[this] var dma = false
  private[this] val expansionPort = ExpansionPort.getExpansionPort
  // -------------------- TRACE ----------------
  private[this] var traceDialog : TraceDialog = _
  private[this] var diskTraceDialog : TraceDialog = _
  private[this] var inspectDialog : JDialog = _
  private[this] var traceItem,traceDiskItem : JCheckBoxMenuItem = _
  // -------------------- DISK -----------------
  private[this] var isDiskActive = true
  private[this] var isDiskActive9 = false
  private[this] var attachedDisks : Array[Option[Floppy]] = Array(None,None)
  private[this] val driveLeds = Array(new DriveLed,new DriveLed)
  private[this] val diskProgressPanels = Array(new DriveLoadProgressPanel,new DriveLoadProgressPanel)
  private[this] val c1541 = new C1541Emu(bus,DriveLed8Listener)
  private[this] val c1541_real = new C1541(0x00,bus,DriveLed8Listener)
  private[this] val c1541_real9 = new C1541(0x01,bus,DriveLed9Listener)
  private[this] val flyerIEC = new FlyerIEC(bus,file => attachDiskFile(0,file,false))
  private[this] var isFlyerEnabled = false
  private[this] var drives : Array[Drive] = Array(c1541_real,c1541_real9)
  private[this] var device10Drive : Drive = _
  private[this] var device10DriveEnabled = false
  private[this] var FSDIRasInput = true
  // -------------------- TAPE -----------------
  private[this] var datassette : c2n.Datassette = _
  // ----------------- RS-232 ------------------
  private[this] val rs232 = BridgeRS232
  private[this] var activeRs232 : Option[RS232] = None 
  private[this] val AVAILABLE_RS232 : Array[RS232] = Array(TelnetRS232,
                                                           TCPRS232,
                                                           FileRS232,
                                                           SwiftLink.getSL(nmiSwitcher.expansionPortNMI,None),
                                                           SwiftLink.getSL(nmiSwitcher.expansionPortNMI _,Some(REU.getREU(REU.REU_1750,mmu,setDMA _,irqSwitcher.expPortIRQ _,None))),
                                                           ProcessRS232)
  private[this] val rs232StatusPanel = new RS232StatusPanel
  // -------------------- PRINTER --------------
  private[this] var printerEnabled = false
  private[this] val printerGraphicsDriver = new ucesoft.cbm.peripheral.printer.MPS803GFXDriver(new ucesoft.cbm.peripheral.printer.MPS803ROM)
  private[this] val printer = new ucesoft.cbm.peripheral.printer.MPS803(bus,printerGraphicsDriver)  
  private[this] val printerDialog = {
    val dialog = new JDialog(vicDisplayFrame,"Print preview")
    val sp = new JScrollPane(printerGraphicsDriver)
    sp.getViewport.setBackground(Color.BLACK)
    dialog.getContentPane.add("Center",sp)
    printerGraphicsDriver.checkSize
    val buttonPanel = new JPanel
    val exportPNGBUtton = new JButton("Export as PNG")
    buttonPanel.add(exportPNGBUtton)
    exportPNGBUtton.setActionCommand("PRINT_EXPORT_AS_PNG")
    exportPNGBUtton.addActionListener(this)
    val clearButton = new JButton("Clear")
    buttonPanel.add(clearButton)
    clearButton.setActionCommand("PRINT_CLEAR")
    clearButton.addActionListener(this)
    dialog.getContentPane.add("South",buttonPanel)
    dialog.pack
    dialog
  }
  // -------------- AUDIO ----------------------
  private[this] val volumeDialog : JDialog = VolumeSettingsPanel.getDialog(vicDisplayFrame,sid.getDriver)
  // ------------ Control Port -----------------------
  private[this] val gameControlPort = new controlport.GamePadControlPort(configuration)
  private[this] val keypadControlPort = controlport.ControlPort.keypadControlPort
  private[this] val keyboardControlPort = controlport.ControlPort.userDefinedKeyControlPort(configuration)
  private[this] val controlPortA = new controlport.ControlPortBridge(keypadControlPort,"Control Port 1")  
  private[this] val controlPortB = new controlport.ControlPortBridge(gameControlPort,"Control port 2")
  // -------------- Light Pen -------------------------
  private[this] val LIGHT_PEN_NO_BUTTON = 0
  private[this] val LIGHT_PEN_BUTTON_UP = 1
  private[this] val LIGHT_PEN_BUTTON_LEFT = 2
  private[this] var lightPenButtonEmulation = LIGHT_PEN_NO_BUTTON
  
  private[this] class LightPenButtonListener extends MouseAdapter with CBMComponent {
    val componentID = "Light pen"
    val componentType = CBMComponentType.INPUT_DEVICE 
    
    override def mousePressed(e:MouseEvent) {
      lightPenButtonEmulation match {
        case LIGHT_PEN_NO_BUTTON =>
        case LIGHT_PEN_BUTTON_UP => controlPortB.emulateUp         
        case LIGHT_PEN_BUTTON_LEFT => controlPortB.emulateLeft
      }
    }
    override def mouseReleased(e:MouseEvent) {
      lightPenButtonEmulation match {
        case LIGHT_PEN_NO_BUTTON =>
        case _ => controlPortB.releaseEmulated
      }
    }
    //override def mouseDragged(e:MouseEvent) = mouseClicked(e)
    
    def init {}
    def reset {}
    // state
    protected def saveState(out:ObjectOutputStream) {}
    protected def loadState(in:ObjectInputStream) {}
    protected def allowsStateRestoring(parent:JFrame) : Boolean = true
  }
  // ------------------------------------ Drag and Drop ----------------------------
  private[this] val DNDHandler = new DNDHandler(handleDND _)
  
  private object DriveLed8Listener extends AbstractDriveLedListener(driveLeds(0),diskProgressPanels(0))
  
  private object DriveLed9Listener extends AbstractDriveLedListener(driveLeds(1),diskProgressPanels(1)) {
    driveLeds(1).setVisible(false)
  }  
  
  def reset {
    baLow = false
    dma = false
    z80Active = true
    clockSpeed = 1
    clock.maximumSpeed = false
    maxSpeedItem.setSelected(false)
  }
  
  def init {
    val sw = new StringWriter
    Log.setOutput(new PrintWriter(sw))
    
    Log.info("Building the system ...")
    ExpansionPort.addConfigurationListener(mmu)
    // -----------------------    
    mmu.setKeyboard(keyb)
    add(clock)
    add(mmu)
    add(cpu)
    add(z80)
    add(keyb)
    add(controlPortA)
    add(controlPortB)
    add(bus)
    add(expansionPort)
    add(rs232)    
    rs232.setRS232Listener(rs232StatusPanel)
    add(new FloppyComponent(8,attachedDisks,drives,driveLeds))
    add(new FloppyComponent(9,attachedDisks,drives,driveLeds))
    // -----------------------
    val vicMemory = mmu
    ExpansionPort.setMemoryForEmptyExpansionPort(mmu)
    ExpansionPort.addConfigurationListener(mmu)    
    import cia._
    // control ports
    val cia1CP1 = new CIA1Connectors.PortAConnector(keyb,controlPortA)
    val cia1CP2 = new CIA1Connectors.PortBConnector(keyb,controlPortB,() => vicChip.triggerLightPen)
    add(cia1CP1)
    add(cia1CP2)    
    
    add(irqSwitcher)    
    // CIAs
    // CIA1 must be modified to handle fast serial ...
    cia1 = new CIA("CIA1",
    				   0xDC00,
    				   cia1CP1,
    				   cia1CP2,
    				   irqSwitcher.ciaIRQ _) {
      override protected def sendSerialBit(on:Boolean) {
        if (!FSDIRasInput) {
          bus.setLine(CIA2Connectors.CIA2_PORTA_BUSID,IECBusLine.DATA,if (on) IECBus.GROUND else IECBus.VOLTAGE)
          //println(s"Sending serial bit $on")
        }
      }
      override protected def sendCNT(high:Boolean) {
        if (!FSDIRasInput) {
          bus.setLine(CIA2Connectors.CIA2_PORTA_BUSID,IECBusLine.SRQ,if (high) IECBus.GROUND else IECBus.VOLTAGE)
          //println(s"SRQ set to $high")
        }
      }
    }
    add(nmiSwitcher)
    // CIA2 connector must be modified to handle fast serial ... 
    val cia2CP1 = new CIA2Connectors.PortAConnector(mmu,bus,rs232) {
      override def srqChanged(oldValue:Int,newValue:Int) {
        if (FSDIRasInput) {
          cia1.setSP(bus.data == IECBus.GROUND)
          cia1.setCNT(newValue == IECBus.GROUND)          
        }
      }
    }
    val cia2CP2 = new CIA2Connectors.PortBConnector(rs232)    
    add(cia2CP1)
    add(cia2CP2)
    cia2 = new CIA("CIA2",
    				   0xDD00,
    				   cia2CP1,
    				   cia2CP2,
    				   nmiSwitcher.cia2NMIAction _)
    rs232.setCIA(cia2)
    ParallelCable.ca2Callback = cia2.setFlagLow _
    add(ParallelCable)
    vicChip = new vic.VIC(vicMemory,mmu.colorRAM,irqSwitcher.vicIRQ _,baLow _)
    // I/O set
    mmu.setIO(cia1,cia2,vicChip,sid,vdc)
    // VIC vicDisplay
    vicDisplay = new vic.Display(vicChip.SCREEN_WIDTH,vicChip.SCREEN_HEIGHT,vicDisplayFrame.getTitle,vicDisplayFrame)
    add(vicDisplay)
    vicDisplay.setPreferredSize(new java.awt.Dimension(vicChip.VISIBLE_SCREEN_WIDTH,vicChip.VISIBLE_SCREEN_HEIGHT))
    vicChip.setDisplay(vicDisplay)
    vicDisplayFrame.getContentPane.add("Center",vicDisplay)
    vicDisplayFrame.addKeyListener(keyb)
    vicDisplayFrame.addKeyListener(keypadControlPort)
    vicDisplayFrame.addKeyListener(keyboardControlPort)
    vicDisplay.addMouseListener(keypadControlPort)
    // VDC vicDisplay
    vdcDisplay = new vic.Display(ucesoft.cbm.peripheral.vdc.VDC.SCREEN_WIDTH,ucesoft.cbm.peripheral.vdc.VDC.SCREEN_HEIGHT,vdcDisplayFrame.getTitle,vdcDisplayFrame) 
    add(vdcDisplay)
    vdcDisplay.setPreferredSize(ucesoft.cbm.peripheral.vdc.VDC.PREFERRED_FRAME_SIZE)
    vdc.setDisplay(vdcDisplay)
    val dummy = new JPanel(new FlowLayout(FlowLayout.LEFT,0,0))
    dummy.add(vdcDisplay)
    vdcDisplayFrame.getContentPane.add("Center",dummy)
    vdcDisplayFrame.addKeyListener(keyb)
    vdcDisplayFrame.addKeyListener(keypadControlPort)
    vdcDisplayFrame.addKeyListener(keyboardControlPort)
    vdcDisplay.addMouseListener(keypadControlPort)
    val resPanel = new JPanel(new FlowLayout(FlowLayout.LEFT))
    val resLabel = new JLabel("Initializing ...")
    resPanel.add(resLabel)
    vdcDisplayFrame.getContentPane.add("South",resPanel)
    vdc.setGeometryUpdateListener { msg =>
      resLabel.setText(msg)
    }
    // light pen
    val lightPen = new LightPenButtonListener    
    add(lightPen)
    vicDisplay.addMouseListener(lightPen)    
    // tracing
    traceDialog = TraceDialog.getTraceDialog(vicDisplayFrame,mmu,z80,vicDisplay,vicChip)
    diskTraceDialog = TraceDialog.getTraceDialog(vicDisplayFrame,c1541_real.getMem,c1541_real)    
    // drive leds
    add(driveLeds(0))        
    add(driveLeds(1))
    if (configuration.getProperty(CONFIGURATION_FRAME_XY) != null) {
      val xy = configuration.getProperty(CONFIGURATION_FRAME_XY) split "," map { _.toInt }
      vicDisplayFrame.setLocation(xy(0),xy(1))
    }    
    configureJoystick
    // drive
    c1541_real.setIsRunningListener(diskRunning => isDiskActive = diskRunning)    
    c1541_real9.setIsRunningListener(diskRunning => isDiskActive9 = diskRunning)
    add(c1541)
    add(c1541_real) 
    add(c1541_real9)
    Log.setOutput(traceDialog.logPanel.writer)
    // tape
    datassette = new c2n.Datassette(cia1.setFlagLow _)
    mmu.setDatassette(datassette)
    add(datassette)
    // printer
    add(printer)
    // Flyer
    add(flyerIEC)
    
    // info panel
    val infoPanel = new JPanel(new BorderLayout)
    val rowPanel = new JPanel(new BorderLayout(0,0))
    val row1Panel = new JPanel(new FlowLayout(FlowLayout.RIGHT))
    val row2Panel = new JPanel(new FlowLayout(FlowLayout.RIGHT))
    rowPanel.add("North",row1Panel)
    rowPanel.add("South",row2Panel)
    val tapePanel = new TapeState
    datassette.setTapeListener(tapePanel)
    row1Panel.add(rs232StatusPanel)
    row1Panel.add(tapePanel)
    row1Panel.add(tapePanel.progressBar)
    row1Panel.add(diskProgressPanels(0))
    row1Panel.add(driveLeds(0))
    row2Panel.add(diskProgressPanels(1))
    row2Panel.add(driveLeds(1))
    infoPanel.add("East",rowPanel)
    infoPanel.add("West",mmuStatusPanel)
    mmuStatusPanel.setVisible(false)
    vicDisplayFrame.getContentPane.add("South",infoPanel)
    vicDisplayFrame.setTransferHandler(DNDHandler)    
    Log.info(sw.toString)
  }
  
  override def afterInitHook {    
	  inspectDialog = InspectPanel.getInspectDialog(vicDisplayFrame,this)    
    // deactivate drive 9
    c1541_real9.setActive(false)        
    // set the correct CPU configuration
    cpuChanged(false)
  }
  
  private def errorHandler(t:Throwable) {
    import CPU6510.CPUJammedException
    t match {
      case j:CPUJammedException =>
        JOptionPane.showConfirmDialog(vicDisplayFrame,
            "CPU jammed at " + Integer.toHexString(cpu.getCurrentInstructionPC) + ". Do you want to open debugger or reset ?",
            "CPU jammed",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.ERROR_MESSAGE) match {
          case JOptionPane.YES_OPTION =>
            traceDialog.forceTracing(true)
            trace(true,true)
          case _ => 
            reset(true)
        }
      case _ =>
        Log.info("Fatal error occurred: " + cpu + "-" + t)
        Log.info(CPU6510.disassemble(mmu,cpu.getCurrentInstructionPC).toString)
        t.printStackTrace(Log.getOut)
        t.printStackTrace
        JOptionPane.showMessageDialog(vicDisplayFrame,t.toString + " [PC=" + Integer.toHexString(cpu.getCurrentInstructionPC) + "]", "Fatal error",JOptionPane.ERROR_MESSAGE)
        trace(true,true)
    }    
  }
  
  private def mainLoop(cycles:Long) { 
    // VIC PHI1
    vicChip.clock
    //DRIVES
    if (isDiskActive) drives(0).clock(cycles)
    if (isDiskActive9) drives(1).clock(cycles)
    if (device10DriveEnabled) device10Drive.clock(cycles)
    // printer
    if (printerEnabled) printer.clock(cycles)
    // Flyer
    if (isFlyerEnabled) flyerIEC.clock(cycles)
    // CPU PHI2
    if (z80Active) z80.clock(cycles,2) 
    else {
      cpu.fetchAndExecute(1)
      if (cpuFrequency == 2 && !mmu.isIOACC) cpu.fetchAndExecute(1)      
    }
  }
  
  private def irqRequest(low:Boolean) {
    cpu.irqRequest(low)
    z80.irq(low)
  }
  
  private def setDMA(dma:Boolean) {
    this.dma = dma
    if (z80Active) z80.requestBUS(dma) else cpu.setDMA(dma)    
  }
  
  private def baLow(low:Boolean) {
    // ignore BA in 2Mhz mode
    //if (cpuFrequency == 1) {
      baLow = low
      if (z80Active) z80.requestBUS(baLow) else cpu.setBaLow(low)
      expansionPort.setBaLow(low)
    //}
  }
  
  // MMU change listener
  def frequencyChanged(f:Int) {
    Log.debug(s"Frequency set to $f Mhz")
    mmuStatusPanel.frequencyChanged(f)
    cpuFrequency = f
  }
  def cpuChanged(is8502:Boolean) {
    if (is8502) {
      traceDialog.traceListener = cpu      
      z80Active = false
      cpu.setDMA(false)
      z80.requestBUS(true)
    }
    else {
      traceDialog.traceListener = z80
      z80Active = true
      z80.requestBUS(false)
      cpu.setDMA(true)      
    }
    traceDialog.forceTracing(traceDialog.isTracing)
    mmuStatusPanel.cpuChanged(is8502)
    Log.debug("Enabling CPU " + (if (z80Active) "Z80" else "8502"))
  }
  def c64Mode(c64Mode:Boolean) {
    mmuStatusPanel.c64Mode(c64Mode)
    this.c64Mode = c64Mode
  }  
  
  def fastSerialDirection(input:Boolean) {
    FSDIRasInput = input
    //println(s"FSDIR set to input $input")
  }
  // ---------------------------------- GUI HANDLING -------------------------------
    
  final def actionPerformed(e:ActionEvent) {
    e.getActionCommand match {
      case "TRACE" =>
      	val traceItem = e.getSource.asInstanceOf[JCheckBoxMenuItem]
      	trace(true,traceItem.isSelected)
      case "TRACE_DISK" =>
      	val traceItem = e.getSource.asInstanceOf[JCheckBoxMenuItem]
      	trace(false,traceItem.isSelected)
      case "INSPECT" =>
        val selected = e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected
        inspectDialog.setVisible(selected)
      case "RESET" =>
        reset(true)
      case "ATTACH_CTR" => 
        attachCtr
      case "DETACH_CTR" =>
        detachCtr
      case "MAXSPEED" =>
        val maxSpeedItem = e.getSource.asInstanceOf[JCheckBoxMenuItem]
        clock.maximumSpeed = maxSpeedItem.isSelected
        //clock.pause
        sid.setFullSpeed(maxSpeedItem.isSelected)
        //clock.play
      case "ADJUSTRATIO" =>
        adjustRatio
      case "AUTORUN_DISK" =>
        attachDisk(0,true)
      case "ATTACH_DISK_0" =>
        attachDisk(0,false)
      case "ATTACH_DISK_1" =>
        attachDisk(1,false)
      case "LOAD_FILE_0" =>
        loadFileFromAttachedFile(0,true)
      case "LOAD_FILE_1" =>
        loadFileFromAttachedFile(1,true)
      case "JOY" =>
        joySettings
      case "PASTE" =>
        paste
      case "SNAPSHOT_VIC" =>
        takeSnapshot(true)
      case "SNAPSHOT_VDC" =>
        takeSnapshot(false)
      case "LOADPRG" =>
        loadPrg
      case "SAVEPRG" =>
        savePrg
      case "ZOOM1" =>
        zoom(1)
      case "ZOOM2" =>
        zoom(2)
      case "PAUSE" =>
        if (e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected) Clock.systemClock.pause
        else Clock.systemClock.play
      case "SWAP_JOY" =>
        swapJoysticks
      case "DISK_RO" =>
        drives foreach { _.setReadOnly(e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected) }
      case "DISK_TRUE_EMU" =>
        val trueEmu = e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected
        bus.reset
        drives(0).setActive(false)
        drives(0) = if (trueEmu) c1541_real 
        else {
          isDiskActive = true
          c1541
        }
        drives(0).setActive(true)
      case "DISK_CAN_GO_SLEEP" =>
        val canGoSleep = e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected
        drives foreach { _.setCanSleep(canGoSleep) }
      case "PARALLEL_CABLE" =>
        val enabled = e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected
        ParallelCable.enabled = enabled
      case "DISK_EXP_RAM_2000" =>
        C1541Mems.RAM_EXP_2000.isActive = e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected
      case "DISK_EXP_RAM_4000" =>
        C1541Mems.RAM_EXP_4000.isActive = e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected
      case "DISK_EXP_RAM_6000" =>
        C1541Mems.RAM_EXP_6000.isActive = e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected
      case "DISK_EXP_RAM_8000" =>
        C1541Mems.RAM_EXP_8000.isActive = e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected
      case "DISK_EXP_RAM_A000" =>
        C1541Mems.RAM_EXP_A000.isActive = e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected
      case "DISK_MIN_SPEED" =>
        for(d <- drives) d.setSpeedHz(d.MIN_SPEED_HZ)
      case "DISK_MAX_SPEED" =>
        for(d <- drives) d.setSpeedHz(d.MAX_SPEED_HZ)
      case "NO_PEN" =>
        lightPenButtonEmulation = LIGHT_PEN_NO_BUTTON
        keypadControlPort.setLightPenEmulation(false)
        vicChip.enableLightPen(false)
      case "PEN_UP" =>
        lightPenButtonEmulation = LIGHT_PEN_BUTTON_UP
        vicChip.enableLightPen(true)
        keypadControlPort.setLightPenEmulation(true)
      case "PEN_LEFT" =>
        lightPenButtonEmulation = LIGHT_PEN_BUTTON_LEFT
        vicChip.enableLightPen(true)
        keypadControlPort.setLightPenEmulation(true)
      case "MOUSE_ENABLED" =>
        val mouseEnabled = e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected
        sid.setMouseEnabled(mouseEnabled)
        if (mouseEnabled) {
          val cursor = new java.awt.image.BufferedImage(3, 3, java.awt.image.BufferedImage.TYPE_INT_ARGB)
          vicDisplayFrame.getContentPane.setCursor(vicDisplayFrame.getToolkit.createCustomCursor(cursor,new Point(0, 0),"null"))
          vdcDisplayFrame.getContentPane.setCursor(vicDisplayFrame.getToolkit.createCustomCursor(cursor,new Point(0, 0),"null"))
        }
        else {
          vicDisplayFrame.getContentPane.setCursor(new java.awt.Cursor(java.awt.Cursor.DEFAULT_CURSOR))
          vdcDisplayFrame.getContentPane.setCursor(new java.awt.Cursor(java.awt.Cursor.DEFAULT_CURSOR))
        }
      case "EXIT" => close
      case "TAPE" =>
        loadFileFromTape
      case "TAPE_PLAY" =>
        datassette.pressPlay
      case "TAPE_STOP" =>
        datassette.pressStop
      case "TAPE_RECORD" =>
        datassette.pressRecordAndPlay
      case "TAPE_REWIND" =>
        datassette.pressRewind
      case "ATTACH_TAPE" =>
        attachTape 
      case "ABOUT" =>
        showAbout
      case "PRINTER_PREVIEW" =>
        showPrinterPreview
      case "PRINT_CLEAR" =>
        printerGraphicsDriver.clearPages
      case "PRINT_EXPORT_AS_PNG" =>
        printerSaveImage
      case "PRINTER_ENABLED" =>
        printerEnabled = e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected
        printer.setActive(printerEnabled)
      case "VOLUME" =>
        volumeDialog.setVisible(true)
      case "CRT_PRESS" => ExpansionPort.getExpansionPort.freezeButton
      case "NO_REU" =>
        ExpansionPort.getExpansionPort.eject
        ExpansionPort.setExpansionPort(ExpansionPort.emptyExpansionPort)
      case "REU_128" => 
        ExpansionPort.setExpansionPort(REU.getREU(REU.REU_1700,mmu,setDMA _,irqSwitcher.expPortIRQ _,None))
      case "REU_256" => 
        ExpansionPort.setExpansionPort(REU.getREU(REU.REU_1764,mmu,setDMA _,irqSwitcher.expPortIRQ _,None))
      case "REU_512" => 
        ExpansionPort.setExpansionPort(REU.getREU(REU.REU_1750,mmu,setDMA _,irqSwitcher.expPortIRQ _,None))
      case "REU_16M" => 
        val fc = new JFileChooser
  	    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
  	    fc.setFileView(new C64FileView)
  	    fc.setFileFilter(new javax.swing.filechooser.FileFilter {
  	      def accept(f: File) = f.isDirectory || f.getName.toUpperCase.endsWith(".REU")
  	      def getDescription = "REU files"
  	    })
  	    fc.showOpenDialog(vicDisplayFrame) match {
  	      case JFileChooser.APPROVE_OPTION => 
  	        try {
  	          val reu = REU.getREU(REU.REU_16M,mmu,setDMA _,irqSwitcher.expPortIRQ _,Some(fc.getSelectedFile))	          
  	          ExpansionPort.setExpansionPort(reu)
  	        }
  	        catch {
  	          case t:Throwable =>
  	            JOptionPane.showMessageDialog(vicDisplayFrame,t.toString, "REU loading error",JOptionPane.ERROR_MESSAGE)
  	        }
  	      case _ =>
  	    }	   
      case "MAKE_DISK" =>
        makeDisk
      case "RS232" =>
        manageRS232
      case "CP/M" =>
        ExpansionPort.getExpansionPort.eject
        if (e.getSource.asInstanceOf[JRadioButtonMenuItem].isSelected) {
          ExpansionPort.setExpansionPort(new ucesoft.cbm.expansion.cpm.CPMCartridge(mmu,setDMA _,setTraceListener _))
        }
        else ExpansionPort.setExpansionPort(ExpansionPort.emptyExpansionPort)
      case "DEVICE10_DISABLED" =>
        device10DriveEnabled = false
      case "LOCAL_DRIVE_ENABLED" =>        
        device10Drive = new LocalDrive(bus,10)
        device10DriveEnabled = true
        changeLocalDriveDir
      case "DROPBOX_DRIVE_ENABLED" =>
        checkDropboxDrive
      case "DRIVE_9_ENABLED" =>
        val enabled = e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected
        c1541_real9.setActive(enabled)
        driveLeds(1).setVisible(enabled)
        adjustRatio
      case "SID_6581" =>
        sid.setModel(true)
      case "SID_8580" =>
        sid.setModel(false)
      case "NO_DUAL_SID" =>
        expansionPort.eject
        sid.setStereo(false)
        ExpansionPort.setExpansionPort(ExpansionPort.emptyExpansionPort)
      case "DUAL_SID_DE00" =>
        expansionPort.eject
        sid.setStereo(true)
        ExpansionPort.setExpansionPort(new DualSID(sid,0xDE00))
      case "DUAL_SID_DF00" =>
        expansionPort.eject
        sid.setStereo(true)
        ExpansionPort.setExpansionPort(new DualSID(sid,0xDF00))
      case "FLYER ENABLED" =>
        val enabled = e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected
        if (enabled != isFlyerEnabled) flyerIEC.reset
        isFlyerEnabled = enabled
      case "FLYER DIR" =>
        val fc = new JFileChooser
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY)
  	    fc.setCurrentDirectory(flyerIEC.getFloppyRepository)
  	    fc.showOpenDialog(vicDisplayFrame) match {
  	      case JFileChooser.APPROVE_OPTION =>
  	        flyerIEC.setFloppyRepository(fc.getSelectedFile)
  	      case _ =>
        }
      case "REMOTE_OFF" =>
        remote match {
          case Some(rem) => 
            rem.stopRemoting
            vicDisplay.setRemote(None)
            remote = None
          case None =>
        }
      case "REMOTE_ON" =>
        val source = e.getSource.asInstanceOf[JRadioButtonMenuItem]
        if (remote.isDefined) remote.get.stopRemoting
        val listeningPort = JOptionPane.showInputDialog(vicDisplayFrame,"Listening port", "Remoting port configuration",JOptionPane.QUESTION_MESSAGE,null,null,"8064")
        if (listeningPort == null) {
          source.setSelected(false)
          vicDisplay.setRemote(None)
        }
        else {
          try {
            val clip = vicDisplay.getClipArea
            val remote = new ucesoft.cbm.remote.RemoteC64Server(listeningPort.toString.toInt,keyboardControlPort :: keyb :: keypadControlPort :: Nil,vicDisplay.displayMem,vicChip.SCREEN_WIDTH,vicChip.SCREEN_HEIGHT,clip._1.x,clip._1.y,clip._2.x,clip._2.y)
            this.remote = Some(remote)            
        	  vicDisplay.setRemote(this.remote)
        	  remote.start
          }
          catch {
            case io:IOException =>
              JOptionPane.showMessageDialog(vicDisplayFrame,io.toString, "Remoting init error",JOptionPane.ERROR_MESSAGE)
              source.setSelected(false)
              vicDisplay.setRemote(None)
          }
        }
      case "SAVE_STATE" =>
        saveState
      case "LOAD_STATE" =>
        loadState
      case "ZIP" =>
        attachZip
      case "KEYPAD" =>
        keyb.enableKeypad(e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected)
      case "MMUSTATUS" =>
        mmuStatusPanel.setVisible(e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected)
      case "NO_FR_INTERNAL" =>
        mmu.configureFunctionROM(true,null)
      case "NO_FR_EXTERNAL" =>
        mmu.configureFunctionROM(false,null)
      case "LOAD_FR_INTERNAL" =>
        loadFunctionROM(true)
      case "LOAD_FR_EXTERNAL" =>
        loadFunctionROM(false)
      case "80COLATSTARTUP" =>
        keyb.set4080Pressed(e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected)
      case "VDCENABLED" =>
        if (e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected) {
          vdc.play
          vdcDisplayFrame.setVisible(true)
        }
        else {
          vdc.pause
          vdcDisplayFrame.setVisible(false)
        }   
      case "VDCTHREAD" =>
        vdcOnItsOwnThread = e.getSource.asInstanceOf[JCheckBoxMenuItem].isSelected
        if (vdcOnItsOwnThread) vdc.setOwnThread else vdc.stopOwnThread
      case "KEYB_EDITOR" =>
        val source = configuration.getProperty(CONFIGURATION_KEYB_MAP_FILE,"default")
        val kbef = new JFrame(s"Keyboard editor ($source)")
        val kbe = new KeyboardEditor(keybMapper,false)
        kbef.getContentPane.add("Center",kbe)
        kbef.pack
        kbef.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE)
        kbef.setVisible(true)
      case "LOAD_KEYB" =>
        loadKeyboard
    }
  }
  
  private def loadKeyboard {
    JOptionPane.showConfirmDialog(vicDisplayFrame,"Would you like to set default keyboard or load a configuration from file ?","Keyboard layout selection", JOptionPane.YES_NO_CANCEL_OPTION,JOptionPane.QUESTION_MESSAGE) match {
      case JOptionPane.YES_OPTION =>
        configuration.remove(CONFIGURATION_KEYB_MAP_FILE)
        JOptionPane.showMessageDialog(vicDisplayFrame,"Reboot the emulator to activate the new keyboard", "Keyboard..",JOptionPane.INFORMATION_MESSAGE)
      case JOptionPane.NO_OPTION =>
        val fc = new JFileChooser
        fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
        fc.setDialogTitle("Choose a keyboard layout")
        fc.showOpenDialog(vicDisplayFrame) match {
          case JFileChooser.APPROVE_OPTION =>
            val in = new BufferedReader(new InputStreamReader(new FileInputStream(fc.getSelectedFile)))
            try {
              keyboard.KeyboardMapperStore.load(in)
              configuration.setProperty(CONFIGURATION_KEYB_MAP_FILE,fc.getSelectedFile.toString)
              JOptionPane.showMessageDialog(vicDisplayFrame,"Reboot the emulator to activate the new keyboard", "Keyboard..",JOptionPane.INFORMATION_MESSAGE)
            }
            catch {
              case _:IllegalArgumentException =>
                JOptionPane.showMessageDialog(vicDisplayFrame,"Invalid keyboard layout file", "Keyboard..",JOptionPane.ERROR_MESSAGE)
            }
            finally {
              in.close
            }
          case _ =>
        }
      case JOptionPane.CANCEL_OPTION =>
    }
  }
  
  private def loadFunctionROM(internal:Boolean) {
    val fc = new JFileChooser
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setDialogTitle("Choose a ROM to load")
    val fn = fc.showOpenDialog(vicDisplayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        fc.getSelectedFile
      case _ =>
        return
    }
    try {
      if (fn.length > 32768) throw new IllegalArgumentException("ROM's size must be less than 32K")
      val rom = Array.ofDim[Byte](fn.length.toInt)
      val f = new DataInputStream(new FileInputStream(fn))
      f.readFully(rom)
      f.close
      mmu.configureFunctionROM(internal,rom)
      JOptionPane.showMessageDialog(vicDisplayFrame,"ROM loaded. Reset to turn it on", "ROM loaded successfully",JOptionPane.INFORMATION_MESSAGE)
    }
    catch {
      case t:Throwable =>
        JOptionPane.showMessageDialog(vicDisplayFrame,"Can't load ROM. Unexpected error occurred: " + t, "ROM loading error",JOptionPane.ERROR_MESSAGE)
        t.printStackTrace
    }
  }
  
  private def loadState {
    clock.pause
    var in : ObjectInputStream = null
    try {
      val canLoad = allowsState(vicDisplayFrame)
      if (!canLoad) {
        JOptionPane.showMessageDialog(vicDisplayFrame,"Can't load state", "State saving error",JOptionPane.ERROR_MESSAGE)
        return
      }
      val fc = new JFileChooser
      fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
	    fc.setFileFilter(new javax.swing.filechooser.FileFilter {
	      def accept(f: File) = f.isDirectory || f.getName.toUpperCase.endsWith(".K64")
	      def getDescription = "Kernal64 state files"
	    })
      fc.setDialogTitle("Choose a state file to load")
      val fn = fc.showOpenDialog(vicDisplayFrame) match {
        case JFileChooser.APPROVE_OPTION =>
          fc.getSelectedFile
        case _ =>
          return
      }
      in = new ObjectInputStream(new FileInputStream(fn))
      reset(false)
      load(in)
    }
    catch {
      case t:Throwable =>
        JOptionPane.showMessageDialog(vicDisplayFrame,"Can't load state. Unexpected error occurred: " + t, "State loading error",JOptionPane.ERROR_MESSAGE)
        t.printStackTrace
        reset(false)
    }
    finally {
      if (in != null) in.close
      clock.play
    }
  }
  
  private def saveState {
    clock.pause
    var out : ObjectOutputStream = null
    try {
      val canSave = allowsState(vicDisplayFrame)
      if (!canSave) {
        JOptionPane.showMessageDialog(vicDisplayFrame,"Can't save state", "State saving error",JOptionPane.ERROR_MESSAGE)
        return
      }
      val fc = new JFileChooser
      fc.setDialogTitle("Choose where to save current state")
      fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
	    fc.setFileFilter(new javax.swing.filechooser.FileFilter {
	      def accept(f: File) = f.isDirectory || f.getName.toUpperCase.endsWith(".K64")
	      def getDescription = "Kernal64 state files"
	    })
      val fn = fc.showSaveDialog(vicDisplayFrame) match {
        case JFileChooser.APPROVE_OPTION =>
          if (fc.getSelectedFile.getName.toUpperCase.endsWith(".K64")) fc.getSelectedFile.toString else fc.getSelectedFile.toString + ".k64" 
        case _ =>
          return
      }
      out = new ObjectOutputStream(new FileOutputStream(fn))
      save(out)
      out.close
    }
    catch {
      case t:Throwable =>
        JOptionPane.showMessageDialog(vicDisplayFrame,"Can't save state. Unexpected error occurred: " + t, "State saving error",JOptionPane.ERROR_MESSAGE)
        t.printStackTrace
    }
    finally {
      if (out != null) out.close
      clock.play
    }
  }
  
  private def adjustRatio {
    val dim = vicDisplay.asInstanceOf[java.awt.Component].getSize
    dim.height = (dim.width / vicChip.SCREEN_ASPECT_RATIO).round.toInt
    vicDisplay.setPreferredSize(dim) 
    vicDisplayFrame.pack
  } 
  
  private def checkDropboxDrive {
    try {
      if (DropBoxAuth.isAccessCodeRequested(configuration)) {                 
        device10Drive = new DropboxDrive(bus,DropBoxAuth.getDbxClient(configuration),10)
        device10DriveEnabled = true
      }
      else {
        if (DropBoxAuth.requestAuthorization(configuration,vicDisplayFrame)) {          
          device10Drive = new DropboxDrive(bus,DropBoxAuth.getDbxClient(configuration),10)
          device10DriveEnabled = true
        }
      }
    }
    catch {
        case t:Throwable =>
          t.printStackTrace
          device10DriveEnabled = false
          JOptionPane.showMessageDialog(vicDisplayFrame,t.toString, "Dropbox init error",JOptionPane.ERROR_MESSAGE)
      }
  }
  
  private def changeLocalDriveDir {
    val fc = new JFileChooser
    fc.setCurrentDirectory(device10Drive.asInstanceOf[LocalDrive].getCurrentDir)
    fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY)
    fc.setDialogTitle("Choose the current device 9 local directory")
    fc.showOpenDialog(vicDisplayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        device10Drive.asInstanceOf[LocalDrive].setCurrentDir(fc.getSelectedFile)
      case _ =>
    }
  }
  
  private def manageRS232 {
    val choices = Array.ofDim[Object](AVAILABLE_RS232.length + 1)
    choices(0) = "None"
    Array.copy(AVAILABLE_RS232,0,choices,1,AVAILABLE_RS232.length)
    val (currentChoice,oldConfig) = activeRs232 match {
      case None => (choices(0),"")
      case Some(rs) => (rs,rs.connectionInfo)
    }
    val choice = JOptionPane.showInputDialog(vicDisplayFrame,"Choose an rs-232 implementation","RS-232",JOptionPane.QUESTION_MESSAGE,null,choices,currentChoice)
    if (choice == choices(0) && activeRs232.isDefined) {
      activeRs232.get.setEnabled(false)      
      activeRs232 = None
    }
    else
    if (choice != null) {
      val rs = choice.asInstanceOf[RS232]
      val conf = JOptionPane.showInputDialog(vicDisplayFrame,s"<html><b>${rs.getDescription}</b><br>Type the configuration string:</html>", s"${rs.componentID}'s configuration",JOptionPane.QUESTION_MESSAGE,null,null,oldConfig)
      if (conf != null) {
        try {
          rs.setConfiguration(conf.toString)
          activeRs232 foreach { _.setEnabled(false) }                   
          activeRs232 = Some(rs)
          rs232.setRS232(rs)
          rs232.setEnabled(true)
          rs232StatusPanel.setVisible(true)
          adjustRatio
        }
        catch {
          case t:Throwable =>
            JOptionPane.showMessageDialog(vicDisplayFrame,t.toString, "RS-232 configuration error",JOptionPane.ERROR_MESSAGE)
        }
      }
    }
  }
  
  private def makeDisk {
    val fc = new JFileChooser
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setFileView(new C64FileView)
    fc.setDialogTitle("Save a .d64 or .g64 empty disk")
    fc.showSaveDialog(vicDisplayFrame) match {
      case JFileChooser.APPROVE_OPTION => 
        try {          
          var emptyFile = fc.getSelectedFile.toString
          if (emptyFile.toUpperCase.endsWith(".G64")) G64.makeEmptyDisk(emptyFile)
          else {
            val diskLabel = JOptionPane.showInputDialog(vicDisplayFrame,"Insert disk label", "New Disk label",JOptionPane.QUESTION_MESSAGE)
            if (diskLabel != null) {
              if (!emptyFile.toUpperCase.endsWith(".D64")) emptyFile += ".d64"
              val emptyD64 = new D64(emptyFile,true)
              emptyD64.format(s"N:${diskLabel.toUpperCase},00")
              emptyD64.close
            }
          }          
        }
        catch {
          case t:Throwable => 
            JOptionPane.showMessageDialog(vicDisplayFrame,t.toString, "Disk making error",JOptionPane.ERROR_MESSAGE)
        }
      case _ =>
    }
  }
    
  // GamePlayer interface
  def play(file:File) = {
    ExpansionPort.getExpansionPort.eject
    ExpansionPort.setExpansionPort(ExpansionPort.emptyExpansionPort)
    handleDND(file)
  }
  def attachDevice(file:File) : Unit = attachDevice(file,false)
  
  private def handleDND(file:File) {
    val name = file.getName.toUpperCase
    if (name.endsWith(".CRT")) loadCartridgeFile(file)
    else {
      reset(false)
      clock.schedule(new ClockEvent("Loading",clock.currentCycles + 2200000,(cycles) => { attachDevice(file,true) }))
      clock.play
    }
  }
  
  private def attachDevice(file:File,autorun:Boolean) {
    val name = file.getName.toUpperCase
    
    if (name.endsWith(".PRG")) loadPRGFile(file,autorun)
    else    
    if (name.endsWith(".D64") || name.endsWith(".G64") || name.endsWith(".D71")) attachDiskFile(0,file,autorun)
    else
    if (name.endsWith(".TAP")) attachTapeFile(file,autorun)
    else
    if (name.endsWith(".T64")) attachT64File(file,autorun)
    else
    if (name.endsWith(".ZIP")) attachZIPFile(file,autorun)
    else
    if (name.endsWith(".CRT")) loadCartridgeFile(file)
  }
  
  private def attachZip {
    val fc = new JFileChooser
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setFileFilter(new javax.swing.filechooser.FileFilter {
      def accept(f: File) = f.isDirectory || f.getName.toUpperCase.endsWith(".ZIP")
      def getDescription = "ZIP files"
    })
    fc.showOpenDialog(vicDisplayFrame) match {
      case JFileChooser.APPROVE_OPTION =>                
        attachZIPFile(fc.getSelectedFile,false)
      case _ =>        
    }
  }
  
  private def loadPRGFile(file:File,autorun:Boolean) {    
    val in = new FileInputStream(file)
    val start = in.read + in.read * 256
    var m = start
    var b = in.read
    var size = 0
    val mem = mmu.getBank0RAM
    while (b != -1) {
      mem.write(m, b)
      m += 1
      size += 1
      b = in.read
    }
    in.close
    val end = start + size
    ProgramLoader.updateBASICPointers(mem,start,end,c64Mode)
    
    Log.info(s"BASIC program loaded from ${start} to ${end} size=${size}")
    configuration.setProperty(CONFIGURATION_LASTDISKDIR,file.getParentFile.toString)
    if (autorun) {
      insertTextIntoKeyboardBuffer("RUN" + 13.toChar)
    }
  }
  
  private def loadCartridgeFile(file:File) {
    try {          
      if (Thread.currentThread != Clock.systemClock) clock.pause
      val ep = ExpansionPortFactory.loadExpansionPort(file.toString,irqSwitcher.expPortIRQ _,nmiSwitcher.expansionPortNMI _,mmu.RAM)
      println(ep)
      if (ep.isFreezeButtonSupported) cartMenu.setVisible(true)
      ExpansionPort.setExpansionPort(ep)
      Log.info(s"Attached cartridge ${ExpansionPort.getExpansionPort.name}")
      reset(false)
      configuration.setProperty(CONFIGURATION_LASTDISKDIR,file.getParentFile.toString)  
      detachCtrItem.setEnabled(true)
    }
    catch {
      case t:Throwable =>
        t.printStackTrace(traceDialog.logPanel.writer)
        JOptionPane.showMessageDialog(vicDisplayFrame,t.toString, "Cartridge loading error",JOptionPane.ERROR_MESSAGE)
    }
    finally {
      clock.play
    }
  }
  
  private def attachDiskFile(driveID:Int,file:File,autorun:Boolean) {
    try {      
      val isD64 = file.getName.toUpperCase.endsWith(".D64") || file.getName.toUpperCase.endsWith(".D71")
      if (drives(driveID) == c1541 && !isD64) {
        JOptionPane.showMessageDialog(vicDisplayFrame,"G64 format not allowed on a 1541 not in true emulation mode", "Disk attaching error",JOptionPane.ERROR_MESSAGE)
        return
      }
      val disk = if (isD64) new D64(file.toString) else new G64(file.toString)
      attachedDisks(driveID) match {
        case Some(oldDisk) => oldDisk.close
        case None =>
      }
      attachedDisks(driveID) = Some(disk)
      if (!traceDialog.isTracing) clock.pause
      drives(driveID).setDriveReader(disk,true)
      clock.play
            
      loadFileItems(driveID).setEnabled(isD64)
      configuration.setProperty(CONFIGURATION_LASTDISKDIR,file.getParentFile.toString)
      if (autorun) {
        insertTextIntoKeyboardBuffer("LOAD\"*\",8,1" + 13.toChar + "RUN" + 13.toChar)
      }
      driveLeds(driveID).setToolTipText(disk.toString)
    }
    catch {
      case t:Throwable =>
        t.printStackTrace
        JOptionPane.showMessageDialog(vicDisplayFrame,t.toString, "Disk attaching error",JOptionPane.ERROR_MESSAGE)
    }
  }
  
  private def attachTapeFile(file:File,autorun:Boolean) {
    datassette.setTAP(Some(new TAP(file.toString)))
    tapeMenu.setEnabled(true)
    configuration.setProperty(CONFIGURATION_LASTDISKDIR,file.getParentFile.toString)
    if (autorun) {
      insertTextIntoKeyboardBuffer("LOAD" + 13.toChar + "RUN" + 13.toChar)
    }
  }
  // -------------------------------------------------
  
  private def printerSaveImage {
    val fc = new JFileChooser
    fc.showSaveDialog(printerDialog) match {
      case JFileChooser.APPROVE_OPTION =>
        val file = if (fc.getSelectedFile.getName.toUpperCase.endsWith(".PNG")) fc.getSelectedFile else new File(fc.getSelectedFile.toString + ".png")
      	printerGraphicsDriver.saveAsPNG(file)
      case _ =>
    }
  }
  
  private def showPrinterPreview {
    printerGraphicsDriver.checkSize
    printerDialog.setVisible(true)
  }
  
  private def showAbout {
    val about = new AboutCanvas(mmu.CHAR_ROM,ucesoft.cbm.Version.VERSION.toUpperCase + " (" + ucesoft.cbm.Version.BUILD_DATE.toUpperCase + ")")
    JOptionPane.showMessageDialog(vicDisplayFrame,about,"About",JOptionPane.INFORMATION_MESSAGE,new ImageIcon(getClass.getResource("/resources/commodore_file.png")))    
  }
  
  private def zoom(f:Double) {
    val dim = new Dimension((vicChip.VISIBLE_SCREEN_WIDTH * f).toInt,(vicChip.VISIBLE_SCREEN_HEIGHT * f).toInt)
    vicDisplay.setPreferredSize(dim)
    vicDisplay.invalidate
    vicDisplay.repaint()
    vicDisplayFrame.pack
  }
  
  private def savePrg {
    val fc = new JFileChooser
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setFileFilter(new javax.swing.filechooser.FileFilter {
      def accept(f: File) = f.isDirectory || f.getName.toUpperCase.endsWith(".PRG")
      def getDescription = "PRG files"
    })
    fc.showSaveDialog(vicDisplayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        configuration.setProperty(CONFIGURATION_LASTDISKDIR,fc.getSelectedFile.getParentFile.toString)
        val out = new FileOutputStream(fc.getSelectedFile)
        val start = ProgramLoader.startAddress(mmu, c64Mode)
        val end = ProgramLoader.endAddress(mmu, c64Mode) - 1
  	    out.write(start & 0x0F)
  	    out.write(start >> 8)
        for (m <- start to end) out.write(mmu.getBank0RAM.read(m))
        out.close
        Log.info(s"BASIC program saved from ${start} to ${end}")
      case _ =>
    }
  }
  
  private def loadPrg {
    val fc = new JFileChooser
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setFileView(new C64FileView)
    fc.setFileFilter(new javax.swing.filechooser.FileFilter {
      def accept(f: File) = f.isDirectory || f.getName.toUpperCase.endsWith(".PRG")
      def getDescription = "PRG files"
    })
    fc.showOpenDialog(vicDisplayFrame) match {
      case JFileChooser.APPROVE_OPTION =>                
        loadPRGFile(fc.getSelectedFile,false)
      case _ =>
    }
  }
  
  private def takeSnapshot(vic:Boolean) {
    val fc = new JFileChooser
    fc.showSaveDialog(vicDisplayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        val file = if (fc.getSelectedFile.getName.toUpperCase.endsWith(".PNG")) fc.getSelectedFile else new File(fc.getSelectedFile.toString + ".png")
      	vic match {
          case true => vicDisplay.saveSnapshot(file)
          case false => vdcDisplay.saveSnapshot(file)
        }
      case _ =>
    }
  }
  
  private def paste {
    val clipboard = Toolkit.getDefaultToolkit.getSystemClipboard
    val contents = clipboard.getContents(null)
    if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
      val str = contents.getTransferData(DataFlavor.stringFlavor).toString
      new Thread {
        val maxLenAddr = if (c64Mode) 649 else 2592
        val bufferAddr = if (c64Mode) 631 else 842
        val lenAddr = if (c64Mode) 198 else 208
        override def run {
          val len = mmu.read(maxLenAddr)
          var strpos = 0
          while (strpos < str.length) {
            val size = if (len < str.length - strpos) len else str.length - strpos            
            for(i <- 0 until size) {
              val c = str.charAt(strpos).toUpper
              mmu.write(bufferAddr + i,if (c != '\n') c else 0x0D)
              strpos += 1
            }
            mmu.write(lenAddr,size)
            val ts = System.currentTimeMillis
            // wait max 10 secs...
            while (mmu.read(lenAddr) > 0 && System.currentTimeMillis - ts < 10000) Thread.sleep(1)
          }
        }
      }.start
    }
  }
  
  private def trace(cpu:Boolean,on:Boolean) {
    if (cpu) {
      Log.setOutput(traceDialog.logPanel.writer)
      traceDialog.setVisible(on)
      traceItem.setSelected(on)
    }
    else {
      if (on) Log.setOutput(diskTraceDialog.logPanel.writer) 
      else Log.setOutput(traceDialog.logPanel.writer)
      diskTraceDialog.setVisible(on)
      traceDiskItem.setSelected(on)
    }
  }
  
  private def reset(play:Boolean=true) {
    traceDialog.forceTracing(false)
    diskTraceDialog.forceTracing(false)
    if (Thread.currentThread != Clock.systemClock) clock.pause
    //if (play) ExpansionPort.setExpansionPort(ExpansionPort.emptyExpansionPort)
    resetComponent
    if (play) clock.play
  } 
  
  private def detachCtr {
    if (ExpansionPort.getExpansionPort.isEmpty) JOptionPane.showMessageDialog(vicDisplayFrame,"No cartridge attached!", "Detach error",JOptionPane.ERROR_MESSAGE)
    else {
      if (Thread.currentThread != Clock.systemClock) clock.pause
      ExpansionPort.getExpansionPort.eject
      ExpansionPort.setExpansionPort(ExpansionPort.emptyExpansionPort)
      reset(true)
    }
    detachCtrItem.setEnabled(false)
    cartMenu.setVisible(false)
  }
  
  private def attachCtr {
    val fc = new JFileChooser
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setFileView(new C64FileView)
    fc.setFileFilter(new javax.swing.filechooser.FileFilter {
      def accept(f:File) = f.isDirectory || f.getName.toUpperCase.endsWith(".CRT")
      def getDescription = "CRT files"
    })
    fc.showOpenDialog(vicDisplayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        loadCartridgeFile(fc.getSelectedFile)
      case _ =>
    }
  }
  
  private def attachDisk(driveID:Int,autorun:Boolean) {
    val fc = new JFileChooser
    fc.setAccessory(new javax.swing.JScrollPane(new D64Canvas(fc,mmu.CHAR_ROM)))
    fc.setFileView(new C64FileView)
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setFileFilter(new javax.swing.filechooser.FileFilter {
      def accept(f:File) = f.isDirectory || 
                           f.getName.toUpperCase.endsWith(".D64") ||
                           f.getName.toUpperCase.endsWith(".D71") ||
                           f.getName.toUpperCase.endsWith(".G64")
      def getDescription = "D64/71 or G64 files"
    })
    fc.showOpenDialog(vicDisplayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        attachDiskFile(driveID,fc.getSelectedFile,autorun)
      case _ =>
    }
  }
  
  private def loadFileFromAttachedFile(driveID:Int,relocate:Boolean) {
    attachedDisks(driveID) match {
      case None =>
        JOptionPane.showMessageDialog(vicDisplayFrame,"No disk attached!", "Loading error",JOptionPane.ERROR_MESSAGE)
      case Some(floppy) =>
        Option(JOptionPane.showInputDialog(vicDisplayFrame,"Load file","*")) match {
          case None =>
          case Some(fileName) =>
            try {
              floppy.asInstanceOf[D64].loadInMemory(mmu.getBank0RAM,fileName,relocate,c64Mode)
            }
            catch {
              case t:Throwable =>
                JOptionPane.showMessageDialog(vicDisplayFrame, "Errore while loading from disk: " + t.getMessage,"Loading error",JOptionPane.ERROR_MESSAGE)
            }
        }
    }
  }    
  
  private def loadFileFromTape {
    val fc = new JFileChooser
    fc.setAccessory(new javax.swing.JScrollPane(new T64Canvas(fc,mmu.CHAR_ROM)))
    fc.setFileView(new C64FileView)
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setFileFilter(new javax.swing.filechooser.FileFilter {
      def accept(f:File) = f.isDirectory || f.getName.toUpperCase.endsWith(".T64")
      def getDescription = "T64 files"
    })
    fc.showOpenDialog(vicDisplayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        attachT64File(new File(fc.getSelectedFile.toString),false)
      case _ =>
    }
  }
  
  private def attachT64File(file:File,autorun:Boolean) {
    val tape = new T64(file.toString)
    try {
      val values = tape.entries map { e => e.asInstanceOf[Object] }
      JOptionPane.showInputDialog(vicDisplayFrame,"Select file to open:","Open file in " + file,JOptionPane.INFORMATION_MESSAGE,null,values,values(0)) match {
        case null =>
        case entry:T64Entry =>
          tape.loadInMemory(mmu.getBank0RAM,entry,c64Mode)
          configuration.setProperty(CONFIGURATION_LASTDISKDIR,file.getParentFile.toString)
          if (autorun) {
            insertTextIntoKeyboardBuffer("RUN" + 13.toChar)
          }
      }
    }
    finally {
      tape.close
    }
  }
  
  private def attachZIPFile(file:File,autorun:Boolean) {
    ZIP.zipEntries(file) match {
      case Success(entries) =>
        if (entries.size > 0) {
          val values = entries map { e => e.asInstanceOf[Object] } toArray
          
          JOptionPane.showInputDialog(vicDisplayFrame,"Select file to open:","Open file in " + file,JOptionPane.INFORMATION_MESSAGE,null,values,values(0)) match {
            case null =>
            case e@ZIP.ArchiveEntry(_,_,_) =>
              ZIP.extractEntry(e,new File(scala.util.Properties.tmpDir)) match {
                case Some(f) =>
                  attachDevice(f,autorun)
                case None =>
              }          
          } 
        }
      case Failure(t) =>
        JOptionPane.showMessageDialog(vicDisplayFrame,t.toString,s"Error while opening zip file $file",JOptionPane.ERROR_MESSAGE)
    }    
  }
  
  private def attachTape {
    val fc = new JFileChooser
    fc.setFileView(new C64FileView)
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setFileFilter(new javax.swing.filechooser.FileFilter {
      def accept(f:File) = f.isDirectory || f.getName.toUpperCase.endsWith(".TAP")
      def getDescription = "TAP files"
    })
    fc.showOpenDialog(vicDisplayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        attachTapeFile(fc.getSelectedFile,false)
      case _ =>
    }
  }
  
  private def insertTextIntoKeyboardBuffer(txt:String) {
    val bufferAddr = if (c64Mode) 631 else 842
    val lenAddr = if (c64Mode) 198 else 208
    for(i <- 0 until txt.length) {
      mmu.write(bufferAddr + i,txt.charAt(i))
    }
    mmu.write(lenAddr,txt.length)
  }
  
  private def setMenu {
    val menuBar = new JMenuBar
    val fileMenu = new JMenu("File")
    val editMenu = new JMenu("Edit")
    val stateMenu = new JMenu("State")
    val traceMenu = new JMenu("Trace")
    val optionMenu = new JMenu("Settings")
    val gamesMenu = new JMenu("Games")
    val helpMenu = new JMenu("Help")    
    cartMenu.setVisible(false)
    
    menuBar.add(fileMenu)
    menuBar.add(editMenu)
    menuBar.add(stateMenu)
    menuBar.add(traceMenu)
    menuBar.add(optionMenu)         
    menuBar.add(cartMenu)
    menuBar.add(gamesMenu)
    menuBar.add(helpMenu)
    
    val zipItem = new JMenuItem("Attach zip ...")
    zipItem.setActionCommand("ZIP")
    zipItem.addActionListener(this)
    fileMenu.add(zipItem)
    
    val tapeItem = new JMenuItem("Load file from tape ...")
    tapeItem.setActionCommand("TAPE")
    tapeItem.addActionListener(this)
    fileMenu.add(tapeItem)
    
    val attachTapeItem = new JMenuItem("Attach tape ...")
    attachTapeItem.setActionCommand("ATTACH_TAPE")
    attachTapeItem.addActionListener(this)
    fileMenu.add(attachTapeItem)
        
    tapeMenu.setEnabled(false)
    fileMenu.add(tapeMenu)
    
    val tapePlayItem = new JMenuItem("Cassette press play")
    tapePlayItem.setActionCommand("TAPE_PLAY")
    tapePlayItem.addActionListener(this)
    tapeMenu.add(tapePlayItem)
    
    val tapeStopItem = new JMenuItem("Cassette press stop")
    tapeStopItem.setActionCommand("TAPE_STOP")
    tapeStopItem.addActionListener(this)
    tapeMenu.add(tapeStopItem)
    
    val tapeRecordItem = new JMenuItem("Cassette press record & play")
    tapeRecordItem.setActionCommand("TAPE_RECORD")
    tapeRecordItem.addActionListener(this)
    tapeMenu.add(tapeRecordItem)
    
    val tapeRewindItem = new JMenuItem("Cassette press rewind")
    tapeRewindItem.setActionCommand("TAPE_REWIND")
    tapeRewindItem.addActionListener(this)
    tapeMenu.add(tapeRewindItem)
    
    fileMenu.addSeparator
    
    val makeDiskItem = new JMenuItem("Make empty disk ...")
    makeDiskItem.setActionCommand("MAKE_DISK")
    makeDiskItem.addActionListener(this)
    fileMenu.add(makeDiskItem)
    
    val autorunDiskItem = new JMenuItem("Autorun disk ...")
    autorunDiskItem.setActionCommand("AUTORUN_DISK")
    autorunDiskItem.addActionListener(this)
    fileMenu.add(autorunDiskItem)
    
    val attachDisk0Item = new JMenuItem("Attach disk 8...")
    attachDisk0Item.setActionCommand("ATTACH_DISK_0")
    attachDisk0Item.addActionListener(this)
    fileMenu.add(attachDisk0Item)
    
    val attachDisk1Item = new JMenuItem("Attach disk 9...")
    attachDisk1Item.setActionCommand("ATTACH_DISK_1")
    attachDisk1Item.addActionListener(this)
    fileMenu.add(attachDisk1Item)
        
    loadFileItems(0).setEnabled(false)
    loadFileItems(0).setActionCommand("LOAD_FILE_0")
    loadFileItems(0).addActionListener(this)
    fileMenu.add(loadFileItems(0)) 
    loadFileItems(1).setEnabled(false)
    loadFileItems(1).setActionCommand("LOAD_FILE_1")
    loadFileItems(1).addActionListener(this)
    fileMenu.add(loadFileItems(1))
    fileMenu.addSeparator
    
    val loadPrgItem = new JMenuItem("Load PRG file from local disk ...")
    loadPrgItem.setActionCommand("LOADPRG")
    loadPrgItem.addActionListener(this)
    fileMenu.add(loadPrgItem)
    
    val savePrgItem = new JMenuItem("Save PRG file to local disk ...")
    savePrgItem.setActionCommand("SAVEPRG")
    savePrgItem.addActionListener(this)
    fileMenu.add(savePrgItem)
    
    val drive9EnabledItem = new JCheckBoxMenuItem("Drive 9 enabled")
    drive9EnabledItem.setSelected(false)
    drive9EnabledItem.setActionCommand("DRIVE_9_ENABLED")
    drive9EnabledItem.addActionListener(this)
    fileMenu.add(drive9EnabledItem)
    
    val localDriveItem = new JMenu("Drive on device 10 ...")
    fileMenu.add(localDriveItem)
    val group0 = new ButtonGroup
    val noLocalDriveItem = new JRadioButtonMenuItem("Disabled")
    noLocalDriveItem.setSelected(true)
    noLocalDriveItem.setActionCommand("DEVICE10_DISABLED")
    noLocalDriveItem.addActionListener(this)
    group0.add(noLocalDriveItem)
    localDriveItem.add(noLocalDriveItem)
    val localDriveEnabled = new JRadioButtonMenuItem("Local drive ...")
    localDriveEnabled.setActionCommand("LOCAL_DRIVE_ENABLED")
    localDriveEnabled.addActionListener(this)
    group0.add(localDriveEnabled)
    localDriveItem.add(localDriveEnabled)
    val dropboxDriveEnabled = new JRadioButtonMenuItem("Dropbox drive ...")
    dropboxDriveEnabled.setActionCommand("DROPBOX_DRIVE_ENABLED")
    dropboxDriveEnabled.addActionListener(this)
    group0.add(dropboxDriveEnabled)
    localDriveItem.add(dropboxDriveEnabled)
    
    fileMenu.addSeparator
    
    val resetItem = new JMenuItem("Reset")
    resetItem.setActionCommand("RESET")
    resetItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_R,java.awt.event.InputEvent.ALT_DOWN_MASK))
    resetItem.addActionListener(this)
    fileMenu.add(resetItem)
    
    fileMenu.addSeparator
    
    val attachCtrItem = new JMenuItem("Attach cartridge ...")
    attachCtrItem.setActionCommand("ATTACH_CTR")
    attachCtrItem.addActionListener(this)
    fileMenu.add(attachCtrItem)
        
    detachCtrItem.setEnabled(false)
    detachCtrItem.setActionCommand("DETACH_CTR")
    detachCtrItem.addActionListener(this)
    fileMenu.add(detachCtrItem)
    
    fileMenu.addSeparator
    
    val exitItem = new JMenuItem("Exit")
    exitItem.setActionCommand("EXIT")
    exitItem.addActionListener(this)
    fileMenu.add(exitItem)
    
    // edit
        
    val pasteItem = new JMenuItem("Paste text")
    pasteItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V,java.awt.Event.CTRL_MASK))
    pasteItem.setActionCommand("PASTE")
    pasteItem.addActionListener(this)
    editMenu.add(pasteItem)
    
    //state
    val saveStateItem = new JMenuItem("Save state ...")
    saveStateItem.setActionCommand("SAVE_STATE")
    saveStateItem.addActionListener(this)    
    stateMenu.add(saveStateItem)
    val loadStateItem = new JMenuItem("Load state ...")
    loadStateItem.setActionCommand("LOAD_STATE")
    loadStateItem.addActionListener(this)    
    stateMenu.add(loadStateItem)
    
    // trace
    
    traceItem = new JCheckBoxMenuItem("Trace CPU")
    traceItem.setSelected(false)
    traceItem.setActionCommand("TRACE")
    traceItem.addActionListener(this)    
    traceMenu.add(traceItem)  
    
    traceDiskItem = new JCheckBoxMenuItem("Trace Disk CPU")
    traceDiskItem.setSelected(false)
    traceDiskItem.setActionCommand("TRACE_DISK")
    traceDiskItem.addActionListener(this)    
    traceMenu.add(traceDiskItem)
    
    val inspectItem = new JCheckBoxMenuItem("Inspect components ...")
    inspectItem.setSelected(false)
    inspectItem.setActionCommand("INSPECT")
    inspectItem.addActionListener(this)    
    traceMenu.add(inspectItem)
    
    // settings
    
    val vdcMenu = new JMenu("VDC")
    optionMenu.add(vdcMenu)
    val _80enabledAtStartupItem = new JCheckBoxMenuItem("80 columns enabled at startup")
    _80enabledAtStartupItem.setSelected(false)
    _80enabledAtStartupItem.setActionCommand("80COLATSTARTUP")
    _80enabledAtStartupItem.addActionListener(this)    
    vdcMenu.add(_80enabledAtStartupItem)
    val vdcEnabled = new JCheckBoxMenuItem("VDC enabled")
    vdcEnabled.setSelected(true)
    vdcEnabled.setActionCommand("VDCENABLED")
    vdcEnabled.addActionListener(this)    
    vdcMenu.add(vdcEnabled)
    val vdcSeparateThreadItem = new JCheckBoxMenuItem("VDC on its own thread")
    vdcSeparateThreadItem.setSelected(false)
    vdcSeparateThreadItem.setActionCommand("VDCTHREAD")
    vdcSeparateThreadItem.addActionListener(this)    
    vdcMenu.add(vdcSeparateThreadItem)
    
    val statusPanelItem = new JCheckBoxMenuItem("MMU status panel enabled")
    statusPanelItem.setSelected(false)
    statusPanelItem.setActionCommand("MMUSTATUS")
    statusPanelItem.addActionListener(this)    
    optionMenu.add(statusPanelItem)
    
    optionMenu.addSeparator
    
    val keybMenu = new JMenu("Keyboard")
    optionMenu.add(keybMenu)
    
    val enableKeypadItem = new JCheckBoxMenuItem("Keypad enabled")
    enableKeypadItem.setSelected(true)
    enableKeypadItem.setActionCommand("KEYPAD")
    enableKeypadItem.addActionListener(this)    
    keybMenu.add(enableKeypadItem)
    
    val keybEditorItem = new JMenuItem("Keyboard editor ...")
    keybEditorItem.setActionCommand("KEYB_EDITOR")
    keybEditorItem.addActionListener(this)
    keybMenu.add(keybEditorItem)
    val loadKeybItem = new JMenuItem("Set keyboard layout ...")
    loadKeybItem.setActionCommand("LOAD_KEYB")
    loadKeybItem.addActionListener(this)
    keybMenu.add(loadKeybItem)
    
    optionMenu.addSeparator
    
    val volumeItem = new JMenuItem("Volume settings ...")
    volumeItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V,java.awt.Event.ALT_MASK))
    volumeItem.setActionCommand("VOLUME")
    volumeItem.addActionListener(this)
    optionMenu.add(volumeItem)
    
    optionMenu.addSeparator
        
    maxSpeedItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_W,java.awt.event.InputEvent.ALT_DOWN_MASK))
    maxSpeedItem.setSelected(clock.maximumSpeed)
    maxSpeedItem.setActionCommand("MAXSPEED")
    maxSpeedItem.addActionListener(this)    
    optionMenu.add(maxSpeedItem)
    
    optionMenu.addSeparator
    
    val adjustRatioItem = new JMenuItem("Adjust vicDisplay ratio")
    adjustRatioItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_A,java.awt.event.InputEvent.ALT_DOWN_MASK))
    adjustRatioItem.setActionCommand("ADJUSTRATIO")
    adjustRatioItem.addActionListener(this)
    optionMenu.add(adjustRatioItem)
    
    val zoomItem = new JMenu("Zoom")
    optionMenu.add(zoomItem)
    val zoom1Item = new JMenuItem("Zoom x 1")
    zoom1Item.setActionCommand("ZOOM1")
    zoom1Item.addActionListener(this)
    zoomItem.add(zoom1Item)
    val zoom4Item = new JMenuItem("Zoom x 2")
    zoom4Item.setActionCommand("ZOOM2")
    zoom4Item.addActionListener(this)
    zoomItem.add(zoom4Item)
    
    optionMenu.addSeparator
        
    val joyAItem = new JMenuItem("Joystick...")
    joyAItem.setActionCommand("JOY")
    joyAItem.addActionListener(this)
    optionMenu.add(joyAItem)
    
    val swapJoyAItem = new JMenuItem("Swap joysticks")
    swapJoyAItem.setActionCommand("SWAP_JOY")
    swapJoyAItem.addActionListener(this)
    optionMenu.add(swapJoyAItem)
    
    val lightPenMenu = new JMenu("Light pen")
    optionMenu.add(lightPenMenu)
    val group3 = new ButtonGroup
    val noPenItem = new JRadioButtonMenuItem("No light pen")
    noPenItem.setSelected(true)
    noPenItem.setActionCommand("NO_PEN")
    noPenItem.addActionListener(this)
    group3.add(noPenItem)
    lightPenMenu.add(noPenItem)
    val penUp = new JRadioButtonMenuItem("Light pen with button up")
    penUp.setActionCommand("PEN_UP")
    penUp.addActionListener(this)
    group3.add(penUp)
    lightPenMenu.add(penUp)
    val penLeft = new JRadioButtonMenuItem("Light pen with button left")
    penLeft.setActionCommand("PEN_LEFT")
    penLeft.addActionListener(this)
    group3.add(penLeft)
    lightPenMenu.add(penLeft)
    
    val mouseEnabledItem = new JCheckBoxMenuItem("Mouse 1351 enabled")
    mouseEnabledItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_M,java.awt.event.InputEvent.ALT_DOWN_MASK))
    mouseEnabledItem.setSelected(false)
    mouseEnabledItem.setActionCommand("MOUSE_ENABLED")
    mouseEnabledItem.addActionListener(this)    
    optionMenu.add(mouseEnabledItem)
    
    optionMenu.addSeparator
    
    val snapMenu = new JMenu("Take a snapshot")
    optionMenu.add(snapMenu)
    
    val vicSnapshotItem = new JMenuItem("VIC ...")
    vicSnapshotItem.setActionCommand("SNAPSHOT_VIC")
    vicSnapshotItem.addActionListener(this)
    snapMenu.add(vicSnapshotItem)
    val vdcSnapshotItem = new JMenuItem("VDC ...")
    vdcSnapshotItem.setActionCommand("SNAPSHOT_VDC")
    vdcSnapshotItem.addActionListener(this)
    snapMenu.add(vdcSnapshotItem)
    
    optionMenu.addSeparator
    
    val pauseItem = new JCheckBoxMenuItem("Pause")
    pauseItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_P,java.awt.event.InputEvent.ALT_DOWN_MASK))
    pauseItem.setActionCommand("PAUSE")
    pauseItem.addActionListener(this)
    optionMenu.add(pauseItem)
    
    optionMenu.addSeparator
    
    val printerPreviewItem = new JMenuItem("Printer preview ...")    
    printerPreviewItem.setActionCommand("PRINTER_PREVIEW")
    printerPreviewItem.addActionListener(this)
    optionMenu.add(printerPreviewItem)
    
    val printerEnabledItem = new JCheckBoxMenuItem("Printer enabled")
    printerEnabledItem.setSelected(false)
    printerEnabledItem.setActionCommand("PRINTER_ENABLED")
    printerEnabledItem.addActionListener(this)
    optionMenu.add(printerEnabledItem)
    
    optionMenu.addSeparator
    
    val sidItem = new JMenu("SID")
    optionMenu.add(sidItem)
    val group7 = new ButtonGroup
    val sidTypeItem = new JMenu("SID Type")
    sidItem.add(sidTypeItem)
    val sid6581Item = new JRadioButtonMenuItem("MOS 6581")
    sid6581Item.setSelected(true)
    sid6581Item.setActionCommand("SID_6581")
    sid6581Item.addActionListener(this)
    sidTypeItem.add(sid6581Item)
    group7.add(sid6581Item)
    val sid8580Item = new JRadioButtonMenuItem("MOS 8580")
    sid8580Item.setSelected(false)
    sid8580Item.setActionCommand("SID_8580")
    sid8580Item.addActionListener(this)
    sidTypeItem.add(sid8580Item)
    group7.add(sid8580Item)
    val sid2Item = new JMenu("Dual SID")
    sidItem.add(sid2Item)
    val group8 = new ButtonGroup
    val nosid2Item = new JRadioButtonMenuItem("None")
    sid2Item.add(nosid2Item)
    nosid2Item.setSelected(true)
    nosid2Item.setActionCommand("NO_DUAL_SID")
    nosid2Item.addActionListener(this)
    group8.add(nosid2Item)
    val sid2DE00Item = new JRadioButtonMenuItem("$DE00")
    sid2Item.add(sid2DE00Item)
    sid2DE00Item.setSelected(false)
    sid2DE00Item.setActionCommand("DUAL_SID_DE00")
    sid2DE00Item.addActionListener(this)
    group8.add(sid2DE00Item)
    val sid2DF00Item = new JRadioButtonMenuItem("$DF00")
    sid2Item.add(sid2DF00Item)
    sid2DF00Item.setSelected(false)
    sid2DF00Item.setActionCommand("DUAL_SID_DF00")
    sid2DF00Item.addActionListener(this)
    group8.add(sid2DF00Item)
    
    optionMenu.addSeparator
    
    val diskItem = new JMenu("Drive")
    optionMenu.add(diskItem)
    
    val diskReadOnlyItem = new JCheckBoxMenuItem("Read only disk")
    diskReadOnlyItem.setSelected(false)
    diskReadOnlyItem.setActionCommand("DISK_RO")
    diskReadOnlyItem.addActionListener(this)    
    diskItem.add(diskReadOnlyItem)
    
    val diskTrueEmuItem = new JCheckBoxMenuItem("1541 True emulation")
    diskTrueEmuItem.setSelected(true)
    diskTrueEmuItem.setActionCommand("DISK_TRUE_EMU")
    diskTrueEmuItem.addActionListener(this)    
    diskItem.add(diskTrueEmuItem)
    
    val diskCanSleepItem = new JCheckBoxMenuItem("1541 can go sleeping")
    diskCanSleepItem.setSelected(true)
    diskCanSleepItem.setActionCommand("DISK_CAN_GO_SLEEP")
    diskCanSleepItem.addActionListener(this)    
    diskItem.add(diskCanSleepItem)
    
    val parallelCableItem = new JCheckBoxMenuItem("Parallel cable enabled")
    parallelCableItem.setSelected(false)
    parallelCableItem.setActionCommand("PARALLEL_CABLE")
    parallelCableItem.addActionListener(this)    
    diskItem.add(parallelCableItem)
    
    val diskSpeedItem = new JMenu("1541 speed")
    val group11 = new ButtonGroup 
    val diskMinSpeedItem = new JRadioButtonMenuItem("Min speed")
    diskMinSpeedItem.setSelected(true)
    diskMinSpeedItem.setActionCommand("DISK_MIN_SPEED")
    diskMinSpeedItem.addActionListener(this)  
    diskSpeedItem.add(diskMinSpeedItem)
    group11.add(diskMinSpeedItem)
    val diskMaxSpeedItem = new JRadioButtonMenuItem("Max speed")
    diskMaxSpeedItem.setActionCommand("DISK_MAX_SPEED")
    diskMaxSpeedItem.addActionListener(this)  
    diskSpeedItem.add(diskMaxSpeedItem)
    group11.add(diskMaxSpeedItem)
    diskItem.add(diskSpeedItem)
    
    val expRAMItem = new JMenu("RAM Expansion")
    diskItem.add(expRAMItem)
    
    val ramExp2000Item = new JCheckBoxMenuItem("$2000-$3FFF enabled")
    ramExp2000Item.setSelected(false)
    ramExp2000Item.setActionCommand("DISK_EXP_RAM_2000")
    ramExp2000Item.addActionListener(this)    
    expRAMItem.add(ramExp2000Item)
    val ramExp4000Item = new JCheckBoxMenuItem("$4000-$5FFF enabled")
    ramExp4000Item.setSelected(false)
    ramExp4000Item.setActionCommand("DISK_EXP_RAM_4000")
    ramExp4000Item.addActionListener(this)    
    expRAMItem.add(ramExp4000Item)
    val ramExp6000Item = new JCheckBoxMenuItem("$6000-$7FFF enabled")
    ramExp6000Item.setSelected(false)
    ramExp6000Item.setActionCommand("DISK_EXP_RAM_6000")
    ramExp6000Item.addActionListener(this)    
    expRAMItem.add(ramExp6000Item)
    val ramExp8000Item = new JCheckBoxMenuItem("$8000-$9FFF enabled")
    ramExp8000Item.setSelected(false)
    ramExp8000Item.setActionCommand("DISK_EXP_RAM_8000")
    ramExp8000Item.addActionListener(this)    
    expRAMItem.add(ramExp8000Item)
    val ramExpA000Item = new JCheckBoxMenuItem("$A000-$BFFF enabled")
    ramExpA000Item.setSelected(false)
    ramExpA000Item.setActionCommand("DISK_EXP_RAM_A000")
    ramExpA000Item.addActionListener(this)    
    expRAMItem.add(ramExpA000Item)
    
    optionMenu.addSeparator
    
    val remoteItem = new JMenu("Remoting")
    optionMenu.add(remoteItem)
    
    val group10 = new ButtonGroup
    val remoteDisabledItem = new JRadioButtonMenuItem("Off")
    remoteDisabledItem.setSelected(true)
    remoteDisabledItem.setActionCommand("REMOTE_OFF")
    remoteDisabledItem.addActionListener(this)
    group10.add(remoteDisabledItem)
    remoteItem.add(remoteDisabledItem)
    val remoteEnabledItem = new JRadioButtonMenuItem("On ...")
    remoteEnabledItem.setActionCommand("REMOTE_ON")
    remoteEnabledItem.addActionListener(this)
    group10.add(remoteEnabledItem)
    remoteItem.add(remoteEnabledItem)
    
    optionMenu.addSeparator
    
    val frMenu = new JMenu("Function roms")
    optionMenu.add(frMenu)
    val frIntGroup = new ButtonGroup
    val frNoInternalItem = new JRadioButtonMenuItem("Internal function ROM empty")
    frNoInternalItem.setSelected(true)
    frNoInternalItem.setActionCommand("NO_FR_INTERNAL")
    frNoInternalItem.addActionListener(this)
    frIntGroup.add(frNoInternalItem)
    frMenu.add(frNoInternalItem)
    val frLoadInternalItem = new JRadioButtonMenuItem("Load Internal function ROM ...")
    frLoadInternalItem.setActionCommand("LOAD_FR_INTERNAL")
    frLoadInternalItem.addActionListener(this)
    frIntGroup.add(frLoadInternalItem)
    frMenu.add(frLoadInternalItem)
    val frExtGroup = new ButtonGroup
    val frNoExternalItem = new JRadioButtonMenuItem("External function ROM empty")
    frNoExternalItem.setSelected(true)
    frNoExternalItem.setActionCommand("NO_FR_EXTERNAL")
    frNoExternalItem.addActionListener(this)
    frExtGroup.add(frNoExternalItem)
    frMenu.add(frNoExternalItem)
    val frLoadExternalItem = new JRadioButtonMenuItem("Load External function ROM ...")
    frLoadExternalItem.setActionCommand("LOAD_FR_EXTERNAL")
    frLoadExternalItem.addActionListener(this)
    frExtGroup.add(frLoadExternalItem)
    frMenu.add(frLoadExternalItem)
    
    optionMenu.addSeparator
    
    val IOItem = new JMenu("I/O")
    optionMenu.add(IOItem)
    
    optionMenu.addSeparator
    
    val rs232Item = new JMenuItem("RS-232 ...")
    rs232Item.setActionCommand("RS232")
    rs232Item.addActionListener(this)    
    IOItem.add(rs232Item)
    
    IOItem.addSeparator
    
    val flyerItem = new JMenu("Flyer internet modem")
    IOItem.add(flyerItem)
    
    val fylerEnabledItem = new JCheckBoxMenuItem("Flyer enabled on 7")
    fylerEnabledItem.setSelected(false)
    flyerItem.add(fylerEnabledItem)
    fylerEnabledItem.setActionCommand("FLYER ENABLED")
    fylerEnabledItem.addActionListener(this)
    val flyerDirectoryItem = new JMenuItem("Set disks repository ...")
    flyerItem.add(flyerDirectoryItem)
    flyerDirectoryItem.setActionCommand("FLYER DIR")
    flyerDirectoryItem.addActionListener(this)
    
    val reuItem = new JMenu("REU")
    val group5 = new ButtonGroup
    val noReuItem = new JRadioButtonMenuItem("None")
    noReuItem.setSelected(true)
    noReuItem.setActionCommand("NO_REU")
    noReuItem.addActionListener(this)
    group5.add(noReuItem)
    reuItem.add(noReuItem)
    val reu128Item = new JRadioButtonMenuItem("128K")
    reu128Item.setActionCommand("REU_128")
    reu128Item.addActionListener(this)
    group5.add(reu128Item)
    reuItem.add(reu128Item)
    val reu256Item = new JRadioButtonMenuItem("256K")
    reu256Item.setActionCommand("REU_256")
    reu256Item.addActionListener(this)
    group5.add(reu256Item)
    reuItem.add(reu256Item)
    val reu512Item = new JRadioButtonMenuItem("512K")
    reu512Item.setActionCommand("REU_512")
    reu512Item.addActionListener(this)
    group5.add(reu512Item)
    reuItem.add(reu512Item)
    val reu16MItem = new JRadioButtonMenuItem("16M ...")
    reu16MItem.setActionCommand("REU_16M")
    reu16MItem.addActionListener(this)
    group5.add(reu16MItem)
    reuItem.add(reu16MItem)
    
    IOItem.add(reuItem)
    
    IOItem.addSeparator
    
    val cpmItem = new JRadioButtonMenuItem("CP/M Cartdrige")
    cpmItem.setActionCommand("CP/M")
    cpmItem.addActionListener(this)
    IOItem.add(cpmItem)
    
    // cartridge
    val cartButtonItem = new JMenuItem("Press cartridge button...")
    cartButtonItem.setActionCommand("CRT_PRESS")
    cartButtonItem.addActionListener(this)
    cartMenu.add(cartButtonItem)
    
    // games
    val loader = ServiceLoader.load(classOf[ucesoft.cbm.game.GameProvider])
    var providers = loader.iterator
    try {
      if (!providers.hasNext) providers = java.util.Arrays.asList((new ucesoft.cbm.game.GameBaseSpi).asInstanceOf[ucesoft.cbm.game.GameProvider],(new ucesoft.cbm.game.PouetDemoSpi).asInstanceOf[ucesoft.cbm.game.GameProvider]).iterator
      val names = new collection.mutable.HashSet[String]
      while (providers.hasNext) {
        val provider = providers.next
        Log.info(s"Loaded ${provider.name} provider")
        if (!names.contains(provider.name)) {
          names += provider.name
          val item = new JCheckBoxMenuItem(provider.name)
          gamesMenu.add(item)
          item.addActionListener(new ActionListener {
            def actionPerformed(e:ActionEvent) {
              try {
                val ui = GameUI.getUIFor(item,vicDisplayFrame,provider,C128.this)
                ui.setVisible(item.isSelected)
              }
              catch {
                case t:Throwable =>
                  JOptionPane.showMessageDialog(vicDisplayFrame,t.toString,s"Error while contacting provider ${provider.name}'s server",JOptionPane.ERROR_MESSAGE)
              }
            }
          })
        }
      }
    }
    catch {
      case t:Throwable =>
        t.printStackTrace
    }
    
    // help    
    val aboutItem = new JMenuItem("About")
    aboutItem.setActionCommand("ABOUT")
    aboutItem.addActionListener(this)
    helpMenu.add(aboutItem)
    
    vicDisplayFrame.setJMenuBar(menuBar)
  }
  
  private def configureJoystick {
    import ucesoft.cbm.peripheral.controlport.Joysticks._
    def getControlPortFor(id:String) = configuration.getProperty(id) match {
      case CONFIGURATION_KEYPAD_VALUE => keypadControlPort
      case CONFIGURATION_JOYSTICK_VALUE => gameControlPort
      case CONFIGURATION_KEYBOARD_VALUE => keyboardControlPort
      case _ => controlport.ControlPort.emptyControlPort
    }
    
    controlPortA.controlPort = getControlPortFor(CONFIGURATION_JOY_PORT_2)
    controlPortB.controlPort = getControlPortFor(CONFIGURATION_JOY_PORT_1)
  }
  
  private def joySettings {
    Clock.systemClock.pause
    try {
      val dialog = new JoystickSettingDialog(vicDisplayFrame,configuration,gameControlPort)
      dialog.setVisible(true)
      configureJoystick
    }
    finally {
      Clock.systemClock.play
    }
  }
  
  private def swapJoysticks {
    import ucesoft.cbm.peripheral.controlport.Joysticks._
    val j1 = configuration.getProperty(CONFIGURATION_JOY_PORT_1)
    val j2 = configuration.getProperty(CONFIGURATION_JOY_PORT_2)
    if (j2 != null) configuration.setProperty(CONFIGURATION_JOY_PORT_1,j2)
    if (j1 != null) configuration.setProperty(CONFIGURATION_JOY_PORT_2,j1)
    configureJoystick
  }
  
  private def close {
    configuration.setProperty(CONFIGURATION_FRAME_XY,vicDisplayFrame.getX + "," + vicDisplayFrame.getY)
    configuration.setProperty(CONFIGURATION_FRAME_DIM,vicDisplayFrame.getSize.width + "," + vicDisplayFrame.getSize.height)
    try {
      val propsFile = new File(new File(scala.util.Properties.userHome),CONFIGURATION_FILENAME)
      val out = new FileWriter(propsFile)
      configuration.store(out, "C64 configuration file")
      out.close
      for(attachedDisk <- attachedDisks)
        attachedDisk match {
          case Some(d64) => d64.close
          case None =>
        }
    }
    catch {
      case io:IOException =>
    }
    sys.exit(0)
  }
  
  private def setTraceListener(tl:Option[TraceListener]) {
    tl match {
      case None => 
        traceDialog.traceListener = cpu
      case Some(t) => 
        traceDialog.traceListener = t
    }
  }
  
  // state
  protected def saveState(out:ObjectOutputStream) {
    out.writeChars("KERNAL128")
    out.writeObject(ucesoft.cbm.Version.VERSION)
    out.writeLong(System.currentTimeMillis)
    out.writeBoolean(isDiskActive)
    out.writeBoolean(isDiskActive9)
    out.writeBoolean(printerEnabled)
    out.writeBoolean(z80Active)
    out.writeInt(clockSpeed)
    out.writeBoolean(FSDIRasInput)
    out.writeBoolean(c64Mode)
    out.writeInt(cpuFrequency)
  }
  protected def loadState(in:ObjectInputStream) {
    val header = "KERNAL128"
    for(i <- 0 until header.length) if (in.readChar != header(i)) throw new IOException("Bad header")
    val ver = in.readObject.asInstanceOf[String]
    val ts = in.readLong
    isDiskActive = in.readBoolean
    isDiskActive9 = in.readBoolean
    printerEnabled = in.readBoolean
    z80Active = in.readBoolean
    clockSpeed = in.readInt
    FSDIRasInput = in.readBoolean
    c64Mode = in.readBoolean
    cpuFrequency = in.readInt
    val msg = s"<html><b>Version:</b> $ver<br><b>Date:</b> ${new java.util.Date(ts)}</html>"
    JOptionPane.showConfirmDialog(vicDisplayFrame,msg,"State loading confirmation",JOptionPane.YES_NO_OPTION,JOptionPane.QUESTION_MESSAGE) match {
      case JOptionPane.YES_OPTION =>
      case _ => throw new IOException("State loading aborted")
    }
  }
  protected def allowsStateRestoring(parent:JFrame) : Boolean = true
  // -----------------------------------------------------------------------------------------
  
  def run {
    //build
    initComponent
    setMenu    
    SwingUtilities.invokeLater(new Runnable {
      def run {
        vdcDisplayFrame.pack
        vdcDisplayFrame.setResizable(false)
        vdcDisplayFrame.setVisible(true)
        vdc.play
      }
    }) 
    SwingUtilities.invokeLater(new Runnable {
      def run {
        vicDisplayFrame.pack
        if (configuration.getProperty(CONFIGURATION_FRAME_DIM) != null) {
          val dim = configuration.getProperty(CONFIGURATION_FRAME_DIM) split "," map { _.toInt }
          vicDisplayFrame.setSize(dim(0),dim(1))
          adjustRatio
        }
        vicDisplayFrame.setVisible(true)                        
        clock.play
      }
    })    
  }
}