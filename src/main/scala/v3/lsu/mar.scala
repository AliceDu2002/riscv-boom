package boom.v3.lsu

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import boom.v3.common._

class MemAccessRecord(implicit p: Parameters) extends BoomBundle {
  val pc     = UInt(coreMaxAddrBits.W)   // uop.debug_pc
  val addr   = UInt(coreMaxAddrBits.W)   // req.addr
  val wdata  = UInt(xLen.W)              // req.data (valid for stores/AMOs)
  val isLd   = Bool()
  val isSt   = Bool()
  val isAMO  = Bool()
  val isHella= Bool()
  val robIdx = UInt(robAddrSz.W)
  val ldqIdx = UInt(ldqAddrSz.W)
  val stqIdx = UInt(stqAddrSz.W)
}

class mar(val fifo_log2: Int = 5)(implicit p: Parameters) extends BoomModule {
  val io = IO(new Bundle {
    val enable     = Input(Bool())
    val mem_access = Input(Bool())
    val mem_record = Input(new MemAccessRecord)

    val first_addr  = Output(UInt(coreMaxAddrBits.W))
    val full       = Output(Bool())
  })

  val fifo_depth = 1 << fifo_log2

  val data = Reg(Vec(fifo_depth, UInt(coreMaxAddrBits.W)))

  val rd_idx = RegInit(0.U((fifo_log2 + 1).W))
  val first_addr = data(rd_idx(fifo_log2-1, 0))
  io.first_addr := first_addr
  dontTouch(data)

  // allocator indices
  val wr_idx = RegInit(0.U((fifo_log2 + 1).W))
  
  // level-pulse converter
  val tog_p  = RegNext(wr_idx(fifo_log2), false.B)

  // saves value on every mem_access
  when (io.mem_access && io.enable) {
    wr_idx                       := wr_idx +% 1.U
    data(wr_idx(fifo_log2-1, 0)) := io.mem_record
  }
  
  io.full := wr_idx(fifo_log2) ^ tog_p
}
