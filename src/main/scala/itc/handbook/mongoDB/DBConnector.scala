/*
 * Wapnee APP
 *   AdmHMicroS
 *       Copyright (c) 2019. ITC
 *       http://mlsp.gov.by/
 *              Developed by Leonid Artioukhov on 01.04.19 10:07
 */

package itc.handbook.mongoDB

import com.sun.xml.internal.txw2.Document
import itc.handbook.configs.MongoDBConfig
import org.bson.types.ObjectId
import org.mongodb.scala._
import org.mongodb.scala.bson.BsonObjectId
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model._
import wapnee.dataModel.directory._

import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}

class DBConnector(conf: MongoDBConfig)(implicit val ec: ExecutionContext) {

  import DBConnector._

  private[this] val db = MongoClient(conf.connectionString)

  lazy val directory: MongoCollection[Directory] =
    db.getDatabase(conf.dbName)
    .withCodecRegistry()
    .getCollection()

  lazy val question: MongoCollection[Question] = ???

  lazy val solution: MongoCollection[Solution] = ???

  lazy val levelsCatalogCollection: MongoCollection[handbookCatalogElement] =
    db.getDatabase(conf.dbName)
      .withCodecRegistry(itc.handbook.codecs.mongo.admHCatalogCodecRegistry)
      .getCollection[AdmHCatalogElement](conf.admHCollectionName)

  lazy val streetsAggregator: MongoCollection[ListElement] =
    db.getDatabase(conf.dbName)
      .withCodecRegistry(itc.admH.codecs.mongo.admHCatalogCodecRegistry)
      .getCollection[ListElement](conf.admHCollectionName)

  lazy val streetIdAggregator: MongoCollection[Document] = db
    .getDatabase(conf.dbName)
    .getCollection(conf.admHCollectionName)

  private def genProjectionDoc(extCodes: ExtCodes, needHl: Boolean = false) =
    if (needHl) extCodes match {
      case ExtCodes.NONE            ⇒ Document("l"   → 1, "hL" → 1)
      case ExtCodes.PR              ⇒ Document("l"   → 1, "hL" → 1, "eC" → 1)
      case ExtCodes.Unrecognized(_) ⇒ Document("_id" → 1)
    } else
      extCodes match {
        case ExtCodes.NONE            ⇒ Projections.include("l")
        case ExtCodes.PR              ⇒ Document("l" → 1, "eC" → 1)
        case ExtCodes.Unrecognized(_) ⇒ Projections.fields(Projections.include("rPr"), Projections.excludeId())
      }

  def close(): Unit = db.close()

  def getTopList(extCodes: ExtCodes): Future[Option[ChildrenList]] = {

    levelsCatalogCollection
      .find(Filters.and(Filters.equal("hL", 1)))
      .projection(genProjectionDoc(extCodes))
      .collect()
      .map(ChildrenList(hasStreets = false, _))
      .headOption()
  }

  def findById(id: String): Future[DBSearchResult] =
    levelsCatalogCollection
      .find(Filters.equal("_id", BsonObjectId(id)))
      .headOption()
      .map {
        case Some(value) ⇒ Found(value)
        case None        ⇒ NotFound(Some(id))
      }

  def getChildList(extCodes: ExtCodes, id: String): Future[Option[ChildrenList]] = {
    val lst: SingleObservable[Seq[AdmHCatalogElement]] =
      levelsCatalogCollection
        .find(Filters.and(Filters.equal("pId", BsonObjectId(id)), Filters.exists("eC.shC", exists = false)))
        .projection(genProjectionDoc(extCodes))
        .collect()
    val hasStreet: SingleObservable[Boolean] =
      levelsCatalogCollection
        .find(Filters.and(Filters.equal("pId", BsonObjectId(id)), Filters.exists("eC.shC", exists = true)))
        .limit(1)
        .collect()
        .map(_.nonEmpty)
    for {
      l  ← lst
      sC ← hasStreet
    } yield ChildrenList(sC, l)
  }.headOption()

