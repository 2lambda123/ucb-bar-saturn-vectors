package saturn.backend

import chisel3._
import chisel3.util._
import chisel3.experimental.dataview._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tile._
import freechips.rocketchip.util._
import saturn.mem._
import saturn.exu._
import saturn.common._
import saturn.insns._

class VectorBackend(implicit p: Parameters) extends CoreModule()(p) with HasVectorParams {
  val io = IO(new Bundle {
    val dis = Flipped(Decoupled(new VectorIssueInst))

    val vmu = Flipped(new VectorMemDatapathIO)

    val busy = Output(Bool())

    val index_access = new VectorIndexAccessIO
    val mask_access = new VectorMaskAccessIO

    val scalar_resp = Decoupled(new ScalarWrite)

    val set_vxsat = Output(Bool())
    val set_fflags = Output(Valid(UInt(5.W)))

    val fp_req = Decoupled(new FPInput())
    val fp_resp = Flipped(Valid(new FPResult()))

    val vat_tail = Input(UInt(vParams.vatSz.W))
    val vat_head = Input(UInt(vParams.vatSz.W))

    val vat_release = Output(Vec(nRelease, Valid(UInt(vParams.vatSz.W))))
  })

  require(vLen >= 64)
  require(xLen == 64)
  require(vLen >= dLen)
  require(vLen % dLen == 0)

  val vdq = Module(new DCEQueue(new VectorIssueInst, vParams.vdqEntries))

  def vatOlder(i0: UInt, i1: UInt) = cqOlder(i0, i1, io.vat_tail)

  vdq.io.enq <> io.dis

  val integerFUs = Seq(
    (() => new IntegerPipe, "vintfu"),
    (() => new ShiftPipe, "vshiftfu"),
    (() => new BitwisePipe, "vbitfu"),
    (() => new IterativeIntegerDivider(vParams.useIterativeIMul), "vdivfu"),
    (() => new MaskUnit, "vmaskfu"),
    (() => new PermuteUnit, "vpermfu"),
  )

  val integerMul = (!vParams.useIterativeIMul).option((if (vParams.useSegmentedIMul)
    (() => new SegmentedMultiplyPipe(vParams.imaPipeDepth), "vsegmulfu")
  else
    (() => new ElementwiseMultiplyPipe(vParams.imaPipeDepth), "velemmulfu"))
  )

  val fpFMA = if (vParams.useScalarFPFMA) {
    (() => new SharedScalarElementwiseFPFMA(vParams.fmaPipeDepth), "vsharedfpfma")
  } else {
    (() => new FPFMAPipe(vParams.fmaPipeDepth), "vfpfma")
  }

  val fpMISCs = if (vParams.useScalarFPMisc) Seq(
    (() => new SharedScalarElementwiseFPMisc, "vsharedfpmisc")
  ) else Seq(
    (() => new FPDivSqrt, "vfdivfu"),
    (() => new FPCompPipe, "vfcmpfu"),
    (() => new FPConvPipe, "vfcvtfu")
  )

  val perm_buffer = Module(new Compactor(dLenB, dLenB, UInt(8.W), false))

  val vxus = vParams.issStructure match {
    case VectorIssueStructure.Unified => {
      Seq(ExecutionUnit.instantiate("", integerFUs ++ fpMISCs ++ Seq(fpFMA) ++ integerMul))
    }
    case _ => {
      require(!vParams.useScalarFPFMA)
      Seq(
        ExecutionUnit.instantiate("_int", integerFUs ++ fpMISCs),
        ExecutionUnit.instantiate("_fp", Seq(fpFMA) ++ integerMul)
      )
    }
  }

  val vlissq = Module(new IssueQueue(vParams.vlissqEntries, 1))
  val vsissq = Module(new IssueQueue(vParams.vsissqEntries, 1))
  val vxissqs = vParams.issStructure match {
    case VectorIssueStructure.Split => vxus.map { vxu =>
      Module(new IssueQueue(vParams.vxissqEntries, 1)).suggestName(s"vxissq${vxu.suffix}")
    }
    case _ => Seq(Module(new IssueQueue(vParams.vxissqEntries, vxus.size)).suggestName("vxissq"))
  }
  val vpissq = Module(new IssueQueue(vParams.vpissqEntries, 1))

