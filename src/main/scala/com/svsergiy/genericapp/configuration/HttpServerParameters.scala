package com.svsergiy.genericapp.configuration

case class HttpServerParameters(host: String, port: Int) {
  override def toString: String =
    s"HttpServerParameters(host = $host, port = ${port.toString})"
}