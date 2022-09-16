package com.svsergiy.genericapp.genesys

//Akka imports:
import akka.event.LoggingAdapter

//Other imports:
import scala.util.{Try, Failure}
import scala.util.control.NonFatal
import java.util.EventObject

//Genesys imports:
import com.genesyslab.platform.commons.protocol.{ChannelState, ChannelClosedEvent, ChannelErrorEvent, ChannelListener, Message, MessageHandler}
import com.genesyslab.platform.management.protocol.{LocalControlAgentProtocol, ApplicationExecutionMode, ApplicationStatus}
import com.genesyslab.platform.management.protocol.localcontrolagent.requests.RequestUpdateStatus
import com.genesyslab.platform.management.protocol.localcontrolagent.responses.ResponseExecutionModeChanged
import com.genesyslab.platform.management.protocol.localcontrolagent.events.{EventChangeExecutionMode, EventSuspendApplication}

//Application imports:
import com.svsergiy.genericapp.configuration.LcaConnectionParameters

trait LocalControlAgentDriver {
  private var logOpt: Option[LoggingAdapter] = None
  private var lcaProtocolOpt: Option[LocalControlAgentProtocol] = None

  protected def initializeLcaDriver(appStartInfo: LcaConnectionParameters, log: LoggingAdapter): Try[Unit] = {
    logOpt = Option(log)
    if (lcaProtocolOpt.isDefined)
      Failure(new Exception("The LCA protocol is already created"))
    else {
      Try {
        val lcaProtocol = new LocalControlAgentProtocol(appStartInfo.lcaPort)
        lcaProtocol.setApplicationType(appStartInfo.appType)
        lcaProtocol.setClientName(appStartInfo.appName)
        lcaProtocol.setClientId(appStartInfo.appDbId)
        lcaProtocol.addChannelListener(new LCAChannelListener())
        lcaProtocol.setMessageHandler(new LCAMessageHandler())
        lcaProtocolOpt = Some(lcaProtocol)
      }
    }
  }

  protected def openLca(): Try[Unit] =
    Try {
      lcaProtocolOpt.foreach {
        lcaProt => {
          lcaProt.openAsync()
        }
      }
    }

  protected def closeLca(): Unit = {
    Try {
      lcaProtocolOpt.foreach(_.closeAsync())
    }
    lcaProtocolOpt = None
  }

  def isLcaConnected: Boolean =
    lcaProtocolOpt.exists(_.getState == ChannelState.Opened)

  def getExecutionMode: Option[ApplicationExecutionMode] =
    lcaProtocolOpt.map(_.getExecutionMode)

  def getControlStatus: Option[Int] =
    lcaProtocolOpt.map(_.getControlStatus)

  protected def doUpdateStatus(execModeOpt: Option[ApplicationExecutionMode],
                               controlStatus: ApplicationStatus): Try[Unit] = {
    val theRequest = RequestUpdateStatus.create
    lcaProtocolOpt.foreach {lcaProt =>
      theRequest.setReferenceId(0)
      if (lcaProt.getClientName != "") theRequest.setApplicationName(lcaProt.getClientName)
      if (lcaProt.getClientId != 0) theRequest.setControlObjectId(lcaProt.getClientId)
      execModeOpt.foreach(execMode => theRequest.setExecutionMode(execMode))
      theRequest.setControlStatus(controlStatus.ordinal)
      if (lcaProt.getProcessId != -1) theRequest.setProcessId(lcaProt.getProcessId)
    }
    Try {
      lcaProtocolOpt.foreach {lcaProt =>
        lcaProt.setControlStatus(controlStatus.ordinal)
        execModeOpt.foreach(execMode => lcaProt.setExecutionMode(execMode))
        lcaProt.send(theRequest)
      }
    }
  }

  protected def onLcaConnectionOpened(): Unit

  protected def onLcaConnectionLost(event: ChannelClosedEvent): Unit

  protected def onLcaConnectionError(event: ChannelErrorEvent): Unit

  private class LCAChannelListener extends ChannelListener {
    def onChannelOpened(event: EventObject): Unit =
      onLcaConnectionOpened()

    def onChannelClosed(event: ChannelClosedEvent): Unit =
      onLcaConnectionLost(event)

    def onChannelError(event: ChannelErrorEvent): Unit =
      onLcaConnectionError(event)
  }

  protected def onSuspendApplication(): Unit

  protected def onChangeExecutionMode(execMode: ApplicationExecutionMode): Boolean

  private def doResponseExecutionModeChanged(referenceIdOpt: Option[Int]): Unit = {
    val theResponse = ResponseExecutionModeChanged.create
    referenceIdOpt.foreach(refId => theResponse.setReferenceId(refId))
    lcaProtocolOpt.foreach { lcaProt =>
      if (lcaProt.getProcessId > 0) theResponse.setProcessId(lcaProt.getProcessId)
      theResponse.setExecutionMode(lcaProt.getExecutionMode)
    }
    Try {
      lcaProtocolOpt.foreach(_.send(theResponse))
    }.recover(ex => logOpt.foreach(_.warning(s"Got exception: $ex for response on ExecutionModeChange request")))
  }

  private class LCAMessageHandler extends MessageHandler {
    def onMessage(message: Message): Unit = {
      if (message != null) {
        try {
          message match {
            case message: EventChangeExecutionMode =>
              val newMode = message.getExecutionMode
              val referenceIdOpt = Option(message.getReferenceId).map(_.toInt)
              try
                if (onChangeExecutionMode(newMode)) lcaProtocolOpt.foreach(_.setExecutionMode(newMode))
              finally doResponseExecutionModeChanged(referenceIdOpt)
            case _: EventSuspendApplication =>
              onSuspendApplication()
              doUpdateStatus(getExecutionMode, ApplicationStatus.Suspending)
            case _ =>
              logOpt.foreach(_.warning(s"Non-handled LCA message: $message"))
          }
        } catch {
          case NonFatal(ex) => logOpt.foreach(_.error(s"Exception during LCA message processing: $ex"))
        }
      }
    }
  }
}
