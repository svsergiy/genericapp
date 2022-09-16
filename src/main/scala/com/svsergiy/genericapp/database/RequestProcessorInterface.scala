package com.svsergiy.genericapp.database

import akka.Done
import com.svsergiy.genericapp.http.RouteFactory.{Customer, CustomerAttributes, CustomerPhone}

import scala.concurrent.Future
import scala.util.Try

trait RequestProcessorInterface {
  def initialize(): Try[Unit]
  def isNotAvailable: Boolean
  def getCustomer(customerPhone: CustomerPhone): Future[Option[Customer]]
  def createCustomer(customer: Customer): Future[Done]
  def updateCustomer(custAttrs: CustomerAttributes): Future[Done]
  def release(): Unit
}
