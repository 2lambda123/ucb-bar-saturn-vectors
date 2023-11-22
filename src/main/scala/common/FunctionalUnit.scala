package vector.common

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.rocket._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._

abstract class VectorFunctionalUnit(depth: Int)(implicit p: Parameters) extends CoreModule()(p) with HasVectorParams {
  val io = IO(new Bundle {
    val iss = Input(Valid(new VectorMicroOp(depth)))

    val pipe = Input(Vec(depth, Valid(new VectorMicroOp(depth))))

    val writes = Vec(2, Valid(new VectorWrite))
  })
}

class VectorIntegerUnit(implicit p: Parameters) extends VectorFunctionalUnit(1)(p) {

  val ctrl_sub :: ctrl_add_sext :: ctrl_wide_in ::  Nil = VecDecode.applyBools(
    io.pipe(0).bits.funct3, io.pipe(0).bits.funct6,
    Seq.fill(3)(BitPat.dontCare(1)), Seq(
      (OPIFunct6.add   , Seq(N,X,N)),
      (OPIFunct6.sub   , Seq(Y,X,N)),
      (OPIFunct6.rsub  , Seq(Y,X,N)),
      (OPMFunct6.waddu , Seq(N,N,N)),
      (OPMFunct6.wadd  , Seq(N,Y,N)),
      (OPMFunct6.wsubu , Seq(Y,N,N)),
      (OPMFunct6.wsub  , Seq(Y,Y,N)),
      (OPMFunct6.wadduw, Seq(N,N,Y)),
      (OPMFunct6.waddw , Seq(N,Y,Y)),
      (OPMFunct6.wsubuw, Seq(Y,N,Y)),
      (OPMFunct6.wsubw , Seq(Y,Y,Y)),
      (OPIFunct6.adc   , Seq(N,X,N)),
      (OPIFunct6.madc  , Seq(N,X,N))
    ))
  val ctrl_cmask = io.pipe(0).bits.opif6 === OPIFunct6.adc || ((io.pipe(0).bits.opif6 === OPIFunct6.madc) && !io.pipe(0).bits.vm)

  val rsub = io.pipe(0).bits.isOpi && OPIFunct6(io.pipe(0).bits.funct6) === OPIFunct6.rsub
  val xunary0 = io.pipe(0).bits.isOpm && OPMFunct6(io.pipe(0).bits.funct6) === OPMFunct6.xunary0

  val add_in1 = io.pipe(0).bits.rvs1_data.asTypeOf(Vec(dLenB, UInt(8.W)))
  val add_in2 = io.pipe(0).bits.rvs2_data.asTypeOf(Vec(dLenB, UInt(8.W)))
  val add_use_carry = Mux1H(UIntToOH(io.pipe(0).bits.rvs2_eew),
    (0 until 4).map { eew => Fill(dLenB >> eew, ~(1.U((1 << eew).W))) }
  )
  val add_mask_carry = Mux1H(UIntToOH(io.pipe(0).bits.rvs2_eew), (0 until 4).map { eew =>
    VecInit((0 until dLenB >> eew).map { i => io.pipe(0).bits.rmask(i) | 0.U((1 << eew).W) }).asUInt
  })
  val add_carry = Wire(Vec(dLenB+1, UInt(1.W)))
  val add_out = Wire(Vec(dLenB, UInt(8.W)))
  val add_wide_out_eew = (0 until 3).map { eew => Wire(Vec(dLenB >> eew, UInt((16 << eew).W))) }
  val add_wide_out = Mux1H(UIntToOH(io.pipe(0).bits.rvs2_eew), add_wide_out_eew.map(_.asUInt))
  val add_wide_in1_eew = (0 until 3).map { eew => Wire(Vec(dLenB >> (eew + 1), UInt((16 << eew).W))) }
  val add_wide_in1 = Mux1H(UIntToOH(io.pipe(0).bits.rvs1_eew), add_wide_in1_eew.map(_.asTypeOf(Vec(dLenB, UInt(8.W)))))
  val add_carry_out = Fill(8, Mux1H(UIntToOH(io.pipe(0).bits.rvs1_eew), (0 until 4).map { eew =>
    Fill(1 << eew, VecInit(add_carry.drop(1).grouped(1 << eew).map(_.last).toSeq).asUInt)
  }))

  dontTouch(add_carry_out)

  add_carry(0) := ctrl_sub

