
package boom.tests

import scala.util.Random

import org.scalatest._

import chisel3._
import chisel3.util._
import chisel3.testers._

import freechips.rocketchip.config.{Parameters,Config}
import freechips.rocketchip.system._
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.subsystem._

import boom._
import boom.exu._
import boom.bpu._
import boom.acc._
import boom.exu.ygjk._


class MACTests(c: MAC32) extends PeekPokeTester(c) {
  println("********************* test MAC *********************\n") 
  poke(c.io.icb.valid, true.B)
  poke(c.io.icb.bits, 16)
  step(1)
  poke(c.io.icb.valid, false.B)
 // poke(c.io.a, a)
  for( i <- 1 to 80){
    poke(c.io.Ain.valid, true.B)
    poke(c.io.Ain.bits, 1)
    poke(c.io.Bin.valid, true.B)
    poke(c.io.Bin.bits, 1)
    poke(c.io.Cout.ready, true.B)
	  step(1)
  }
}


object testMAC {
  def main(args: Array[String]): Unit = {
    chisel3.iotesters.Driver.execute(args,() =>new MAC32())( c => new MACTests(c))
    //chisel3.iotesters.Driver.execute(args,() =>new RoCC2YGJK(boomParams))( c => new R2YTests(c))  
}

}


