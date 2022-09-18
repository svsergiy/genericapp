package com.svsergiy.genericapp.configuration

case class DatabaseParameters(
  connectionPool: String,
  dataSourceClass: String,
  properties: DatabaseProperties,
  numThreads: Int,
)

case class DatabaseProperties(serverName: String, portNumber: Int, databaseName: String, user: String, password: String)