  def getStreetList(id: String): Future[StreetsList] = {
    streetsAggregator
      .aggregate(List(
        Aggregates.filter(Filters.and(Filters.equal("pId", BsonObjectId(id)), Filters.exists("eC.shC", exists = true))),
        Aggregates.unwind("$l"),
        Aggregates.filter(Filters.exists("l.RU")),
        Aggregates.project(Projections.fields(
          Projections.computed("stC", "$eC.shC.stC"),
          Projections.computed(
            "stN.RU",
            Document("""{n:{$rtrim:{"input":"$l.RU.n","chars":{$concat:[" ","$eC.shC.hN"]}}},s:"$l.RU.s",p:"$l.RU.p"}"""))
        )),
        Aggregates.group(Document("""{stC:"$stC"}"""), Accumulators.addToSet("l", "$stN"))
      ))
      .collect()
      .toFuture()
      .map(StreetsList(_))
  }

  def getObjectList(extCodes: ExtCodes, parentId: String, streetCode: Long): Future[ObjectsList] = {
    levelsCatalogCollection
      .find(Filters.and(Filters.equal("pId", BsonObjectId(parentId)), Filters.equal("eC.shC.stC", streetCode)))
      .projection(genProjectionDoc(extCodes))
      .collect()
      .map(ObjectsList(_))
      .head()
  }

  def insertNew(lC: AdmHCatalogElement): Future[Created] = {
    val newId: ObjectId = new ObjectId()
    levelsCatalogCollection
      .insertOne(lC.withId(newId.toHexString))
      .map(_ ⇒ Created(newId.toHexString))
      .head()
  }

  def updateOne(id: String, origin: AdmHCatalogElement, updateOne: AdmHCatalogElement): Future[UpdateResult] = {
    val updatesList = ListBuffer.empty[Bson]
    if (origin.pId != updateOne.pId) updatesList += Updates.set("pId", BsonObjectId(updateOne.pId.get))
    if (origin.hL != updateOne.hL)
      updatesList += Updates.inc("hL", updateOne.hL.get - origin.hL.get)
    if (origin.l != updateOne.l) updatesList += Updates.set("l", updateOne.l.toList.map { p =>
      Document(p._1 → Document("n" → p._2.n, "p" → p._2.p, "s" → p._2.s))
    })
    if (origin.pReC != updateOne.pReC) updatesList += Updates.set("eC", updateOne.pReC)
    levelsCatalogCollection.updateOne(Filters.equal("_id", BsonObjectId(id)),
                                      if (updatesList.size == 1) updatesList.head else Updates.combine(updatesList: _*))
  }.map((uR: result.UpdateResult) ⇒ UpdateResult(id, uR.getMatchedCount, uR.getModifiedCount))
    .head()

  //
  def updateAllChildren(id: String, inc: Int): Future[UpdateResult] =
    levelsCatalogCollection
      .aggregate {
        Seq(
          Aggregates.filter(Filters.equal("_id", BsonObjectId(id))),
          Aggregates.graphLookup("admHierarchy", "$_id", "_id", "pId", "child"),
          Aggregates.project(Projections.fields(Projections.include("child"), Projections.excludeId())),
          Aggregates.unwind("$child"),
          Aggregates.replaceRoot("$child")
        )
      }
      .map { _.id.get }
      .toFuture()
      .flatMap(updateChildren(_, inc))
      .map(uR ⇒ UpdateResult("", uR.getMatchedCount, uR.getModifiedCount))

  private def updateChildren(idList: Seq[String], inc: Int): Future[result.UpdateResult] = {
    val oIdList = idList.map(BsonObjectId(_))
    val filter  = Filters.in("_id", oIdList: _*)
    val update  = Updates.inc("hL", inc)
    levelsCatalogCollection.updateMany(filter, update).toFuture()
  }

  def findByPRExternalCode(prExtCodes: PReC): Future[DBSearchResult] =
    levelsCatalogCollection
      .find(Filters.equal("eC", prExtCodes))
      .limit(1)
      .headOption()
      .map {
        case Some(value) ⇒ Found(value)
        case None        ⇒ NotFound(None)
      }

  def findParentsIdForStreet(streetCode: Long): Future[Seq[String]] =
    streetIdAggregator.aggregate {
      Seq(
        Aggregates.filter(Filters.equal("eC.shC.stC", streetCode)),
        Aggregates.project(Projections.fields(Projections.include("pId"))),
        Aggregates.group("$pId")
      )
    } map { _.getObjectId("_id").toHexString } toFuture
}

object DBConnector {

  sealed trait DBSearchResult

  case class Found(lC: AdmHCatalogElement) extends DBSearchResult
  case class NotFound(id: Option[String])  extends DBSearchResult
  case class Created(newId: String)
  case class IdSeq(list: Seq[String])
}
