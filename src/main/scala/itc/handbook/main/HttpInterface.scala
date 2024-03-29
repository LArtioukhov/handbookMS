package itc.handbook.main

import akka.actor.ActorRef
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpHeader, HttpResponse, StatusCodes, Uri}
import akka.http.scaladsl.server.ContentNegotiator.Alternative.ContentType
import akka.http.scaladsl.server.Directives.{complete, get, path, pathEnd, pathPrefix}
import akka.http.scaladsl.server.{Directives, Route}
import akka.http.scaladsl.server.ExceptionHandler.PF
import akka.util.Timeout
import itc.globals.actorMessages.GetStatus
import itc.handbook.codecs.json._
import akka.pattern._
import wapnee.dataModel.personFile.incl.Hierarchy

import scala.concurrent.ExecutionContextExecutor

class HttpInterface (handbookMS: ActorRef) (implicit val timeout: Timeout, implicit val ec: ExecutionContextExecutor)
  extends Directives
  with SprayJsonSupport
  with HandbookJsonProtocol{

  implicit class ObjectIdChecker (s: String) {
    def isValidObjectId: Boolean =
      s.length == 24 &&
        s.forall { ch ⇒
          (ch >= '0' && ch <= '9') ||
          (ch >= 'a' && ch <= 'f') ||
          (ch >= 'A' && ch <= 'F')
    }
  }



  private val defaultResponse: PF[] = ???

  private  def getResponse(f: PF[]): PF[] = ???

  private def childrenListWanted(): HttpResponse = ???

  private def objectListWanted(): HttpResponse = ???

  private def decodeResultWanted(): HttpResponse = ???

  private def streetsListWanted(): HttpResponse = ???

  private def insertResultWanted(): HttpResponse = ???

  private def updateResultWanted(): HttpResponse = ???

  def routeRead(): Route = ???

  def routeWritePR: Route = ???

  //noinspection TypeAnnotation
  val route =
    pathPrefix("hbms" / "v01") {
      path("status") {
        get {
          complete{
            (handbookMS ? GetStatus).mapTo[HandbookStatus]
          }
        }
      } ~
        pathPrefix("question") {
          pathEnd {
            get {
              complete("/hmbs/v01/question")
            }
          } ~
            pathPrefix("qId") {
              pathEnd {
                get {
                  complete("/hmbs/v01/question/qId")
                }
              } ~
                path("sId") {
                  get {
                    complete("/hmbs/v01/question/qId/sId")
                  }
                }
            }
        }
    }
}

object HttpInterface {
  def created(s: String): HttpResponse = {
    val headers: scala.collection.immutable.Seq[HttpHeader] = List(Location(Uri("")))
    HttpResponse(StatusCodes.Created, headers = headers, entity = HttpEntity(ContentTypes.`application/json`, s))
  }
  def success(s: String) = HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`application/json`, s))
  val notFound = HttpResponse(StatusCodes.NotFound)
  def badRequest(s: String = "") =
    HttpResponse(StatusCodes.BadRequest, entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, s))
  def internalError(s: String) =
    HttpResponse(StatusCodes.InternalServerError, entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, s))
}
