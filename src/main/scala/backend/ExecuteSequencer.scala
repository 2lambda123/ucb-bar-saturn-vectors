package vector.backend

import chisel3._
import chisel3.util._
import chisel3.experimental.dataview._
import org.chipsalliance.cde.config._
import freechips.rocketchip.rocket._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._
import vector.common._

class ExecuteIssueInst(implicit p: Parameters) extends VectorIssueInst()(p) {
  val wide_vd = Bool()      // vd reads/writes at 2xSEW
  val wide_vs2 = Bool()     // vs2 reads at 2xSEW
  val writes_mask = Bool()  // writes dest as a mask
  val renv1 = Bool()
  val renv2 = Bool()
  val renvd = Bool()
  val renvm = Bool()
}

class ExecuteSequencer(implicit p: Parameters) extends PipeSequencer()(p) {
  val decode_table = Seq(
    (OPMFunct6.waddu  , Seq(Y,N,N)),
    (OPMFunct6.wadd   , Seq(Y,N,N)),
    (OPMFunct6.wsubu  , Seq(Y,N,N)),
    (OPMFunct6.wsub   , Seq(Y,N,N)),
    (OPMFunct6.wadduw , Seq(Y,Y,N)),
    (OPMFunct6.waddw  , Seq(Y,Y,N)),
    (OPMFunct6.wsubuw , Seq(Y,Y,N)),
    (OPMFunct6.wsubw  , Seq(Y,Y,N)),
    (OPIFunct6.nsra   , Seq(N,Y,N)),
    (OPIFunct6.nsrl   , Seq(N,Y,N)),
    (OPIFunct6.madc   , Seq(N,N,Y)),
    (OPIFunct6.msbc   , Seq(N,N,Y)),
    (OPIFunct6.mseq   , Seq(N,N,Y)),
    (OPIFunct6.msne   , Seq(N,N,Y)),
    (OPIFunct6.msltu  , Seq(N,N,Y)),
    (OPIFunct6.mslt   , Seq(N,N,Y)),
    (OPIFunct6.msleu  , Seq(N,N,Y)),
    (OPIFunct6.msle   , Seq(N,N,Y)),
    (OPIFunct6.msgtu  , Seq(N,N,Y)),
    (OPIFunct6.msgt   , Seq(N,N,Y)),
    (OPMFunct6.wmul   , Seq(Y,N,N)),
    (OPMFunct6.wmulu  , Seq(Y,N,N)),
    (OPMFunct6.wmulsu , Seq(Y,N,N)),
    (OPMFunct6.wmaccu , Seq(Y,N,N)),
    (OPMFunct6.wmacc  , Seq(Y,N,N)),
    (OPMFunct6.wmaccsu, Seq(Y,N,N)),
    (OPMFunct6.wmaccus, Seq(Y,N,N)),
    (OPIFunct6.nclip  , Seq(N,Y,N)),
    (OPIFunct6.nclipu , Seq(N,Y,N)),
    (OPFFunct6.fwadd  , Seq(Y,N,N)),
    (OPFFunct6.fwsub  , Seq(Y,N,N)),
    (OPFFunct6.fwaddw , Seq(Y,Y,N)),
    (OPFFunct6.fwsubw , Seq(Y,Y,N)),
    (OPFFunct6.fwmul  , Seq(Y,N,N)),
    (OPFFunct6.fwmacc , Seq(Y,N,N)),
    (OPFFunct6.fwnmacc, Seq(Y,N,N)),
    (OPFFunct6.fwmsac , Seq(Y,N,N)),
    (OPFFunct6.fwnmsac, Seq(Y,N,N)),
    (OPFFunct6.vmfeq  , Seq(N,N,Y)),
    (OPFFunct6.vmfne  , Seq(N,N,Y)),
    (OPFFunct6.vmflt  , Seq(N,N,Y)),
    (OPFFunct6.vmfle  , Seq(N,N,Y)),
    (OPFFunct6.vmfgt  , Seq(N,N,Y)),
    (OPFFunct6.vmfge  , Seq(N,N,Y)),
  )

  def issQEntries = vParams.vxissqEntries
  val issq = Module(new DCEQueue(new ExecuteIssueInst, issQEntries, pipe=true))

