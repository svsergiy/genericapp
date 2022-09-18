package com.svsergiy.genericapp.genesys

import com.genesyslab.platform.applicationblocks.com.{ConfEvent, Subscription}
import com.genesyslab.platform.applicationblocks.commons.Action
import com.genesyslab.platform.configuration.protocol.types.CfgObjectType

object CfgEventsSubscription {
  case class CfgEventsSubscriptionData(tenantDbIdOpt: Option[Int], objectTypeOpt: Option[CfgObjectType], objectDbIdOpt: Option[Int]) {
    require(tenantDbIdOpt.isDefined || objectTypeOpt.isDefined)
  }

  case class CfgEventsSubscriptionResult(handler: Action[ConfEvent], subscription: Subscription)
}