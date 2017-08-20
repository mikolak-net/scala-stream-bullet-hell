package net.mikolak.stream_bullethell

import com.badlogic.gdx.backends.lwjgl.{LwjglApplication, LwjglApplicationConfiguration}

object Main extends App {
  val cfg = new LwjglApplicationConfiguration
  cfg.title = "stream-bullethell-demo"
  cfg.height = 640
  cfg.width = 1024
  cfg.forceExit = false
  new LwjglApplication(new BulletHell, cfg)
}
