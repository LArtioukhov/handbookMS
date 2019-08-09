package itc.handbook.main

import java.util.concurrent.TimeUnit

import akka.Done
import akka.actor._
import akka.http.scaladsl.Http
import akka.pattern._
import akka.stream.ActorMaterializer
import akka.util.Timeout
import itc.globals.actorMessages._
import itc.globals.exceptions.{ErrorAppNotInitialized, ReadConfigException, UndefinedData}
import itc.handbook.configs.AppConfig
import itc.handbook.main.HttpInterface
import itc.handbook.microS.{ActorMicroserviceCompanion, HandbookMS, HandbookMSStatus}

import scala.concurrent.Future.sequence
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.language.postfixOps
import scala.util._

class RootSupervisor (val config: AppConfig) extends Actor with ActorLogging {

  import akka.actor.SupervisorStrategy._
  import context._

  import scala.concurrent.duration._

  implicit val timeOut: Timeout = Timeout(10, TimeUnit.SECONDS)

  private[this] var started: Boolean = false

  def isOk: Boolean = true

  private[this] def registerActorS[CF](companion: ActorMicroserviceCompanion[CF], cfg: CF) = {
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
    registerActorS(HandbookMS, config)
  }

  override def receive: Receive = {

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

  private val log = akka.event.Logging(system, classOf[RootSupervisor])

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
        case Success(Started) ⇒
        _bindingFuture = Http().bindAndHandle(_httpInterface.route,
                                              _config.get.interfaceConfig.host,
                                              _config.get.interfaceConfig.port)
        _bindingFuture.onComplete(sB ⇒
        log.info()
        )
      }
    }

  def stop():Future[Done] =
    if (_instance == null) throw ErrorAppNotInitialized(s"$appName not initialised")
    else {
      _bindingFuture.flatMap(_.unbind()).onComplete(_ ⇒ CoordinatedShutdown(system).run(CoordinatedShutdown.JvmExitReason))
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