  val vls = Module(new LoadSequencer)
  val vss = Module(new StoreSequencer)
  val vxs = vxus.map { xu => Module(new ExecuteSequencer(xu.supported_insns)).suggestName(s"vxs${xu.suffix}") }
  val vps = Module(new PermuteSequencer(vxus.head.supported_insns))

  io.fp_req <> vxus.head.io.shared_fp_req
  vxus.head.io.shared_fp_resp <> io.fp_resp
  vxus.tail.foreach { vxu =>
    vxu.io.shared_fp_req.ready := false.B
    vxu.io.shared_fp_resp.valid := false.B
    vxu.io.shared_fp_resp.bits := DontCare
    vxu.io.scalar_write.ready := false.B
    assert(!vxu.io.scalar_write.valid)
    assert(!vxu.io.shared_fp_req.valid)
  }
  vxs.tail.foreach { seq =>
    assert(!seq.io.perm.req.valid)
  }

  case class IssueGroup(
    issq: IssueQueue,
    seqs: Seq[PipeSequencer[_]])

  val issGroups = Seq(
    IssueGroup(vlissq, Seq(vls)),
    IssueGroup(vsissq, Seq(vss)),
    IssueGroup(vpissq, Seq(vps))
  ) ++ (vParams.issStructure match {
    case VectorIssueStructure.Split => vxs.zip(vxissqs).map { case (seq, vxissq) =>
      IssueGroup(vxissq, Seq(seq))
    }
    case _ => Seq(IssueGroup(vxissqs(0), vxs))
  })
  val issqs = issGroups.map(_.issq)
  val allSeqs = issGroups.map(_.seqs).flatten

  vlissq.io.enq.bits.reduction := false.B
  vlissq.io.enq.bits.wide_vd := false.B
  vlissq.io.enq.bits.wide_vs2 := false.B
  vlissq.io.enq.bits.writes_mask := false.B
  vlissq.io.enq.bits.reads_vs1_mask := false.B
  vlissq.io.enq.bits.reads_vs2_mask := false.B
  vlissq.io.enq.bits.nf_log2 := log2_up(vdq.io.deq.bits.nf, 8)
  vlissq.io.enq.bits.renv1 := false.B
  vlissq.io.enq.bits.renv2 := false.B
  vlissq.io.enq.bits.renvd := false.B
  vlissq.io.enq.bits.renvm := !vdq.io.deq.bits.vm
  vlissq.io.enq.bits.wvd   := true.B
  vlissq.io.enq.bits.scalar_to_vd0 := false.B
  vlissq.io.enq.bits.rs1_is_rs2 := false.B

  vsissq.io.enq.bits.reduction := false.B
  vsissq.io.enq.bits.wide_vd := false.B
  vsissq.io.enq.bits.wide_vs2 := false.B
  vsissq.io.enq.bits.writes_mask := false.B
  vsissq.io.enq.bits.reads_vs1_mask := false.B
  vsissq.io.enq.bits.reads_vs2_mask := false.B
  vsissq.io.enq.bits.nf_log2 := log2_up(vdq.io.deq.bits.nf, 8)
  vsissq.io.enq.bits.renv1 := false.B
  vsissq.io.enq.bits.renv2 := false.B
  vsissq.io.enq.bits.renvd := true.B
  vsissq.io.enq.bits.renvm := !vdq.io.deq.bits.vm && vdq.io.deq.bits.mop === mopUnit
  vsissq.io.enq.bits.wvd   := false.B
  vsissq.io.enq.bits.scalar_to_vd0 := false.B
  vsissq.io.enq.bits.rs1_is_rs2 := false.B

