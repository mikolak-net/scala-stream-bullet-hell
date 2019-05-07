package net.mikolak.stream_bullethell

import akka.actor.ActorRef
import com.badlogic.gdx.physics.box2d.{Body, Contact, ContactImpulse, ContactListener, Manifold}
import net.mikolak.stream_bullethell.entity.Entity
import cats.implicits._

class ContactProxy(outActor: ActorRef) extends ContactListener {
  override def beginContact(contact: Contact): Unit = outActor ! BeginContact(contact)

  override def endContact(contact: Contact): Unit = outActor ! EndContact(contact)

  override def preSolve(contact: Contact, oldManifold: Manifold): Unit =
    outActor ! PreSolve(contact, oldManifold)

  override def postSolve(contact: Contact, impulse: ContactImpulse): Unit =
    outActor ! PostSolve(contact, impulse)
}

sealed trait ContactEvent {
  def contact: Contact
}
case class BeginContact(contact: Contact) extends ContactEvent
case class EndContact(contact: Contact) extends ContactEvent
case class PreSolve(contact: Contact, oldManifold: Manifold) extends ContactEvent
case class PostSolve(contact: Contact, impulse: ContactImpulse) extends ContactEvent

object contactSyntax {

  implicit class ContactOps(val contact: Contact) extends AnyVal {
    def filterEntities(c1: Entity => Boolean, c2: Entity => Boolean): Option[(Entity, Entity)] = {
      val fixtures = (contact.getFixtureA, contact.getFixtureB)

      if (fixtures._1 == null || fixtures._2 == null) {
        none
      } else {
        val (e1, e2) = fixtures.bimap(_.getBody.entity, _.getBody.entity)
        if (e1 == null || e2 == null) {
          none
        } else {
          if (c1(e1) && c2(e2)) {
            (e1, e2).some
          } else if (c1(e2) && c2(e1)) {
            (e2, e1).some
          } else {
            none
          }
        }
      }
    }
  }

  implicit class BodyOps(val body: Body) extends AnyVal {
    def entity: Entity = body.getUserData.asInstanceOf[Entity]
  }
}
