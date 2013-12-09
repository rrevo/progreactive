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

/**
 * Actor that clients use for the Binary Tree
 */
class BinaryTreeSet extends Actor {
  import BinaryTreeSet._
  import BinaryTreeNode._

  def createRoot: ActorRef = context.actorOf(BinaryTreeNode.props(0, initiallyRemoved = true))

  var root = createRoot

  def receive = normal

  /*
   * Normal mode for messages.
   *  
   * Operation messages are handled by forwarding to the root node
   * 
   * When the GC message is received, garbage collection of the removed nodes is started and 
   * garbageCollecting mode become's
   * 
   */
  def normal: Receive = {
    case c: Contains => root ! c
    case i: Insert => root ! i
    case r: Remove => root ! r
    case GC => {

      // Copy from root to newRoot and replace the root
      val newRoot = createRoot
      root ! CopyTo(newRoot)
      root = newRoot
      context.become(garbageCollecting(newRoot, Queue.empty))
    }
  }

  /*
   * Garbage collection mode for messages
   * 
   * Operation messages are queued
   * 
   * When the Copy from root to newRoot is complete, pending operations are processed. Normal
   * mode is then become'ed
   *
   * @param newRoot is the root of the new binary tree where we want to copy
   * all non-removed elements into.
   * @param pendingQueue is the list of messages that were received during the GC process
   */
  def garbageCollecting(newRoot: ActorRef, pendingQueue: Queue[Operation]): Receive = {
    case c: Contains => context.become(garbageCollecting(newRoot, pendingQueue.enqueue(c)))
    case i: Insert => context.become(garbageCollecting(newRoot, pendingQueue.enqueue(i)))
    case r: Remove => context.become(garbageCollecting(newRoot, pendingQueue.enqueue(r)))
    case GC => // Ignore
    case CopyFinished => {
      pendingQueue.foreach(op => root ! op)
      context.become(normal)
    }
  }
}

object BinaryTreeNode {
  trait Position

  case object Left extends Position
  case object Right extends Position

  case class CopyTo(treeNode: ActorRef)
  case object CopyFinished

  def props(elem: Int, initiallyRemoved: Boolean) = Props(classOf[BinaryTreeNode], elem, initiallyRemoved)
}

/**
 * A node in the Binary Search Tree
 *
 */
class BinaryTreeNode(val elem: Int, val initiallyRemoved: Boolean) extends Actor {
  import BinaryTreeNode._
  import BinaryTreeSet._

  var subtrees = Map[Position, ActorRef]()

  /*
   * State whether this node is removed from the tree
   */
  var removed = initiallyRemoved

  def receive = normal

  /*
   * Normal mode of processing messages
   * Operations are handled or may be deferred to the left or right nodes
   * 
   * On CopyTo, garbage collection has started and nodes that are not 'removed' are to be
   * CopyTo to the treeNode
   */
  def normal: Receive = {
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
    case ct: CopyTo => {
      // Insert elem in the treeNode?
      if (!removed) {
        ct.treeNode ! Insert(self, 0, elem)
      }
      // CopyTo child nodes
      val subtreeRefs = subtrees.values.toList
      subtreeRefs.foreach(tree => tree ! CopyTo(ct.treeNode))

      // Change mode and wait for completion notifications
      context.become(copying(sender, removed, subtreeRefs.size))
      if (removed && subtreeRefs.isEmpty) {
        stop(sender)
      }
    }
  }

  /*
   * Wait for notifications in the garbage collectoin mode
   * 
   * @param inserted is for state of self.elem copy
   * @param childCopyCount is the number of children that are pending copy notification
   * 
   * Once everything has been notified, this actor can be stopped and needs to notify the
   * requester actor
   */
  def copying(requester: ActorRef, inserted: Boolean, childCopyCount: Int): Receive = {
    case of: OperationFinished => {
      if (checkStop(true, childCopyCount)) {
        stop(requester)
      } else {
        context.become(copying(requester, true, childCopyCount))
      }
    }
    case CopyFinished => {
      if (checkStop(inserted, childCopyCount - 1)) {
        stop(requester)
      } else {
        context.become(copying(requester, inserted, childCopyCount - 1))
      }
    }
  }

  def checkStop(inserted: Boolean, copyCount: Int): Boolean = {
    inserted && copyCount == 0
  }

  def stop(requester: ActorRef): Unit = {
    requester ! CopyFinished
    context.stop(self)
  }

}
