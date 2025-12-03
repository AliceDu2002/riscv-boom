package boom.v3.lsu

import chisel3._
import chisel3.util._

class RoundRobinArbiter(n: Int) extends Module {
  val io = IO(new Bundle {
    val req        = Input(Vec(n, Bool()))
    val grant      = Output(Vec(n, Bool()))
    val grantIndex = Output(UInt(log2Ceil(n).W))
    val grantValid = Output(Bool())            
  })

  // Register to remember last granted index
  val lastGrant = RegInit(0.U(log2Ceil(n).W))

  // Default all grants to false
  io.grant := VecInit(Seq.fill(n)(false.B))
  io.grantIndex := lastGrant // default (to avoid undefined)
  io.grantValid := false.B

  // Generate a rotated version of requests
  val rotatedReq = Wire(Vec(n, Bool()))
  for (i <- 0 until n) {
    rotatedReq(i) := io.req((i.U + lastGrant + 1.U) % n.U)
  }

  // Priority encoder on rotated requests
  val grantIdxRot = PriorityEncoder(rotatedReq.asUInt)
  val hasReq = io.req.asUInt.orR

  // Decode the rotated grant back to original position
  val realGrantIdx = (grantIdxRot + lastGrant + 1.U) % n.U

  // Create one-hot grant and outputs
  when(hasReq) {
    io.grant(realGrantIdx) := true.B
    io.grantIndex := realGrantIdx
    io.grantValid := true.B
    lastGrant := realGrantIdx
  }
}
