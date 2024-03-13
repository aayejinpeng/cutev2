
package boom.acc.cute

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import boom.exu.ygjk._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.prci._
import freechips.rocketchip.tile._
// import freechips.rocketchip.regmapper.RRTest0Map

case class MMAccConfig()
case object MMAccKey extends Field[Option[MMAccConfig]](None)
case object BuildDMAygjk extends Field[Seq[Parameters => LazyRoCC]](Nil)
class Withacc_MMacc extends Config((site,here,up) => {  
    case BuildYGAC =>
        (p:Parameters) => {          
            val myAccel = Module(new MMacc)
            myAccel
        }
    case MMAccKey => true
    case BuildDMAygjk => true
    }
)

class CUTECrossingParams(
  override val MemDirectMaster: TilePortParamsLike = TileMasterPortParams(where = MBUS)
) extends RocketCrossingParams
trait HWParameters{

//ReduceWidthByte 代表ReducePE进行内积时的数据宽度，单位是字节
    val ReduceWidthByte = 32
    val ReduceWidth = ReduceWidthByte * 8
//ResultWidthByte 代表ReducePE的结果宽度，单位是字节
    val ResultWidthByte = 4
    val ResultWidth = ResultWidthByte * 8

//最大可处理的程序的张量形状，
    val ApplicationMaxTensorSize = 16384
    val ApplicationMaxTensorSizeBitSize = log2Ceil(ApplicationMaxTensorSize) + 1
//MMU的地址宽度
    val MMUAddrWidth = 64
//MMU的数据线宽度
    val MMUDataWidth = ReduceWidth //TODO:ReduceWidth等于LLCDataWidth，以后得改
//MMU的数据线有效数据位数
    val MMUDataWidthBitSize = log2Ceil(MMUDataWidth) + 1
//LLC的数据线宽度
    val LLCDataWidth = 256      //TODO:这个值需要从chipyard的config中来
//Memory的数据线宽度
    val MemoryDataWidth = 64    //TODO:这个值需要从chipyard的config中来
//LLC总线上的source最大数量 --> 这个参数和LLC的访存延迟强相关，若要满流水，这个sourceMAXnum的数量必须大于LLC的访存延迟
    val LLCSourceMaxNum = 32
    val LLCSourceMaxNumBitSize = log2Ceil(LLCSourceMaxNum) + 1
//Memory总线上的source最大数量 --> 这个参数和Memory的访存延迟强相关，若要满流水，这个sourceMAXnum的数量必顶大于Memory的访存延迟
    val MemorysourceMaxNum = 32
    val MemorysourceMaxNumBitSize = log2Ceil(MemorysourceMaxNum) + 1

