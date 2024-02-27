
package boom.acc.cute

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import boom.exu.ygjk._

class ReduceMACTree8 extends Module with HWParameters{
    val io = IO(new Bundle{
        val AVector = Flipped(DecoupledIO(UInt(ReduceWidth.W)))
        val BVector = Flipped(DecoupledIO(UInt(ReduceWidth.W)))
        val CAdd    = Flipped(DecoupledIO(UInt(ResultWidth.W)))
        val DResult = Valid(UInt(ResultWidth.W))
        val Chosen = Input(Bool())
        val FIFOReady = Input(Bool())
        val working = Output(Bool())
    })
    //FIFOReady置高，所有寄存器向下流一个流水级
    //Chosen置高，该加法树工作被选择为工作加法树
    //working置高，在加法树工作中
    //完成加法树部分即可，ABC fire，且Ready置高，则DResult置valid
}

class ReduceMACTree16 extends Module with HWParameters{
    val io = IO(new Bundle{
        val AVector = Flipped(DecoupledIO(UInt(ReduceWidth.W)))
        val BVector = Flipped(DecoupledIO(UInt(ReduceWidth.W)))
        val CAdd    = Flipped(DecoupledIO(UInt(ResultWidth.W)))
        val DResult = Valid(UInt(ResultWidth.W))
        val Chosen = Input(Bool())
        val FIFOReady = Input(Bool())
        val working = Output(Bool())
    })

}

class ReduceMACTree32 extends Module with HWParameters{
    val io = IO(new Bundle{
        val AVector = Flipped(DecoupledIO(UInt(ReduceWidth.W)))
        val BVector = Flipped(DecoupledIO(UInt(ReduceWidth.W)))
        val CAdd    = Flipped(DecoupledIO(UInt(ResultWidth.W)))
        val DResult = Valid(UInt(ResultWidth.W))
        val Chosen = Input(Bool())
        val FIFOReady = Input(Bool())
        val working = Output(Bool())
    })

}

//单个ReducePE, 计算Reduce乘累加的结果
class ReducePE extends Module with HWParameters{
    val io = IO(new Bundle{
        val ReduceA = Flipped(DecoupledIO(UInt(ReduceWidth.W)))
        val ReduceB = Flipped(DecoupledIO(UInt(ReduceWidth.W)))
        val AddC    = Flipped(DecoupledIO(UInt(ReduceWidth.W)))
        val ResultD = DecoupledIO(UInt(ResultWidth.W))
        val ConfigInfo = Flipped(DecoupledIO(new ConfigInfoIO))
    })

    val ReduceMAC8 = Module(new ReduceMACTree8)
    ReduceMAC8.io.AVector <> io.ReduceA
    ReduceMAC8.io.BVector <> io.ReduceB
    ReduceMAC8.io.CAdd    <> io.AddC
    ReduceMAC8.io.Chosen  := false.B
    ReduceMAC8.io.FIFOReady   := false.B

    val ReduceMAC16 = Module(new ReduceMACTree16)
    ReduceMAC16.io.AVector <> io.ReduceA
    ReduceMAC16.io.BVector <> io.ReduceB
    ReduceMAC16.io.CAdd    <> io.AddC
    ReduceMAC16.io.Chosen  := false.B
    ReduceMAC16.io.FIFOReady   := false.B

    val ReduceMAC32 = Module(new ReduceMACTree32)
    ReduceMAC32.io.AVector <> io.ReduceA
    ReduceMAC32.io.BVector <> io.ReduceB
    ReduceMAC32.io.CAdd    <> io.AddC
    ReduceMAC32.io.Chosen  := false.B
    ReduceMAC32.io.FIFOReady   := false.B

    //PE不工作且FIFO为空时，才能接受新的配置信息
    val PEWorking = RegInit(false.B)
    PEWorking = ReduceMAC8.io.working || ReduceMAC16.io.working || ReduceMAC32.io.working
    io.ConfigInfo.ready := !PEWorking && ResultFIFOEmpty
    when(io.ConfigInfo.fire()){
      dataType := io.ConfigInfo.bits.dataType
    }
    
    //数据类型，整个计算过程中只有一个数据类型，ConfigInfo不会改变
    val dataType = RegInit(ElementDataType.DataTypeUndef)
    
