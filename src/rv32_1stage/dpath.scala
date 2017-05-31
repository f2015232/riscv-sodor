//**************************************************************************
// RISCV Processor 1-Stage Datapath
//--------------------------------------------------------------------------
//
// Christopher Celio
// 2012 Jan 11

package Sodor
{

import chisel3._
import chisel3.util._


import Constants._
import Common._
import Common.Constants._

class DatToCtlIo(implicit conf: SodorConfiguration) extends Bundle() 
{
   val inst   = Output(UInt(32.W))
   val br_eq  = Output(Bool())
   val br_lt  = Output(Bool())
   val br_ltu = Output(Bool())
//   val csr    = new CSRFileIO()
//   val status = new MStatus().asOutput()
   val csr_eret = Output(Bool())
   val csr_interrupt = Output(Bool())
   val csr_xcpt = Output(Bool())
   val csr_interrupt_cause = Output(UInt(conf.xprlen.W))
   override def cloneType = { new DatToCtlIo().asInstanceOf[this.type] }
}

class DpathIo(implicit conf: SodorConfiguration) extends Bundle() 
{
   val host  = new HTIFIO()
   val imem = new MemPortIo(conf.xprlen)
   val dmem = new MemPortIo(conf.xprlen)
   val ctl  = Flipped(new CtlToDatIo())
   val dat  = new DatToCtlIo()
}

class DatPath(implicit conf: SodorConfiguration) extends Module
{
   val io = IO(new DpathIo())
   
   
   // Instruction Fetch
   val pc_next          = Wire(UInt(32.W))
   val pc_plus4         = Wire(UInt(32.W))
   val br_target        = Wire(UInt(32.W))
   val jmp_target       = Wire(UInt(32.W))
   val jump_reg_target  = Wire(UInt(32.W))
   val exception_target = Wire(UInt(32.W))
 
   // PC Register
   pc_next := MuxCase(pc_plus4, Array(
                  (io.ctl.pc_sel === PC_4)   -> pc_plus4,
                  (io.ctl.pc_sel === PC_BR)  -> br_target,
                  (io.ctl.pc_sel === PC_J )  -> jmp_target,
                  (io.ctl.pc_sel === PC_JR)  -> jump_reg_target,
                  (io.ctl.pc_sel === PC_EXC) -> exception_target
                  ))

   val pc_reg = Reg(init=START_ADDR.asUInt(conf.xprlen.W))


   when (!io.ctl.stall) 
   {
      pc_reg := pc_next
   }

   pc_plus4 := (pc_reg + 4.asUInt(conf.xprlen.W))               

   
   io.imem.req.bits.addr := pc_reg
   io.imem.req.valid := Bool(true)
   val inst = Mux(io.imem.resp.valid, io.imem.resp.bits.data, BUBBLE) 
                 
   
   // Decode
   val rs1_addr = inst(RS1_MSB, RS1_LSB)
   val rs2_addr = inst(RS2_MSB, RS2_LSB)
   val wb_addr  = inst(RD_MSB,  RD_LSB)
   
   val wb_data = Wire(UInt(conf.xprlen.W))
 
   // Register File
   val regfile = Mem(UInt(conf.xprlen.W), 32)

   when (io.ctl.rf_wen && (wb_addr != 0.U) && !io.dat.csr_xcpt)
   {
      regfile(wb_addr) := wb_data
   }

   val rs1_data = Mux((rs1_addr != 0.U), regfile(rs1_addr), 0.asUInt(conf.xprlen.W))
   val rs2_data = Mux((rs2_addr != 0.U), regfile(rs2_addr), 0.asUInt(conf.xprlen.W))
   
   
   // immediates
   val imm_i = inst(31, 20) 
   val imm_s = Cat(inst(31, 25), inst(11,7))
   val imm_b = Cat(inst(31), inst(7), inst(30,25), inst(11,8))
   val imm_u = inst(31, 12)
   val imm_j = Cat(inst(31), inst(19,12), inst(20), inst(30,21))
   val imm_z = Cat(Fill(27,0.U), inst(19,15))

   // sign-extend immediates
   val imm_i_sext = Cat(Fill(20,imm_i(11)), imm_i)
   val imm_s_sext = Cat(Fill(20,imm_s(11)), imm_s)
   val imm_b_sext = Cat(Fill(19,imm_b(11)), imm_b, 0.U)
   val imm_u_sext = Cat(imm_u, Fill(12,0.U))
   val imm_j_sext = Cat(Fill(11,imm_j(19)), imm_j, 0.U)


   val alu_op1 = MuxCase(0.U, Array(
               (io.ctl.op1_sel === OP1_RS1) -> rs1_data,
               (io.ctl.op1_sel === OP1_IMU) -> imm_u_sext,
               (io.ctl.op1_sel === OP1_IMZ) -> imm_z
               )).toUInt

   val alu_op2 = MuxCase(0.U, Array(
               (io.ctl.op2_sel === OP2_RS2) -> rs2_data,
               (io.ctl.op2_sel === OP2_PC)  -> pc_reg,
               (io.ctl.op2_sel === OP2_IMI) -> imm_i_sext,
               (io.ctl.op2_sel === OP2_IMS) -> imm_s_sext
               )).toUInt



   // ALU
   val alu_out   = Wire(UInt(conf.xprlen.W))

   val alu_shamt = alu_op2(4,0).toUInt

   alu_out := MuxCase(0.U, Array(
                  (io.ctl.alu_fun === ALU_ADD)  -> (alu_op1 + alu_op2).toUInt,
                  (io.ctl.alu_fun === ALU_SUB)  -> (alu_op1 - alu_op2).toUInt,
                  (io.ctl.alu_fun === ALU_AND)  -> (alu_op1 & alu_op2).toUInt,
                  (io.ctl.alu_fun === ALU_OR)   -> (alu_op1 | alu_op2).toUInt,
                  (io.ctl.alu_fun === ALU_XOR)  -> (alu_op1 ^ alu_op2).toUInt,
                  (io.ctl.alu_fun === ALU_SLT)  -> (alu_op1.toSInt < alu_op2.toSInt).toUInt,
                  (io.ctl.alu_fun === ALU_SLTU) -> (alu_op1 < alu_op2).toUInt,
                  (io.ctl.alu_fun === ALU_SLL)  -> ((alu_op1 << alu_shamt)(conf.xprlen-1, 0)).toUInt,
                  (io.ctl.alu_fun === ALU_SRA)  -> (alu_op1.toSInt >> alu_shamt).toUInt,
                  (io.ctl.alu_fun === ALU_SRL)  -> (alu_op1 >> alu_shamt).toUInt,
                  (io.ctl.alu_fun === ALU_COPY1)-> alu_op1
                  ))

   // Branch/Jump Target Calculation
   br_target       := pc_reg + imm_b_sext
   jmp_target      := pc_reg + imm_j_sext
   jump_reg_target := (rs1_data.toUInt + imm_i_sext.toUInt)

   // Control Status Registers
   val csr = Module(new CSRFile())
   csr.io.host <> io.host
//   csr.io <> io.dat.csr
   csr.io.rw.addr  := inst(CSR_ADDR_MSB,CSR_ADDR_LSB)
   csr.io.rw.cmd   := io.ctl.csr_cmd
   csr.io.rw.wdata := alu_out

   csr.io.retire    := !io.ctl.stall
   csr.io.exception := io.ctl.exception
//   io.dat.status    := csr.io.status
   csr.io.cause     := io.ctl.exc_cause
   csr.io.pc        := pc_reg
   exception_target := csr.io.evec

   io.dat.csr_eret := csr.io.eret
   io.dat.csr_xcpt := csr.io.csr_xcpt
   io.dat.csr_interrupt := csr.io.interrupt
   io.dat.csr_interrupt_cause := csr.io.interrupt_cause
   // TODO replay? stall?

   // Add your own uarch counters here!
   csr.io.uarch_counters.foreach(_ := Bool(false))

   // WB Mux
   wb_data := MuxCase(alu_out, Array(
                  (io.ctl.wb_sel === WB_ALU) -> alu_out,
                  (io.ctl.wb_sel === WB_MEM) -> io.dmem.resp.bits.data, 
                  (io.ctl.wb_sel === WB_PC4) -> pc_plus4,
                  (io.ctl.wb_sel === WB_CSR) -> csr.io.rw.rdata
                  ))
                                  

   // datapath to controlpath outputs
   io.dat.inst   := inst
   io.dat.br_eq  := (rs1_data === rs2_data)
   io.dat.br_lt  := (rs1_data.toSInt < rs2_data.toSInt) 
   io.dat.br_ltu := (rs1_data.toUInt < rs2_data.toUInt)
   
   
   // datapath to data memory outputs
   io.dmem.req.bits.addr  := alu_out
   io.dmem.req.bits.data := rs2_data.toUInt 
   
   // Printout
   // pass output through the spike-dasm binary (found in riscv-tools) to turn
   // the DASM(%x) into a disassembly string.
   printf("Cyc= %d Op1=[0x%x] Op2=[0x%x] W[%c,%d= 0x%x] %c Mem[%d: R:0x%x W:0x%x] PC= 0x%x %c%c DASM(%x)\n"
      , csr.io.time(31,0)
      , alu_op1
      , alu_op2
      , Mux(io.ctl.rf_wen, Str("W"), Str("_"))
      , wb_addr
      , wb_data
      , Mux(io.ctl.exception, Str("E"), Str(" ")) // EXC -> E
      , io.ctl.wb_sel
      , io.dmem.resp.bits.data
      , io.dmem.req.bits.data
      , pc_reg
      , Mux(io.ctl.stall, Str("s"), Str(" "))
      , Mux(io.ctl.pc_sel  === UInt(1), Str("B"),
         Mux(io.ctl.pc_sel === UInt(2), Str("J"),
         Mux(io.ctl.pc_sel === UInt(3), Str("K"),// JR -> K
         Mux(io.ctl.pc_sel === UInt(4), Str("X"),// EX -> X
         Mux(io.ctl.pc_sel === 0.U, Str(" "), Str("?"))))))
      , inst
      )
 
   if (PRINT_COMMIT_LOG)
   {
      when (!io.ctl.stall)
      {
         // use "sed" to parse out "@@@" from the other printf code above.
         val rd = inst(RD_MSB,RD_LSB)
         when (io.ctl.rf_wen && rd != 0.U)
         {
            printf("@@@ 0x%x (0x%x) x%d 0x%x\n", pc_reg, inst, rd, Cat(Fill(32,wb_data(31)),wb_data))
         }
         .otherwise
         {
            printf("@@@ 0x%x (0x%x)\n", pc_reg, inst)
         }
      }
   }   
}

 
}
