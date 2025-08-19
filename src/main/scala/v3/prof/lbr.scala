package boom.v3.prof

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.util._

import boom.v3.common._
import boom.v3.util._
import boom.v3.exu.{CommitSignals, BrResolutionInfo}

// TODO: Make sure compiles without LBR (should be good now)
// TODO: Return? --> Return gets translated to jalr, so it should be fine.
// TODO: Handle mispredictions --> Forward how the the bracnh was predicted into the LBR?
// TODO: Abstract LBR class
// TODO: Handle exceptions? Or other control flow pseudo instructions (not super performance critical right now)
// TODO: Remove valid from the registers --> no valid signal should just have address zero, software can filter out
// TODO: How can we move this around different stages in the pipeleine?
// TODO: How to interact with events? 
// TODO: Right now the way we get the address is from debug_pc, wich is not synthesizable. In future we have to reconstruct the PC


// TODO: How to emulate something like PEBS how should this interact with ?

class LBREntry(implicit p: Parameters) extends BoomBundle {
  // val from = UInt(vaddrBitsExtended.W)
  // val to = UInt(vaddrBitsExtended.W)
  val from = UInt(vaddrBitsExtended.W)
  val to = UInt(vaddrBitsExtended.W)
  val m = Bool() // Was this mispredicted?
}

class LBRIo(implicit p: Parameters) extends BoomBundle {
  val commit = Input(new CommitSignals())
  val lbr_entries = Output(Vec(nLBREntries, new LBREntry()))
}

class LBR(implicit p: Parameters) extends BoomModule {
  val io = IO(new LBRIo())

  // Check all LBR signals and filter out all retired uops
  val is_retiring = VecInit((0 until retireWidth).map { i =>
    io.commit.valids(i)
  })

  // Always store the last uop that is retiring. 
  val last_uop = RegInit(0.U.asTypeOf(new MicroOp()))

  when (is_retiring.asUInt =/= 0.U) {
    last_uop := PriorityMux(is_retiring, io.commit.uops)
  }

  val commitPairs: Seq[(MicroOp, Bool)] =
    io.commit.uops.zip(io.commit.valids)

  val uops: Seq[(MicroOp, Bool)] = (last_uop, true.B) +: commitPairs

  val is_first_cfi: Seq[Bool] = uops
  .dropRight(1)
  .map { case (u, v) =>
    v && ((u.is_br && u.taken) || u.is_jal || u.is_jalr) // How to handle sfb?
  }

  val is_new_lbr_entry : Seq[Bool]  = 
    is_first_cfi.zipWithIndex.map { case (cfi, i) =>
      cfi && uops(i+1)._2
    }
  
  
  val rawNew = VecInit((0 until retireWidth).map { i =>
    val v = Wire(Valid(new LBREntry()))
    when (is_new_lbr_entry(i)) {
      v.valid := true.B
      v.bits.from := uops(i)._1.debug_pc
      v.bits.to   := uops(i+1)._1.debug_pc
      v.bits.m    := false.B  // TODO: hook up mispredict
    } .otherwise {
      v.valid := false.B
      v.bits  := DontCare
    }
    v
  })

  val entries = RegInit(
    VecInit.fill(nLBREntries) { WireInit((0.U).asTypeOf(Valid(new LBREntry())))  }
  )

  val nNew = PopCount(rawNew.map(_.valid))
  val entriesNext = WireInit(
    VecInit.fill(nLBREntries) { 0.U.asTypeOf(Valid(new LBREntry)) }
  )

  val idxLast = Wire(Vec(nLBREntries, UInt(log2Ceil(nLBREntries).W)))
  for (i <- 0 until nLBREntries) {
    idxLast(i) := i.U - nNew // We do not need to wrap around,
  }

  for(i <- 0 until retireWidth) {
    when (i.U < nNew) {
      entriesNext(i) := rawNew(i)
    } .otherwise {
      entriesNext(i) := entries(idxLast(i))
    }
  }

  for (i <- retireWidth until nLBREntries) {
      entriesNext(i) := entries(idxLast(i))
  }


  when (nNew =/= 0.U) {
    entries := entriesNext
  }

  io.lbr_entries := entries.map(_.bits)

  dontTouch(entries)
  override def toString: String = BoomCoreStringPrefix(
    "==LBR==",
    "LBR Entries        : " + nLBREntries,
    "LBR entry width   : " + new LBREntry().getWidth + " bits"
  )
}
