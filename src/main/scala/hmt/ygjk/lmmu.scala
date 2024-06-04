
package boom.exu.ygjk

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config._
import boom.common._ 

trait LMMUParameters{
	val entry = 64
}

class CoreIO(implicit p: Parameters) extends BoomBundle{
	val useVM_v = Input(Bool())
	val useVM = Input(Bool())
	val refill_v = Input(Bool())
	val refillVaddr = Input(UInt(vpnBits.W))
	val refillPaddr = Input(UInt(ppnBits.W))
}

class LMMUReq(implicit p: Parameters) extends BoomBundle{
	val vaddr0 = Input(UInt(vaddrBits.W))
	val vaddr0_v = Input(Bool())
	val vaddr1 = Input(UInt(vaddrBits.W))
	val vaddr1_v = Input(Bool())
}

class LMMUResp(implicit p: Parameters) extends BoomBundle{
	val paddr0 = Output(UInt(paddrBits.W))
	val paddr1 = Output(UInt(paddrBits.W))
	val paddr0_v = Output(Bool())
	val paddr1_v = Output(Bool())
	val miss = Output(Bool())
	val missAddr = Output(UInt(vaddrBits.W))

}

class LMMUIO(implicit p: Parameters) extends BoomBundle{
	val core = new CoreIO
	val req  = new LMMUReq
	val resp = new LMMUResp
    val paddr0 = Output(UInt(64.W))
	val paddr1 = Output(UInt(64.W))
}

class LMMU(implicit p: Parameters) extends BoomModule with LMMUParameters{
	val io = IO(new LMMUIO)

//	printf(p"vaddrBitsExtended $vaddrBitsExtended paddrBits $paddrBits vaddrBits $vaddrBits vpnBits $vpnBits ppnBits $ppnBits\n")
//	printf(p"io.req ${Hexadecimal(io.req.vaddr0)} ${io.req.vaddr0_v} ${Hexadecimal(io.req.vaddr1)} ${io.req.vaddr1_v}\n")
//	printf(p"io.resp ${Hexadecimal(io.resp.paddr0)} ${io.resp.paddr0_v} ${Hexadecimal(io.resp.paddr1)} ${io.resp.paddr1_v} ${io.resp.miss}\n")


	val VTable = RegInit(VecInit(Seq.fill(entry)(0.U(vaddrBits.W))))
	val PTable = RegInit(VecInit(Seq.fill(entry)(0.U(paddrBits.W))))
	val VMusing = RegInit(false.B)

	io.paddr0 := PTable(0)
	io.paddr1 := PTable(1)


	//vaddr -> paddr
	io.resp.paddr0 := io.req.vaddr0(paddrBits - 1 , 0)
	io.resp.paddr1 := io.req.vaddr1(paddrBits - 1 , 0)

	val hit0 = Wire(Vec(entry,Bool()))
	val hit1 = Wire(Vec(entry,Bool()))
	for(i <- 0 until entry){
		hit0(i) := (io.req.vaddr0(vaddrBits-1, pgIdxBits) === VTable(i)) & io.req.vaddr0_v
		when((io.req.vaddr0(vaddrBits-1, pgIdxBits) === VTable(i)) & io.req.vaddr0_v & VMusing){
			io.resp.paddr0 := Cat(PTable(i)(19,0),io.req.vaddr0(pgIdxBits - 1 , 0))
		}

		hit1(i) := (io.req.vaddr1(vaddrBits-1, pgIdxBits) === VTable(i)) & io.req.vaddr1_v
		when((io.req.vaddr1(vaddrBits-1, pgIdxBits) === VTable(i)) & io.req.vaddr1_v & VMusing){
			io.resp.paddr1 := Cat(PTable(i)(19,0),io.req.vaddr1(pgIdxBits - 1 , 0))
		}
	}

	io.resp.miss := ((!hit0.reduce(_|_) & io.req.vaddr0_v) | (!hit1.reduce(_|_) & io.req.vaddr1_v)) & VMusing
	io.resp.paddr0_v := io.req.vaddr0_v & !io.resp.miss
	io.resp.paddr1_v := io.req.vaddr1_v & !io.resp.miss



	when(!hit0.reduce(_|_) & io.req.vaddr0_v & VMusing){
		io.resp.missAddr := io.req.vaddr0
	}.otherwise{
		io.resp.missAddr := io.req.vaddr1
	}

	when(io.resp.miss){
		printf(p"io.resp.missAddr ${io.resp.missAddr}\n")
	}

	//CPU config Local MMU
	val count = RegInit(0.U(log2Ceil(entry).W))
 	when(io.core.useVM_v){
 		VMusing := io.core.useVM
 	}
//        val flag_w = RegInit(false.B)
//        val v_w = RegInit(0.U(vaddrBits.W))
//        val p_w = RegInit(0.U(paddrBits.W))
//        val wait_w = RegInit(4095.U(12.W))

    when(io.core.refill_v){
 		VTable(count) := io.core.refillVaddr
 		PTable(count) := io.core.refillPaddr
 		when(count === (entry -1).U){
 			count := 0.U
 		}.otherwise{
 			count := count + 1.U
 		}
 		printf(p"io.core.refillVaddr ${io.core.refillVaddr} io.core.refillPaddr ${io.core.refillPaddr}\n")
 	}

/*
      when(io.core.refill_v){
                v_w := io.core.refillVaddr
                p_w := io.core.refillPaddr
                flag_w := true.B
                when(count === (entry -1).U){
                        count := 0.U
                }.otherwise{
                        count := count + 1.U
                }
        }
        when(flag_w){
          wait_w := wait_w - 1.U
          when(wait_w === 0.U){
            VTable(count) := v_w
            PTable(count) := p_w
            wait_w := 4095.U
            flag_w := false.B
          }
        }
*/
}
