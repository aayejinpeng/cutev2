/*
//2020-0923
package boom.exu.ygjk

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.tile._ //for rocc
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import boom.common._

//import boom.acc._
class RoCC2YGJK(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(opcodes) 
  with YGJKParameters{
  override lazy val module = new YgjkTile(this)
//  lazy val mem = LazyModule(new YgTLpfCache)
  lazy val mem = LazyModule(new Yg2TL2)
  tlNode := TLWidthWidget(ygjk_memWidth) := mem.node
}

class YgjkTile(outer: RoCC2YGJK)(implicit p: Parameters) extends LazyRoCCModuleImp(outer)
  with YGJKParameters
  with HasBoomCoreParameters
  with L0cacheParameters {

//  val acc = p(BuildYGAC)(p) //Module(new MyACCModule1) //
  val mem = outer.mem.module
  val lmmu = Module(new LMMU)
  val sdma = Module(new StreamModule)

//  acc.io.sdma <> sdma.io.acc

  val jk_idle :: jk_compute :: jk_resp :: jk_lmmu_miss  :: Nil = Enum(4)
  val jk_state = RegInit(jk_idle)

  val rs1 = RegInit(0.U(64.W))
  val rs2 = RegInit(0.U(64.W))
  val rd = RegInit(0.U(5.W))
  val func = RegInit(0.U(7.W))
  val sdmaInstV = RegInit(false.B)
  val accInstV = RegInit(false.B)
  val accQuery = RegInit(false.B)
  val ac_busy = RegInit(false.B)
  val canResp = RegInit(false.B)
  val interrupt_end = RegInit(true.B)

  val missAddr = RegInit(0.U(vaddrBits.W))
  val instSel = RegInit(0.U(2.W))

  val cache_hit = RegInit(0.U(32.W))
  val cache_miss = RegInit(0.U(32.W))
  val cycles_compute = RegInit(0.U(32.W))
  val cycles_total = RegInit(0.U(32.W))
  val cycles_compute_only = RegInit(0.U(32.W))
  val cycles_lmmu_miss = RegInit(0.U(32.W))

  val touch_addr = RegInit(0.U(32.W))
  val touch_state = RegInit(0.U(2.W)) //0-没有需要touch的地址 1-touch请求 2-等待touch返回

  val req = WireInit(sdma.io.cmd.acc_req_a)


  lmmu.io.req.vaddr0_v := (sdma.io.cmd.acc_req_a.valid && jk_state === jk_compute) || touch_state === 1.U
  lmmu.io.req.vaddr0 := Mux(sdma.io.cmd.acc_req_a.valid, sdma.io.cmd.acc_req_a.addr, touch_addr)
//  lmmu.io.req.vaddr0_v := sdma.io.cmd.acc_req_a.valid && jk_state === jk_compute
//  lmmu.io.req.vaddr0 := sdma.io.cmd.acc_req_a.addr
  lmmu.io.req.vaddr1_v := sdma.io.cmd.acc_req_b.valid && jk_state === jk_compute
  lmmu.io.req.vaddr1 := sdma.io.cmd.acc_req_b.addr

  when(touch_state===1.U || touch_state===2.U){
//    printf(p"touch_addr ${touch_addr}\n")
  }


  req.valid := lmmu.io.resp.paddr0_v
  req.addr := lmmu.io.resp.paddr0
  when(lmmu.io.resp.paddr1_v) {
    req := sdma.io.cmd.acc_req_b
    req.valid := lmmu.io.resp.paddr1_v
    req.addr := lmmu.io.resp.paddr1
  }.otherwise{
    req := sdma.io.cmd.acc_req_a
    req.valid := lmmu.io.resp.paddr0_v | touch_state === 1.U
    req.addr := lmmu.io.resp.paddr0
  }

  when(!sdma.io.cmd.acc_req_a.valid && !sdma.io.cmd.acc_req_b.valid && touch_state === 1.U){
    touch_state := 2.U
  }.elsewhen(mem.io.resp.valid && touch_state === 2.U){
    touch_state := 0.U
  }

  mem.io.req.valid := req.valid
  mem.io.req.bits.addr := req.addr
  mem.io.req.bits.data := Cat(req.data.reverse)
  mem.io.req.bits.cmd := req.cmd
  mem.io.req.bits.size := 6.U
  mem.io.req.bits.mask := -1.S(64.W).asUInt()

  // memio(0).io.acc_req <> sdma.io.cmd.acc_req_a
  // memio(1).io.acc_req <> sdma.io.cmd.acc_req_b

/*  
  when(mem.io.hit =/=mem.io.miss){
    when(mem.io.hit){
      cache_hit := cache_hit + 1.U
    }
    when(mem.io.miss){
      cache_miss := cache_miss + 1.U
    }
  }
*/
  when(ac_busy){
    cycles_compute := cycles_compute + 1.U
  }

  when(!interrupt_end){
    cycles_total := cycles_total + 1.U
  }

  when(jk_state === jk_lmmu_miss){
    cycles_lmmu_miss := cycles_lmmu_miss + 1.U
  }

  //decode & initial & response
  rd := io.cmd.bits.inst.rd