    //根据数据类型选择不同的ReduceMAC,作为CurrentResultD的数据源，由于configinfo不会改变，所以这里的DResult不用改变，并设置Valid信号
    val CurrentResultD = Wire(Valid(UInt(ResultWidth.W)))
    when(dataType===ElementDataType.DataTypeUInt8){
        CurrentResultD <> ReduceMAC8.io.DResult
        RedcueMAC8.io.Chosen := true.B
    }.elsewhen(dataType===ElementDataType.DataTypeUInt16){
        CurrentResultD <> ReduceMAC16.io.DResult
        ReduceMAC16.io.Chosen := true.B
    }.elsewhen(dataType===ElementDataType.DataTypeUInt32){
        CurrentResultD <> ReduceMAC32.io.DResult
        ReduceMAC32.io.Chosen := true.B
    }.otherwise{
        CurrentResultD.valid := false.B
    }

    
    //只有在数据类型匹配时才能进行计算
    //在Reduce内完成数据的握手，及所有数据准备好后才能进行计算，并用一个fifo保存ResultD，等待ResultD被握手
    val ResultFIFO = RegInit(VecInit(Seq.fill(ResultFIFODepth)(0.U(ResultWidth.W))))
    val ResultFIFOHead = RegInit(0.U(log2Ceil(ResultFIFODepth).W))
    val ResultFIFOTail = RegInit(0.U(log2Ceil(ResultFIFODepth).W))
    val ResultFIFOFull = ResultFIFOHead === (ResultFIFOTail + 1.U)(log2Ceil(ResultFIFODepth).W-1, 0)
    val ResultFIFOEmpty = ResultFIFOHead === ResultFIFOTail
    val ResultFIFOValid = RegInit(false.B)

    when(CurrentResultD.valid){
      when(!ResultFIFOFull){
        ResultFIFO(ResultFIFOTail) := CurrentResultD.bits
        when(ResultFIFOTail+1.U===ResultFIFODepth.U){
          ResultFIFOTail := 0.U
        }.otherwise{
          ResultFIFOTail := ResultFIFOTail + 1.U
        }
      }.otherwise{
        // printf(p"ResultFIFOFull\n")
      }
    }

    when(io.ResultD.fire()){
      when(ResultFIFOEmpty){
        ResultFIFOValid := false.B
      }.otherwise{
        io.ResultD.bits := ResultFIFO(ResultFIFOHead)
        ResultFIFOValid := true.B
        when(ResultFIFOHead+1.U===ResultFIFODepth.U){
          ResultFIFOHead := 0.U
        }.otherwise{
          ResultFIFOHead := ResultFIFOHead + 1.U
        }
      }
    }

    //数据源ReduceA ReduceB AddC什么时候能置ready？
    //全部valid的时候才可以，同时当前流水下的所有数据都能在fifo中存的下，才能置ready
    //方案1:已知MACTree的流水线深度，已知ResultFIFO的深度，可以得出ResultFIFO存的数据达到某个深度时，可以安全的接受新的数据
    //方案2：直接用FIFO满没满确定是否ready，整体流水线都受这个制约，好像有点粗暴？只要ready，所有数据往下流一个流水级，否则不动
    val InputReady = ResultFIFOFull===false.B
    io.ReduceA.ready := InputReady
    io.ReduceB.ready := InputReady
    io.AddC.ready    := InputReady

    //什么时候能接让MacTree的数据输入到fifo？
    //MacTree的数据输入到fifo的时候，fifo不满，且MacTree的数据有效
    val MacTreeReady = ResultFIFOFull===false.B
    ReduceMAC8.io.FIFOReady := MacTreeReady
    ReduceMAC16.io.FIFOReady := MacTreeReady
    ReduceMAC32.io.FIFOReady := MacTreeReady

    //输出的ResultD什么时候能置valid？
    //ResultFIFO不为空时，才能置valid
    io.ResultD.valid := ResultFIFOValid

    // //PE矩阵,PEHigh*PEWidth个MAC,这个其实是单个TE的配置
    // val matrix32 = VecInit.tabulate(PEHigh, PEWidth){(x,y) => Module(new MAC32).io}
    // val matrix16 = VecInit.tabulate(PEHigh, PEWidth){(x,y) => Module(new MAC16).io}
    // val matrix8 = VecInit.tabulate(PEHigh, PEWidth){(x,y) => Module(new MAC8).io}

    // //结果队列
    // val resq = RegInit(VecInit.tabulate(MACresq, PEHigh*PEWidth){(a,b) => 0.U(ALineDWidth.W)})
    // val resq_vn = RegInit(0.U(log2Ceil(MACresq).W))
    // val reshead = RegInit(0.U(log2Ceil(MACresq).W))
    // val restail = RegInit(0.U(log2Ceil(MACresq).W))
    // val resqFull = reshead===(restail+1.U)(log2Ceil(MACresq)-1, 0)
    
