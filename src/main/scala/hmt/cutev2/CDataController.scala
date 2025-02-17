
package boom.acc.cute

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import boom.exu.ygjk._
import boom.util._

//代表对MatrixTE供数的供数逻辑控制单元，隶属于TE，负责选取Scarchpad，选取Scarchpad的行，向TE供数。
//主要问题在如何设计Scarchpad，在为两种模式供数时(矩阵乘运算和卷积运算)，不存在bank冲突，数据每拍都能完整供应上。
//对TE的供数需求是Reduce_Width，Tensor_shape则表示了要存储的数据量。合理的分法是，分Matrix_N个bank，这样就可以合理的为数据进行编排了。
//本模块的核心设计是以ConfigInfo为输入进行配置的，以模块内部寄存器为基础的，长时间运行的取数地址计算和状态机设计。
class CDataController(implicit p: Parameters) extends Module with HWParameters{
    val io = IO(new Bundle{

        //先整一个ScarchPad的接口的总体设计
        val FromScarchPadIO = Flipped(new CDataControlScaratchpadIO)
        val ConfigInfo = Flipped(DecoupledIO(new ConfigInfoIO))
        val Matrix_C = DecoupledIO(UInt((ResultWidth*Matrix_M*Matrix_N).W))
        val ResultMatrix_D = Flipped(DecoupledIO(UInt((ResultWidth*Matrix_M*Matrix_N).W)))
        val CaculateEnd = DecoupledIO(Bool())
        val TaskEnd = Flipped(DecoupledIO(Bool()))
    })

    //TODO:init
    io.Matrix_C.valid := false.B
    io.Matrix_C.bits := 0.U
    io.ResultMatrix_D.ready := false.B
    io.ConfigInfo.ready := false.B
    io.TaskEnd.ready := false.B
    io.CaculateEnd.bits := false.B
    io.CaculateEnd.valid := false.B
    io.FromScarchPadIO.Chosen := false.B
    io.FromScarchPadIO.WriteBankAddr.valid := false.B
    io.FromScarchPadIO.WriteBankAddr.bits := 0.U.asTypeOf(io.FromScarchPadIO.WriteBankAddr.bits)
    io.FromScarchPadIO.WriteRequestData.valid := false.B
    io.FromScarchPadIO.WriteRequestData.bits := 0.U.asTypeOf(io.FromScarchPadIO.WriteRequestData.bits)    
    

    val ScarchPadReadRequestBankAddr = io.FromScarchPadIO.ReadBankAddr
    ScarchPadReadRequestBankAddr.bits := DontCare
    ScarchPadReadRequestBankAddr.valid := false.B
    val ScarchPadReadResponseData = io.FromScarchPadIO.ReadResponseData //1周期的延迟
    val ScarchPadChosen = io.FromScarchPadIO.Chosen

    io.FromScarchPadIO.WriteRequestData.valid := false.B
    io.FromScarchPadIO.WriteRequestData.bits := DontCare

    val ConfigInfo = io.ConfigInfo.bits

    //任务状态机 先来个简单的，顺序遍历所有bank，返回数据
    val s_idle :: s_mm_task :: s_write :: Nil = Enum(3) //TODO:新增状态，这里要加各种计算状态，mm，sliding window之类的
    val state = RegInit(s_idle)

