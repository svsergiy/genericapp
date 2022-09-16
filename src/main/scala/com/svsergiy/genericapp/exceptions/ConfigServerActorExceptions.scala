package com.svsergiy.genericapp.exceptions

object ConfigServerActorExceptions {
  //  final case class ConfigServiceNotDefinedException(private val message: String = "", private val cause: Throwable = None.orNull) extends Exception(message, cause)
  final class ConfigServiceNotDefinedException(message: String) extends Exception(message) {
    def this(message: String, cause: Throwable) = {
      this(message)
      initCause(cause)
    }

    def this(cause: Throwable) = {
      this(Option(cause).map(_.toString).orNull, cause)
    }

    def this() = {
      this(null: String)
    }
  }

//  final case class ApplicationNotFoundException(private val message: String = "", private val cause: Throwable = None.orNull) extends Exception(message, cause)
  final class ApplicationNotFoundException(message: String) extends Exception(message) {
    def this(message: String, cause: Throwable) = {
      this(message)
      initCause(cause)
    }

    def this(cause: Throwable) = {
      this(Option(cause).map(_.toString).orNull, cause)
    }

    def this() = {
      this(null: String)
    }
  }

  final class HostNotFoundException(message: String) extends Exception(message) {
    def this(message: String, cause: Throwable) = {
      this(message)
      initCause(cause)
    }

    def this(cause: Throwable) = {
      this(Option(cause).map(_.toString).orNull, cause)
    }

    def this() = {
      this(null: String)
    }
  }

}