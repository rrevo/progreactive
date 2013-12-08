/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package actorbintree

import akka.actor._
import scala.collection.immutable.Queue

object BinaryTreeSet {

  trait Operation {
    def requester: ActorRef
    def id: Int
    def elem: Int
  }

  trait OperationReply {
    def id: Int
  }

  /**
   * Request with identifier `id` to insert an element `elem` into the tree.
   * The actor at reference `requester` should be notified when this operation
   * is completed if insert was done or elem existed.
   */
  case class Insert(requester: ActorRef, id: Int, elem: Int) extends Operation

  /**
   * Request with identifier `id` to check whether an element `elem` is present
   * in the tree. The actor at reference `requester` should be notified when
   * this operation is completed.
   */
  case class Contains(requester: ActorRef, id: Int, elem: Int) extends Operation

  /**
   * Request with identifier `id` to remove the element `elem` from the tree.
   * The actor at reference `requester` should be notified when this operation
   * is completed if remove was done or elem did not exist.
   */
  case class Remove(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request to perform garbage collection */
  case object GC

  /**
   * Holds the answer to the Contains request with identifier `id`.
   * `result` is true if and only if the element is present in the tree.
   */
  case class ContainsResult(id: Int, result: Boolean) extends OperationReply

  /** Message to signal successful completion of an insert or remove operation. */
  case class OperationFinished(id: Int) extends OperationReply

}

class BinaryTreeSet extends Actor with ActorLogging {
  import BinaryTreeSet._
  import BinaryTreeNode._

  def createRoot: ActorRef = context.actorOf(BinaryTreeNode.props(0, initiallyRemoved = true))

  var root = createRoot

  var pendingQueue = Queue.empty[Operation]

  def receive: Receive = {
    case c: Contains => root ! c
    case i: Insert => root ! i
    case r: Remove => root ! r
  }

  /**
   * Handles messages while garbage collection is performed.
   * `newRoot` is the root of the new binary tree where we want to copy
   * all non-removed elements into.
   */
  def garbageCollecting(newRoot: ActorRef): Receive = ???

}

object BinaryTreeNode {
  trait Position

  case object Left extends Position
  case object Right extends Position

  case class CopyTo(treeNode: ActorRef)
  case object CopyFinished

  def props(elem: Int, initiallyRemoved: Boolean) = Props(classOf[BinaryTreeNode], elem, initiallyRemoved)
}

class BinaryTreeNode(val elem: Int, val initiallyRemoved: Boolean) extends Actor with ActorLogging {
  import BinaryTreeNode._
  import BinaryTreeSet._

  var subtrees = Map[Position, ActorRef]()

  // State whether this node is removed from the tree
  var removed = initiallyRemoved

  def receive: Receive = {
    case c: Contains => {
      var resp = false
      if (c.elem == elem && !removed) {
        c.requester ! ContainsResult(c.id, true)
        resp = true
      }
      if (c.elem < elem && subtrees.contains(Left)) {
        subtrees.get(Left).get ! c
        resp = true
      }
      if (c.elem > elem && subtrees.contains(Right)) {
        subtrees.get(Right).get ! c
        resp = true
      }
      if (!resp) {
        c.requester ! ContainsResult(c.id, false)
      }
    }
    case i: Insert => {
      if (elem == i.elem) {
        if (removed) {
          removed = false
        }
        i.requester ! OperationFinished(i.id)
      } else if (i.elem < elem) {
        if (!subtrees.contains(Left)) {
          val left = context.actorOf(BinaryTreeNode.props(i.elem, initiallyRemoved = false))
          subtrees = subtrees + (Left -> left)
          i.requester ! OperationFinished(i.id)
        } else {
          subtrees.get(Left).get ! i
        }
      } else { // if (i.elem > elem)
        if (!subtrees.contains(Right)) {
          val right = context.actorOf(BinaryTreeNode.props(i.elem, initiallyRemoved = false))
          subtrees = subtrees + (Right -> right)
          i.requester ! OperationFinished(i.id)
        } else {
          subtrees.get(Right).get ! i
        }
      }
    }
    case r: Remove => {
      if (elem == r.elem) {
        if (!removed) {
          removed = true
        }
        r.requester ! OperationFinished(r.id)
      } else if (r.elem < elem) {
        if (!subtrees.contains(Left)) {
          r.requester ! OperationFinished(r.id)
        } else {
          subtrees.get(Left).get ! r
        }
      } else { // if (r.elem > elem)
        if (!subtrees.contains(Right)) {
          r.requester ! OperationFinished(r.id)
        } else {
          subtrees.get(Right).get ! r
        }
      }
    }
  }

  /**
   * `expected` is the set of ActorRefs whose replies we are waiting for,
   * `insertConfirmed` tracks whether the copy of this node to the new tree has been confirmed.
   */
  def copying(expected: Set[ActorRef], insertConfirmed: Boolean): Receive = ???

}
