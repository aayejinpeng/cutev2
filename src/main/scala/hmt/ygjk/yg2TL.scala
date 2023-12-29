/*
package boom.exu.ygjk

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tile._
import freechips.rocketchip.util._
import freechips.rocketchip.rocket._

class Yg2TL(implicit p: Parameters) extends LazyModule with YGJKParameters {
  lazy val module = new Yg2TLImp(this)
  val node = TLClientNode(Seq(TLClientPortParameters(Seq(TLClientParameters( //TLHelper.makeClientNode
    name = "ygjk",
    sourceId = IdRange(0, 1))))))
}

class Yg2TLImp(outer: Yg2TL) extends LazyModuleImp(outer) with YGJKParameters {
  val edge = outer.node.edges.out(0)
  val (tl_out, _) = outer.node.out(0)
  val io = IO(new Bundle {
    val ready = Output(Bool())
    val acc_req = Input(new AccReq)
    val resp = Valid(UInt(32.W))
  })
  val busy = RegInit(false.B)
  when(!busy){
    when(tl_out.a.fire()){
      busy:=true.B
    }
  }.otherwise{
    when(tl_out.d.fire()){
      busy:=false.B
    }
  }
  tl_out.a.valid := io.acc_req.valid && !busy
  tl_out.a.bits := edge.Get(0.U, io.acc_req.addr, 2.U)._2   // 发送4byte请求, 2^2byte
  io.ready := tl_out.a.ready && !busy
  tl_out.d.ready := true.B
  io.resp.valid := tl_out.d.valid
  io.resp.bits := tl_out.d.bits.data
}
*/