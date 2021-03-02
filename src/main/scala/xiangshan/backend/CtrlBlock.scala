package xiangshan.backend

import chisel3._
import chisel3.util._
import utils._
import xiangshan._
import xiangshan.backend.decode.{DecodeStage, ImmUnion, WaitTableParameters}
import xiangshan.backend.rename.{BusyTable, Rename}
import xiangshan.backend.dispatch.Dispatch
import xiangshan.backend.exu._
import xiangshan.backend.exu.Exu.exuConfigs
import xiangshan.backend.ftq.{Ftq, FtqRead, GetPcByFtq}
import xiangshan.backend.regfile.RfReadPort
import xiangshan.backend.roq.{Roq, RoqCSRIO, RoqLsqIO, RoqPtr}
import xiangshan.mem.LsqEnqIO

class CtrlToIntBlockIO extends XSBundle {
  val enqIqCtrl = Vec(exuParameters.IntExuCnt, DecoupledIO(new MicroOp))
  val readRf = Vec(NRIntReadPorts, Output(UInt(PhyRegIdxWidth.W)))
  val jumpPc = Output(UInt(VAddrBits.W))
  val jalr_target = Output(UInt(VAddrBits.W))
  // int block only uses port 0~7
  val readPortIndex = Vec(exuParameters.IntExuCnt, Output(UInt(log2Ceil(8 / 2).W))) // TODO parameterize 8 here
  val redirect = ValidIO(new Redirect)
  val flush = Output(Bool())
}

class CtrlToFpBlockIO extends XSBundle {
  val enqIqCtrl = Vec(exuParameters.FpExuCnt, DecoupledIO(new MicroOp))
  val readRf = Vec(NRFpReadPorts, Output(UInt(PhyRegIdxWidth.W)))
  // fp block uses port 0~11
  val readPortIndex = Vec(exuParameters.FpExuCnt, Output(UInt(log2Ceil((NRFpReadPorts - exuParameters.StuCnt) / 3).W)))
  val redirect = ValidIO(new Redirect)
  val flush = Output(Bool())
}

class CtrlToLsBlockIO extends XSBundle {
  val enqIqCtrl = Vec(exuParameters.LsExuCnt, DecoupledIO(new MicroOp))
  val enqLsq = Flipped(new LsqEnqIO)
  val waitTableUpdate = Vec(StorePipelineWidth, Input(new WaitTableUpdateReq))
  val redirect = ValidIO(new Redirect)
  val flush = Output(Bool())
}

class RedirectGenerator extends XSModule with HasCircularQueuePtrHelper with WaitTableParameters {
  val numRedirect = exuParameters.JmpCnt + exuParameters.AluCnt
  val io = IO(new Bundle() {
    val exuMispredict = Vec(numRedirect, Flipped(ValidIO(new ExuOutput)))
    val loadReplay = Flipped(ValidIO(new Redirect))
    val flush = Input(Bool())
    val stage1FtqRead = Vec(numRedirect + 1, new FtqRead)
    val stage2FtqRead = new FtqRead
    val stage2Redirect = ValidIO(new Redirect)
    val stage3Redirect = ValidIO(new Redirect)
    val waitTableUpdate = Output(new WaitTableUpdateReq) 
  })
  /*
        LoadQueue  Jump  ALU0  ALU1  ALU2  ALU3   exception    Stage1
          |         |      |    |     |     |         |
          |============= reg & compare =====|         |       ========
                            |                         |
                            |                         |
                            |                         |        Stage2
                            |                         |
                    redirect (flush backend)          |
                    |                                 |
               === reg ===                            |       ========
                    |                                 |
                    |----- mux (exception first) -----|        Stage3
                            |
                redirect (send to frontend)
   */
  private class Wrapper(val n: Int) extends Bundle {
    val redirect = new Redirect
    val valid = Bool()
    val idx = UInt(log2Up(n).W)
  }
  def selectOldestRedirect(xs: Seq[Valid[Redirect]]): (Valid[Redirect], UInt) = {
    val wrappers = for((r, i) <- xs.zipWithIndex) yield {
      val wrap = Wire(new Wrapper(xs.size))
      wrap.redirect := r.bits
      wrap.valid := r.valid
      wrap.idx := i.U
      wrap
    }
    val oldest = ParallelOperation[Wrapper](wrappers, (x, y) => {
      Mux(x.valid,
        Mux(y.valid, Mux(isAfter(x.redirect.roqIdx, y.redirect.roqIdx), y, x), x), y
      )
    })
    val result = Wire(Valid(new Redirect))
    result.valid := oldest.valid
    result.bits := oldest.redirect
    (result, oldest.idx)
  }

