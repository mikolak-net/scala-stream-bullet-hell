package net.mikolak.stream_bullethell

import java.time.Instant

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.stream._
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
import com.badlogic.gdx.Input.Keys
import com.badlogic.gdx.graphics.g2d.{BitmapFont, SpriteBatch}
import com.badlogic.gdx.graphics.{GL20, OrthographicCamera}
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.physics.box2d.BodyDef.BodyType
import com.badlogic.gdx.physics.box2d._
import com.badlogic.gdx.{Game, Gdx, ScreenAdapter}
import net.mikolak.stream_bullethell.components._
import net.mikolak.stream_bullethell.contactSyntax._
import net.mikolak.stream_bullethell.entity.Entity
import net.mikolak.travesty
import net.mikolak.travesty.registry._

import scala.concurrent.duration.Duration
import scala.reflect.runtime.universe._
import scala.util.Random
import cats.instances.tuple._
import cats.syntax.bifunctor._
import net.mikolak.stream_bullethell.components.global.HighScore

object config {

  object scoring {
    val PointsPerDestroyed = 10
    val PointPerTimeUnitSurvived = 1
    val TimeUnitSurvivedInMs = 10000
  }

  object world {
    object gen {
      object enemies {
        val MaxSpawned = 5
        val StartHealth = 20
        val ContactDamage = 20
        val MoveForce = 100
        val ForceApplyTickInterval = 10
      }
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
  type ~~>[-A, +B] = PartialFunction[A, B]

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

  // format: off
  private def batchedLazySource[T: TypeTag](batchBufferSize: Int = 100): Source[List[T], ActorRef] =
    Source
      .actorRef[T](bufferSize = 0, OverflowStrategy.dropTail).↓
      .batch(batchBufferSize, List(_))(_ :+ _).↓
      .extrapolate(_ => Iterator.continually(List.empty[T]), Some(List.empty[T])).↓
  // format: on

  var tickActor: Option[ActorRef] = None
  var actionQueue: Option[ActionQueue] = None

  implicit lazy val world = new World(new Vector2(0, 0), false)
  val globalEntity: Entity = Entity()
  val entities: collection.mutable.Buffer[Entity] = collection.mutable.Buffer.empty[Entity]

  val debugRenderer = new Box2DDebugRenderer()

  lazy val font = {
    val f = new BitmapFont()
    f.getData.setScale(0.5f)
    f
  }

