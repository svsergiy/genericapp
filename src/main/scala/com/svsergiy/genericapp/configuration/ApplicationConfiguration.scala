package com.svsergiy.genericapp.configuration

//Cats imports:
import cats.data.Validated._
import cats.data.ValidatedNec
import cats.implicits._

//Other imports:
import com.typesafe.config.{Config, ConfigFactory}
import scala.collection.mutable.ListBuffer
import scala.util.Try

//Genesys imports:
import com.genesyslab.platform.applicationblocks.com.objects.{CfgApplication, CfgHost}

//Application imports:
import com.svsergiy.genericapp.ManagementService.UpdatedParameters
import com.svsergiy.genericapp.validation._

object ApplicationConfiguration {
  type ValidationResult[A] = ValidatedNec[ValidationError, A]

  private val configUpdateListeners = ListBuffer.empty[ConfigurationUpdateListener]

  private var genesysConnectionInfoOpt: Option[GenesysConnectionParameters] = None
  private var lcaConnectionInfoOpt: Option[LcaConnectionParameters] = None
  private var httpServerInfoOpt: Option[HttpServerParameters] = None
  private var dbConnectionInfoOpt: Option[DatabaseParameters] = None

  def addConfigurationUpdateListener(cfgUpdateListener: ConfigurationUpdateListener): Unit = {
    configUpdateListeners += cfgUpdateListener
  }

  def removeConfigurationUpdateListener(cfgUpdateListener: ConfigurationUpdateListener): Unit = {
    configUpdateListeners -= cfgUpdateListener
  }

  def configurationUpdated(updatedParams: UpdatedParameters): Unit = {
    configUpdateListeners.foreach(_.configurationUpdated(updatedParams))
  }

  // Retrieve genesys connection information from application.conf configuration file
  def parseGenesysConnectionParameters(implicit config: Config):  ValidationResult[GenesysConnectionParameters] = {
    Try(config.getConfig("genesys"))
      .map(Right(_))
      .recover(_ => Left(GenesysConfigIsNotDefined))
      .get.toValidatedNec
      .andThen { genesysConfig =>
        Try(genesysConfig.getConfig("confserver"))
          .map(Right(_))
          .recover(_ => Left(ConfServConfigIsNotDefined))
          .get.toValidatedNec
          .andThen { confservConfig =>
            val prmEndpoint = Try(confservConfig.getObjectList("endpoints").get(0).toConfig).getOrElse(ConfigFactory.empty())
            val bkpEndpoint = Try(confservConfig.getObjectList("endpoints").get(1).toConfig).getOrElse(ConfigFactory.empty())
            (
              Try(confservConfig.getString("client-name").trim).map(Right(_))
                .map {
                  case Right("") => Left(ClientNameIsNotSpecified)
                  case Right(value) => Right(value)
                }
                .recover(_ => Left(ClientNameIsNotSpecified)).get.toValidatedNec,
              Try(confservConfig.getString("cfg-app-type").trim).map(Right(_))
                .map {
                  case Right("") => Left(CfgAppTypeIsNotSpecified)
                  case Right(value) => Right(value)
                }
                .recover(_ => Left(CfgAppTypeIsNotSpecified)).get.toValidatedNec,
              Try(prmEndpoint.getString("host").trim).map(Right(_))
                .map {
                  case Right("") => Left(PrimaryConfServHostIsNotSpecified)
                  case Right(value) => Right(value)
                }
                .recover(_ => Left(PrimaryConfServHostIsNotSpecified)).get.toValidatedNec,
              Try(prmEndpoint.getInt("port")).map(Right(_))
                .map {
                  case Right(value) if value <= 0 => Left(PrimaryConfServPortIsNotSpecified)
                  case Right(value) => Right(value)
                }
                .recover(_ => Left(PrimaryConfServPortIsNotSpecified)).get.toValidatedNec
            ).mapN {(clientName, cfgAppType, prmConfigSrvHost, prmConfigSrvPort) =>
              GenesysConnectionParameters(
                clientName = Try(genesysConfig.getString("client-name").trim).getOrElse("GenGenericApp"),
                stringsEncoding = Try(genesysConfig.getString("strings-encoding").trim).getOrElse("utf-8"),
                configSrv = ConfigSrvConnection(
                  clientName = clientName,
                  cfgAppType = cfgAppType,
                  prmConfigSrv = ConfigSrvEndpoint(
                    name = Try(prmEndpoint.getString("name").trim).getOrElse("conf-main-endpoint"),
                    host = prmConfigSrvHost,
                    port = prmConfigSrvPort
                  ),
                  bkpConfigSrv = ConfigSrvEndpoint(
                    name = Try(bkpEndpoint.getString("name").trim).toOption.filter(_.nonEmpty).getOrElse("conf-standby-endpoint"),
                    host = Try(bkpEndpoint.getString("host").trim).getOrElse(""),
                    port = Try(bkpEndpoint.getInt("port")).getOrElse(0)
                  )
                ),
                addp = Addp(
                  enabled = Try(genesysConfig.getBoolean("addp.enabled")).getOrElse(true),
                  clientTimeout = Try(genesysConfig.getInt("addp.client-timeout")).getOrElse(15),
                  serverTimeout = Try(genesysConfig.getInt("addp.server-timeout")).getOrElse(25),
                  traceMode = Try(genesysConfig.getString("addp.trace-mode").trim).toOption.filter(_.nonEmpty).getOrElse("Both")
                ),
                warmStandby = WarmStandbyParameters(
                  timeout = Try(genesysConfig.getInt("warmstandby.timeout")).getOrElse(5),
                  backupDelay = Try(genesysConfig.getInt("warmstandby.backup-delay")).getOrElse(1),
                  retryDelay = Try(genesysConfig.getInt("warmstandby.retry-delay")).getOrElse(1),
                  reconnectionRandomDelayRange = Try(genesysConfig.getDouble("warmstandby.reconnection-random-delay-range")).getOrElse(0.5)
                ),
                connectionTimeout = Try(genesysConfig.getInt("connectionTimeout")).getOrElse(120)
              )
            }
          }
      }
  }

