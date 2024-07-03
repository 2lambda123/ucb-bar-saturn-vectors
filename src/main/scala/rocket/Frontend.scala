package saturn.rocket

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.rocket._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.diplomacy._

import saturn.common._
import saturn.backend.{VectorBackend}
import saturn.mem.{ScalarMemOrderCheckIO, TLSplitInterface}
import saturn.frontend.{EarlyTrapCheck, IterativeTrapCheck}

class SaturnRocketFrontend(edge: TLEdge)(implicit p: Parameters) extends CoreModule()(p) with HasVectorParams {
  val io = IO(new Bundle {
    val core = new VectorCoreIO
    val tlb = Flipped(new DCacheTLBPort)

    val issue = Decoupled(new VectorIssueInst)

    val index_access = Flipped(new VectorIndexAccessIO)
    val mask_access = Flipped(new VectorMaskAccessIO)

    val scalar_check = Flipped(new ScalarMemOrderCheckIO)

  })

  val ecu = Module(new EarlyTrapCheck(edge, None))
  val icu = Module(new IterativeTrapCheck)

  ecu.io.sg_base            := DontCare
  ecu.io.s0.in.valid        := io.core.ex.valid && !icu.io.busy
  ecu.io.s0.in.bits.inst    := io.core.ex.inst
  ecu.io.s0.in.bits.pc      := io.core.ex.pc
  ecu.io.s0.in.bits.status  := io.core.status
  ecu.io.s0.in.bits.vconfig := io.core.ex.vconfig
  ecu.io.s0.in.bits.vstart  := io.core.ex.vstart
  ecu.io.s0.in.bits.rs1     := io.core.ex.rs1
  ecu.io.s0.in.bits.rs2     := io.core.ex.rs2
  ecu.io.s0.in.bits.phys    := false.B
  io.core.ex.ready          := !icu.io.busy

  ecu.io.s1.rs1.valid := ecu.io.s1.inst.isOpf && !ecu.io.s1.inst.vmu
  ecu.io.s1.rs1.bits  := io.core.mem.frs1
  ecu.io.s1.kill      := io.core.killm
  io.core.mem.block_all    := icu.io.busy || ecu.io.s2.internal_replay.valid
  io.core.mem.block_mem    := (ecu.io.s2.inst.valid && ecu.io.s2.inst.bits.vmu) || io.scalar_check.conflict

  io.tlb.req.valid := Mux(icu.io.busy, icu.io.s0_tlb_req.valid, ecu.io.s0.tlb_req.valid)
  io.tlb.req.bits  := Mux(icu.io.busy, icu.io.s0_tlb_req.bits , ecu.io.s0.tlb_req.bits)
  ecu.io.s1.tlb_resp := io.tlb.s1_resp
  when (RegEnable(icu.io.busy || !io.tlb.req.ready, ecu.io.s0.tlb_req.valid)) { ecu.io.s1.tlb_resp.miss := true.B }
  icu.io.tlb_resp := io.tlb.s1_resp
  when (RegEnable(!io.tlb.req.ready, icu.io.s0_tlb_req.valid)) { icu.io.tlb_resp.miss := true.B }
  io.tlb.s2_kill := false.B

  ecu.io.s2.scalar_store_pending := io.core.wb.store_pending

  io.core.wb.replay := ecu.io.s2.replay
  io.core.wb.xcpt   := Mux(icu.io.busy, icu.io.xcpt.valid     , ecu.io.s2.xcpt.valid)
  io.core.wb.cause  := Mux(icu.io.busy, icu.io.xcpt.bits.cause, ecu.io.s2.xcpt.bits.cause)
  io.core.wb.pc     := Mux(icu.io.busy, icu.io.pc             , ecu.io.s2.pc)
  io.core.wb.retire := Mux(icu.io.busy, icu.io.retire         , ecu.io.s2.retire)
  io.core.wb.inst   := Mux(icu.io.busy, icu.io.inst.bits      , ecu.io.s2.inst.bits.bits)
  io.core.wb.tval   := Mux(icu.io.busy, icu.io.xcpt.bits.tval , ecu.io.s2.xcpt.bits.tval)
  io.core.wb.rob_should_wb    := Mux(icu.io.busy, icu.io.inst.writes_xrf, ecu.io.s2.inst.bits.writes_xrf)
  io.core.wb.rob_should_wb_fp := Mux(icu.io.busy, icu.io.inst.writes_frf, ecu.io.s2.inst.bits.writes_frf)
  io.core.set_vstart       := Mux(icu.io.busy, icu.io.vstart         , ecu.io.s2.vstart)
  io.core.set_vconfig      := icu.io.vconfig
  ecu.io.s2.vxrm := io.core.wb.vxrm
  ecu.io.s2.frm  := io.core.wb.frm
  icu.io.in      := ecu.io.s2.internal_replay

  io.issue.valid := Mux(icu.io.busy, icu.io.issue.valid, ecu.io.s2.issue.valid)
  io.issue.bits  := Mux(icu.io.busy, icu.io.issue.bits , ecu.io.s2.issue.bits)
  icu.io.issue.ready    := io.issue.ready
  ecu.io.s2.issue.ready := !icu.io.busy && io.issue.ready

  io.core.trap_check_busy := ecu.io.busy || icu.io.busy

  icu.io.status := io.core.status
  icu.io.index_access <> io.index_access
  icu.io.mask_access <> io.mask_access
  io.scalar_check.addr := io.tlb.s1_resp.paddr
  io.scalar_check.size := io.tlb.s1_resp.size
  io.scalar_check.store := isWrite(io.tlb.s1_resp.cmd)

  io.core.backend_busy := false.B // set externally
  io.core.set_vxsat := false.B // set externally
  io.core.set_fflags := DontCare // set externally
  io.core.resp := DontCare
}

