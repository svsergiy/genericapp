package com.svsergiy.genericapp.validation

case object LcaPortIsZero extends ValidationError {
  def errorMessage: String = "Lca Port is set to 0 in configuration"
}

case object LcaPortIsNotDefined extends ValidationError {
  def errorMessage: String = "Lca Port is not defined in configuration or problem with reading information from configuration"
}

case object AppDbIdIsNotDefined extends ValidationError {
  def errorMessage: String = "Application DB Id is not defined in configuration or problem with reading information from configuration"
}

case object AppNameIsNotDefined extends ValidationError {
  def errorMessage: String = "Application Name is not defined in configuration or problem with reading information from configuration"
}

case object AppTypeIsNotDefined extends ValidationError {
  def errorMessage: String = "Application Type is not defined in configuration or problem with reading information from configuration"
}
