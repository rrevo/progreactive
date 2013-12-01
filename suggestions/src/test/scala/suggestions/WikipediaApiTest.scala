package suggestions

import language.postfixOps
import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{ Try, Success, Failure }
import rx.lang.scala._
import org.scalatest._
import gui._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import rx.lang.scala.Observable._
import rx.lang.scala.subjects._

@RunWith(classOf[JUnitRunner])
class WikipediaApiTest extends FunSuite {

  object mockApi extends WikipediaApi {
    def wikipediaSuggestion(term: String) = Future {
      if (term.head.isLetter) {
        for (suffix <- List(" (Computer Scientist)", " (Footballer)")) yield term + suffix
      } else {
        List(term)
      }
    }
    def wikipediaPage(term: String) = Future {
      "Title: " + term
    }
  }

  import mockApi._

  test("WikipediaApi should make the stream valid using sanitized") {
    val notvalid = Observable("erik", "erik meijer", "martin")
    val valid = notvalid.sanitized

    var count = 0
    var completed = false

    val sub = valid.subscribe(
      term => {
        assert(term.forall(_ != ' '))
        count += 1
      },
      t => assert(false, s"stream error $t"),
      () => completed = true)
    assert(completed && count == 3, "completed: " + completed + ", event count: " + count)
  }

  test("WikipediaApi recovered") {
    val observed = scala.collection.mutable.Buffer[Integer]()
    val requests = Observable(1, 2, 3)
    val responses = requests recovered
    val sum = responses.subscribe { t =>
      t match {
        case Success(i) => observed += i
        case Failure(ex) => throw ex
      }
    }
    assert(observed == Seq(1, 2, 3))
  }

  test("WikipediaApi recovered with exception") {
    val succ = scala.collection.mutable.Buffer[(Int, Int)]()
    val err = scala.collection.mutable.Buffer[(Int, Throwable)]()

    val requests = PublishSubject[Int](-1)
    val responses = requests recovered

    var index: Int = 0
    val sum = responses.subscribe { t =>
      t match {
        case Success(x) => {
          val tup = (index, x)
          succ += tup
          index += 1
        }
        case Failure(ex) => {
          val tup = (index, ex)
          err += tup
          index += 1
        }
      }
    }
    requests.onNext(1)
    requests.onNext(2)
    requests.onError(new IllegalArgumentException)
    assert(succ == Seq((0, 1), (1, 2)), succ)
    assert(err.size == 1)
    assert(err(0)._1 == 2 && err(0)._2.isInstanceOf[IllegalArgumentException])
  }

  test("WikipediaApi should correctly use concatRecovered") {
    val succ = scala.collection.mutable.Buffer[(Int, Int)]()
    val requests = Observable(1, 2, 3)
    val remoteComputation = (n: Int) => Observable(0 to n)
    val responses = requests concatRecovered remoteComputation
    var index = 0
    val sum = responses.foldLeft(0) { (acc, tn) =>
      tn match {
        case Success(n) => {
          val tup = (index, n)
          succ += tup
          index += 1
          acc + n
        }
        case Failure(t) => throw t
      }
    }
    var total = -1
    val sub = sum.subscribe {
      s => total = s
    }
    assert(succ == Seq((0, 0), (1, 1), (2, 0), (3, 1), (4, 2), (5, 0), (6, 1), (7, 2), (8, 3)), succ)
    assert(total == (1 + 1 + 2 + 1 + 2 + 3), s"Sum: $total")
  }
}