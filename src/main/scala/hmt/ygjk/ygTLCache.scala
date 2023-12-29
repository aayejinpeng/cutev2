/*

package boom.exu.ygjk

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tile._
import freechips.rocketchip.util._
import freechips.rocketchip.rocket._
import freechips.rocketchip.rocket.constants.MemoryOpConstants

trait HasYGTLCacheParams extends YGJKParameters{
  def nWays:Int=2
//  def nSets:Int=8
  def nSets:Int=256
  def idxBits:Int=log2Ceil(nSets)
  def tagBits:Int=32-lgBlkBytes-idxBits
  def blkBytes:Int=JKDataNum * encDataBits / 8
  def lgBlkBytes:Int=log2Ceil(blkBytes)
}

class YgTLCache(implicit p: Parameters) extends LazyModule with HasYGTLCacheParams {
  lazy val module = new YgTLCacheImp(this)
  val node = TLClientNode(Seq(TLClientPortParameters(Seq(TLClientParameters( //TLHelper.makeClientNode
    name = "YgTLCache",
    sourceId = IdRange(0, 1),
    supportsProbe = TransferSizes(blkBytes, blkBytes)
  )), minLatency = 1)))
//  val node = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(//TLClientPortParameters(Seq(TLClientParameters( //TLHelper.makeClientNode
//    name = "YgTLCache",
//    sourceId = IdRange(0, 1),
//    supportsProbe = TransferSizes(blkBytes, blkBytes)
//  )), minLatency = 1)))
}

class YgTLCacheMeta(val tagBits: Int) extends Bundle{
  val tag = UInt(tagBits.W)
  val coh = new ClientMetadata
}

class YgTLCacheImp(outer: YgTLCache) extends LazyModuleImp(outer) with HasYGTLCacheParams with MemoryOpConstants{
  val edge = outer.node.edges.out(0)
  val (tl_out, _) = outer.node.out(0)
  val io = IO(new Bundle {
    val ready = Output(Bool())
    val acc_req = Input(new AccReq)
    val resp = Valid(Vec(JKDataNum, Bits(encDataBits.W)))
  })

  val debug = false

  require(lgBlkBytes == 6)

  def gettag(addr: UInt): UInt = {
    require(addr.getWidth == 32,"addr width")
    addr(31,32-tagBits)
  }

  def getidx(addr: UInt): UInt = {
    require(addr.getWidth == 32,"addr width")
    addr(idxBits + lgBlkBytes - 1, lgBlkBytes)
  }

  def assem_addr(tag: UInt, idx: UInt): UInt = {
    require(tag.getWidth == tagBits, "tag width")
    require(idx.getWidth == idxBits, "idx width")
    Cat(Seq(tag, idx, 0.U(lgBlkBytes.W)))
  } 

  val readArb = Module(new Arbiter(UInt(idxBits.W),2))
  val writeArb = Module(new Arbiter(new Bundle{
    val addr = UInt(idxBits.W)
    val data = Vec(JKDataNum, Bits(encDataBits.W))
    val way_en = Vec(nWays, Bool())
  },2))
  readArb.io.out.ready := true.B
  writeArb.io.out.ready := true.B
  val data = VecInit.tabulate(nWays){i => {
    val array = SyncReadMem(nSets, Vec(JKDataNum, Bits(encDataBits.W)))
    when(writeArb.io.out.valid && writeArb.io.out.bits.way_en(i)) {
      array.write(writeArb.io.out.bits.addr, writeArb.io.out.bits.data)
    }
    array.read(readArb.io.out.bits, readArb.io.out.valid)
  }}

  val meta_rst = Wire(new YgTLCacheMeta(tagBits))
  meta_rst.tag := 0.U(tagBits.W)
  meta_rst.coh := ClientMetadata.onReset
  val rst_cnt = RegInit(0.U(log2Ceil(nSets+1).W))
  val rst = rst_cnt < nSets.U
  when (rst) { rst_cnt := rst_cnt+1.U }

  val metaReadArb = Module(new Arbiter(UInt(idxBits.W),2))
  val metaWriteArb = Module(new Arbiter(new Bundle{
    val addr = UInt(idxBits.W)
    val data = new YgTLCacheMeta(tagBits)
    val way_en = Vec(nWays, Bool())
  },3))
  metaReadArb.io.out.ready := !rst
  metaWriteArb.io.out.ready := !rst
  val meta = VecInit.tabulate(nWays){i => {
    val meta_array = SyncReadMem(nSets, UInt(meta_rst.getWidth.W))
    when((metaWriteArb.io.out.valid && metaWriteArb.io.out.bits.way_en(i)) || rst) {
      meta_array.write(Mux(rst, rst_cnt, metaWriteArb.io.out.bits.addr).asUInt, Mux(rst, meta_rst, metaWriteArb.io.out.bits.data).asUInt)
    }
    meta_array.read(metaReadArb.io.out.bits, metaReadArb.io.out.valid).asTypeOf(meta_rst)
  }}


  val acq_ready :: acq_acq :: acq_ack :: Nil = Enum(3)
  val wb_ready :: wb_probe :: wb_release :: wb_read :: wb_write :: Nil = Enum(5)

  val busy = RegInit(false.B)
  val req = Reg(new AccReq)
  val acq_state = RegInit(acq_ready)
  val wb_state = RegInit(wb_ready)


  // val (tag, idx, offset) = Split(io.acc_req.addr, lgBlkBytes, idxBits)
  val idx = getidx(io.acc_req.addr)
  val acc_req_fire = io.acc_req.valid && io.ready
  val release_done = RegInit(true.B)
  val acquire_done = RegInit(true.B)
  val missing = !release_done || !acquire_done
  val done = Wire(Bool())

  readArb.io.in(1).valid := acc_req_fire
  readArb.io.in(1).bits := idx
  metaReadArb.io.in(1).valid := acc_req_fire
  metaReadArb.io.in(1).bits := idx
  when(acc_req_fire){
    req := io.acc_req
    busy := true.B
  }.elsewhen(done){
    busy := false.B
  }

  val req_memop = Mux1H(Seq(
    (req.cmd === 0.U) -> M_XRD,
    (req.cmd === 1.U) -> M_XWR
  ))
  // val (req_tag, req_idx, req_offset) = Split(req.addr, idxBits, lgBlkBytes)
  val req_tag = gettag(req.addr)
  val req_idx = getidx(req.addr)
  val data_valid = meta.map(_.coh.isValid())
  val tag_match = meta.map(_.tag === req_tag )
  val way_hit = data_valid & tag_match
  val (coh_hit, acq_par_wire, next_coh) = Mux1H(way_hit, meta.map(_.coh)).onAccess(req_memop)
  val hit = busy && !missing && way_hit.reduce(_||_) && coh_hit
  val miss = busy && !missing && !hit
  val d_data = Wire(Vec(JKDataNum, Bits(encDataBits.W)))
  d_data.zipWithIndex.foreach {
    case (w, i) => w := tl_out.d.bits.data((i + 1) * encDataBits - 1, i * encDataBits)
  }
  io.resp.valid := isRead(req_memop) && (hit || (tl_out.d.fire() && tl_out.d.bits.opcode === TLMessages.GrantData))
  io.resp.bits := Mux1H(Seq(
    hit -> Mux1H(way_hit, data),
    (tl_out.d.fire() && tl_out.d.bits.opcode === TLMessages.GrantData) -> d_data
  ))
  writeArb.io.in(0).valid := isWrite(req_memop) && hit
  writeArb.io.in(0).bits.addr := req_idx
  writeArb.io.in(0).bits.data := req.data
  writeArb.io.in(0).bits.way_en := way_hit
  metaWriteArb.io.in(0).valid := hit
  metaWriteArb.io.in(0).bits.addr := req_idx
  metaWriteArb.io.in(0).bits.data.coh := next_coh
  metaWriteArb.io.in(0).bits.data.tag := req_tag
  metaWriteArb.io.in(0).bits.way_en := way_hit

  val acq_par_reg = RegEnable(acq_par_wire, miss)
  val acq_param = Mux(missing, acq_par_reg, acq_par_wire)
  val replacer = new RandomReplacement(nWays)
  val victim_way = WireDefault(replacer.way)
  for (i <- 0 until nWays) when(! data_valid(i)) { victim_way := i.U }
  val refill_way = VecInit(UIntToOH(Mux(miss, victim_way, RegEnable(victim_way, miss)), nWays).asBools)
  val d_sink = RegEnable(tl_out.d.bits.sink, metaWriteArb.io.in(1).fire())
  val acq_done_n = Wire(Bool())
  val rel_done_n = Wire(Bool())
  val need_release = data_valid.reduce(_&&_) && ! way_hit.reduce(_||_)
  val (rel_dty_w, rel_par_w, _) = meta(victim_way).coh.onCacheControl(M_FLUSH)
  val release_param = Mux(miss && need_release, rel_par_w, RegEnable(rel_par_w, miss && need_release))
  val release_data = Cat(Mux(miss && need_release && rel_dty_w, data(victim_way), RegEnable(data(victim_way), miss && need_release && rel_dty_w)).reverse)
  val release_dirty = Mux(miss && need_release, rel_dty_w, RegEnable(rel_dty_w, miss && need_release))
  val release_tag = Mux(miss && need_release, meta(victim_way).tag, RegEnable(meta(victim_way).tag, miss && need_release))
  val release_fire = tl_out.c.fire() && (tl_out.c.bits.opcode === TLMessages.Release || tl_out.c.bits.opcode === TLMessages.ReleaseData)
  val probeAck_fire = tl_out.c.fire() && (tl_out.c.bits.opcode === TLMessages.ProbeAck || tl_out.c.bits.opcode === TLMessages.ProbeAckData)
  when(miss){
    replacer.miss
  }
  when(metaWriteArb.io.in(1).fire()){//acquire grant 可能同时发生
    acq_done_n := true.B
  }.elsewhen(miss){
    acq_done_n := false.B
  }.otherwise{
    acq_done_n := acquire_done
  }
  acquire_done := acq_done_n
  when(release_fire){
    rel_done_n := true.B
  }.elsewhen(miss){
    rel_done_n := ! need_release
  }.otherwise{
    rel_done_n := release_done
  }
  release_done := rel_done_n

  when(metaWriteArb.io.in(1).fire()){
    acq_state := acq_ack
  }.elsewhen(tl_out.a.fire()){
    acq_state := acq_acq
  }.elsewhen(tl_out.e.fire()){
    acq_state := acq_ready
  }

  when(probeAck_fire || tl_out.d.fire() && tl_out.d.bits.opcode === TLMessages.ReleaseAck){
    wb_state := wb_ready
  }.elsewhen(release_fire){
    wb_state := wb_release
  }.elsewhen(tl_out.b.fire()){
    wb_state := wb_read
  }.elsewhen(metaWriteArb.io.in(2).fire()){
    wb_state := wb_probe
  }.elsewhen(wb_state === wb_read){
    wb_state := wb_write
  }

  done := hit || (acq_done_n && rel_done_n)
  tl_out.a.valid := acq_state === acq_ready && (miss || !acquire_done)
  tl_out.a.bits := Mux1H(Seq(
    (req.cmd === 0.U) -> edge.AcquireBlock(0.U, req.addr, 6.U, acq_param)._2,
    (req.cmd === 1.U) -> edge.AcquirePerm(0.U, req.addr, 6.U, acq_param)._2
  ))


  writeArb.io.in(1).valid := tl_out.d.valid && (tl_out.d.bits.opcode === TLMessages.Grant || tl_out.d.bits.opcode === TLMessages.GrantData)
  writeArb.io.in(1).bits.addr := req_idx
  writeArb.io.in(1).bits.data := Mux1H(Seq(
    isWrite(req_memop) -> req.data,
    (tl_out.d.bits.opcode === TLMessages.GrantData) -> d_data
  ))
  writeArb.io.in(1).bits.way_en := refill_way
  metaWriteArb.io.in(1).valid := tl_out.d.valid && (tl_out.d.bits.opcode === TLMessages.Grant || tl_out.d.bits.opcode === TLMessages.GrantData)
  metaWriteArb.io.in(1).bits.addr := req_idx
  metaWriteArb.io.in(1).bits.data.coh := ClientMetadata.onReset.onGrant(req_memop, tl_out.d.bits.param)
  metaWriteArb.io.in(1).bits.data.tag := req_tag
  metaWriteArb.io.in(1).bits.way_en := refill_way
  tl_out.d.ready := true.B
  tl_out.e.valid := acq_state === acq_ack
  tl_out.e.bits := edge.GrantAck(d_sink)

  readArb.io.in(0).valid := tl_out.b.valid
  readArb.io.in(0).bits := getidx(tl_out.b.bits.address)
  metaReadArb.io.in(0).valid := tl_out.b.valid
  metaReadArb.io.in(0).bits := getidx(tl_out.b.bits.address)
  val probe_addr = RegEnable(tl_out.b.bits.address, tl_out.b.fire())
  val probe_param = Reg(UInt(TLPermissions.bdWidth.W))
  val probe_tag_hit = VecInit(meta.map(_.tag === gettag(probe_addr)))//share MUX with W/R?
  val (probe_dirty_wire, proberesp, probe_coh_next_wire) = Mux1H(probe_tag_hit, meta.map(_.coh)).onProbe(probe_param)
  val probe_data_wire = Mux1H(probe_tag_hit, data)
  val probe_way = RegEnable(probe_tag_hit, wb_state === wb_read)
  val probe_data = Cat(RegEnable(probe_data_wire, wb_state === wb_read).reverse)
  val probe_dirty = RegEnable(probe_dirty_wire, wb_state === wb_read)
  val probe_coh_next = RegEnable(probe_coh_next_wire, wb_state === wb_read)
  when(tl_out.b.fire()){
    probe_param := tl_out.b.bits.param
  }.elsewhen(wb_state === wb_read){
    probe_param := proberesp
  }
  when(wb_state === wb_read){
    probe_way := probe_tag_hit
  }
  metaWriteArb.io.in(2).valid := wb_state === wb_write
  metaWriteArb.io.in(2).bits.addr := getidx(probe_addr)
  metaWriteArb.io.in(2).bits.data.coh := probe_coh_next
  metaWriteArb.io.in(2).bits.data.tag := gettag(probe_addr)
  metaWriteArb.io.in(2).bits.way_en := probe_way


  tl_out.c.valid := wb_state === wb_ready && ! tl_out.b.fire() && (miss && need_release || !release_done) || wb_state === wb_probe
  tl_out.c.bits := Mux(wb_state === wb_ready,
    Mux(release_dirty,
      edge.Release(0.U, assem_addr(release_tag, req_idx), 6.U, release_param, release_data)._2,
      edge.Release(0.U, assem_addr(release_tag, req_idx), 6.U, release_param)._2
    ),
    Mux(probe_dirty,
      edge.ProbeAck(0.U, probe_addr, 6.U, probe_param, probe_data),
      edge.ProbeAck(0.U, probe_addr, 6.U, probe_param)
    )
  )
  tl_out.b.ready := wb_state === wb_ready
  
  io.ready := (!busy || done) && readArb.io.in(1).ready && metaReadArb.io.in(1).ready && (io.acc_req.addr =/= probe_addr || wb_state === wb_probe || wb_state === wb_ready)


  if(debug){
    when(tl_out.b.valid){
      assert(tl_out.b.bits.opcode === TLMessages.Probe, "ygcache: Channel B not probe")
      assert(tl_out.b.bits.size === 6.U, "probe size")
    }
    when(wb_state === wb_read){
      assert(probe_tag_hit.reduce(_||_), "probe block not exist")
    }
    when(tl_out.d.valid && tl_out.d.bits.opcode =/= TLMessages.ReleaseAck){
      assert(! tl_out.d.bits.denied, "ygcache: L2 Cache denied")
      assert(! tl_out.d.bits.corrupt, "ygcache: L2 Cache corrupt")
    }
    when(tl_out.d.fire() && tl_out.d.bits.opcode =/= TLMessages.ReleaseAck){
      assert(metaWriteArb.io.in(1).ready, "ygcache: meta write not ready on Grant")
      assert(writeArb.io.in(1).ready, "ygcache: array write not ready on Grant")
      assert(isWrite(req_memop) || tl_out.d.bits.opcode === TLMessages.GrantData, "ygcache: no data on Grant")
    }
    val cnt = RegInit(0.U(8.W))
    val cnt_msg = p"counter = ${cnt} \n"
    when(acc_req_fire){
      cnt := 0.U
    }.elsewhen(done){
      cnt := 0.U
    }.elsewhen(cnt < 255.U){
      cnt := cnt + 1.U
    }

    val rej_cnt = RegInit(0.U(8.W))
    when(io.ready){
      rej_cnt := 0.U
    }.elsewhen(io.acc_req.valid && rej_cnt < 255.U){
      rej_cnt := rej_cnt + 1.U
    }

    when(rej_cnt === 254.U){
      printf(p"reject too long:\n rst: ${rst} busy:${busy} done:${done} Arbs:${readArb.io.in(1).ready} ${metaReadArb.io.in(1).ready} ")
    }

    when(acc_req_fire){
      printf(p" req:\n addr = ${io.acc_req.addr} \n cmd = ${io.acc_req.cmd} \n" + cnt_msg)
    }
    when(miss){
      printf(p" miss:" + cnt_msg)
    }
    when(hit){
      printf(p"hit:" + cnt_msg)
    }
    when(tl_out.a.fire()){
      printf(p" A fire:\n ${tl_out.a.bits}" + cnt_msg)
    }
    when(tl_out.b.fire()){
      printf(p" B fire:\n ${tl_out.b.bits}" + cnt_msg)
    }
    when(tl_out.c.fire()){
      printf(p" C fire:\n ${tl_out.c.bits}" + cnt_msg)
    }
    when(tl_out.d.fire()){
      printf(p" D fire:\n ${tl_out.d.bits}" + cnt_msg)
    }
    when(tl_out.e.fire()){
      printf(p" E fire:\n ${tl_out.e.bits}" + cnt_msg)
    }
    when(cnt === 254.U){
      printf(p"DEAD: \n busy: ${busy}\n missing:${missing}\n release_done:${release_done}\n acquire_done:${acquire_done}\n wb_state:${wb_state}\n acq_state:${acq_state}\n ")
    }
  }
}

*/