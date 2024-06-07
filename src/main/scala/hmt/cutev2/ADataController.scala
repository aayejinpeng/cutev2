
package boom.acc.cute

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import boom.exu.ygjk._

//代表对MatrixTE供数的供数逻辑控制单元，隶属于TE，负责选取Scarchpad，选取Scarchpad的行，向TE供数。
//主要问题在如何设计Scarchpad，在为两种模式供数时(矩阵乘运算和卷积运算)，不存在bank冲突，数据每拍都能完整供应上。
//对TE的供数需求是Reduce_Width，Tensor_shape则表示了要存储的数据量。合理的分法是，分Matrix_N个bank，这样就可以合理的为数据进行编排了。
//本模块的核心设计是以ConfigInfo为输入进行配置的，以模块内部寄存器为基础的，长时间运行的取数地址计算和状态机设计。
class ADataController(implicit p: Parameters) extends Module with HWParameters{
    val io = IO(new Bundle{

        //先整一个ScarchPad的接口的总体设计
        val FromScarchPadIO = Flipped(new ADataControlScaratchpadIO)
        val ConfigInfo = Flipped(DecoupledIO(new ConfigInfoIO))
        val VectorA = DecoupledIO(UInt((ReduceWidth*Matrix_M).W))
        val CaculateEnd = DecoupledIO(Bool())
        val TaskEnd = Flipped(DecoupledIO(Bool()))
    })

    //TODO:init
    io.VectorA.valid := false.B
    io.VectorA.bits := 0.U
    io.ConfigInfo.ready := false.B
    io.TaskEnd.ready := false.B
    io.CaculateEnd.bits := false.B
    io.CaculateEnd.valid := false.B
    io.FromScarchPadIO.Chosen := false.B

    val ScarchPadRequestBankAddr = io.FromScarchPadIO.BankAddr
    ScarchPadRequestBankAddr.bits := 0.U.asTypeOf(ScarchPadRequestBankAddr.bits)
    ScarchPadRequestBankAddr.valid := false.B
    val ScarchPadData = io.FromScarchPadIO.Data //1周期的延迟
    val ScarchPadChosen = io.FromScarchPadIO.Chosen

    val ConfigInfo = io.ConfigInfo.bits

    //任务状态机 先来个简单的，顺序遍历所有bank，返回数据
    val s_idle :: s_mm_task :: s_write :: Nil = Enum(3) //TODO:新增状态，这里要加各种计算状态，mm，sliding window之类的
    val state = RegInit(s_idle)

    //计算状态机，用来配合流水线刷新
    val s_cal_idle :: s_cal_init :: s_cal_working :: s_cal_end :: Nil = Enum(4)
    val calculate_state = RegInit(s_cal_idle)
    val ScaratchpadWorkingTensor_M = RegInit(0.U(ScaratchpadMaxTensorDimBitSize.W))
    val ScaratchpadWorkingTensor_N = RegInit(0.U(ScaratchpadMaxTensorDimBitSize.W))
    val ScaratchpadWorkingTensor_K = RegInit(0.U(ScaratchpadMaxTensorDimBitSize.W))



    //输出state
    when(io.ConfigInfo.valid)
    {
        printf("[ADataController]ADataController: state is %d\n", state)
        printf("[ADataController]ADataController: calculate_state is %d\n", calculate_state)
    }


    //矩阵乘的状态机
    //如果config是矩阵乘，那么就是矩阵乘的状态机
    when(state === s_idle){
        //TODO:是否要考虑 计算状态机的值？
        when(io.ConfigInfo.valid){
            when(io.ConfigInfo.bits.taskType === CUTETaskType.TaskTypeMatrixMul && io.ConfigInfo.bits.ComputeGo === true.B){
                //阶段0，配置信息就位，开始配置
                printf("[ADataController]ADataController: ConfigInfo is valid, taskType is MatrixMul, ComputeGo is true\n")
                state := s_mm_task
                ScaratchpadWorkingTensor_M := io.ConfigInfo.bits.ScaratchpadTensor_M
                ScaratchpadWorkingTensor_N := io.ConfigInfo.bits.ScaratchpadTensor_N
                ScaratchpadWorkingTensor_K := io.ConfigInfo.bits.ScaratchpadTensor_K
                io.ConfigInfo.ready := true.B
                ScarchPadChosen := true.B
                //阶段0，让计算状态机开始初始化
                calculate_state := s_cal_init
            }
        }
    }

    //数据在Scarachpad中的编排
    //数据会先排K，再排M
    //AVector一定是不同M的数据，K不断送入，直到K迭代完成，再换新的M，
    //   K 0 1 2 3 4 5 6 7     time     AVector     ScaratchpadData也这么排布
    // M                        0       0 8 g o             {bank[0] [1] [2] [3]}
    // 0   0 1 2 3 4 5 6 7      1       1 9 h p   |addr    0 |    0   8   g   o
    // 1   8 9 a b c d e f      2       2 a i q   |        1 |    1   9   h   p
    // 2   g h i j k l m n      3       3 b j r   |        2 |    2   a   i   q
    // 3   o p g r s t u v      4       4 c k s   |        3 |    3   b   j   r
    // 4   w x y z .......      5       5 d l t   |        4 |    4   c   k   s
    // 5   !..............      6       6 e m u   |        5 |    5   d   l   t
    // 6   @..............      7       7 f n v   |        6 |    6   e   m   u
    // 7   #..............      8       w ! @ #   |        7 |    7   f   n   v
    // 8   $..............      9       .......   | ...........................

