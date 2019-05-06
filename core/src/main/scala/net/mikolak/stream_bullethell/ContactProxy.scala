package net.mikolak.stream_bullethell

import akka.actor.ActorRef
import com.badlogic.gdx.physics.box2d.{Body, Contact, ContactImpulse, ContactListener, Manifold}

class ContactProxy(outActor: ActorRef) extends ContactListener {
  override def beginContact(contact: Contact): Unit = outActor ! BeginContact(contact)

  override def endContact(contact: Contact): Unit = outActor ! EndContact(contact)

  override def preSolve(contact: Contact, oldManifold: Manifold): Unit = ()

  override def postSolve(contact: Contact, impulse: ContactImpulse): Unit = ()
}

sealed trait ContactEvent {
  def contact: Contact
}
case class BeginContact(contact: Contact) extends ContactEvent
case class EndContact(contact: Contact) extends ContactEvent

object contactSyntax {

  implicit class ContactOps(val contact: Contact) extends AnyVal {
    def filterBodies(c1: Body => Boolean, c2: Body => Boolean): Option[(Body, Body)] =
      if (contact.getFixtureA == null || contact.getFixtureB == null) {
        None
      } else {
        val (b1, b2) = (contact.getFixtureA.getBody, contact.getFixtureB.getBody)
        if (c1(b1) && c2(b2)) {
          Some((b1, b2))
        } else if (c1(b2) && c2(b1)) {
          Some((b2, b1))
        } else {
          None
        }
      }
  }
}
