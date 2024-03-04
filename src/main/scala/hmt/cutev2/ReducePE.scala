
package boom.acc.cute

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import boom.exu.ygjk._
import boom.util._

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

    //只有在数据类型匹配时才能进行计算
    //在Reduce内完成数据的握手，及所有数据准备好后才能进行计算，并用一个fifo保存ResultD，等待ResultD被握手
    val ResultFIFO = RegInit(VecInit(Seq.fill(ResultFIFODepth)(0.U(ResultWidth.W))))
    val ResultFIFOHead = RegInit(0.U(log2Ceil(ResultFIFODepth).W))
    val ResultFIFOTail = RegInit(0.U(log2Ceil(ResultFIFODepth).W))
    val ResultFIFOFull = ResultFIFOHead === WrapInc(ResultFIFOTail, ResultFIFODepth)
    val ResultFIFOEmpty = ResultFIFOHead === ResultFIFOTail
    val ResultFIFOValid = RegInit(false.B)

    //数据类型，整个计算过程中只有一个数据类型，ConfigInfo不会改变
    val dataType = RegInit(ElementDataType.DataTypeUndef)
    //PE不工作且FIFO为空时，才能接受新的配置信息
    val PEWorking = ReduceMAC8.io.working || ReduceMAC16.io.working || ReduceMAC32.io.working
    io.ConfigInfo.ready := !PEWorking && ResultFIFOEmpty
    when(io.ConfigInfo.fire){
      dataType := io.ConfigInfo.bits.dataType
    }
    


    //根据数据类型选择不同的ReduceMAC,作为CurrentResultD的数据源，由于configinfo不会改变，所以这里的DResult不用改变，并设置Valid信号
    val CurrentResultD = Wire(Valid(UInt(ResultWidth.W)))
    when(dataType===ElementDataType.DataTypeUInt8){
        CurrentResultD <> ReduceMAC8.io.DResult
        ReduceMAC8.io.Chosen := true.B
    }.elsewhen(dataType===ElementDataType.DataTypeUInt16){
        CurrentResultD <> ReduceMAC16.io.DResult
        ReduceMAC16.io.Chosen := true.B
    }.elsewhen(dataType===ElementDataType.DataTypeUInt32){
        CurrentResultD <> ReduceMAC32.io.DResult
        ReduceMAC32.io.Chosen := true.B
    }.otherwise{
        CurrentResultD.valid := false.B
    }

    


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

    when(io.ResultD.fire){
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

}

