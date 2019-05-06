package net.mikolak.stream_bullethell

import net.mikolak.stream_bullethell.entity.Component

sealed trait Allegiance extends Component
case object Player extends Allegiance
case object Enemy extends Allegiance

object Allegiance {

  def player: Allegiance = Player
  def enemy: Allegiance = Enemy

}
