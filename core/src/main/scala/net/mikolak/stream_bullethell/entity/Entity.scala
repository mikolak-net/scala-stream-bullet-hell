package net.mikolak.stream_bullethell.entity

import scala.collection.concurrent.TrieMap
import scala.reflect.ClassTag

trait Entity {

  def get[T <: Component: ClassTag]: Option[T]
  def update[T <: Component: ClassTag](c: T): Entity
  def drop[T <: Component: ClassTag]: Entity
  def has[T <: Component: ClassTag]: Boolean

}

object Entity {
  def apply(cs: Component*): Entity = new EntityImpl
}

private class EntityImpl extends Entity {

  private val cs: collection.concurrent.Map[Class[_], Component] = TrieMap.empty

  override def get[T <: Component](implicit cTag: ClassTag[T]): Option[T] =
    cs.get(cTag.runtimeClass).map(_.asInstanceOf[T])

  override def update[T <: Component](c: T)(implicit cTag: ClassTag[T]): Entity = {
    cs.update(cTag.runtimeClass, c)
    this
  }

  override def drop[T <: Component](implicit cTag: ClassTag[T]): Entity = {
    cs.remove(cTag.runtimeClass)
    this
  }

  override def has[T <: Component](implicit cTag: ClassTag[T]): Boolean =
    cs.contains(cTag.runtimeClass)
}

trait Component {}