  def accepts(inst: VectorIssueInst) = !inst.vmu
  io.dis.ready := !accepts(io.dis.bits) || issq.io.enq.ready
  issq.io.enq.valid := io.dis.valid && accepts(io.dis.bits)
  issq.io.enq.bits.viewAsSupertype(new VectorIssueInst) := io.dis.bits
  val dis_wide_vd :: dis_wide_vs2 :: dis_writes_mask :: Nil = VecDecode.applyBools(
    io.dis.bits.funct3, io.dis.bits.funct6, Seq.fill(3)(false.B), decode_table)
  issq.io.enq.bits.wide_vd     := dis_wide_vd || (io.dis.bits.funct3.isOneOf(OPFVV) && io.dis.bits.opff6 === OPFFunct6.vfunary0 && io.dis.bits.rs1(3))
  issq.io.enq.bits.wide_vs2    := dis_wide_vs2 || (io.dis.bits.funct3.isOneOf(OPFVV) && io.dis.bits.opff6 === OPFFunct6.vfunary0 && io.dis.bits.rs1(4))
  issq.io.enq.bits.writes_mask := dis_writes_mask
  issq.io.enq.bits.renv1       := io.dis.bits.funct3.isOneOf(OPIVV, OPFVV, OPMVV)
  issq.io.enq.bits.renv2       := !((io.dis.bits.opif6 === OPIFunct6.merge || io.dis.bits.opff6 === OPFFunct6.fmerge) && io.dis.bits.vm)
  issq.io.enq.bits.renvd       := io.dis.bits.opmf6.isOneOf(
    OPMFunct6.macc, OPMFunct6.nmsac, OPMFunct6.madd, OPMFunct6.nmsub,
    OPMFunct6.wmaccu, OPMFunct6.wmacc, OPMFunct6.wmaccsu, OPMFunct6.wmaccus) ||
    (io.dis.bits.funct3.isOneOf(OPFVV, OPFVF) && io.dis.bits.opff6.isOneOf(
    OPFFunct6.vfmacc, OPFFunct6.vfnmacc, OPFFunct6.vfmsac, OPFFunct6.vfnmsac,
    OPFFunct6.vfmadd, OPFFunct6.vfnmadd, OPFFunct6.vfmsub, OPFFunct6.vfnmsub,
    OPFFunct6.vfwmacc, OPFFunct6.vfwnmacc, OPFFunct6.vfwmsac, OPFFunct6.vfwnmsac))
  issq.io.enq.bits.renvm       := !io.dis.bits.vm || io.dis.bits.opif6 === OPIFunct6.merge || io.dis.bits.opff6 === OPFFunct6.fmerge

  for (i <- 0 until issQEntries) {
    val inst = issq.io.peek(i).bits
    io.iss_hazards(i).valid    := issq.io.peek(i).valid
    io.iss_hazards(i).bits.vat := inst.vat
    val vd_arch_mask  = get_arch_mask(inst.rd , inst.pos_lmul +& inst.wide_vd , 4)
    val vs1_arch_mask = get_arch_mask(inst.rs1, inst.pos_lmul                 , 3)
    val vs2_arch_mask = get_arch_mask(inst.rs2, inst.pos_lmul +& inst.wide_vs2, 4)
    io.iss_hazards(i).bits.rintent := Seq(
      (inst.renv1, vs1_arch_mask),
      (inst.renv2, vs2_arch_mask),
      (inst.renv2, vd_arch_mask),
      (inst.renvm, 1.U)
    ).map(t => Mux(t._1, t._2, 0.U)).reduce(_|_)
    io.iss_hazards(i).bits.wintent := vd_arch_mask
  }

  val valid = RegInit(false.B)
  val inst  = Reg(new ExecuteIssueInst)
  val wvd_mask  = Reg(UInt(egsTotal.W))
  val rvs1_mask = Reg(UInt(egsTotal.W))
  val rvs2_mask = Reg(UInt(egsTotal.W))
  val rvd_mask  = Reg(UInt(egsTotal.W))
  val rvm_mask  = Reg(UInt(egsPerVReg.W))

