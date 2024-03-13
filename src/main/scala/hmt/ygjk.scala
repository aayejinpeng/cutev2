package boom.exu.ygjk

import chisel3._
import chisel3.util._
import boom.acc._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.tile._ //for rocc
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import boom.common._
import org.chipsalliance.cde.config._


class WithYGJKAccel extends Config((site,here,up) => {
    case BuildRoCC => Seq(
        (p:Parameters) => {
            val regWidth = 32 // 寄存器位宽
            val ygjk = LazyModule(new RoCC2YGJK(OpcodeSet.all)(p))
            ygjk
        }
    )
})

class RoCC2YGJK(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(opcodes) 
  with YGJKParameters{
  override lazy val module = new YgjkTile(this)
//  lazy val mem = LazyModule(new Yg2TL2)
//  tlNode := TLWidthWidget(ygjk_memWidth) := mem.node
  lazy val mem0 = Seq.fill(accNum)(LazyModule(new Yg2TL2))
  lazy val mem1 = Seq.fill(accNum)(LazyModule(new Yg2TL2))
  for(i <- 0 until accNum){
    tlNode := TLWidthWidget(ygjk_memWidth) := mem0(i).node
    DMANode := TLWidthWidget(ygjk_memWidth) := mem1(i).node //TODO:这里的node的处理逻辑得重新写，尤其是位宽处理上
  }
//  tlNode := TLWidthWidget(ygjk_memWidth) := mem.map(_.node)
}

class YgjkTile(outer: RoCC2YGJK)(implicit p: Parameters) extends LazyRoCCModuleImp(outer)
  with YGJKParameters
  with HasBoomCoreParameters {
    val acc = VecInit(Seq.fill(accNum)(p(BuildYGAC)(p).io))
    val mem0 = (outer.mem0.map(_.module))
    val mem1 = (outer.mem1.map(_.module))
//    val lmmu = Module(new LMMU)

    val rs1 = RegInit(0.U(regWidth.W))
    val rs2 = RegInit(0.U(regWidth.W))
    val rd_data = RegInit(0.U(regWidth.W))
    val rd = RegInit(0.U(5.W))
    val func = RegInit(0.U(7.W))
    val canResp = RegInit(false.B)
    val ac_busy = RegInit(false.B)
    val configV = RegInit(false.B)

    val count = RegInit(0.U(regWidth.W))
    when(ac_busy){
      count := count + 1.U
    }
    val compute = RegInit(0.U(regWidth.W))
    when(io.cmd.fire() && io.cmd.bits.inst.opcode === "h0B".U && io.cmd.bits.inst.funct === 0.U){
      compute := 0.U
    }.elsewhen(ac_busy){
      compute := compute + 1.U
    }

    val memNum_r = RegInit(0.U(regWidth.W))
    val memNum_w = RegInit(0.U(regWidth.W))

    val missAddr = RegInit(0.U(vaddrBits.W))

    val jk_idle :: jk_compute :: jk_resp :: jk_lmmu_miss :: Nil = Enum(4)
    val jk_state = RegInit(jk_idle)

//    lmmu.io.req.vaddr0_v := acc.io.cmd.acc_req_a.valid
//    lmmu.io.req.vaddr1_v := acc.io.cmd.acc_req_b.valid
//    lmmu.io.req.vaddr0 := acc.io.cmd.acc_req_a.bits.addr
//    lmmu.io.req.vaddr1 := acc.io.cmd.acc_req_b.bits.addr
/*
    val mem_req = Wire(UInt(1.W)) //0-a, 1-b
    when(acc.io.cmd.acc_req_b.valid){
      mem_req := 1.U
    }.otherwise{
      mem_req := 0.U
    }
*/
    for(i <- 0 until accNum){
      mem0(i).io.req.valid := acc(i).cmd.acc_req_a.valid || acc(i).cmd.acc_req_b.valid
      mem1(i).io.req.valid := ((acc(i).cmd.acc_req_a.valid || acc(i).cmd.acc_req_b.valid) && !mem0(i).io.req.ready) || 
                              acc(i).cmd.acc_req_a.valid && acc(i).cmd.acc_req_b.valid
      mem0(i).io.req.bits.addr := Mux(acc(i).cmd.acc_req_b.valid, acc(i).cmd.acc_req_b.bits.addr, acc(i).cmd.acc_req_a.bits.addr)
      mem1(i).io.req.bits.addr := Mux(mem0(i).io.req.ready, acc(i).cmd.acc_req_a.bits.addr, 
                                      Mux(acc(i).cmd.acc_req_b.valid, acc(i).cmd.acc_req_b.bits.addr, 
                                            acc(i).cmd.acc_req_a.bits.addr))
      mem0(i).io.req.bits.data := Cat(acc(i).cmd.acc_req_b.bits.data.reverse)
      mem1(i).io.req.bits.data := Cat(acc(i).cmd.acc_req_b.bits.data.reverse)
      mem0(i).io.req.bits.cmd := Mux(acc(i).cmd.acc_req_b.valid, 1.U, 0.U)
      mem0(i).io.req.bits.size := log2Ceil(JKDataNum*dataWidth>>3).U
      mem0(i).io.req.bits.mask := -1.S((JKDataNum*dataWidth>>3).W).asUInt()
      mem1(i).io.req.bits.cmd := Mux(mem0(i).io.req.ready, 0.U, Mux(acc(i).cmd.acc_req_b.valid, 1.U, 0.U))
      mem1(i).io.req.bits.size := log2Ceil(JKDataNum*dataWidth>>3).U
      mem1(i).io.req.bits.mask := -1.S((JKDataNum*dataWidth>>3).W).asUInt()

      acc(i).cmd.acc_req_a.ready := (mem0(i).io.req.ready && !acc(i).cmd.acc_req_b.valid) || 
                                    (!mem0(i).io.req.ready && mem1(i).io.req.ready && !acc(i).cmd.acc_req_b.valid) ||
                                    (mem0(i).io.req.ready && mem1(i).io.req.ready)
      acc(i).cmd.acc_req_b.ready := mem0(i).io.req.ready || mem1(i).io.req.ready
      acc(i).cmd.req_id := Mux(mem0(i).io.req.ready && !acc(i).cmd.acc_req_b.valid, Cat(0.U, mem0(i).io.req_id), 
                                  Cat(1.U, mem1(i).io.req_id)) 

      acc(i).buffer0.valid := mem0(i).io.resp.valid 
      acc(i).buffer1.valid := mem1(i).io.resp.valid 
      acc(i).buffer0.bits.data := VecInit.tabulate(JKDataNum) { t => mem0(i).io.resp.bits.data((t + 1) * dataWidth - 1, t * dataWidth)}
      acc(i).buffer1.bits.data := VecInit.tabulate(JKDataNum) { t => mem1(i).io.resp.bits.data((t + 1) * dataWidth - 1, t * dataWidth)}
      acc(i).buffer0.bits.id := Cat(0.U, mem0(i).io.resp.bits.id)
      acc(i).buffer1.bits.id := Cat(1.U, mem1(i).io.resp.bits.id)

      acc(i).ctl.config.valid := io.cmd.fire() && io.cmd.bits.inst.opcode === "h0B".U && io.cmd.bits.inst.funct === ((i<<2)+0).U
      acc(i).ctl.config.bits.cfgData1 := io.cmd.bits.rs1
      acc(i).ctl.config.bits.cfgData2 := io.cmd.bits.rs2
      acc(i).ctl.config.bits.func := io.cmd.bits.inst.funct

      acc(i).ctl.reset := false.B  //多次重启时置位，未实现
    }
/*
    mem.io.req.valid := (mem_req === 0.U && lmmu.io.resp.paddr0_v) || (mem_req === 1.U && lmmu.io.resp.paddr1_v)
    mem.io.req.bits.addr := Mux(mem_req === 0.U, lmmu.io.resp.paddr0, lmmu.io.resp.paddr1)
    mem.io.req.bits.data := Cat(acc.io.cmd.acc_req_b.bits.data.reverse)
    mem.io.req.bits.cmd := mem_req
    mem.io.req.bits.size := log2Ceil(JKDataNum*dataWidth>>3).U
    mem.io.req.bits.mask := -1.S((JKDataNum*dataWidth>>3).W).asUInt()

    acc.io.cmd.acc_req_a.ready := mem.io.req.ready && mem_req === 0.U && !lmmu.io.resp.miss
    acc.io.cmd.acc_req_b.ready := mem.io.req.ready && mem_req === 1.U && !lmmu.io.resp.miss
    acc.io.cmd.req_id := mem.io.req_id
*/
    when(acc(0).cmd.acc_req_a.fire()){
      memNum_r := memNum_r + 1.U
    }
    when(acc(0).cmd.acc_req_b.fire()){
      memNum_w := memNum_w + 1.U
    }

/*  
    acc.io.buffer.valid := mem.io.resp.valid
    acc.io.buffer.bits.data := VecInit.tabulate(JKDataNum) { i => mem.io.resp.bits.data((i + 1) * dataWidth - 1, i * dataWidth)}
    acc.io.buffer.bits.id := mem.io.resp.bits.id

    acc.io.ctl.config.valid := io.cmd.fire() && io.cmd.bits.inst.opcode === "h0B".U && io.cmd.bits.inst.funct === 0.U
    acc.io.ctl.config.bits.cfgData1 := io.cmd.bits.rs1
    acc.io.ctl.config.bits.cfgData2 := io.cmd.bits.rs2
    acc.io.ctl.config.bits.func := io.cmd.bits.inst.funct

    acc.io.ctl.reset := false.B  //多次重启时置位，未实现
*/
    io.cmd.ready := !canResp
    when(io.cmd.fire()){
      canResp := true.B
    }.elsewhen(io.resp.fire()){
      canResp := false.B
    }

    rd := io.cmd.bits.inst.rd    //下一拍一定会返回
    io.resp.bits.rd := rd
    io.resp.bits.data := rd_data
    io.resp.valid := canResp

    when(io.cmd.fire() && io.cmd.bits.inst.opcode === "h0B".U && io.cmd.bits.inst.funct === 1.U){ //查询加速器是否在运行
      rd_data := ac_busy
    }.elsewhen(io.cmd.fire() && io.cmd.bits.inst.opcode === "h0B".U && io.cmd.bits.inst.funct === 2.U){ //查询加速器运行时间
      rd_data := count
    }.elsewhen(io.cmd.fire() && io.cmd.bits.inst.opcode === "h0B".U && io.cmd.bits.inst.funct === 3.U){ //查询加速器对外访存读次数
      rd_data := memNum_r
    }.elsewhen(io.cmd.fire() && io.cmd.bits.inst.opcode === "h0B".U && io.cmd.bits.inst.funct === 4.U){ //查询加速器对外访存写次数
      rd_data := memNum_w
    }.elsewhen(io.cmd.fire() && io.cmd.bits.inst.opcode === "h0B".U && io.cmd.bits.inst.funct === 5.U){ //查询加速器计算时间
      rd_data := compute
    }

/*
    lmmu.io.core.refillVaddr := io.cmd.bits.rs1
    lmmu.io.core.refillPaddr := io.cmd.bits.rs2
    lmmu.io.core.refill_v := false.B
    lmmu.io.core.useVM := false.B
    lmmu.io.core.useVM_v := false.B
    when(io.cmd.fire() && io.cmd.bits.inst.opcode === "h2B".U && io.cmd.bits.inst.funct === 1.U){
      //填TLB
      lmmu.io.core.refill_v := true.B
    }.elsewhen(io.cmd.fire() && io.cmd.bits.inst.opcode === "h2B".U && io.cmd.bits.inst.funct === 2.U){
      //开启虚实地址转换
      lmmu.io.core.useVM := true.B
      lmmu.io.core.useVM_v := true.B
    }.elsewhen(io.cmd.fire() && io.cmd.bits.inst.opcode === "h2B".U && io.cmd.bits.inst.funct === 3.U){
      //关闭虚实地址转换
      lmmu.io.core.useVM := false.B
      lmmu.io.core.useVM_v := true.B
    }
*/

    io.interrupt := false.B
    io.badvaddr_ygjk := Mux(jk_state=/=jk_resp, missAddr, missAddr+1.U)
    switch(jk_state){
      is(jk_idle){
        when(io.cmd.fire() && io.cmd.bits.inst.opcode === "h0B".U && io.cmd.bits.inst.funct === 0.U){
          ac_busy := true.B
          jk_state := jk_compute
          count := 0.U
          memNum_r := 0.U
          memNum_w := 0.U
        }
      }

      is(jk_compute){
/*
        when(lmmu.io.resp.miss){
          missAddr := lmmu.io.resp.missAddr
          jk_state := jk_lmmu_miss
        }
*/
//        when(acc.io.ctl.acc_running === false.B && mem.io.idle){
        when(acc.map(_.ctl.acc_running).reduce(_|_) === false.B && mem0.map(_.io.idle).reduce(_&_) && mem1.map(_.io.idle).reduce(_&_)){
          jk_state := jk_resp
        }
      }
/*
      is(jk_lmmu_miss){
        io.interrupt := true.B
        when(lmmu.io.core.refill_v === true.B){
          jk_state := jk_compute
        }
      }
*/
      is(jk_resp){
//        io.interrupt := true.B
        ac_busy := false.B
        when(io.cmd.fire() && io.cmd.bits.inst.opcode === "h2B".U && io.cmd.bits.inst.funct === 0.U){
          // 收到中断响应
          jk_state := jk_idle
        }
      }
    }

}
