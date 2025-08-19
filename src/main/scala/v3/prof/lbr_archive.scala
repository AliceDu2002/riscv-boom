// package boom.v3.prof


// import chisel3._
// import chisel3.util._

// import org.chipsalliance.cde.config.Parameters
// import freechips.rocketchip.util._



// import boom.v3.common._
// import boom.v3.util._
// import boom.v3.exu.{CommitSignals, BrResolutionInfo}




// class LbrEntry(implicit p: Parameters) extends BoomBundle {
//   val target = UInt(vaddrBitsExtended.W)
// }


// class LBRIo(implicit p: Parameters) extends BoomBundle {
//     // Maybe commit signals are overkill, but for now this be enough information for LBR
//     val commit = Input(new CommitSignals())
//     val brinfos = Input(Vec(coreWidth, new LBRBranchResolutionInfo()))
//     // val ifu =  new boom.v3.ifu.BoomFrontendIO
// }

// class LBRBranchResolutionInfo(implicit p: Parameters) extends BoomBundle {
//     val uop        = new MicroOp
//     val valid      = Bool()
//     val taken      = Bool()                     // which direction did the branch go?
//     val jalr_target = UInt(vaddrBitsExtended.W)
//     val target_offset = SInt(21.W) // TODO: Is this the right width? Not set in BrResolutionInfo
// }

// class LBR(implicit p : Parameters) extends BoomModule {
//     val io = IO(new LBRIo())
//     // Map of RoB entries to BranchResoltuionInfos
//     val robBrInfoMap = RegInit(
//         VecInit(Seq.fill(numRobEntries) {0.U.asTypeOf(new LBRBranchResolutionInfo())})
//     )

//     // Bank
//     val head = RegInit(0.U(log2Ceil(nLBREntries).W))
//     val entries = RegInit(VecInit(Seq.fill(nLBREntries) {0.U.asTypeOf(new LbrEntry())}))
//     val valids = RegInit(0.U(nLBREntries.W)) // valid bits for each entry


//     // Update Br Resolution Infos
//     for (i <- 0 until coreWidth) {
//         when(io.brinfos(i).valid) {
//             val rob_idx = io.brinfos(i).uop.rob_idx
//             robBrInfoMap(rob_idx) := io.brinfos(i)
//         }
//     }

//     // val block_pc = AlignPCToBoundary(io.ifu.get_pc(1).pc, icBlockBytes)
    
//     /**  
//      * Compute the full branch/JAL target:  
//      *   reconstructed PC + target_offset + (edge_inst?2:4 adjustment)  
//      *
//      *  @param uopPc    the reconstructed full PC of the uop (UInt)  
//      *  @param tgtOff   the signed target_offset from the predictor or ROB (SInt)  
//      *  @param edgeInst whether this was an RVC‐boundary inst (Bool)  
//      *  @return         the full target address (UInt)  
//      */
//     def computeCfiTarget(
//     uopPc:   UInt,
//     tgtOff:  SInt,
//     edgeInst: Bool
//     ): UInt = {
//         val edgeAdj = (Fill(vaddrBitsExtended-1, edgeInst) << 1).asSInt
//         (uopPc.asSInt + tgtOff + edgeAdj).asUInt
//     }


//     // We write an LBR entry only when the instruction is actually retired. We use the rob_idx in the uop to match branch info from the execution units to match with each retired instruction. We only need to track retired instructions and not every valid signal. 


//     // Safety guarentee: We only ever write LBR entries for isntructions that are retired. 

//     // Handwavey proof: 
//     // Assume we execute CFI uop with rob_idx X and store its br_info in robBrInfoMap(X). If the CFI uop is flushed before it can retire, robBrInfoMap(X) will persist stale results. 

//     // Lets assume another uop with rob_idx Y is then executed. If Y is a CFI uop, it will overwrite robBrInfoMap(Y) with its own br_info. If Y is not a CFI uop, then no LBR entry is every written, and the stale robBrInfoMap(X) will not get written into the LBR.

//     val isCfi = VecInit((0 until retireWidth).map { i =>
//     val u = io.commit.uops(i)
//     io.commit.valids(i) && (u.is_br || u.is_jal || u.is_jalr)
//     })

//     val totalCfIs = PopCount(isCfi) // hardware UInt

//     for (i <- 0 until retireWidth) {
//         when (isCfi(i)) {
//             val thisOffset = PopCount(isCfi.take(i)) 
//             val writeIdx   = (head + thisOffset) % nLBREntries.U

//             val u = io.commit.uops(i)
//             val uop_pc =  u.pc_lob  // Todo: For now we only have the lower part of the PC. We need to somehow ensure we can reconstruct the entire thing?

//             when (u.is_br || u.is_jal) {
//             entries(writeIdx).target :=
//                 computeCfiTarget(uop_pc, robBrInfoMap(u.rob_idx).target_offset, u.edge_inst)
//             // valids(writeIdx) := true.B
//             }.elsewhen (u.is_jalr) {
//             entries(writeIdx).target := robBrInfoMap(u.rob_idx).jalr_target
//             // valids(writeIdx) := true.B
//             }
//         }
//     }
//     head := (head + totalCfIs) % nLBREntries.U

//     dontTouch(entries)
//     dontTouch(valids)

//     override def toString: String = BoomCoreStringPrefix(
//     "==LBR==",
//     "LBR Entries        : " + nLBREntries,
//     "LBR entry width   : "  + new LbrEntry().getWidth + " bits"
//     )
// }