  for((ptr, redirect) <- io.stage1FtqRead.map(_.ptr).zip(
    io.exuMispredict.map(_.bits.redirect) :+ io.loadReplay.bits
  )){ ptr := redirect.ftqIdx }

  def getRedirect(exuOut: Valid[ExuOutput]): ValidIO[Redirect] = {
    val redirect = Wire(Valid(new Redirect))
    redirect.valid := exuOut.valid && exuOut.bits.redirect.cfiUpdate.isMisPred
    redirect.bits := exuOut.bits.redirect
    redirect
  }

  val jumpOut = io.exuMispredict.head
  val aluOut = VecInit(io.exuMispredict.tail)
  val (oldestAluRedirect, oldestAluIdx) = selectOldestRedirect(aluOut.map(getRedirect))
  val (oldestExuRedirect, jumpIsOlder) = selectOldestRedirect(Seq(
    oldestAluRedirect, getRedirect(jumpOut)
  ))
  val oldestExuOutput = Mux(jumpIsOlder.asBool(), jumpOut, aluOut(oldestAluIdx))
  val (oldestRedirect, _) = selectOldestRedirect(Seq(io.loadReplay, oldestExuRedirect))

  val s1_isJump = RegNext(jumpIsOlder.asBool(), init = false.B)
  val s1_jumpTarget = RegEnable(jumpOut.bits.redirect.cfiUpdate.target, jumpOut.valid)
  val s1_imm12_reg = RegEnable(oldestExuOutput.bits.uop.ctrl.imm(11, 0), oldestExuOutput.valid)
  val s1_pd = RegEnable(oldestExuOutput.bits.uop.cf.pd, oldestExuOutput.valid)
  val s1_redirect_bits_reg = Reg(new Redirect)
  val s1_redirect_valid_reg = RegInit(false.B)
  val s1_aluIdx = RegEnable(oldestAluIdx, oldestAluRedirect.valid)

  // stage1 -> stage2
  when(oldestRedirect.valid && !oldestRedirect.bits.roqIdx.needFlush(io.stage2Redirect, io.flush)){
    s1_redirect_bits_reg := oldestRedirect.bits
    s1_redirect_valid_reg := true.B
  }.otherwise({
    s1_redirect_valid_reg := false.B
  })
  io.stage2Redirect.valid := s1_redirect_valid_reg && !io.flush
  io.stage2Redirect.bits := s1_redirect_bits_reg
  io.stage2Redirect.bits.cfiUpdate := DontCare
  // at stage2, we read ftq to get pc
  io.stage2FtqRead.ptr := s1_redirect_bits_reg.ftqIdx

  val isReplay = RedirectLevel.flushItself(s1_redirect_bits_reg.level)
  val ftqRead = Mux(isReplay,
    io.stage1FtqRead.last.entry,
    Mux(
      s1_isJump,
      io.stage1FtqRead.head.entry,
      VecInit(io.stage1FtqRead.tail.take(exuParameters.AluCnt).map(_.entry))(s1_aluIdx)
    )
  )
  val cfiUpdate_pc = Cat(
    ftqRead.ftqPC.head(VAddrBits - s1_redirect_bits_reg.ftqOffset.getWidth - instOffsetBits),
    s1_redirect_bits_reg.ftqOffset,
    0.U(instOffsetBits.W)
  )
  val real_pc = GetPcByFtq(ftqRead.ftqPC, s1_redirect_bits_reg.ftqOffset,
    ftqRead.lastPacketPC.valid,
    ftqRead.lastPacketPC.bits
  )
  val brTarget = real_pc + SignExt(ImmUnion.B.toImm32(s1_imm12_reg), XLEN)
  val snpc = real_pc + Mux(s1_pd.isRVC, 2.U, 4.U)
  val target = Mux(isReplay,
    real_pc, // repaly from itself
    Mux(s1_redirect_bits_reg.cfiUpdate.taken,
      Mux(s1_isJump, s1_jumpTarget, brTarget),
      snpc
    )
  )

