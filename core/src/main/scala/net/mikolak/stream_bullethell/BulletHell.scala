package net.mikolak.stream_bullethell

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

  lazy val font = {
    val f = new BitmapFont()
    f.getData.setScale(2f)
    f
  }

  override def render(delta: Float) = {
    tick += 1

    //print tick
    Gdx.gl.glClearColor(0, 0, 0.5f, 1)
    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
    camera.update()
    batch.setProjectionMatrix(camera.combined)
    batch.begin()
    font.draw(batch, s"Tick: $tick", 0, font.getCapHeight)
    batch.end()
  }

  override def show() = {
    camera.setToOrtho(false, 800, 480)
  }
}
