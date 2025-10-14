package boom.v3.lsu

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import boom.v3.common._

class mar(val fifo_log2: Int = 5)(implicit p: Parameters) extends BoomModule {
  val io = IO(new Bundle {
    val mem_access = Input(Bool())
    val addr       = Input(UInt(coreMaxAddrBits.W))
    val is_ld      = Input(Bool())
    val is_st      = Input(Bool())

    val full       = Output(Bool())
  })

  val fifo_depth = 1 << fifo_log2

  val data = Reg(Vec(fifo_depth, UInt(coreMaxAddrBits.W)))
  dontTouch(data)

  // allocator indices
  val wr_idx = RegInit(0.U((fifo_log2 + 1).W))
  
  // level-pulse converter
  val tog_p  = RegNext(wr_idx(fifo_log2), false.B)

  // saves value on every mem_access
  when (io.mem_access) {
    wr_idx                       := wr_idx +% 1.U
    data(wr_idx(fifo_log2-1, 0)) := io.addr
  }
  
  io.full := wr_idx(fifo_log2) ^ tog_p
}
