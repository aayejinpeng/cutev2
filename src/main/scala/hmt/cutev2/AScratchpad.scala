
package boom.acc.cute

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import boom.exu.ygjk._

//AScratchpad，用于暂存A矩阵的数据，供给TE模块使用
//A矩阵在DataController看来是一个只读的矩阵
//A矩阵需要支持滑动窗口，分Matrix_M个bank是合理的
//Scarchpad的功能是，根据输入的地址，输出数据

class AScarchPadIO extends Bundle with HWParameters{
    val FromDataController = new ADataControlScaratchpadIO
    val FromMemoryLoader = new AMemoryLoaderScaratchpadIO 
}

class AScratchpad extends Module with HWParameters{
    val io = IO(new Bundle{
        val ConfigInfo = Flipped(DecoupledIO(new ConfigInfoIO))
        val ScarchPadIO = new AScarchPadIO
    })

    //当前ScarchPad被选为工作ScarchPad
    val DataControllerChosen = io.ScarchPadIO.FromDataController.Chosen
    //当前ScarchPad的各个bank的请求地址
    val DataControllerBankAddr = io.ScarchPadIO.FromDataController.BankAddr.bits
    //当前ScarchPad的返回的值
    val DataControllerData = io.ScarchPadIO.FromDataController.Data.bits

    //Scaratchpad的被MemoryLoader选中
    val MemoryLoaderChosen = io.ScarchPadIO.FromMemoryLoader.Chosen
    //MemoryLoader的请求地址
    val MemoryLoaderBankAddr = io.ScarchPadIO.FromMemoryLoader.BankAddr.bits
    //MemoryLoader的请求数据
    val MemoryLoaderData = io.ScarchPadIO.FromMemoryLoader.Data.bits
    
    //TODO:fifoready?
    val read_ready = io.ScarchPadIO.FromDataController.BankAddr.valid && !io.ScarchPadIO.FromMemoryLoader.BankAddr.valid && DataControllerChosen
    val write_ready = io.ScarchPadIO.FromMemoryLoader.BankAddr.valid && !io.ScarchPadIO.FromDataController.BankAddr.valid && MemoryLoaderChosen && io.ScarchPadIO.FromMemoryLoader.Data.valid
    //为输入信号赋ready
    io.ScarchPadIO.FromDataController.BankAddr.ready := read_ready
    io.ScarchPadIO.FromMemoryLoader.BankAddr.ready := write_ready
    //SRAM下一拍的返回结果，所以使用上一拍的ready作为valid
    io.ScarchPadIO.FromDataController.Data.valid := RegNext(read_ready)
    //实例化多个sram为多个bank
    val sram_banks = (0 until AScratchpadNBanks) map { i =>

        //一个SeqMem就是一个SRAM，在一拍内完成读写，结果在下一拍输出，所以后头的代码里有s0，s1对不同阶段的流水数据进行分类，好区分每个周期的数据
        val bank = SyncReadMem(AScratchpadBankNEntrys, Bits(width = ReduceWidth.W))
        bank.suggestName("CUTE-A-Scratchpad-SRAM")
        
        //第0周期的数据
        val s0_bank_read_addr = DataControllerBankAddr(i)
        val s0_bank_read_valid = read_ready
        //第1周期的数据
        val s1_bank_read_data = bank.read(s0_bank_read_addr,s0_bank_read_valid).asUInt
        // val s1_bank_read_addr = RegEnable(s0_bank_read_addr, s0_bank_read_valid)
        // val s1_bank_read_valid = RegNext(s0_bank_read_valid)
        DataControllerData(i) := s1_bank_read_data
        //读取数据的fifo得在DataController里面自己实现，ScarchPad尽可能减少逻辑，符合SRAM的特性，所以上面的代码只有valid和data，没有ready
        
        //写数据
        val s0_bank_write_addr = MemoryLoaderBankAddr(i)
        val s0_bank_write_data = MemoryLoaderData(i)
        bank.write(s0_bank_write_addr, s0_bank_write_data)

        bank
    }


}
