
package boom.acc.cute

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import boom.exu.ygjk._
import boom.util._

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


    //TODO:这里就是有两个设计选项的
    //矩阵乘结果出来后，如果有逐元素的DSP部件，那就是npu的形状              ---> SOC上的NPU！        ～  ultra --> 不足的L3总带宽+不足的热功耗
    //如果矩阵乘结果出来后，如果没有逐元素的DSP部件，那就是矩阵乘部件的形状    ---> 通用多核/众核AI处理器 ～  dojo --> 充足的L3带宽+冗余的计算能力
    

    //但是这里的reorder部件是一定要有的，方便后续的数据编排和处理，让输入和输出的数据排布一致。
    //为什么在这里，因为我们的PE计算完后，在这里是第一次全逐个联线，所以这里是最合适的地方。



class CScarchPadIO extends Bundle with HWParameters{
    val FromDataController = new CDataControlScaratchpadIO
    val FromMemoryLoader = new CMemoryLoaderScaratchpadIO
    val DataControllerValid = Input(Bool())
    val MemoryLoaderValid = Input(Bool())
}

class CScratchpad extends Module with HWParameters{
    val io = IO(new Bundle{
        // val ConfigInfo = Flipped(DecoupledIO(new ConfigInfoIO))
        val ScarchPadIO = new CScarchPadIO
    })
    
    // //当前ScarchPad被选为工作ScarchPad
    // val DataControllerChosen = io.ScarchPadIO.FromDataController.Chosen
    // //当前ScarchPad的各个bank的请求地址
    // val DataControllerBankAddr = io.ScarchPadIO.FromDataController.ReadBankAddr.bits
    // //当前ScarchPad的返回的值
    // val DataControllerData = io.ScarchPadIO.FromDataController.ReadResponseData.bits

    // //Scaratchpad的被MemoryLoader选中
    // val MemoryLoaderChosen = io.ScarchPadIO.FromMemoryLoader.Chosen
    
    //根据读写请求的优先级，确定当前周期服务的是哪个请求
    val DataControllerReadWriteRequest = io.ScarchPadIO.FromDataController.ReadWriteRequest
    val MemoryControllerReadWriteRequest = io.ScarchPadIO.FromMemoryLoader.ReadWriteRequest
    val ReadWriteRequest = DataControllerReadWriteRequest | MemoryControllerReadWriteRequest //这里其实可以拼接

    //只选择一个请求，进行服务,先来个时间片轮转?或者来个检查，看谁的fifo最深。
    //这么看一个时间片轮转就还挺不错的，如果k=4，则只需要4个周期处理到一次DataContrller来的一次读和一次写就行了
    val FirstRequestIndex = RegInit(0.U(log2Ceil(ScaratchpadTaskType.TaskTypeBitWidth).W))
    FirstRequestIndex := WrapInc(FirstRequestIndex, ScaratchpadTaskType.TaskTypeBitWidth)
    //选择一个离FirstRequestIndex最近的请求
    val FirstIndex = FirstRequestIndex
    val SecIndex = WrapInc(FirstRequestIndex, ScaratchpadTaskType.TaskTypeBitWidth)
    val ThirdIndex = WrapInc(SecIndex, ScaratchpadTaskType.TaskTypeBitWidth)
    val FourthIndex = WrapInc(ThirdIndex, ScaratchpadTaskType.TaskTypeBitWidth)

    val HasRequest = ReadWriteRequest.andR
    //根据请求的优先级，确定当前周期服务的是哪个请求
    val ChoseIndex_0 = Mux(ReadWriteRequest(FirstIndex), FirstIndex,
                    Mux(ReadWriteRequest(SecIndex), SecIndex,
                    Mux(ReadWriteRequest(ThirdIndex), ThirdIndex,
                    Mux(ReadWriteRequest(FourthIndex), FourthIndex, 0.U))))
    
    val RequestResponse = VecInit(Seq.fill(ScaratchpadTaskType.TaskTypeBitWidth)(false.B))
    RequestResponse(ChoseIndex_0) := true.B
    
    //单个读写端口，只用选一个ChoseIndex_0，根据ChosenIndex，读写端口的信号
    //读写请求？
    //地址线？
    //数据线？
    // val SramAddr_0 = Wire(Vec(CScratchpadNBanks, (UInt(log2Ceil(CScratchpadBankNEntrys).W))))
    