  // update waittable if load violation redirect triggered
  io.waitTableUpdate.valid := RegNext(isReplay && s1_redirect_valid_reg, init = false.B)
  io.waitTableUpdate.waddr := RegNext(XORFold(real_pc(VAddrBits-1, 1), WaitTableAddrWidth))
  io.waitTableUpdate.wdata := true.B

  io.stage2FtqRead.ptr := s1_redirect_bits_reg.ftqIdx

  val s2_target = RegEnable(target, enable = s1_redirect_valid_reg)
  val s2_pd = RegEnable(s1_pd, enable = s1_redirect_valid_reg)
  val s2_cfiUpdata_pc = RegEnable(cfiUpdate_pc, enable = s1_redirect_valid_reg)
  val s2_redirect_bits_reg = RegEnable(s1_redirect_bits_reg, enable = s1_redirect_valid_reg)
  val s2_redirect_valid_reg = RegNext(s1_redirect_valid_reg && !io.flush, init = false.B)
  val s2_ftqRead = io.stage2FtqRead.entry

  io.stage3Redirect.valid := s2_redirect_valid_reg
  io.stage3Redirect.bits := s2_redirect_bits_reg
  val stage3CfiUpdate = io.stage3Redirect.bits.cfiUpdate
  stage3CfiUpdate.pc := s2_cfiUpdata_pc
  stage3CfiUpdate.pd := s2_pd
  stage3CfiUpdate.rasSp := s2_ftqRead.rasSp
  stage3CfiUpdate.rasEntry := s2_ftqRead.rasTop
  stage3CfiUpdate.predHist := s2_ftqRead.predHist
  stage3CfiUpdate.specCnt := s2_ftqRead.specCnt
  stage3CfiUpdate.hist := s2_ftqRead.hist
  stage3CfiUpdate.predTaken := s2_redirect_bits_reg.cfiUpdate.predTaken
  stage3CfiUpdate.sawNotTakenBranch := VecInit((0 until PredictWidth).map{ i =>
    if(i == 0) false.B else Cat(s2_ftqRead.br_mask.take(i)).orR()
  })(s2_redirect_bits_reg.ftqOffset)
  stage3CfiUpdate.target := s2_target
  stage3CfiUpdate.taken := s2_redirect_bits_reg.cfiUpdate.taken
  stage3CfiUpdate.isMisPred := s2_redirect_bits_reg.cfiUpdate.isMisPred
}

class CtrlBlock extends XSModule with HasCircularQueuePtrHelper {
  val io = IO(new Bundle {
    val frontend = Flipped(new FrontendToBackendIO)
    val fromIntBlock = Flipped(new IntBlockToCtrlIO)
    val fromFpBlock = Flipped(new FpBlockToCtrlIO)
    val fromLsBlock = Flipped(new LsBlockToCtrlIO)
    val toIntBlock = new CtrlToIntBlockIO
    val toFpBlock = new CtrlToFpBlockIO
    val toLsBlock = new CtrlToLsBlockIO
    val roqio = new Bundle {
      // to int block
      val toCSR = new RoqCSRIO
      val exception = ValidIO(new ExceptionInfo)
      // to mem block
      val lsq = new RoqLsqIO
    }
    val csrCtrl = Input(new CustomCSRCtrlIO)
  })

  val difftestIO = IO(new Bundle() {
    val fromRoq = new Bundle() {
      val commit = Output(UInt(32.W))
      val thisPC = Output(UInt(XLEN.W))
      val thisINST = Output(UInt(32.W))
      val skip = Output(UInt(32.W))
      val wen = Output(UInt(32.W))
      val wdata = Output(Vec(CommitWidth, UInt(XLEN.W))) // set difftest width to 6
      val wdst = Output(Vec(CommitWidth, UInt(32.W))) // set difftest width to 6
      val wpc = Output(Vec(CommitWidth, UInt(XLEN.W))) // set difftest width to 6
      val isRVC = Output(UInt(32.W))
      val scFailed = Output(Bool())
      val lpaddr = Output(Vec(CommitWidth, UInt(64.W)))
      val ltype = Output(Vec(CommitWidth, UInt(32.W)))
      val lfu = Output(Vec(CommitWidth, UInt(4.W)))
    }
  })
  difftestIO <> DontCare

  val ftq = Module(new Ftq)
  val trapIO = IO(new TrapIO())
  trapIO <> DontCare

