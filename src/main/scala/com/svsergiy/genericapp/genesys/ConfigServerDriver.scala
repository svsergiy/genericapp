package com.svsergiy.genericapp.genesys

//Akka imports:
import akka.event.LoggingAdapter

//Other imports:
import scala.collection.mutable
import scala.util.{Failure, Success, Try}
import scala.concurrent.Future
import concurrent.ExecutionContext.Implicits.global

//Genesys imports:
import com.genesyslab.platform.commons.protocol.{Endpoint, ChannelState}
import com.genesyslab.platform.commons.connection.configuration.PropertyConfiguration
import com.genesyslab.platform.commons.connection.configuration.ClientADDPOptions.AddpTraceMode
import com.genesyslab.platform.configuration.protocol.ConfServerProtocol
import com.genesyslab.platform.configuration.protocol.types.CfgAppType
import com.genesyslab.platform.standby.{WarmStandby, WSHandler}
import com.genesyslab.platform.standby.events.{WSAllTriedUnsuccessfullyEvent, WSDisconnectedEvent, WSOpenedEvent, WSTriedUnsuccessfullyEvent}
import com.genesyslab.platform.applicationblocks.commons.Action
import com.genesyslab.platform.applicationblocks.com.{ConfEvent, ConfServiceFactory, IConfService, NotificationFilter, NotificationQuery}
import com.genesyslab.platform.applicationblocks.com.objects.{CfgApplication, CfgHost}
import com.genesyslab.platform.applicationblocks.com.queries.{CfgApplicationQuery, CfgHostQuery}

//Application imports:
import com.svsergiy.genericapp.configuration.GenesysConnectionParameters
import com.svsergiy.genericapp.exceptions.ConfigServerActorExceptions._
import com.svsergiy.genericapp.genesys.CfgEventsSubscription._

trait ConfigServerDriver {
  private var logOpt: Option[LoggingAdapter] = None
  private var wsOpt: Option[WarmStandby] = None
  private var confServiceOpt: Option[IConfService] = None

  private var currentSubscriptions = mutable.Map.empty[String, CfgEventsSubscriptionResult]

  protected def onConfigServerConnectionOpened(): Unit

  protected def onConfigServerConnectionClosed(): Unit

  private class ConfigSrvWarmStandbyEventsHandler extends WSHandler {
    override def onEndpointTriedUnsuccessfully(event: WSTriedUnsuccessfullyEvent): Unit = {
      super.onEndpointTriedUnsuccessfully(event)
      logOpt.foreach(_.error(s"Failed to connect to ${event.getEndpoint.toString}"))
    }

    override def onChannelOpened(event: WSOpenedEvent): Unit = {
      super.onChannelOpened(event)
      logOpt.foreach(_.info("Connected to Configuration Server..."))
      onConfigServerConnectionOpened()
    }

    override def onChannelDisconnected(event: WSDisconnectedEvent): Unit = {
      super.onChannelDisconnected(event)
      logOpt.foreach(_.error("Disconnected from Configuration Server..."))
      onConfigServerConnectionClosed()
    }

    override def onAllEndpointsTriedUnsuccessfully(event: WSAllTriedUnsuccessfullyEvent): Unit = {
      super.onAllEndpointsTriedUnsuccessfully(event)
      logOpt.foreach(_.error("Failed to connect to all endpoints"))
    }
  }

  def initializeConfigSrvDriver(genesysConnParams: GenesysConnectionParameters, log: LoggingAdapter): Try[Unit] = Try {
    // Set logger
    logOpt = Option(log)

    // Create protocol
    val configSrvProtocol: ConfServerProtocol = new ConfServerProtocol()
    configSrvProtocol.setClientName(genesysConnParams.configSrv.clientName)
    configSrvProtocol.setClientApplicationType(CfgAppType.valueOf(genesysConnParams.configSrv.cfgAppType).ordinal)

    // Create service
    confServiceOpt = Try(ConfServiceFactory.createConfService(configSrvProtocol)).toOption

    // Create configuration for addp
    val propertyConfig = new PropertyConfiguration
    propertyConfig.setUseAddp(genesysConnParams.addp.enabled)
    propertyConfig.setAddpClientTimeout(genesysConnParams.addp.clientTimeout)
    propertyConfig.setAddpServerTimeout(genesysConnParams.addp.serverTimeout)
    propertyConfig.setAddpTraceMode(AddpTraceMode.valueOf(genesysConnParams.addp.traceMode))

    // Create Warm Standby connection
    val ws: WarmStandby = new WarmStandby(configSrvProtocol,
      new Endpoint(genesysConnParams.configSrv.prmConfigSrv.name, genesysConnParams.configSrv.prmConfigSrv.host, genesysConnParams.configSrv.prmConfigSrv.port, propertyConfig),
      new Endpoint(genesysConnParams.configSrv.bkpConfigSrv.name, genesysConnParams.configSrv.bkpConfigSrv.host, genesysConnParams.configSrv.bkpConfigSrv.port, propertyConfig)
    )
    ws.getConfig
      .setBackupDelay(genesysConnParams.warmStandby.backupDelay * 1000)
      .setReconnectionRandomDelayRange((genesysConnParams.warmStandby.reconnectionRandomDelayRange * 1000).toInt)
      .setTimeout(genesysConnParams.warmStandby.timeout * 1000)
    ws.setHandler(new ConfigSrvWarmStandbyEventsHandler)
    wsOpt = Option(ws)
  }