  for (i <- 0 until dLenB) {
    val in1 = Mux(ctrl_wide_in, add_wide_in1(i), Mux(rsub, add_in2(i), add_in1(i)))
    val in2 = Mux(rsub, add_in1(i), add_in2(i))
    val full = (Mux(ctrl_sub, ~in1, in1) +&
      in2 +&
      Mux(add_use_carry(i), add_carry(i), ctrl_sub) +&
      Mux(ctrl_cmask, add_mask_carry(i), 0.U)
    )
    add_out(i) := full(7,0)
    add_carry(i+1) := full(8)
  }
  for (eew <- 0 until 3) {
    val in_vec = add_in1.asTypeOf(Vec(dLenB >> eew, UInt((8 << eew).W)))
    for (i <- 0 until dLenB >> (eew + 1)) {
      val lo = Mux(io.pipe(0).bits.wvd_eg(0), in_vec(i + (dLenB >> (eew + 1))), in_vec(i))
      val hi = Fill(16 << eew, lo((8 << eew)-1) && ctrl_add_sext)
      add_wide_in1_eew(eew)(i) := Cat(hi, lo)
    }

    val out_vec = add_out.asTypeOf(Vec(dLenB >> eew, UInt((8 << eew).W)))
    for (i <- 0 until dLenB >> eew) {
      val carry = add_carry((i+1) << eew)

      val hi1 = (ctrl_add_sext && add_in1(((i + 1) << eew) - 1)(7)) ^ ctrl_sub
      val hi2 = ctrl_add_sext && add_in2(((i + 1) << eew) - 1)(7)
      val hi = Mux(hi1 && hi2, ~(1.U((8 << eew).W)), Fill(8 << eew, hi1 ^ hi2))
      add_wide_out_eew(eew)(i) := Cat(hi + carry, out_vec(i))
    }
  }

  val xunary0_eew_mul = io.pipe(0).bits.vd_eew - io.pipe(0).bits.rvs2_eew
  val xunary0_in = (1 until 4).map { m =>
    val w = dLen >> m
    val in = Wire(UInt(w.W))
    val in_mul = io.pipe(0).bits.rvs2_data.asTypeOf(Vec(1 << m, UInt(w.W)))
    in := in_mul(io.pipe(0).bits.wvd_eg(m-1,0))
    in
  }
  val xunary0_out = Mux1H((1 until 4).map { vd_eew => (0 until vd_eew).map { vs2_eew =>
    (io.pipe(0).bits.vd_eew === vd_eew.U && io.pipe(0).bits.rvs2_eew === vs2_eew.U) -> {
      val mul = vd_eew - vs2_eew
      val in = xunary0_in(mul-1).asTypeOf(Vec(dLenB >> vd_eew, UInt((8 << vs2_eew).W)))
      val out = Wire(Vec(dLenB >> vd_eew, UInt((8 << vd_eew).W)))
      out.zip(in).foreach { case (l, r) => l := Cat(
        Fill((8 << vd_eew) - (8 << vs2_eew), io.pipe(0).bits.rs1(0) && r((8 << vs2_eew)-1)),
        r)
      }
      out.asUInt
    }
  }}.flatten)

  val out = Mux(xunary0, xunary0_out, add_out.asUInt)

  val mask_write = io.pipe(0).bits.opif6 === OPIFunct6.madc
  val mask_write_offset = Mux1H(UIntToOH(io.pipe(0).bits.rvs1_eew), (0 until 4).map { eew =>
    Cat(io.pipe(0).bits.eidx(log2Ceil(dLen)-1, dLenOffBits-eew), 0.U((dLenOffBits-eew).W))
  })
  val mask_write_mask = Mux1H(UIntToOH(io.pipe(0).bits.rvs1_eew), (0 until 4).map { eew =>
    VecInit(io.pipe(0).bits.wmask.asBools.grouped(1 << eew).map(_.head).toSeq).asUInt
  })

  for (b <- 0 until 2) {
    io.writes(b).valid     := io.pipe(0).valid && (io.pipe(0).bits.wvd_eg(0) === b.U || io.pipe(0).bits.wvd_widen2)
    io.writes(b).bits.eg   := io.pipe(0).bits.wvd_eg >> 1
    io.writes(b).bits.mask := FillInterleaved(8, io.pipe(0).bits.wmask)
    io.writes(b).bits.data := out
    when (mask_write) {
      io.writes(b).bits.mask := mask_write_mask << mask_write_offset
      io.writes(b).bits.data := add_carry_out
    }
  }

  val wide_mask = FillInterleaved(2, io.pipe(0).bits.wmask)
  when (io.pipe(0).bits.wvd_widen2) {
    io.writes(0).valid := io.pipe(0).valid
    io.writes(1).valid := io.pipe(0).valid
    io.writes(0).bits.mask := FillInterleaved(8, wide_mask(dLenB-1,0))
    io.writes(1).bits.mask := FillInterleaved(8, wide_mask >> dLenB)
    io.writes(0).bits.data := add_wide_out
    io.writes(1).bits.data := add_wide_out >> dLen
  }
}
