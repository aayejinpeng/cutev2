
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
        val ExternalReduceSize = Input(UInt(ScaratchpadMaxTensorDimBitSize.W))
    })
    //FIFOReady置高，所有寄存器向下流一个流水级
    //Chosen置高，该加法树工作被选择为工作加法树
    //working置高，在加法树工作中
    //完成加法树部分即可，ABC fire，且Ready置高，则DResult置valid
    
    //累加ExternalReduceSize次，完成一次计算，置DResult为valid

    //TODO:init
    io.AVector.ready := false.B
    io.BVector.ready := false.B
    io.CAdd.ready := false.B
    io.DResult.valid := false.B
    io.DResult.bits := DontCare
    io.working := false.B

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
        val ExternalReduceSize = Input(UInt(ScaratchpadMaxTensorDimBitSize.W))
    })
    io.AVector.ready := false.B
    io.BVector.ready := false.B
    io.CAdd.ready := false.B
    io.DResult.valid := false.B
    io.DResult.bits := DontCare
    io.working := false.B
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
        val ExternalReduceSize = Input(UInt(ScaratchpadMaxTensorDimBitSize.W))
    })
    io.AVector.ready := false.B
    io.BVector.ready := false.B
    io.CAdd.ready := false.B
    io.DResult.valid := false.B
    io.DResult.bits := DontCare
    io.working := false.B
}

//单个ReducePE, 计算Reduce乘累加的结果
class ReducePE extends Module with HWParameters{
    val io = IO(new Bundle{
        val ReduceA = Flipped(DecoupledIO(UInt(ReduceWidth.W)))
        val ReduceB = Flipped(DecoupledIO(UInt(ReduceWidth.W)))
        val AddC    = Flipped(DecoupledIO(UInt(ReduceWidth.W)))
        val ResultD = DecoupledIO(UInt(ResultWidth.W))
        val ConfigInfo = Flipped(DecoupledIO(new ConfigInfoIO))
        // val ExternalReduceSize = Flipped(DecoupledIO(UInt(ScaratchpadMaxTensorDimBitSize.W)))
    })

    //TODO:init
    io.ReduceA.ready := false.B
    io.ReduceB.ready := false.B
    io.AddC.ready := false.B
    io.ResultD.valid := false.B
    io.ResultD.bits := DontCare
    io.ConfigInfo.ready := false.B


    //ReducePE和MatrixTE需要一个对于externalReduce的处理，以提高热效率，提供主频，减少对CScratchPad的访问
    //ExternalReduce是指，我们的Scarchpad内的Tensor的K维大于1时，可以减少从CScratchPad的访问数据，让ReducePE使用自己暂存的累加结果后，再存至CScratchPad
    //Trick：再来，这里的K越大，我们的CSratchPad的平均访问次数就越少，就可以使用更慢更大的SRAM
    val ReduceMAC8 = Module(new ReduceMACTree8)
    ReduceMAC8.io.AVector <> io.ReduceA
    ReduceMAC8.io.BVector <> io.ReduceB
    ReduceMAC8.io.CAdd    <> io.AddC
    ReduceMAC8.io.Chosen  := false.B
    ReduceMAC8.io.FIFOReady   := false.B
    ReduceMAC8.io.ExternalReduceSize := Tensor_K.U

    val ReduceMAC16 = Module(new ReduceMACTree16)
    ReduceMAC16.io.AVector <> io.ReduceA
    ReduceMAC16.io.BVector <> io.ReduceB
    ReduceMAC16.io.CAdd    <> io.AddC
    ReduceMAC16.io.Chosen  := false.B
    ReduceMAC16.io.FIFOReady   := false.B
    ReduceMAC16.io.ExternalReduceSize := Tensor_K.U

    val ReduceMAC32 = Module(new ReduceMACTree32)
    ReduceMAC32.io.AVector <> io.ReduceA
    ReduceMAC32.io.BVector <> io.ReduceB
    ReduceMAC32.io.CAdd    <> io.AddC
    ReduceMAC32.io.Chosen  := false.B
    ReduceMAC32.io.FIFOReady   := false.B
    ReduceMAC32.io.ExternalReduceSize := Tensor_K.U

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
    CurrentResultD := DontCare
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

