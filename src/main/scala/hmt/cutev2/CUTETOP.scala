
package boom.acc.cute

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import boom.exu.ygjk._
import scala.collection.parallel.Task


class MMacc extends MyACCModule with HWParameters with YGJKParameters{
    
    // //ocPENum个TE
    // val PEs = VecInit(Seq.fill(ocPENum)(Module(new PE).io))
    // //每个TE对应一个Abuffer
    // val Abuffer = VecInit(Seq.fill(ocPENum)(Module(new PEAbuffer).io))
    // //一份Abroadcast和一份Ascratchpad
    // val Abroadcast = Module(new A2PEbroadcast).io
    // val AScratchpad = Module(new AScratchpad).io
    // //每个TE对应一个Bbuffer、Bbroadcast和BScratchpad，没有B的广播
    // val Bbuffer = VecInit(Seq.fill(ocPENum)(Module(new PEBbuffer).io))
    // val Bbroadcast = VecInit(Seq.fill(ocPENum)(Module(new B2PEbroadcast).io))
    // val BScratchpad = VecInit(Seq.fill(ocPENum)(Module(new BScratchpad).io))
    // //每个TE对应一个Cbuffer和C2PE、CScratchpad
    // val Cbuffer = VecInit(Seq.fill(ocPENum)(Module(new Cbuffer).io))
    // val C2PE = VecInit(Seq.fill(ocPENum)(Module(new C2PE).io))
    // val CScratchpad = VecInit(Seq.fill(ocPENum)(Module(new CScratchpad).io))

    //TODO:init DontCare很危险
    io.cmd.acc_req_a.bits := DontCare
    io.cmd.acc_req_a.valid := DontCare
    io.cmd.acc_req_b.bits := DontCare
    io.cmd.acc_req_b.valid := DontCare
    io.cmd.req_id := DontCare
    io.ctl.acc_running := DontCare


    val ASPad_0 = Module(new AScratchpad).io //double buffer
    val ASPad_1 = Module(new AScratchpad).io //double buffer
    val ADC = Module(new ADataController).io
    val AML = Module(new AMemoryLoader).io

    val BSPad_0 = Module(new BScratchpad).io
    val BSPad_1 = Module(new BScratchpad).io
    val BDC = Module(new BDataController).io
    val BML = Module(new BMemoryLoader).io

    val CSPad_0 = Module(new CScratchpad).io
    val CSPad_1 = Module(new CScratchpad).io
    val CDC = Module(new CDataController).io
    val CML = Module(new CMemoryLoader).io

    val TaskCtrl = Module(new TaskController).io
    
    val MTE = Module(new MatrixTE).io

    MTE.VectorA <> ADC.VectorA
    MTE.VectorB <> BDC.VectorB
    MTE.MatirxC <> CDC.Matrix_C
    MTE.MatrixD <> CDC.ResultMatrix_D
    MTE.ConfigInfo.bits := TaskCtrl.ConfigInfo.bits
    MTE.ConfigInfo.valid := TaskCtrl.ConfigInfo.valid

    
    
    //ADC能不能切换ScarchPad，要协商的东西还挺多的。
    ASPad_0.ScarchPadIO.FromDataController <> ADC.FromScarchPadIO
    ASPad_1.ScarchPadIO.FromDataController <> ADC.FromScarchPadIO
    when(TaskCtrl.ConfigInfo.bits.ScaratchpadChosen.ADataControllerChosenIndex === 0.U)
    {
        ASPad_0.ScarchPadIO.DataControllerValid := true.B
        ASPad_1.ScarchPadIO.DataControllerValid := false.B
    }.otherwise{
        ASPad_0.ScarchPadIO.DataControllerValid := false.B
        ASPad_1.ScarchPadIO.DataControllerValid := true.B
    }
    ADC.FromScarchPadIO <> ASPad_0.ScarchPadIO.FromDataController
    // ADC.FromScarchPadIO <> Mux(, ASPad_0.ScarchPadIO.FromDataController, ASPad_1.ScarchPadIO.FromDataController)
    ADC.ConfigInfo.bits := TaskCtrl.ConfigInfo.bits
    ADC.ConfigInfo.valid := TaskCtrl.ConfigInfo.valid
    ADC.SwitchScarchPad.ready := true.B //实际上要看具体的另外一个ScarchPad的memoryloader的任务是否完成了。//TODO:
    ADC.TaskEnd.bits := true.B //TODO:
    ADC.TaskEnd.valid := true.B //TODO:

