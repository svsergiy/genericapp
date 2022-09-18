package com.svsergiy.genericapp.http

import akka.Done
import akka.actor.{ActorContext, ActorSystem}
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.{Http, ServerBuilder}
import akka.http.scaladsl.server.Route
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}
import com.svsergiy.genericapp.configuration.HttpServerParameters
import com.svsergiy.genericapp.database.RequestProcessorInterface

class HttpServer(context: ActorContext, val httpServerInfo: HttpServerParameters, requestProcessor: RequestProcessorInterface) {
  private implicit val system: ActorSystem = context.system
  private implicit val executionContext: ExecutionContextExecutor = system.getDispatcher

  private val log: LoggingAdapter = Logging(system, this.getClass.getName)
  private val route: Route = new RouteFactory(requestProcessor, log).getRoute
  private val serverBuilder: ServerBuilder = Http().newServerAt(httpServerInfo.host, httpServerInfo.port)
  private var bindingFutureOpt: Option[Future[Http.ServerBinding]] = None

  def startServer: Future[Http.ServerBinding] = {
    if (bindingFutureOpt.isDefined) {
      bindingFutureOpt.foreach {bindFuture =>
        bindFuture.value match {
          case Some(Failure(_)) => bindingFutureOpt = None
          case _ => ()
        }
      }
    }
    if (bindingFutureOpt.isEmpty) {
      val bindingFuture = serverBuilder.bind(route)
      bindingFutureOpt = Some(bindingFuture)
      bindingFuture.onComplete{
        case Success(_) =>
          log.info(s"Server successfully started on host: ${httpServerInfo.host} and port: ${httpServerInfo.port}")
        case Failure(exception) => log.error(s"Failed to start Server on host: ${httpServerInfo.host} and port: ${httpServerInfo.port}, reason: $exception")
      }
    } else {
      log.info(s"Server already started on host: ${httpServerInfo.host} and port: ${httpServerInfo.port}")
    }
    bindingFutureOpt.get
  }

  def stopServer: Future[Done] = {
    if (bindingFutureOpt.isDefined) {
      val bindingFuture = bindingFutureOpt.get
      val futureDone = bindingFuture.flatMap(_.unbind())
      futureDone.onComplete {
        case Success(_) =>
          log.info("Server successfully stopped")
          bindingFutureOpt = None
        case Failure(exception) => log.error(s"Failed to stop Server, reason: $exception")
      }
      futureDone
    } else {
      log.info(s"Server already stopped")
      Future.successful(Done)
    }
  }

  def isStarted: Boolean =
    bindingFutureOpt.exists ( _.value match {
        case Some(Success(_)) => true
        case _ => false
      }
    )
}