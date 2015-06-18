package org.broadinstitute.dsde.rawls


import java.io.File

import akka.actor.ActorSystem
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import com.wordnik.swagger.model.ApiInfo
import org.broadinstitute.dsde.rawls.dataaccess._
import org.broadinstitute.dsde.rawls.webservice._
import org.broadinstitute.dsde.rawls.workspace.WorkspaceService
import spray.can.Http

import scala.concurrent.duration._
import scala.reflect.runtime.universe._
import scala.util.{Failure, Success}

object Boot extends App {

  private def startup(): Unit = {
    val conf = ConfigFactory.parseFile(new File("/etc/rawls.conf"))

    // we need an ActorSystem to host our application in
    implicit val system = ActorSystem("rawls")

    val swaggerConfig = conf.getConfig("swagger")
    val swaggerService = new SwaggerService(
      swaggerConfig.getString("apiVersion"),
      swaggerConfig.getString("baseUrl"),
      swaggerConfig.getString("apiDocs"),
      swaggerConfig.getString("swaggerVersion"),
      Seq(typeOf[WorkspaceApiService], typeOf[EntityApiService], typeOf[MethodConfigApiService]),
      Option(new ApiInfo(
        swaggerConfig.getString("info"),
        swaggerConfig.getString("description"),
        swaggerConfig.getString("termsOfServiceUrl"),
        swaggerConfig.getString("contact"),
        swaggerConfig.getString("license"),
        swaggerConfig.getString("licenseUrl"))
      ))

    val orientConfig = conf.getConfig("orientdb")
    val dbUrl = s"remote:${orientConfig.getString("server")}/${orientConfig.getString("dbName")}"
    val dataSource = DataSource(dbUrl, orientConfig.getString("rootUser"), orientConfig.getString("rootPassword"), 0, 30)

    system.registerOnTermination {
      dataSource.shutdown()
    }

    val methodRepoConfig = conf.getConfig("methodrepo")
    val service = system.actorOf(RawlsApiServiceActor.props(swaggerService, WorkspaceService.constructor(dataSource, new GraphWorkspaceDAO(), new GraphEntityDAO(), new GraphMethodConfigurationDAO(), methodRepoConfig.getString("server"))), "rawls-service")

    implicit val timeout = Timeout(5.seconds)
    // start a new HTTP server on port 8080 with our service actor as the handler
    import scala.concurrent.ExecutionContext.Implicits.global
    (IO(Http) ? Http.Bind(service, interface = "0.0.0.0", port = 8080)).onComplete {
      case Success(Http.CommandFailed(failure)) =>
        system.log.error("could not bind to port: " + failure.toString)
        system.shutdown()
      case Failure(t) =>
        system.log.error(t, "could not bind to port")
        system.shutdown()
      case _ =>
    }
  }

  startup()
}
