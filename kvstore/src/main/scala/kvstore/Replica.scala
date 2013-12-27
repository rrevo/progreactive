package kvstore

import akka.actor.{ OneForOneStrategy, Props, ActorRef, Actor }
import kvstore.Arbiter._
import scala.collection.immutable.Queue
import akka.actor.SupervisorStrategy.Restart
import scala.annotation.tailrec
import akka.pattern.{ ask, pipe }
import akka.actor.Terminated
import scala.concurrent.duration._
import akka.actor.PoisonPill
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy
import akka.util.Timeout

/**
 * Primary or Secondary Replica node of the store
 */
object Replica {
  sealed trait Operation {
    def key: String
    def id: Long
  }
  case class Insert(key: String, value: String, id: Long) extends Operation
  case class Remove(key: String, id: Long) extends Operation
  case class Get(key: String, id: Long) extends Operation

  sealed trait OperationReply
  case class OperationAck(id: Long) extends OperationReply
  case class OperationFailed(id: Long) extends OperationReply
  case class GetResult(key: String, valueOption: Option[String], id: Long) extends OperationReply

  def props(arbiter: ActorRef, persistenceProps: Props): Props = Props(new Replica(arbiter, persistenceProps))
}

class Replica(val arbiter: ActorRef, persistenceProps: Props) extends Actor {
  import Replica._
  import Replicator._
  import Persistence._
  import context.dispatcher
  import scala.language.postfixOps

  // Map containing persisted key values
  var kv = Map.empty[String, String]

  // a map from secondary replicas to replicators
  var secondaries = Map.empty[ActorRef, ActorRef]

  // the current set of replicators
  var replicators = Set.empty[ActorRef]

  val persistence = context.actorOf(persistenceProps)

  arbiter ! Join

  def receive = {
    case JoinedPrimary => context.become(leader)
    case JoinedSecondary => context.become(replica)
  }

  /* Behavior for  the leader role. */
  val leader: Receive = {
    case i: Insert => {
      kv = kv + ((i.key, i.value))
      sender ! OperationAck(i.id)
    }
    case r: Remove => {
      kv = kv - ((r.key))
      sender ! OperationAck(r.id)
    }
    case g: Get => {
      val valueOption = kv.get(g.key)
      sender ! GetResult(g.key, valueOption, g.id)
    }
  }

  /* Secondary replica state */
  var seq = 0
  var persistPending = Map.empty[Long, (Snapshot, ActorRef)]

  /* Behavior for the replica role. */
  val replica: Receive = {
    case g: Get => {
      val valueOption = kv.get(g.key)
      sender ! GetResult(g.key, valueOption, g.id)
    }
    case s: Snapshot => {
      if (seq == s.seq) {
        s.valueOption match {
          case Some(value) => {
            persistPending += s.seq -> (s, sender)
            kv += s.key -> value
            val p = Persist(s.key, s.valueOption, s.seq)
            context.system.scheduler.scheduleOnce(100 millisecond, self, PersistRetryTimeOut(s.seq, p))
            context.system.scheduler.scheduleOnce(1 second, self, PersistCancelTimeOut(s.seq, p))
            persistence ! p
          }
          case None => {
            persistPending += s.seq -> (s, sender)
            kv -= s.key
            val p = Persist(s.key, None, s.seq)
            context.system.scheduler.scheduleOnce(100 millisecond, self, PersistRetryTimeOut(s.seq, p))
            context.system.scheduler.scheduleOnce(1 second, self, PersistCancelTimeOut(s.seq, p))
            persistence ! p
          }
        }
        seq = seq + 1

      } else if (seq > s.seq) {
        sender ! SnapshotAck(s.key, s.seq)
      }
    }
    case p: Persisted => {
      persistPending.get(p.id) match {
        case Some(snapshot_sender) => {
          persistPending -= p.id
          snapshot_sender._2 ! SnapshotAck(snapshot_sender._1.key, snapshot_sender._1.seq)
        }
        case None => // Already acknowledged so ignore
      }
    }
    case r: PersistRetryTimeOut => {
      persistPending.get(r.id) match {
        case Some(snapshot_sender) => {
          // Retry again
          persistence ! r.p
          context.system.scheduler.scheduleOnce(100 millisecond, self, PersistRetryTimeOut(r.id, r.p))
        }
        case None => // Already acknowledged so ignore
      }
    }
    case c: PersistCancelTimeOut => {
      persistPending.get(c.id) match {
        case Some(snapshot_sender) => {
          // Failed persistence so just remove from persistPending
          persistPending -= c.id
        }
        case None => // Already acknowledged so ignore
      }
    }
  }

}
