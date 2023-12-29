// package boom.acc

// import chisel3._
// import chisel3.util._
// import freechips.rocketchip.config._
// import boom.exu.ygjk._



// class Withacc2 extends Config((site,here,up) => {
//   case BuildYGAC =>
//       (p:Parameters) => {
//           val myAccel = Module(new front_test)
//           myAccel
//       }
//   }
// )

// // 通过ygjk接口向外发送取指请求

// class front_test extends MyACCModule{
//   io.cmd := DontCare
//   io.buffer := DontCare   // 忽略两个数据访存通道
//   io.ctl.axh_jdone := false.B   // 默认false
//   io.ctl.axh_jrunning := true.B   // 没用

//   val pc_addr = RegInit(0.U(dataWidth.W))
//   val pc_valid = RegInit(false.B)
//   val data = RegInit(0.U(dataWidth.W))
//   val started = RegInit(false.B)

//   io.fetch.imem_req.addr  := pc_addr
//   io.fetch.imem_req.valid := pc_valid
//   io.fetch.imem_req.cmd   := 0.U  // 默认读
//   io.fetch.imem_req.size  := 16.U
//   io.fetch.imem_req.data  := DontCare

//   pc_valid := io.fetch.ready && started   // 只要可以发送就发
//   // 启动状态
//   when(io.ctl.hax_jval){
//     started := true.B
//     pc_addr := io.ctl.config.cfgData1
//     pc_valid := true.B
//   }
//   // 收到指令, 存起来, 发下一条指令(默认已经启动了)
//   when(io.fetch.imem_resp.valid){
//     data := io.fetch.imem_resp.bits
//     printf("data = %x\n", data)
//     pc_addr :=  pc_addr + 4.U
//   }


// }