    //矩阵乘的状态机，遍历所有数据就完事了
    //TODO:这里可以修改遍历顺序来节省带宽
    //首先Scaratchpad的数据有Tensor_M*Tensor_K个，每个数据是ReduceWidth位
    //然后我们要把这些数据送入TE，每次送入的数据是Matrix_M个，每个数据是Matrix_N*ReduceWidth位
    //我们的Scaratchpad是先排K再排M，所以我们的数据送入也是先送K再送M，每次送完一批K，重复Tensor_N/Matrix_N次，再切换M
    val M_Iterator = RegInit(0.U(ScaratchpadMaxTensorDimBitSize.W))
    val N_Iterator = RegInit(0.U(ScaratchpadMaxTensorDimBitSize.W))
    val K_Iterator = RegInit(0.U(ScaratchpadMaxTensorDimBitSize.W))

    //TODO:这里是否需要一个这样的除法电路？实际上一个截断/移位电路就可以了？因为我们一定是整数倍的，多余的地方补0即可。
    //如果是除法电路，这里需要几拍？其实不会，Matrix_M一定是2的幂次，所有这个除法一定会被优化成移位，一定是一拍完成的，一定会优化成移位电路
    val M_IteratorMax = (ScaratchpadWorkingTensor_M / Matrix_M.U)
    val N_IteratorMax = (ScaratchpadWorkingTensor_N / Matrix_N.U)
    val K_IteratorMax = (ScaratchpadWorkingTensor_K)

    val Max_Caculate_Iter = M_IteratorMax * N_IteratorMax * K_IteratorMax

    //统计读数请求次数
    val AVectorCount = RegInit(0.U(32.W))
    //如果是mm_task,且计算状态机是init，那么就开始初始化
    when(state === s_mm_task){
        when(calculate_state === s_cal_init){
            M_Iterator := 0.U
            N_Iterator := 0.U
            K_Iterator := 0.U
            //阶段1，计算初始化完成，开始工作
            calculate_state := s_cal_working
        }.elsewhen(calculate_state === s_cal_working){
            //阶段2，计算开始，计算对Scarchpad的取数地址
            //输出所有的mnk和maxmnk
            printf("[ADataController]ADataController: M_Iterator is %d, N_Iterator is %d, K_Iterator is %d\n", M_Iterator, N_Iterator, K_Iterator)
            printf("[ADataController]ADataController: M_IteratorMax is %d, N_IteratorMax is %d, K_IteratorMax is %d\n", M_IteratorMax, N_IteratorMax, K_IteratorMax)
            //循环的最外层是M，然后是N，最后是K
            val next_addr = Wire(UInt(AScratchpadBankNEntrys.W))
            next_addr := M_Iterator * K_IteratorMax + K_Iterator
            ScarchPadRequestBankAddr.bits.foreach(_ := next_addr)
            
            when(AVectorCount < Max_Caculate_Iter){
                //计算取数地址
                ScarchPadRequestBankAddr.valid := true.B
                K_Iterator := K_Iterator + 1.U
                when(K_Iterator === K_IteratorMax - 1.U){
                    K_Iterator := 0.U
                    N_Iterator := N_Iterator + 1.U
                    when(N_Iterator === N_IteratorMax - 1.U){
                        N_Iterator := 0.U
                        M_Iterator := M_Iterator + 1.U
                    }
                }
            }.otherwise{
                ScarchPadRequestBankAddr.valid := false.B
            }
            //TODO:这里默认AVector是默认握手的，如果不能默认握手，需要一个FIFO缓冲这部分数据
            io.VectorA.valid := ScarchPadData.valid
            io.VectorA.bits := ScarchPadData.bits.asTypeOf(io.VectorA.bits) //这里是顺序摆放，卷积可以重新组合
            when(io.VectorA.valid)//double buffer，保证了每週期回數
            {
                AVectorCount := AVectorCount + 1.U
                when(AVectorCount === Max_Caculate_Iter - 1.U){
                    calculate_state := s_cal_end
                }
                //输出AVectorCount，VectorA的信息
                printf("[ADataController]ADataController: AVectorCount is %d\n", AVectorCount)
            }
        }.elsewhen(calculate_state === s_cal_end){
            //计算结束，要么结束计算，要么切换ScarchPad
            io.CaculateEnd.valid := true.B
            io.CaculateEnd.bits := true.B
            printf("[ADataController]ADataController: CaculateEnd is valid\n")
            when(io.CaculateEnd.fire){
                state := s_idle
                calculate_state := s_cal_idle
            }

        }.elsewhen(calculate_state === s_cal_idle){
            //计算状态机空闲
            //加速器闲闲没事做
        }.otherwise{
            //未定义状态
            //加速器闲闲没事做
        }
    }





    
}
