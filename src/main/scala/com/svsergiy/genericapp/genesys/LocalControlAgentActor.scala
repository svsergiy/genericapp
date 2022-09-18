package com.svsergiy.genericapp.genesys

import akka.actor.{Actor, ActorRef, Props, Timers}
import akka.event.LoggingReceive
import scala.concurrent.duration.DurationInt
import com.genesyslab.platform.commons.protocol.{ChannelClosedEvent, ChannelErrorEvent}
import com.genesyslab.platform.management.protocol.{ApplicationExecutionMode, ApplicationStatus}
import com.svsergiy.genericapp.configuration.LcaConnectionParameters

object LocalControlAgentActor {
  /** Timer key for reconnection to LCA in case of lost connection */
  private case object LcaReconnectTimerKey

  /** Messages between ManagementService Actor and Lca Actor */
  sealed trait LcaActorMessage
  case object ReconnectToLca extends LcaActorMessage                                       /** Internal message to reconnect to LCA */
  case object ConnectedToLca extends LcaActorMessage                                       /** from Lca Actor to ManagementService Actor */
  case class StopApplication(gracefully: Boolean) extends LcaActorMessage                  /** from Lca Actor to ManagementService Actor */
  case class SetApplicationStatus(appStatus: ApplicationStatus) extends LcaActorMessage    /** from ManagementService Actor to Lca Actor */

  def props(appStartInfo: LcaConnectionParameters, managementActorRef: ActorRef): Props =
    Props(new LocalControlAgentActor(appStartInfo, managementActorRef))
}

/** Akka Classic Actor Class is used for managing connection to Genesys LCA component. In case of failed connection or
 *  disconnection reconnection is initiated by the Actor.
 *  @param lcaConnectionInfo - configuration information to connect to Genesys LCA component
 */
class LocalControlAgentActor(lcaConnectionInfo: LcaConnectionParameters, managementActorRef: ActorRef)
  extends Actor with Timers with akka.actor.ActorLogging with LocalControlAgentDriver {

  import com.svsergiy.genericapp.genesys.LocalControlAgentActor._

  override def preStart(): Unit = {
    super.preStart()
    log.info("Try to connect to LCA...")
    initializeLcaDriver(lcaConnectionInfo, log).flatMap(_ => openLca())
      .recover(ex => log.error(s"Exception when trying to connect to LCA: $ex"))
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    if (!isLcaConnected) {
      log.info("Try to reconnect to LCA...")
      openLca()
    }
  }

  override def postStop(): Unit = {
    doUpdateStatus(Some(ApplicationExecutionMode.Exiting), ApplicationStatus.Stopped)
    closeLca()
    super.postStop()
  }

  val receive: Receive = LoggingReceive {
    case SetApplicationStatus(appStatus: ApplicationStatus) =>
      log.debug(s"Request to change application status to: $appStatus")
      doUpdateStatus(getExecutionMode, appStatus)
    case ReconnectToLca =>
      if (!isLcaConnected) {
        log.info("Try to reconnect to LCA...")
        openLca()
      }
    case msg => log.error(s"Unsupported Lca Actor message: $msg")
  }


  protected def onLcaConnectionOpened(): Unit = {
    log.info("Connected to LCA")
    if (!getExecutionMode.contains(ApplicationExecutionMode.Exiting)
      && !getControlStatus.contains(ApplicationStatus.Suspending.ordinal))
      managementActorRef ! ConnectedToLca
  }

  protected def onLcaConnectionLost(event: ChannelClosedEvent): Unit = {
    log.error("Lost connection to LCA...")
    if ((event != null) && (event.getCause != null)
      && !getExecutionMode.contains(ApplicationExecutionMode.Exiting)) {
      log.info(s"Retry in ${lcaConnectionInfo.reconnectTimeout} seconds")
      timers.startSingleTimer(LcaReconnectTimerKey, ReconnectToLca, lcaConnectionInfo.reconnectTimeout.seconds)
    }
  }

  protected def onLcaConnectionError(event: ChannelErrorEvent): Unit = {
    log.error(s"LCA connection error... Retry in ${lcaConnectionInfo.reconnectTimeout} seconds")
    timers.startSingleTimer(LcaReconnectTimerKey, ReconnectToLca, lcaConnectionInfo.reconnectTimeout.seconds)
  }

  protected def onSuspendApplication(): Unit = {
    log.info("Get request from SCS to stop application gracefully")
    managementActorRef ! StopApplication(gracefully = true)
  }

  protected def onChangeExecutionMode(execMode: ApplicationExecutionMode): Boolean = {
    if (execMode == ApplicationExecutionMode.Exiting) {
      log.info("Get request from SCS to stop application")
      managementActorRef ! StopApplication(gracefully = false)
    }
    true
  }
}