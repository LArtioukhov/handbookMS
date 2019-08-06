package itc.handbook.configs

import com.typesafe.config.Config
import itc.handbook.configs.{HttpInterfaceConfig, MongoDBConfig}

case class AppConfig (handbookMSConfig: HandbookMSConfig, interfaceConfig: HttpInterfaceConfig, dbConfig: MongoDBConfig)

object AppConfig {
  def apply(app: Config, db: Config): AppConfig = {
    val handbookMSConfig = HandbookMSConfig(app.getBoolean("isActive"))
    val interfaceConfig = HttpInterfaceConfig(app.getString("host"), app.getInt("port"))
    val dbName = app.getString("database")
    val dbConfig = MongoDBConfig(db.getString(dbName + ".connectionStr"), dbName, db.getString("handbook.collection"))
    new AppConfig(handbookMSConfig, interfaceConfig, dbConfig)
  }
}