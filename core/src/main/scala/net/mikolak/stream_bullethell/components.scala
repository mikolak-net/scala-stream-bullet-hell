package net.mikolak.stream_bullethell

import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.physics.box2d.BodyDef.BodyType
import com.badlogic.gdx.physics.box2d.{Body, BodyDef, CircleShape, FixtureDef, World}
import net.mikolak.stream_bullethell.entity.{Component, Entity}

object components {

  case class BodyComponent private (body: Body) extends Component
  object BodyComponent {
    def sphere(entity: Entity,
               center: Vector2,
               radius: Float = 1,
               initialVelocity: Vector2 = Vector2.Zero,
               bodyType: BodyType = BodyType.DynamicBody)(implicit world: World): BodyComponent = {
      val bodyDef = new BodyDef
      bodyDef.`type` = bodyType
      bodyDef.position.set(center)

      val circle = new CircleShape()
      circle.setRadius(radius)

      val fixtureDef = new FixtureDef()
      fixtureDef.shape = circle
      fixtureDef.density = 1f

      val body = world.createBody(bodyDef)
      body.createFixture(fixtureDef)
      fixtureDef.shape.dispose()

      if (initialVelocity != Vector2.Zero) {
        body.setLinearVelocity(initialVelocity)
      }

      body.setUserData(entity)
      BodyComponent(body)
    }
  }
  case class Health(hp: Int) extends Component
  case class Projectile(dmg: Int) extends Component
  case class Controllable(speed: Float) extends Component

}
