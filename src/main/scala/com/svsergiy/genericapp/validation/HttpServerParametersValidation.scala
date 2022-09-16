package com.svsergiy.genericapp.validation

case object AppConfigIsNotDefined extends ValidationError {
  def errorMessage: String = "app section is not defined in application.conf file"
}

case object AppPortIsNotSpecified extends ValidationError {
  def errorMessage: String = "Application port is not specified in the app section of the application.conf file"
}

case object AppPortIsNotSpecifiedInConfigDB extends ValidationError {
  def errorMessage: String = "Application port is not specified in the Configuration database"
}