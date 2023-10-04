package vector.common

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.rocket._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._

class VectorIssueInst(implicit p: Parameters) extends CoreBundle()(p) with HasVectorParams {
  val bits = UInt(32.W)
  val vconfig = new VConfig
  val vstart = UInt(log2Ceil(maxVLMax).W)
  val rs1_data = UInt(xLen.W)
  val rs2_data = UInt(xLen.W)
  val vat = UInt(vParams.vatSz.W)
  val phys = Bool()

  def opcode = bits(6,0)
  def store = opcode(5)
  def mem_idx_size = bits(13,12)
  def mem_elem_size = Mux(mop(0), vconfig.vtype.vsew, bits(13,12))
  def mop = bits(27,26)
  def vm = bits(25)
  def umop = bits(24,20)
  def nf = bits(31,29)
  def wr = mop === mopUnit && umop === lumopWhole
  def seg_nf = Mux(wr, 0.U, nf)
  def wr_nf = Mux(wr, nf, 0.U)
  def pos_lmul = Mux(vconfig.vtype.vlmul_sign, 0.U, vconfig.vtype.vlmul_mag)
  def vmu = opcode.isOneOf(opcLoad, opcStore)
  def rs1 = bits(19,15)
  def rs2 = bits(24,20)
  def rd  = bits(11,7)
  def may_write_v0 = rd === 0.U && opcode =/= opcStore
  def funct3 = bits(14,12)
  def imm4 = bits(19,15)
  def funct6 = bits(31,26)
}

class WideVectorWrite(implicit p: Parameters) extends CoreBundle()(p) with HasVectorParams {
  val eg = UInt(log2Ceil(egsTotal).W)
  val data = UInt(dLen.W)
  val mask = UInt(dLenB.W)
}

class VectorWrite(implicit p: Parameters) extends CoreBundle()(p) with HasVectorParams {
  val eg = UInt(log2Ceil(egsTotal).W)
  val data = UInt(dLen.W)
  val mask = UInt(dLenB.W)
}

class VectorReadIO(implicit p: Parameters) extends CoreBundle()(p) with HasVectorParams {
  val req = Decoupled(UInt(log2Ceil(egsTotal).W))
  val resp = Input(UInt(dLen.W))
}

class VectorIndexAccessIO(implicit p: Parameters) extends CoreBundle()(p) with HasVectorParams {
  val ready = Output(Bool())
  val valid = Input(Bool())
  val vrs = Input(UInt(5.W))
  val eidx = Input(UInt(maxVLMax.W))
  val eew = Input(UInt(2.W))
  val idx = Output(UInt(64.W))
}

class VectorBackend(implicit p: Parameters) extends CoreModule()(p) with HasVectorParams {
  val io = IO(new Bundle {
    val issue = Flipped(Decoupled(new VectorIssueInst))

    val vm = Output(UInt(maxVLMax.W))

    val mem = new VectorMemInterface

    val backend_busy = Output(Bool())
    val mem_busy = Output(Bool())
    val vm_busy = Output(Bool())

    val index_access = new VectorIndexAccessIO
  })

  require(vLen >= 64)
  require(xLen == 64)
  require(vLen >= dLen)
  require(vLen % dLen == 0)

  val vmu = Module(new VectorMemUnit)
  vmu.io.dmem <> io.mem

  val vdq = Module(new DCEQueue(new VectorIssueInst, vParams.vdqEntries))

  val vat_valids = RegInit(VecInit.fill(1 << vParams.vatSz)(false.B))
  val vat_tail = RegInit(0.U(vParams.vatSz.W))
  def vatOlder(i0: UInt, i1: UInt) = cqOlder(i0, i1, vat_tail)
  val vat_available = !vat_valids(vat_tail)
  val vat_available_count = PopCount(~vat_valids.asUInt)


  when (vdq.io.enq.fire) {
    assert(!vat_valids(vat_tail))
    vat_valids(vat_tail) := true.B
    vat_tail := vat_tail + 1.U
  }

