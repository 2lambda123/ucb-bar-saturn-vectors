package vector.common

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.rocket._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._

class VectorExecutionUnit(depth: Int)(implicit p: Parameters) extends CoreModule()(p) with HasVectorParams {
  val io = IO(new Bundle {
    val iss = Flipped(Decoupled(new VectorIssueBeat(depth)))

    val writes = Vec(2, Valid(new VectorWrite))
  })

  val wmask = RegInit(0.U(depth.W))
  val pipe_valids = Seq.fill(depth) { RegInit(false.B) }
  val pipe_bits = Seq.fill(depth) { Reg(new VectorIssueBeat(depth)) }

  wmask := wmask >> 1 | ((io.iss.fire << io.iss.bits.wlat) >> 1)
  io.iss.ready := (1.U << (io.iss.bits.wlat) & wmask) === 0.U

  pipe_valids.head := io.iss.fire
  when (io.iss.fire) { pipe_bits.head := io.iss.bits }
  for (i <- 1 until depth) {
    pipe_valids(i) := pipe_valids(i-1)
    when (pipe_valids(i-1)) {
      pipe_bits(i) := pipe_bits(i-1)
    }
  }
  //TODO: define and connect ready signals
  val viu = Module(new VectorIntegerUnit)
  viu.io.pipe(0).valid := pipe_valids(0) && io.iss.bits.inst.isOpi
  viu.io.pipe(0).bits := pipe_bits(0)

  val viMul = Module(new VectorIntegerMultiply)
  viMul.io.pipe(0).valid := pipe_valids(0) && io.iss.bits.inst.isOpm
  viMul.io.pipe(0).bits := pipe_bits(0)

  val writeArbs = Seq.fill(2) { Module(new Arbiter(new VectorWrite, 2)) }
  for (i <- 0 until 2) {
    writeArbs(i).io.in(0) <> viu.io.writes(i)
    writeArbs(i).io.in(1) <> viMul.io.writes(i)
    io.writes(i).valid := writeArbs(i).io.out.valid
    io.writes(i).bits := writeArbs(i).io.out.bits
  }
}
