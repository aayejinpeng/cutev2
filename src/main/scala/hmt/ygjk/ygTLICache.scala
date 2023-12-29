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
import freechips.rocketchip.rocket.constants.MemoryOpConstants

// 对于Icache
trait HasYgtlICacheParams extends YGJKParameters{
  val nWays = 1   // 直接相连
  val nSets = 8
  val blkBytes = 64   // 每行存16条4byte指令
  val blkBits = 64 * 8  // 512
  val idxBits = log2Ceil(nSets)     // 3
  val offsetBits = log2Ceil(blkBytes)   // 6
  val tagBits = dataWidth - idxBits - offsetBits  // 23
}


class YgTLICache(implicit p:Parameters) extends LazyModule with HasYgtlICacheParams {
  lazy val module = new YgTLICacheImp(this)
  val node = TLClientNode(Seq(TLClientPortParameters(Seq(TLClientParameters(
    name = "YgtlICache",
    sourceId = IdRange(0,1),
    supportsProbe = TransferSizes(blkBytes, blkBytes)   // [min, max], 都传输64byte
  )), minLatency = 1)))
}

class YgTLICacheImp(outer:YgTLICache) extends LazyModuleImp(outer) with HasYgtlICacheParams{
  val edge = outer.node.edges.out(0)
  val (tl_out, _) = outer.node.out(0)
  val io = IO(new Bundle {
    val ready = Output(Bool())
    val acc_req = Input(new AccReq)
//    val resp = Valid(Vec(JKDataNum, Bits(encDataBits.W)))
    val resp = Valid(UInt(dataWidth.W))   // 取出的4byte指令
  })

  def getTag(addr: UInt): UInt = {
    addr(31, 32 - tagBits)
  }
  def getIndex(addr: UInt):UInt = {
    addr(idxBits + offsetBits - 1, offsetBits)
  }
  def getOffset(addr:UInt):UInt = {
    addr(offsetBits-1, 0)
  }

//  val data = SyncReadMem(nSets, Vec(JKDataNum, Bits(encDataBits.W)))
  val Data  = RegInit(VecInit(Seq.fill(nSets)(0.U(blkBits.W))))   // todo: 对于这种大数组, 需要用AsynReadMem吗?
  val tag   = RegInit(VecInit(Seq.fill(nSets)(1.U(tagBits.W))))
  val dataV = RegInit(VecInit(Seq.fill(nSets)(false.B)))

  val addr  = io.acc_req.addr
  val addr_align = ((addr >> offsetBits.U) << offsetBits.U)(dataWidth-1, 0)   // tilelink请求地址是16*4byte=512bit对齐的

  // 正常命中, 当拍返回inst
  val req_idx = getIndex(addr)   // 去索引一行
  val req_tag = getTag(addr)
  val dataIdx_valid = dataV(req_idx) === true.B
  val tagIdx_hit = tag(req_idx) === req_tag
  val hit = dataIdx_valid && tagIdx_hit

  val offset = getOffset(addr)(offsetBits-1, 2)  // 只看前4位
  io.resp.valid := hit && io.acc_req.valid
  io.resp.bits  := (Data(req_idx) >> offset*dataWidth.U)(dataWidth-1, 0)   // 返回存的数据

  // 看下一行地址是否命中(默认不命中, 要预取)
  val prefetch = WireInit(false.B)
  val addr_prefetch = addr + blkBytes.U   // 若一行存64byte=16条inst, pc += 16*4
  val addr_prefetch_align = addr_align.asUInt() + blkBytes.U
  val addr_prefetch_reg = RegInit(0.U(dataWidth.W))
  val idx_nextline = (req_idx+1.U)(idxBits-1, 0)  // 加1后取余, cache下一行idx
  val dataV_nextline = dataV(idx_nextline)
  val tag_prefetch_miss = getTag(addr_prefetch) =/= tag(idx_nextline)  // 加1后取余, 预取下一行地址不为存的tag下一行
  prefetch := !dataV_nextline || tag_prefetch_miss  // 下一行没数据or数据地址不是addr+4*16

  val tag_reg = RegInit(0.U(tagBits.W))   // 存一下发送的tag

  // 没命中, 发请求, 等待返回数据并写入
  when(tl_out.d.valid){   // 有返回数据了
    when(!hit){
      Data(req_idx) := tl_out.d.bits.data   // 整行存入, 替换原有的行
      tag(req_idx)  := tag_reg
      dataV(req_idx):= true.B
    }.elsewhen(prefetch){
      Data(idx_nextline) := tl_out.d.bits.data
      tag(idx_nextline)  := tag_reg   // 需要存一下吗? idx_nextline或许还要存一下, 看结果吧
      dataV(idx_nextline):= true.B
    }
  }

  val busy = RegInit(false.B)
  when(!busy){
    when(tl_out.a.fire()){
      busy := true.B
      tag_reg := Mux(!hit, req_tag, getTag(addr_prefetch))  // 发送时刻的tag存一下
    }
  }.otherwise{
    when(tl_out.d.fire()){
      busy := false.B
    }
  }

  tl_out.a.valid  := io.acc_req.valid && !busy && (!hit || prefetch)    // 没命中或者要预取, 再发送
  val addr_a  = Mux(!hit, addr_align, addr_prefetch_align)        // 没命中优先
  tl_out.a.bits   := edge.Get(0.U, addr_a, offsetBits.U)._2  // 只有读有效, 注意这里请求地址要对齐
  io.ready := tl_out.a.ready   // 在取指过程中, 忙状态也能接收请求

  tl_out.d.ready  := true.B

}
*/