package net.mikolak.stream_bullethell

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, OverflowStrategy, Supervision}
import com.badlogic.gdx.graphics.g2d.{BitmapFont, SpriteBatch}
import com.badlogic.gdx.graphics.{GL20, OrthographicCamera}
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.physics.box2d.BodyDef.BodyType
import com.badlogic.gdx.physics.box2d._
import com.badlogic.gdx.{Game, Gdx, ScreenAdapter}
import com.badlogic.gdx.utils.{Array => ArrayGdx}

import scala.collection.JavaConverters._
import scala.util.Random

object config {
  object world {
    object gen {
      val NumCircles = 3
      val MaxForce = 300
      val ForceApplyTickInterval = 100
    }

    val Width = Dim(160)
    val Height = Dim(100)
  }
}

class BulletHell extends Game {

  override def create(): Unit =
    setScreen(new MainScreen)

}

class MainScreen extends ScreenAdapter {

  lazy val camera = new OrthographicCamera()
  val batch: SpriteBatch = new SpriteBatch()

  var tick = 1L

  val loggingDecider: Supervision.Decider = { e =>
    println(s"Exception when processing game loop: $e")
    Supervision.Stop
  }
  implicit val actorSystem = ActorSystem("game")
  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(actorSystem)
    .withSupervisionStrategy(loggingDecider))

  val tickSource = Source.actorRef[GameState](0, OverflowStrategy.dropNew)
  var tickActor: Option[ActorRef] = None

  lazy val world = new World(new Vector2(0, 0), false)

  val debugRenderer = new Box2DDebugRenderer()

  lazy val font = {
    val f = new BitmapFont()
    f.getData.setScale(0.5f)
    f
  }

  override def show() = {
    camera.setToOrtho(false, config.world.Width.size, config.world.Height.size)

    val tickSettingFlow = {

      Flow[GameState].map { case gs@GameState(delta, bodies) =>
        if(bodies.isEmpty) {
          for(_ <- 1 to config.world.gen.NumCircles) {
            val randomLocation = new Vector2(Random.nextInt(config.world.Width.size), Random.nextInt(config.world.Height.size))
            createSphere(randomLocation)
          }
        } else {
          import config.world.gen
          def randomForceComponent = Random.nextInt(2*gen.MaxForce)-gen.MaxForce

          for(body <- bodies) {
            if(tick % gen.ForceApplyTickInterval == 0) {
              body.applyForceToCenter(randomForceComponent, randomForceComponent, true)
            }
          }
        }
        world.step(delta, 6, 2)

        tick += 1
        gs
      }
    }

    val graph = tickSource.via(tickSettingFlow).to(Sink.ignore)

    tickActor = Some(graph.run())
  }

  private def createSphere(center: Vector2) = {
    val bodyDef = new BodyDef
    bodyDef.`type` = BodyType.DynamicBody
    bodyDef.position.set(center)

    val circle = new CircleShape()
    circle.setRadius(1)

    val fixtureDef = new FixtureDef()
    fixtureDef.shape = circle
    fixtureDef.density = 1f

    val body = world.createBody(bodyDef)
    body.createFixture(fixtureDef)
    fixtureDef.shape.dispose()
  }

  override def render(delta: TickDelta) = {
    tickActor.foreach { actor =>
      val bodyArray = ArrayGdx.of(classOf[Body])
      world.getBodies(bodyArray)
      actor ! GameState(delta, bodyArray.asScala.toList)
    }

    Gdx.gl.glClearColor(0, 0, 0.5f, 1)
    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
    camera.update()
    batch.setProjectionMatrix(camera.combined)
    batch.begin()
    font.draw(batch, s"Tick: $tick", 0, font.getCapHeight)
    batch.end()

    debugRenderer.render(world, camera.combined)
  }


  override def hide() =
    actorSystem.terminate()
}

case class GameState(delta: TickDelta, bodies: List[Body])

case class Dim(d: Float) extends AnyVal {
  def size = d.toInt
}