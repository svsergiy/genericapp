package com.svsergiy.genericapp

import akka.actor.{Actor, ActorRef, Props, Timers}
import akka.event.LoggingReceive
import cats.data.Validated.{Invalid, Valid}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Try
import scala.util.control.NonFatal
import com.typesafe.config.Config
import com.genesyslab.platform.management.protocol.ApplicationStatus
import com.svsergiy.genericapp.configuration.{ApplicationConfiguration, ConfigurationUpdateListener, UpdatedParameters}
import com.svsergiy.genericapp.genesys.{ConfigServerActor, LocalControlAgentActor}
import com.svsergiy.genericapp.genesys.LocalControlAgentActor.{ConnectedToLca, SetApplicationStatus, StopApplication}
import com.svsergiy.genericapp.http.HttpServer
import com.svsergiy.genericapp.database.RequestProcessorDB

object ManagementService {
  /** Timer key for ConfigServer connection timeout. When reached application will be started without
   *  connection to Genesys ConfigServer
   */
  private case object ConfigServerConnectionTimerKey
  /** Timer key for restarting Http Server in case of problem with binding to specified port */
  private case object HttpServerRestartTimerKey

  /** ManagementService Actor messages that are used for controlling application */
  sealed trait ManagementServiceMessage
  case object StartConfigServerActor extends ManagementServiceMessage
  case object ConfServerActorStartTimeout extends ManagementServiceMessage
  case class ConfigurationUpdated(updatedParams: UpdatedParameters) extends ManagementServiceMessage
  case object SystemInitialized extends ManagementServiceMessage
  case object StartHttpServer extends ManagementServiceMessage
  case object HttpServerStarted extends ManagementServiceMessage
  case class FailedToStartHttpServer(ex: Throwable) extends ManagementServiceMessage
  case object RequestProcessorAvailable extends ManagementServiceMessage
  case object RequestProcessorUnavailable extends ManagementServiceMessage

  /** Case class is used for describing application status */
  case class SystemStatus(requestsProcessorStatus: ApplicationStatus, httpServerStatus: ApplicationStatus) {
    def getSystemStatus: ApplicationStatus = {
      if (requestsProcessorStatus == ApplicationStatus.Running && httpServerStatus == ApplicationStatus.Running) {
        ApplicationStatus.Running
      } else if (requestsProcessorStatus == ApplicationStatus.ServiceUnavailable || httpServerStatus == ApplicationStatus.ServiceUnavailable) {
        ApplicationStatus.ServiceUnavailable
      } else ApplicationStatus.Initializing
    }
  }

  def props(): Props = Props(new ManagementService())
}

class ManagementService() extends Actor with Timers with akka.actor.ActorLogging with ConfigurationUpdateListener {
  import com.svsergiy.genericapp.ManagementService._

  private implicit val config: Config = context.system.settings.config

  // TODO: Transfer mutable variables to Actor states
  private var confServRefOpt: Option[ActorRef] = None
  private var lcaRefOpt: Option[ActorRef] = None
  private val requestProcessor: RequestProcessorDB = new RequestProcessorDB
  private var httpServerOpt: Option[HttpServer] = None

  override def preStart(): Unit = {
    super.preStart()
    // Read configuration parameters from application.conf file:
    ApplicationConfiguration.parseGenesysConnectionParameters match {
      case Valid(genConnParams) => ApplicationConfiguration.genesysConnectionInfoOpt = Some(genConnParams)
      case Invalid(errs) => errs.map(err => log.warning(err.errorMessage))
    }
    ApplicationConfiguration.parseDatabaseParameters match {
      case Valid(dbParams) => ApplicationConfiguration.dbConnectionInfoOpt = Some(dbParams)
      case Invalid(errs) => errs.map(err => log.warning(err.errorMessage))
    }
    ApplicationConfiguration.parseHttpServerParameters match {
      case Valid(httpSrvParams) => ApplicationConfiguration.httpServerInfoOpt = Some(httpSrvParams)
      case Invalid(errs) => errs.map(err => log.warning(err.errorMessage))
    }
    context.self ! StartConfigServerActor
  }

