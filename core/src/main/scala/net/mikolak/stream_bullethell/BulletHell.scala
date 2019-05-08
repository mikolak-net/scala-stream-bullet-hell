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
import com.badlogic.gdx.graphics.g2d.{BitmapFont, SpriteBatch, TextureRegion}
import com.badlogic.gdx.graphics.{GL20, OrthographicCamera, Texture}
import com.badlogic.gdx.math.{Rectangle, Vector2}
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
    val TimeUnitSurvivedInMs = 10000L
  }

  object display {
    val SpriteScaling = 12 //TODO: dynamic, resolution-based scaling
  }

  object world {

    object player {
      val MercyInvulnerabilityGracePeriod = 1000L
    }

    object gen {
      object enemies {
        val StartSpawned = 5
        val NewSpawnEveryMs = 20000L
        val StartHealth = 20
        val ContactDamage = 20
        val MoveForce = 100
        val ForceApplyTickInterval = 10
        val StopAngleThreshold = 90
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

  implicit lazy val world = new World(new Vector2(0, 0), false) //TODO: move to component
  val globalEntity: Entity = Entity()
  val entities: collection.mutable.Buffer[Entity] = collection.mutable.Buffer.empty[Entity]

  val debugRenderer = new Box2DDebugRenderer()

  lazy val font = {
    val f = new BitmapFont()
    f.getData.setScale(0.5f)
    f
  }

  override def show() = {
    val coreGenerator: GameState => Action = (g: GameState) => { () =>
      {
        if (g.entities.isEmpty) {
          //player body
          val playerEntity = Entity()
          val body = BodyComponent.sphere(
            playerEntity,
            new Vector2(config.world.Width.size, config.world.Height.size).scl(0.5f),
            radius = 3f)
          playerEntity.update(body)
          playerEntity.update(Allegiance.player)
          playerEntity.update(Controllable(1000f))
          playerEntity.update(Health(200))
          playerEntity.update(
            MercyInvulnerability(0, config.world.player.MercyInvulnerabilityGracePeriod))
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

          for {
            startTime <- g.globalEntity.get[global.StartTime]
          } {
            val targetSpawned = StartSpawned + (startTime.timeSurvivedInMs / NewSpawnEveryMs).toInt
            for (_ <- g.entities.count(_.get[Allegiance].exists(_ == Enemy)) to targetSpawned) {
              val randomLocation = new Vector2(Random.nextInt(config.world.Width.size),
                                               Random.nextInt(config.world.Height.size))
              val enemyEntity = Entity()
              val body = BodyComponent.sphere(enemyEntity, randomLocation, radius = 1.8f)
              enemyEntity.update(body)
              enemyEntity.update(Allegiance.enemy).update(Health(StartHealth))
              enemyEntity.update(ContactDamaging(ContactDamage))
              g.entities.append(enemyEntity)
            }
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
            val beelineDirection = playerLocation.cpy.sub(enemyLocation)

            if (beelineDirection
                  .angle(enemyBody.body.getLinearVelocity)
                  .abs < gen.enemies.StopAngleThreshold) {
              val forceVector = beelineDirection.setLength(gen.enemies.MoveForce)
              enemyBody.body.applyForceToCenter(forceVector, true)
            } else {
              enemyBody.body.setLinearVelocity(0, 0)
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
            body = bodyComponent.body
          } {
            val inputMults = KeyMultipliers(e)
            val dirVector = (new Vector2(_: Float, _: Float)).tupled(inputMults)
            val angle = dirVector.angleRad()
            body.setTransform(body.getPosition, angle)
            body.applyForceToCenter(dirVector.scl(controllable.speed), true)
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
              bodyType = BodyType.DynamicBody,
              bullet = true,
              density = 0.0001f,
              angle = dirVector.angleRad()
            )
            projectileEntity
              .update(projectileBody)
              .update(Projectile(10))
              .update(Health(10))
              .update(OutOfBoundDestroy)
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
          val currentTime = System.currentTimeMillis()
          val mercy = damaged.get[MercyInvulnerability]

          val shouldBeDamaged = mercy.forall(m => currentTime > m.lastDmgMs + m.gracePeriodMs)

          if (shouldBeDamaged) {
            damaged.update(health.copy(newHp))
            mercy.foreach(m => damaged.update(m.copy(lastDmgMs = currentTime)))
            println(s"Contact damage: $newHp")
          } else {
            println("MERCY")
          }
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

    val outOfBoundTeleportHandler = (g: GameState) => {
      val WorldRectangle = new Rectangle(0f, 0f, config.world.Width.d, config.world.Height.d)
      val TeleportAmount = 2

      () =>
        for {
          bodyEntity <- g.entities
            .filter(_.has[BodyComponent])
            .filter(!_.has[OutOfBoundDestroy.type])
          bodyComponent <- bodyEntity.get[BodyComponent]
          if !WorldRectangle.contains(bodyComponent.body.getWorldCenter)
          body = bodyComponent.body
          bodyCenter = body.getWorldCenter
        } {
          //normalize center vector for world offsets
          val bcN = bodyCenter.cpy().sub(WorldRectangle.x, WorldRectangle.y)

          //adjust out of bounds for x
          if (bcN.x < 0) {
            bcN.x = WorldRectangle.width - TeleportAmount
          } else if (bcN.x > WorldRectangle.width) {
            bcN.x = 0 + TeleportAmount
          }
          //adjust out of bounds for y
          if (bcN.y < 0) {
            bcN.y = WorldRectangle.height - TeleportAmount
          } else if (bcN.y > WorldRectangle.height) {
            bcN.y = 0 + TeleportAmount
          }

          //denormalize
          val newCenter = bcN.add(WorldRectangle.x, WorldRectangle.y)
          body.setTransform(newCenter, body.getAngle)
        }
    }

    val outOfBoundRemovalHandler = (g: GameState) => {
      val WorldRectangle = new Rectangle(0f, 0f, config.world.Width.d, config.world.Height.d)

      () =>
        for {
          bodyEntity <- g.entities
            .filter(_.has[BodyComponent])
            .filter(_.has[OutOfBoundDestroy.type])
          bodyComponent <- bodyEntity.get[BodyComponent]
          if !WorldRectangle.contains(bodyComponent.body.getWorldCenter)
        } {
          //TODO: change into event model for creation/destruction so this can be split
          world.destroyBody(bodyComponent.body)
          entities.remove(entities.indexOf(bodyEntity))
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

    val renderer = (g: GameState) => {
      val camera = new OrthographicCamera()
      lazy val batch: SpriteBatch = new SpriteBatch()
      var initialized = false

      lazy val textureNameBase: Entity ~~> TextureRegion = {
        val List(ship, projectile, enemy) = List("ship", "bolt", "enemy")
          .map(_ + ".png")
          .map(file => new TextureRegion(new Texture(Gdx.files.internal(file))))

        {
          case e if e.get[Allegiance].contains(Player) => ship
          case e if e.get[Allegiance].contains(Enemy)  => enemy
          case e if e.has[Projectile]                  => projectile
        }
      }

      lazy val textureNames = textureNameBase.lift

      () =>
        {
          if (!initialized) {
            camera.setToOrtho(false, config.world.Width.size, config.world.Height.size)
            initialized = true
          }

          Gdx.gl.glClearColor(0, 0, 0f, 1)
          Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
          camera.update()
          batch.setProjectionMatrix(camera.combined)
          batch.begin()

          for {
            e <- g.entities
            bodyComp <- e.get[BodyComponent]
            sprite <- textureNames(e)
          } {

            val scaledSpriteWidth = sprite.getRegionWidth / config.display.SpriteScaling
            val scaledSpriteHeight = sprite.getRegionHeight / config.display.SpriteScaling

            val body = bodyComp.body
            val coord = body.getPosition
            val halfWidth = scaledSpriteWidth / 2
            val halfHeight = scaledSpriteHeight / 2

            batch.draw(
              sprite,
              coord.x - halfWidth,
              coord.y - halfHeight,
              halfWidth,
              halfHeight,
              scaledSpriteWidth,
              scaledSpriteHeight,
              1f,
              1f,
              Math.toDegrees(body.getAngle).toFloat
            )
          }

          val currentScore = globalEntity.get[global.HighScore].map(_.score).getOrElse(0)
          font.draw(batch, s"Score: $currentScore", 0, font.getCapHeight)
          batch.end()
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
          outOfBoundTeleportHandler,
          outOfBoundRemovalHandler,
          highScoreCounter,
          renderer
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
