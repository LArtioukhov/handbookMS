package itc.handbook.microS

import akka.Done
import akka.actor._
import HandbookMS.configs.HandbookMSConfig
import itc.globals.actorMessages._

trait ActorMicroservice {
  this: Actor with ActorLogging ⇒

  import context._

  val serviceName: String

  val config: HandbookMSConfig

  def doStart(): Done

  def doStop(): Done

  def mainReceive: Receive

  def preInitiated: Receive = {
    case DoStart ⇒
      if (config.isActive) {
        log.info("Starting actor service {}", serviceName)
        doStart()
        log.info("Actor service {} started successfully.", serviceName)
        become(initiated.orElse(mainReceive).orElse(unsupportedMessage))
        sender ! Started
      } else {
        log.info("Actor service {} is not active. If necessary, activate it in the configuration file.", serviceName)
        sender ! Stopped
      }

    case DoStop ⇒ sender ! AlreadyStopped

    case msg ⇒ log.error("Actor service {} not started and can`t process message {} from {}", serviceName, msg, sender)
  }

  def initiated: Receive = {
    case DoStart ⇒ sender ! AlreadyStarted
    case DoStop ⇒
      log.info("Stopping actor service {}.", serviceName)
      doStop()
      log.info("Actor service {} stopped successfully.", serviceName)
      become(preInitiated)
      sender ! Stopped
  }

  def unsupportedMessage: Receive = {
    case msg ⇒ log.error("Unsupported message {} from {}", msg, sender())
  }

  override def receive: Receive = preInitiated

}
