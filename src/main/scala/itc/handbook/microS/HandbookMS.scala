package itc.handbook.microS

import akka.Done
import akka.actor._
import akka.event.LoggingAdapter
import akka.util.Timeout
import itc.globals.actorMessages.GetStatus
import itc.handbook.configs._
import itc.handbook.main.Main
import itc.handbook.microS.chains.AbstractChain

import scala.annotation.tailrec
import scala.concurrent.ExecutionContextExecutor


class HandbookMS(cfg: AppConfig)(implicit val timeOut: Timeout) extends {
  override val serviceName = HandbookMS.serviceName
  override val config: HandbookMSConfig = cfg.handbookMSConfig
} with ActorMicroservice with Actor with ActorLogging with Stash {

  case class AnyChain[In](chain: AbstractChain) {

    private[this] var _chainStartPoint: ActorRef = _
    private[this] var _chainReady: Boolean = true
    private[this] var _hasStashed: Boolean = false

    def init(): Unit = {
      _chainStartPoint = chain.buildChain
    }

    def execute(msg: In): Unit = {
      if (_chainReady) {
        _requestNum += 1
        _chainReady = false
        _chainStartPoint ! msg
      } else {
        _hasStashed = true
        stash()
        log.debug("Stashed message {}", msg)
      }
    }

    def ack(): Unit = {
      if (_hasStashed) {
        unstashAll()
        _hasStashed = false
      }
      _chainReady = true
    }

    def itsMe(ref: ActorRef): Boolean = ref == _chainStartPoint
  }

  import HandbookMS._

  implicit private[this] lazy val ec: ExecutionContextExecutor = context.dispatcher
  implicit private[this] lazy val loggingAd: LoggingAdapter    = log
  implicit private[this] lazy val _dbConnector: DBConnector    = new DBConnector(cfg.dbConfig)

  private[this] var _requestNum: Long          = 0L
  private[this] var _status: String            = "Stopped"
  private[this] var _cache: LevelsCatalogCache = _

  private[this] val _findElementForDecoderChain: AnyChain[DBSearchNeed] = AnyChain(FindElementForDecoderChain(_dbConnector))

  private[this] val _getTopListChain: AnyChain[RunGetTopList]         = AnyChain(GetTopListChain(_dbConnector))
  private[this] val _getChildListChain: AnyChain[RunGetChildList]     = AnyChain(GetChildListChain(_dbConnector))
  private[this] val _getObjectsListChain: AnyChain[RunGetObjectList]  = AnyChain(GetObjectListChain(_dbConnector))
  private[this] val _getStreetsListChain: AnyChain[RunGetStreetsList] = AnyChain(GetStreetListChain(_dbConnector))

  private[this] val _updateLCChain: AnyChain[RunUpdateElement]            = AnyChain(UpdateLCChain(_dbConnector))
  private[this] val _createElementChain: AnyChain[RunCreateLevelsCatalog] = AnyChain(CreateElementChain(_dbConnector))
  private[this] val _decodePRAddressChain: AnyChain[RunDecodePRAddress]   = AnyChain(DecodePRAddressChain(_dbConnector))

  override def doStart(): Done = {
    _status = "Started"
    _findElementFarDecoderChain.init()
    _getChildListChain.init()
    _getTopListChain.init()
    _getStreetsListChain.init()
    _getObjectsListChain.init()
    _createElementChain.init()
    _updateLCChain.init()
    _decodePRAddressChain.init()
    if (Main.trace) log.debug(context.children.toList.toString())
    Done
  }

  override def doStop(): Done = {
    _status = "Stopped"
    _requestNum = 0
    _dbConnector.close()
    _cache.flush()
    _cache = null
    Done
  }

  def makeDecodeOneProcess(thisSender: ActorRef, cDR: CasheDecoded, nextId: String): Unit = {

    @tailrec
    def buildDecodedLevelCatalog(cDR: CasheDecoded, thisId: String): CasheDecodeResult = {
      _cache.get(thisId) match {
        case Some(lC) ⇒
          lC match {
            case lC @ AdmHCatalogElement(_, Some(pId), _, _, _) ⇒ buildDecodedLevelCatalog(cDR += lC, pId)
            case lC @ AdmHCatalogElement(_, None, _, _, _)      ⇒ cDR += lC
          }
        case None ⇒ LevelCatalogNotFound(cDR.extCodes, cDR.dR, thisId)
      }
    }

    buildDecodedLevelCatalog(cDR, nextId) match {
      case CasheDecoded(eC, dR) ⇒
        if (Main.trace) log.debug("Level catalog element decoded from cache to {}", dR)
        thisSender ! AdmHResult().withDecodeResult(dR.withExtCodes(eC))
      case LevelCatalogNotFound(eC, dR, nfId) ⇒
        log.debug("Next search {} , {}", nfId, dR)
        _findElementForDecoderChain.execute(DBSearchNeed(thisSender, eC, dR, nfId))
    }
  }

  override def mainReceive: Receive = {
    case GetStatus ⇒
      sender ! HandbookMSStatus(_status, _requestNum, _cache.getStatus)

    case handbookComand(action) ⇒
      _requestNum += 1
      action match {
        case Action.Empty                         ⇒
        case Action.ActionGetTopList(request)     ⇒ _getTopListChain.execute(RunGetTopList(sender, request))
        case Action.ActionGetChildList(request)   ⇒ _getChildListChain.execute(RunGetChildList(sender, request))
        case Action.ActionGetStreetsList(request) ⇒ _getStreetsListChain.execute(RunGetStreetsList(sender, request))
        case Action.ActionGetObjectsList(request) ⇒ _getObjectsListChain.execute(RunGetObjectList(sender, request))
        case Action.ActionCreateLevelCatalog(request) ⇒
          _createElementChain.execute(
            RunCreateLevelsCatalog(sender, request.getElement.pId.flatMap(_cache.get), request))
        case msg @ Action.ActionUpdateLevelCatalog(request) ⇒
          if (request.element.isDefined) {
            _updateLCChain.execute(
              RunUpdateElement(sender,
                request.getElement.pId.flatMap(_cache.get),
                request.getElement.id.flatMap(_cache.get),
                request.getElement))
          } else {
            log.warning(s"Very strange message received $msg")
            sender ! AdmHResult().withIncorrectRequest(
              IncorrectRequest(s"Item to update is not defined in the message $msg"))

          }
        case Action.ActionDecodeLevelCatalog(request) ⇒
          makeDecodeOneProcess(sender, CasheDecoded(request.extCode, DecodeResult()), request.id)
        case Action.ActionDecodePRAddress(request) ⇒
          if (Main.trace) log.debug("Run action DecodePRAddress for address {}", request.getAddress)
          _decodePRAddressChain.execute(RunDecodePRAddress(sender,request))

      }

    case PutInCache(lC) ⇒
      _cache.put(lC)

    case ContinueNextOneLevel(thisSender, extCodes, dLC, next) ⇒
      log.debug("Continue next one level signal received for decode {} with next parameter {}", dLC, next)
      next match {
        case Some(value) ⇒ makeDecodeOneProcess(thisSender, CasheDecoded(extCodes, dLC), value)
        case None        ⇒ thisSender ! AdmHResult().withDecodeResult(dLC.withExtCodes(extCodes))
      }

    case Ack if _findElementForDecoderChain.itsMe(sender) ⇒
      if (Main.trace) log.debug("Ack from find one element for decoder chain")
      _findElementForDecoderChain.ack()

    case Ack if _getChildListChain.itsMe(sender) ⇒
      if (Main.trace) log.debug("Ack from get child list chain")
      _getChildListChain.ack()

    case Ack if _getStreetsListChain.itsMe(sender) ⇒
      if (Main.trace) log.debug("Ack from get street list chain")
      _getStreetsListChain.ack()

    case Ack if _getTopListChain.itsMe(sender) ⇒
      if (Main.trace) log.debug("Ack from get child list chain")
      _getTopListChain.ack()

    case Ack if _getObjectsListChain.itsMe(sender) ⇒
      if (Main.trace) log.debug("Ack from get object list chain")
      _getObjectsListChain.ack()

    case Ack if _createElementChain.itsMe(sender) ⇒
      if (Main.trace) log.debug("Ack from create chain")
      _createElementChain.ack()

    case Ack if _updateLCChain.itsMe(sender) ⇒
      if (Main.trace) log.debug("Ack from update chain")
      _updateLCChain.ack()

    case Ack if _decodePRAddressChain.itsMe(sender) ⇒
      if (Main.trace) log.debug("Ack from decode population registries address chain")
      _decodePRAddressChain.ack()
  }

}