    val SoureceMaxNum = math.max(LLCSourceMaxNum, MemorysourceMaxNum)
    val SoureceMaxNumBitSize = log2Ceil(SoureceMaxNum) + 1


//Scaratchpad中保存的张量形状
    val Tensor_M = 64
    val Tensor_N = 64
    val Tensor_K = 64
    val ScaratchpadMaxTensorDim = Math.max(Tensor_M, Math.max(Tensor_N, Tensor_K))
    val ScaratchpadMaxTensorDimBitSize = log2Ceil(ScaratchpadMaxTensorDim) + 1
//AScaratchpad中保存的张量形状为M*K
//AScaratchpad的大小为Tenser_M * Tensor_K * ReduceWidthByte
//需要考虑Scaratchpad的顺序读，需要考虑为Scaratchpad分bank
    val AScratchpadSize = Tensor_M * Tensor_K * ReduceWidthByte //reduce
    val BScratchpadSize = Tensor_N * Tensor_K * ReduceWidthByte //reduce
    val CScratchpadSize = Tensor_M * Tensor_N * ResultWidthByte //result
//Matrix_M，代表TE执行的矩阵乘法的M的大小
    val Matrix_M = 4
//Matrix_N，代表TE执行的矩阵乘法的N的大小
    val Matrix_N = 4

//目前的Scratchpad设计，分Tensor_T个bank，每次取Tensor_T个数据，根据取数逻辑，在不同的bank里取不同的数据，然后拼接
    val AScratchpadNBanks = Matrix_M //注意这里与Matrix_M有强相关性，一般是Matrix_M的整数倍
    val BScratchpadNBanks = Matrix_N //这里与Matrix_N强相关
    val CScratchpadNBanks = 4 //这里与Tensor_K强相关，同时读写任务～
    val AScratchpadBankSize = AScratchpadSize / AScratchpadNBanks
    val BScratchpadBankSize = BScratchpadSize / BScratchpadNBanks
    val CScratchpadBankSize = CScratchpadSize / CScratchpadNBanks
    val AScratchpadBankNEntrys = AScratchpadBankSize / ReduceWidthByte
    val BScratchpadBankNEntrys = BScratchpadBankSize / ReduceWidthByte
    val CScratchpadBankNEntrys = CScratchpadBankSize / ResultWidthByte


//MACLatency 用于ReducePE内的乘累加树的延迟描述
    val MAC32TreeLevel = log2Ceil(ReduceWidthByte * 8 / 32)
    val MAC32Latency = 3 //这是一个经验值，依据时序结果，填写的需要切分的流水段数量
    val MAC16TreeLevel = log2Ceil(ReduceWidthByte * 8 / 16)
    val MAC16Latency = 4
    val MAC8TreeLevel = log2Ceil(ReduceWidthByte * 8 / 8)
    val MAC8Latency = 5
//乘累加FIFO的深度
    val ResultFIFODepth = 8
}

//需要配置的信息：oc -- 控制器发来的oc编号, 
//                ic, oh, ow, kh, kw, ohb -- 外层循环次数,
//                icb -- 矩阵乘计算中的中间长度
//                paddingH, paddingW, strideH, strideW -- 卷积层属性
class ConfigInfoIO extends Bundle with HWParameters with YGJKParameters{
    // val oc = Flipped(Valid(UInt(ocWidth.W)))               //对应reorder分块后的oc层循环次数
    // val ic = Flipped(Valid(UInt(icWidth.W)))               //对应reorder分块后的ic层循环次数
    // val ih = Flipped(Valid(UInt(ohWidth.W)))
    // val iw = Flipped(Valid(UInt(owWidth.W)))
    // val oh = Flipped(Valid(UInt(ohWidth.W)))
    // val ow = Flipped(Valid(UInt(owWidth.W)))
    // val kh = Flipped(Valid(UInt(khWidth.W)))
    // val kw = Flipped(Valid(UInt(kwWidth.W)))
    // val ohb = Flipped(Valid(UInt(ohbWidth.W)))
    // val icb = Flipped(Valid(UInt(icWidth.W)))
    // val paddingH = Flipped(Valid(UInt(paddingWidth.W)))
    // val paddingW = Flipped(Valid(UInt(paddingWidth.W)))
    // val strideH = Flipped(Valid(UInt(strideWidth.W)))
    // val strideW = Flipped(Valid(UInt(strideWidth.W)))
    // val start = Input(Bool()) //开始运行信号，持续一拍
    // val Aaddr = Flipped(Valid(UInt(addrWidth.W))) //A矩阵首地址
    // val Baddr = Flipped(Valid(UInt(addrWidth.W))) //B矩阵首地址
    // val Caddr = Flipped(Valid(UInt(addrWidth.W))) //C矩阵首地址
    
    val ApplicationTensor_A = (new Bundle{
        val ApplicationTensor_A_BaseVaddr = (UInt(MMUAddrWidth.W))
        val BlockTensor_A_BaseVaddr       = (UInt(MMUAddrWidth.W))
        val MemoryOrder                   = (UInt(MemoryOrderType.MemoryOrderTypeBitWidth.W))
        val Conherent                     = (Bool())
    })