  def getGenesysConnectionInfo: Option[GenesysConnectionParameters] = genesysConnectionInfoOpt

  def setGenesysConnectionInfo(genConnInfo: GenesysConnectionParameters): Unit = genesysConnectionInfoOpt = Option(genConnInfo)

  // Read LCA connection information from configuration database
  def readLcaConnectionParameters(cfgApp: CfgApplication, hostInfo: CfgHost): ValidationResult[LcaConnectionParameters] = {
    val lcaPortV = Try(hostInfo.getLCAPort.toInt)
      .map(Right(_))
      .map {
        case Right(0) => Left(LcaPortIsZero)
        case Right(value) => Right(value)
      }
      .recover(_ => Left(LcaPortIsNotDefined))
      .get.toValidatedNec
    val appDbIdV = Try(cfgApp.getDBID.toInt)
      .map(Right(_))
      .recover(_ => Left(AppDbIdIsNotDefined))
      .get.toValidatedNec
    val appNameV = Try(cfgApp.getName)
      .map(Right(_))
      .recover(_ => Left(AppNameIsNotDefined))
      .get.toValidatedNec
    val appTypeV = Try(cfgApp.getType.ordinal)
      .map(Right(_))
      .recover(_ => Left(AppTypeIsNotDefined))
      .get.toValidatedNec
    (lcaPortV, appDbIdV, appNameV, appTypeV).mapN { (lcaPort, appDbId, appName, appType) =>
      LcaConnectionParameters(
        lcaPort,
        appType,
        appName,
        appDbId,
        Try(cfgApp.getOptions.getList("lca").getString("reconnectTimeout").trim.toInt).getOrElse(10)
      )
    }
  }

  def getLcaConnectionInfo: Option[LcaConnectionParameters] = lcaConnectionInfoOpt

  def setLcaConnectionInfo(appStartInfo: LcaConnectionParameters): Unit = lcaConnectionInfoOpt = Option(appStartInfo)

  // Retrieve genesys connection information from application.conf configuration file
  def parseHttpServerParameters(implicit config: Config): ValidationResult[HttpServerParameters] = {
    Try(config.getConfig("app")).map(Right(_))
      .recover(_ => Left(AppConfigIsNotDefined))
      .get.toValidatedNec
      .andThen {appConfig =>
        Try(appConfig.getInt("port")).map(Right(_))
          .map {
            case Right(value) if value <= 0 => Left(AppPortIsNotSpecified)
            case Right(value) => Right(value)
          }
          .recover(_ => Left(AppPortIsNotSpecified))
          .get.toValidatedNec
          .map {appPort =>
            HttpServerParameters(
              host = Try(appConfig.getString("host").trim).getOrElse("localhost"),
              port = appPort
            )
        }
      }
  }

  // Read Http server information from configuration database
  def readHttpServerParameters(cfgApp: CfgApplication, hostInfo: CfgHost): ValidationResult[HttpServerParameters] = {
    val appPortV = Try(cfgApp.getServerInfo.getPort.toInt)
      .map(Right(_))
      .map {
        case Right(0) => Left(AppPortIsNotSpecifiedInConfigDB)
        case Right(value) => Right(value)
      }
      .recover(_ => Left(AppPortIsNotSpecifiedInConfigDB))
      .get.toValidatedNec
    appPortV.map {appPort =>
      HttpServerParameters(
        host = Try(cfgApp.getOptions.getList("app").getString("host").trim).toOption.filter(_.nonEmpty).getOrElse {
          Try(hostInfo.getIPaddress.trim).toOption.filter(_.nonEmpty).getOrElse("localhost")
        },
        port = appPort
      )
    }
  }