  val issue_inst = WireInit(io.issue.bits)
  issue_inst.vat := vat_tail

  io.issue.ready   := vat_available && vdq.io.enq.ready && (!issue_inst.vmu || vmu.io.enq.ready)
  vdq.io.enq.valid := vat_available && io.issue.valid   && (!issue_inst.vmu || vmu.io.enq.ready)
  vmu.io.enq.valid := vat_available && io.issue.valid   && vdq.io.enq.ready && issue_inst.vmu

  when (io.issue.bits.vconfig.vl <= io.issue.bits.vstart) {
    io.issue.ready := true.B
    vdq.io.enq.valid := false.B
    vmu.io.enq.valid := false.B
  }

  vdq.io.enq.bits := issue_inst
  vmu.io.enq.bits := issue_inst

  vmu.io.vat_tail := vat_tail

  val vls = Module(new PipeSequencer(0, (i: VectorIssueInst) => i.vmu && !i.opcode(5),
    true, false, false, false, true))
  val vss = Module(new PipeSequencer(0, (i: VectorIssueInst) => i.vmu &&  i.opcode(5),
    false, false, false, true, true))
  val vxs = Module(new PipeSequencer(3, (i: VectorIssueInst) => !i.vmu,
    true, true, true, true, false))
  val vims = Module(new PipeSequencer(0, (i: VectorIssueInst) => i.vmu && (!i.vm || i.mop(0)),
    false, false, true, false, false))
  val seqs = Seq(vls, vss, vxs, vims)

  val vxu = Module(new VectorExecutionUnit(3))

  vdq.io.deq.ready := seqs.map(_.io.dis.ready).andR
  seqs.foreach { s =>
    s.io.dis.fire := vdq.io.deq.fire
    s.io.dis.inst := vdq.io.deq.bits
    s.io.dis.wvd := false.B
    s.io.dis.renv1 := false.B
    s.io.dis.renv2 := false.B
    s.io.dis.renvd := false.B
    s.io.dis.renvm := false.B
    s.io.dis.execmode := execRegular
    s.io.dis.seg_nf := 0.U
    s.io.dis.sub_dlen := 0.U
    s.io.dis.pipe_lat := s.depth.U
    when (s.io.vat_release.valid) {
      assert(vat_valids(s.io.vat_release.bits))
      vat_valids(s.io.vat_release.bits) := false.B
    }
    s.io.dis.vs1_eew  := vdq.io.deq.bits.vconfig.vtype.vsew
    s.io.dis.vs2_eew  := vdq.io.deq.bits.vconfig.vtype.vsew
    s.io.dis.vs3_eew  := vdq.io.deq.bits.vconfig.vtype.vsew
    s.io.dis.vd_eew   := vdq.io.deq.bits.vconfig.vtype.vsew
    s.io.dis.incr_eew := vdq.io.deq.bits.vconfig.vtype.vsew
    s.io.rvs1 := DontCare
    s.io.rvs2 := DontCare
    s.io.rvd := DontCare
  }

  vls.io.dis.wvd := true.B
  vls.io.dis.clear_vat := true.B
  vls.io.dis.seg_nf := vdq.io.deq.bits.seg_nf
  vls.io.dis.vd_eew := vdq.io.deq.bits.mem_elem_size
  vls.io.dis.incr_eew := vdq.io.deq.bits.mem_elem_size

  vss.io.dis.renvd := true.B
  vss.io.dis.clear_vat := false.B
  vss.io.dis.seg_nf := vdq.io.deq.bits.seg_nf
  vss.io.dis.sub_dlen := Mux(
    vdq.io.deq.bits.seg_nf =/= 0.U && (log2Ceil(dLenB).U > (3.U +& vss.io.dis.vs3_eew)),
    log2Ceil(dLenB).U - 3.U - vss.io.dis.vs3_eew,
    0.U)
  vss.io.dis.vs3_eew := vdq.io.deq.bits.mem_elem_size
  vss.io.dis.incr_eew := vdq.io.deq.bits.mem_elem_size