    val ApplicationTensor_B = (new Bundle{
        val ApplicationTensor_B_BaseVaddr = (UInt(MMUAddrWidth.W))
        val BlockTensor_B_BaseVaddr       = (UInt(MMUAddrWidth.W))
        val MemoryOrder                   = (UInt(MemoryOrderType.MemoryOrderTypeBitWidth.W))
        val Conherent                     = (Bool())
    })
    
    val ApplicationTensor_C = (new Bundle{
        val ApplicationTensor_C_BaseVaddr = (UInt(MMUAddrWidth.W))
        val BlockTensor_C_BaseVaddr       = (UInt(MMUAddrWidth.W))
        val MemoryOrder                   = (UInt(MemoryOrderType.MemoryOrderTypeBitWidth.W))
        val Conherent                     = (Bool())
    })

    val ApplicationTensor_M = (UInt(ApplicationMaxTensorSizeBitSize.W))
    val ApplicationTensor_N = (UInt(ApplicationMaxTensorSizeBitSize.W))
    val ApplicationTensor_K = (UInt(ApplicationMaxTensorSizeBitSize.W))

    val ScaratchpadTensor_M = (UInt(ScaratchpadMaxTensorDimBitSize.W)) //Scaratchpad当前处理的矩阵乘的M //TODO:
    val ScaratchpadTensor_N = (UInt(ScaratchpadMaxTensorDimBitSize.W)) //Scaratchpad当前处理的矩阵乘的N
    val ScaratchpadTensor_K = (UInt(ScaratchpadMaxTensorDimBitSize.W)) //Scaratchpad当前处理的矩阵乘的K

    val taskType = (UInt(ElementDataType.DataTypeBitWidth.W)) //0-矩阵乘，1-卷积
    val dataType = (UInt(CUTETaskType.CUTETaskBitWidth.W)) //1-32位，2-16位， 4-32位
//    val idle = Output(Bool())
}

//从Scaratchpad中取数，要明确是从哪个bank里，取第几行的数据，然后完成数据拼接返回
//从哪个bank里取数据，取第几行的数据，是由datacontrol模块算出来的
//怎么在bank里编排数据，是由MemoryLoader模块填进去的
//MemoryLoader模块和datacontrol模块都有窗口期，可以完成数据额外的一些编排如量化、反稀疏、反量化、量化重排等等
//将MemoryLoader模块和datacontrol模块分开，是为了使用窗口期，让单读写口的ScarchPad可以独立运行
//有没有能同时读写的SRAM啊？我能保证不写同一块数据,还是先doublebuffer吧....
//我们考虑到回数的延迟，所以DataControl与Scarachpad之间也是有fifo的。考虑到后续的SRAM是一个简单模块，fifo要加在DataControl里，让Scarachpad尽可能简单。
class ADataControlScaratchpadIO extends Bundle with HWParameters{
    //bankaddr是对nbanks个bank，各自bank的行选信号,是一个vec，有nbanks个元素，每个元素是一个UInt，UInt的宽度是log2Ceil(AScratchpadBankNLines)，是输入的需要握手的数据
    val BankAddr = Flipped(DecoupledIO(Vec(AScratchpadNBanks, (UInt(log2Ceil(AScratchpadBankNEntrys).W)))))
    //bankdata是对nbanks个bank，各自bank的行数据，是一个vec，有nbanks个元素，每个元素是一个UInt，UInt的宽度是ReduceWidthByte*8
    val Data = Valid(Vec(AScratchpadNBanks, UInt(ReduceWidth.W)))
    //chosen是选择该ScarchPad的信号，是一个bool，我们做doublebuffer，选择其一供数，选择其一加载数据
    val Chosen = Input(Bool())
}

