package simulations

import org.scalatest.FunSuite

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class CircuitSuite extends CircuitSimulator with FunSuite {
  val InverterDelay = 1
  val AndGateDelay = 3
  val OrGateDelay = 5
  
  test("connect example") {
    val in, out = new Wire
    connect(in, out)
    in.setSignal(false)
    run
    assert(out.getSignal === false, "connect 0")

    in.setSignal(true)
    run
    assert(out.getSignal === true, "connect 1")
  }

  test("invGate example") {
    val in, out = new Wire
    inverter(in, out)
    in.setSignal(false)
    run
    assert(out.getSignal === true, "inv 0")

    in.setSignal(true)
    run
    assert(out.getSignal === false, "inv 1")
  }
  
  test("andGate example") {
    val in1, in2, out = new Wire
    andGate(in1, in2, out)
    in1.setSignal(false)
    in2.setSignal(false)
    run
    assert(out.getSignal === false, "0 and 0")

    in1.setSignal(true)
    in2.setSignal(false)
    run
    assert(out.getSignal === false, "1 and 0")

    in1.setSignal(false)
    in2.setSignal(true)
    run
    assert(out.getSignal === false, "0 and 1")
    
    in1.setSignal(true)
    in2.setSignal(true)
    run
    assert(out.getSignal === true, "1 and 1")
  }
  
  test("orGate example") {
    val in1, in2, out = new Wire
    orGate(in1, in2, out)
    in1.setSignal(false)
    in2.setSignal(false)
    run
    assert(out.getSignal === false, "0 or 0")

    in1.setSignal(true)
    in2.setSignal(false)
    run
    assert(out.getSignal === true, "1 or 0")

    in1.setSignal(false)
    in2.setSignal(true)
    run
    assert(out.getSignal === true, "0 or 1")
    
    in1.setSignal(true)
    in2.setSignal(true)
    run
    assert(out.getSignal === true, "1 or 1")
  }
  
  test("orGate2 example") {
    val in1, in2, out = new Wire
    orGate2(in1, in2, out)
    in1.setSignal(false)
    in2.setSignal(false)
    run
    assert(out.getSignal === false, "0 or 0")

    in1.setSignal(true)
    in2.setSignal(false)
    run
    assert(out.getSignal === true, "1 or 0")

    in1.setSignal(false)
    in2.setSignal(true)
    run
    assert(out.getSignal === true, "0 or 1")
    
    in1.setSignal(true)
    in2.setSignal(true)
    run
    assert(out.getSignal === true, "1 or 1")
  }
  
  test("demux 1:2 example") {
    val in, c0, out0, out1 = new Wire
    demux(in, List(c0), List(out1, out0))

    run
    assert(out0.getSignal === false)
    assert(out1.getSignal === false)
    
    in.setSignal(true)
    
    c0.setSignal(true)
    run
    assert(out1.getSignal === true)
    assert(out0.getSignal === false)

    c0.setSignal(false)
    run
    assert(out1.getSignal === false)
    assert(out0.getSignal === true)
  }
  
  test("demux 1:4 example") {
    val in, c0, c1, out0, out1, out2, out3 = new Wire
    demux(in, List(c1, c0), List(out3, out2, out1, out0))

    run
    assert(out3.getSignal === false)
    assert(out2.getSignal === false)
    assert(out0.getSignal === false)
    assert(out1.getSignal === false)
    
    in.setSignal(true)
    
    c1.setSignal(true)
    c0.setSignal(true)
    run
    assert(out3.getSignal === true)
    assert(out2.getSignal === false)
    assert(out1.getSignal === false)
    assert(out0.getSignal === false)

    c1.setSignal(true)
    c0.setSignal(false)
    run
    assert(out3.getSignal === false)
    assert(out2.getSignal === true)
    assert(out1.getSignal === false)
    assert(out0.getSignal === false)
    
    c1.setSignal(false)
    c0.setSignal(true)
    run
    assert(out3.getSignal === false)
    assert(out2.getSignal === false)
    assert(out1.getSignal === true)
    assert(out0.getSignal === false)
    
    c1.setSignal(false)
    c0.setSignal(false)
    run
    assert(out3.getSignal === false)
    assert(out2.getSignal === false)
    assert(out1.getSignal === false)
    assert(out0.getSignal === true)
  }
  
}
