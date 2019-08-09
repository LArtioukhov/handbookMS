package itc.handbook.codecs.json

import spray.json._
import DefaultJsonProtocol._
import wapnee.dataModel.administrativeHierarchy.incl.LexicalValues
import wapnee.dataModel.directory._

trait HandbookJsonProtocol extends DefaultJsonProtocol{

  implicit val directoryJsonFormat: RootJsonFormat[Nothing] = jsonFormat3(Directory)
  implicit val questionJsonFormat: RootJsonFormat[Nothing] = jsonFormat3(Question)
  implicit val solutionJsonFormat: RootJsonFormat[Nothing] = jsonFormat3(Solution)

}
