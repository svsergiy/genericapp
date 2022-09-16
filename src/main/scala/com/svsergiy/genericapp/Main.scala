package com.svsergiy.genericapp

import akka.actor.{ActorSystem, Props}
import akka.event.Logging
import scala.concurrent.Await
import scala.concurrent.duration.Duration

object Main {
  lazy val system: ActorSystem = ActorSystem("GenesysGenericApp")

  def main(args: Array[String]): Unit = {
    val log = Logging(system, "com.svserhii.genericapp.Main")
    log.info("Starting Genesys Generic App...")
    system.actorOf(ManagementService.props(), name = "ManagementService")
    Await.ready(system.whenTerminated, Duration.Inf)
    log.info("Genesys Generic App has been stopped")
  }
}