  val receive: Receive = LoggingReceive {
    case StartConfigServerActor =>
      /** Start ConfigServer Actor */
      ApplicationConfiguration.genesysConnectionInfoOpt.map { genConnParams =>
        context.become(startingConfigServerActor)
        /** Add itself as configuration update listener */
        ApplicationConfiguration.addConfigurationUpdateListener(this)
        confServRefOpt = Option(context.actorOf(ConfigServerActor.props(genConnParams)))
        timers.startSingleTimer(ConfigServerConnectionTimerKey, ConfServerActorStartTimeout, genConnParams.connectionTimeout.seconds)
      }.getOrElse {
        context.become(
          initializingSystem(
            UpdatedParameters(appStart = false, httpServer = false, database = false),
            SystemStatus(ApplicationStatus.Stopped, ApplicationStatus.Stopped))
        )
        initializeSystem()
      }
  }

  val startingConfigServerActor: Receive = LoggingReceive {
    case ConfServerActorStartTimeout =>
      log.warning("Cant start ConfigServer actor in configured, will continue without connection to ConfigServer...")
      ApplicationConfiguration.removeConfigurationUpdateListener(this)
      confServRefOpt.foreach(context.stop)
      confServRefOpt = None
      context.become(
        initializingSystem(
          UpdatedParameters(appStart = false, httpServer = false, database = false),
          SystemStatus(ApplicationStatus.Stopped, ApplicationStatus.Stopped))
      )
      initializeSystem()
    case ConfigurationUpdated(_) =>
      timers.cancel(ConfigServerConnectionTimerKey)
      context.become(
        initializingSystem(
          UpdatedParameters(appStart = false, httpServer = false, database = false),
          SystemStatus(ApplicationStatus.Stopped, ApplicationStatus.Stopped))
      )
      initializeSystem()
  }

  def initializingSystem(updatedParams: UpdatedParameters, systemStatus: SystemStatus): Receive = LoggingReceive {
    case SystemInitialized =>
      context.become(running(systemStatus))
      log.info("System initialized successfully")
      if (updatedParams.appStart || updatedParams.httpServer || updatedParams.database) context.self ! ConfigurationUpdated(updatedParams)
    case ConfigurationUpdated(updParams) =>
      context.become(
        initializingSystem(
          UpdatedParameters(updParams.appStart || updatedParams.appStart, updParams.httpServer || updatedParams.httpServer, updParams.database || updatedParams.database),
          systemStatus
        )
      )
    case ConnectedToLca =>
      lcaRefOpt.foreach(_ ! SetApplicationStatus(systemStatus.getSystemStatus))
    case RequestProcessorAvailable =>
      val newSystemStatus = systemStatus.copy(requestsProcessorStatus = ApplicationStatus.Running)
      context.become(
        initializingSystem(updatedParams, newSystemStatus)
      )
      lcaRefOpt.foreach(_ ! SetApplicationStatus(newSystemStatus.getSystemStatus))
    case RequestProcessorUnavailable =>
      val newSystemStatus = systemStatus.copy(requestsProcessorStatus = ApplicationStatus.ServiceUnavailable)
      context.become(
        initializingSystem(updatedParams, newSystemStatus)
      )
      lcaRefOpt.foreach(_ ! SetApplicationStatus(newSystemStatus.getSystemStatus))
    case StartHttpServer =>
      httpServerOpt.foreach { httpSrv =>
        if (!httpSrv.isStarted) {
          log.debug(s"Trying to start Http Server on host: ${httpSrv.httpServerInfo.host} and port: ${httpSrv.httpServerInfo.port}...")
          httpSrv.startServer.map(_ => context.self ! HttpServerStarted).recover {
            case NonFatal(ex) =>
              context.self ! FailedToStartHttpServer(ex)
          }
        }
      }
    case FailedToStartHttpServer(ex) =>
      httpServerOpt.foreach {_ =>
        log.error(s"Failed to start Http Server, exception: $ex, retry in 10 seconds")
        val newSystemStatus = systemStatus.copy(httpServerStatus = ApplicationStatus.ServiceUnavailable)
        context.become(
          initializingSystem(updatedParams, newSystemStatus)
        )
        lcaRefOpt.foreach(_ ! SetApplicationStatus(newSystemStatus.getSystemStatus))
        timers.startSingleTimer(HttpServerRestartTimerKey, StartHttpServer, 10.seconds)
      }
    case HttpServerStarted =>
      httpServerOpt.foreach { httpSrv =>
        log.info(s"Http Server successfully started on host: ${httpSrv.httpServerInfo.host} and port: ${httpSrv.httpServerInfo.port}")
        val newSystemStatus = systemStatus.copy(httpServerStatus = ApplicationStatus.Running)
        context.become(
          initializingSystem(updatedParams, newSystemStatus)
        )
        lcaRefOpt.foreach(_ ! SetApplicationStatus(newSystemStatus.getSystemStatus))
      }
    case StopApplication(gracefully) =>
      log.info("Get request to stop application")
      timers.cancel(HttpServerRestartTimerKey)
      timers.cancel(ConfigServerConnectionTimerKey)
      stopApplication(gracefully)
  }