class AMemoryLoaderScaratchpadIO extends Bundle with HWParameters{
    //bankaddr是对nbanks个bank，各自bank的行选信号,是一个vec，有nbanks个元素，每个元素是一个UInt，UInt的宽度是log2Ceil(AScratchpadBankNLines)，是输入的需要握手的数据
    val BankId = Flipped(Valid(UInt(log2Ceil(AScratchpadNBanks).W)))
    val BankAddr = Flipped(Valid(UInt(log2Ceil(AScratchpadBankNEntrys).W)))
    //bankdata是对nbanks个bank，各自bank的行数据，是一个vec，有nbanks个元素，每个元素是一个UInt，UInt的宽度是ReduceWidthByte*8
    val Data = Flipped(Valid(UInt(ReduceWidth.W)))
    //chosen是选择该ScarchPad的信号，是一个bool，我们做doublebuffer，选择其一供数，选择其一加载数据
    val Chosen = Input(Bool())
}

//LocalMMU的接口
class LocalMMUIO extends Bundle with HWParameters{

    val Request = Flipped(DecoupledIO(new Bundle{
        val RequestVirtualAddr = UInt(MMUAddrWidth.W)
        val RequestConherent = Bool()
        val RequestData = UInt(MMUDataWidth.W)
        val RequestSourceID = UInt(SoureceMaxNumBitSize.W)
        val RequestType_isWrite = UInt(2.W) //0-读，1-写
    }))
    //读写请求分发到的TL Link的事务编号
    val ConherentRequsetSourceID = Valid(UInt(LLCSourceMaxNumBitSize.W))
    val nonConherentRequsetSourceID = Valid(UInt(MemorysourceMaxNumBitSize.W))

    val Response = Valid(new Bundle{
        val ReseponseData = UInt(MMUDataWidth.W)
        val ReseponseConherent = Bool()
        val ReseponseSourceID = UInt(SoureceMaxNumBitSize.W)
    })
}


//数据类型的样板类
case object  ElementDataType extends Field[UInt]{
    val DataTypeBitWidth = 3
    val DataTypeUndef  = 0.U(DataTypeBitWidth.W)
    val DataTypeUInt32 = 1.U(DataTypeBitWidth.W)
    val DataTypeUInt16 = 2.U(DataTypeBitWidth.W)
    val DataTypeUInt8  = 3.U(DataTypeBitWidth.W)
}

//工作任务的样板类
case object  CUTETaskType extends Field[UInt]{
    val CUTETaskBitWidth = 8
    val TaskTypeUndef = 0.U(CUTETaskBitWidth.W)
    val TaskTypeMatrixMul = 1.U(CUTETaskBitWidth.W)
    val TaskTypeConv = 2.U(CUTETaskBitWidth.W)
}

case object  MemoryOrderType extends Field[UInt]{
    val MemoryOrderTypeBitWidth = 8
    val OrderTypeUndef      = 0.U(MemoryOrderTypeBitWidth.W)
    val OrderType_Mb_Kb     = 1.U(MemoryOrderTypeBitWidth.W) //在地址空间中顺序摆放的顺序, Mb在前，Kb在后
    val OrderType_Mb_Nb     = 1.U(MemoryOrderTypeBitWidth.W) //在地址空间中顺序摆放的顺序, Mb在前，Nb在后
    val OrderType_Nb_Kb     = 1.U(MemoryOrderTypeBitWidth.W) //在地址空间中顺序摆放的顺序, Nb在前，Kb在后
    val OrderType_Nb_Mb     = 2.U(MemoryOrderTypeBitWidth.W) //在地址空间中顺序摆放的顺序, Nb在前，Mb在后
    val OrderType_Kb_Mb     = 2.U(MemoryOrderTypeBitWidth.W) //在地址空间中顺序摆放的顺序, Kb在前，Mb在后
    val OrderType_Kb_Nb     = 2.U(MemoryOrderTypeBitWidth.W) //在地址空间中顺序摆放的顺序, Kb在前，Nb在后

}



