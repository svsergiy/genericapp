package com.svsergiy.genericapp.validation

case object GenesysConfigIsNotDefined extends ValidationError {
  def errorMessage: String = "genesys configuration is not defined in application.conf file"
}

case object ConfServConfigIsNotDefined extends ValidationError {
  def errorMessage: String = "confserver configuration is not defined in the genesys section of the application.conf file"
}

case object ClientNameIsNotSpecified extends ValidationError {
  def errorMessage: String = "client-name is not specified in the genesys.confserv section of the application.conf file"
}

case object CfgAppTypeIsNotSpecified extends ValidationError {
  def errorMessage: String = "cfg-app-type is not specified in the genesys.confserv section of the application.conf file"
}

case object PrimaryConfServHostIsNotSpecified extends ValidationError {
  def errorMessage: String = "endpoints[0].host (Primary ConfigServer host) is not specified in the genesys.confserv section of the application.conf file"
}

case object PrimaryConfServPortIsNotSpecified extends ValidationError {
  def errorMessage: String = "endpoints[0].port (Primary ConfigServer port) is not specified in the genesys.confserv section of the application.conf file"
}
