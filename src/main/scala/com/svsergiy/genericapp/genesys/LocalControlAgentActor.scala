package com.svsergiy.genericapp.genesys

//Akka imports:
import akka.actor.{Actor, ActorRef, Props, Timers}
import akka.event.LoggingReceive

//Other imports:
import scala.concurrent.duration.DurationInt

//Genesys imports:
import com.genesyslab.platform.commons.protocol.{ChannelClosedEvent, ChannelErrorEvent}
import com.genesyslab.platform.management.protocol.{ApplicationExecutionMode, ApplicationStatus}

//Application imports:
import com.svsergiy.genericapp.configuration.LcaConnectionParameters

object LocalControlAgentActor {
  private case object LcaReconnectTimerKey    //Timer key for reconnection to LCA in case of lost connection

  sealed trait LcaMessage
  case object ReconnectToLca extends LcaMessage                                       //Internal message to reconnect to LCA
  case object ConnectedToLca extends LcaMessage                                       //from Lca Actor to Management Actor
  case class StopApplication(gracefully: Boolean) extends LcaMessage                  //from Lca Actor to Management Actor
  case class SetApplicationStatus(appStatus: ApplicationStatus) extends LcaMessage    //from Management Actor to Lca Actor

  def props(appStartInfo: LcaConnectionParameters, managementActorRef: ActorRef): Props =
    Props(new LocalControlAgentActor(appStartInfo, managementActorRef))
}

class LocalControlAgentActor(appStartInfo: LcaConnectionParameters, managementActorRef: ActorRef)
  extends Actor with Timers with akka.actor.ActorLogging with LocalControlAgentDriver {

  import com.svsergiy.genericapp.genesys.LocalControlAgentActor._

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
      log.info(s"Retry in ${appStartInfo.reconnectTimeout} seconds")
      timers.startSingleTimer(LcaReconnectTimerKey, ReconnectToLca, appStartInfo.reconnectTimeout.seconds)
    }
  }

  protected def onLcaConnectionError(event: ChannelErrorEvent): Unit = {
    log.error(s"LCA connection error... Retry in ${appStartInfo.reconnectTimeout} seconds")
    timers.startSingleTimer(LcaReconnectTimerKey, ReconnectToLca, appStartInfo.reconnectTimeout.seconds)
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

  override def preStart(): Unit = {
    super.preStart()
    log.info("Try to connect to LCA...")
    initializeLcaDriver(appStartInfo, log).flatMap(_ => openLca())
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
    case msg @ SetApplicationStatus(appStatus: ApplicationStatus) =>
      log.debug(s"Request to change application status to: $msg")
      doUpdateStatus(getExecutionMode, appStatus)
    case ReconnectToLca =>
      if (!isLcaConnected) {
        log.info("Try to reconnect to LCA...")
        openLca()
      }
    case msg => log.error(s"Unsupported Lca Actor message: $msg")
  }
}