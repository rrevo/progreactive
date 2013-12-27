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
  var replicatePending = Map.empty[Long, Set[ActorRef]]

  // Persistence state
  val persistence = context.actorOf(persistenceProps)
  var persistPending = Map.empty[Long, (Snapshot, ActorRef)]

  var ackPending = Map.empty[Long, ActorRef]

  arbiter ! Join

  def receive = {
    case JoinedPrimary => context.become(leader)
    case JoinedSecondary => context.become(replica)
  }

  val actorRef = self

  /* Behavior for  the leader role. */
  val leader: Receive = {
    case rs: Replicas => {
      val addedSecs = rs.replicas.filter(sec => !secondaries.keySet.contains(sec) && sec != actorRef)
      val removedSecs = secondaries.keySet.filter(sec => !rs.replicas.contains(sec))

      addedSecs.foreach(addedSec => {
        val replicator = context.actorOf(Replicator.props(addedSec))
        secondaries += addedSec -> replicator
        replicators += replicator
        addReplicator(replicator)
      })

      removedSecs.foreach(removedSec => {
        secondaries.get(removedSec) match {
          case Some(replicator) => {
            removeReplicator(replicator)
            context.stop(replicator)
          }
          case None => throw new IllegalStateException
        }
      })
    }
    case i: Insert => {
      ackPending += i.id -> sender

      // Invoke replicators
      if (!replicators.isEmpty) {
        val rep = Replicate(i.key, Some(i.value), i.id)
        replicatePending += rep.id -> replicators
        replicators.foreach(replicator => replicator ! rep)
      }

      // Invoke local persistence
      val s = Snapshot(i.key, Some(i.value), i.id)
      persistPending += s.seq -> (s, sender)
      kv += s.key -> i.value
      val p = Persist(s.key, s.valueOption, s.seq)
      persistence ! p

      // Start timers
      context.system.scheduler.scheduleOnce(100 millisecond, self, PersistRetryTimeOut(s.seq, p))
      context.system.scheduler.scheduleOnce(1 second, self, PersistCancelTimeOut(s.seq, p))
    }
    case r: Remove => {
      ackPending += r.id -> sender

      // Invoke replicators
      if (!replicators.isEmpty) {
        val rep = Replicate(r.key, None, r.id)
        replicatePending += rep.id -> replicators
        replicators.foreach(replicator => replicator ! rep)
      }

      // Invoke local persistence
      val s = Snapshot(r.key, None, r.id)
      persistPending += s.seq -> (s, sender)
      kv -= s.key
      val p = Persist(s.key, None, s.seq)
      persistence ! p

      // Start timers
      context.system.scheduler.scheduleOnce(100 millisecond, self, PersistRetryTimeOut(s.seq, p))
      context.system.scheduler.scheduleOnce(1 second, self, PersistCancelTimeOut(s.seq, p))
    }
    case g: Get => {
      val valueOption = kv.get(g.key)
      sender ! GetResult(g.key, valueOption, g.id)
    }
    case p: Persisted => {
      persistPending.get(p.id) match {
        case Some(snapshot_sender) => {
          persistPending -= p.id
          opAck(p.id)
        }
        case None => // Already acknowledged so ignore
      }
    }
    case rep: Replicated => {
      val replicator = sender
      replicatePending.get(rep.id) match {
        case Some(replicators) => {
          if (replicators.contains(replicator)) {
            if (replicators.size == 1) {
              replicatePending -= rep.id
              opAck(rep.id)
            } else {
              replicatePending += rep.id -> (replicators - replicator)
            }
          }
        }
        case None => // Ignore
      }
    }
    case rt: PersistRetryTimeOut => {
      persistPending.get(rt.id) match {
        case Some(snapshot_sender) => {
          // Retry again
          persistence ! rt.p
          context.system.scheduler.scheduleOnce(100 millisecond, self, PersistRetryTimeOut(rt.id, rt.p))
        }
        case None => // Already acknowledged so ignore
      }
    }
    case ct: PersistCancelTimeOut => {
      opFail(ct.id)
    }
  }

  def opAck(id: Long) {
    if (!persistPending.contains(id) && !replicatePending.contains(id) && ackPending.contains(id)) {
      ackPending(id) ! OperationAck(id)
      ackPending -= id
    }
  }

  def opFail(id: Long) {
    persistPending -= id
    replicatePending -= id
    if (ackPending.contains(id)) {
      ackPending(id) ! OperationFailed(id)
      ackPending -= id
    }
  }

  def addReplicator(replicator: ActorRef) {
    var localSeq = 0
    kv.foreach(entry => {
      val rep = Replicate(entry._1, Some(entry._2), localSeq)
      replicator ! rep
      localSeq += 1
    })
  }

  def removeReplicator(replicator: ActorRef) {
    replicatePending.foreach(entry => {
      replicatePending.get(entry._1).foreach(replicators => {
        if (replicators.contains(replicator)) {
          if (replicators.size == 1) {
            replicatePending -= entry._1
            opAck(entry._1)
          } else {
            replicatePending += entry._1 -> (replicators - replicator)
          }
        }
      })
    })
  }

  /* Secondary replica state */
  var seq = 0

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
