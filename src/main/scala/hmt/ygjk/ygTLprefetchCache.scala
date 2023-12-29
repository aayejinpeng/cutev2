/*
package boom.exu.ygjk

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._

class YgTLpfCache(implicit p: Parameters) extends LazyModule with HasYGTLCacheParams {
  lazy val module = new YgTLpfCacheImp(this)
  val node = TLClientNode(Seq(TLClientPortParameters(Seq(TLClientParameters(
    //TLClientPortParameters(Seq(TLClientParameters( //TLHelper.makeClientNode
    name = "YgDCache",
    sourceId = IdRange(0, 2),
    supportsProbe = TransferSizes(blkBytes, blkBytes)
  )), minLatency = 1)))
}

class YgTLpfCacheMeta(val tagBits: Int) extends Bundle {
  val tag = UInt(tagBits.W)
  val coh = new ClientMetadata
}

class AcquireUnit(id: Int)(implicit edge: TLEdgeOut) extends Module with HasYGTLCacheParams {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new Bundle {
      val addr = UInt(32.W)
      val param = UInt(TLPermissions.aWidth.W)
      val no_data = Bool()
    }))
    val tl_a = Decoupled(new TLBundleA(edge.bundle))
    val dsink = Flipped(Valid(new TLBundleD(edge.bundle).sink))
    val tl_e = Decoupled(new TLBundleE(edge.bundle))
    val wait_time = Output(UInt(32.W))
  })

  val wait_time = RegInit(500.U(32.W))
  val wait_reg = RegInit(0.U(32.W))

  io.wait_time := wait_time

  when(io.tl_a.fire()) {
    assert(io.req.bits.addr(lgBlkBytes - 1, 0) === 0.U)
  }

  val ready :: wait_data :: ack :: Nil = Enum(3)
  val state = RegInit(ready)
  val dsink = RegEnable(io.dsink.bits, io.dsink.valid)
  io.req.ready := io.tl_a.ready && state === ready
  io.tl_a.valid := io.req.valid && state === ready
  io.tl_a.bits := Mux(io.req.bits.no_data,
    edge.AcquirePerm(id.U, io.req.bits.addr, lgBlkBytes.U, io.req.bits.param)._2,
    edge.AcquireBlock(id.U, io.req.bits.addr, lgBlkBytes.U, io.req.bits.param)._2)
  io.tl_e.valid := state === ack
  io.tl_e.bits := edge.GrantAck(dsink) //forward io.dsink ?

  when(io.tl_e.fire()) {
    state := ready
  }.elsewhen(io.dsink.valid) {
    state := ack
  }.elsewhen(io.tl_a.fire()) {
    state := wait_data
  }

  when(state === ready){
    wait_reg := 0.U
  }.elsewhen(state === wait_data){
    wait_reg := wait_reg + 1.U
  }.elsewhen(state === ack){
    when(wait_reg < wait_time){
      wait_time := wait_reg
    }
  }

}

class ReleaseUnit(id: Int)(implicit edge: TLEdgeOut) extends Module with HasYGTLCacheParams {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new Bundle {
      val addr = UInt(32.W)
      val data = UInt((JKDataNum * encDataBits).W)
      // only release when dirty
    }))
    val tl_c = Decoupled(new TLBundleC(edge.bundle))
    val tl_d = Flipped(Valid(new TLBundleD(edge.bundle)))
  })
  val busy = RegInit(false.B)
  io.req.ready := io.tl_c.ready && !busy
  io.tl_c.valid := io.req.valid && !busy
  io.tl_c.bits := edge.Release(id.U, io.req.bits.addr, lgBlkBytes.U, TLPermissions.TtoN, io.req.bits.data)._2

  when(io.tl_c.fire()) {
    busy := true.B
  }.elsewhen(io.tl_d.valid && !edge.isRequest(io.tl_d.bits) && io.tl_d.bits.source === id.U) { //is ReleaseAck
    busy := false.B
  }
}

class YgTLpfCacheImp(outer: YgTLpfCache) extends LazyModuleImp(outer) with HasYGTLCacheParams with MemoryOpConstants {
  implicit val edge = outer.node.edges.out.head
  val (tl_out, _) = outer.node.out.head
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new Bundle {
      val addr = UInt(32.W)
      val data = UInt((JKDataNum * encDataBits).W)
      val cmd = UInt(2.W)
      val size = UInt(3.W) // log2(bytes)
      val mask = UInt(blkBytes.W)
    }))
    val resp = Valid(UInt((JKDataNum * encDataBits).W))

    val hit = Output(Bool())
    val miss = Output(Bool())
    val miss_wait_time = Output(UInt(34.W))
    val pf_wait_time = Output(UInt(32.W))
  })
  val debug = true

  // require(lgBlkBytes == 6)
  def memop(cmd: UInt): UInt = Mux1H(Seq(
    (cmd === 0.U) -> M_XRD,
    (cmd === 1.U) -> M_XWR,
    (cmd === 2.U) -> M_XRD,
    (cmd === 3.U) -> M_PWR,
  ))

  def gettag(addr: UInt): UInt = addr(31, 32 - tagBits)

  def getidx(addr: UInt): UInt = addr(idxBits + lgBlkBytes - 1, lgBlkBytes)

  def getoff(addr: UInt): UInt = addr(lgBlkBytes - 1, 0)

  def assem_addr(tag: UInt, s1_idx: UInt): UInt = Cat(Seq(tag, s1_idx, 0.U(lgBlkBytes.W)))

  def align_addr(addr: UInt): UInt = assem_addr(gettag(addr), getidx(addr))

  def splitdata(data: UInt): Vec[UInt] = VecInit.tabulate(blkBytes) { i => data((i + 1) * 8 - 1, i * 8) }

  def catdata(data: Seq[UInt]): UInt = Cat(data.reverse)

  when(io.req.fire() && io.req.bits.cmd < 2.U) {
    assert(getoff(io.req.bits.addr) + (1.U << io.req.bits.size).asUInt() <= blkBytes.U)
  }

  //data array
  val dataArb = Module(new Arbiter(new Bundle {
    val addr = UInt(idxBits.W)
    val write = Bool()
    val data = UInt((JKDataNum * encDataBits).W)
    val mask = UInt(blkBytes.W)
    val way_en = Vec(nWays, Bool())
  }, 4)) // 0->s2_write 1->s1_read 2->probe 3->refill
  dataArb.io.out.ready := true.B
  val data = Wire(Vec(nWays, UInt((JKDataNum * encDataBits).W)))
  (data zip dataArb.io.out.bits.way_en).foreach { case (out, en) =>
    out := DontCare
    val array = SyncReadMem(nSets, Vec(blkBytes, UInt(8.W)))
    when(dataArb.io.out.valid && en) {
      val arrport = array(dataArb.io.out.bits.addr)
      when(dataArb.io.out.bits.write) {
        for (i <- 0 until blkBytes) when(dataArb.io.out.bits.mask(i)) {
          arrport(i) := splitdata(dataArb.io.out.bits.data)(i)
        }
      }.otherwise {
        out := catdata(arrport)
      }
    }
  }

  //meta array & rst

  val meta_rst = Wire(new YgTLpfCacheMeta(tagBits))
  meta_rst.tag := DontCare
  meta_rst.coh := ClientMetadata.onReset
  val rst_cnt = RegInit(0.U(log2Ceil(nSets + 1).W))
  val rst = rst_cnt < nSets.U
  when(rst) {
    rst_cnt := rst_cnt + 1.U
  }

  val metaReadArb = Module(new Arbiter(UInt(idxBits.W), 4)) //read, probe, refill, prefetch
  val metaWriteArb = Module(new Arbiter(new Bundle {
    val addr = UInt(idxBits.W)
    val data = new YgTLpfCacheMeta(tagBits)
    val way_en = Vec(nWays, Bool())
  }, 3)) // 0->s2_write 1->probe 2->refill
  metaReadArb.io.out.ready := !rst
  metaWriteArb.io.out.ready := !rst
  val meta = VecInit.tabulate(nWays) { i => {
    val meta_array = SyncReadMem(nSets, UInt(meta_rst.getWidth.W))
    when((metaWriteArb.io.out.valid && metaWriteArb.io.out.bits.way_en(i)) || rst) {
      meta_array.write(Mux(rst, rst_cnt, metaWriteArb.io.out.bits.addr).asUInt(), Mux(rst, meta_rst, metaWriteArb.io.out.bits.data).asUInt())
    }
    meta_array.read(metaReadArb.io.out.bits, metaReadArb.io.out.valid).asTypeOf(meta_rst)
  }
  }

  //meta process logic
  val data_valid = meta.map(_.coh.isValid())
  val target_tag = Wire(UInt(tagBits.W))
  val tag_match = meta.map(_.tag === target_tag)
  val way_hit = data_valid & tag_match
  val hit = way_hit.orR


  //tl_out Arbiters
  val aArb = Module(new Arbiter(new TLBundleA(edge.bundle), 2))
  tl_out.a <> aArb.io.out
  val cArb = Module(new Arbiter(new TLBundleC(edge.bundle), 3))
  tl_out.c <> cArb.io.out
  val eArb = Module(new Arbiter(new TLBundleE(edge.bundle), 2))
  tl_out.e <> eArb.io.out

  //states
  val prb_ready :: prb_read :: prb_write :: prb_ack :: Nil = Enum(4)
  val pf_idle :: pf_read :: pf_match :: pf_fire :: Nil = Enum(4)

  val prb_state = RegInit(prb_ready)
  val pf_state = RegInit(pf_idle)
  val s2_busy = RegInit(false.B)
  //  val s2_pf_busy = RegInit(false.B)
  val s3_need_acq = RegInit(false.B)
  //  val s3_pf_busy = RegInit(false.B)
  //  val s4_pf_busy = RegInit(false.B)
  val refill_busy = RegInit(false.B)
  val rel_busy = RegInit(false.B)

  val replacers = Seq.fill(nSets)(new PseudoLRU(nWays))


  //s1: read meta, data
  val s1_idx = getidx(io.req.bits.addr)
  val s1_off = getoff(io.req.bits.addr)

  dataArb.io.in(1).valid := io.req.fire() && isRead(memop(io.req.bits.cmd))
  dataArb.io.in(1).bits.addr := s1_idx
  dataArb.io.in(1).bits.way_en.foreach(_ := true.B)
  dataArb.io.in(1).bits.write := false.B
  dataArb.io.in(1).bits.data := DontCare
  dataArb.io.in(1).bits.mask := DontCare
  metaReadArb.io.in(0).valid := io.req.fire()
  metaReadArb.io.in(0).bits := s1_idx


  //s2: cache hit or miss
  when(io.req.fire()) {
    s2_busy := true.B
  }.otherwise {
    s2_busy := false.B
  }
  val s2_req = RegEnable(io.req.bits, io.req.fire())
  val masks = VecInit.tabulate(7) { i => Seq.tabulate(blkBytes) { j => io.req.bits.mask(j >> i) }.asUInt() }
  val maskLUT = VecInit.tabulate(7)(i => ((BigInt(1) << (1 << i)) - 1).U(blkBytes.W)) //mask = 2^2^log2(size)-1
  when(io.req.fire()) {
    when(io.req.bits.cmd === 1.U) {
      s2_req.mask := maskLUT(io.req.bits.size) << s1_off
    }.elsewhen(io.req.bits.cmd === 3.U) {
      s2_req.mask := masks(io.req.bits.size)
      assert(s1_off === 0.U)
    }.otherwise {
      s2_req.mask := 0.U
    }
    s2_req.data := io.req.bits.data << (s1_off << 3)
  }
  val s2_req_tag = gettag(s2_req.addr)
  val s2_req_idx = getidx(s2_req.addr)

  // val s2_data_valid = meta.map(_.coh.isValid())
  // val s2_tag_match = meta.map(_.tag === s2_req_tag )
  // val s2_way_hit = s2_data_valid & s2_tag_match

  val (s2_coh_hit, s2_acq_par, s2_next_coh) = Mux1H(way_hit, meta.map(_.coh)).onAccess(memop(s2_req.cmd))
  val s2_hit = s2_busy && hit && s2_coh_hit
  val s2_miss = s2_busy && !s2_hit

  io.hit := s2_hit
  io.miss := s2_miss
  //  when(s2_busy && hit){
  //    replacers(s2_req_idx).access(OHToUInt(way_hit))
  //  }
  replacers.zipWithIndex.foreach { case (r, i) =>
    when(s2_req_idx === i.U && s2_busy && hit) {
      r.access(OHToUInt(way_hit))
    }
  }


  dataArb.io.in(0).valid := isWrite(memop(s2_req.cmd)) && s2_hit
  dataArb.io.in(0).bits.addr := s2_req_idx
  dataArb.io.in(0).bits.write := true.B
  dataArb.io.in(0).bits.data := s2_req.data
  dataArb.io.in(0).bits.way_en := way_hit
  dataArb.io.in(0).bits.mask := s2_req.mask
  metaWriteArb.io.in(0).valid := s2_hit
  metaWriteArb.io.in(0).bits.addr := s2_req_idx
  metaWriteArb.io.in(0).bits.data.coh := s2_next_coh
  metaWriteArb.io.in(0).bits.data.tag := s2_req_tag
  metaWriteArb.io.in(0).bits.way_en := way_hit


  //s3: acquire
  val s3_need_acq_next = Wire(Bool())

  val acq_unit = Module(new AcquireUnit(0))
  aArb.io.in(0) <> acq_unit.io.tl_a
  eArb.io.in(0) <> acq_unit.io.tl_e
  //TODO: let refill control this
  acq_unit.io.dsink.valid := tl_out.d.fire() && tl_out.d.bits.source === 0.U && edge.isRequest(tl_out.d.bits)
  acq_unit.io.dsink.bits := tl_out.d.bits.sink
  acq_unit.io.req.valid := s3_need_acq
  acq_unit.io.req.bits.addr := align_addr(s2_req.addr)
  acq_unit.io.req.bits.param := RegEnable(s2_acq_par, s2_miss)
  acq_unit.io.req.bits.no_data := RegEnable(way_hit.orR || s2_req.mask.andR, s2_miss)

  when(s2_miss) {
    s3_need_acq_next := true.B
  }.elsewhen(acq_unit.io.req.fire()) {
    s3_need_acq_next := false.B
  }.otherwise {
    s3_need_acq_next := s3_need_acq
  }
  s3_need_acq := s3_need_acq_next

  io.miss_wait_time := acq_unit.io.wait_time

  //prefetch
  val pf_addr = Reg(UInt(32.W))
  val pf_acq_unit = Module(new AcquireUnit(1))

  io.pf_wait_time := pf_acq_unit.io.wait_time

  metaReadArb.io.in(3).valid := false.B
  metaReadArb.io.in(3).bits := DontCare
  pf_acq_unit.io.req.valid := false.B
  pf_acq_unit.io.req.bits := DontCare
  when(io.req.fire() && gettag(io.req.bits.addr) === gettag(pf_addr)) {
    pf_state := pf_read
    pf_addr := align_addr(io.req.bits.addr + blkBytes.U)
  }.otherwise {
    switch(pf_state) {
      is(pf_idle) {
        when(io.req.fire()) {
          pf_addr := align_addr(io.req.bits.addr + blkBytes.U)
          pf_state := pf_read
        }
      }
      is(pf_read) {
        metaReadArb.io.in(3).valid := true.B
        metaReadArb.io.in(3).bits := getidx(pf_addr)
        when(metaReadArb.io.in(3).fire()) {
          pf_state := pf_match
        }.elsewhen(io.req.fire()) {
          pf_addr := align_addr(io.req.bits.addr + blkBytes.U)
        }
      }
      is(pf_match) {
        when(!hit) {
          pf_state := pf_fire
        }.otherwise {
          pf_state := pf_idle
        }
      }
      is(pf_fire) {
        pf_acq_unit.io.req.valid := true.B
        pf_acq_unit.io.req.bits.addr := pf_addr
        pf_acq_unit.io.req.bits.param := TLPermissions.NtoB
        pf_acq_unit.io.req.bits.no_data := false.B
        when(pf_acq_unit.io.req.fire()) {
          pf_state := pf_idle
        }
      }
    }
  }

  //  //s2_prefetch
  //  val s2_pf_addr = RegEnable(align_addr(io.req.bits.addr + blkBytes.U), io.req.fire())
  //  metaReadArb.io.in(3).valid := s2_pf_busy
  //  metaReadArb.io.in(3).bits := getidx(s2_pf_addr)
  //  when(io.req.fire()){
  //    s2_pf_busy := true.B
  //  }.elsewhen(metaReadArb.io.in(3).fire()){
  //    s2_pf_busy := false.B
  //  }
  //  //s3_prefetch: meta come out
  //  val s3_pf_addr = RegEnable(s2_pf_addr, metaReadArb.io.in(3).fire())
  //  when(metaReadArb.io.in(3).fire()){
  //    s3_pf_busy := true.B
  //  }.otherwise{
  //    s3_pf_busy := false.B
  //  }
  //  //s4_prefetch
  //  val s4_pf_addr = RegEnable(s3_pf_addr, s3_pf_busy)
  aArb.io.in(1) <> pf_acq_unit.io.tl_a
  eArb.io.in(1) <> pf_acq_unit.io.tl_e
  pf_acq_unit.io.dsink.valid := tl_out.d.fire() && tl_out.d.bits.source === 1.U && edge.isRequest(tl_out.d.bits)
  pf_acq_unit.io.dsink.bits := tl_out.d.bits.sink
  //  pf_acq_unit.io.req.valid := s4_pf_busy && ! RegEnable(hit, s3_pf_busy)
  //  pf_acq_unit.io.req.bits.addr := s4_pf_addr
  //  pf_acq_unit.io.req.bits.param := TLPermissions.NtoB
  //  pf_acq_unit.io.req.bits.no_data := false.B
  //  when(s3_pf_busy){
  //    s4_pf_busy := true.B
  //  }.elsewhen(pf_acq_unit.io.req.fire()){
  //    s4_pf_busy := false.B
  //  }

  //refill
  val req_refill = RegEnable(s2_req, acq_unit.io.req.fire())
  val pf_refill_addr = RegEnable(pf_addr, pf_acq_unit.io.req.fire())
  //refill_s1
  when(tl_out.d.valid && edge.isRequest(tl_out.d.bits)) {
    tl_out.d.ready := dataArb.io.in(3).ready && metaReadArb.io.in(2).ready && !refill_busy && !rel_busy
    metaReadArb.io.in(2).valid := tl_out.d.fire()
    dataArb.io.in(3).valid := tl_out.d.fire()
  }.otherwise {
    tl_out.d.ready := true.B
    metaReadArb.io.in(2).valid := false.B
    dataArb.io.in(3).valid := false.B
  }
  metaReadArb.io.in(2).bits := Mux(tl_out.d.bits.source === 0.U, getidx(req_refill.addr), getidx(pf_refill_addr))
  dataArb.io.in(3).bits.addr := Mux(tl_out.d.bits.source === 0.U, getidx(req_refill.addr), getidx(pf_refill_addr))
  dataArb.io.in(3).bits.write := false.B
  dataArb.io.in(3).bits.data := DontCare
  dataArb.io.in(3).bits.mask := DontCare
  dataArb.io.in(3).bits.way_en.foreach(_ := true.B)
  //refill_s2
  when(metaReadArb.io.in(2).fire()) {
    refill_busy := true.B
  }.elsewhen(metaWriteArb.io.in(2).fire()) {
    refill_busy := false.B
  }
  val refill_param = RegEnable(tl_out.d.bits.param, metaReadArb.io.in(2).fire())
  val refill_op = RegEnable(Mux(tl_out.d.bits.source === 0.U, memop(req_refill.cmd), M_XRD), metaReadArb.io.in(2).fire())
  val refill_addr = RegEnable(Mux(tl_out.d.bits.source === 0.U, req_refill.addr, pf_refill_addr), metaReadArb.io.in(2).fire())
  val refill_data = RegEnable(Mux(tl_out.d.bits.source === 0.U,
    catdata((req_refill.mask.asBools() zip splitdata(req_refill.data) zip splitdata(tl_out.d.bits.data)).map {
      case ((m, r), g) => Mux(m, r, g)
    }),
    tl_out.d.bits.data)
    , metaReadArb.io.in(2).fire())
  val refill_mask = RegEnable(Mux(edge.hasData(tl_out.d.bits), -1.S(blkBytes.W).asUInt(), req_refill.mask), metaReadArb.io.in(2).fire())

  //  BUG: prefetch may overwrite require permission block
  //  val replacer = new RandomReplacement(nWays)
  //  when(refill_busy && !hit && data_valid.andR) {
  //    replacer.miss
  //  }
  //  val victim_way = WireDefault(replacer.way)
  val victim_way = WireDefault(VecInit(replacers.map(_.replace))(getidx(refill_addr)))
  for (i <- 0 until nWays) when(!data_valid(i)) {
    victim_way := i.U
  }
  //  when(RegNext(metaReadArb.io.in(2).fire() && tl_out.d.bits.source === 0.U) && !hit && data_valid.andR){
  //    replacers(getidx(refill_addr)).miss
  //  }
  replacers.zipWithIndex.foreach { case (r, i) =>
    when(s2_req_idx === i.U && RegNext(metaReadArb.io.in(2).fire() && tl_out.d.bits.source === 0.U) && !hit && data_valid.andR) {
      r.access(r.replace)
    }
  }

  //  val refill_way_mask_wire = Mux(tag_match.orR, VecInit(tag_match), VecInit(UIntToOH(victim_way, nWays).asBools()))
  val refill_way_mask_wire = Mux(hit, VecInit(way_hit), VecInit(UIntToOH(victim_way, nWays).asBools()))
  val refill_way_mask = Mux(RegNext(metaReadArb.io.in(2).fire()), refill_way_mask_wire, RegEnable(refill_way_mask_wire, RegNext(metaReadArb.io.in(2).fire())))
  when(refill_busy) {
    dataArb.io.in(3).valid := metaWriteArb.io.in(2).ready
    dataArb.io.in(3).bits.addr := getidx(refill_addr)
    dataArb.io.in(3).bits.write := true.B
    dataArb.io.in(3).bits.data := refill_data
    dataArb.io.in(3).bits.mask := refill_mask
    dataArb.io.in(3).bits.way_en := refill_way_mask
  }
  metaWriteArb.io.in(2).valid := refill_busy && dataArb.io.in(3).ready
  metaWriteArb.io.in(2).bits.addr := getidx(refill_addr)
  metaWriteArb.io.in(2).bits.data.tag := gettag(refill_addr)
  metaWriteArb.io.in(2).bits.data.coh := ClientMetadata.onReset.onGrant(refill_op, refill_param)
  metaWriteArb.io.in(2).bits.way_en := refill_way_mask
  //release
  val rel_unit = Seq.tabulate(2) { i => Module(new ReleaseUnit(i)) }
  val rel_data = Reg(UInt((JKDataNum * encDataBits).W))
  val rel_addr = Reg(UInt(32.W))
  when(Mux1H(refill_way_mask, meta).coh.onCacheControl(M_FLUSH)._1 && RegNext(metaReadArb.io.in(2).fire())) {
    rel_busy := true.B
    rel_data := Mux1H(refill_way_mask, data)
    rel_addr := assem_addr(Mux1H(refill_way_mask, meta).tag, getidx(refill_addr))
  }.elsewhen(rel_unit.map(_.io.req.fire()).orR) {
    rel_busy := false.B
  }
  rel_unit.foreach(_.io.req.valid := rel_busy)
  rel_unit.foreach(_.io.req.bits.addr := rel_addr)
  rel_unit.foreach(_.io.req.bits.data := rel_data)
  (cArb.io.in zip rel_unit).foreach { case (in, out) => in <> out.io.tl_c }
  rel_unit.foreach(_.io.tl_d.bits := tl_out.d.bits)
  rel_unit.foreach(_.io.tl_d.valid := tl_out.d.valid)

  //probe
  tl_out.b.ready := prb_state === prb_ready && metaReadArb.io.in(1).ready
  val probe_addr = RegEnable(tl_out.b.bits.address, tl_out.b.fire())
  val probe_param = Reg(UInt(TLPermissions.bdWidth.W))
  val (probe_dirty_wire, proberesp, probe_coh_next_wire) = Mux(hit, Mux1H(way_hit, meta.map(_.coh)), ClientMetadata.onReset).onProbe(probe_param)
  //  val probe_hit = RegEnable(hit, prb_state === prb_read)
  val probe_way = RegEnable(VecInit(way_hit), prb_state === prb_read)
  val probe_data = RegEnable(Mux1H(way_hit, data), prb_state === prb_read)
  val probe_dirty = Reg(Bool())
  val probe_coh_next = RegEnable(probe_coh_next_wire, prb_state === prb_read)
  switch(prb_state) {
    is(prb_ready) {
      when(tl_out.b.fire()) {
        assert(tl_out.b.bits.address =/= align_addr(req_refill.addr) || acq_unit.io.req.ready, "probe req")
        assert(tl_out.b.bits.address =/= pf_refill_addr || pf_acq_unit.io.req.ready, "probe pf")
        prb_state := prb_read
        probe_param := tl_out.b.bits.param
      }
    }
    is(prb_read) {
      probe_param := proberesp
      when(hit) {
        prb_state := prb_write
        probe_dirty := probe_dirty_wire
      }.otherwise {
        prb_state := prb_ack
        probe_dirty := false.B
      }
    }
    is(prb_write) {
      when(metaWriteArb.io.in(1).fire()) {
        prb_state := prb_ack
      }
    }
    is(prb_ack) {
      when(cArb.io.in(2).fire()) {
        prb_state := prb_ready
      }
    }
  }
  metaReadArb.io.in(1).valid := tl_out.b.valid && prb_state === prb_ready
  metaReadArb.io.in(1).bits := getidx(tl_out.b.bits.address)
  dataArb.io.in(2).valid := tl_out.b.valid && prb_state === prb_ready
  dataArb.io.in(2).bits.addr := getidx(tl_out.b.bits.address)
  dataArb.io.in(2).bits.write := false.B
  dataArb.io.in(2).bits.data := DontCare
  dataArb.io.in(2).bits.way_en.foreach(_ := true.B)
  dataArb.io.in(2).bits.mask := DontCare
  metaWriteArb.io.in(1).valid := prb_state === prb_write //&& probe_hit
  metaWriteArb.io.in(1).bits.addr := getidx(probe_addr)
  metaWriteArb.io.in(1).bits.data.coh := probe_coh_next
  metaWriteArb.io.in(1).bits.data.tag := gettag(probe_addr)
  metaWriteArb.io.in(1).bits.way_en := probe_way
  cArb.io.in(2).valid := prb_state === prb_ack
  cArb.io.in(2).bits := Mux(probe_dirty,
    edge.ProbeAck(tl_out.d.bits.source ^ 1.U, probe_addr, 6.U, probe_param, probe_data), //avoid ready check
    edge.ProbeAck(tl_out.d.bits.source ^ 1.U, probe_addr, 6.U, probe_param)
  )

  target_tag := VecInit(s2_req_tag, gettag(probe_addr), gettag(refill_addr), gettag(pf_addr))(RegNext(metaReadArb.io.chosen))

  //  val fix_req = RegInit(false.B)
  //  when(io.resp.valid){
  //    fix_req := false.B
  //  }.elsewhen(io.req.fire()){
  //    fix_req := true.B
  //  }
  io.req.ready := Seq(
    !s3_need_acq_next,
    metaReadArb.io.in(0).ready,
    !refill_busy || getidx(refill_addr) =/= s1_idx,
    dataArb.io.in(1).ready || !isRead(memop(io.req.bits.cmd)),
    align_addr(io.req.bits.addr) =/= probe_addr || prb_state === prb_ack || prb_state === prb_ready,
    align_addr(io.req.bits.addr) =/= pf_refill_addr || pf_acq_unit.io.req.ready,
    align_addr(io.req.bits.addr) =/= align_addr(req_refill.addr) || acq_unit.io.req.ready,
    align_addr(io.req.bits.addr) =/= align_addr(s2_req.addr) || !acq_unit.io.req.fire()
  ).andR
  //      !fix_req &&
  //      metaReadArb.io.in(2).ready &&
  //      !s3_need_acq_next &&
  //      (dataArb.io.in(1).ready || !isRead(memop(io.req.bits.cmd))) &&
  //      (align_addr(io.req.bits.addr) =/= probe_addr || prb_state === prb_ack || prb_state === prb_ready) &&
  //      (align_addr(io.req.bits.addr) =/= pf_refill_addr || pf_acq_unit.io.req.ready) &&
  //      (align_addr(io.req.bits.addr) =/= align_addr(req_refill.addr) || acq_unit.io.req.ready) &&
  //      !(align_addr(io.req.bits.addr) === align_addr(s2_req.addr) && acq_unit.io.req.fire())
  io.resp.valid :=
    //    isWrite(memop(io.req.bits.cmd)) && io.req.fire() ||
    isRead(memop(s2_req.cmd)) && s2_hit ||
      isRead(memop(req_refill.cmd)) && tl_out.d.fire() && edge.isRequest(tl_out.d.bits) && tl_out.d.bits.source === 0.U
  io.resp.bits := Mux(isRead(memop(s2_req.cmd)) && s2_hit,
    Mux1H(way_hit, data) >> (getoff(s2_req.addr) << 3),
    tl_out.d.bits.data >> (getoff(req_refill.addr) << 3))

/*
  if (debug) {
    when(io.req.fire()) {
      printf(p"req: ${io.req.bits}\n")
    }
    when(io.resp.valid) {
      printf(p"resp: ${io.resp.bits}\n")
    }
    when(tl_out.a.fire()) {
      printf(p"tl_a: ${tl_out.a.bits}\n")
    }
    when(tl_out.b.fire()) {
      printf(p"tl_b: ${tl_out.b.bits}\n")
    }
    when(tl_out.c.fire()) {
      printf(p"tl_c: ${tl_out.c.bits}\n")
    }
    when(tl_out.d.fire()) {
      printf(p"tl_d: ${tl_out.d.bits}\n")
    }
    when(tl_out.e.fire()) {
      printf(p"tl_e: ${tl_out.e.bits}\n")
    }
  }
*/
}

*/