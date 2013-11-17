package simulations

import math.random

class EpidemySimulator extends Simulator {

  def randomBelow(i: Int) = (random * i).toInt

  protected[simulations] object SimConfig {
    val population: Int = 300
    val roomRows: Int = 8
    val roomColumns: Int = 8

    val prevalenceRate = 0.01
    val transmissibilityRate = 0.40
    val deathRate = 0.25

    val moveDays = 5
  }

  import SimConfig._

  val persons: List[Person] = {
    val initial: Int = (population * prevalenceRate).toInt
    var ps = List[Person]()
    // Create the initial infected people
    for (i <- 0 until initial) {
      val p = new Person(i)
      p.infected = true
      ps = p :: ps
      p.postInfectionActions
    }
    // Create the other population
    for (i <- 0 until (population - initial)) {
      val p = new Person(i)
      ps = p :: ps
    }
    ps
  }

  for (p <- persons) p.move

  class Person(val id: Int) {
    // Health
    var infected = false // contated infection by transmissibilityRate chance
    
    var sick = false // after 6 days of infection will become sick
    var dead = false // after 14 days of infection can die by deathRate chance. Cannot move once dead
    var immune = false // after 16 days of infection. 

    // Current Room
    var row: Int = randomBelow(roomRows)
    var col: Int = randomBelow(roomColumns)

    def isHealthy = !infected
    def isVisibleInfections = sick || dead
    def isVisiblyInfectedRoom(): Boolean = {
      return !persons.filter(p => p.row == row && p.col == col && p.isVisibleInfections).isEmpty
    }
    def isInfectious = infected
    def isInfectedRoom(): Boolean = {
      return !persons.filter(p => p.row == row && p.col == col && p.isInfectious).isEmpty
    }

    def healAction(): Unit = {
      immune = false
      infected = false
    }

    def immuneAction(): Unit = {
      if (!dead) {
        sick = false
        immune = true
      }
    }

    def sickAction(): Unit = {
      sick = true
    }

    def deathAction(): Unit = {
      dead = random > deathRate
      if (dead) {
        sick = false
      }
    }

    def postInfectionActions(): Unit = {
      afterDelay(6)(sickAction)
      afterDelay(14)(deathAction)
      afterDelay(16)(immuneAction)
      afterDelay(18)(healAction)
    }

    def moveAction(): Unit = {
      // Dead person does not move
      if (!dead) {
        // Move if not in visibly infectious room or immune
        if (!isVisiblyInfectedRoom || immune) {

          // Actually move now
          def neighbourRooms(): Vector[Pair[Int, Int]] = {
            val left = Pair(row, if (col == 0) 7 else (col - 1))
            val right = Pair(row, if (col == 7) 0 else (col + 1))
            val top = Pair(if (row == 0) 7 else row - 1, col)
            val bottom = Pair(if (row == 7) 0 else row + 1, col)
            Vector(left, right, top, bottom)
          }
          val newRoomIndex = (randomBelow(4)).toInt
          val newRoom = neighbourRooms()(newRoomIndex)
          row = newRoom._1
          col = newRoom._2

          if (isInfectedRoom && isHealthy) {
            infected = random > transmissibilityRate
            if (infected) {
              postInfectionActions
            }
          }
        }
        move()
      }
    }

    def move() = {
      val moveDelay = randomBelow(moveDays) + 1
      afterDelay(moveDelay)(moveAction)
    }
  }
}
