package com.svsergiy.genericapp

/** Package is used for parse and store configuration data from application.conf file and from Genesys configuration
 *  database. The main class is [[com.svsergiy.genericapp.configuration.ApplicationConfiguration]]. The last one is
 *  object and contains:
 *  1. Configuration data in a form of case classes
 *  1. Methods to work with configuration data (read and parse configuration data from application.conf file and
 *  Genesys configuration database). To store configuration data the following case classes are used:
 *  - [[com.svsergiy.genericapp.configuration.GenesysConnectionParameters]]
 *  - [[com.svsergiy.genericapp.configuration.LcaConnectionParameters]]
 *  - [[com.svsergiy.genericapp.configuration.HttpServerParameters]]
 *  - [[com.svsergiy.genericapp.configuration.DatabaseParameters]]
 *  Configuration data from Genesys configuration database has higher priority than data in application.conf file.
 *  Configuration data from application.conf file is used for connection to ConfigServer. Also, in case of
 *  configuration data absence in configuration database or problems with connection to ConfigServer, configuration
 *  data from application.conf file is used */
package object configuration {}