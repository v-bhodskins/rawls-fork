package org.broadinstitute.dsde.rawls.dataaccess

import org.broadinstitute.dsde.rawls.model._
import spray.httpx.UnsuccessfulResponseException

import scala.concurrent.Future
import scala.util.Try

/**
 * @author tsharpe
 */
trait ExecutionServiceDAO {
  def submitWorkflow(wdl: String, inputs: String, options: Option[String], userInfo: UserInfo): Future[ExecutionServiceStatus]
  def validateWorkflow(wdl: String, inputs: String, userInfo: UserInfo): Future[ExecutionServiceValidation]
  def status(id: String, userInfo: UserInfo): Future[ExecutionServiceStatus]
  def callLevelMetadata(id: String, userInfo: UserInfo): Future[ExecutionMetadata]
  def outputs(id: String, userInfo: UserInfo): Future[ExecutionServiceOutputs]
  def logs(id: String, userInfo: UserInfo): Future[ExecutionServiceLogs]
  def abort(id: String, userInfo: UserInfo): Future[Try[ExecutionServiceStatus]]

  def toErrorReport(throwable: Throwable) = {
    val SOURCE = "cromwell"
    throwable match {
      case ure: UnsuccessfulResponseException =>
        ErrorReport(SOURCE, ErrorReport.message(ure), Option(ure.response.status), ErrorReport.causes(throwable), Seq.empty)
      case _ =>
        ErrorReport(SOURCE, ErrorReport.message(throwable), None, ErrorReport.causes(throwable), throwable.getStackTrace)
    }
  }
}
