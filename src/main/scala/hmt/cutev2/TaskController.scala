
package boom.acc.cute

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import boom.exu.ygjk._
import firrtl.passes.memlib.Config

//TaskController代表,
class TaskController(implicit p: Parameters) extends Module with HWParameters{
    val io = IO(new Bundle{
        val ygjkctrl = Flipped(new YGJKControl)
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

    


    //100000000个周期以后调换一次
    val test_count = RegInit(0.U(32.W))
    test_count := test_count + 1.U
    val ctc = RegInit(0.U(1.W))
    when(test_count === 100000000.U){
        ctc := ~ctc
    }
    when(test_count === 100000000.U){
        test_count := 0.U
    }
    io.ConfigInfo.bits.ScaratchpadChosen.ADataControllerChosenIndex := ctc
    io.ConfigInfo.bits.ScaratchpadChosen.BDataControllerChosenIndex := ctc
    io.ConfigInfo.bits.ScaratchpadChosen.CDataControllerChosenIndex := ctc

    io.ConfigInfo.bits.ScaratchpadChosen.AMemoryLoaderChosenIndex := ~ctc
    io.ConfigInfo.bits.ScaratchpadChosen.BMemoryLoaderChosenIndex := ~ctc
    io.ConfigInfo.bits.ScaratchpadChosen.CMemoryLoaderChosenIndex := ~ctc

    io.ConfigInfo.bits.MMUConfig.refillVaddr := io.ygjkctrl.config.bits.cfgData1
    io.ConfigInfo.bits.MMUConfig.refillPaddr := io.ygjkctrl.config.bits.cfgData2
    io.ConfigInfo.bits.MMUConfig.refill_v := io.ygjkctrl.config.valid
    io.ConfigInfo.bits.MMUConfig.useVM := ctc
    io.ConfigInfo.bits.MMUConfig.useVM_v := ctc

    io.ConfigInfo.bits.CMemoryLoaderConfig.MemoryOrder := MemoryOrderType.OrderType_Mb_Nb
    io.ConfigInfo.bits.CMemoryLoaderConfig.TaskType := CMemoryLoaderTaskType.TaskTypeTensorLoad
    when(ctc === 0.U){
        io.ConfigInfo.bits.CMemoryLoaderConfig.TaskType := CMemoryLoaderTaskType.TaskTypeTensorStore
    }

    io.ConfigInfo.valid := true.B

    val acc_run = RegInit(false.B)
    when(io.ygjkctrl.config.valid){
        acc_run := true.B
    }
    io.ygjkctrl.acc_running := acc_run
    


    
}