  val vs1_eew  = inst.vconfig.vtype.vsew
  val vs2_eew  = inst.vconfig.vtype.vsew + inst.wide_vs2 - Mux(inst.opmf6 === OPMFunct6.xunary0,
    ~inst.rs1(2,1) + 1.U, 0.U)
  val vs3_eew  = inst.vconfig.vtype.vsew + inst.wide_vd
  val vd_eew   = inst.vconfig.vtype.vsew + inst.wide_vd
  val incr_eew = Seq(
    Mux(inst.renv1, vs1_eew, 0.U),
    Mux(inst.renv2, vs2_eew, 0.U),
    Mux(inst.renvd, vs3_eew, 0.U),
    vd_eew).foldLeft(0.U(2.W)) { case (b, a) => Mux(a > b, a, b) }

  val use_wmask = !inst.vm && (!inst.opif6.isOneOf(OPIFunct6.adc, OPIFunct6.madc, OPIFunct6.sbc, OPIFunct6.msbc, OPIFunct6.merge) || !inst.opff6.isOneOf(OPFFunct6.fmerge))

  val eidx      = Reg(UInt(log2Ceil(maxVLMax).W))
  val next_eidx = get_next_eidx(inst.vconfig.vl, eidx, incr_eew, io.sub_dlen)
  val last      = next_eidx === inst.vconfig.vl


  issq.io.deq.ready := !valid || (last && io.iss.fire)

  when (issq.io.deq.fire) {
    val iss_inst = issq.io.deq.bits
    valid := true.B
    inst := issq.io.deq.bits
    eidx := iss_inst.vstart

    val vd_arch_mask  = get_arch_mask(iss_inst.rd , iss_inst.pos_lmul +& iss_inst.wide_vd , 4)
    val vs1_arch_mask = get_arch_mask(iss_inst.rs1, iss_inst.pos_lmul                     , 3)
    val vs2_arch_mask = get_arch_mask(iss_inst.rs2, iss_inst.pos_lmul +& iss_inst.wide_vs2, 4)

    wvd_mask    := FillInterleaved(egsPerVReg, vd_arch_mask)
    rvs1_mask   := Mux(iss_inst.renv1, FillInterleaved(egsPerVReg, vs1_arch_mask), 0.U)
    rvs2_mask   := Mux(iss_inst.renv2, FillInterleaved(egsPerVReg, vs2_arch_mask), 0.U)
    rvd_mask    := Mux(iss_inst.renvd, FillInterleaved(egsPerVReg, vd_arch_mask), 0.U)
    rvm_mask    := Mux(iss_inst.renvm, ~(0.U(egsPerVReg.W)), 0.U)
  } .elsewhen (last && io.iss.fire) {
    valid := false.B
  }


  io.vat := inst.vat
  io.seq_hazard.valid := valid
  io.seq_hazard.bits.rintent := rvs1_mask | rvs2_mask | rvd_mask | rvm_mask
  io.seq_hazard.bits.wintent := wvd_mask
  io.seq_hazard.bits.vat := inst.vat

  val vs1_read_oh = Mux(inst.renv1, UIntToOH(io.rvs1.req.bits), 0.U)
  val vs2_read_oh = Mux(inst.renv2, UIntToOH(io.rvs2.req.bits), 0.U)
  val vd_read_oh  = Mux(inst.renvd, UIntToOH(io.rvd.req.bits ), 0.U)
  val vm_read_oh  = Mux(inst.renvm, UIntToOH(io.rvm.req.bits ), 0.U)
  val vd_write_oh = UIntToOH(io.iss.bits.wvd_eg)

  val raw_hazard = ((vs1_read_oh | vs2_read_oh | vd_read_oh | vm_read_oh) & io.older_writes) =/= 0.U
  val waw_hazard = (vd_write_oh & io.older_writes) =/= 0.U
  val war_hazard = (vd_write_oh & io.older_reads) =/= 0.U
  val data_hazard = raw_hazard || waw_hazard || war_hazard


  io.rvs1.req.bits := getEgId(inst.rs1, eidx     , vs1_eew)
  io.rvs2.req.bits := getEgId(inst.rs2, eidx     , vs2_eew)
  io.rvd.req.bits  := getEgId(inst.rd , eidx     , vs3_eew)
  io.rvm.req.bits  := getEgId(0.U     , eidx >> 3, 0.U)

