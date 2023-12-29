/*
package boom.exu.ygjk

import chisel3._
import chisel3.util._
import freechips.rocketchip.config._
import boom.exu.ygjk._


trait StreamParameters{
    val keyNum = 32
    val statemtrow = 4
    val statemtcol =4
    val wordNum = 60
    val wordGroupNum = 4
    val byteNum = 8
    val req_buffer = 16
}

class ACC2SDMAIO extends Bundle with YGJKParameters{
  val read0_req = Flipped(DecoupledIO(Vec(dataNum,UInt(dataWidth.W))))
  val read1_req = Flipped(DecoupledIO(Vec(dataNum,UInt(dataWidth.W))))
  val write_req = DecoupledIO(Vec(dataNum,UInt(dataWidth.W)))
  val run = Input(Bool())
}

class StreamModule extends Module with StreamParameters with YGJKParameters{
    val io = IO(new Bundle{
      val jk = Flipped(new YGJKIO)
      val acc = Flipped(new ACC2SDMAIO)
    })

    val read0_addr = RegInit(0.U(addrWidth.W))
    val read1_addr = RegInit(0.U(addrWidth.W))
    val req0_num = RegInit(0.U(32.W))    //队列中已发出读请求及收到未送到加速器的个数
    val req1_num = RegInit(0.U(32.W))
    val write_addr = RegInit(0.U(addrWidth.W))

    val read0_data = RegInit(VecInit(Seq.fill(req_buffer)(VecInit(Seq.fill(dataNum)(0x05050505.U(dataWidth.W))))))
    val read0_data_v = RegInit(VecInit(Seq.fill(req_buffer)(false.B)))
    val read1_data = RegInit(VecInit(Seq.fill(req_buffer)(VecInit(Seq.fill(dataNum)(0x05050505.U(dataWidth.W))))))
    val read1_data_v = RegInit(VecInit(Seq.fill(req_buffer)(false.B)))
    val write_data = RegInit(VecInit(Seq.fill(req_buffer)(VecInit(Seq.fill(dataNum)(0x05050505.U(dataWidth.W))))))
    val req0_id = RegInit(VecInit(Seq.fill(req_buffer)(0.U(5.W))))
    val req1_id = RegInit(VecInit(Seq.fill(req_buffer)(0.U(5.W))))
    val read0_head = RegInit(0.U(5.W))
    val read0_tail = RegInit(0.U(5.W))
    val read1_head = RegInit(0.U(5.W))
    val read1_tail = RegInit(0.U(5.W))
    val write_head = RegInit(0.U(5.W))
    val write_tail = RegInit(0.U(5.W))

    val read_req = WireInit(false.B)
    val read0_size = RegInit(0.U(32.W))
    val read1_size = RegInit(0.U(32.W))
    val write_req = WireInit(false.B)
    val write_size = RegInit(0.U(32.W))

    val s_idle :: s_copy :: s_finish :: Nil = Enum(3)
    val sta  = RegInit(s_idle)

    val run = RegInit(false.B)
    io.jk.ctl.axh_jrunning := run
    io.jk.ctl.axh_jdone := !run

    io.acc.run := run

    val config_i = RegInit(0.U(3.W))
    when(io.jk.ctl.hax_jval && sta === s_idle){  //接收到启动指令
        run := true.B
        when(config_i === 0.U){
            read0_addr := io.jk.ctl.config.cfgData1
            read0_size := io.jk.ctl.config.cfgData2
            config_i := 1.U
        }.elsewhen(config_i === 1.U){
//            printf(p"second inst\n")
            read1_addr := io.jk.ctl.config.cfgData1
            read1_size := io.jk.ctl.config.cfgData2
            config_i := 2.U
        }.elsewhen(config_i === 2.U){
//            printf(p"third inst\n")
            write_addr := io.jk.ctl.config.cfgData1
            write_size := io.jk.ctl.config.cfgData2
            config_i := 3.U
        }
    }
// read_req
    val read_req_stream = Wire(UInt(1.W))
    read_req_stream := Mux(((req0_num > req1_num) && (read1_size > 0.U)) || (read0_size === 0.U), 1.U, 0.U)
//    printf(p"req0_num ${req0_num}  req1_num ${req1_num} read1_size ${read1_size}  read0_size ${read0_size} read_req_stream ${read_req_stream}\n")
    when(io.jk.cmd.acc_req_a.valid){
//      printf(p"io.jk.cmd.acc_req_a.addr ${io.jk.cmd.acc_req_a.addr}\n")
    }

    io.jk.cmd.acc_req_a.valid := read_req 
    io.jk.cmd.acc_req_a.cmd := 0.U
    io.jk.cmd.acc_req_a.addr := Mux(read_req_stream===0.U , read0_addr , read1_addr)
    io.jk.cmd.acc_req_a.data := read0_data(0)
    io.jk.cmd.acc_req_a.size := 16.U

//write_req
    io.jk.cmd.acc_req_b.valid := write_req
    io.jk.cmd.acc_req_b.cmd :=  1.U
    io.jk.cmd.acc_req_b.addr := write_addr
    io.jk.cmd.acc_req_b.data := write_data(write_head)
    io.jk.cmd.acc_req_b.size := 16.U

    when(io.jk.cmd.axh_cready_b && write_req){
      write_addr := write_addr + 64.U
      write_size := write_size - 64.U
      when(write_head + 1.U === req_buffer.U){
        write_head := 0.U
      }.otherwise{
        write_head := write_head + 1.U
      }
    }

    when(io.acc.write_req.fire()){
      write_data(write_tail) := io.acc.write_req.bits
      when(write_tail + 1.U === req_buffer.U){
        write_tail := 0.U
      }.otherwise{
        write_tail := write_tail + 1.U
      }
    }
    
    io.acc.write_req.ready := !(write_tail === req_buffer.U - 1.U && write_head === 0.U) && !(write_tail + 1.U === write_head) && sta === s_copy

    when(io.jk.cmd.axh_cready_a && read_req && read_req_stream === 0.U){
      req0_id(read0_tail) := io.jk.cmd.req_id
      when(read0_tail + 1.U === req_buffer.U){
        read0_tail := 0.U
      }.otherwise{
        read0_tail := read0_tail + 1.U
      }
      read0_size := read0_size - 64.U
      read0_addr := read0_addr + 64.U
      when(!(io.acc.read0_req.fire())){
        req0_num := req0_num + 1.U  
      }
    }

//    printf(p"io.jk.cmd.axh_cready_a ${io.jk.cmd.axh_cready_a} read_req ${read_req}\n")

    when(io.jk.cmd.axh_cready_a && read_req && read_req_stream === 1.U){
      req1_id(read1_tail) := io.jk.cmd.req_id
      when(read1_tail + 1.U === req_buffer.U){
        read1_tail := 0.U
      }.otherwise{
        read1_tail := read1_tail + 1.U
      }
      read1_size := read1_size - 64.U
      read1_addr := read1_addr + 64.U
      when(!(io.acc.read1_req.fire())){
        req1_num := req1_num + 1.U  
      }
      
    }

    io.acc.read0_req.bits := read0_data(read0_head)
    io.acc.read0_req.valid := read0_data_v(read0_head)
    when(io.acc.read0_req.fire()){
//      printf(p"io.acc.read0_req.fire()\n")
      read0_data_v(read0_head) := false.B
      when(read0_head + 1.U === req_buffer.U){
        read0_head := 0.U
      }.otherwise{
        read0_head := read0_head + 1.U
      }
      when(!(io.jk.cmd.axh_cready_a && read_req && read_req_stream === 0.U)){
        req0_num := req0_num - 1.U  
      }
    }

    io.acc.read1_req.bits := read1_data(read1_head)
    io.acc.read1_req.valid := read1_data_v(read1_head)
    when(io.acc.read1_req.fire()){
//      printf(p"io.acc.read1_req.fire()\n")
      read1_data_v(read1_head) := false.B
      when(read1_head + 1.U === req_buffer.U){
        read1_head := 0.U
      }.otherwise{
        read1_head := read1_head + 1.U
      }
      when(!(io.jk.cmd.axh_cready_a && read_req && read_req_stream === 1.U)){
        req1_num := req1_num - 1.U  
      }
    }


    when(io.jk.buffer.brvalid){
//      printf(p"io.jk.buffer.brvalid\n")
      for( i <- 0 until req_buffer){
        when(read0_data_v(i) =/= true.B && req0_id(i) === io.jk.buffer.id && ((i.U >= read0_head && i.U < read0_tail) ||(read0_tail < read0_head && (i.U < read0_tail || i.U >= read0_head)))){
          read0_data_v(i) := true.B
          read0_data(i) := io.jk.buffer.brdata
          
//          printf(p"i $i read_head $read_head read_tail $read_tail\n")
        }
      }
      for( i <- 0 until req_buffer){
        when(read1_data_v(i) =/= true.B && req1_id(i) === io.jk.buffer.id && ((i.U >= read1_head && i.U < read1_tail) ||(read1_tail < read1_head && (i.U < read1_tail || i.U >= read1_head)))){
          read1_data_v(i) := true.B
          read1_data(i) := io.jk.buffer.brdata
          
//          printf(p"i $i read_head $read_head read_tail $read_tail\n")
        }
      }
    }

    write_req := write_head =/= write_tail && sta =/= s_idle

//    printf(p"read0_tail${read0_tail} read0_head${read0_head} read0_size${read0_size}\n")
//    printf(p"read1_tail${read1_tail} read1_head${read1_head} read1_size${read1_size}\n")
    read_req := ((!(read0_tail === req_buffer.U - 1.U && read0_head === 0.U) && !(read0_tail + 1.U === read0_head) && read0_size > 0.U)
                || (!(read1_tail === req_buffer.U - 1.U && read1_head === 0.U) && !(read1_tail + 1.U === read1_head) && read1_size > 0.U)) && sta === s_copy

    switch(sta){
      is(s_idle){
        when(config_i === 3.U){
          sta := s_copy
        }
      }
      is(s_copy){
//        printf(p"s_copy\n")
//        printf(p"read0_size ${read0_size} read1_size ${read1_size}\n")
        when(read0_size === 0.U && read1_size === 0.U && write_size === 0.U && write_head === write_tail && read0_head === read0_tail && read1_head === read1_tail){
          sta := s_finish
        }
        when(io.jk.ctl.reset === true.B){
          sta := s_idle
          config_i := 0.U
          run := false.B          
        }
      }
      is(s_finish){
        sta := s_idle
        config_i := 0.U
        run := false.B
      }
    }
}
*/