    // ASPad.ScarchPadIO.FromMemoryLoader <> AML.ToScarchPadIO
    // ASPad.ScarchPadIO.FromDataController <> ADC.FromScarchPadIO

    // AML.ToScarchPadIO <> Mux(TaskCtrl.ConfigInfo.bits.ScaratchpadChosen.AMemoryLoaderChosenIndex === 0.U, ASPad_0.ScarchPadIO.FromMemoryLoader, ASPad_1.ScarchPadIO.FromMemoryLoader)
    // val ASPadDefaultInvaild = Wire(new AScarchPadIO)

    ASPad_0.ScarchPadIO.FromMemoryLoader <> AML.ToScarchPadIO
    ASPad_1.ScarchPadIO.FromMemoryLoader <> AML.ToScarchPadIO
    when(TaskCtrl.ConfigInfo.bits.ScaratchpadChosen.AMemoryLoaderChosenIndex === 0.U)
    {
        //TODO:改ScarchPad内的处理逻辑。
        ASPad_0.ScarchPadIO.MemoryLoaderValid := true.B
        ASPad_1.ScarchPadIO.MemoryLoaderValid := false.B
    }.otherwise{
        ASPad_0.ScarchPadIO.MemoryLoaderValid := false.B
        ASPad_1.ScarchPadIO.MemoryLoaderValid := true.B
    }
    AML.ConfigInfo.bits := TaskCtrl.ConfigInfo.bits
    AML.ConfigInfo.valid := TaskCtrl.ConfigInfo.valid
    AML.TaskEnd.bits := true.B //TODO:
    AML.TaskEnd.valid := true.B //TODO:
    AML.MemoryLoadEnd.ready := true.B //TODO:
    
    // BDC.FromScarchPadIO <> Mux(TaskCtrl.ConfigInfo.bits.ScaratchpadChosen.BDataControllerChosenIndex === 0.U, BSPad_0.ScarchPadIO.FromDataController, BSPad_1.ScarchPadIO.FromDataController)
    BSPad_0.ScarchPadIO.FromDataController <> BDC.FromScarchPadIO
    BSPad_1.ScarchPadIO.FromDataController <> BDC.FromScarchPadIO
    when(TaskCtrl.ConfigInfo.bits.ScaratchpadChosen.BDataControllerChosenIndex === 0.U)
    {
        BSPad_0.ScarchPadIO.DataControllerValid := true.B
        BSPad_1.ScarchPadIO.DataControllerValid := false.B
    }.otherwise{
        BSPad_0.ScarchPadIO.DataControllerValid := false.B
        BSPad_1.ScarchPadIO.DataControllerValid := true.B
    }
    BDC.ConfigInfo.bits := TaskCtrl.ConfigInfo.bits
    BDC.ConfigInfo.valid := TaskCtrl.ConfigInfo.valid
    BDC.SwitchScarchPad.ready := true.B //实际上要看具体的另外一个ScarchPad的memoryloader的任务是否完成了。//TODO:
    BDC.TaskEnd.bits := true.B //TODO:
    BDC.TaskEnd.valid := true.B //TODO:

    // BSPad.ScarchPadIO.FromMemoryLoader <> BML.ToScarchPadIO
    // BSPad.ScarchPadIO.FromDataController <> BDC.FromScarchPadIO

