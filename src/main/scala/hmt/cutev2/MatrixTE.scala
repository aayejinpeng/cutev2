
package boom.acc.cute

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import boom.exu.ygjk._

//MatrixTE
//该模块的设计目标是，外积模块，组织多个ReducePE，来复用矩阵乘的数据，MatrixTE接受ABScarchPad的数据，交给broadcaster，生成数据馈送至至ReducePE，将Reduce的输出数据准备馈送至ScarchPadC。
//计算上来看，它的输入是两个向量，将两个向量广播成两个相同大小的矩阵后，将元素送入ReducuPE。向量的大小也很明显其一是Matrix_M，其二是Matrix_N，向量内的元素宽度是Reduce_Width
class MatrixTE extends Module with HWParameters{
    val io = IO(new Bundle{
        val VectorA = Flipped(DecoupledIO(UInt((ReduceWidth*Matrix_N).W)))
        val VectorB = Flipped(DecoupledIO(UInt((ReduceWidth*Matrix_M).W)))
        val MatirxC = Flipped(DecoupledIO(UInt((ResultWidth*Matrix_M*Matrix_N).W)))
        val MatrixD = DecoupledIO(UInt(ResultWidth.W))
        val ConfigInfo = Flipped(DecoupledIO(new ConfigInfoIO))
    })

    // val ReduceMAC8 = Module(new ReduceMACTree8)
    // ReduceMAC8.io.AVector <> io.ReduceA
    // ReduceMAC8.io.BVector <> io.ReduceB
    // ReduceMAC8.io.CAdd    <> io.AddC
    // ReduceMAC8.io.Chosen  := false.B
    // ReduceMAC8.io.FIFOReady   := false.B

    // val ReduceMAC16 = Module(new ReduceMACTree16)
    // ReduceMAC16.io.AVector <> io.ReduceA
    // ReduceMAC16.io.BVector <> io.ReduceB
    // ReduceMAC16.io.CAdd    <> io.AddC
    // ReduceMAC16.io.Chosen  := false.B
    // ReduceMAC16.io.FIFOReady   := false.B

    // val ReduceMAC32 = Module(new ReduceMACTree32)
    // ReduceMAC32.io.AVector <> io.ReduceA
    // ReduceMAC32.io.BVector <> io.ReduceB
    // ReduceMAC32.io.CAdd    <> io.AddC
    // ReduceMAC32.io.Chosen  := false.B
    // ReduceMAC32.io.FIFOReady   := false.B

    // //PE不工作且FIFO为空时，才能接受新的配置信息
    // val PEWorking = RegInit(false.B)
    // PEWorking = ReduceMAC8.io.working || ReduceMAC16.io.working || ReduceMAC32.io.working
    // io.ConfigInfo.ready := !PEWorking && ResultFIFOEmpty
    // when(io.ConfigInfo.fire()){
    //   dataType := io.ConfigInfo.bits.dataType
    // }
    
    // //数据类型，整个计算过程中只有一个数据类型，ConfigInfo不会改变
    // val dataType = RegInit(ElementDataType.DataTypeUndef)

    // //根据数据类型选择不同的ReduceMAC,作为CurrentResultD的数据源，由于configinfo不会改变，所以这里的DResult不用改变，并设置Valid信号
    // val CurrentResultD = Wire(Valid(UInt(ResultWidth.W)))
    // when(dataType===ElementDataType.DataTypeUInt8){
    //     CurrentResultD <> ReduceMAC8.io.DResult
    //     RedcueMAC8.io.Chosen := true.B
    // }.elsewhen(dataType===ElementDataType.DataTypeUInt16){
    //     CurrentResultD <> ReduceMAC16.io.DResult
    //     ReduceMAC16.io.Chosen := true.B
    // }.elsewhen(dataType===ElementDataType.DataTypeUInt32){
    //     CurrentResultD <> ReduceMAC32.io.DResult
    //     ReduceMAC32.io.Chosen := true.B
    // }.otherwise{
    //     CurrentResultD.valid := false.B
    // }

    
    // //只有在数据类型匹配时才能进行计算
    // //在Reduce内完成数据的握手，及所有数据准备好后才能进行计算，并用一个fifo保存ResultD，等待ResultD被握手
    // val ResultFIFO = RegInit(VecInit(Seq.fill(ResultFIFODepth)(0.U(ResultWidth.W))))
    // val ResultFIFOHead = RegInit(0.U(log2Ceil(ResultFIFODepth).W))
    // val ResultFIFOTail = RegInit(0.U(log2Ceil(ResultFIFODepth).W))
    // val ResultFIFOFull = ResultFIFOHead === (ResultFIFOTail + 1.U)(log2Ceil(ResultFIFODepth).W-1, 0)
    // val ResultFIFOEmpty = ResultFIFOHead === ResultFIFOTail
    // val ResultFIFOValid = RegInit(false.B)

    // when(CurrentResultD.valid){
    //   when(!ResultFIFOFull){
    //     ResultFIFO(ResultFIFOTail) := CurrentResultD.bits
    //     when(ResultFIFOTail+1.U===ResultFIFODepth.U){
    //       ResultFIFOTail := 0.U
    //     }.otherwise{
    //       ResultFIFOTail := ResultFIFOTail + 1.U
    //     }
    //   }.otherwise{
    //     // printf(p"ResultFIFOFull\n")
    //   }
    // }

    // when(io.ResultD.fire()){
    //   when(ResultFIFOEmpty){
    //     ResultFIFOValid := false.B
    //   }.otherwise{
    //     io.ResultD.bits := ResultFIFO(ResultFIFOHead)
    //     ResultFIFOValid := true.B
    //     when(ResultFIFOHead+1.U===ResultFIFODepth.U){
    //       ResultFIFOHead := 0.U
    //     }.otherwise{
    //       ResultFIFOHead := ResultFIFOHead + 1.U
    //     }
    //   }
    // }

    // //数据源ReduceA ReduceB AddC什么时候能置ready？
    // //全部valid的时候才可以，同时当前流水下的所有数据都能在fifo中存的下，才能置ready
    // //方案1:已知MACTree的流水线深度，已知ResultFIFO的深度，可以得出ResultFIFO存的数据达到某个深度时，可以安全的接受新的数据
    // //方案2：直接用FIFO满没满确定是否ready，整体流水线都受这个制约，好像有点粗暴？只要ready，所有数据往下流一个流水级，否则不动
    // val InputReady = ResultFIFOFull===false.B
    // io.ReduceA.ready := InputReady
    // io.ReduceB.ready := InputReady
    // io.AddC.ready    := InputReady

    // //什么时候能接让MacTree的数据输入到fifo？
    // //MacTree的数据输入到fifo的时候，fifo不满，且MacTree的数据有效
    // val MacTreeReady = ResultFIFOFull===false.B
    // ReduceMAC8.io.FIFOReady := MacTreeReady
    // ReduceMAC16.io.FIFOReady := MacTreeReady
    // ReduceMAC32.io.FIFOReady := MacTreeReady

    // //输出的ResultD什么时候能置valid？
    // //ResultFIFO不为空时，才能置valid
    // io.ResultD.valid := ResultFIFOValid

}

