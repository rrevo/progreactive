package kvstore

import akka.actor.{ Props, Actor }
import scala.util.Random
import java.util.concurrent.atomic.AtomicInteger

/**
 * Persistance to stable storage that may fail
 */
object Persistence {
  case class Persist(key: String, valueOption: Option[String], id: Long)
  case class Persisted(key: String, id: Long)

  class PersistenceException extends Exception("Persistence failure")

  case class PersistRetryTimeOut(id: Long, p: Persist)
  case class PersistCancelTimeOut(id: Long, p: Persist)

  def props(flaky: Boolean): Props = Props(classOf[Persistence], flaky)
}

class Persistence(flaky: Boolean) extends Actor {
  import Persistence._

  def receive = {
    case Persist(key, _, id) =>
      if (!flaky || Random.nextBoolean()) sender ! Persisted(key, id)
      else throw new PersistenceException
  }

}