  override def show() = {
    camera.setToOrtho(false, config.world.Width.size, config.world.Height.size)

    val coreGenerator: GameState => Action = (g: GameState) => { () =>
      {
        if (g.entities.isEmpty) {
          //player body
          val playerEntity = Entity()
          val body = BodyComponent.sphere(
            playerEntity,
            new Vector2(config.world.Width.size, config.world.Height.size).scl(0.5f),
            radius = 2f)
          playerEntity.update(body)
          playerEntity.update(Allegiance.player)
          playerEntity.update(Controllable(1000f))
          playerEntity.update(Health(200))
          g.entities.append(playerEntity)

          g.globalEntity.update(global.HighScore(0))
          g.globalEntity.update(global.StartTime(System.currentTimeMillis()))
          g.globalEntity.update(global.DestroyedAmount(0))
        }

        ()
      }
    }

    val enemyGenerator: GameState => Action = (g: GameState) => {
      import config.world.gen.enemies._
      () =>
        {
          for (_ <- g.entities.count(_.get[Allegiance].exists(_ == Enemy)) to config.world.gen.enemies.MaxSpawned) {
            val randomLocation = new Vector2(Random.nextInt(config.world.Width.size),
                                             Random.nextInt(config.world.Height.size))
            val enemyEntity = Entity()
            val body = BodyComponent.sphere(enemyEntity, randomLocation)
            enemyEntity.update(body)
            enemyEntity.update(Allegiance.enemy).update(Health(StartHealth))
            enemyEntity.update(ContactDamaging(ContactDamage))
            g.entities.append(enemyEntity)
          }
        }
    }

    val enemyAi = (g: GameState) => {
      import config.world.gen
      () =>
        {
          for {
            enemy <- g.entities
            if enemy
              .get[Allegiance]
              .contains(Enemy) && tick % gen.enemies.ForceApplyTickInterval == 0
            player <- g.entities.find(_.get[Allegiance].exists(_ == Player))
            playerBody <- player.get[BodyComponent]
            enemyBody <- enemy.get[BodyComponent]
          } {
            val playerLocation = playerBody.body.getPosition
            val enemyLocation = enemyBody.body.getPosition
            val forceVector = playerLocation.cpy.sub(enemyLocation).setLength(gen.enemies.MoveForce)
            enemyBody.body.applyForceToCenter(forceVector, true)
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

    val controlHandler = (g: GameState) => {
      val KeyMultipliers: KeyboardInput ~~> (Float, Float) = {
        case KeyUp(Keys.LEFT)  => (-1f, 0f)
        case KeyUp(Keys.RIGHT) => (1f, 0f)
        case KeyUp(Keys.UP)    => (0f, 1f)
        case KeyUp(Keys.DOWN)  => (0f, -1f)
      }

      () =>
        {
          for {
            playerEntity <- g.entities.find(_.has[Controllable])
            e <- g.keyEvents.filter(KeyMultipliers.isDefinedAt)
            bodyComponent <- playerEntity.get[BodyComponent]
            controllable <- playerEntity.get[Controllable]
          } {
            val inputMults = KeyMultipliers(e)
            val v = (new Vector2(_: Float, _: Float)).tupled(inputMults).scl(controllable.speed)
            bodyComponent.body.applyForceToCenter(v, true)
          }
        }
    }

    val shootUiHandler = (g: GameState) => {
      val ProjectileSpeed = 2000f
      val SpacingOffsetScale = 1.3f
      val KeyMultipliers: KeyboardInput ~~> (Float, Float) = {
        case KeyUp(Keys.A) => (-1f, 0f)
        case KeyUp(Keys.D) => (1f, 0f)
        case KeyUp(Keys.W) => (0f, 1f)
        case KeyUp(Keys.S) => (0f, -1f)
      }

      () =>
        {
          for {
            playerEntity <- g.entities.find(_.get[Allegiance].contains(Player))
            playerBody <- playerEntity.get[BodyComponent]
            e <- g.keyEvents.filter(KeyMultipliers.isDefinedAt)
          } {
            val inputMults = KeyMultipliers(e)

            val dirVector = (new Vector2(_: Float, _: Float)).tupled(inputMults)

            val offsetLoc = dirVector
              .cpy()
              .scl(playerBody.body.getFixtureList.first().getShape.getRadius)
              .scl(SpacingOffsetScale)

            val v = (new Vector2(_: Float, _: Float)).tupled(inputMults).scl(ProjectileSpeed)
            val projectileEntity = Entity()
            val projectileBody = BodyComponent.sphere(
              projectileEntity,
              playerBody.body.getWorldCenter.cpy.add(offsetLoc),
              initialVelocity = v,
              bodyType = BodyType.KinematicBody,
              sensor = true)
            projectileEntity.update(projectileBody).update(Projectile(10)).update(Health(10))
            g.entities.append(projectileEntity)
          }
        }
    }

    val shootCollisionHandler = (g: GameState) => { () =>
      {
        for {
          (projectile, enemy) <- g.contactEvents
            .filter(_.isInstanceOf[BeginContact])
            .flatMap(
              _.contact
                .filterEntities(_.has[Projectile], _.get[Allegiance].contains(Enemy))
                .toList)
          projectileSpec <- projectile.get[Projectile]
          projectileHp <- projectile.get[Health]
          enemyHp <- enemy.get[Health]
        } {
          enemy.update(enemyHp.copy(enemyHp.hp - projectileSpec.dmg))
          projectile.update(projectileHp.copy(projectileHp.hp - projectileSpec.dmg))

          println("BUMP!!")
        }
      }
    }

    val contactDamageHandler = (g: GameState) => { () =>
      {
        for {
          (contactDamager, damaged) <- g.contactEvents
            .filter(_.isInstanceOf[BeginContact])
            .flatMap(
              _.contact
                .filterEntities(_.has[ContactDamaging], _.has[Health])
                .toList)
          if contactDamager.get[Allegiance].exists(a => damaged.get[Allegiance].exists(a != _))
          dmg <- contactDamager.get[ContactDamaging].map(_.dmg)
          health <- damaged.get[Health]
          newHp = health.hp - dmg
        } {
          damaged.update(health.copy(newHp))
          println(s"Contact damage: $newHp")
        }
      }
    }

    val destructionHandler = (g: GameState) => { () =>
      for {
        healthEntity <- g.entities.filter(_.has[Health])
        if healthEntity.get[Health].exists(_.hp <= 0)
        bodyComponent <- healthEntity.get[BodyComponent]
      } {
        world.destroyBody(bodyComponent.body)
        entities.remove(entities.indexOf(healthEntity))

        //TODO: change into event model for creation/destruction so this can be split
        if (healthEntity.get[Allegiance].contains(Enemy)) {
          g.globalEntity.updateWith[global.DestroyedAmount](amount =>
            amount.copy(amount.amount + 1))
        }
      }
    }

    val highScoreCounter = (g: GameState) => {
      import config.scoring._
      () =>
        for {
          startTime <- g.globalEntity.get[global.StartTime]
          destroyedAmount <- g.globalEntity.get[global.DestroyedAmount]
        } {
          val survivedPoints = (startTime.timeSurvivedInMs / TimeUnitSurvivedInMs).toInt * PointPerTimeUnitSurvived
          val destroyedPoints = destroyedAmount.amount * PointsPerDestroyed

          g.globalEntity.updateWith[global.HighScore](currentHs =>
            currentHs.copy(survivedPoints + destroyedPoints))
        }
    }

    val graph =
      tickSource.↓.zipWithMat(
        batchedLazySource[KeyboardInput]()
      )((gs, es) => gs.copy(keyEvents = es))(Keep.both).↓.zipWithMat( //TODO: generalize
        batchedLazySource[ContactEvent]())((gs, cs) => gs.copy(contactEvents = cs))(Keep.both)
        .via(setUpLogic(List(
          coreGenerator,
          enemyGenerator,
          enemyAi,
          worldUpdater,
          tickIncrementer,
          controlHandler,
          shootUiHandler,
          shootCollisionHandler,
          contactDamageHandler,
          destructionHandler,
          highScoreCounter
        )).↓)
        .toMat(Sink.queue())(Keep.both)

    // Enable if you want graph:
    //println(net.mikolak.travesty.toString(graph, Text))
    //net.mikolak.travesty.toFile(graph, SVG, net.mikolak.travesty.TopToBottom)("/tmp/gamegraph.svg")

    val (((sourceActor, inputActor), contactActor), sinkQueue) = graph.run()

    tickActor = Some(sourceActor)
    actionQueue = Some(sinkQueue)
    Gdx.input.setInputProcessor(new KeyboardProxy(inputActor))
    world.setContactListener(new ContactProxy(contactActor))
  }

  private def setUpLogic(
      elements: List[GameState => Action]): Flow[GameState, Seq[Action], NotUsed] =
    Flow.fromGraph(GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._
      val scatter = b.add(Broadcast[GameState](elements.size))
      val gather = b.add(ZipN[Action](elements.size))

      import travesty.registry._

      for (e <- elements) {
        scatter ~> b.add(Flow.fromFunction(e).↓) ~> gather
      }

      FlowShape(scatter.in, gather.out)
    })

  override def render(delta: TickDelta) = {
    tickActor.foreach { actor =>
//      val bodyArray = ArrayGdx.of(classOf[Body])
//      world.getBodies(bodyArray)
      actor ! GameState(delta, entities, globalEntity)
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
    val currentScore = globalEntity.get[global.HighScore].map(_.score).getOrElse(0)
    font.draw(batch, s"Score: $currentScore", 0, font.getCapHeight)
    batch.end()

    debugRenderer.render(world, camera.combined)
  }

  override def hide() =
    actorSystem.terminate()
}

case class GameState(delta: TickDelta,
                     entities: collection.mutable.Buffer[Entity],
                     globalEntity: Entity,
                     keyEvents: List[KeyboardInput] = List.empty,
                     contactEvents: List[ContactEvent] = List.empty)

case class Dim(d: Float) extends AnyVal {
  def size = d.toInt
}