  io.rvs1.req.valid := valid && inst.renv1
  io.rvs2.req.valid := valid && inst.renv2
  io.rvd.req.valid  := valid && inst.renvd
  io.rvm.req.valid  := valid && inst.renvm

  io.iss.valid := (valid &&
    !data_hazard &&
    !(inst.renv1 && !io.rvs1.req.ready) &&
    !(inst.renv2 && !io.rvs2.req.ready) &&
    !(inst.renvd && !io.rvd.req.ready) &&
    !(inst.renvm && !io.rvm.req.ready)
  )

  io.iss.bits.wvd   := true.B
  io.iss.bits.rvs1_data := io.rvs1.resp
  when (inst.funct3.isOneOf(OPIVI, OPIVX, OPMVX, OPFVF) && !inst.vmu) {
    val rs1_data = Mux(inst.funct3 === OPIVI, Cat(Fill(59, inst.imm5(4)), inst.imm5), inst.rs1_data)
    io.iss.bits.rvs1_data := dLenSplat(rs1_data, vs1_eew)
  }
  io.iss.bits.rvs2_data := io.rvs2.resp
  io.iss.bits.rvd_data  := io.rvd.resp
  io.iss.bits.rvs1_eew  := vs1_eew
  io.iss.bits.rvs2_eew  := vs2_eew
  io.iss.bits.rvd_eew   := vs3_eew
  io.iss.bits.vd_eew    := vd_eew
  io.iss.bits.eidx      := eidx
  io.iss.bits.wvd_eg    := getEgId(inst.rd, Mux(inst.writes_mask, eidx >> 3, eidx), Mux(inst.writes_mask, 0.U, vd_eew))
  io.iss.bits.rs1       := inst.rs1
  io.iss.bits.funct3    := inst.funct3
  io.iss.bits.funct6    := inst.funct6
  io.iss.bits.last      := last
  io.iss.bits.vat       := inst.vat
  io.iss.bits.vm        := inst.vm
  io.iss.bits.rm        := inst.rm

  val dlen_mask = ~(0.U(dLenB.W))
  val head_mask = dlen_mask << (eidx << vd_eew)(dLenOffBits-1,0)
  val tail_mask = dlen_mask >> (0.U(dLenOffBits.W) - (next_eidx << vd_eew)(dLenOffBits-1,0))
  val vm_off    = ((1 << dLenOffBits) - 1).U(log2Ceil(dLen).W)
  val vm_eidx   = (eidx & ~(vm_off >> vd_eew))(log2Ceil(dLen)-1,0)
  val vm_resp   = (io.rvm.resp >> vm_eidx)
  val vm_mask   = Mux(use_wmask, VecInit.tabulate(4)({ sew =>
    FillInterleaved(1 << sew, vm_resp)
  })(vd_eew), ~(0.U(dLenB.W)))
  io.iss.bits.wmask := head_mask & tail_mask & vm_mask
  io.iss.bits.rmask := Mux(inst.vm, ~(0.U(dLenB.W)), vm_resp)

  when (io.iss.fire && !last) {
    when (Mux(inst.writes_mask, next_mask_is_new_eg(eidx, next_eidx), next_is_new_eg(eidx, next_eidx, vd_eew))) {
      val wvd_clr_mask = UIntToOH(io.iss.bits.wvd_eg)
      wvd_mask  := wvd_mask  & ~wvd_clr_mask
    }
    when (next_is_new_eg(eidx, next_eidx, vs2_eew)) {
      rvs2_mask := rvs2_mask & ~UIntToOH(io.rvs2.req.bits)
    }
    when (next_is_new_eg(eidx, next_eidx, vs1_eew)) {
      rvs1_mask := rvs1_mask & ~UIntToOH(io.rvs1.req.bits)
    }
    when (next_is_new_eg(eidx, next_eidx, vs3_eew)) {
      rvd_mask  := rvd_mask  & ~UIntToOH(io.rvd.req.bits)
    }
    when (next_mask_is_new_eg(eidx, next_eidx)) {
      rvm_mask  := rvm_mask  & ~UIntToOH(io.rvm.req.bits)
    }
    eidx := next_eidx
  }

  io.busy := valid
}
