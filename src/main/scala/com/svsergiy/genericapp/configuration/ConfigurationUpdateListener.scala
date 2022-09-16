package com.svsergiy.genericapp.configuration

import com.svsergiy.genericapp.ManagementService.UpdatedParameters

trait ConfigurationUpdateListener {
  def configurationUpdated(updatedParams: UpdatedParameters): Unit
}