  vims.io.dis.renvm   := !vdq.io.deq.bits.vm
  vims.io.dis.renv2   := vdq.io.deq.bits.mop(0)
  vims.io.dis.vs2_eew := vdq.io.deq.bits.mem_idx_size
  vims.io.dis.execmode := execElementOrder
  vims.io.dis.clear_vat := false.B

  vxs.io.dis.clear_vat := true.B
  vxs.io.dis.renv1 := vdq.io.deq.bits.funct3.isOneOf(OPIVI, OPFVV, OPMVV)
  vxs.io.dis.renv2 := true.B
  vxs.io.dis.wvd := true.B
  when (vdq.io.deq.bits.funct3 === OPIVI) {
    vxs.io.dis.inst.rs1_data := Cat(Fill(59, vdq.io.deq.bits.imm4(4)), vdq.io.deq.bits.imm4)
  }
  when (vdq.io.deq.bits.funct3.isOneOf(OPIVI, OPMVV, OPIVI, OPIVX, OPMVX)) {
    vxs.io.dis.pipe_lat := 1.U
  }



  for ((seq, i) <- seqs.zipWithIndex) {
    val otherSeqs = seqs.zipWithIndex.filter(_._2 != i).map(_._1)
    val older_wintents = otherSeqs.map { s =>
      Mux(vatOlder(s.io.seq_hazards.vat, seq.io.seq_hazards.vat) && s.io.seq_hazards.valid,
        s.io.seq_hazards.wintent, 0.U)
    }.reduce(_|_)
    val older_rintents = (otherSeqs.map { s =>
      Mux(vatOlder(s.io.seq_hazards.vat, seq.io.seq_hazards.vat) && s.io.seq_hazards.valid,
        s.io.seq_hazards.rintent, 0.U)
    }).reduce(_|_)
    val older_pipe_writes = (otherSeqs.map(_.io.pipe_hazards).flatten.map(h =>
      Mux(vatOlder(h.bits.vat, seq.io.seq_hazards.vat) && h.valid && h.bits.hazard =/= 0.U,
        UIntToOH(h.bits.eg), 0.U)) :+ 0.U).reduce(_|_)

    seq.io.seq_hazards.writes := older_pipe_writes | older_wintents
    seq.io.seq_hazards.reads := older_rintents
  }

  val vrf = Module(new RegisterFileBank(4, 2, egsTotal))
  val vmf = Reg(Vec(egsPerVReg, Vec(dLenB, UInt(8.W))))

  var writePortId: Int = 0
  def vrfWrite(write: Valid[VectorWrite]) {
    val eg = write.bits.eg
    val wdata = write.bits.data.asTypeOf(Vec(dLenB, UInt(8.W)))
    val wmask = write.bits.mask.asBools
    vrf.io.write(writePortId) := write
    writePortId = writePortId + 1
    when (write.valid && eg < egsPerVReg.U) {
      for (i <- 0 until dLenB) when (wmask(i)) { vmf(eg)(i) := wdata(i) }
    }
  }

  vmu.io.lresp.ready := vls.io.iss.valid
  vls.io.iss.ready := vmu.io.lresp.valid

  val resetting = RegInit(true.B)
  val reset_ctr = RegInit(0.U(log2Ceil(egsTotal).W))
  when (resetting) {
    reset_ctr := reset_ctr + 1.U
    io.issue.ready := false.B
  }
  when (~reset_ctr === 0.U) { resetting := false.B }

  val load_write = Wire(Valid(new VectorWrite))
  load_write.valid := vls.io.iss.fire
  load_write.bits.eg := vls.io.iss.bits.wvd_eg
  load_write.bits.data := vmu.io.lresp.bits
  load_write.bits.mask := vls.io.iss.bits.wmask
  when (!vls.io.iss.bits.inst.vm &&
    (vmf.asUInt & UIntToOH(vls.io.iss.bits.eidx)) === 0.U) {
    load_write.bits.mask := 0.U(dLenB.W)
  }