//  io.cmd.ready := true.B
  io.cmd.ready := !canResp
  when(io.cmd.fire() && io.cmd.bits.inst.opcode === "h0B".U && io.cmd.bits.inst.funct === 0.U) { // //rocc_req  fire  cmd come
    rs1 := io.cmd.bits.rs1
    rs2 := io.cmd.bits.rs2
    func := io.cmd.bits.inst.funct
    sdmaInstV := false.B
    accInstV := false.B
    accQuery := false.B
    when(jk_state===jk_idle){
       
    }
    cycles_lmmu_miss := 0.U
    ac_busy := true.B
    interrupt_end := false.B
  }.elsewhen(io.cmd.fire() && io.cmd.bits.inst.opcode === "h0B".U && io.cmd.bits.inst.funct >= 2.U && io.cmd.bits.inst.funct <= 4.U) {
    rs1 := io.cmd.bits.rs1
    rs2 := io.cmd.bits.rs2
    func := io.cmd.bits.inst.funct
    accInstV := false.B
    sdmaInstV := true.B
    accQuery := false.B
    cycles_compute_only := 0.U
    ac_busy := true.B
  }.elsewhen(io.cmd.fire() && io.cmd.bits.inst.opcode === "h0B".U && io.cmd.bits.inst.funct === 5.U){
    rs1 := io.cmd.bits.rs1
    rs2 := io.cmd.bits.rs2
    sdmaInstV := false.B
    accInstV := false.B
    accQuery := true.B
  }.otherwise {
    sdmaInstV := false.B
    accInstV := false.B
    accQuery := false.B
  }

  when(io.cmd.fire()){
//      printf(p"io.cmd.fire()\n")
      canResp := true.B
      printf(p"io.cmd.fire()  io.cmd.bits.rs1 ${io.cmd.bits.rs1} io.cmd.bits.rs2 ${io.cmd.bits.rs2}\n")
  }


  val rd_data = RegInit(0.U(64.W))

  io.resp.bits.data := rd_data
//  io.resp.bits.data := Mux(accQuery, acc.io.query.rd, rd_data)
  io.resp.valid := canResp
  io.resp.bits.rd := rd
  when(io.resp.fire()) {
//  printf(p"io.resp.fire()\n")
    canResp := false.B
  }

  
  lmmu.io.core.refillVaddr := io.cmd.bits.rs1
  lmmu.io.core.refillPaddr := io.cmd.bits.rs2
  when(io.cmd.fire() && io.cmd.bits.inst.opcode === "h2B".U && io.cmd.bits.inst.funct === 4.U){
      lmmu.io.core.refill_v := true.B
      printf(p"io.cmd.bits.rs1 ${io.cmd.bits.rs1} io.cmd.bits.rs2 ${io.cmd.bits.rs2}\n")
      lmmu.io.core.refillVaddr := io.cmd.bits.rs1
      lmmu.io.core.refillPaddr := io.cmd.bits.rs2
  }.otherwise{
    lmmu.io.core.refill_v := false.B
  }


  when(io.cmd.fire() && io.cmd.bits.inst.opcode === "h2B".U && io.cmd.bits.inst.funct === 2.U){
//    printf(p"lmmu funct === 2\n")
    lmmu.io.core.useVM := true.B
    lmmu.io.core.useVM_v := true.B
  }.elsewhen(io.cmd.fire() && io.cmd.bits.inst.opcode === "h2B".U && io.cmd.bits.inst.funct === 3.U){
    lmmu.io.core.useVM := false.B
    lmmu.io.core.useVM_v := true.B
  }.otherwise{
    lmmu.io.core.useVM := false.B
    lmmu.io.core.useVM_v := false.B
  }

  when(io.cmd.fire() && io.cmd.bits.inst.opcode === "h2B".U && io.cmd.bits.inst.funct === 1.U){
    when(jk_state=/=jk_resp){
      rd_data := missAddr
    }.otherwise{
      rd_data := missAddr + 1.U
    }
  }.elsewhen(io.cmd.fire() && io.cmd.bits.inst.opcode === "h2B".U && io.cmd.bits.inst.funct === 5.U){
    rd_data := cache_miss
  }.elsewhen(io.cmd.fire() && io.cmd.bits.inst.opcode === "h2B".U && io.cmd.bits.inst.funct === 6.U){
    rd_data := cycles_compute_only
  }.elsewhen(io.cmd.fire() && io.cmd.bits.inst.opcode === "h2B".U && io.cmd.bits.inst.funct === 7.U){
//    printf(p"&&&&&jk_state ${jk_state}\n")
    when(jk_state === jk_resp){
//      printf(p"jk_state === jk_resp\n")
      rd_data := 10.U
    }.otherwise{
//      printf(p"jk_state =/= jk_resp\n")
      rd_data := 0.U
    }
  }.elsewhen(io.cmd.fire() && io.cmd.bits.inst.opcode === "h2B".U && io.cmd.bits.inst.funct === 8.U){
    rd_data := cycles_compute
  }.elsewhen(io.cmd.fire() && io.cmd.bits.inst.opcode === "h2B".U && io.cmd.bits.inst.funct === 9.U){
    rd_data := cycles_total
  }.elsewhen(io.cmd.fire() && io.cmd.bits.inst.opcode === "h0B".U && io.cmd.bits.inst.funct === 5.U){
//    rd_data := acc.io.query.rd
  }.otherwise{
    rd_data := ac_busy
  }

  sdma.io.cmd.axh_cready_a := mem.io.req.ready && ! lmmu.io.req.vaddr1_v && jk_state === jk_compute && !lmmu.io.resp.miss
  sdma.io.cmd.axh_cready_b := mem.io.req.ready && jk_state === jk_compute && !lmmu.io.resp.miss

  sdma.io.cmd.req_id := mem.io.req_id

  sdma.io.ctl.hax_jval := sdmaInstV
  sdma.io.ctl.config.cfgData1 := rs1
  sdma.io.ctl.config.cfgData2 := rs2
  sdma.io.ctl.config.func := func