    // //要保证AB数据同时握手
    // io.A2PE.ready := Mux(dataType===1.U,matrix32(0)(0).Ain.ready && resq_vn < (MACresq-MAC32Latency).U && io.B2PE.valid, 
    //                  Mux(dataType===2.U,matrix16(0)(0).Ain.ready && resq_vn < (MACresq-MAC16Latency).U &&  io.B2PE.valid, 
    //                                     matrix8(0)(0).Ain.ready && resq_vn < (MACresq-MAC8Latency).U &&  io.B2PE.valid))   
    // io.B2PE.ready := Mux(dataType===1.U,matrix32(0)(0).Bin.ready && resq_vn < (MACresq-MAC32Latency).U && io.A2PE.valid, 
    //                  Mux(dataType===2.U,matrix16(0)(0).Bin.ready && resq_vn < (MACresq-MAC16Latency).U &&  io.A2PE.valid, 
    //                                     matrix8(0)(0).Bin.ready && resq_vn < (MACresq-MAC8Latency).U &&  io.A2PE.valid))  
    // for{
    //   i <- 0 until PEHigh
    //   j <- 0 until PEWidth
    // } yield {
    //   matrix32(i)(j).icb := io.icb
    //   matrix32(i)(j).Ain.valid := io.A2PE.fire() && dataType===1.U
    //   matrix32(i)(j).Ain.bits := io.A2PE.bits(i)
    //   matrix32(i)(j).Bin.valid := io.B2PE.fire() && dataType===1.U
    //   matrix32(i)(j).Bin.bits := io.B2PE.bits(j)
    //   matrix32(i)(j).Cout.ready := !resqFull  && dataType===1.U

    //   matrix16(i)(j).icb := io.icb
    //   matrix16(i)(j).Ain.valid := io.A2PE.fire() && dataType===2.U
    //   matrix16(i)(j).Ain.bits := io.A2PE.bits(i)
    //   matrix16(i)(j).Bin.valid := io.B2PE.fire() && dataType===2.U
    //   matrix16(i)(j).Bin.bits := Cat(io.B2PE.bits(j/2)((j&1)*16+15,(j&1)*16), io.B2PE.bits(j/2+2)((j&1)*16+15,(j&1)*16))
    //   matrix16(i)(j).Cout.ready := !resqFull  && dataType===2.U

    //   matrix8(i)(j).icb := io.icb
    //   matrix8(i)(j).Ain.valid := io.A2PE.fire() && dataType===4.U
    //   matrix8(i)(j).Ain.bits := io.A2PE.bits(i)
    //   matrix8(i)(j).Bin.valid := io.B2PE.fire() && dataType===4.U
    //   matrix8(i)(j).Bin.bits := Cat(Cat(io.B2PE.bits(0)(j*8+7, j*8), io.B2PE.bits(1)(j*8+7, j*8)), 
    //                                 Cat(io.B2PE.bits(2)(j*8+7, j*8), io.B2PE.bits(3)(j*8+7, j*8)))
    //   matrix8(i)(j).Cout.ready := !resqFull  && dataType===4.U
    // }

    // when(matrix32(0)(0).Cout.fire() || matrix16(0)(0).Cout.fire() || matrix8(0)(0).Cout.fire()){
    //   for{
    //     i <- 0 until PEHigh
    //     j <- 0 until PEWidth
    //   } yield {
    //     resq(restail)(i*PEWidth+j) := Mux(dataType===1.U, matrix32(i)(j).Cout.bits,
    //                                   Mux(dataType===2.U, matrix16(i)(j).Cout.bits, matrix8(i)(j).Cout.bits)) 
    //   }
    //   when(restail+1.U===MACresq.U){
    //     restail := 0.U
    //   }.otherwise{
    //     restail := restail + 1.U
    //   }
    // }

    // io.PE2C.valid := restail =/= reshead
    // for{
    //     i <- 0 until PEHigh
    //     j <- 0 until PEWidth
    // } yield {
    //     io.PE2C.bits(i)(j) := resq(reshead)(i*PEWidth+j) 
    // }
    // when(io.PE2C.fire()){
    //   when(reshead+1.U===MACresq.U){
    //     reshead := 0.U
    //   }.otherwise{
    //     reshead := reshead + 1.U
    //   }
    // }

    // when((matrix32(0)(0).Cout.fire() || matrix16(0)(0).Cout.fire() || matrix8(0)(0).Cout.fire()) && !io.PE2C.fire()){
    //   resq_vn := resq_vn + 1.U
    // }.elsewhen(!(matrix32(0)(0).Cout.fire() || matrix16(0)(0).Cout.fire() || matrix8(0)(0).Cout.fire()) && io.PE2C.fire()){
    //   resq_vn := resq_vn - 1.U
    // }

}