  vpissq.io.enq.bits.reduction := false.B
  vpissq.io.enq.bits.wide_vd := false.B
  vpissq.io.enq.bits.wide_vs2 := false.B
  vpissq.io.enq.bits.writes_mask := false.B
  vpissq.io.enq.bits.reads_vs1_mask := false.B
  vpissq.io.enq.bits.reads_vs2_mask := false.B
  vpissq.io.enq.bits.nf_log2 := 0.U
  vpissq.io.enq.bits.renv1 := false.B
  vpissq.io.enq.bits.renv2 := vdq.io.deq.bits.mop(0) || !vdq.io.deq.bits.vmu
  vpissq.io.enq.bits.renvd := true.B
  vpissq.io.enq.bits.renvm := !vdq.io.deq.bits.vm && vdq.io.deq.bits.mop =/= mopUnit && vdq.io.deq.bits.vmu
  vpissq.io.enq.bits.wvd   := false.B
  vpissq.io.enq.bits.scalar_to_vd0 := false.B
  vpissq.io.enq.bits.rs1_is_rs2 := !vdq.io.deq.bits.vmu && (vdq.io.deq.bits.opif6 === OPIFunct6.rgather || (vdq.io.deq.bits.funct3 === OPIVV && vdq.io.deq.bits.opif6 === OPIFunct6.rgatherei16))

  val xdis_ctrl = new VectorDecoder(vdq.io.deq.bits.funct3, vdq.io.deq.bits.funct6, vdq.io.deq.bits.rs1, vdq.io.deq.bits.rs2, vxus.map(_.supported_insns).flatten,
    Seq(Reduction, Wide2VD, Wide2VS2, WritesAsMask, ReadsVS1AsMask, ReadsVS2AsMask, ReadsVS1, ReadsVS2, ReadsVD, VMBitReadsVM, AlwaysReadsVM, WritesVD, WritesScalar, ScalarToVD0))
  vxissqs.foreach { vxissq =>
    vxissq.io.enq.bits.wide_vd := xdis_ctrl.bool(Wide2VD)
    vxissq.io.enq.bits.wide_vs2 := xdis_ctrl.bool(Wide2VS2)
    vxissq.io.enq.bits.writes_mask := xdis_ctrl.bool(WritesAsMask)
    vxissq.io.enq.bits.reads_vs1_mask := xdis_ctrl.bool(ReadsVS1AsMask)
    vxissq.io.enq.bits.reads_vs2_mask := xdis_ctrl.bool(ReadsVS2AsMask)
    vxissq.io.enq.bits.nf_log2 := 0.U
    vxissq.io.enq.bits.renv1 := xdis_ctrl.bool(ReadsVS1)
    vxissq.io.enq.bits.renv2 := xdis_ctrl.bool(ReadsVS2)
    vxissq.io.enq.bits.renvd := xdis_ctrl.bool(ReadsVD)
    vxissq.io.enq.bits.renvm := (!vdq.io.deq.bits.vm && xdis_ctrl.bool(VMBitReadsVM)) || xdis_ctrl.bool(AlwaysReadsVM)
    vxissq.io.enq.bits.wvd := !xdis_ctrl.bool(WritesScalar)
    vxissq.io.enq.bits.scalar_to_vd0 := xdis_ctrl.bool(ScalarToVD0)
    vxissq.io.enq.bits.reduction := xdis_ctrl.bool(Reduction)
    vxissq.io.enq.bits.rs1_is_rs2 := false.B
    when (vdq.io.deq.bits.funct3 === OPIVV && vdq.io.deq.bits.funct6 === OPIFunct6.mvnrr.litValue.U) {
      vxissq.io.enq.bits.emul := log2_up(vdq.io.deq.bits.nf, 8)
    }
  }

  val issq_stall = Wire(Vec(issGroups.size, Bool()))
  vdq.io.deq.ready := !issq_stall.orR

