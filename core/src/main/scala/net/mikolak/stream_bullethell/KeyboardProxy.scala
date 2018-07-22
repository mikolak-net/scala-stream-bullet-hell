package net.mikolak.stream_bullethell

import akka.actor.ActorRef
import com.badlogic.gdx.InputAdapter

class KeyboardProxy(outActor: ActorRef) extends InputAdapter {
  override def keyUp(keycode: Int) = {
    outActor ! KeyUp(keycode)
    true
  }

  override def keyTyped(character: Char) = {
    outActor ! KeyTyped(character)
    true
  }

  override def keyDown(keycode: Int) = {
    outActor ! KeyUp(keycode)
    true
  }
}

sealed trait KeyboardInput
case class KeyUp(keycode: Int) extends KeyboardInput
case class KeyDown(keycode: Int) extends KeyboardInput
case class KeyTyped(character: Char) extends KeyboardInput
