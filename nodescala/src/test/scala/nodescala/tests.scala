package nodescala

import scala.language.postfixOps
import scala.util.{ Try, Success, Failure }
import scala.collection._
import scala.concurrent._
import ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.async.Async.{ async, await }
import org.scalatest._
import NodeScala._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class NodeScalaSuite extends FunSuite {

  test("A Future should always be created") {
    val always = Future.always(517)

    assert(Await.result(always, 0 nanos) == 517)
  }

  test("A Future should never be created") {
    val never = Future.never[Int]

    try {
      Await.result(never, 1 second)
      assert(false)
    } catch {
      case t: TimeoutException => // ok!
      case _: Throwable => fail
    }
  }

  test("all futures executed") {
    val all = Future.all(List(future(1), future(2), future(3)))
    assert(Await.result(all, 1 second) == List(1, 2, 3))
  }

  test("all futures with a failure") {
    val p = Promise[Int]()
    val all = Future.all(List(future(1), p.future, future(3)))
    p.failure(new NumberFormatException)

    Await.ready(all, 1 second)
    all onComplete {
      case Success(is) => fail
      case Failure(t) => assert(t.isInstanceOf[NumberFormatException])
    }
  }

  test("any futures executed") {
    val any = Future.any(List(future(1), future(2), future(3)))
    val result = Await.result(any, 1 second)
    assert(result == 1 || result == 2 || result == 3)
  }

  test("any futures with a failure") {
    val p = Promise[Unit]()
    val any = Future.any(List(Future.delay(100 milliseconds), p.future))
    p.failure(new NumberFormatException)

    Await.ready(any, 1 second)
    any onComplete {
      case Success(is) => fail
      case Failure(t) => assert(t.isInstanceOf[NumberFormatException])
    }
  }

  test("now completed") {
    val f = future { 1 }
    Await.result(f, 1 second)
    assert(f.now == 1)
  }

  test("now incomplete") {
    val f = Future.delay(2 second)
    try {
      f.now
      fail
    } catch {
      case t: NoSuchElementException => //Ok!
      case _: Throwable => fail
    }
  }

  test("continueWith") {
    val f1 = future {
      blocking {
        Thread.sleep(1000)
      }
      1
    }
    val f2 = f1.continueWith(in => "one")
    Await.result(f2, 2 second)
    assert(f2.value.get.get == "one")
  }

  test("continueWith failure") {
    val f1: Future[Int] = future {
      throw new NumberFormatException
    }
    val f2 = f1.continueWith(in => "one")
    Await.ready(f2, 1 second)
    f2 onComplete {
      case Success(s) => fail
      case Failure(t) => assert(t.isInstanceOf[NumberFormatException])
    }
  }

  test("continue") {
    val f1 = future {
      blocking {
        Thread.sleep(1000)
      }
      1
    }
    val f2 = f1.continue(in => "one")
    Await.result(f2, 2 second)
    assert(f2.value.get.get == "one")
  }

  test("continue failure") {
    val f1: Future[Int] = future {
      throw new NumberFormatException
    }
    val f2 = f1.continue(in => "one")
    Await.ready(f2, 1 second)
    f2 onComplete {
      case Success(s) => fail
      case Failure(t) => assert(t.isInstanceOf[NumberFormatException])
    }
  }

  test("CancellationTokenSource should allow stopping the computation") {
    val cts = CancellationTokenSource()
    val ct = cts.cancellationToken
    val p = Promise[String]()

    async {
      while (ct.nonCancelled) {
        // do work
        blocking {
          Thread.sleep(100)
        }
      }

      p.success("done")
    }

    assert(ct.nonCancelled)
    cts.unsubscribe()
    assert(Await.result(p.future, 1 second) == "done")
  }

  test("run test") {
    @volatile var res = 1
    val working = Future.run() { ct =>
      Future {
        while (ct.nonCancelled) {
          blocking {
            Thread.sleep(100)
          }
        }
        res = 2
      }
    }
    val df = Future.delay(1 seconds)
    df onSuccess {
      case _ => working.unsubscribe()
    }
    Await.ready(df, 2 second)
    blocking{Thread.sleep(1000)}
    assert(res == 2)
  }

  class DummyExchange(val request: Request) extends Exchange {
    @volatile var response = ""
    val loaded = Promise[String]()
    def write(s: String) {
      response += s
    }
    def close() {
      loaded.success(response)
    }
  }

  class DummyListener(val port: Int, val relativePath: String) extends NodeScala.Listener {
    self =>

    @volatile private var started = false
    var handler: Exchange => Unit = null

    def createContext(h: Exchange => Unit) = this.synchronized {
      assert(started, "is server started?")
      handler = h
    }

    def removeContext() = this.synchronized {
      assert(started, "is server started?")
      handler = null
    }

    def start() = self.synchronized {
      started = true
      new Subscription {
        def unsubscribe() = self.synchronized {
          started = false
        }
      }
    }

    def emit(req: Request) = {
      val exchange = new DummyExchange(req)
      if (handler != null) handler(exchange)
      exchange
    }
  }

  class DummyServer(val port: Int) extends NodeScala {
    self =>
    val listeners = mutable.Map[String, DummyListener]()

    def createListener(relativePath: String) = {
      val l = new DummyListener(port, relativePath)
      listeners(relativePath) = l
      l
    }

    def emit(relativePath: String, req: Request) = this.synchronized {
      val l = listeners(relativePath)
      l.emit(req)
    }
  }

  test("Listener should serve the next request as a future") {
    val dummy = new DummyListener(8191, "/test")
    val subscription = dummy.start()

    def test(req: Request) {
      val f = dummy.nextRequest()
      dummy.emit(req)
      val (reqReturned, xchg) = Await.result(f, 1 second)

      assert(reqReturned == req)
    }

    test(immutable.Map("StrangeHeader" -> List("StrangeValue1")))
    test(immutable.Map("StrangeHeader" -> List("StrangeValue2")))

    subscription.unsubscribe()
  }

  test("Server should serve requests") {
    val dummy = new DummyServer(8191)
    val dummySubscription = dummy.start("/testDir") {
      request => for (kv <- request.iterator) yield (kv + "\n").toString
    }

    // wait until server is really installed
    Thread.sleep(500)

    def test(req: Request) {
      val webpage = dummy.emit("/testDir", req)
      val content = Await.result(webpage.loaded.future, 1 second)
      val expected = (for (kv <- req.iterator) yield (kv + "\n").toString).mkString
      assert(content == expected, s"'$content' vs. '$expected'")
    }

    test(immutable.Map("StrangeRequest" -> List("Does it work?")))
    test(immutable.Map("StrangeRequest" -> List("It works!")))
    test(immutable.Map("WorksForThree" -> List("Always works. Trust me.")))

    dummySubscription.unsubscribe()
  }

}
