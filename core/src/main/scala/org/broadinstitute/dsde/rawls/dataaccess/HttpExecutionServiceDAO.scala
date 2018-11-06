package org.broadinstitute.dsde.rawls.dataaccess

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.{Multipart, RequestEntity, Uri}
import akka.http.scaladsl.server.PathMatchers.Segment
import akka.http.scaladsl.server.directives.PathDirectives._
import akka.stream.Materializer
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.rawls.metrics.RawlsExpansion._
import org.broadinstitute.dsde.rawls.metrics.{Expansion, InstrumentedRetry, RawlsExpansion, RawlsInstrumented}
import org.broadinstitute.dsde.rawls.model.ExecutionJsonSupport._
import org.broadinstitute.dsde.rawls.model.StatusJsonSupport._
import org.broadinstitute.dsde.rawls.model._
import org.broadinstitute.dsde.rawls.util.{FutureSupport, HttpClientUtilsGzipInstrumented}
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * @author tsharpe
 */
class HttpExecutionServiceDAO(executionServiceURL: String, override val workbenchMetricBaseName: String)(implicit val system: ActorSystem, val materializer: Materializer, val executionContext: ExecutionContext) extends ExecutionServiceDAO with DsdeHttpDAO with InstrumentedRetry with FutureSupport with LazyLogging with RawlsInstrumented {
  import system.dispatcher

  private implicit lazy val baseMetricBuilder =
    ExpandedMetricBuilder.expand(SubsystemMetricKey, Subsystems.Cromwell)

  override val http = Http(system)
  override val httpClientUtils = HttpClientUtilsGzipInstrumented()

  // Strip out workflow IDs from metrics by providing a redactedUriExpansion
  override protected val UriExpansion: Expansion[Uri] = RawlsExpansion.redactedUriExpansion(
    Seq((Slash ~ "api").? / "workflows" / "v1" / Segment / Neutral)
  )

  override def submitWorkflows(wdl: String, inputs: Seq[String], options: Option[String], userInfo: UserInfo): Future[Seq[Either[ExecutionServiceStatus, ExecutionServiceFailure]]] = {
    val url = executionServiceURL+"/api/workflows/v1/batch"

    val bodyParts = Seq(Multipart.FormData.BodyPart("workflowSource", wdl),
      Multipart.FormData.BodyPart("workflowInputs", inputs.mkString("[", ",", "]"))
    ) ++ options.map(Multipart.FormData.BodyPart("workflowOptions", _))

    val formData = Multipart.FormData(bodyParts:_*)

    pipeline[Seq[Either[ExecutionServiceStatus, ExecutionServiceFailure]]](userInfo) apply (Post(url, Marshal(formData).to[RequestEntity]))
  }

  override def status(id: String, userInfo: UserInfo): Future[ExecutionServiceStatus] = {
    val url = executionServiceURL + s"/api/workflows/v1/${id}/status"
    retry(when500) { () => pipeline[ExecutionServiceStatus](userInfo) apply Get(url) }
  }

  // break out uri generation into a separate method so it's easily unit-testable
  def getExecutionServiceMetadataUri(id: String, metadataParams: MetadataParams): Uri = {
    val params = metadataParams.includeKeys.map(("includeKey", _)) ++
      metadataParams.excludeKeys.map(("excludeKey", _)) +
      (("expandSubWorkflows", metadataParams.expandSubWorkflows.toString))

    Uri(executionServiceURL + s"/api/workflows/v1/${id}/metadata").withQuery(Query(params.toList:_*))
  }

  override def callLevelMetadata(id: String, metadataParams: MetadataParams, userInfo: UserInfo): Future[JsObject] = {
    retry(when500) { () => pipeline[JsObject](userInfo) apply Get(getExecutionServiceMetadataUri(id, metadataParams)) }
  }

  override def outputs(id: String, userInfo: UserInfo): Future[ExecutionServiceOutputs] = {
    val url = executionServiceURL + s"/api/workflows/v1/${id}/outputs"
    retry(when500) { () => pipeline[ExecutionServiceOutputs](userInfo) apply Get(url) }
  }

  override def logs(id: String, userInfo: UserInfo): Future[ExecutionServiceLogs] = {
    val url = executionServiceURL + s"/api/workflows/v1/${id}/logs"
    retry(when500) { () => pipeline[ExecutionServiceLogs](userInfo) apply Get(url) }
  }

  override def abort(id: String, userInfo: UserInfo): Future[Try[ExecutionServiceStatus]] = {
    val url = executionServiceURL + s"/api/workflows/v1/${id}/abort"
    retry(when500) { () => toFutureTry(pipeline[ExecutionServiceStatus](userInfo) apply Post(url)) }
  }

  override def getLabels(id: String, userInfo: UserInfo): Future[ExecutionServiceLabelResponse] = {
    val url = executionServiceURL + s"/api/workflows/v1/${id}/labels"
    retry(when500) { () => pipeline[ExecutionServiceLabelResponse](userInfo) apply Get(url) }
  }

  override def patchLabels(id: String, userInfo: UserInfo, labels: Map[String, String]): Future[ExecutionServiceLabelResponse] = {
    val url = executionServiceURL + s"/api/workflows/v1/${id}/labels"
    retry(when500) { () => pipeline[ExecutionServiceLabelResponse](userInfo) apply Patch(url, labels) }
  }

  override def version: Future[ExecutionServiceVersion] = {
    val url = executionServiceURL + s"/engine/v1/version"
    retry(when500) { () => httpClientUtils.executeRequestUnmarshalResponse[ExecutionServiceVersion](http, Get(url)) }
  }

  override def getStatus(): Future[Map[String, SubsystemStatus]] = {
    val url = executionServiceURL + s"/engine/v1/status"
    // we're explicitly not retrying on 500 here
    httpClientUtils.executeRequestUnmarshalResponse[Map[String, SubsystemStatus]](http, Get(url))
  }
}