  when (resetting) {
    load_write.valid := true.B
    load_write.bits.eg := reset_ctr
    load_write.bits.data := 0.U
    load_write.bits.mask := ~(0.U(dLenB.W))
  }

  vrfWrite(load_write)

  // Read ports are
  // vss-vrd
  // vxs-vrs1
  // vxs-vrs2
  // vxs-vrs3, vmu-index, frontend-index
  val reads = Seq(1, 1, 1, 3).zipWithIndex.map { case (rc, i) =>
    val arb = Module(new RegisterReadXbar(1, rc))
    vrf.io.read(i) <> arb.io.out(0)
    arb.io.in
  }

  reads(0)(0) <> vss.io.rvd
  vmu.io.sdata.valid   := vss.io.iss.valid
  vmu.io.sdata.bits    := vss.io.iss.bits.rvd_data
  vss.io.iss.ready     := vmu.io.sdata.ready

  reads(1)(0) <> vxs.io.rvs1
  reads(2)(0) <> vxs.io.rvs2
  reads(3)(0) <> vxs.io.rvd

  reads(3)(1) <> vims.io.rvs2
  vims.io.rvs1.req.ready := true.B

  val maskindex_q = Module(new Queue(new MaskIndex, 1, pipe=true, flow=true))
  vmu.io.maskindex <> maskindex_q.io.deq

  maskindex_q.io.enq.valid := vims.io.iss.valid
  val index_shifted = (vims.io.iss.bits.rvs2_data >> ((vims.io.iss.bits.eidx << vims.io.iss.bits.inst.mem_idx_size)(dLenOffBits-1,0) << 3))
  maskindex_q.io.enq.bits.index := index_shifted & eewBitMask(vims.io.iss.bits.inst.mem_idx_size)
  maskindex_q.io.enq.bits.mask  := vmf.asUInt >> vims.io.iss.bits.eidx
  maskindex_q.io.enq.bits.load  := !vims.io.iss.bits.inst.opcode(5)
  vims.io.iss.ready      := maskindex_q.io.enq.ready

  reads(3)(2).req.valid := io.index_access.valid
  io.index_access.ready := reads(3)(2).req.ready
  reads(3)(2).req.bits  := getEgId(io.index_access.vrs, io.index_access.eidx, io.index_access.eew)
  io.index_access.idx   := reads(3)(2).resp >> ((io.index_access.eidx << io.index_access.eew)(dLenOffBits-1,0) << 3) & eewBitMask(io.index_access.eew)

  when (vmu.io.vat_release.valid) {
    assert(vat_valids(vmu.io.vat_release.bits))
    vat_valids(vmu.io.vat_release.bits) := false.B
  }


  vxu.io.iss <> vxs.io.iss
  when (vxs.io.iss.bits.inst.funct3.isOneOf(OPIVI, OPIVX, OPMVX)) {
    val scalar = vxs.io.iss.bits.inst.rs1_data
    vxu.io.iss.bits.rvs1_data := dLenSplat(scalar, vxu.io.iss.bits.rvs1_eew)
  }
  vrfWrite(vxu.io.write)

  val seq_inflight_wv0 = (seqs.map(_.io.seq_hazards).map { h =>
    h.valid && ((h.wintent & ~(0.U(egsPerVReg.W))) =/= 0.U)
  } ++ seqs.map(_.io.pipe_hazards).flatten.map { h =>
    h.valid && h.bits.hazard && (h.bits.eg < egsPerVReg.U)
  }).orR
  val vdq_inflight_wv0 = vdq.io.peek.map { h =>
    h.valid && h.bits.may_write_v0
  }.orR


  io.mem_busy := vmu.io.busy
  io.vm := vmf.asUInt
  io.vm_busy := seq_inflight_wv0 || vdq_inflight_wv0
  io.backend_busy := vdq.io.deq.valid || seqs.map(_.io.busy).orR || resetting
}
