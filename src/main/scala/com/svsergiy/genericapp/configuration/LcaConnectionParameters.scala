package com.svsergiy.genericapp.configuration

case class LcaConnectionParameters(lcaPort: Int, appType: Int, appName: String, appDbId: Int, reconnectTimeout: Int) {
  override def toString: String =
    s"LcaConnectionParameters(lcaPort = ${lcaPort.toString}, appType = ${appType.toString}, appName = $appName," +
      s"appDbId = ${appDbId.toString}, reconnectTimeout = ${reconnectTimeout.toString})"
}