object HandbookMS extends { override val serviceName = "AdmHMicroS" } with ActorMicroserviceCompanion[AppConfig] {

  override def props(cfg: AppConfig)(implicit timeOut: Timeout): Props = Props(new AdmHMicroS(cfg)(timeOut))

  case class PutInCache(lC: AdmHCatalogElement)

  sealed trait CasheDecodeResult

  case class CasheDecoded(extCodes: ExtCodes, dR: DecodeResult) extends CasheDecodeResult {
    def +=(nEl: AdmHCatalogElement) = CasheDecoded(extCodes, dR += nEl)
  }

  case class DBSearchNeed(sender: ActorRef, extCodes: ExtCodes, dR: DecodeResult, nfId: String)

  case class LevelCatalogNotFound(extCodes: ExtCodes, dLC: DecodeResult, thisId: String) extends CasheDecodeResult

  case class ContinueNextOneLevel(thisSender: ActorRef, extCodes: ExtCodes, dLC: DecodeResult, next: Option[String])

  /** Execute external request GetObjectsList from actor chain GetObjectListChain
    *
    * @param sender who must receive result
    * @param request request in external format. The content of the request is defined in file AdmHCommands.proto
    */
  case class RunGetObjectList(sender: ActorRef, request: GetObjectsList)

  case class RunGetStreetsList(sender: ActorRef, request: GetStreetsList)

  /** Execute external request [[GetChildList]] from actor chain [[itc.admH.microS.chains.GetChildListChain]]
    *
    * @param sender who must receive result
    * @param request request in external format. The content of the request is defined in file <a href="file:../protobuf/AdmHCommands.proto">/protobuf/AdmHCommands.proto</a>
    */
  case class RunGetChildList(sender: ActorRef, request: GetChildList)

  case class RunGetTopList(sender: ActorRef, request: GetTopList)

  case class RunCreateLevelsCatalog(sender: ActorRef,
                                    newParent: Option[AdmHCatalogElement],
                                    request: CreateLevelCatalog)

  case class RunUpdateElement(sender: ActorRef,
                              newParent: Option[AdmHCatalogElement],
                              origin: Option[AdmHCatalogElement],
                              forUpdate: AdmHCatalogElement)

  case class RunDecodePRAddress(sender: ActorRef, request: DecodePRAddress)
}
