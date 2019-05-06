package net.mikolak.stream_bullethell

sealed trait EntityType
case object Player extends EntityType
case object Enemy extends EntityType
case object Projectile extends EntityType
