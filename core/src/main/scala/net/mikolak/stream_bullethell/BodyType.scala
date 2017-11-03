package net.mikolak.stream_bullethell

sealed trait BodyType
case object Player extends BodyType
case object Enemy extends BodyType