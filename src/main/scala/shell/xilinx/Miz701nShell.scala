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

class SysClockMiz701nOverlay(val shell: Miz701nShell, val name: String, params: ClockInputOverlayParams)
  extends SingleEndedClockInputXilinxOverlay(params)
{
  val node = shell { ClockSourceNode(freqMHz = 100, jitterPS = 50)(ValName(name)) }

  shell { InModuleBody {
    val clk: Clock = io
    shell.xdc.addPackagePin(clk, "H16")
    shell.xdc.addIOStandard(clk, "LVCMOS33")
  } }
}

// NEP for UART
class UARTMiz701nOverlay(val shell: Miz701nShell, val name: String, params: UARTOverlayParams)
  extends UARTXilinxOverlay(params, false)
{
  shell { InModuleBody {
    val packagePinsWithPackageIOs = Seq(
      ("T10", IOPin(io.rxd)), // pin 4
      ("U12", IOPin(io.txd))) // pin 6

    packagePinsWithPackageIOs foreach { case (pin, io) => {
      shell.xdc.addPackagePin(io, pin)
      shell.xdc.addIOStandard(io, "LVCMOS33")
      shell.xdc.addIOB(io)
    } }
  } }
}

class LEDMiz701nOverlay(val shell: Miz701nShell, val name: String, params: LEDOverlayParams)
  extends LEDXilinxOverlay(params, packagePins = Seq("N15", "N16", "M19", "M20"))

class SwitchMiz701nOverlay(val shell: Miz701nShell, val name: String, params: SwitchOverlayParams)
  extends SwitchXilinxOverlay(params, packagePins = Seq("M14", "M15"))

// NEP for JTAG
class JTAGDebugMiz701nOverlay(val shell: Miz701nShell, val name: String, params: JTAGDebugOverlayParams)
  extends JTAGDebugXilinxOverlay(params)
{
  shell { InModuleBody {
    shell.sdc.addClock("JTCK", IOPin(io.jtag_TCK), 10)
    shell.sdc.addGroup(clocks = Seq("JTCK"))
    shell.xdc.clockDedicatedRouteFalse(IOPin(io.jtag_TCK))
    val packagePinsWithPackageIOs = Seq(
      ("N17", IOPin(io.jtag_TCK)),  // pin 37
      ("W18", IOPin(io.jtag_TMS)),  // pin 35
      ("Y18", IOPin(io.jtag_TDI)),  // pin 33
      ("V17", IOPin(io.jtag_TDO)))  // pin 31

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

  val sys_clock = Overlay(ClockInputOverlayKey)(new SysClockMiz701nOverlay   (_, _, _))
  val led       = Overlay(LEDOverlayKey)       (new LEDMiz701nOverlay        (_, _, _))
  val switch    = Overlay(SwitchOverlayKey)    (new SwitchMiz701nOverlay     (_, _, _))
  val jtag      = Overlay(JTAGDebugOverlayKey) (new JTAGDebugMiz701nOverlay  (_, _, _))

  val topDesign = LazyModule(p(DesignKey)(designParameters))

  // Place the sys_clock at the Shell if the user didn't ask for it
  p(ClockInputOverlayKey).foreach(_(ClockInputOverlayParams()))

  override lazy val module = new LazyRawModuleImp(this) {
    val reset = IO(Input(Bool()))
    xdc.addBoardPin(reset, "reset")

    val reset_ibuf = Module(new IBUF)
    reset_ibuf.io.I := reset

    val powerOnReset = PowerOnResetFPGAOnly(sys_clock.get.clock)
    sdc.addAsyncPath(Seq(powerOnReset))

    pllReset :=
      (!reset_ibuf.io.O) || powerOnReset
  }
}