  def running(systemStatus: SystemStatus): Receive = LoggingReceive {
    case ConfigurationUpdated(updatedParams) =>
      if (updatedParams.appStart) reinitializeLcaActor()
      if (updatedParams.httpServer) reinitializeHttpServer()
      if (updatedParams.database) reinitializeRequestProcessor()
    case ConnectedToLca =>
      lcaRefOpt.foreach(_ ! SetApplicationStatus(systemStatus.getSystemStatus))
    case RequestProcessorAvailable =>
      val newSystemStatus = systemStatus.copy(requestsProcessorStatus = ApplicationStatus.Running)
      context.become(
        running(newSystemStatus)
      )
      lcaRefOpt.foreach(_ ! SetApplicationStatus(newSystemStatus.getSystemStatus))
    case RequestProcessorUnavailable =>
      val newSystemStatus = systemStatus.copy(requestsProcessorStatus = ApplicationStatus.ServiceUnavailable)
      context.become(
        running(newSystemStatus)
      )
      lcaRefOpt.foreach(_ ! SetApplicationStatus(newSystemStatus.getSystemStatus))
    case StartHttpServer =>
      httpServerOpt.foreach { httpSrv =>
        if (!httpSrv.isStarted) {
          log.debug(s"Trying to start Http Server on host: ${httpSrv.httpServerInfo.host} and port: ${httpSrv.httpServerInfo.port}...")
          httpSrv.startServer.map(_ => context.self ! HttpServerStarted).recover {
            case NonFatal(ex) =>
              context.self ! FailedToStartHttpServer(ex)
          }
        }
      }
    case FailedToStartHttpServer(ex) =>
      httpServerOpt.foreach {_ =>
        log.error(s"Failed to start Http Server, exception: $ex, retry in 10 seconds")
        val newSystemStatus = systemStatus.copy(httpServerStatus = ApplicationStatus.ServiceUnavailable)
        context.become(
          running(newSystemStatus)
        )
        lcaRefOpt.foreach(_ ! SetApplicationStatus(newSystemStatus.getSystemStatus))
        timers.startSingleTimer(HttpServerRestartTimerKey, StartHttpServer, 10.seconds)
      }
    case HttpServerStarted =>
      httpServerOpt.foreach { httpSrv =>
        log.info(s"Http Server successfully started on host: ${httpSrv.httpServerInfo.host} and port: ${httpSrv.httpServerInfo.port}")
        val newSystemStatus = systemStatus.copy(httpServerStatus = ApplicationStatus.Running)
        context.become(
          running(newSystemStatus)
        )
        lcaRefOpt.foreach(_ ! SetApplicationStatus(newSystemStatus.getSystemStatus))
      }
    case StopApplication(gracefully) =>
      log.info("Get request to stop application")
      timers.cancel(HttpServerRestartTimerKey)
      timers.cancel(ConfigServerConnectionTimerKey)
      stopApplication(gracefully)
  }

