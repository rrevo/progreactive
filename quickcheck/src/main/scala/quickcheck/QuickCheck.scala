package quickcheck

import common._
import org.scalacheck._
import Arbitrary._
import Gen._
import Prop._
import scala.collection.immutable.Nil

abstract class QuickCheckHeap extends Properties("Heap") with IntHeap {

  property("insert into empty") = forAll { a: Int =>
    val h = insert(a, empty)
    findMin(h) == a
  }

  property("insert min existing") = forAll { (h: H) =>
    val m = if (isEmpty(h)) 0 else findMin(h)
    findMin(insert(m, h)) == m
  }

  property("insert min after delete") = forAll { (h: H) =>
    val m = if (isEmpty(h)) 0 else findMin(h)
    findMin(insert(m, deleteMin(h))) == m
  }

  property("insert twice") = forAll { a: Int =>
    val h = insert(a, empty)
    findMin(h) == a
    forAll { b: Int =>
      val h2 = insert(b, h)
      val min = findMin(h2)
      if (a < b) {
        min == a
      } else {
        min == b
      }
    }
  }

  property("same min of two") = forAll { (h1: H) =>
    forAll { (h2: H) =>
      val min = Math.min(findMin(h1), findMin(h2))
      val minThrice = meld(insert(min, h1), insert(min, h2))
      val minTwice = deleteMin(minThrice)
      val minOnce = deleteMin(minTwice)
      findMin(minThrice) == findMin(minTwice) &&
        findMin(minTwice) == findMin(minOnce) &&
        findMin(minOnce) == min
    }
  }

  property("delete from size 1") = forAll { a: Int =>
    val h = insert(a, empty)
    isEmpty(deleteMin(h))
  }

  def elements(intH: H, es: List[A]): List[A] = {
    if (isEmpty(intH)) {
      es
    } else {
      val v = findMin(intH)
      elements(deleteMin(intH), v :: es)
    }
  }

  def checkOrder(es: List[A]): Boolean = es match {
    case x :: y :: zs => {
      if (x >= y) checkOrder(es.tail)
      else false
    }
    case x :: Nil => true
    case Nil => true
  }

  property("delete order") = forAll { (h: H) =>
    val es = elements(h, List())
    checkOrder(es)
  }

  property("meld minimum") = forAll { (h1: H) =>
    forAll { (h2: H) =>
      val h = meld(h1, h2)
      findMin(h) == Math.min(findMin(h1), findMin(h2))
    }
  }

  property("meld with empty") = forAll { (h1: H) =>
    meld(h1, empty) == h1 && meld(empty, h1) == h1
  }

  property("meld same heap") = forAll { (h1: H) =>
    val h1s = elements(h1, List())
    val h1sTwice = h1s.flatMap(x => List(x, x))
    val h2 = meld(h1, h1)
    val h2s = elements(h2, List())
    h1sTwice == h2s && checkOrder(h2s)
  }

  property("random addition") = forAll { (h: H) =>
    if (isEmpty(h))
      true
    else {
      val min = findMin(h)
      val hMinus = deleteMin(h)

      val r = new java.util.Random()
      val newInt = r.nextInt()
      val hPrime = insert(newInt, hMinus)

      val hTwice = meld(h, hPrime)

      if (min <= newInt) {
        findMin(hTwice) == min
      } else {
        findMin(hTwice) == newInt
      }
    }
  }
  
  property("meld similar rank heaps") = {
    val genIntList = Gen.containerOfN[List,Int](100, Arbitrary.arbitrary[Int])
    val l1 = genIntList.sample.get
    val l2 = genIntList.sample.get

    val h1 = l1.foldLeft(empty)((h, x) => insert(x, h))
    val h2 = l2.foldLeft(empty)((h, x) => insert(x, h))
    
    val hBoth = meld(h1, h2)
    
    elements(hBoth, List()) == (l1 ++ l2).sorted.reverse
  }

  lazy val genHeap: Gen[H] = for {
    i <- arbitrary[Int]
    h <- oneOf(value(empty), genHeap)
  } yield insert(i, h)

  implicit lazy val arbHeap: Arbitrary[H] = Arbitrary(genHeap)

}