  def getHttpServerInfo: Option[HttpServerParameters] = httpServerInfoOpt

  def setHttpServerInfo(httpServerInfo: HttpServerParameters): Unit = httpServerInfoOpt = Option(httpServerInfo)

  def parseDatabaseParameters(implicit config: Config): ValidationResult[DatabaseParameters] = {
    Try(config.getConfig("db"))
      .map(Right(_))
      .recover(_ => Left(DbConfigIsNotDefined))
      .get.toValidatedNec
      .andThen { dbConfig =>
        Try(dbConfig.getConfig("properties"))
          .map(Right(_))
          .recover(_ => Left(DbPropertiesConfigIsNotDefined))
          .get.toValidatedNec
          .andThen { dbPropConfig =>
            (
              Try(dbPropConfig.getString("databaseName").trim).map(Right(_))
                .map {
                  case Right("") => Left(DatabaseNameIsNotSpecified)
                  case Right(value) => Right(value)
                }
                .recover(_ => Left(DatabaseNameIsNotSpecified)).get.toValidatedNec,
              Try(dbPropConfig.getString("user").trim).map(Right(_))
                .map {
                  case Right("") => Left(UserIsNotSpecified)
                  case Right(value) => Right(value)
                }
                .recover(_ => Left(UserIsNotSpecified)).get.toValidatedNec,
              Try(dbPropConfig.getString("password").trim).map(Right(_))
                .map {
                  case Right("") => Left(PasswordIsNotSpecified)
                  case Right(value) => Right(value)
                }
                .recover(_ => Left(PasswordIsNotSpecified)).get.toValidatedNec
            ).mapN { (databaseName, user, password) =>
              DatabaseParameters(
                connectionPool = Try(dbConfig.getString("connectionPool").trim).getOrElse("HikariCP"),
                dataSourceClass = Try(dbConfig.getString("dataSourceClass").trim).getOrElse("com.microsoft.sqlserver.jdbc.SQLServerDataSource"),
                properties = DatabaseProperties(
                  serverName = Try(dbPropConfig.getString("serverName").trim).getOrElse("localhost"),
                  portNumber = Try(dbPropConfig.getString("portNumber").trim.toInt).getOrElse(1433),
                  databaseName = databaseName,
                  user = user,
                  password = password
                ),
                numThreads = Try(dbConfig.getString("numThreads").trim.toInt).getOrElse(5)
              )
            }
          }
      }
  }

  // Read Http server information from configuration database
  def readDatabaseParameters(cfgApp: CfgApplication): ValidationResult[DatabaseParameters] = {
    val databaseNameV = Try(cfgApp.getOptions.getList("db").getString("databaseName").trim).map(Right(_))
      .map {
        case Right("") => Left(DatabaseNameIsNotSpecifiedInConfigDB)
        case Right(value) => Right(value)
      }
      .recover(_ => Left(DatabaseNameIsNotSpecifiedInConfigDB))
      .get.toValidatedNec
    val userV = Try(cfgApp.getOptions.getList("db").getString("user").trim).map(Right(_))
      .map {
        case Right("") => Left(UserIsNotSpecifiedInConfigDB)
        case Right(value) => Right(value)
      }
      .recover(_ => Left(UserIsNotSpecifiedInConfigDB))
      .get.toValidatedNec
    val passwordV = Try(cfgApp.getOptions.getList("db").getString("password").trim).map(Right(_))
      .map {
        case Right("") => Left(PasswordIsNotSpecifiedInConfigDB)
        case Right(value) => Right(value)
      }
      .recover(_ => Left(PasswordIsNotSpecifiedInConfigDB))
      .get.toValidatedNec
    (databaseNameV, userV, passwordV).mapN {(databaseName, user, password) =>
      DatabaseParameters(
        connectionPool = Try(cfgApp.getOptions.getList("db").getString("connectionPool").trim).toOption.filter(_.nonEmpty).getOrElse("HikariCP"),
        dataSourceClass = Try(cfgApp.getOptions.getList("db").getString("dataSourceClass").trim).toOption.filter(_.nonEmpty).getOrElse("com.microsoft.sqlserver.jdbc.SQLServerDataSource"),
        properties = DatabaseProperties(
          serverName = Try(cfgApp.getOptions.getList("db").getString("serverName").trim).toOption.filter(_.nonEmpty).getOrElse("localhost"),
          portNumber = Try(cfgApp.getOptions.getList("db").getString("portNumber").trim.toInt).getOrElse(1433),
          databaseName = databaseName,
          user = user,
          password = password
        ),
        numThreads = Try(cfgApp.getOptions.getList("db").getString("numThreads").trim.toInt).getOrElse(10)
      )
    }
  }

  def getDbConnectionInfo: Option[DatabaseParameters] = dbConnectionInfoOpt

  def setDbConnectionInfo(dbConnInfo: DatabaseParameters): Unit = dbConnectionInfoOpt = Option(dbConnInfo)

}