    // BML.ToScarchPadIO <> Mux(TaskCtrl.ConfigInfo.bits.ScaratchpadChosen.BMemoryLoaderChosenIndex === 0.U, BSPad_0.ScarchPadIO.FromMemoryLoader, BSPad_1.ScarchPadIO.FromMemoryLoader)
    BSPad_0.ScarchPadIO.FromMemoryLoader <> BML.ToScarchPadIO
    BSPad_1.ScarchPadIO.FromMemoryLoader <> BML.ToScarchPadIO
    when(TaskCtrl.ConfigInfo.bits.ScaratchpadChosen.BMemoryLoaderChosenIndex === 0.U)
    {
        //TODO:改ScarchPad内的处理逻辑。
        BSPad_0.ScarchPadIO.MemoryLoaderValid := true.B
        BSPad_1.ScarchPadIO.MemoryLoaderValid := false.B
    }.otherwise{
        BSPad_0.ScarchPadIO.MemoryLoaderValid := false.B
        BSPad_1.ScarchPadIO.MemoryLoaderValid := true.B
    }
    BML.ConfigInfo.bits := TaskCtrl.ConfigInfo.bits
    BML.ConfigInfo.valid := TaskCtrl.ConfigInfo.valid
    BML.TaskEnd.bits := true.B //TODO:
    BML.TaskEnd.valid := true.B //TODO:
    BML.MemoryLoadEnd.ready := true.B //TODO:
    
    // CDC.FromScarchPadIO <> Mux(TaskCtrl.ConfigInfo.bits.ScaratchpadChosen.CDataControllerChosenIndex === 0.U, CSPad_0.ScarchPadIO.FromDataController, CSPad_1.ScarchPadIO.FromDataController)
    CSPad_0.ScarchPadIO.FromDataController <> CDC.FromScarchPadIO
    CSPad_1.ScarchPadIO.FromDataController <> CDC.FromScarchPadIO
    when(TaskCtrl.ConfigInfo.bits.ScaratchpadChosen.CDataControllerChosenIndex === 0.U)
    {
        CSPad_0.ScarchPadIO.DataControllerValid := true.B
        CSPad_1.ScarchPadIO.DataControllerValid := false.B
    }.otherwise{
        CSPad_0.ScarchPadIO.DataControllerValid := false.B
        CSPad_1.ScarchPadIO.DataControllerValid := true.B
    }
    CDC.ConfigInfo.bits := TaskCtrl.ConfigInfo.bits
    CDC.ConfigInfo.valid := TaskCtrl.ConfigInfo.valid
    CDC.SwitchScarchPad.ready := true.B //实际上要看具体的另外一个ScarchPad的memoryloader的任务是否完成了。//TODO:
    CDC.TaskEnd.bits := true.B //TODO:
    CDC.TaskEnd.valid := true.B //TODO:
    
    // CSPad.ScarchPadIO.FromMemoryLoader <> CML.ToScarchPadIO
    // CSPad.ScarchPadIO.FromDataController <> CDC.FromScarchPadIO

    // CML.ToScarchPadIO <> Mux(TaskCtrl.ConfigInfo.bits.ScaratchpadChosen.CMemoryLoaderChosenIndex === 0.U, CSPad_0.ScarchPadIO.FromMemoryLoader, CSPad_1.ScarchPadIO.FromMemoryLoader)
    CSPad_0.ScarchPadIO.FromMemoryLoader <> CML.ToScarchPadIO
    CSPad_1.ScarchPadIO.FromMemoryLoader <> CML.ToScarchPadIO
    when(TaskCtrl.ConfigInfo.bits.ScaratchpadChosen.CMemoryLoaderChosenIndex === 0.U)
    {
        //TODO:改ScarchPad内的处理逻辑。
        CSPad_0.ScarchPadIO.MemoryLoaderValid := true.B
        CSPad_1.ScarchPadIO.MemoryLoaderValid := false.B
    }.otherwise{
        CSPad_0.ScarchPadIO.MemoryLoaderValid := false.B
        CSPad_1.ScarchPadIO.MemoryLoaderValid := true.B
    }
    CML.ConfigInfo.bits := TaskCtrl.ConfigInfo.bits
    CML.ConfigInfo.valid := TaskCtrl.ConfigInfo.valid
    CML.TaskEnd.bits := true.B //TODO:
    CML.TaskEnd.valid := true.B //TODO:
    CML.MemoryLoadEnd.ready := true.B //TODO:

    TaskCtrl.ConfigInfo.ready := ADC.ConfigInfo.ready && BDC.ConfigInfo.ready && CDC.ConfigInfo.ready && MTE.ConfigInfo.ready && AML.ConfigInfo.ready && BML.ConfigInfo.ready && CML.ConfigInfo.ready


}