  for ((group, i) <- issGroups.zipWithIndex) {
    val otherIssGroups = issGroups.zipWithIndex.filter(_._2 != i).map(_._1)
    val otherIssqs = otherIssGroups.map(_.issq)
    val otherIssqSeqs = otherIssGroups.map(_.seqs).flatten

    for ((seq, j) <- group.seqs.zipWithIndex) {
      val otherSameIssqSeqs = group.seqs.zipWithIndex.filter(_._2 != j).map(_._1)
      val otherSeqs = otherIssqSeqs ++ otherSameIssqSeqs

      val vat = seq.io.vat

      seq.io.rvs1 := DontCare
      seq.io.rvs2 := DontCare
      seq.io.rvd := DontCare
      seq.io.rvm := DontCare
      seq.io.perm := DontCare
      seq.io.acc.valid := false.B
      seq.io.acc.bits := DontCare
      seq.io.vat_head := io.vat_head

      val older_issq_wintents = FillInterleaved(egsPerVReg, otherIssqs.map { i =>
        i.io.hazards.map(h => Mux(vatOlder(h.bits.vat, vat) && h.valid, h.bits.wintent, 0.U))
      }.flatten.foldLeft(0.U)(_|_))
      val older_seq_wintents = otherSeqs.map { s =>
        Mux(vatOlder(s.io.seq_hazard.bits.vat, vat) && s.io.seq_hazard.valid, s.io.seq_hazard.bits.wintent, 0.U)
      }.reduce(_|_)
      val older_wintents = older_issq_wintents | older_seq_wintents

      val older_issq_rintents = FillInterleaved(egsPerVReg, otherIssqs.map { i =>
        i.io.hazards.map(h => Mux(vatOlder(h.bits.vat, vat) && h.valid, h.bits.rintent, 0.U))
      }.flatten.foldLeft(0.U)(_|_))
      val older_seq_rintents = otherSeqs.map { s =>
        Mux(vatOlder(s.io.seq_hazard.bits.vat, vat) && s.io.seq_hazard.valid, s.io.seq_hazard.bits.rintent, 0.U)
      }.reduce(_|_)
      val older_rintents = older_issq_rintents | older_seq_rintents

      val older_pipe_writes = vxus.map(_.io.pipe_hazards.toSeq).flatten.map { h =>
        Mux(h.valid, h.bits.eg_oh, 0.U)
      }.reduce(_|_)

      val older_iter_writes = vxus.map(_.io.iter_hazards.toSeq).flatten.map { h =>
        Mux(h.valid, h.bits.eg_oh, 0.U)
      }.reduce(_|_)

      seq.io.older_writes := older_pipe_writes | older_iter_writes | older_wintents
      seq.io.older_reads := older_rintents
    }

    issq_stall(i) := group.seqs.map(_.accepts(vdq.io.deq.bits)).reduce(_ || _) && !group.issq.io.enq.ready
    val accepts = group.seqs.map(_.accepts(vdq.io.deq.bits))
    group.issq.io.enq.valid := vdq.io.deq.valid && !issq_stall.orR && accepts.orR
    group.issq.io.enq.bits.viewAsSupertype(new VectorIssueInst) := vdq.io.deq.bits
    group.issq.io.enq.bits.seq := VecInit(accepts).asUInt

    group.seqs.zipWithIndex.foreach{ case(s, j) =>
      s.io.dis.valid := group.issq.io.deq.valid && group.issq.io.deq.bits.seq(j)
      s.io.dis.bits := group.issq.io.deq.bits.viewAsSupertype(new BackendIssueInst)
    }
    group.issq.io.deq.ready := Mux1H(group.issq.io.deq.bits.seq, group.seqs.map(_.io.dis.ready))
  }

  // Hazard checking for multi-VXS
  // Check if there is a VRF write port hazard against the in-flight insns in other VXUs
  // Check if there is a VRF write port hazard against a simultaneously issuing insn
  //  from another VXS (check that it's actually a valid hazard)
  val inflight_hazards = WireInit(VecInit(Seq.fill(vxs.length)(false.B)))

