package itc.handbook.codecs.json

import spray.json._

trait HandbookJsonProtocol extends DefaultJsonProtocol{

  implicit val directoryJsonFormat = jsonFormat()
  implicit val questionJsonFormat = jsonFormat()
  implicit val solutionJsonFormat = jsonFormat()

}
