package com.svsergiy.genericapp.http

import akka.http.scaladsl.server.Route
import com.svsergiy.genericapp.database.RequestProcessorInterface
import spray.json._
import DefaultJsonProtocol._
import akka.Done
import akka.event.LoggingAdapter
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

object RouteFactory {
  //Case classes that define contract (data exchange) between http rest requests and request processor

  //Customer class that is used for customer creation and update all customer attributes
  case class Customer(phoneNumber: String, firstName: String, lastName: String, balance: Double, products: String, lcaAgent: String) {
    def toLog: String =
      s"""|
          |    Customer {
          |        PhoneNumber: $phoneNumber
          |        FirstName: $firstName
          |        LastName: $lastName
          |        Balance: ${balance.toString}
          |        Product: $products
          |        LastCalledAgent: $lcaAgent
          |    }""".stripMargin
  }

  //CustomerPhone Class for request full customer information
  case class CustomerPhone(phoneNumber: String) {
    def toLog: String =
      s"""|
          |  CustomerPhone {
          |    PhoneNumber: $phoneNumber
          |  }""".stripMargin
  }

  //Customer attributes classes:
  val CustomerAttrNames: List[String] = List("phoneNumber", "firstName", "lastName", "balance", "products", "lcaAgent")

  sealed trait CustomerAttr {
    def toLog: String
  }
  case class CustomerAttribute[T](name: String, value: T) extends CustomerAttr {
    def toLog: String =
      s"""|
          |      CustomerAttribute {
          |        name: $name
          |        value: ${value.toString}
          |      }""".stripMargin
  }

  //List of the Customer Attributes to update:
  case class CustomerAttributes(phoneNumber: String, customerAttributes: List[CustomerAttr]) {
    def toLog: String =
      s"""|
          |  CustomerAttributes {
          |    PhoneNumber: $phoneNumber {${customerAttributes.foreach(_.toLog)}
          |    }
          |  }""".stripMargin
  }

  //List of the implicits for marshalling/unmarshalling requests bodies in JSON format:
  implicit val printer: PrettyPrinter.type = PrettyPrinter
  implicit val formatCustomer: RootJsonFormat[Customer] = jsonFormat6(Customer.apply)
  implicit val formatCustomerPhone: RootJsonFormat[CustomerPhone] = jsonFormat1(CustomerPhone.apply)
  implicit object formatCustomerAttr extends RootJsonFormat[CustomerAttr] {
    def write(attr: CustomerAttr): JsValue = {
      attr match {
        case CustomerAttribute("balance", value: Double) => new JsObject(Map("balance" -> new JsNumber(value)))
        case CustomerAttribute(name, value: String) =>
          if (CustomerAttrNames.contains(name) && (name != "balance")) new JsObject(Map(name -> new JsString(value)))
          else serializationError("Customer attribute expected")
        case _ => serializationError("Customer attribute expected")
      }
    }
    def read(value: JsValue): CustomerAttribute[_ >: Double with String] = value match {
      case JsObject(obj) =>
        if (obj.size == 1) {
          obj.toList match {
            case (name, JsNumber(attrVal)) :: Nil if name == "balance" => CustomerAttribute("balance", attrVal.toDouble)
            case (name, JsString(attrVal)) :: Nil if CustomerAttrNames.contains(name) => CustomerAttribute(name, attrVal)
            case _ => deserializationError("Customer attribute expected")
          }
        } else deserializationError("Customer attribute expected")
      case _ => deserializationError("Customer attribute expected")
    }
  }
  implicit val formatCustomerAttrs: RootJsonFormat[CustomerAttributes] = jsonFormat2(CustomerAttributes.apply)
}

class RouteFactory (requestsProcessor: RequestProcessorInterface, log: LoggingAdapter) {

  import com.svsergiy.genericapp.http.RouteFactory._

  private object CustomerGet {
    private val innerCustomerGetRoute: Route = {
      entity(as[CustomerPhone]) { custPhone =>
        log.debug(s"Request: customer/get${custPhone.toLog}")
        val maybeCustomerInfo: Future[Option[Customer]] = requestsProcessor.getCustomer(custPhone)
        onComplete(maybeCustomerInfo) {
          case Success(Some(customerInfo)) =>
            log.debug(s"Response: customer/get${customerInfo.toLog}")
            complete(customerInfo)
          case Success(None) =>
            log.debug("Response: customer/get: customer not found")
            complete(StatusCodes.NotFound)
          case Failure(ex) =>
            log.error(s"Failed customer/get request due to $ex exception")
            complete(StatusCodes.InternalServerError)
        }
      }
    }