  for (i <- 0 until vxs.length) {
    val other_vxu_idx = (0 until vxs.length).filter(_ != i)

    val inflight_hazard = other_vxu_idx.map(vxus(_).io.pipe_hazards).flatten.map { hazard =>
      hazard.valid &&
      (hazard.bits.latency === vxus(i).io.issue_pipe_latency) &&
      (hazard.bits.eg(vrfBankBits-1,0) === vxs(i).io.iss.bits.wvd_eg(vrfBankBits-1,0))
    }.reduceOption(_ || _).getOrElse(false.B)

    inflight_hazards(i) := inflight_hazard

    val issue_hazard = other_vxu_idx.map { other_iss =>
      (vxus(other_iss).io.issue_pipe_latency === vxus(i).io.issue_pipe_latency) &&
      (vxs(other_iss).io.iss.bits.wvd_eg(vrfBankBits-1,0) === vxs(i).io.iss.bits.wvd_eg(vrfBankBits-1,0)) &&
      vatOlder(vxs(other_iss).io.iss.bits.vat, vxs(i).io.iss.bits.vat) &&
      !inflight_hazards(other_iss) &&
      vxs(other_iss).io.iss.valid &&
      vxus(other_iss).io.iss.ready
    }.reduceOption(_ || _).getOrElse(false.B)

    vxus(i).io.iss.valid := vxs(i).io.iss.valid && !inflight_hazard && !issue_hazard
    vxs(i).io.iss.ready := vxus(i).io.iss.ready && !inflight_hazard && !issue_hazard
    vxus(i).io.iss.bits := vxs(i).io.iss.bits
    vxs(i).io.acc := vxus(i).io.acc_write
  }

  // Read ports are
  // vxs0-vrs1, vxs1-vrs1, vmu-index, frontend-index
  // vxs0-vrs2, vxs1-vrs2
  // vxs0-vrs3, vxs1-vrs3, vss-vrd
  // vxs0-mask, vxs1-mask, vls-mask, vss-mask, vps-mask, frontend-mask
  // Mask ports are
  // vxs0-mask, vxs1-mask, vls-mask, vss-mask, vps-mask, frontend-mask
  val vrf = Module(new RegisterFile(
    reads = Seq(2 + vxs.size, vxs.size, 1 + vxs.size),
    maskReads = Seq(4 + vxs.size),
    pipeWrites = vxus.size,
    llWrites = vxus.size + 2 // vxus + load + reset
  ))

  val load_write = Wire(Decoupled(new VectorWrite(dLen)))
  io.vmu.lresp.ready := vls.io.iss.valid && load_write.ready
  vls.io.iss.ready := io.vmu.lresp.valid && load_write.ready
  load_write.valid := vls.io.iss.valid && io.vmu.lresp.valid
  load_write.bits.eg   := vls.io.iss.bits.wvd_eg
  load_write.bits.data := io.vmu.lresp.bits.data
  load_write.bits.mask := FillInterleaved(8, vls.io.iss.bits.wmask)
  when (io.vmu.lresp.fire) {
    assert(io.vmu.lresp.bits.debug_id === vls.io.iss.bits.debug_id)
  }

  val resetting = RegInit(true.B)
  val reset_ctr = RegInit(0.U(log2Ceil(egsTotal).W))
  when (resetting) {
    reset_ctr := reset_ctr + 1.U
    io.dis.ready := false.B
  }
  when (~reset_ctr === 0.U) { resetting := false.B }

  // Write ports
  vrf.io.pipe_writes.zip(vxus).foreach { case (w,vxu) =>
    w := vxu.io.pipe_write
  }

  vrf.io.ll_writes(0) <> load_write
  vrf.io.ll_writes(1).valid     := resetting
  vrf.io.ll_writes(1).bits.eg   := reset_ctr
  vrf.io.ll_writes(1).bits.data := 0.U
  vrf.io.ll_writes(1).bits.mask := ~(0.U(dLen.W))
  vxus.zipWithIndex.foreach { case (vxu,i) =>
    vrf.io.ll_writes(2+i) <> vxu.io.iter_write
  }

  vxs.zipWithIndex.foreach { case(xs, i) =>
    vrf.io.read(0)(i) <> vxs(i).io.rvs1
    vrf.io.read(1)(i) <> vxs(i).io.rvs2
    vrf.io.read(2)(i) <> vxs(i).io.rvd
    vrf.io.mask_read(0)(i) <> vxs(i).io.rvm
  }

