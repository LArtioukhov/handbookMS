package itc.handbook.microS

import akka.actor.Props
import akka.util.Timeout

trait ActorMicroserviceCompanion[CF] {
  val serviceName: String
  def props(cfg: CF)(implicit timeOut: Timeout): Props
}