  override def configurationUpdated(updatedParams: UpdatedParameters): Unit = {
    log.info(s"Application Configuration has been updated: ${updatedParams.toString}")
    context.self ! ConfigurationUpdated(updatedParams)
  }

  private def stopApplication(gracefully: Boolean): Unit = {
    if (gracefully) {
      log.info("Start to stop application gracefully...")
      /** In case of graceful stop "Suspended" status should be sent to Genesys to prevent "Application Failure"
       *  Alarm message generation on Genesys side
       */
      lcaRefOpt.foreach(_ ! SetApplicationStatus(ApplicationStatus.Suspended))
    }
    log.info("Stopping Genesys Generic App...")
    httpServerOpt.foreach(_.stopServer)
    requestProcessor.release()
    lcaRefOpt.foreach(context.stop)
    confServRefOpt.foreach(context.stop)
    context.system.terminate()
  }

  private def initializeSystem(): Future[Unit] = Future {
    if (ApplicationConfiguration.httpServerInfoOpt.isEmpty || ApplicationConfiguration.dbConnectionInfoOpt.isEmpty) {
      log.error("Mandatory configuration parameters are not specified. Application will be stopped...")
      StopApplication(false)
    } else {
      ApplicationConfiguration.lcaConnectionInfoOpt.foreach { lcaConnInfo =>
        lcaRefOpt = Some(context.actorOf(LocalControlAgentActor.props(lcaConnInfo, context.self)))
      }
      ApplicationConfiguration.dbConnectionInfoOpt.foreach { dbConnInfo =>
        requestProcessor.setDbParameters(dbConnInfo)
        requestProcessor.initialize()
        context.self ! RequestProcessorAvailable
      }
      ApplicationConfiguration.httpServerInfoOpt.foreach { httpSrvInfo =>
        val httpSrv = new HttpServer(context, httpSrvInfo, requestProcessor)
        httpServerOpt = Some(httpSrv)
        context.self ! StartHttpServer
      }
      context.self ! SystemInitialized
    }
  }

  private def reinitializeLcaActor(): Try[Unit] = Try {
    log.info("Try to restart Lca Actor")
    ApplicationConfiguration.lcaConnectionInfoOpt.foreach { lcaConnInfo =>
      lcaRefOpt.foreach(context.stop)
      val lcaRef = context.actorOf(LocalControlAgentActor.props(lcaConnInfo, context.self))
      lcaRefOpt = Some(lcaRef)
    }
  }

  private def reinitializeHttpServer(): Try[Unit] = Try {
    log.info("Try to restart Http Server")
    ApplicationConfiguration.httpServerInfoOpt.foreach { httpSrvInfo =>
      httpServerOpt.foreach { httpSrv =>
        httpSrv.stopServer
      }
      val httpSrv = new HttpServer(context, httpSrvInfo, requestProcessor)
      httpServerOpt = Some(httpSrv)
      context.self ! StartHttpServer
    }
  }

  private def reinitializeRequestProcessor(): Try[Unit] = Try {
    log.info("Try to restart Requests Processor")
    ApplicationConfiguration.dbConnectionInfoOpt.foreach { dbConnInfo =>
      context.self ! RequestProcessorUnavailable
      requestProcessor.release()
      requestProcessor.setDbParameters(dbConnInfo)
      requestProcessor.initialize()
      context.self ! RequestProcessorAvailable
    }
  }
}