  vrf.io.read(0)(vxs.length) <> vps.io.rvs2
  vps.io.rvs1.req.ready := true.B

  val index_access_eg = getEgId(io.index_access.vrs, io.index_access.eidx, io.index_access.eew, false.B)
  val index_access_eg_oh = UIntToOH(index_access_eg)
  val index_access_hazard = (allSeqs.map(_.io.seq_hazard).map { h =>
    h.valid && ((h.bits.wintent & index_access_eg_oh) =/= 0.U)
  } ++ issqs.map(_.io.hazards).flatten.map { h =>
    h.valid && h.bits.wintent(io.index_access.vrs)
  } ++ vxus.map(_.io.pipe_hazards).flatten.map { h =>
    h.valid && h.bits.eg === index_access_eg
  } ++ vxus.map(_.io.iter_hazards).flatten.map { h =>
    h.valid && h.bits.eg === index_access_eg
  }).orR || vdq.io.peek.map(i => i.valid && !(i.bits.vmu && i.bits.store)).orR
  // TODO: this conservatively assumes a index data hazard against anything in the vdq

  val index_access_ready_pipe = RegNext(vrf.io.read(0)(vxs.length+1).req.ready && !index_access_hazard && io.index_access.valid, false.B)
  val index_access_idx_pipe = RegNext(vrf.io.read(0)(vxs.length+1).resp >> ((io.index_access.eidx << io.index_access.eew)(dLenOffBits-1,0) << 3) & eewBitMask(io.index_access.eew))

  vrf.io.read(0)(vxs.length+1).req.valid := io.index_access.valid && !index_access_hazard
  io.index_access.ready := index_access_ready_pipe
  vrf.io.read(0)(vxs.length+1).req.bits.eg  := index_access_eg
  vrf.io.read(0)(vxs.length+1).req.bits.oldest  := false.B
  io.index_access.idx := index_access_idx_pipe

  vrf.io.read(2)(vxs.length) <> vss.io.rvd
  io.vmu.sdata.valid   := vss.io.iss.valid
  io.vmu.sdata.bits    := vss.io.iss.bits
  vss.io.iss.ready     := io.vmu.sdata.ready

  vrf.io.mask_read(0)(vxs.length) <> vls.io.rvm
  vrf.io.mask_read(0)(vxs.length+1) <> vss.io.rvm
  vrf.io.mask_read(0)(vxs.length+2) <> vps.io.rvm
  val vm_busy = Wire(Bool())
  vrf.io.mask_read(0)(vxs.length+3).req.valid    := io.mask_access.valid && !vm_busy
  vrf.io.mask_read(0)(vxs.length+3).req.bits.eg  := getEgId(0.U, io.mask_access.eidx, 0.U, true.B)
  vrf.io.mask_read(0)(vxs.length+3).req.bits.oldest := false.B
  io.mask_access.ready  := vrf.io.mask_read(0)(vxs.length+3).req.ready && !vm_busy
  io.mask_access.mask   := vrf.io.mask_read(0)(vxs.length+3).resp >> io.mask_access.eidx(log2Ceil(dLen)-1,0)


  val vmu_index_q = Module(new Compactor(dLenB, dLenB, UInt(8.W), false))
  val vmu_mask_q = Module(new Compactor(dLenB, dLenB, Bool(), false))
  val perm_q = Module(new DCEQueue(new PermuteMicroOp, 2))

  vmu_index_q.io.push_data      := vps.io.iss.bits.rvs2_data.asTypeOf(Vec(dLenB, UInt(8.W)))
  vmu_index_q.io.push.bits.head := vps.io.iss.bits.eidx << vps.io.iss.bits.rvs2_eew
  vmu_index_q.io.push.bits.tail := Mux(vps.io.iss.bits.tail,
    vps.io.iss.bits.vl << vps.io.iss.bits.rvs2_eew,
    0.U)

  vmu_mask_q.io.push_data       := (vps.io.iss.bits.rvm_data >> vps.io.iss.bits.eidx(log2Ceil(dLen)-1,0))(dLenB-1,0).asBools
  vmu_mask_q.io.push.bits.head  := 0.U
  vmu_mask_q.io.push.bits.tail  := Mux(vps.io.iss.bits.tail, vps.io.iss.bits.vl, 0.U) - vps.io.iss.bits.eidx


