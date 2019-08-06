package itc.handbook

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer

import scala.concurrent.ExecutionContextExecutor
import scala.io.StdIn

object structHandbookMS extends App {
  override def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("HBMS")
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    implicit val executionContext: ExecutionContextExecutor = system.dispatcher

    val route =
      pathPrefix("hbms" / "v01") {
        path("status") {
          get {
              complete("/hmbs/v01/status")
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

    val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)

    println(s"Server status at http://localhost:8080/hbms/v01/status" +
      s"\nList of question at http://localhost:8080/hbms/v01/question" +
      s"\nList of solutions at http://localhost:8080/hbms/v01/question/qId" +
      s"\nTranscript of this solution at http://localhost:8080/hbms/v01/question/qId/sId" +
      s"\nPress RETURN to stop...")
    StdIn.readLine()
    bindingFuture
      .flatMap(_.unbind())
      .onComplete(_ => system.terminate())
  }
}