    private val innerWrongCustomerGetRequestRoute: Route = {
      complete(StatusCodes.BadRequest, "Wrong format of the customer get JSON")
    }

    val customerGetRoute: Route = {
      log.debug("Customer get request...")
      if (requestsProcessor.isNotAvailable) {
        log.error("Request Processor unavailable")
        complete(StatusCodes.ServiceUnavailable, "Requests Processor is not available")
      } else {
        concat(innerCustomerGetRoute, innerWrongCustomerGetRequestRoute)
      }
    }
  }

  private object CustomerCreate {
    private val innerCustomerCreateRoute: Route = {
      decodeRequest (
        entity(as[Customer]) { customer =>
          log.debug(s"Request: customer/create${customer.toLog}")
          val maybeDone: Future[Done] = requestsProcessor.createCustomer(customer)
          onComplete(maybeDone) {
            case Success(_) =>
              log.debug(s"Response: customer/create: Done")
              complete("Customer created")
            case Failure(ex) =>
              log.error(s"Failed customer/create request due to $ex exception")
              complete(StatusCodes.InternalServerError)
          }
        }
      )
    }

    private val innerWrongCustomerCreateRequestRoute: Route = {
      complete(StatusCodes.BadRequest, "Wrong format of the customer create JSON")
    }

    val customerCreateRoute: Route = {
      log.debug("Customer create request...")
      if (requestsProcessor.isNotAvailable) {
        log.error("Request Processor unavailable")
        complete(StatusCodes.ServiceUnavailable, "Requests Processor is not available")
      } else {
        concat(innerCustomerCreateRoute, innerWrongCustomerCreateRequestRoute)
      }
    }
  }

  private object CustomerUpdate {
    private val innerCustomerUpdateRoute: Route = {
      entity(as[CustomerAttributes]) { custAttrs =>
        log.debug(s"Request: customer/update${custAttrs.toLog}")
        val maybeDone: Future[Done] = requestsProcessor.updateCustomer(custAttrs)
        onComplete(maybeDone) {
          case Success(_) =>
            log.debug(s"Response: customer/update: Done")
            complete("Customer updated")
          case Failure(ex) =>
            log.error(s"Failed customer/update request due to $ex exception")
            complete(StatusCodes.InternalServerError)
        }
      }
    }

    private val innerWrongCustomerUpdateRequestRoute: Route = {
      complete(StatusCodes.BadRequest, "Wrong format of the customer update JSON")
    }

    val customerUpdateRoute: Route = {
      log.debug("Customer update request...")
      if (requestsProcessor.isNotAvailable) {
        log.error("Request Processor unavailable")
        complete(StatusCodes.ServiceUnavailable, "Requests Processor is not available")
      } else {
        concat(innerCustomerUpdateRoute, innerWrongCustomerUpdateRequestRoute)
      }
    }
  }

  private object WrongPath {
    val innerWrongUrlStartRoute: Route =
      complete(StatusCodes.BadRequest, "Request path prefix should be: \"customer\"")

    val innerWrongRequestName: Route =
      complete(StatusCodes.BadRequest, "Request path suffix should be one of the: \"get\", \"create\", \"update\"")
  }

  def getRoute: Route = {
    log.debug("getRoute method invocation...")
    val timeoutResponse: HttpResponse = HttpResponse(StatusCodes.EnhanceYourCalm, entity = "Good Luck!")
    concat(
      (pathPrefixTest("customer" / "") and pathPrefix("customer")) {
        concat(
          (get & path("get")) (withRequestTimeout(20.seconds, _ => timeoutResponse) (CustomerGet.customerGetRoute)),
          (post & path("create")) (withRequestTimeout(30.seconds) (CustomerCreate.customerCreateRoute)),
          (post & path("update")) (withRequestTimeout(30.seconds) (CustomerUpdate.customerUpdateRoute)),
          WrongPath.innerWrongRequestName
        )
      },
      WrongPath.innerWrongUrlStartRoute
    )
  }
}