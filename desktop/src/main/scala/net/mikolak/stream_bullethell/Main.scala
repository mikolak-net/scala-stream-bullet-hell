package net.mikolak.stream_bullethell

import com.badlogic.gdx.backends.lwjgl.{LwjglApplication, LwjglApplicationConfiguration}

object Main extends App {
  val cfg = new LwjglApplicationConfiguration
  cfg.title = "stream-bullethell-demo"
  cfg.height = 800
  cfg.width = 1280
  cfg.forceExit = false
  new LwjglApplication(new BulletHell, cfg)
}
