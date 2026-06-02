package boom.v3.lsu

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import boom.v3.common._

class MemAccessRecord(implicit p: Parameters) extends BoomBundle {
  val pc     = UInt(coreMaxAddrBits.W)   // uop.debug_pc
  val addr   = UInt(coreMaxAddrBits.W)   // req.addr
  val pid    = UInt(32.W)
  val wdata  = UInt(xLen.W)              // req.data (valid for stores/AMOs)
  val isLd   = Bool()
  val isSt   = Bool()
  val isAMO  = Bool()
  val isHella= Bool()
  val robIdx = UInt(robAddrSz.W)
  val ldqIdx = UInt(ldqAddrSz.W)
  val stqIdx = UInt(stqAddrSz.W)
  val time   = UInt(xLen.W)
}

class mar_csr(val fifo_log2: Int = 5)(implicit p: Parameters) extends BoomModule {
  val io = IO(new Bundle {
    val enable      = Input(Bool())
    val csr_data_read = Input(Bool())
    val mem_access  = Input(Bool())
    val mem_record  = Input(new MemAccessRecord)

    val full        = Output(Bool())
    val empty       = Output(Bool())
    val first_addr  = Output(UInt(64.W))
  })

  val fifo_depth = 1 << fifo_log2

  val data = RegInit(VecInit(Seq.fill(fifo_depth)(0.U.asTypeOf(new MemAccessRecord))))
  dontTouch(data)

  // allocator indices
  val wr_idx = RegInit(0.U((fifo_log2 + 1).W))
  val rd_idx = RegInit(0.U((fifo_log2 + 1).W)) 
  
  io.full := (wr_idx(fifo_log2 - 1, 0) === rd_idx(fifo_log2 - 1, 0)) && 
              (wr_idx(fifo_log2) =/= rd_idx(fifo_log2))
  io.empty := (wr_idx === rd_idx)
  val a = true.B
  
  // saves value on every mem_access
  when (io.mem_access && io.enable && !io.full) {
    wr_idx                       := wr_idx +% 1.U
    data(wr_idx(fifo_log2-1, 0)) := io.mem_record
  } 
  // overwrite mechanism (irq does not work if this goes up forever)
  // when (io.mem_access && io.enable && io.full) {
  //   wr_idx                       := wr_idx +% 1.U
  //   rd_idx                       := rd_idx +% 1.U
  //   data(wr_idx(fifo_log2-1, 0)) := io.mem_record
  // } 
  
  // below state machine increments through 3 stages. SW requires this to remain synchronized
  val counter = RegInit(0.U(3.W))

  // stage1 -> addr (64 bit)
  // stage2 -> pc (64 bit)
  // stage3 -> single bit values (4 bit)

  when (io.csr_data_read) {
    when (counter === 4.U) {
      rd_idx := rd_idx +% 1.U
      counter := 0.U
    } .otherwise {
      counter := counter + 1.U
    }
  }
  // if empty -> give back all 0's (detectable from sw and NULL (0) is invalid anyways
  val rec     = data(rd_idx(fifo_log2 - 1, 0))
  val packed  = Cat(rec.isLd, rec.isSt, rec.isAMO, rec.isHella)

  val addr_or_zero   = Mux(io.empty, 0.U(64.W), rec.addr)
  val pc_or_zero     = Mux(io.empty, 0.U(64.W), rec.pc)
  val packed_or_zero = Mux(io.empty, 0.U(64.W), Cat(0.U(60.W), packed))
  val pid_or_zero    = Mux(io.empty, 0.U(64.W), Cat(0.U(32.W), rec.pid))
  val time_or_zero   = Mux(io.empty, 0.U(64.W), rec.time)

  io.first_addr := MuxCase(0.U(64.W), Seq(
    (counter === 0.U) -> addr_or_zero,
    (counter === 1.U) -> pc_or_zero,
    (counter === 2.U) -> packed_or_zero,
    (counter === 3.U) -> pid_or_zero,
    (counter === 4.U) -> time_or_zero
  ))

}

class mar(val fifo_log2: Int = 5)(implicit p: Parameters) extends BoomModule {
  val io = IO(new Bundle {
    val enable      = Input(Bool())
    val mem_access  = Input(Bool())
    val mem_record  = Input(new MemAccessRecord)

    val full        = Output(Bool())
    val empty       = Output(Bool())

    val push        = Input(Bool())
    val push_rec    = Output(new MemAccessRecord)
  })

  val fifo_depth = 1 << fifo_log2

  val data = RegInit(VecInit(Seq.fill(fifo_depth)(0.U.asTypeOf(new MemAccessRecord))))
  dontTouch(data)

  // allocator indices
  val wr_idx = RegInit(0.U((fifo_log2 + 1).W))
  val rd_idx = RegInit(0.U((fifo_log2 + 1).W)) 
  
  io.full := (wr_idx(fifo_log2 - 1, 0) === rd_idx(fifo_log2 - 1, 0)) && 
              (wr_idx(fifo_log2) =/= rd_idx(fifo_log2))
  io.empty := (wr_idx === rd_idx)
  val a = true.B
  
  // saves value on every mem_access
  when (io.mem_access && io.enable && !io.full) {
    wr_idx                       := wr_idx +% 1.U
    data(wr_idx(fifo_log2-1, 0)) := io.mem_record
  } 

  when (io.push) {
    rd_idx := rd_idx +% 1.U
  }

  // if empty -> give back all 0's (detectable from sw and NULL (0) is invalid anyways
  val rec = data(rd_idx(fifo_log2 - 1, 0))
  io.push_rec := rec
}