package com.svsergiy.genericapp.http

import akka.Done
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.{Success, Try}
import com.svsergiy.genericapp.database.RequestProcessorInterface
import com.svsergiy.genericapp.http.RouteFactory.{Customer, CustomerAttributes, CustomerPhone}

trait RouteFactorySuite extends AnyWordSpec with Matchers with ScalatestRouteTest {

  private class RequestProcessor extends RequestProcessorInterface {
    private var serviceUnavailable = true
    private val db = mutable.Map.empty[String, Customer]
    def isNotAvailable: Boolean = serviceUnavailable

    def initialize(): Try[Unit] = {
      serviceUnavailable = false
      Success(())
    }

    def release(): Unit = {
      serviceUnavailable = true
      Success(())
    }

    def createCustomer(customer: Customer): Future[Done] = {
      db += (customer.phoneNumber -> customer)
      Future.successful(Done)
    }

    def getCustomer(customerPhone: CustomerPhone): Future[Option[Customer]] =
      Future.successful(Try(db(customerPhone.phoneNumber)).toOption)

    def updateCustomer(customerAttrs: CustomerAttributes): Future[Done] = Future.successful(Done)
  }
  private val requestProcessor = new RequestProcessor
  private val route: Route = new RouteFactory(requestProcessor, None).getRoute
  requestProcessor.initialize()

  "The service" should {
    "Return ServiceUnavailable for Get when requestProcessor isn't initialized" in {
      requestProcessor.release()
      Get("/customer/get") ~> route ~> check {
        status shouldEqual StatusCodes.ServiceUnavailable
      }
    }

    "Return BedRequest in case of wrong start url 'cust' instead of 'customer" in {
      Get("/cust/get") ~> route ~> check {
        status shouldEqual StatusCodes.BadRequest
      }
    }

  }
}