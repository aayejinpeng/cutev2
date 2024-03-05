
package boom.acc.cute

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import boom.exu.ygjk._

class Withacc_MMacc extends Config((site,here,up) => {  
    case BuildYGAC =>
        (p:Parameters) => {          
            val myAccel = Module(new MMacc)
            myAccel
        }
    }
)

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


    val ASPad = Module(new AScratchpad).io
    val ADC = Module(new ADataController).io
    
    val MTE = Module(new MatrixTE).io

    MTE.VectorA <> ADC.VectorA
    MTE.VectorB := DontCare //TODO:
    MTE.MatirxC := DontCare //TODO:
    MTE.MatrixD := DontCare //TODO:
    MTE.ConfigInfo := DontCare //TODO:
    ADC.FromScarchPadIO <> ASPad.ScarchPadIO.FromDataController
    ADC.ConfigInfo := DontCare //TODO:
    ASPad.ScarchPadIO.FromMemoryLoader := DontCare //TODO:
    
    ADC.SwitchScarchPad.ready := false.B //TODO:
    ADC.TaskEnd.bits := false.B //TODO:
    ADC.TaskEnd.valid := false.B //TODO:
    




}


