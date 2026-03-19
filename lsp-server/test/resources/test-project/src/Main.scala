package example

trait Animal:
  def name: String
  def speak: String

class Dog extends Animal:
  def name: String = "Dog"
  def speak: String = "Woof"

class Cat extends Animal:
  def name: String = "Cat"
  def speak: String = "Meow"

object Main:
  val dog: Dog = Dog()
  val cat: Cat = Cat()

  def greet(animal: Animal): String =
    s"Hello, ${animal.name}! You say: ${animal.speak}"

  def main(args: Array[String]): Unit =
    println(greet(dog))
    println(greet(cat))
