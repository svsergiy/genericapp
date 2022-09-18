package com.svsergiy.genericapp.configuration

case class GenesysConnectionParameters(
  clientName: String,
  stringsEncoding: String,
  configSrv: ConfigSrvConnection,
  addp: Addp,
  warmStandby: WarmStandbyParameters,
  connectionTimeout: Int,
)

case class ConfigSrvConnection(
  clientName: String,
  cfgAppType: String,
  prmConfigSrv: ConfigSrvEndpoint,
  bkpConfigSrv: ConfigSrvEndpoint,
)

case class Addp(enabled: Boolean, clientTimeout: Int, serverTimeout: Int, traceMode: String)

case class WarmStandbyParameters(timeout: Int, backupDelay: Int, retryDelay: Int, reconnectionRandomDelayRange: Double)

case class ConfigSrvEndpoint(name: String, host: String, port: Int)