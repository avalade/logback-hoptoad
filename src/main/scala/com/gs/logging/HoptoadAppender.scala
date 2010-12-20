/*
Copyright (c) $today.year Aaron Valade <adv@alum.mit.edu>

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.gs.logging

import reflect.BeanProperty
import ch.qos.logback.classic.spi.ILoggingEvent
import org.apache.http.client.methods.HttpPut
import org.apache.http.entity.StringEntity
import org.apache.http.params.HttpProtocolParams
import ch.qos.logback.core.{Layout, LayoutBase, AppenderBase}
import org.slf4j.LoggerFactory
import dispatch.{Logger, :/, Threads, Http, Request => HR}

/**
 * This is a small Logback Appender which can be used to log to the
 * Hoptoad API for error gathering.
 *
 * @author Aaron Valade <adv@alum.mit.edu>
 */
class HoptoadAppender extends AppenderBase[ILoggingEvent] {
  @BeanProperty var apiKey: String = null

  val http = new Http with Threads with Logger {
    val LOG = LoggerFactory.getLogger("com.gs.logging.HoptoadAppender")
    def info(msg: String, items: Any*) = LOG.info(msg, items)
  }

  var layout: Layout[ILoggingEvent] = null

  override def start = {
    if (apiKey == null) throw new IllegalStateException("HoptoadAppender cannot function without an API key")
    layout = new HoptoadLayout(apiKey)
    super.start
  }

  override def stop = {
    http.shutdown
    super.stop
  }

  class HoptoadRequest(r: HR) {
    def <<< (eventObject: ILoggingEvent) = r next {
      val m = new HttpPut
      val entity = new StringEntity(layout.doLayout(eventObject), r.defaultCharset)
      entity.setContentType(layout.getContentType)
      m setEntity entity
      HttpProtocolParams.setUseExpectContinue(m.getParams, false)
      HR.mimic(m)_
    }
  }

  implicit def request2hoptoad(r: HR) = new HoptoadRequest(r)

  def append(eventObject: ILoggingEvent) = {
    if (isStarted)
      http on_error {
        case e => System.err.println("Unable to complete Hoptoad request: %s".format(e.getMessage))
      } future(:/("hoptoadapp.com") / "notifier_api" / "v2" / "notices" <<< eventObject >|)
  }
}

object HoptoadLayout {
  val REQUEST_URL = "REQUEST_URL"
  val ENVIRONMENT_NAME = "ENVIRONMENT_NAME"
  val PROJECT_ROOT = "PROJECT_ROOT"
  val REQUEST_COMPONENT = "REQUEST_COMPONENT"
  val REQUEST_ACTION = "REQUEST_ACTION"
  val REQUEST_PARAMS = "REQUEST_PARAMS"
  val SESSION_PARAMS = "SESSION_PARAMS"
  val CGI_PARAMS = "CGI_PARAMS"
}

/**
 * This specifies a LOGBack layout which can be used to create valid POST bodies for
 * the Hoptoad service.
 * @author Aaron Valade <adv@alum.mit.edu>
 */
class HoptoadLayout(@BeanProperty apiKey: String = null) extends LayoutBase[ILoggingEvent] {
  override def start = {
    if (apiKey == null) throw new IllegalArgumentException("HoptoadLayout requires an apiKey to be set")
    super.start
  }

  def doLayout(e: ILoggingEvent) = {
    val environmentName = for {
      mdc <- Option(e.getMdc)
      name <- Option(mdc.get(HoptoadLayout.ENVIRONMENT_NAME))
    } yield name.toString
    val clazz = for {
      proxy <- Option(e.getThrowableProxy)
      name <- Option(proxy.getClassName)
    } yield name

    val message = e.getFormattedMessage
    val backtraces = e.getCallerData.map(x => new Backtrace(x.getFileName, x.getLineNumber, x.getMethodName))
    val request = for {
      mdc <- Option(e.getMdc)
      r <- Option(mdc.get(HoptoadLayout.REQUEST_URL))
      c <- Option(mdc.get(HoptoadLayout.REQUEST_COMPONENT))
      action = Option(mdc.get(HoptoadLayout.REQUEST_ACTION))
      params = Option(mdc.get(HoptoadLayout.REQUEST_PARAMS)).map(Var.split_decode).getOrElse(Nil)
      sessions = Option(mdc.get(HoptoadLayout.SESSION_PARAMS)).map(Var.split_decode).getOrElse(Nil)
      cgi_data = Option(mdc.get(HoptoadLayout.CGI_PARAMS)).map(Var.split_decode).getOrElse(Nil)
    } yield new Request(r, c, action, params, sessions, cgi_data)

    val project_root = for {
      mdc <- Option(e.getMdc)
      pr <- Option(mdc.get(HoptoadLayout.PROJECT_ROOT))
    } yield pr

    val notice = new HoptoadNotice(
      apiKey,
      environmentName.getOrElse("N/A"),
      clazz.getOrElse("N/A"),
      message,
      backtraces,
      request,
      project_root)
    notice.toXml.toString
  }

  override def getContentType = "text/xml"
}

class Backtrace(file: String, number: Int, methodName: String) {
  def toXml = <line method={methodName} file={file} number={number.toString}></line>
}

object Var {
  val split_decode: (String => List[Var]) = {
    case null => Nil
    case params => List[Var]() ++ params.trim.split('&').map { nvp =>
      { nvp split "=" map Http.-% } match {
        case Array(name) => new Var(name, "")
        case Array(name, value) => new Var(name, value)
      }
    }
  }
}

class Var(key: String, value: String) {
  def toXml = <val key={key}>{value}</val>
}

class Request(url: String, component: String, action: Option[String] = None,
              params: List[Var] = Nil, sessions: List[Var] = Nil,
              cgi_data: List[Var] = Nil) {

  def actionXml = action.map(x => <action>{x}</action>)
  def paramsXml = <params>{params.map(_.toXml)}</params>
  def sessionsXml = <sessions>{sessions.map(_.toXml)}</sessions>
  def cgi_dataXml = <cgi-data>{cgi_data.map(_.toXml)}</cgi-data>

  def toXml =
    <request>
      <url>{url}</url>
      <component>{component}</component>
      {if (action.isDefined) actionXml}
      {if (params.length > 0) paramsXml}
      {if (sessions.length > 0) sessionsXml}
      {if (cgi_data.length > 0) cgi_dataXml}
    </request>
}

class HoptoadNotice(apiKey: String,
                    environmentName: String,
                    clazz: String,
                    message: String,
                    backtraces: Seq[Backtrace],
                    request: Option[Request] = None,
                    project_root: Option[String] = None) {

  def toXml =
    <notice version="2.0">
      <api-key>{apiKey}</api-key>
      <notifier>
        <name>Hoptoad Logback Notifier</name>
        <version>1.0-SNAPSHOT</version>
        <url>http://github.com/avalade/slf4j-hoptoad</url>
      </notifier>
      <error>
        <class>{clazz}</class>
        <message>{message}</message>
        <backtrace>{backtraces.map(_.toXml)}</backtrace>
      </error>
      {request.map(_.toXml).getOrElse(scala.xml.Comment("No request was set"))}
      <server-environment>
        {project_root.map(x => <project-root>{x}</project-root>).getOrElse(scala.xml.Comment("No project root was set"))}
        <environment-name>{environmentName}</environment-name>
      </server-environment>
    </notice>

}
