
package boom.acc.cute

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import boom.exu.ygjk._
// import freechips.rocketchip.regmapper.RRTest0Map

trait HWParameters{
//Scaratchpad中保存的张量形状
    val Tensor_M = 64
    val Tensor_N = 64
    val Tensor_K = 64

//ReduceWidthByte 代表ReducePE进行内积时的数据宽度，单位是字节
    val ReduceWidthByte = 32
    val ReduceWidth = ReduceWidthByte * 8
//ResultWidthByte 代表ReducePE的结果宽度，单位是字节
    val ResultWidthByte = 4
    val ResultWidth = ResultWidthByte * 8
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
    val dataType = (UInt(3.W))  //1-32位，2-16位， 4-32位
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
    val Chosen = Bool()
}

class AMemoryLoaderScaratchpadIO extends Bundle with HWParameters{
    //bankaddr是对nbanks个bank，各自bank的行选信号,是一个vec，有nbanks个元素，每个元素是一个UInt，UInt的宽度是log2Ceil(AScratchpadBankNLines)，是输入的需要握手的数据
    val BankAddr = Flipped(DecoupledIO(Vec(AScratchpadNBanks, (UInt(log2Ceil(AScratchpadBankNEntrys).W)))))
    //bankdata是对nbanks个bank，各自bank的行数据，是一个vec，有nbanks个元素，每个元素是一个UInt，UInt的宽度是ReduceWidthByte*8
    val Data = Valid(Vec(AScratchpadNBanks, UInt(ReduceWidth.W)))
    //chosen是选择该ScarchPad的信号，是一个bool，我们做doublebuffer，选择其一供数，选择其一加载数据
    val Chosen = Bool()
}

case object  ElementDataType extends Field[UInt]{
    val DataTypeUndef = 0.U(3.W)
    val DataTypeUInt32 = 1.U(3.W)
    val DataTypeUInt16 = 2.U(3.W)
    val DataTypeUInt8  = 3.U(3.W)
}