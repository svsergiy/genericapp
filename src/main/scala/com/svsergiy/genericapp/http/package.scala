package com.svsergiy.genericapp

/** Package used for starting Http Server on configured port and processing HTTP Rest requests related with
 *  customer information. Class [[com.svsergiy.genericapp.http.RouteFactory]] is used to create 'Route' for
 *  processing http requests from clients, passing information to
 *  [[com.svsergiy.genericapp.database.RequestProcessorDB]] and getting result from it, transferring result
 *  to clients. Class [[com.svsergiy.genericapp.http.HttpServer]] is used for creating and managing
 *  Http.ServerBinding
 */
package object http {}