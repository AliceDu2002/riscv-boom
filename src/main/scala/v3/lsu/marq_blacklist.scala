package boom.v3.lsu

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import boom.v3.common._

/** Hit-only CAM that stores dynamic addresses plus two programmable fixed entries.
  * depth = 1 << fifo_log2. Matches are exact on addr.
  * Writing a fixed entry sets its internal valid bit.
  */
class marq_blacklist(val fifo_log2: Int = 2, val nMem: Int = 1)(implicit p: Parameters) extends BoomModule {
  val io = IO(new Bundle {
    // global controls
    val clear        = Input(Bool())

    // dynamic entries (FIFO-like)
    val enq          = Input(UInt(coreMaxAddrBits.W))
    val enq_valid    = Input(Bool())

    // two fixed entries; one WE per entry (sets addr and marks valid=1)
    val fixed0_we        = Input(Bool())
    val fixed0_addr_in   = Input(UInt(coreMaxAddrBits.W))

    val fixed1_we        = Input(Bool())
    val fixed1_addr_in   = Input(UInt(coreMaxAddrBits.W))

    // lookup
    val lookup_addr = Input(Vec(nMem, UInt(coreMaxAddrBits.W)))
    val blacklist   = Output(Vec(nMem, Bool()))
  })

  val depth = 1 << fifo_log2
  val ptrW  = fifo_log2

  // dynamic storage + valids
  val mem   = Reg(Vec(depth, UInt(coreMaxAddrBits.W)))
  val valid = RegInit(VecInit(Seq.fill(depth)(false.B)))

  // write pointer (wraps)
  val wptr  = RegInit(0.U(ptrW.W))
  val widx  = wptr

  // two programmable fixed entries w/ internal valid bits
  val fixed0_addr = RegInit(0.U(coreMaxAddrBits.W))
  val fixed0_val  = RegInit(false.B)
  val fixed1_addr = RegInit(0.U(coreMaxAddrBits.W))
  val fixed1_val  = RegInit(false.B)

  // clear (sync)
  when (io.clear) {
    for (i <- 0 until depth) { valid(i) := false.B }
    wptr        := 0.U
    fixed0_addr := 0.U; fixed0_val := false.B
    fixed1_addr := 0.U; fixed1_val := false.B
  }

  // load/update fixed entries (WE sets addr and marks valid)
  when (io.fixed0_we) { fixed0_addr := io.fixed0_addr_in; fixed0_val := true.B }
  when (io.fixed1_we) { fixed1_addr := io.fixed1_addr_in; fixed1_val := true.B }

  // enqueue dynamic entry
  when (io.enq_valid) {
    mem(widx)   := io.enq
    valid(widx) := true.B
    wptr        := wptr +% 1.U
  }

  // For each lookup port
  for (p <- 0 until nMem) {
    // CAM compare across dynamic entries
    val matchesDyn = VecInit((0 until depth).map(i => valid(i) && (mem(i) === io.lookup_addr(p))))
    val camHit     = matchesDyn.asUInt.orR

    // fixed compares (only if valid)
    val fixedHit0  = fixed0_val && (io.lookup_addr(p) === fixed0_addr)
    val fixedHit1  = fixed1_val && (io.lookup_addr(p) === fixed1_addr)

    // final result for this port
    io.blacklist(p) := camHit || fixedHit0 || fixedHit1
  }

}
