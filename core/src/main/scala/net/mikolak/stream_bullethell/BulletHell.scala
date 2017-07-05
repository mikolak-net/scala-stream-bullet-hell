package net.mikolak.stream_bullethell

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream.ActorMaterializer
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.badlogic.gdx.graphics.g2d.{BitmapFont, SpriteBatch}
import com.badlogic.gdx.graphics.{GL20, OrthographicCamera}
import com.badlogic.gdx.{Game, Gdx, ScreenAdapter}

class BulletHell extends Game {

  override def create(): Unit = {
    setScreen(new MainScreen)
  }

}

class MainScreen extends ScreenAdapter {

  lazy val camera = new OrthographicCamera()
  val batch: SpriteBatch = new SpriteBatch()

  var tick = 1L

  implicit val actorSystem = ActorSystem("game")
  implicit val materializer = ActorMaterializer()

  val tickSource: Source[Nothing, ActorRef] = Source.actorPublisher(Props[GameTickPublisherActor])
  var tickActor: Option[ActorRef] = None

  lazy val font = {
    val f = new BitmapFont()
    f.getData.setScale(2f)
    f
  }

  override def show() = {
    camera.setToOrtho(false, 800, 480)

    val tickSettingFlow = Flow[TickDelta].map { td =>
      tick += 1
      td
    }
    val graph = tickSource.via(tickSettingFlow).to(Sink.ignore)

    tickActor = Some(graph.run())
  }

  override def render(delta: TickDelta) = {
    tickActor.foreach(_ ! delta)

    //print tick
    Gdx.gl.glClearColor(0, 0, 0.5f, 1)
    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
    camera.update()
    batch.setProjectionMatrix(camera.combined)
    batch.begin()
    font.draw(batch, s"Tick: $tick", 0, font.getCapHeight)
    batch.end()
  }

  override def dispose(): Unit = {
    actorSystem.terminate()
  }
}

class GameTickPublisherActor() extends Actor with ActorPublisher[TickDelta] {

  private var updateStack = List.empty[TickDelta]

  override def receive = {
    case delta: TickDelta =>
      updateStack :+= delta

      if (isActive && totalDemand > 0) {
        val (toTransmit, toPreserve) =
          updateStack.splitAt((updateStack.length - totalDemand.toInt).max(0))
        toTransmit.foreach(onNext)
        updateStack = toPreserve
      }
  }
}
