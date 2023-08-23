package vref

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.rocket._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._

class VectorIssueInst(val params: VREFVectorParams)(implicit p: Parameters) extends CoreBundle()(p) with VectorConsts {
  val bits = UInt(32.W)
  val vconfig = new VConfig
  val vstart = UInt(log2Ceil(maxVLMax).W)
  val rs1_data = UInt(xLen.W)
  val rs2_data = UInt(xLen.W)
  val vat = UInt(params.vatSz.W)

  def opcode = bits(6,0)
  def mem_size = bits(13,12)
  def mop = bits(27,26)
  def vm = bits(25)
  def umop = bits(24,20)
  def nf = bits(31,29)
  def pos_lmul = Mux(vconfig.vtype.vlmul_sign, 0.U, vconfig.vtype.vlmul_mag)
  def vmu = opcode.isOneOf(opcLoad, opcStore)
  def rs1 = bits(19,15)
  def rs2 = bits(24,20)
  def rd  = bits(11,7)
  def may_write_v0 = rd === 0.U && opcode =/= opcStore
}

case class VREFVectorParams(
  viqEntries: Int = 4,
  vdqEntries: Int = 4,
  vlaqEntries: Int = 4,
  vsaqEntries: Int = 4,
  vatSz: Int = 3) {
  require(viqEntries >= 3)
}

trait HasVREFVectorParams extends VectorConsts { this: HasCoreParameters =>
  val params: VREFVectorParams
  def dLen = vMemDataBits
  def dLenB = dLen / 8
  def dLenOffBits = log2Ceil(dLenB)

  def egsPerVReg = vLen / dLen
  def egsTotal = (vLen / dLen) * 32

  def getEgId(vreg: UInt, eidx: UInt, eew: UInt): UInt = {
    val base = vreg << log2Ceil(egsPerVReg)
    val off = eidx >> (log2Ceil(dLenB).U - eew)
    base + off
  }
}

class VREFVectorUnit(val params: VREFVectorParams)(implicit p: Parameters) extends RocketVectorUnit()(p) with HasVREFVectorParams {
  require(vLen >= 64)
  require(xLen == 64)
  require(vLen >= dLen)
  require(vLen % dLen == 0)

  val tlb_arbiter = Module(new TLBArbiter)
  tlb_arbiter.io.out <> io.tlb

  val trap_check = Module(new VREFFrontendTrapCheck(params))
  trap_check.io.core <> io.core
  trap_check.io.tlb <> tlb_arbiter.io.in1

  val viq = Module(new DCEQueue(new VectorIssueInst(params), params.viqEntries))
  trap_check.io.issue_credits := viq.io.count - viq.entries.U
  viq.io.enq.valid := trap_check.io.issue.valid
  viq.io.enq.bits := trap_check.io.issue.bits
  assert(!(viq.io.enq.valid && !viq.io.enq.ready))

  val vat_valids = RegInit(VecInit.fill(1 << params.vatSz)(false.B))
  val vat_tail = RegInit(0.U(params.vatSz.W))
  def vatOlder(i0: UInt, i1: UInt) = (i0 < i1) ^ (i0 < vat_tail) ^ (i1 < vat_tail)
  val vat_available = !vat_valids(vat_tail)

  when (viq.io.deq.fire) {
    vat_valids(vat_tail) := true.B
    vat_tail := vat_tail + 1.U
  }

  val viq_issue_inst = Wire(new VectorIssueInst(params))
  viq_issue_inst := viq.io.deq.bits
  viq_issue_inst.vat := vat_tail


  val vmu = Module(new VectorMemUnit(params))
  vmu.io.status := io.core.status
  vmu.io.tlb <> tlb_arbiter.io.in0
  vmu.io.dmem <> io.dmem

  val vdq = Module(new DCEQueue(new VectorIssueInst(params), params.vdqEntries))

