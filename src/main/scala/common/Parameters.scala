package vector.common

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.rocket._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._

case class VectorParams(
  viqEntries: Int = 4,
  vdqEntries: Int = 4,
  vlaqEntries: Int = 4,
  vsaqEntries: Int = 4,
  vatSz: Int = 3) {
  require(viqEntries >= 3)
}

trait HasVectorParams extends VectorConsts { this: HasCoreParameters =>
  val params: VectorParams
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