  val decode = Module(new DecodeStage)
  val rename = Module(new Rename)
  val dispatch = Module(new Dispatch)
  val intBusyTable = Module(new BusyTable(NRIntReadPorts, NRIntWritePorts))
  val fpBusyTable = Module(new BusyTable(NRFpReadPorts, NRFpWritePorts))
  val redirectGen = Module(new RedirectGenerator)

  val roqWbSize = NRIntWritePorts + NRFpWritePorts + exuParameters.StuCnt
  val roq = Module(new Roq(roqWbSize))

  val backendRedirect = redirectGen.io.stage2Redirect
  val frontendRedirect = redirectGen.io.stage3Redirect
  val flush = roq.io.flushOut.valid
  val flushReg = RegNext(flush)

  val exuRedirect = io.fromIntBlock.exuRedirect.map(x => {
    val valid = x.valid && x.bits.redirectValid
    val killedByOlder = x.bits.uop.roqIdx.needFlush(backendRedirect, flushReg)
    val delayed = Wire(Valid(new ExuOutput))
    delayed.valid := RegNext(valid && !killedByOlder, init = false.B)
    delayed.bits := RegEnable(x.bits, x.valid)
    delayed
  })
  VecInit(ftq.io.ftqRead.tail.dropRight(1)) <> redirectGen.io.stage1FtqRead
  ftq.io.cfiRead <> redirectGen.io.stage2FtqRead
  redirectGen.io.exuMispredict <> exuRedirect
  redirectGen.io.loadReplay := io.fromLsBlock.replay
  redirectGen.io.flush := flushReg

  ftq.io.enq <> io.frontend.fetchInfo
  for(i <- 0 until CommitWidth){
    ftq.io.roq_commits(i).valid := roq.io.commits.valid(i) && !roq.io.commits.isWalk
    ftq.io.roq_commits(i).bits := roq.io.commits.info(i)
  }
  ftq.io.redirect <> backendRedirect
  ftq.io.flush := flushReg
  ftq.io.flushIdx := RegNext(roq.io.flushOut.bits.ftqIdx)
  ftq.io.flushOffset := RegNext(roq.io.flushOut.bits.ftqOffset)
  ftq.io.frontendRedirect <> frontendRedirect
  ftq.io.exuWriteback <> exuRedirect

  ftq.io.ftqRead.last.ptr := roq.io.flushOut.bits.ftqIdx
  val flushPC = GetPcByFtq(
    ftq.io.ftqRead.last.entry.ftqPC,
    RegEnable(roq.io.flushOut.bits.ftqOffset, roq.io.flushOut.valid),
    ftq.io.ftqRead.last.entry.lastPacketPC.valid,
    ftq.io.ftqRead.last.entry.lastPacketPC.bits
  )

  val flushRedirect = Wire(Valid(new Redirect))
  flushRedirect.valid := flushReg
  flushRedirect.bits := DontCare
  flushRedirect.bits.ftqIdx := RegEnable(roq.io.flushOut.bits.ftqIdx, flush)
  flushRedirect.bits.interrupt := true.B
  flushRedirect.bits.cfiUpdate.target := Mux(io.roqio.toCSR.isXRet || roq.io.exception.valid,
    io.roqio.toCSR.trapTarget,
    flushPC + 4.U // flush pipe
  )

  io.frontend.redirect_cfiUpdate := Mux(flushRedirect.valid, flushRedirect, frontendRedirect)
  io.frontend.commit_cfiUpdate := ftq.io.commit_ftqEntry
  io.frontend.ftqEnqPtr := ftq.io.enqPtr
  io.frontend.ftqLeftOne := ftq.io.leftOne

  decode.io.in <> io.frontend.cfVec
  // currently, we only update wait table when isReplay
  decode.io.waitTableUpdate(0) <> RegNext(redirectGen.io.waitTableUpdate)
  decode.io.waitTableUpdate(1) := DontCare
  decode.io.waitTableUpdate(1).valid := false.B
  // decode.io.waitTableUpdate <> io.toLsBlock.waitTableUpdate
  decode.io.csrCtrl := RegNext(io.csrCtrl)


