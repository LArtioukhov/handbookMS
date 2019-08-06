/*
 * Wapnee APP
 *   AdmHMicroS
 *       Copyright (c) 2019. ITC
 *       http://mlsp.gov.by/
 *              Developed by Leonid Artioukhov on 22.04.19 12:36
 */

package itc.handbook.microS.chains

import akka.actor.{ActorContext, ActorRef, Props}

import scala.concurrent.ExecutionContextExecutor

abstract class AbstractChain(context: ActorContext, prefix: String) {

  implicit val ec: ExecutionContextExecutor = context.dispatcher
  implicit val chain: AbstractChain         = this

  private[this] var _counter = 0L
  private[this] def getCount = {
    _counter += 1
    _counter
  }

  def createThis(f: Props, suffix: String): ActorRef =
    context.actorOf(f, prefix + "-" + suffix + "-" + getCount)

  def buildChain: ActorRef
}
