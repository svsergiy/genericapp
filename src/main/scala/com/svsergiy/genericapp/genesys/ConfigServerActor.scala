package com.svsergiy.genericapp.genesys

//Akka imports:
import akka.actor.{Actor, ActorRef, Props, Timers}
import akka.event.LoggingReceive
import com.svsergiy.genericapp.ManagementService.UpdatedParameters

//Cats imports:
import cats.data.Validated.{Invalid, Valid}

//Other imports:
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration.DurationInt
import scala.concurrent.ExecutionContext.Implicits.global

//Genesys imports:
import com.genesyslab.platform.commons.protocol.ChannelState
import com.genesyslab.platform.applicationblocks.commons.Action
import com.genesyslab.platform.applicationblocks.com.{ICfgDelta, ConfEvent}
import com.genesyslab.platform.applicationblocks.com.objects.{CfgApplication, CfgHost}
import com.genesyslab.platform.configuration.protocol.types.CfgObjectType

//Application imports:
import com.svsergiy.genericapp.configuration.{ApplicationConfiguration, GenesysConnectionParameters}
import com.svsergiy.genericapp.genesys.CfgEventsSubscription.CfgEventsSubscriptionData

object ConfigServerActor {
  private case object CfgSrvConfigTimerKey       //Timer key for reconnection to ConfigServer and reread configuration in case of error/lost connection

  sealed trait ConfigServerRequest
  case object ReadConfiguration extends ConfigServerRequest
  case object ReconnectToConfigServer extends ConfigServerRequest
  case class UpdateApplicationInfo(cfgEvent: ConfEvent) extends ConfigServerRequest
  case class UpdateHostInfo(cfgEvent: ConfEvent) extends ConfigServerRequest

  def props(genesysConnParams: GenesysConnectionParameters, managementActorRef: ActorRef): Props =
    Props(new ConfigServerActor(genesysConnParams, managementActorRef))
}

