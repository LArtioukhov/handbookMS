package itc.handbook.codecs.json

import spray.json._
import DefaultJsonProtocol._
import itc.handbook.configs._
import itc.handbook.main.HandbookStatus
import wapnee.dataModel.administrativeHierarchy.incl.LexicalValues
import wapnee.dataModel.directory._

//noinspection TypeAnnotation
trait HandbookJsonProtocol extends DefaultJsonProtocol{

  implicit val solutionJsonFormat = jsonFormat5(Solution)
  implicit val questionJsonFormat = jsonFormat6(Question)
  implicit val directoryJsonFormat  = jsonFormat2(Directory)

  implicit val dbConfigJsonFormat = jsonFormat3(MongoDBConfig)
  implicit val interfaceConfigJsonFormat =  jsonFormat2(HttpInterfaceConfig)
  implicit val handbookMSConfigJsonFormat = jsonFormat1(HandbookMSConfig)
  implicit val appConfigJsonFormat = jsonFormat3(AppConfig.apply)
  implicit val handbookStatusJsonFormat = jsonFormat2(HandbookStatus)

}
