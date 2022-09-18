package com.svsergiy.genericapp.database

import akka.Done
import scala.concurrent.Future
import scala.util.Try
import com.svsergiy.genericapp.http.RouteFactory.{Customer, CustomerAttributes, CustomerPhone}

trait RequestProcessorInterface {
  def initialize(): Try[Unit]
  def isNotAvailable: Boolean
  def getCustomer(customerPhone: CustomerPhone): Future[Option[Customer]]
  def createCustomer(customer: Customer): Future[Done]
  def updateCustomer(customerAttrs: CustomerAttributes): Future[Done]
  def release(): Unit
}