class ConfigServerActor(genesysConnParams: GenesysConnectionParameters, managementActorRef: ActorRef)
              extends Actor with Timers with akka.actor.ActorLogging with ConfigServerDriver{

  import com.svsergiy.genericapp.genesys.ConfigServerActor._

  override def preStart(): Unit = {
    super.preStart()
    initializeConfigSrvDriver(genesysConnParams, log).map(_ => context.self ! ReconnectToConfigServer)
      .recover(ex => log.error(s"Exception when trying to create Configuration Server service: $ex"))
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = super.preRestart(reason, message)

  override def postRestart(reason: Throwable): Unit = super.postRestart(reason)

  override def postStop(): Unit = {
    unsubscribeAll()
    closeConfigSrv().map(_ => releaseConfigSrvDriver()).recover(_ => ())
    super.postStop()
  }

  protected def onConfigServerConnectionOpened(): Unit = {
    context.self ! ReadConfiguration
  }

  protected def onConfigServerConnectionClosed(): Unit = {
    log.error(s"Disconnected from Configuration Server. Try to reconnect in ${genesysConnParams.warmStandby.retryDelay} seconds...")
    timers.startSingleTimer(CfgSrvConfigTimerKey, ReconnectToConfigServer, genesysConnParams.warmStandby.retryDelay.seconds)
  }

  private def onCfgApplicationChanged(cfgEvent: ConfEvent): Unit = {
    context.self ! UpdateApplicationInfo(cfgEvent)
  }

  private def onCfgHostChanged(cfgEvent: ConfEvent): Unit = {
    context.self ! UpdateHostInfo(cfgEvent)
  }

  private class ConfigUpdateEventHandler extends Action[ConfEvent] {
    override def handle(obj: ConfEvent): Unit = {
      if ((obj != null) && (obj.getEventType == ConfEvent.EventType.ObjectUpdated)) {
        obj.getObjectType match {
          case CfgObjectType.CFGApplication =>
            log.debug(s"Application object has been updated in configuration: ${obj.getCfgObject}")
            onCfgApplicationChanged(obj)
          case CfgObjectType.CFGHost =>
            log.debug(s"Host object has been updated in configuration: ${obj.getCfgObject}")
            onCfgHostChanged(obj)
          case _ =>
            log.debug(s"Unexpected object has been updated in configuration: ${obj.getCfgObject}")
        }
      } else log.warning("Unexpected Configuration change event has been received")
    }
  }

  private def parseConfigurationData(appInfo: CfgApplication, hostInfo: CfgHost): Unit = {
    val lcaConnInfoOpt = ApplicationConfiguration.readLcaConnectionParameters(appInfo, hostInfo) match {
      case Valid(lcaConnInfo) =>
        log.debug(lcaConnInfo.toString)
        Some(lcaConnInfo)
      case Invalid(errs) =>
        log.warning("Lca connection parameters has not been retrieved from Configuration database")
        errs.map(err => log.warning(err.errorMessage))
        None
    }
    val httpServerInfoOpt = ApplicationConfiguration.readHttpServerParameters(appInfo, hostInfo) match {
      case Valid(httpServerInfo) =>
        log.debug(httpServerInfo.toString)
        Some(httpServerInfo)
      case Invalid(errs) =>
        log.warning("Http Server connection parameters has not been retrieved from Configuration database")
        errs.map(err => log.warning(err.errorMessage))
        None
    }
    val dbConnectionInfoOpt = ApplicationConfiguration.readDatabaseParameters(appInfo) match {
      case Valid(dbConnectionInfo) =>
        log.debug(dbConnectionInfo.toString)
        Some(dbConnectionInfo)
      case Invalid(errs) =>
        log.warning("Database connection parameters has not been retrieved from Configuration database")
        errs.map(err => log.warning(err.errorMessage))
        None
    }
    ApplicationConfiguration.configurationUpdated(
      UpdatedParameters(
        appStart = lcaConnInfoOpt.exists { lcaConnInfo =>
          if (ApplicationConfiguration.getLcaConnectionInfo.contains(lcaConnInfo)) {
            log.debug("Lca Connection parameters has not been changed")
            false
          } else {
            ApplicationConfiguration.setLcaConnectionInfo(lcaConnInfo)
            log.debug("Lca Connection parameters has been changed")
            true
          }
        },
        httpServer = httpServerInfoOpt.exists { httpServerInfo =>
          if (ApplicationConfiguration.getHttpServerInfo.contains(httpServerInfo)) {
            log.debug("Http Server parameters has not been changed")
            false
          } else {
            ApplicationConfiguration.setHttpServerInfo(httpServerInfo)
            log.debug("Http Server parameters has been changed")
            true
          }
        },
        database = dbConnectionInfoOpt.exists { dbConnectionInfo =>
          if (ApplicationConfiguration.getDbConnectionInfo.contains(dbConnectionInfo)) {
            log.debug("Database Connection parameters has not been changed")
            false
          } else {
            ApplicationConfiguration.setDbConnectionInfo(dbConnectionInfo)
            log.debug("Database Connection parameters has been changed")
            true
          }
        }
      )
    )
  }

  val receive: Receive = processUpdates(None, None)

  def processUpdates(appInfoOpt: Option[CfgApplication], hostInfoOpt: Option[CfgHost]): Receive = LoggingReceive {
    case ReconnectToConfigServer =>
      if (getChannelState != ChannelState.Opened) {
        context.become(processUpdates(None, None))
        openConfigSrv().recover { ex =>
          log.error(s"Exception when trying to connect to Configuration Server: $ex. Retry in ${genesysConnParams.warmStandby.retryDelay} seconds...")
          timers.startSingleTimer(CfgSrvConfigTimerKey, ReconnectToConfigServer, genesysConnParams.warmStandby.retryDelay.seconds)
        }
      }
    case ReadConfiguration =>
      log.info("Try to retrieve Application information from Configuration database...")
      retrieveApplication(genesysConnParams.configSrv.clientName) match {
        case Failure(ex) =>
          log.error(s"Can't retrieve Application information from Configuration database due to exception: $ex, retry in 10 seconds...")
          timers.startSingleTimer(CfgSrvConfigTimerKey, ReadConfiguration, 10.seconds)
        case Success(appInfo) =>
          log.debug(s"${appInfo.toString}")
          log.info("Application information has been successfully retrieved  from Configuration database...")
          Try(appInfo.getServerInfo.getHostDBID.toInt) match {
            case Failure(ex) =>
              log.error(s"Can't retrieve Host DBID information from Configuration database due to exception: $ex, retry in 10 seconds...")
              timers.startSingleTimer(CfgSrvConfigTimerKey, ReadConfiguration, 10.seconds)
            case Success(0) =>
              log.error("Can't retrieve Host DBID information from Configuration database, retry in 10 seconds...")
              timers.startSingleTimer(CfgSrvConfigTimerKey, ReadConfiguration, 10.seconds)
            case Success(hostDbId) =>
              log.info("Try to retrieve Host information from Configuration database...")
              retrieveHost(hostDbId) match {
                case Failure(ex) =>
                  log.error(s"Can't retrieve Host information from Configuration database due to exception: $ex, retry in 10 seconds...")
                  timers.startSingleTimer(CfgSrvConfigTimerKey, ReadConfiguration, 10.seconds)
                case Success(hostInfo) =>
                  context.become(processUpdates(Some(appInfo),Some(hostInfo)))
                  log.debug(s"${hostInfo.toString}")
                  log.info("Host information has been successfully retrieved  from Configuration database...")
                  parseConfigurationData(appInfo, hostInfo)
                  subscribe(new ConfigUpdateEventHandler, "app", CfgEventsSubscriptionData(None, Some(appInfo.getObjectType), Some(appInfo.getObjectDbid)))
                  subscribe(new ConfigUpdateEventHandler, "host", CfgEventsSubscriptionData(None, Some(hostInfo.getObjectType), Some(hostInfo.getObjectDbid)))
              }
          }
      }
    case UpdateApplicationInfo(cfgEvent: ConfEvent) =>
      appInfoOpt.zip(hostInfoOpt).foreach {
        case (appInfo, hostInfo) =>
          if (cfgEvent.getObjectId == appInfo.getObjectDbid) {
            cfgEvent.getCfgObject match {
              case delta: ICfgDelta =>
                appInfo.update(delta)
                Try(appInfo.getServerInfo.getHost.getDBID.toInt) match {
                  case Failure(ex) =>
                    log.warning(s"Cant retrieve Host DBID after application update due to exception: $ex, will continue with current host configuration")
                    context.become(processUpdates(Some(appInfo), Some(hostInfo)))
                    parseConfigurationData(appInfo, hostInfo)
                    unsubscribe("host")
                  case Success(0) =>
                    log.warning("Cant retrieve Host DBID after application update, will continue with current host configuration")
                    context.become(processUpdates(Some(appInfo), Some(hostInfo)))
                    parseConfigurationData(appInfo, hostInfo)
                    unsubscribe("host")
                  case Success(hostDbId) if hostDbId != hostInfo.getObjectDbid =>
                    log.info("Application host has been changed. Try to retrieve Host information from Configuration database...")
                    val newHostInfo = retrieveHost(hostDbId).getOrElse(hostInfo)
                    context.become(processUpdates(Some(appInfo), Some(newHostInfo)))
                    parseConfigurationData(appInfo, newHostInfo)
                    subscribe(new ConfigUpdateEventHandler, "host", CfgEventsSubscriptionData(None, Some(newHostInfo.getObjectType), Some(newHostInfo.getObjectDbid)))
                  case _ =>
                    context.become(processUpdates(Some(appInfo), Some(hostInfo)))
                    parseConfigurationData(appInfo, hostInfo)
                }
            }
          }
      }
    case UpdateHostInfo(cfgEvent: ConfEvent) =>
      appInfoOpt.zip(hostInfoOpt).foreach {
        case (appInfo, hostInfo) =>
          if (cfgEvent.getObjectId == hostInfo.getObjectDbid) {
            cfgEvent.getCfgObject match {
              case delta: ICfgDelta =>
                hostInfo.update(delta)
                context.become(processUpdates(Some(appInfo), Some(hostInfo)))
                parseConfigurationData(appInfo, hostInfo)
            }
          }
      }
    case msg => log.warning(s"Unsupported message received: $msg")
  }

}