/*
  acc.io.config.valid := accInstV
  acc.io.config.bits.cfgData1 := rs1
  acc.io.config.bits.cfgData2 := rs2
  acc.io.config.bits.func := func

  acc.io.query.rs1 := rs1
  acc.io.query.rs2 := rs2
  acc.io.query.valid := accQuery
*/

  sdma.io.buffer.brdata := VecInit.tabulate(JKDataNum) { i => mem.io.resp.bits.data((i + 1) * bufferLine - 1, i * bufferLine)}
  sdma.io.buffer.brvalid := mem.io.resp.valid && touch_state =/= 2.U
  sdma.io.buffer.id := mem.io.resp.bits.id

  sdma.io.buffer.bwdata := VecInit.tabulate(JKDataNum) { i => mem.io.resp.bits.data((i + 1) * bufferLine - 1, i * bufferLine)}
  sdma.io.buffer.bwvalid := mem.io.resp.valid && touch_state =/= 2.U

  io.interrupt := false.B
  io.badvaddr_ygjk := Mux(jk_state=/=jk_resp,missAddr,missAddr+1.U)

  sdma.io.ctl.reset := false.B

  switch(jk_state) {
    is(jk_idle) {
      cycles_compute := 0.U 
      
      jk_state := Mux(ac_busy, jk_compute, jk_idle)
      when(ac_busy){
        cycles_total := 0.U  
      }
    }

    is(jk_compute) {
      when(io.cmd.fire() && io.cmd.bits.inst.opcode === "h0B".U && io.cmd.bits.inst.funct === 2.U){
        cycles_compute_only := 0.U
      }.otherwise{
        cycles_compute_only := cycles_compute_only + 1.U
      }
      when(lmmu.io.resp.miss){
        missAddr := lmmu.io.resp.missAddr
        jk_state := jk_lmmu_miss
      }
      when(sdma.io.ctl.axh_jdone && mem.io.req.ready) {
        jk_state := jk_resp
      }
      when(io.cmd.fire() && io.cmd.bits.inst.opcode === "h2B".U && io.cmd.bits.inst.funct === 10.U){
        ac_busy := false.B
        sdma.io.ctl.reset := true.B
        jk_state := jk_idle
      }
    }
    is(jk_lmmu_miss){
      io.interrupt := true.B
      printf(p"jk_lmmu_miss ${jk_lmmu_miss} io.badvaddr_ygjk ${io.badvaddr_ygjk}\n")
/*
      when(io.cmd.fire() && io.cmd.bits.inst.opcode === "h2B".U && io.cmd.bits.inst.funct === 4.U){
        lmmu.io.core.refill_v := true.B
        lmmu.io.core.refillVaddr := io.cmd.bits.rs1
        lmmu.io.core.refillPaddr := io.cmd.bits.rs2
        
      }
*/
      jk_state := jk_compute
    }
    is(jk_resp) {
      io.interrupt := true.B
      ac_busy := false.B
      jk_state := jk_idle
//      when(io.cmd.fire() && io.cmd.bits.inst.opcode === "h2B".U && io.cmd.bits.inst.funct === 0.U){
//        interrupt_end := true.B
//        jk_state := jk_idle
//      }
    }
  }
}

*/