  viq.io.deq.ready := vat_available && vdq.io.enq.ready && (!viq.io.deq.bits.vmu || vmu.io.enq.ready)
  vdq.io.enq.valid := vat_available && viq.io.deq.valid && (!viq.io.deq.bits.vmu || vmu.io.enq.ready)
  vmu.io.enq.valid := vat_available && viq.io.deq.valid && viq.io.deq.bits.vmu

  vdq.io.enq.bits := viq_issue_inst
  vmu.io.enq.bits := viq_issue_inst



  val vls = Module(new PipeSequencer(0, (i: VectorIssueInst) => i.vmu && !i.opcode(5),
    true, false, false, false, params))
  val vss = Module(new PipeSequencer(0, (i: VectorIssueInst) => i.vmu &&  i.opcode(5),
    false, false, false, true, params))
  val vxs = Module(new PipeSequencer(3, (i: VectorIssueInst) => !i.vmu,
    true, false, false, false, params))
  val seqs = Seq(vls, vss, vxs)


  vdq.io.deq.ready := seqs.map(_.io.dis_ready).andR
  seqs.foreach { s =>
    s.io.dis_valid := vdq.io.deq.valid
    s.io.dis := vdq.io.deq.bits
    s.io.dis_wvd := false.B
    s.io.dis_renv1 := false.B
    s.io.dis_renv2 := false.B
    s.io.dis_renvd := false.B
    s.io.dis_renvm := false.B
    s.io.dis_execmode := execRegular
    when (s.io.vat_release.valid) {
      assert(vat_valids(s.io.vat_release.bits))
      vat_valids(s.io.vat_release.bits) := false.B
    }
  }

  vls.io.dis_wvd := true.B
  vls.io.dis_renvm := !vdq.io.deq.bits.vm
  vls.io.dis_execmode := Mux(vdq.io.deq.bits.mop === mopUnit && vdq.io.deq.bits.vstart === 0.U && vdq.io.deq.bits.vm,
    execRegular, execElementOrder)
  vls.io.dis.vconfig.vtype.vsew := vdq.io.deq.bits.mem_size

  vss.io.dis_renvd := true.B
  vss.io.dis_renvm := vdq.io.deq.bits.vm
  vss.io.dis_execmode := Mux(vdq.io.deq.bits.mop === mopUnit && vdq.io.deq.bits.vstart === 0.U && vdq.io.deq.bits.vm,
    execRegular, execElementOrder)

  for ((seq, i) <- seqs.zipWithIndex) {
    val otherSeqs = seqs.zipWithIndex.filter(_._2 != i).map(_._1)
    val older_wintents = otherSeqs.map { s =>
      Mux(vatOlder(s.io.seq_hazards.vat, seq.io.seq_hazards.vat) && s.io.seq_hazards.valid,
        s.io.seq_hazards.wintent, 0.U)
    }.reduce(_|_)
    val older_rintents = (otherSeqs.map { s =>
      Mux(vatOlder(s.io.seq_hazards.vat, seq.io.seq_hazards.vat) && s.io.seq_hazards.valid,
        s.io.seq_hazards.rintent, 0.U)
    } :+ Mux(vatOlder(vmu.io.vm_hazard.vat, seq.io.seq_hazards.vat) && vmu.io.vm_hazard.valid,
      ~(0.U(egsPerVReg.W)), 0.U)
    ).reduce(_|_)
    val older_pipe_writes = (otherSeqs.map(_.io.pipe_hazards).flatten.map(h =>
      Mux(vatOlder(h.bits.vat, seq.io.seq_hazards.vat) && h.valid, UIntToOH(h.bits.eg), 0.U)) :+ 0.U).reduce(_|_)

    seq.io.seq_hazards.writes := older_pipe_writes | older_wintents
    seq.io.seq_hazards.reads := older_rintents
  }
  vmu.io.vm_hazard.hazard := seqs.map { s =>
    val same = s.io.seq_hazards.vat === vmu.io.vm_hazard.vat
    val older = vatOlder(s.io.seq_hazards.vat, vmu.io.vm_hazard.vat)
    !same && older && s.io.seq_hazards.valid && s.io.seq_hazards.wintent(egsPerVReg-1,0) =/= 0.U
  }.orR || seqs.map(_.io.pipe_hazards).flatten.map { h =>
    val same = h.bits.vat === vmu.io.vm_hazard.vat
    val older = vatOlder(h.bits.vat, vmu.io.vm_hazard.vat)
    !same && older && h.valid && h.bits.eg < egsPerVReg.U
  }.orR