    //计算状态机，用来配合流水线刷新
    val s_cal_idle :: s_cal_init :: s_cal_working :: s_cal_end :: Nil = Enum(4)
    val calculate_state = RegInit(s_cal_idle)
    val store_calculate_state = RegInit(s_cal_idle)
    val ScaratchpadWorkingTensor_M = RegInit(0.U(ScaratchpadMaxTensorDimBitSize.W))
    val ScaratchpadWorkingTensor_N = RegInit(0.U(ScaratchpadMaxTensorDimBitSize.W))
    val ScaratchpadWorkingTensor_K = RegInit(0.U(ScaratchpadMaxTensorDimBitSize.W))
    
    
    //矩阵乘的状态机
    //如果config是矩阵乘，那么就是矩阵乘的状态机
    when(state === s_idle){
        //TODO:是否要考虑 计算状态机的值？
        when(io.ConfigInfo.valid){
            when(io.ConfigInfo.bits.taskType === CUTETaskType.TaskTypeMatrixMul && io.ConfigInfo.bits.ComputeGo === true.B){
                //阶段0，配置信息就位，开始配置
                state := s_mm_task
                ScaratchpadWorkingTensor_M := io.ConfigInfo.bits.ScaratchpadTensor_M
                ScaratchpadWorkingTensor_N := io.ConfigInfo.bits.ScaratchpadTensor_N
                ScaratchpadWorkingTensor_K := io.ConfigInfo.bits.ScaratchpadTensor_K
                io.ConfigInfo.ready := true.B
                ScarchPadChosen := true.B
                //阶段0，让计算状态机开始初始化
                calculate_state := s_cal_init
                store_calculate_state := s_cal_init
            }
        }
    }

    //数据在CScarachpad中的编排
    //数据会先排N，再排M
    //   N 0 1 2 3 4 5 6 7     CScaratchpadData里的排布
    // M                               {bank    [0]         [1]}
    // 0   0 1 2 3 4 5 6 7   |addr    0 |    0123,89ab   ghij,opgr 
    // 1   8 9 a b c d e f   |        1 |    4567,cdef   klmn,stuv 
    // 2   g h i j k l m n   |        2 |    wxyz,!...   @...,#... 
    // 3   o p g r s t u v   |        3 |    ....,....   ....,....
    // 4   w x y z .......   |        4 |    ....,....   ....,.... 
    // 5   !..............   |        5 |    ....,....   ....,....
    // 6   @..............   |        6 |    ....,....   ....,....
    // 7   #..............   |        7 |    ....,....   ....,.... 
    // 8   $..............   | ....................................

    //矩阵乘的状态机，遍历所有数据就完事了
    //TODO:这里可以修改遍历顺序来节省带宽
    //首先Scaratchpad的数据有Tensor_M*Tensor_K个，每个数据是ReduceWidth位
    //然后我们要把这些数据送入TE，每次送入的数据是Matrix_M个，每个数据是Matrix_N*ReduceWidth位
    //我们的Scaratchpad是先排K再排M，所以我们的数据送入也是先送K再送M，每次送完一批K，重复Tensor_N/Matrix_N次，再切换M
    val M_Iterator = RegInit(0.U(ScaratchpadMaxTensorDimBitSize.W))
    val N_Iterator = RegInit(0.U(ScaratchpadMaxTensorDimBitSize.W))
    val K_Iterator = RegInit(0.U(ScaratchpadMaxTensorDimBitSize.W))

    val Store_M_Iterator = RegInit(0.U(ScaratchpadMaxTensorDimBitSize.W))
    val Store_N_Iterator = RegInit(0.U(ScaratchpadMaxTensorDimBitSize.W))
    // val Store_K_Iterator = RegInit(0.U(ScaratchpadMaxTensorDimBitSize.W))

    //TODO:这里是否需要一个这样的除法电路？实际上一个截断/移位电路就可以了？因为我们一定是整数倍的，多余的地方补0即可。
    //如果是除法电路，这里需要几拍？其实不会，Matrix_M一定是2的幂次，所有这个除法一定会被优化成移位，一定是一拍完成的，一定会优化成移位电路
    val M_IteratorMax = (ScaratchpadWorkingTensor_M / Matrix_M.U)
    val N_IteratorMax = (ScaratchpadWorkingTensor_N / Matrix_N.U)
    val K_IteratorMax = (ScaratchpadWorkingTensor_K)

