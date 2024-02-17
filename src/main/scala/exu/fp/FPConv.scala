package saturn.exu

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.rocket._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._
import saturn.common._
import saturn.insns._

class FPConvPipe(implicit p: Parameters) extends PipelinedFunctionalUnit(2)(p) with HasFPUParameters {
  val supported_insns = Seq(FCVT_SGL, FCVT_NRW, FCVT_WID)

  io.set_vxsat := false.B

  io.iss.ready := new VectorDecoder(io.iss.op.funct3, io.iss.op.funct6, 0.U, 0.U,
    supported_insns, Nil).matched

  val rs1 = io.pipe(0).bits.rs1
  val ctrl_widen = rs1(3)
  val ctrl_narrow = rs1(4)
  val ctrl_signed = rs1(0)
  val ctrl_out = !rs1(2) && rs1(1)
  val ctrl_truncating = rs1(2) && rs1(1)
  val ctrl_round_to_odd = rs1(0)

  val rvs2_data = io.pipe(0).bits.rvs2_data

  val fTypes = Seq(FType.H, FType.S, FType.D)
  
  // This defines the ops that are valid according to the spec.  The ordering is as follows for H, S, D:
  //                FP->FP S      W     N  FP->I S    W      N I->FP S     W      N
  val defined_ops = Seq((true , true , false, true , true , true , true , true , false),
                        (true , true , true , true , true , true , true , true , true),
                        ()

  val eew32_ops = Seq(FType.)


  val single_width_out = Wire(Vec(3, UInt(dLen.W)))
  val multi_width_out = Wire(UInt(dLen.W))

  // Single Width Type Conversions
  for(eew <- 0 until 3) {
    val fType = fTypes(eew)
    val num_chunks = dLen / fType.ieeeWidth
    val rvs2_chunks = rvs2_data.asTypeOf(Vec(num_chunks, UInt(fType.ieeeWidth.W)))

    // FP to Int
    val fptoint_modules = Seq.fill(num_chunks)(Module(new hardfloat.RecFNToIN(fType.exp, fType.sig, fType.ieeeWidth)))
    val gen_fptoint = rvs2_chunks.zip(fptoint_modules).map { case(rvs2, conv) =>
      conv.io.signedOut := ctrl_signed
      conv.io.roundingMode := Mux(ctrl_truncating, 1.U, io.pipe(0).bits.frm)
      conv.io.in := fType.recode(Mux(ctrl_truncating, rvs2(fType.ieeeWidth-1, fType.sig-2) << (fType.sig - 2), rvs2))
      conv.io
    } 
    val fptoint_results = gen_fptoint.map { case(conv) =>
      conv.out
    }

    // Int to FP
    val inttofp_modules = Seq.fill(num_chunks)(Module(new hardfloat.INToRecFN(fType.ieeeWidth, fType.exp, fType.sig)))
    val gen_inttofp = rvs2_chunks.zip(inttofp_modules).map { case(rvs2, conv) =>
      conv.io.signedIn := ctrl_signed
      conv.io.roundingMode := io.pipe(0).bits.frm
      conv.io.detectTininess := hardfloat.consts.tininess_afterRounding
      conv.io.in := rvs2
      conv.io
    }
    val inttofp_results = gen_inttofp.map { case(conv) =>
      fType.ieee(conv.out)
    }

    single_width_out(eew) := Mux(ctrl_out, inttofp_results.asUInt, fptoint_results.asUInt) 
  }

  // Widening and Narrowing Type conversions
  val num_convert_units = dLen / 64

  //// Int to FP
  //// 01X

  val inttofp_wide_outs = Wire(Vec(3, UInt(ear
    Len.W)))
  val inttofp_narrow_outs = Wire(Vec(2, UInt(dLen.W)))

  for (eew <- 0 until 3) {
    val inttofp_modules = 
  }

  val wide_inttofp_modules = Seq.fill(num_convert_units)(Module(new hardfloat.INToRecFN(FType.S.ieeeWidth, 11, 53)))
  val narrow_inttofp_modules = Seq.fill(num_convert_units)(Module(new hardfloat.INToRecFN(FType.D.ieeeWidth, 8, 24)))
  val gen_inttofp = wide_inttofp_modules.zip(narrow_inttofp_modules).zipWithIndex.map { case((wide, narrow), idx) =>
    wide.io.signedIn := ctrl_signed
    wide.io.roundingMode := io.pipe(0).bits.frm
    wide.io.detectTininess := hardfloat.consts.tininess_afterRounding
    wide.io.in := extractElem(rvs2_data, 2.U, io.pipe(0).bits.eidx + idx.U)(31,0)

    narrow.io.signedIn := ctrl_signed
    narrow.io.roundingMode := io.pipe(0).bits.frm
    narrow.io.detectTininess := hardfloat.consts.tininess_afterRounding
    narrow.io.in := extractElem(rvs2_data, 3.U, io.pipe(0).bits.eidx + idx.U)(63,0)
    (wide.io, narrow.io)
  }
  val widen_inttofp_results = gen_inttofp.map{ case(wide, narrow) => FType.D.ieee(wide.out) }
  val narrow_inttofp_results = gen_inttofp.map{ case(wide, narrow) => FType.S.ieee(narrow.out) }


  //// FP to Int
  //// 00X
  
  // Widening
  for (eew <- )


  val wide_fptoint_modules = Seq.fill(num_convert_units)(Module(new hardfloat.RecFNToIN(FType.S.exp, FType.S.sig, FType.D.ieeeWidth)))
  val narrow_fptoint_modules = Seq.fill(num_convert_units)(Module(new hardfloat.RecFNToIN(FType.D.exp, FType.D.sig, FType.S.ieeeWidth)))

  val wide_gen_fptoint = wide_fptoint_modules.zipWithIndex.map { case(conv, idx) =>
    val extracted_rvs2_bits = extractElem(rvs2_data, 2.U, io.pipe(0).bits.eidx + idx.U)(31,0)
    conv.io.signedOut := ctrl_signed
    conv.io.roundingMode := io.pipe(0).bits.frm
    conv.io.in := FType.S.recode(Mux(ctrl_truncating, extracted_rvs2_bits(FType.S.ieeeWidth-1, FType.S.sig-2) << (FType.S.sig - 2), extracted_rvs2_bits))
    conv.io
  }
  val wide_fptoint_results = wide_gen_fptoint.map { conv => conv.out }
  
  val narrow_gen_fptoint = narrow_fptoint_modules.zipWithIndex.map { case(conv, idx) =>
    val extracted_rvs2_bits = extractElem(rvs2_data, 3.U, io.pipe(0).bits.eidx + idx.U)(63,0)
    conv.io.signedOut := ctrl_signed
    conv.io.roundingMode := io.pipe(0).bits.frm
    conv.io.in := FType.D.recode(Mux(ctrl_truncating, extracted_rvs2_bits(FType.D.ieeeWidth-1, FType.D.sig-2) << (FType.D.sig - 2), extracted_rvs2_bits))
    conv.io
  }
  val narrow_fptoint_results = narrow_gen_fptoint.map { conv => conv.out }

  // FP to FP
  // 100
  val wide_fptofp_modules = Seq.fill(num_convert_units)(Module(new hardfloat.RecFNToRecFN(FType.S.exp, FType.S.sig, FType.D.exp, FType.D.sig)))
  val narrow_fptofp_modules = Seq.fill(num_convert_units)(Module(new hardfloat.RecFNToRecFN(FType.D.exp, FType.D.sig, FType.S.exp, FType.S.sig)))
  val gen_fptofp = wide_fptofp_modules.zip(narrow_fptofp_modules).zipWithIndex.map{ case((wide, narrow), idx) => 
    wide.io.in := FType.S.recode(extractElem(rvs2_data, 2.U, io.pipe(0).bits.eidx + idx.U)(31,0))
    wide.io.roundingMode := io.pipe(0).bits.frm
    wide.io.detectTininess := hardfloat.consts.tininess_afterRounding

    narrow.io.in := FType.D.recode(extractElem(rvs2_data, 3.U, io.pipe(0).bits.eidx + idx.U)(63,0))
    narrow.io.roundingMode := Mux(ctrl_round_to_odd, "b110".U, io.pipe(0).bits.frm)
    narrow.io.detectTininess := hardfloat.consts.tininess_afterRounding

    (wide.io, narrow.io)
  }
  val widen_fptofp_results = gen_fptofp.map{ case(wide, narrow) => FType.D.ieee(wide.out) }
  val narrow_fptofp_results = gen_fptofp.map{ case(wide, narrow) => FType.S.ieee(narrow.out) }

  when (!rs1(2) && rs1(1)) {
    multi_width_out := Mux(ctrl_widen, widen_inttofp_results.asUInt, Fill(2, narrow_inttofp_results.asUInt))
  } .elsewhen (!rs1(1) && rs1(2)) {
    multi_width_out := Mux(ctrl_widen, widen_fptofp_results.asUInt, Fill(2, narrow_fptofp_results.asUInt))
  } .otherwise {
    multi_width_out := Mux(ctrl_widen, wide_fptoint_results.asUInt, Fill(2, narrow_fptoint_results.asUInt))
  }

  val single_out_final = Wire(UInt(dLen.W))
  single_out_final := Mux(io.pipe(0).bits.rvs2_eew === 3.U, single_width_out(1), single_width_out(0))

  val pipe_out = Pipe(io.pipe(0).valid, Mux(!ctrl_widen && !ctrl_narrow, single_out_final, multi_width_out), 1).bits

  io.write.valid := io.pipe(depth-1).valid
  io.write.bits.eg := io.pipe(depth-1).bits.wvd_eg
  io.write.bits.mask := FillInterleaved(8, io.pipe(depth-1).bits.wmask)
  io.write.bits.data := pipe_out

  io.set_fflags := DontCare
  io.scalar_write.valid := false.B
  io.scalar_write.bits := DontCare
  io.pipe0_stall := false.B
}