  def releaseConfigSrvDriver(): Try[Unit] = Try {
    wsOpt = None
    confServiceOpt.foreach(confService => ConfServiceFactory.releaseConfService(confService))
    confServiceOpt = None
  }

  def getChannelState: ChannelState = {
    Try(confServiceOpt.get.getProtocol.getState).recover(_ => ChannelState.Closed).get
  }

  def openConfigSrv(): Future[Unit] = {
    Future {
      wsOpt.foreach(_.openAsync.get)
    }
  }

  def closeConfigSrv(): Future[Unit] = {
    Future {
      wsOpt.foreach(_.closeAsync.get)
    }
  }

  def retrieveApplication(appName: String): Try[CfgApplication] = {
    if (confServiceOpt.isEmpty) {
      logOpt.foreach(_.warning("Config Service is not defined"))
      Failure(new ConfigServiceNotDefinedException("Config Service is not defined"))
    } else {
      Try(confServiceOpt.get.retrieveObject(classOf[CfgApplication], new CfgApplicationQuery(appName))).flatMap { cfgApp =>
        if (cfgApp != null) Success(cfgApp) else {
          logOpt.foreach(_.warning(s"Application $appName has not been found in configuration"))
          Failure(new ApplicationNotFoundException(s"Application $appName has not been found"))
        }
      }
    }
  }

  def retrieveHost(hostDbId: Int): Try[CfgHost] = {
    if (confServiceOpt.isEmpty) {
      logOpt.foreach(_.warning("Config Service is not defined"))
      Failure(new ConfigServiceNotDefinedException("Config Service is not defined"))
    } else {
      Try(confServiceOpt.get.retrieveObject(classOf[CfgHost], new CfgHostQuery(hostDbId))).flatMap { cfgHost =>
        if (cfgHost != null) Success(cfgHost) else {
          logOpt.foreach(_.warning(s"Host with DBID $hostDbId has not been found in configuration"))
          Failure(new HostNotFoundException(s"Host with DBID $hostDbId has not been found in configuration"))
        }
      }
    }
  }

  /**
   * Unsubscribes from Configuration server notifications about the subscription
   * with specified name.
   * @param subscriptionName the name of the subscription
   */
  def unsubscribe(subscriptionName: String): Try[Unit] = {
    confServiceOpt.map (confService =>
      Try {
        if (currentSubscriptions.contains(subscriptionName)) {
          confService.unregister(currentSubscriptions(subscriptionName).handler)
          confService.unsubscribe(currentSubscriptions(subscriptionName).subscription)
          currentSubscriptions -= subscriptionName
        }
        ()
      }
    ).getOrElse(Failure(new ConfigServiceNotDefinedException("Config Service is not defined")))
  }

  def unsubscribeAll(): Try[Unit] = {
    confServiceOpt.map { confService =>
      val unregisterRes = currentSubscriptions.map { case (_, subscriptionResult) =>
        Try {
          confService.unregister(subscriptionResult.handler)
          confService.unsubscribe(subscriptionResult.subscription)
        }
      }
      currentSubscriptions = mutable.Map.empty[String, CfgEventsSubscriptionResult]
      unregisterRes.find {
        case Failure(_) => true
        case _ => false
      }.getOrElse(Success(()))
    }.getOrElse(Failure(new ConfigServiceNotDefinedException("Config Service is not defined")))
  }

  /**
   * Subscribes to events about an object of the specified type, and with the specified dbid.
   * @param handler           a delegate which will be called when events are received
   * @param subscriptionName  the name of the subscription
   * @param cfgEvSubscription the TenantId and/or CfgObjectType and/or CfgObjectId of the
   *                          subscribed object
   */
  def subscribe(handler: Action[ConfEvent], subscriptionName: String, cfgEvSubscription: CfgEventsSubscriptionData): Try[Unit] = {
    confServiceOpt.map {confService =>
      if (currentSubscriptions.contains(subscriptionName)) Try {
        val subscriptionResult = currentSubscriptions(subscriptionName)
        confService.unregister(subscriptionResult.handler)
        confService.unsubscribe(subscriptionResult.subscription)
      }
      val notificationQuery = new NotificationQuery()
      cfgEvSubscription.tenantDbIdOpt.foreach(tenantId => notificationQuery.setTenantDbid(tenantId))
      cfgEvSubscription.objectTypeOpt.foreach(objType => notificationQuery.setObjectType(objType))
      cfgEvSubscription.objectDbIdOpt.foreach(objDbId => notificationQuery.setObjectDbid(objDbId))
      val filter = new NotificationFilter(notificationQuery)
      Try {
        confService.register(handler, filter)
      }.flatMap { _ =>
        Try(confService.subscribe(notificationQuery))
      }.map { subscription =>
        currentSubscriptions += (subscriptionName -> CfgEventsSubscriptionResult(handler, subscription))
        ()
      }
    }.getOrElse(Failure(new ConfigServiceNotDefinedException("Config Service is not defined")))
  }

}
