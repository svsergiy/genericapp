package com.svsergiy.genericapp.database

import akka.Done
import com.svsergiy.genericapp.configuration.DatabaseParameters
import com.svsergiy.genericapp.http.RouteFactory.{Customer, CustomerAttributes, CustomerPhone}
import slick.jdbc.JdbcBackend.Database
import slick.jdbc.H2Profile.api._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Try}

object RequestProcessorDB {
  class Customers(tag: Tag) extends Table[Customer](tag, "CUSTOMERS") {
    def phoneNumber = column[String]("PHONENUMBER", O.PrimaryKey)
    def firstName = column[String]("FIRSTNAME", O.Default(""))
    def lastName = column[String]("LASTNAME", O.Default(""))
    def balance = column[Double]("BALANCE", O.Default(0.0))
    def products = column[String]("PRODUCTS", O.Default(""))
    def lcaAgent = column[String]("LCAAGENT", O.Default(""))
    def * = (phoneNumber, firstName, lastName, balance, products, lcaAgent) <> ((Customer.apply _).tupled, Customer.unapply)
  }
}

class RequestProcessorDB extends RequestProcessorInterface {
  import RequestProcessorDB._

  private var dbOpt: Option[Database] = None
  private var dbParametersOpt: Option[DatabaseParameters] = None
  private var serviceUnavailable = true

  private val customers = TableQuery[Customers]

  def isNotAvailable: Boolean = serviceUnavailable

  def setDbParameters(dbParams: DatabaseParameters): Unit =
    dbParametersOpt = Some(dbParams)

  def initialize(): Try[Unit] = {
    if (dbParametersOpt.isDefined) {
      Try {
        Database.forConfig("db")
      }.map {db =>
        dbOpt = Some(db)
        serviceUnavailable = false
      }
    } else {
      Failure(new Exception("Configuration is not defined"))
    }
  }

  //OptionT can be used
  def getCustomer(customerPhone: CustomerPhone): Future[Option[Customer]] = {
    if (dbOpt.isDefined) {
      val queryCustomer = customers.filter(_.phoneNumber === customerPhone.phoneNumber)//.take(1)
      dbOpt.get.run(queryCustomer.result).map(_.headOption)
    } else {
      Future.failed(new Exception("DB is not defined"))
    }
  }

  def createCustomer(customer: Customer): Future[Done] = {
    if (dbOpt.isDefined) {
      val insertCustomer = DBIO.seq(customers += customer)
      dbOpt.get.run(insertCustomer).map(_ => Done)
    } else {
      Future.failed(new Exception("DB is not defined"))
    }
  }

  def updateCustomer(custAttrs: CustomerAttributes): Future[Done] = {
    if (dbOpt.isDefined) {
/*      val (phoneNum, attrs) = (custAttrs.phoneNumber, custAttrs.customerAttributes)
      val mappedAttrs = attrs.map {
        case attr: CustomerAttribute[String] => (attr.name, attr.value)
        case attr: CustomerAttribute[Double] => (attr.name, attr.value.toString)
      }.toMap.withDefaultValue("")
    val updateCustomer = customers.filter(_.phoneNumber.toString() == custAttrs.phoneNumber).take(1) */
      Future.successful(Done)
    } else {
      Future.failed(new Exception("DB is not defined"))
    }
  }

  def release(): Unit = {
    dbOpt.foreach(_.close())
    serviceUnavailable = true
  }
}