  vps.io.iss.ready := Mux(vps.io.iss.bits.vmu,
    vmu_index_q.io.push.ready && vmu_mask_q.io.push.ready,
    perm_q.io.enq.ready)

  vmu_index_q.io.push.valid := vps.io.iss.valid && vps.io.iss.bits.vmu && vps.io.iss.bits.renv2 && vps.io.iss.ready
  vmu_mask_q.io.push.valid  := vps.io.iss.valid && vps.io.iss.bits.vmu && vps.io.iss.bits.renvm && vps.io.iss.ready

  io.vmu.mask_pop   <> vmu_mask_q.io.pop
  io.vmu.mask_data  := vmu_mask_q.io.pop_data
  io.vmu.index_pop  <> vmu_index_q.io.pop
  io.vmu.index_data := vmu_index_q.io.pop_data

  perm_q.io.enq.valid := vps.io.iss.valid && !vps.io.iss.bits.vmu
  perm_q.io.enq.bits := vps.io.iss.bits

  perm_q.io.deq.ready := perm_buffer.io.push.ready
  perm_buffer.io.push.valid := perm_q.io.deq.valid
  perm_buffer.io.push.bits.head := perm_q.io.deq.bits.eidx << perm_q.io.deq.bits.rvs2_eew
  perm_buffer.io.push.bits.tail := Mux(perm_q.io.deq.bits.tail,
    perm_q.io.deq.bits.vl << perm_q.io.deq.bits.rvs2_eew,
    0.U)
  perm_buffer.io.push_data := perm_q.io.deq.bits.rvs2_data.asTypeOf(Vec(dLenB, UInt(8.W)))

  perm_buffer.io.pop <> vxs.head.io.perm.req
  vxs.head.io.perm.data := perm_buffer.io.pop_data.asUInt

  // Clear the age tags
  var r_idx = 0
  def clearVat(fire: Bool, tag: UInt) = {
    assert(r_idx < nRelease)
    io.vat_release(r_idx).valid := fire
    io.vat_release(r_idx).bits := tag
    r_idx += 1
  }

  clearVat(vls.io.iss.fire && vls.io.iss.bits.tail, vls.io.iss.bits.vat)
  clearVat(vss.io.iss.fire && vss.io.iss.bits.tail, vss.io.iss.bits.vat)
  vxus.map(_.io.vat_release).foreach{ rel => clearVat(rel.valid, rel.bits) }

  // Signalling to frontend
  val seq_inflight_wv0 = (allSeqs.map(_.io.seq_hazard).map { h =>
    h.valid && ((h.bits.wintent & ~(0.U(egsPerVReg.W))) =/= 0.U)
  } ++ issqs.map(_.io.hazards).flatten.map { h =>
    h.valid && h.bits.wintent(0)
  } ++ vxus.map(_.io.pipe_hazards).flatten.map { h =>
    h.valid && (h.bits.eg < egsPerVReg.U)
  } ++ vxus.map(_.io.iter_hazards).flatten.map { h =>
    h.valid && (h.bits.eg < egsPerVReg.U)
  }).orR
  val vdq_inflight_wv0 = vdq.io.peek.map { h =>
    h.valid && h.bits.may_write_v0
  }.orR

  vm_busy := seq_inflight_wv0 || vdq_inflight_wv0
  io.busy := vdq.io.deq.valid || allSeqs.map(_.io.busy).orR || vxus.map(_.io.busy).asUInt.orR || resetting
  io.set_vxsat := vxus.map(_.io.set_vxsat).asUInt.orR
  io.set_fflags.valid := vxus.map(_.io.set_fflags.valid).asUInt.orR
  io.set_fflags.bits  := vxus.map( xu => Mux(xu.io.set_fflags.valid, xu.io.set_fflags.bits, 0.U)).reduce(_|_)
  io.scalar_resp <> vxus(0).io.scalar_write
}
