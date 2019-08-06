package itc.handbook.main

object Main {
  val trace = true
  def main(args: Array[String]): Unit = {
    RootSupervisor.init()
    RootSupervisor.start()
  }
}