  val vrf = Mem(egsTotal, Vec(dLenB, UInt(8.W)))
  val vmf = Reg(Vec(egsPerVReg, Vec(dLenB, UInt(8.W))))

  def vrfWrite(eg: UInt, data: UInt, mask: UInt) {
    val wdata = data.asTypeOf(Vec(dLenB, UInt(8.W)))
    val wmask = mask.asBools
    vrf.write(eg, wdata, wmask)
    when (eg < egsPerVReg.U) {
      for (i <- 0 until dLenB) when (mask(i)) { vmf(eg)(i) := wdata(i) }
    }
  }

  vmu.io.load.ready := vls.io.iss.valid
  vls.io.iss.ready := vmu.io.load.valid
  vxs.io.iss.ready := false.B

  val resetting = RegInit(true.B)
  val reset_ctr = RegInit(0.U(log2Ceil(egsTotal).W))
  when (resetting) { reset_ctr := reset_ctr + 1.U }
  when (~reset_ctr === 0.U) { resetting := false.B }

  when (vls.io.iss.fire || resetting) {
    val eg = Wire(UInt(log2Ceil(egsTotal).W))
    val data = Wire(UInt(dLen.W))
    val mask = Wire(UInt(dLenB.W))
    when (resetting) {
      eg := reset_ctr
      data := 0.U
      mask := ~(0.U(dLenB.W))
    } .otherwise {
      eg := vls.io.iss.bits.wvd_eg
      data := vmu.io.load.bits
      mask := vls.io.iss.bits.wmask

      when (!vls.io.iss.bits.inst.vm &&
        (vmf.asUInt & UIntToOH(vls.io.iss.bits.eidx)) === 0.U) {
        mask := 0.U(dLenB.W)
      }
    }
    vrfWrite(eg, data, mask)
  }

  vss.io.iss.ready := vmu.io.vstdata.ready
  vmu.io.vstdata.valid := vss.io.iss.valid
  vmu.io.vstdata.bits.data := vrf.read(vss.io.iss.bits.rvd_eg).asUInt
  vmu.io.vstdata.bits.mask := vss.io.iss.bits.wmask
  vmu.io.vm := vmf.asUInt

  val inflight_mem = (viq.io.peek ++ vdq.io.peek).map(e => e.valid && e.bits.vmu).orR || vls.io.valid || vss.io.valid || vmu.io.busy
  trap_check.io.inflight_mem := inflight_mem
  trap_check.io.resetting := resetting
  trap_check.io.vm := vmf.asUInt
  val seq_inflight_wv0 = (seqs.map(_.io.seq_hazards).map { h =>
    h.valid && ((h.wintent & ~(0.U(egsPerVReg.W))) =/= 0.U)
  } ++ seqs.map(_.io.pipe_hazards).flatten.map { h =>
    h.valid && (h.bits.eg < egsPerVReg.U)
  }).orR
  val vdq_viq_inflight_wv0 = (vdq.io.peek ++ viq.io.peek).map { h =>
    h.valid && h.bits.may_write_v0
  }.orR
  trap_check.io.vm_ready := !(seq_inflight_wv0 || vdq_viq_inflight_wv0)

  io.core.backend_busy := viq.io.deq.valid || vdq.io.deq.valid || resetting

}
