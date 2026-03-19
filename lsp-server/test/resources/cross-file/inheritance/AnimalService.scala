package example

object AnimalService:
  def greet(animal: Animal): String =
    s"Hello, ${animal.name}! You say: ${animal.speak}"

  val dog: Dog = Dog()
  val cat: Cat = Cat()
