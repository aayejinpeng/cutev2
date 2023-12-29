package boom.exu.ygjk

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._

import freechips.rocketchip.rocket._ 
import boom.common._ 


class Yg2TL2(implicit p: Parameters) extends LazyModule with YGJKParameters {
  lazy val module = new Yg2TLImp2(this)
  val node = TLClientNode(Seq(TLClientPortParameters(Seq(TLClientParameters(
    name = "ygjk",
    sourceId = IdRange(0, sourceNum))))))
}

class Yg2TLImp2(outer: Yg2TL2) extends LazyModuleImp(outer) with YGJKParameters {
  val edge = outer.node.edges.out(0)
  val (tl_out, _) = outer.node.out(0)

  val blkBytes = 64
  val lgBlkBytes = 6
  val io = IO(new Bundle{
    val req_id = Output(UInt(5.W))
    val req = Flipped(Decoupled(new BoomBundle{
      val addr = UInt(paddrBits.W)
      val data = UInt((JKDataNum * dataWidth).W)
      val cmd = UInt(2.W)
      val size = UInt(3.W) // log2(bytes)
      val mask = UInt(blkBytes.W)
    }))
//    val resp = Valid(UInt((JKDataNum * encDataBits).W))
    val resp = Valid(new Bundle{
      val data = UInt((JKDataNum * dataWidth).W)
      val id = UInt(5.W)
    })
    val idle = Output(Bool())
  })

  def getoff(addr: UInt): UInt = addr(lgBlkBytes - 1, 0)

  //  val mask = VecInit.tabulate(blkBytes){i => io.req.bits.mask((i.U >> io.req.bits.size).asUInt())}.asUInt()
  val masks = VecInit.tabulate(7){ i => VecInit.tabulate(blkBytes){ j => io.req.bits.mask(j >> i)}.asUInt() }
  val data = io.req.bits.data << (getoff(io.req.bits.addr) << 3)
  val offset = RegEnable(getoff(io.req.bits.addr), tl_out.a.fire())
  val busy = RegInit(VecInit(Seq.fill(sourceNum)(false.B)))

  val id = WireInit(0.U(5.W))
  
  io.idle := !busy.reduce(_|_)

//  val i = Wire(UInt(3.W))
  for(i <- 0 until sourceNum){
    when(busy(i) === false.B){
      id := i.U
    }
  }
  io.req_id := id

  when(!(busy.reduce(_&_))){
    when(tl_out.a.fire()){
      busy(id):=true.B
    }
  }

  when(tl_out.d.fire()){
    busy(tl_out.d.bits.source) := false.B
  }

  tl_out.a.valid := io.req.valid && !(busy.reduce(_&_))
  tl_out.a.bits := Mux1H(Seq(
    (io.req.bits.cmd === 0.U) -> edge.Get(id, io.req.bits.addr, io.req.bits.size)._2,
    (io.req.bits.cmd === 1.U) -> edge.Put(id, io.req.bits.addr, io.req.bits.size, data)._2,
    (io.req.bits.cmd === 2.U) -> edge.Get(id, io.req.bits.addr, lgBlkBytes.U)._2,
    (io.req.bits.cmd === 3.U) -> edge.Put(id, io.req.bits.addr, lgBlkBytes.U, data, masks(io.req.bits.size))._2
  ))


  tl_out.d.ready := true.B
  io.resp.valid := tl_out.d.valid && tl_out.d.bits.opcode === TLMessages.AccessAckData
  io.req.ready := tl_out.a.ready && !(busy.reduce(_&_))
  io.resp.bits.data := tl_out.d.bits.data >> (offset << 3.U).asUInt()
  io.resp.bits.id := tl_out.d.bits.source

//  when(io.req.fire() && (io.req.bits.cmd === 3.U)){
//    edge.slave.slaves.foreach(para => printf(p"${para.supportsPutPartial}"))
//    assert(edge.slave.supportsPutPartialSafe(io.req.bits.addr, lgBlkBytes.U))
//  }

}