    val ChoseOneHot_0 = UIntToOH(ChoseIndex_0)
    io.ScarchPadIO.FromDataController.ReadWriteResponse := ChoseOneHot_0
    io.ScarchPadIO.FromMemoryLoader.ReadWriteResponse := ChoseOneHot_0
    val SramAddr_0 = PriorityMux(ChoseOneHot_0, Seq(
        io.ScarchPadIO.FromDataController.ReadBankAddr.bits,
        io.ScarchPadIO.FromDataController.WriteBankAddr.bits,
        VecInit(Seq.fill(CScratchpadNBanks)(io.ScarchPadIO.FromMemoryLoader.ReadRequestToScarchPad.BankAddr.bits)),
        VecInit(Seq.fill(CScratchpadNBanks)(io.ScarchPadIO.FromMemoryLoader.WriteRequestToScarchPad.BankAddr.bits))
    ))

    val SramIsWrite_0 = Mux((ChoseIndex_0 === ScaratchpadTaskType.WriteFromDataControllerIndex.U) || (ChoseIndex_0 === ScaratchpadTaskType.WriteFromMemoryLoaderIndex.U), true.B, false.B) && HasRequest
    val SramIsRead_0  = !SramIsWrite_0 && HasRequest

    //TODO:这里的参数还是有问题的，我们得想明白为什么要分bank，分bank核心是为了让Slidingwindows可以取数，如果数据我们在送入ScarchPad前组织好的，就不需要分bank了
    //TODO:TODO:TODO:目前的问题在于FromMemoryLoader.WriteRequestToScarchPad.Data.bits的宽度
    //TODO:需要修改这个Vec，让他每次回数都只占用一个周期，这样性能才能好，需要在MemoryLoader中完成拼接才可以，这样送进来的就是和数据带宽一致的数据，没有带宽的浪费
    //这个用MUX写
    val SramWriteData_0 = PriorityMux(ChoseOneHot_0, Seq(
        io.ScarchPadIO.FromDataController.WriteRequestData.bits,
        io.ScarchPadIO.FromDataController.WriteRequestData.bits,
        VecInit(Seq.fill(CScratchpadNBanks)(0.U(CScratchpadEntrySize.W))),
        VecInit(Seq.fill(CScratchpadNBanks)(0.U(CScratchpadEntrySize.W)))
    ))
    
    
    
    //记录当前拍回数应该返回给哪条数据线
    val PreReadChosen_0 = RegNext(ChoseIndex_0)
    val PreIsRead_0 = RegNext(SramIsRead_0)

    //实例化多个sram为多个bank
    val sram_banks = (0 until CScratchpadNBanks) map { i =>

        //一个SeqMem就是一个SRAM，在一拍内完成读写，结果在下一拍输出，所以后头的代码里有s0，s1对不同阶段的流水数据进行分类，好区分每个周期的数据
        val bank = SyncReadMem(CScratchpadBankNEntrys, Bits(width = CScratchpadEntrySize.W))
        bank.suggestName("CUTE-C-Scratchpad-SRAM")
        
        //第0周期的数据
        val s0_bank_read_addr = SramAddr_0(i)
        val s0_bank_read_valid = SramIsRead_0 && HasRequest
        //第1周期的数据
        val s1_bank_read_data = bank.read(s0_bank_read_addr,s0_bank_read_valid).asUInt
        // val s1_bank_read_addr = RegEnable(s0_bank_read_addr, s0_bank_read_valid)
        // val s1_bank_read_valid = RegNext(s0_bank_read_valid)
        io.ScarchPadIO.FromDataController.ReadResponseData.bits(i) := s1_bank_read_data
        io.ScarchPadIO.FromDataController.ReadResponseData.valid := ((PreReadChosen_0 ===  ScaratchpadTaskType.ReadFromDataControllerIndex.U) && PreIsRead_0)
        io.ScarchPadIO.FromMemoryLoader.ReadRequestToScarchPad.ReadResponseData.bits(i) := s1_bank_read_data
        io.ScarchPadIO.FromMemoryLoader.ReadRequestToScarchPad.ReadResponseData.valid := (PreReadChosen_0 === ScaratchpadTaskType.ReadFromMemoryLoaderIndex.U && PreIsRead_0)
        //读取数据的fifo得在DataController里面自己实现，ScarchPad尽可能减少逻辑，符合SRAM的特性，所以上面的代码只有valid和data，没有ready
        
        //写数据
        val s0_bank_write_addr = Mux(SramIsWrite_0, SramAddr_0(i), 0.U)
        val s0_bank_write_data = Mux(SramIsWrite_0, SramWriteData_0(i), 0.U)
        val s0_bank_write_valid = SramIsWrite_0 && HasRequest
        when(s0_bank_write_valid){
            bank.write(s0_bank_write_addr, s0_bank_write_data)
        }

        bank
    }


}

