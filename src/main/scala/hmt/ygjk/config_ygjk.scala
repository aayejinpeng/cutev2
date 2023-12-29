/*
package boom.exu.ygjk

//import boom.acc.FetchParameters
import chisel3._
import chisel3.util._
import freechips.rocketchip.tile._
import freechips.rocketchip.rocket._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._ //for LazyModule


class WithYGJKAccel extends Config((site,here,up) => {
    case BuildRoCC => Seq(       
        (p:Parameters) => {
            val regWidth = 32 // 寄存器位宽
            val ygjk = LazyModule(new RoCC2YGJK(OpcodeSet.all)(p))
            ygjk
        }
    ) 
})

trait YGJKParameters{
    def rowWords = 2
    def refillCycles = 8
    def encDataBits = 32
    val bufferLine = 32
    val JKDataNum = 16 //数据接口bufferLine个数
    val dataNum = 16
    val dataWidth = 32
    val sourceNum = 16
    val addrWidth = 64
    val ygjk_memWidth = 64   //单位byte,一个访存请求的数据量
//    val BlkBytes = JKDataNum * encDataBits / 8  // 64
//    val lgBlkBytes = log2Ceil(BlkBytes)         // 6
}

trait L0cacheParameters extends YGJKParameters{
  def accWays = 1   //  每组就一行, 就是直接相连cache
  def accSets = 8   // 有8组
  def setbits = 3   // s = log2ceil(8) = 3, 中间位 = 3
  def lndatas = rowWords*refillCycles   // 2 * 8 = 16, 一行有16块?
  def offsetbits = log2Ceil(lndatas)    // offset = 4
  def tagbits = (32-offsetbits)         // tag = 32 - 4 = 28
  def isvalid = 0.U   // 3种cache状态, 有效, 无效, 脏
  def invalid = 1.U
  def dirty = 2.U
}



class AccReq  extends Bundle with YGJKParameters{
    val valid = Bool()
    val addr = UInt(addrWidth.W)
    val cmd = UInt(1.W) //0-R 1-W
    val data = Vec(JKDataNum,Bits(encDataBits.W))   // 写数据, 也是16*32
//    val size = UInt(32.W)   // 具体写多少
}

class config extends Bundle{
    val cfgData1 = UInt(64.W)
    val cfgData2 = UInt(64.W)
    val func = UInt(7.W)
}
 
class YGJKCommand extends Bundle{
  val axh_cready_a = Output(Bool())  
  val axh_cready_b = Output(Bool())  
  val acc_req_a = Input(new AccReq)   // 加速器的向ygcache的访存请求
  val acc_req_b = Input(new AccReq)
  val req_id = Output(UInt(5.W))
}
class YGJKBuffer extends Bundle with YGJKParameters{
  val brvalid = Output(Bool())   
  val brdata = Output(Vec(JKDataNum,UInt(bufferLine.W)))   // 读取的信息 16*32 = 512bit = 64byte
  val id = Output(UInt(5.W))
  val bwvalid = Output(Bool())     
  val bwdata = Output(Vec(JKDataNum,UInt(bufferLine.W)))   // ygcache返回的另一个读端口
}
class YGJKControl extends Bundle{
  val hax_jval = Output(Bool())     // 指令有效 & func(0)为0
  val reset = Output(Bool())  // reset
  val axh_jrunning = Input(Bool())
  val axh_jdone = Input(Bool())
  val config  = Output(new config)  // 包含了两个reg的信息, 传递给cpu
}

// from the pov of the datapath
class MemPortIo(val data_width: Int) extends Bundle 
{
   val req    = new DecoupledIO(new MemReq(data_width))
   val resp   = Flipped(new ValidIO(new MemResp(data_width)))
}

class MemReq(val data_width: Int) extends Bundle
{
   val addr = Output(UInt(32.W))
   val data = Output(UInt(data_width.W))
   val fcn  = Output(UInt(2.W))  // memory function code
   val typ  = Output(UInt(4.W)) // memory type
   val vstore_mask = Output(UInt(64.W)) //vec store mask, 实际上是64位的
}

class MemResp(val data_width: Int) extends Bundle
{
   val data = Output(UInt(data_width.W))
}

class YGJKIO extends Bundle {
  val cmd     = new YGJKCommand   // 访存请求
  val buffer  = new YGJKBuffer    // 数据返回通道
  val ctl     = new YGJKControl   // 控制命令通道
//  val imem    = Flipped(new MemPortIo(32))
//  val dmem    = Flipped(new MemPortIo(512)) // 数据是512通道的
}


case object BuildYGAC extends Field[Parameters => MyACCModule]

class stateQuery extends Bundle{
  val valid = Input(Bool())
  val rs1 = Input(UInt(64.W))
  val rs2 = Input(UInt(64.W))
  val rd = Output(UInt(64.W))
}

abstract class MyACCModule extends Module with YGJKParameters{
//   val io = IO(Flipped(new YGJKIO))   // 好吧, 这里是有翻转接口的!!!
 val io = IO(new Bundle{
    val sdma = new ACC2SDMAIO
    val config = Flipped(new ValidIO(new config))
    val query = new stateQuery
  })
}

*/
