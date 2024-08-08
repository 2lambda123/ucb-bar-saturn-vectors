package saturn.common

import chisel3._
import chisel3.util._

import saturn.mem.MemRequest

object PipeView {

  def dispatch(dis: DecoupledIO[VectorIssueInst], label: String, cycle: UInt): Unit = {
    when (dis.fire) {
      printf(s"PipeView:%d:${label}:%d:0x%x:%d:%d:DASM(%x)\n",
        dis.bits.debug_id,
        cycle,
        dis.bits.pc,
        dis.bits.vconfig.vl,
        dis.bits.vstart,
        dis.bits.bits)
    }
  }

  def ex(iss: DecoupledIO[ExecuteMicroOp], label: String, cycle: UInt): Unit = {
    when (iss.fire) {
      printf(s"PipeView:%d:${label}:%d:%d:%b\n",
        iss.bits.debug_id,
        cycle,
        iss.bits.wvd_eg,
        iss.bits.tail)
    }
  }

  def sdata(iss: DecoupledIO[StoreDataMicroOp], label: String, cycle: UInt): Unit = {
    when (iss.fire) {
      printf(s"PipeView:%d:${label}:%d:%d:%b\n",
        iss.bits.debug_id,
        cycle,
        iss.bits.debug_eg,
        iss.bits.tail)
    }
  }

  private def wb(en: Bool, write: VectorWrite, label: String, cycle: UInt): Unit = {
    when (en) {
      printf(s"PipeView:%d:${label}:%d:%d\n",
        write.debug_id,
        cycle,
        write.eg)
    }
  }
  def wb(write: ValidIO[VectorWrite], label: String, cycle: UInt): Unit =
    wb(write.valid, write.bits, label, cycle)
  def wb(write: DecoupledIO[VectorWrite], label: String, cycle: UInt): Unit =
    wb(write.fire, write.bits, label, cycle)

  def mem(req: DecoupledIO[MemRequest], label: String, cycle: UInt): Unit = {
      when (req.fire) {
        printf(s"PipeView:%d:${label}:%d:0x%x:%d\n",
          req.bits.debug_id,
          cycle,
          req.bits.addr,
          req.bits.debug_eg)
      }
  }
}
