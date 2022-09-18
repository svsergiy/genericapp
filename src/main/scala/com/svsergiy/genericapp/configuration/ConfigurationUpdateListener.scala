package com.svsergiy.genericapp.configuration

trait ConfigurationUpdateListener {
  def configurationUpdated(updatedParams: UpdatedParameters): Unit
}
