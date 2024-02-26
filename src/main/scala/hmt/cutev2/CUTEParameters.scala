
package boom.acc.cute

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import boom.exu.ygjk._

trait HWParameters{
//Scaratchpad中保存的张量形状
    val Tensor_M = 64
    val Tensor_N = 64
    val Tensor_K = 64
//AScaratchpad中保存的张量形状为M*K
//AScaratchpad的大小为Tenser_M * Tensor_K * ReduceWidthByte
//需要考虑Scaratchpad的顺序读，需要考虑为Scaratchpad分bank
    val AScratchpadSize = Tensor_M * Tensor_K * ReduceWidthByte //reduce
    val BScratchpadSize = Tensor_N * Tensor_K * ReduceWidthByte //reduce
    val CScratchpadSize = Tensor_M * Tensor_N * ResultWidthByte //result
    val AScratchpadBank = 4 //注意这里与Matrix_M有强相关性，一般是Matrix_M的整数倍
    val BScratchpadBank = 4 //这里与Matrix_N强相关
    val CScratchpadBank = 4 //这里与Tensor_K强相关，同时读写任务～

//Matrix_M，代表TE执行的矩阵乘法的M的大小
    val Matrix_M = 4
//Matrix_N，代表TE执行的矩阵乘法的N的大小
    val Matrix_N = 4

//ReduceWidthByte 代表ReducePE进行内积时的数据宽度，单位是字节
    val ReduceWidthByte = 32
    val ReduceWidth = ReduceWidthByte * 8
//ResultWidthByte 代表ReducePE的结果宽度，单位是字节
    val ResultWidthByte = 4
    val ResultWidth = ResultWidthByte * 8


//MACLatency 用于ReducePE内的乘累加树的延迟描述
    val MAC32TreeLevel = log2(ReduceWidthByte * 8 / 32)
    val MAC32Latency = 3 //这是一个经验值，依据时序结果，填写的需要切分的流水段数量
    val MAC16TreeLevel = log2(ReduceWidthByte * 8 / 16)
    val MAC16Latency = 4
    val MAC8TreeLevel = log2(ReduceWidthByte * 8 / 8)
    val MAC8Latency = 5
    //val MACresq = 8
}

//需要配置的信息：oc -- 控制器发来的oc编号, 
//                ic, oh, ow, kh, kw, ohb -- 外层循环次数,
//                icb -- 矩阵乘计算中的中间长度
//                paddingH, paddingW, strideH, strideW -- 卷积层属性
class ConfigInfoIO extends Bundle with HWParameters with YGJKParameters{
    val oc = Flipped(Valid(UInt(ocWidth.W)))               //对应reorder分块后的oc层循环次数
    val ic = Flipped(Valid(UInt(icWidth.W)))               //对应reorder分块后的ic层循环次数
    val ih = Flipped(Valid(UInt(ohWidth.W)))
    val iw = Flipped(Valid(UInt(owWidth.W)))
    val oh = Flipped(Valid(UInt(ohWidth.W)))
    val ow = Flipped(Valid(UInt(owWidth.W)))
    val kh = Flipped(Valid(UInt(khWidth.W)))
    val kw = Flipped(Valid(UInt(kwWidth.W)))
    val ohb = Flipped(Valid(UInt(ohbWidth.W)))
    val icb = Flipped(Valid(UInt(icWidth.W)))
    val paddingH = Flipped(Valid(UInt(paddingWidth.W)))
    val paddingW = Flipped(Valid(UInt(paddingWidth.W)))
    val strideH = Flipped(Valid(UInt(strideWidth.W)))
    val strideW = Flipped(Valid(UInt(strideWidth.W)))
    val start = Input(Bool()) //开始运行信号，持续一拍
    val Aaddr = Flipped(Valid(UInt(addrWidth.W))) //A矩阵首地址
    val Baddr = Flipped(Valid(UInt(addrWidth.W))) //B矩阵首地址
    val Caddr = Flipped(Valid(UInt(addrWidth.W))) //C矩阵首地址
    val dataType = Flipped(Valid(UInt(3.W)))  //1-32位，2-16位， 4-32位
//    val idle = Output(Bool())
}

case object  ElementDataType extends Field[UInt]{
    val DataTypeUInt32 = 1.U(3.W)
    val DataTypeUInt16 = 2.U(3.W)
    val DataTypeUInt8  = 4.U(3.W)
}