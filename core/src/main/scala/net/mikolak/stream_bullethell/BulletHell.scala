package net.mikolak.stream_bullethell

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.stream.scaladsl.{
  Broadcast,
  Flow,
  GraphDSL,
  Keep,
  Sink,
  SinkQueueWithCancel,
  Source,
  ZipN
}
import akka.stream._
import com.badlogic.gdx.Input.Keys
import com.badlogic.gdx.graphics.g2d.{BitmapFont, SpriteBatch}
import com.badlogic.gdx.graphics.{GL20, OrthographicCamera}
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.physics.box2d.BodyDef.BodyType
import com.badlogic.gdx.physics.box2d._
import com.badlogic.gdx.{Game, Gdx, ScreenAdapter}
import com.badlogic.gdx.utils.{Array => ArrayGdx}
import net.mikolak.stream_bullethell.config.world

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
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

  type Action = () => Unit
  type ActionQueue = SinkQueueWithCancel[Seq[Action]]

  lazy val camera = new OrthographicCamera()
  val batch: SpriteBatch = new SpriteBatch()

  var tick = 1L

  val loggingDecider: Supervision.Decider = { e =>
    println(s"Exception when processing game loop: $e")
    Supervision.Stop
  }
  implicit val actorSystem = ActorSystem("game")
  implicit val materializer = ActorMaterializer(
    ActorMaterializerSettings(actorSystem)
      .withSupervisionStrategy(loggingDecider))

  val tickSource = Source.actorRef[GameState](bufferSize = 1, OverflowStrategy.dropNew)
  val inputSource = Source.actorRef[KeyboardInput](bufferSize = 0, OverflowStrategy.dropTail)
  var tickActor: Option[ActorRef] = None
  var actionQueue: Option[ActionQueue] = None

  lazy val world = new World(new Vector2(0, 0), false)

  val debugRenderer = new Box2DDebugRenderer()

  lazy val font = {
    val f = new BitmapFont()
    f.getData.setScale(0.5f)
    f
  }

  override def show() = {
    camera.setToOrtho(false, config.world.Width.size, config.world.Height.size)

    val generator = (g: GameState) => { () =>
      {
        if (g.bodies.isEmpty) {
          for (_ <- 1 to config.world.gen.NumCircles) {
            val randomLocation = new Vector2(Random.nextInt(config.world.Width.size),
                                             Random.nextInt(config.world.Height.size))
            createSphere(randomLocation, Enemy)
          }

          //player body
          createSphere(new Vector2(config.world.Width.size, config.world.Height.size).scl(0.5f),
                       Player,
                       radius = 2f)
        }
      }
    }

    val mover = (g: GameState) => {
      import config.world.gen
      def randomForceComponent = Random.nextInt(2 * gen.MaxForce) - gen.MaxForce

      () =>
        {
          for (body <- g.bodies if body.getUserData == Enemy) {
            if (tick % gen.ForceApplyTickInterval == 0) {
              body.applyForceToCenter(randomForceComponent, randomForceComponent, true)
            }
          }
        }
    }

    val tickIncrementer = (_: GameState) => { () =>
      {
        tick += 1
      }
    }

    val worldUpdater = (g: GameState) => { () =>
      {
        world.step(g.delta, 6, 2)
      }
    }

    val inputHandler = (g: GameState) => { () =>
      {
        for {
          playerBody <- g.bodies.find(_.getUserData == Player)
          e <- g.events
        } {
          val inputMults = e match {
            case KeyUp(keycode) =>
              keycode match {
                case Keys.LEFT  => (-1f, 0f)
                case Keys.RIGHT => (1f, 0f)
                case Keys.UP    => (0f, 1f)
                case Keys.DOWN  => (0f, -1f)
                case _          => (0f, 0f)
              }
            case _ => (0f, 0f)
          }

          val v = (new Vector2(_: Float, _: Float)).tupled(inputMults).scl(1000f)
          playerBody.applyForceToCenter(v, true)
        }
      }
    }

    val bufferSize = 100

    val graph =
      tickSource
        .zipWithMat(
          inputSource
            .batch(bufferSize, List(_))(_ :+ _)
            .prepend(Source.single(List.empty[KeyboardInput]))
            .expand(elem =>
              Iterator.single(elem) ++ Iterator.continually(List.empty[KeyboardInput]))
        )((gs, es) => gs.copy(events = es))(Keep.both)
        .via(setUpLogic(List(generator, mover, worldUpdater, tickIncrementer, inputHandler)))
        .toMat(Sink.queue())(Keep.both)

    val ((sourceActor, inputActor), sinkQueue) = graph.run()

    tickActor = Some(sourceActor)
    actionQueue = Some(sinkQueue)
    Gdx.input.setInputProcessor(new KeyboardProxy(inputActor))
  }

  private def setUpLogic(
      elements: List[(GameState) => Action]): Flow[GameState, Seq[Action], NotUsed] =
    Flow.fromGraph(GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._
      val scatter = b.add(Broadcast[GameState](elements.size))
      val gather = b.add(ZipN[Action](elements.size))

      for (e <- elements) {
        scatter ~> b.add(Flow.fromFunction(e)) ~> gather
      }

      FlowShape(scatter.in, gather.out)
    })

  private def createSphere(center: Vector2, bodyType: BodyType, radius: Float = 1) = {
    val bodyDef = new BodyDef
    bodyDef.`type` = BodyType.DynamicBody
    bodyDef.position.set(center)

    val circle = new CircleShape()
    circle.setRadius(radius)

    val fixtureDef = new FixtureDef()
    fixtureDef.shape = circle
    fixtureDef.density = 1f

    val body = world.createBody(bodyDef)
    body.createFixture(fixtureDef)
    fixtureDef.shape.dispose()
    body.setUserData(bodyType)
  }

  override def render(delta: TickDelta) = {
    tickActor.foreach { actor =>
      val bodyArray = ArrayGdx.of(classOf[Body])
      world.getBodies(bodyArray)
      actor ! GameState(delta, bodyArray.asScala.toList)
    }

    import scala.concurrent.Await.result

    for {
      q <- actionQueue
      actions <- result(q.pull(), Duration.Inf)
      a <- actions
    } {
      a()
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

case class GameState(delta: TickDelta, bodies: List[Body], events: List[KeyboardInput] = List.empty)

case class Dim(d: Float) extends AnyVal {
  def size = d.toInt
}