  val jumpInst = dispatch.io.enqIQCtrl(0).bits
  val ftqOffsetReg = Reg(UInt(log2Up(PredictWidth).W))
  ftqOffsetReg := jumpInst.cf.ftqOffset
  ftq.io.ftqRead(0).ptr := jumpInst.cf.ftqPtr // jump
  io.toIntBlock.jumpPc := GetPcByFtq(
    ftq.io.ftqRead(0).entry.ftqPC, ftqOffsetReg,
    ftq.io.ftqRead(0).entry.lastPacketPC.valid,
    ftq.io.ftqRead(0).entry.lastPacketPC.bits
  )
  io.toIntBlock.jalr_target := ftq.io.ftqRead(0).entry.target

  // pipeline between decode and dispatch
  for (i <- 0 until RenameWidth) {
    PipelineConnect(decode.io.out(i), rename.io.in(i), rename.io.in(i).ready,
      io.frontend.redirect_cfiUpdate.valid)
  }

  rename.io.redirect <> backendRedirect
  rename.io.flush := flushReg
  rename.io.roqCommits <> roq.io.commits
  rename.io.out <> dispatch.io.fromRename
  rename.io.renameBypass <> dispatch.io.renameBypass
  rename.io.dispatchInfo <> dispatch.io.preDpInfo

  dispatch.io.redirect <> backendRedirect
  dispatch.io.flush := flushReg
  dispatch.io.enqRoq <> roq.io.enq
  dispatch.io.enqLsq <> io.toLsBlock.enqLsq
  dispatch.io.readIntRf <> io.toIntBlock.readRf
  dispatch.io.readFpRf <> io.toFpBlock.readRf
  dispatch.io.allocPregs.zipWithIndex.foreach { case (preg, i) =>
    intBusyTable.io.allocPregs(i).valid := preg.isInt
    fpBusyTable.io.allocPregs(i).valid := preg.isFp
    intBusyTable.io.allocPregs(i).bits := preg.preg
    fpBusyTable.io.allocPregs(i).bits := preg.preg
  }
  dispatch.io.numExist <> io.fromIntBlock.numExist ++ io.fromFpBlock.numExist ++ io.fromLsBlock.numExist
  dispatch.io.enqIQCtrl <> io.toIntBlock.enqIqCtrl ++ io.toFpBlock.enqIqCtrl ++ io.toLsBlock.enqIqCtrl
  dispatch.io.singleStep := RegNext(io.fromIntBlock.singleStep)

  fpBusyTable.io.flush := flushReg
  intBusyTable.io.flush := flushReg
  for((wb, setPhyRegRdy) <- io.fromIntBlock.wbRegs.zip(intBusyTable.io.wbPregs)){
    setPhyRegRdy.valid := wb.valid && wb.bits.uop.ctrl.rfWen
    setPhyRegRdy.bits := wb.bits.uop.pdest
  }
  for((wb, setPhyRegRdy) <- io.fromFpBlock.wbRegs.zip(fpBusyTable.io.wbPregs)){
    setPhyRegRdy.valid := wb.valid && wb.bits.uop.ctrl.fpWen
    setPhyRegRdy.bits := wb.bits.uop.pdest
  }
  intBusyTable.io.read <> dispatch.io.readIntState
  fpBusyTable.io.read <> dispatch.io.readFpState

  roq.io.redirect <> backendRedirect
  roq.io.exeWbResults <> (io.fromIntBlock.wbRegs ++ io.fromFpBlock.wbRegs ++ io.fromLsBlock.stOut)

  // TODO: is 'backendRedirect' necesscary?
  io.toIntBlock.redirect <> backendRedirect
  io.toIntBlock.flush <> flushReg
  io.toFpBlock.redirect <> backendRedirect
  io.toFpBlock.flush <> flushReg
  io.toLsBlock.redirect <> backendRedirect
  io.toLsBlock.flush <> flushReg

  if (!env.FPGAPlatform) {
    difftestIO.fromRoq <> roq.difftestIO
    trapIO <> roq.trapIO
  }

  dispatch.io.readPortIndex.intIndex <> io.toIntBlock.readPortIndex
  dispatch.io.readPortIndex.fpIndex <> io.toFpBlock.readPortIndex

  // roq to int block
  io.roqio.toCSR <> roq.io.csr
  io.roqio.exception := roq.io.exception
  io.roqio.exception.bits.uop.cf.pc := flushPC
  // roq to mem block
  io.roqio.lsq <> roq.io.lsq
}
