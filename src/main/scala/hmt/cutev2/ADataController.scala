
package boom.acc.cute

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import boom.exu.ygjk._

//代表对MatrixTE供数的供数逻辑控制单元，隶属于TE，负责选取Scarchpad，选取Scarchpad的行，向TE供数。
//主要问题在如何设计Scarchpad，在为两种模式供数时(矩阵乘运算和卷积运算)，不存在bank冲突，数据每拍都能完整供应上。
//对TE的供数需求是Reduce_Width，Tensor_shape则表示了要存储的数据量。合理的分法是，分Matrix_N个bank，这样就可以合理的为数据进行编排了。
//本模块的核心设计是以ConfigInfo为输入进行配置的，以模块内部寄存器为基础的，长时间运行的取数地址计算和状态机设计。
class ADataController extends Module with HWParameters{
    val io = IO(new Bundle{

        //TODO:需要一个ScarchPad的接口～
        //先整一个ScarchPad的接口的总体设计
        val ScarchPadIO = Flipped(new DataControlScaratchpadIO)
        val ConfigInfo = Flipped(DecoupledIO(new ConfigInfoIO))
        val VectorA = DecoupledIO(UInt(ResultWidth.W))
    })


}
