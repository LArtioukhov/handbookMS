package itc.handbook.main

import java.util.concurrent.TimeUnit

import akka.Done
import akka.actor._
import akka.http.scaladsl.Http
import akka.pattern._
import akka.stream.ActorMaterializer
import akka.util.Timeout
import HandbookMS.configs.AppConfig
import itc.handbook.main.HttpInterface

import scala.concurrent.Future.sequence
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.language.postfixOps
import scala.util._

class RootSupervisor extends Actor with ActorLogging {

  import akka.actor.SupervisorStrategy._
  import context._

  import scala.concurrent.duration._

  implicit val timeOut: Timeout = Timeout(10, TimeUnit.SECONDS)

  private[this] var started: Boolean = false

  def isOk: Boolean = true

  private[this] def registerAcctorS[CF](companion: ActorMicroserviceCompanion[CF], cfg: CF) = {
    context.actorOf(companion.props(cfg), companion.serviceName)
  }

  override val supervisorStrategy: OneForOneStrategy =
    OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
      case _: ReadConfigException      ⇒ Stop
      case _: UndefinedData            ⇒ Resume
      case _: IllegalArgumentException ⇒ Resume
      case cause: ActorNotFound ⇒
        log.warning(s"${cause.getMessage}. Restart")
        Restart
      case _: Exception ⇒ Escalate
    }

  override def preStart(): Unit = {
    super.preStart()
    // Creation and registration actor services.
    registerActorS(AdmHMicroS, config)
  }

  override def receive: Receive = {

    case GetStatus ⇒
      val status = if (isOk) "Ok" else "Failure"
      child(AdmHMicroS.serviceName)
        .map(_ ? GetStatus)
        .map(_.map {
          case msg: AdmHMicroSStatus ⇒ AppStatus(status, config, msg)
        } pipeTo sender)

    case msg: AdmHCommand ⇒ child(AdmHMicroS.serviceName).foreach(_ forward msg)

    case DoStart ⇒
      started = true
      val thisSender = sender
      log.info(s"RootSupervisor {} starting process", RootSupervisor.appName)
      sequence(children.map { child ⇒
        (child ? DoStart).map { result ⇒
          (child.path.name, result)
        }
      }).onComplete { r ⇒
        log.info("RootSupervisor {} starting progress: {}", RootSupervisor.appName, r.map(_.mkString(", ")))
        thisSender ! Started
      }

    case DoStop ⇒
      started = false
      val thisSender = sender
      sequence(children.map(ch ⇒ (ch ? DoStop).map(ch.path + " → " + _)))
        .onComplete { r =>
          log.info("RootSupervisor {} stopping progress: {}", RootSupervisor.appName, r.map(_.mkString(", ")))
          thisSender ! Stopped
        }

    case msg ⇒ log.warning("Unsupported message {} from {}", msg, sender)

  }
}

object RootSupervisor {

  private[this] var _instance: ActorRef                        = _
  private[this] var _bindingFuture: Future[Http.ServerBinding] = _
  private[this] var _httpInterface: HttpInterface              = _
  private[this] var _config: Try[AppConfig]                    = _

  private val appName = "handbook-MS"

  implicit val system: ActorSystem             = ActorSystem(appName)
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor    = system.dispatcher
  implicit val timeOut: Timeout = Timeout(
    system.settings.config
      .getDuration("akka.http.server.request-timeout", TimeUnit.MILLISECONDS),
    TimeUnit.MILLISECONDS
  )

  def init(): Unit = {
    _config = Try(
      AppConfig(system.settings.config.getConfig(RootSupervisor.appName), system.settings.config.getConfig("db")))
    _instance = system.actorOf(supervisorProps, "RootSupervisor")
    CoordinatedShutdown(system)
      .addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "Shutdown microServices") { () ⇒
        stop()
      }
  }

  def start(): Unit =
    if (_instance == null || _config.isFailure) {
      CoordinatedShutdown(system).run(CoordinatedShutdown.JvmExitReason)
    } else {
      _httpInterface = new HttpInterface(_instance)
      (_instance ? DoStart).onComplete {

      }
    }

  def stop():Future[Done] =
    if (_instance == null) throw ErrorAppNotInitialized(s"$appName not initialised")
    else {
      _bindingFuture.flatMap(_.unbind()).onComplete(_ ⇒ CS(system).run(CS.JvmExitReason))
      _instance ? DoStop map { _ ⇒
        Done
      }
    }

  def destroy(): Unit =
    if (_instance == null) throw ErrorAppNotInitialized(s"$appName not initialised")
    else {
      _bindingFuture.flatMap(_.unbind()).onComplete(_ ⇒ CoordinatedShutdown(system).run(CoordinatedShutdown.JvmExitReason))
    }

  def supervisorProps: Props = Props(new RootSupervisor(_config.get))
}