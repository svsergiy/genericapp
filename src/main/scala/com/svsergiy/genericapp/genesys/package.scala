package com.svsergiy.genericapp

/** This package is used for integration with Genesys Software.
 *  Main classes are realized as Akka Classic actors, that manage connections to appropriate
 *  Genesys components. Thus [[com.svsergiy.genericapp.genesys.ConfigServerActor]] class is used to
 *  connect to Genesys ConfigServer. After successful connection configuration data is retrieved from
 *  configuration database, parsed and stored into [[com.svsergiy.genericapp.configuration.ApplicationConfiguration]]
 *  singleton object for further use by [[com.svsergiy.genericapp.ManagementService]] class for initializing and
 *  starting other system modules. [[com.svsergiy.genericapp.genesys.LocalControlAgentActor]] class is used to
 *  connect to Genesys LCA component. This actor transfers application status to Genesys Management Layer and
 *  process requests from Genesys to stop (graceful stop) application
 */
package object genesys {}