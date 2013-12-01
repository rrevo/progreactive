package suggestions

package object gui {

  /**
   * scala.swing.Reactions.Reaction {
   *   type Reaction = PartialFunction[Event, Unit]
   * }
   * 
   */
  object Reaction {
    def apply(r: scala.swing.Reactions.Reaction) = r
  }

}