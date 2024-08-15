package saturn.common

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tile.{CoreModule}
import saturn.common._

abstract class PipeSequencer[T <: Data](issType: T)(implicit p: Parameters) extends CoreModule()(p) with HasVectorParams {

  val io = IO(new Bundle {
    val dis = Flipped(Decoupled(new BackendIssueInst))
    val dis_stall = Input(Bool()) // used to disable OOO

    val seq_hazard = Output(Valid(new SequencerHazard))

    val vat = Output(UInt(vParams.vatSz.W))
    val vat_head = Input(UInt(vParams.vatSz.W))
    val older_writes = Input(UInt(egsTotal.W))
    val older_reads  = Input(UInt(egsTotal.W))

    val busy = Output(Bool())
    val head = Output(Bool())

    val rvs1 = new VectorReadIO
    val rvs2 = new VectorReadIO
    val rvd  = new VectorReadIO
    val rvm  = new VectorReadIO
    val perm = new Bundle {
      val req = Decoupled(new CompactorReq(dLenB))
      val data = Input(UInt(dLen.W))
    }

    val acc_init_resp = Input(UInt(dLen.W))

    val iss = Decoupled(issType)

    val acc = Input(Valid(new VectorWrite(dLen)))
  })
  def accepts(inst: VectorIssueInst): Bool

  def min(a: UInt, b: UInt) = Mux(a > b, b, a)
  def get_max_offset(offset: UInt): UInt = min(offset, maxVLMax.U)(log2Ceil(maxVLMax),0)
  def get_head_mask(bit_mask: UInt, eidx: UInt, eew: UInt) = bit_mask << (eidx << eew)(dLenOffBits-1,0)
  def get_tail_mask(bit_mask: UInt, eidx: UInt, eew: UInt) = bit_mask >> (0.U(dLenOffBits.W) - (eidx << eew)(dLenOffBits-1,0))
  def get_next_eidx(vl: UInt, eidx: UInt, eew: UInt, sub_dlen: UInt, reads_mask: Bool, elementwise: Bool) = {
    val next = Wire(UInt((1+log2Ceil(maxVLMax)).W))
    next := Mux(elementwise, eidx +& 1.U, Mux(reads_mask,
      eidx +& dLen.U,
      (((eidx >> (dLenOffBits.U - eew - sub_dlen)) +& 1.U) << (dLenOffBits.U - eew - sub_dlen))
    ))
    min(vl, next)
  }
  def next_is_new_eg(eidx: UInt, next_eidx: UInt, eew: UInt, masked: Bool) = {
    val offset = Mux(masked, log2Ceil(dLen).U, dLenOffBits.U - eew)
    (next_eidx >> offset) =/= (eidx >> offset)
  }


  io.rvs1.req.valid := false.B
  io.rvs1.req.bits := DontCare
  io.rvs2.req.valid := false.B
  io.rvs2.req.bits := DontCare
  io.rvd.req.valid := false.B
  io.rvd.req.bits := DontCare
  io.rvm.req.valid := false.B
  io.rvm.req.bits := DontCare
  io.perm.req.valid := false.B
  io.perm.req.bits := DontCare
}
