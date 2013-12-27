package kvstore

import akka.actor.Props
import akka.actor.Actor
import akka.actor.ActorRef
import scala.concurrent.duration._

/**
 * Replication protocol
 */
object Replicator {
  case class Replicate(key: String, valueOption: Option[String], id: Long)
  case class Replicated(key: String, id: Long)

  case class ReplicateTimeOut(seq: Long, r: Replicate)

  case class Snapshot(key: String, valueOption: Option[String], seq: Long)
  case class SnapshotAck(key: String, seq: Long)

  def props(replica: ActorRef): Props = Props(new Replicator(replica))
}

class Replicator(val replica: ActorRef) extends Actor {
  import Replicator._
  import Replica._
  import context.dispatcher
  import scala.language.postfixOps

  // map from sequence number to pair of sender and request
  var acks = Map.empty[Long, (ActorRef, Replicate)]
  
  // TODO Implement batching
  // a sequence of not-yet-sent snapshots for batching
  var pending = Vector.empty[Snapshot]

  var seq = 0L
  def nextSeq = {
    val ret = seq
    seq += 1
    ret
  }

  /* Behavior for the Replicator. */
  def receive: Receive = {
    case r: Replicate => {
      acks += seq -> (sender, r)
      replica ! Snapshot(r.key, r.valueOption, seq)

      // Start timer
      context.system.scheduler.scheduleOnce(100 millisecond, self, ReplicateTimeOut(seq, r))

      nextSeq
    }
    case sa: SnapshotAck => {
      acks.get(sa.seq) match {
        case Some(value) => {
          acks -= sa.seq
          value._1 ! Replicated(value._2.key, value._2.id)
        }
        case None => // Ignore since message has already been acknowledged
      }
    }
    case rt: ReplicateTimeOut => {
      if (acks.contains(rt.seq)) {
        replica ! Snapshot(rt.r.key, rt.r.valueOption, rt.seq)

        // Start timer
        context.system.scheduler.scheduleOnce(100 millisecond, self, ReplicateTimeOut(rt.seq, rt.r))
      }
    }
  }

}
