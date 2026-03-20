package service

import hierarchy.*

class ShapeService(repo: ShapeRepository):
  def totalArea: Double = repo.getAll.map(_.area).sum

  def findCircles: List[Circle] =
    repo.getAll.collect { case c: Circle => c }

  def findLargest: Option[Shape] =
    repo.getAll.maxByOption(_.area)

  def describeAll: List[String] =
    repo.getAll.map(_.describe)
