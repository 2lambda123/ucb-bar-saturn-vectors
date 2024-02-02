package saturn.exu

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.rocket._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._
import saturn.common._
import saturn.insns._

trait HasSharedFPUIO {
  implicit val p: Parameters
  val io_fp_req = IO(Decoupled(new FPInput()))
  val io_fp_resp = IO(Flipped(Decoupled(new FPResult())))
}

class SharedScalarElementwiseFPFMA(depth: Int)(implicit p: Parameters) extends PipelinedFunctionalUnit(depth)(p)
    with HasFPUParameters
    with HasSharedFPUIO {

  val supported_insns = Seq(
    FADD.VV, FADD.VF, FSUB.VV, FSUB.VF, FRSUB.VF,
    FMUL.VV, FMUL.VF,
    FMACC.VV, FMACC.VF, FNMACC.VV, FNMACC.VF,
    FMSAC.VV, FMSAC.VF, FNMSAC.VV, FNMSAC.VF,
    FMADD.VV, FMADD.VF, FNMADD.VV, FNMADD.VF,
    FMSUB.VV, FMSUB.VF, FNMSUB.VV, FNMSUB.VF,
    FWADD.VV, FWADD.VF, FWSUB.VV, FWSUB.VF,
    FWADDW.VV, FWADDW.VF, FWSUBW.VV, FWSUBW.VF,
    FWMUL.VV, FWMUL.VF,
    FWMACC.VV, FWMACC.VF, FWNMACC.VV, FWNMACC.VF,
    FWMSAC.VV, FWMSAC.VF, FWNMSAC.VV, FWNMSAC.VF,
    FREDOSUM.VV, FREDUSUM.VV, FWREDOSUM.VV, FWREDUSUM.VV,
  ).map(_.elementWise)

  val ctrl = new VectorDecoder(io.pipe(0).bits.funct3, io.pipe(0).bits.funct6, 0.U, 0.U, supported_insns, Seq(
    FPAdd, FPMul, FPSwapVdV2, FPFMACmd, ReadsVD, FPSpecRM, Wide2VD, Wide2VS2, Reduction))

  val vs1_eew = io.pipe(0).bits.rvs1_eew
  val vs2_eew = io.pipe(0).bits.rvs2_eew
  val vd_eew  = io.pipe(0).bits.vd_eew
  val vd_eew64 = io.pipe(0).bits.vd_eew64
  val eidx = Mux(io.pipe(0).bits.acc, 0.U, io.pipe(0).bits.eidx)

  // Functional unit is ready if not currently running and the scalar FPU is available
  io.iss.ready := new VectorDecoder(io.iss.op.funct3, io.iss.op.funct6, 0.U, 0.U, supported_insns, Nil).matched
  // io.iss.sub_dlen := dLenOffBits.U - Mux(ctrl.bool(Wide2VS2), io.iss.op.rvs2_eew, io.iss.op.vd_eew)

  // Create FPInput
  val req = Wire(new FPInput)
  req.ldst := false.B
  req.wen := false.B
  req.ren1 := true.B
  req.ren2 := true.B
  req.ren3 := ctrl.bool(ReadsVD)
  req.swap12 := false.B
  req.swap23 := ctrl.bool(FPAdd) && !ctrl.bool(FPMul)
  req.typeTagIn := Mux(vd_eew64, D, S)
  req.typeTagOut := Mux(vd_eew64, D, S)
  req.fromint := false.B
  req.toint := false.B
  req.fastpipe := false.B
  req.fma := true.B
  req.div := false.B
  req.sqrt := false.B
  req.wflags := true.B
  req.vec := true.B
  req.rm := io.pipe(0).bits.frm
  req.fmaCmd := ctrl.uint(FPFMACmd)
  req.typ := 0.U
  req.fmt := 0.U

  val rvs2_elem = io.pipe(0).bits.rvs2_elem
  val rvs1_elem = io.pipe(0).bits.rvs1_elem
  val rvd_elem  = io.pipe(0).bits.rvd_elem

  val s_rvs2 = FType.S.recode(rvs2_elem(31,0))
  val s_rvs1 = FType.S.recode(rvs1_elem(31,0))
  val s_rvd = FType.S.recode(rvd_elem(31,0))

  // For widening operations, widen the narrow operands to compute with the scalar FPU
  val widen_rvs1 = Module(new hardfloat.RecFNToRecFN(8, 24, 11, 53))
  widen_rvs1.io.in := s_rvs1
  widen_rvs1.io.roundingMode := io.pipe(0).bits.frm
  widen_rvs1.io.detectTininess := hardfloat.consts.tininess_afterRounding

  val widen_rvs2 = Module(new hardfloat.RecFNToRecFN(8, 24, 11, 53))
  widen_rvs2.io.in := s_rvs2
  widen_rvs2.io.roundingMode := io.pipe(0).bits.frm
  widen_rvs2.io.detectTininess := hardfloat.consts.tininess_afterRounding

  val d_rvs2 = FType.D.recode(rvs2_elem)
  val d_rvs1 = FType.D.recode(rvs1_elem)
  val d_rvd = FType.D.recode(rvd_elem)

  val rvs2_recoded = Mux(vd_eew64, d_rvs2, s_rvs2)
  val rvs1_recoded = Mux(vd_eew64, d_rvs1, s_rvs1)
  val rvd_recoded =  Mux(vd_eew64, d_rvd, s_rvd)

  // Set req.in1
  when (ctrl.bool(FPSwapVdV2)) {
    req.in1 := rvd_recoded
  } .elsewhen (vs2_eew === 3.U) {
    req.in1 := d_rvs2
  } .elsewhen (ctrl.bool(Wide2VD)) {
    req.in1 := widen_rvs2.io.out
  } .otherwise {
    req.in1 := s_rvs2
  }

  // Set req.in2
  when (vs1_eew === 3.U) {
    req.in2 := d_rvs1
  } .elsewhen (ctrl.bool(Wide2VD) && !io.pipe(0).bits.acc) {
    req.in2 := widen_rvs1.io.out
  } .otherwise {
    req.in2 := s_rvs1
  }

  // Set req.in3
  when (ctrl.bool(FPSwapVdV2)) {
    req.in3 := rvs2_recoded
  } .otherwise {
    req.in3 := rvd_recoded
  }

  io_fp_req.bits := req
  io_fp_req.valid := io.pipe(0).valid
  io.pipe0_stall := !io_fp_req.ready
  io_fp_resp.ready := true.B

  when (io.pipe(depth-1).valid) { assert(io_fp_resp.valid) }
  io.write.valid := io.pipe(depth-1).valid
  io.write.bits.eg := io.pipe(depth-1).bits.wvd_eg
  io.write.bits.mask := FillInterleaved(8, io.pipe(depth-1).bits.wmask)
  io.write.bits.data := Fill(dLenB >> 3, Mux(io.pipe(depth-1).bits.vd_eew === 3.U, FType.D.ieee(io_fp_resp.bits.data), Fill(2, FType.S.ieee(unbox(io_fp_resp.bits.data, 0.U, Some(FType.S))))))

  io.set_fflags := DontCare
  io.scalar_write.valid := false.B
  io.scalar_write.bits := DontCare
  io.set_vxsat := false.B
}
