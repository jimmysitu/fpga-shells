// See LICENSE for license details.
package sifive.fpgashells.shell.xilinx

import chisel3._
import chisel3.experimental.{attach, IO, withClockAndReset}
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.SyncResetSynchronizerShiftReg
import sifive.fpgashells.clocks._
import sifive.fpgashells.shell._
import sifive.fpgashells.ip.xilinx._

import sifive.blocks.devices.uart._

class SysClockMiz701nOverlay(val shell: Miz701nShell, val name: String, params: ClockInputOverlayParams)
  extends SingleEndedClockInputXilinxOverlay(params)
{
  val node = shell { ClockSourceNode(freqMHz = 100, jitterPS = 50)(ValName(name)) }

  shell {
    InModuleBody {
      val clk: Clock = io
      shell.xdc.addPackagePin(clk, "H16")
      shell.xdc.addIOStandard(clk, "LsCMOS33")
    }
  }
}

// CPE1 for UART, pin 17: GND
case class UARTPortOverlayParams()(implicit val p: Parameters)
case object UARTPortOverlayKey extends Field[Seq[DesignOverlay[UARTPortOverlayParams, ModuleValue[UARTPortIO]]]](Nil)

class UARTMiz701nOverlay(val shell: Miz701nShell, val name: String, params: UARTPortOverlayParams)
  extends IOOverlay[UARTPortIO, ModuleValue[UARTPortIO]]
{
  implicit val p = params.p

  def ioFactory = new UARTPortIO()

  val uartSource = BundleBridgeSource(() => new UARTPortIO())
  val uartSink = shell { uartSource.makeSink }

  val designOutput = InModuleBody { uartSource.bundle }


  shell { InModuleBody {
    io <> uartSink.bundle
  } }

  shell { InModuleBody {
    val packagePinsWithPackageIOs = Seq(
      ("T22", IOPin(io.rxd)), // pin 15
      ("V22", IOPin(io.txd))) // pin 13

    packagePinsWithPackageIOs foreach { case (pin, io) => {
      shell.xdc.addPackagePin(io, pin)
      shell.xdc.addIOStandard(io, "LVCMOS33")
      shell.xdc.addIOB(io)
    } }
  } }
}

class LEDMiz701nOverlay(val shell: Miz701nShell, val name: String, params: LEDOverlayParams)
  extends LEDXilinxOverlay(params, packagePins = Seq(
    "N15", // LD0
    "N16", // LD1
    "M19", // LD2
    "M20") // LD3
  )

class SwitchMiz701nOverlay(val shell: Miz701nShell, val name: String, params: SwitchOverlayParams)
  extends SwitchXilinxOverlay(params, packagePins = Seq(
    //"M14", // BTN0, reused by reset
    "M15") // BTN1
  )

// CEP1 for JTAG
// pin 1: VCC33; pin 2: GND
class JTAGDebugMiz701nOverlay(val shell: Miz701nShell, val name: String, params: JTAGDebugOverlayParams)
  extends JTAGDebugXilinxOverlay(params)
{
  shell { InModuleBody {
    shell.sdc.addClock("JTCK", IOPin(io.jtag_TCK), 10)
    shell.sdc.addGroup(clocks = Seq("JTCK"))
    shell.xdc.clockDedicatedRouteFalse(IOPin(io.jtag_TCK))
    val packagePinsWithPackageIOs = Seq(
      ("L14", IOPin(io.jtag_TDO)),  // pin 3, Sr1_SCL
      ("L15", IOPin(io.jtag_TCK)),  // pin 4, Sr1_SDA
      ("B19", IOPin(io.jtag_TDI)),  // pin 5, Sr1_SYNC
      ("A20", IOPin(io.jtag_TMS)))  // pin 6, Sr1_HREF

    packagePinsWithPackageIOs foreach { case (pin, io) => {
      shell.xdc.addPackagePin(io, pin)
      shell.xdc.addIOStandard(io, "LVCMOS33")
      shell.xdc.addPullup(io)
    } }
  } }
}

class Miz701nShell()(implicit p: Parameters) extends Series7Shell
{
  // PLL reset causes
  val pllReset = InModuleBody { Wire(Bool()) }

  // Define FPGA shell Interface, actions:
  // a. Generate pin constrains in xdc file
  // b. Insert helper logic in shell module
  val sys_clock = Overlay(ClockInputOverlayKey)(new SysClockMiz701nOverlay   (_, _, _))
  val led       = Overlay(LEDOverlayKey)       (new LEDMiz701nOverlay        (_, _, _))
  val btn       = Overlay(SwitchOverlayKey)    (new SwitchMiz701nOverlay     (_, _, _))
  val jtag      = Overlay(JTAGDebugOverlayKey) (new JTAGDebugMiz701nOverlay  (_, _, _))
  val uart      = Overlay(UARTPortOverlayKey)  (new UARTMiz701nOverlay       (_, _, _))

  // All shell resources are appended to designParameters by overlay
  val topDesign = LazyModule(p(DesignKey)(designParameters))

  // Place the sys_clock at the Shell if the user didn't ask for it
  p(ClockInputOverlayKey).foreach(_(ClockInputOverlayParams()))

  override lazy val module = new LazyRawModuleImp(this) {
    val resetn = IO(Input(Bool()))
    xdc.addPackagePin(IOPin(resetn), "M14")  // BTN0, 0 when pressed
    xdc.addIOStandard(resetn, "LVCMOS33")

    val resetn_ibuf = Module(new IBUF)
    resetn_ibuf.io.I := resetn

    val powerOnReset = PowerOnResetFPGAOnly(sys_clock.get.clock)
    sdc.addAsyncPath(Seq(powerOnReset))

    pllReset := (!resetn_ibuf.io.O) || powerOnReset
  }
}
