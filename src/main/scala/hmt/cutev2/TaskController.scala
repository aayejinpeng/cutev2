
package boom.acc.cute

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import boom.exu.ygjk._
import firrtl.passes.memlib.Config

//TaskController代表,
class TaskController extends Module with HWParameters{
    val io = IO(new Bundle{
        val ConfigInfo = DecoupledIO(new ConfigInfoIO)
    })


    //就测试一个矩阵乘
    io.ConfigInfo.bits.ApplicationTensor_A.ApplicationTensor_A_BaseVaddr := 0.U
    io.ConfigInfo.bits.ApplicationTensor_A.BlockTensor_A_BaseVaddr := 0.U
    io.ConfigInfo.bits.ApplicationTensor_A.MemoryOrder := MemoryOrderType.OrderType_Mb_Kb
    io.ConfigInfo.bits.ApplicationTensor_A.Conherent := true.B

    io.ConfigInfo.bits.ApplicationTensor_B.ApplicationTensor_B_BaseVaddr := 0.U
    io.ConfigInfo.bits.ApplicationTensor_B.BlockTensor_B_BaseVaddr := 0.U
    io.ConfigInfo.bits.ApplicationTensor_B.MemoryOrder := MemoryOrderType.OrderType_Nb_Kb
    io.ConfigInfo.bits.ApplicationTensor_B.Conherent := true.B

    io.ConfigInfo.bits.ApplicationTensor_C.ApplicationTensor_C_BaseVaddr := 0.U
    io.ConfigInfo.bits.ApplicationTensor_C.BlockTensor_C_BaseVaddr := 0.U
    io.ConfigInfo.bits.ApplicationTensor_C.MemoryOrder := MemoryOrderType.OrderType_Mb_Nb
    io.ConfigInfo.bits.ApplicationTensor_C.Conherent := true.B

    io.ConfigInfo.bits.ApplicationTensor_M := 64.U
    io.ConfigInfo.bits.ApplicationTensor_N := 64.U
    io.ConfigInfo.bits.ApplicationTensor_K := 64.U

    io.ConfigInfo.bits.ScaratchpadTensor_K := 64.U
    io.ConfigInfo.bits.ScaratchpadTensor_N := 64.U
    io.ConfigInfo.bits.ScaratchpadTensor_M := 64.U

    io.ConfigInfo.bits.taskType := CUTETaskType.TaskTypeMatrixMul
    io.ConfigInfo.bits.dataType := ElementDataType.DataTypeUInt8

    io.ConfigInfo.bits.CMemoryLoaderConfig.MemoryOrder := MemoryOrderType.OrderType_Mb_Nb
    io.ConfigInfo.bits.CMemoryLoaderConfig.TaskType := CMemoryLoaderTaskType.TaskTypeTensorLoad

    io.ConfigInfo.bits.ScaratchpadChosen.ADataControllerChosenIndex := 0.U
    io.ConfigInfo.bits.ScaratchpadChosen.BDataControllerChosenIndex := 0.U
    io.ConfigInfo.bits.ScaratchpadChosen.CDataControllerChosenIndex := 0.U

    io.ConfigInfo.bits.ScaratchpadChosen.AMemoryLoaderChosenIndex := 1.U
    io.ConfigInfo.bits.ScaratchpadChosen.BMemoryLoaderChosenIndex := 1.U
    io.ConfigInfo.bits.ScaratchpadChosen.CMemoryLoaderChosenIndex := 1.U

    io.ConfigInfo.valid := true.B
    


    
}