        //数据会先排N，再排M
    //   N 0 1 2 3 4 5 6 7     CScaratchpadData里的排布
    // M                                              {bank    [0]         [1]}
    // 0   0 1 2 3 4 5 6 7   |(M_iter,N_iter)    (0,0) |    0123,89ab   ghij,opgr 
    // 1   8 9 a b c d e f   |                   (0,1) |    4567,cdef   klmn,stuv 
    // 2   g h i j k l m n   |                   (1,0) |    wxyz,!...   @...,#... 
    // 3   o p g r s t u v   |                   (1,1) |    ....,....   ....,....
    // 4   w x y z .......   |                   (2,0) |    ....,....   ....,.... 
    // 5   !..............   |                   (2,1) |    ....,....   ....,....
    // 6   @..............   |                   (3,0) |    ....,....   ....,....
    // 7   #..............   |                   (3,1) |    ....,....   ....,.... 
    // 8   $..............   | ..................................................

    val Max_Caculate_Iter = M_IteratorMax * N_IteratorMax
    val CVectorCount = RegInit(0.U(32.W))
    val DVectorCount = RegInit(0.U(32.W))
    //对Scaratchpad的数据请求
    val ReadRequest = WireInit(false.B)
    val WriteRequset = WireInit(false.B)

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

            //循环的最外层是M，然后是N
            val load_addr = Wire(UInt(CScratchpadBankNEntrys.W))
            val store_addr = RegInit(0.U(CScratchpadBankNEntrys.W))
            io.FromScarchPadIO.ReadBankAddr.valid := false.B
            
            load_addr := M_Iterator * N_IteratorMax + N_Iterator
            when(CVectorCount < Max_Caculate_Iter){
                //计算取数地址
                when(K_Iterator === 0.U)
                {
                    ReadRequest := true.B
                    io.FromScarchPadIO.ReadBankAddr.valid := true.B
                    io.FromScarchPadIO.ReadBankAddr.bits.map(_ := load_addr)
                }
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
                io.FromScarchPadIO.ReadBankAddr.valid := false.B
            }
            when(ScarchPadReadResponseData.valid){
                io.Matrix_C.valid := true.B
                io.Matrix_C.bits := ScarchPadReadResponseData.bits.asUInt
                CVectorCount := CVectorCount + 1.U
                printf("[CDataController]CDataController: CVectorCount is %d\n", CVectorCount)
            }

            when(io.ResultMatrix_D.valid){
                WriteRequset := true.B
                io.FromScarchPadIO.WriteBankAddr.valid := true.B
                io.FromScarchPadIO.WriteRequestData.valid := true.B
                io.FromScarchPadIO.WriteRequestData.bits := io.ResultMatrix_D.bits.asTypeOf(io.FromScarchPadIO.WriteRequestData.bits)
                io.ResultMatrix_D.ready := true.B
                io.FromScarchPadIO.WriteBankAddr.bits.map(_ := store_addr)
                store_addr := store_addr + 1.U
                DVectorCount := DVectorCount + 1.U
                printf("[CDataController]CDataController: DVectorCount is %d\n", DVectorCount)
                when(DVectorCount === Max_Caculate_Iter - 1.U){
                    calculate_state := s_cal_end
                }
            }
            
        }.elsewhen(calculate_state === s_cal_end){
            //计算结束，要么结束计算，要么切换ScarchPad
            io.CaculateEnd.valid := true.B
            io.CaculateEnd.bits := true.B
            printf("[CDataController]CDataController: CaculateEnd is valid\n")
            when(io.CaculateEnd.ready){
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
    
    //像Scrartchpad请求数据的汇总
    //使用各自的值进行拼接，最低位代表Read，次低位代表Write，最高位代表Memory的Write
    //叫给Scarchpad来进行仲裁
    //TODO:这里最好是拼一个正常的
    val request = Wire(new ScaratchpadTask)
    request.ReadFromMemoryLoader := false.B
    request.WriteFromMemoryLoader := false.B
    request.WriteFromDataController := Mux(ReadRequest===true.B,false.B,WriteRequset)
    request.ReadFromDataController := ReadRequest
    io.FromScarchPadIO.ReadWriteRequest := request.asUInt
    
}
