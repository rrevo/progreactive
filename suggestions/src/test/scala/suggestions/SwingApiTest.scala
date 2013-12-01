package suggestions

import scala.collection._
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{ Try, Success, Failure }
import scala.swing.event.Event
import scala.swing.Reactions.Reaction
import rx.lang.scala._
import org.scalatest._
import gui._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SwingApiTest extends FunSuite {

  object swingApi extends SwingApi {
    class ValueChanged(val textField: TextField) extends Event

    object ValueChanged {
      def unapply(x: Event) = x match {
        case vc: ValueChanged => Some(vc.textField)
        case _ => None
      }
    }

    class ButtonClicked(val source: Button) extends Event

    object ButtonClicked {
      def unapply(x: Event) = x match {
        case bc: ButtonClicked => Some(bc.source)
        case _ => None
      }
    }

    class Component {
      private val subscriptions = mutable.Set[Reaction]()
      def subscribe(r: Reaction) {
        subscriptions add r
      }
      def unsubscribe(r: Reaction) {
        subscriptions remove r
      }
      def publish(e: Event) {
        for (r <- subscriptions) r(e)
      }
    }

    class TextField extends Component {
      private var _text = ""
      def text = _text
      def text_=(t: String) {
        _text = t
        publish(new ValueChanged(this))
      }
    }

    class Button extends Component {
      def click() {
        publish(new ButtonClicked(this))
      }
    }
  }

  import swingApi._

  /**
   * Understand partial functions better. See http://blog.bruchez.name/2011/10/scala-partial-functions-without-phd.html
   *
   */
  test("Capturing events with a partial function") {
    val textField = new swingApi.TextField
    val observed1 = mutable.Buffer[String]()
    val observed2 = mutable.Buffer[String]()

    // Create partial function and apply for the textField 
    val pf = new PartialFunction[Event, Unit] {
      def isDefinedAt(e: Event): Boolean = e.isInstanceOf[ValueChanged]
      def apply(e: Event): Unit = { observed1 += textField.text }
    }
    textField subscribe pf

    // Preferred form of partial function creation
    textField subscribe {
      case ValueChanged(vc) => observed2 += textField.text
    }

    // Write some text now
    textField.text = "T"
    textField.text = "Tu"
    textField.text = "Tur"
    textField.text = "Turi"
    textField.text = "Turin"
    textField.text = "Turing"

    // No events should be received after unsubscribe
    textField unsubscribe pf

    textField.text = "Alan"

    assert(observed1 == Seq("T", "Tu", "Tur", "Turi", "Turin", "Turing"), observed1)
    assert(observed2 == Seq("T", "Tu", "Tur", "Turi", "Turin", "Turing", "Alan"), observed2)
  }

  test("SwingApi should emit text field values to the observable") {
    val textField = new swingApi.TextField
    val values: Observable[String] = textField.textValues

    val observed = mutable.Buffer[String]()
    val sub: Subscription = values subscribe {
      observed += _
    }

    // write some text now
    textField.text = "T"
    textField.text = "Tu"
    textField.text = "Tur"
    textField.text = "Turi"
    textField.text = "Turin"
    textField.text = "Turing"

    sub.unsubscribe
    textField.text = "Alan"

    assert(observed == Seq("T", "Tu", "Tur", "Turi", "Turin", "Turing"), observed)
  }
}