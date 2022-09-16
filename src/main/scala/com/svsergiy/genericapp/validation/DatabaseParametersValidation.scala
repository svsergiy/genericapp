package com.svsergiy.genericapp.validation

case object DbConfigIsNotDefined  extends ValidationError {
  def errorMessage: String = "db section is not defined in application.conf file"
}

case object DbPropertiesConfigIsNotDefined  extends ValidationError {
  def errorMessage: String = "properties section is not defined in db section of the application.conf file"
}

case object DatabaseNameIsNotSpecified extends ValidationError {
  def errorMessage: String = "databaseName is not specified in the db.properties section of the application.conf file"
}

case object DatabaseNameIsNotSpecifiedInConfigDB extends ValidationError {
  def errorMessage: String = "databaseName is not specified in the Configuration database"
}

case object UserIsNotSpecified extends ValidationError {
  def errorMessage: String = "user is not specified in the db.properties section of the application.conf file"
}

case object UserIsNotSpecifiedInConfigDB extends ValidationError {
  def errorMessage: String = "user is not specified in the Configuration database"
}

case object PasswordIsNotSpecified extends ValidationError {
  def errorMessage: String = "password is not specified in the db.properties section of the application.conf file"
}

case object PasswordIsNotSpecifiedInConfigDB extends ValidationError {
  def errorMessage: String = "password is not specified in the Configuration database"
}