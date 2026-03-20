package service

trait Repository[T]:
  def getAll: List[T]
  def findBy(predicate: T => Boolean): Option[T]
