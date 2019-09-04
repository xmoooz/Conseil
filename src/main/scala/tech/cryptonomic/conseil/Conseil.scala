package tech.cryptonomic.conseil

import java.util.UUID

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directive
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import cats.effect.{ContextShift, IO}
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import com.typesafe.scalalogging.LazyLogging
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import tech.cryptonomic.conseil.config.{ConseilAppConfig, Security}
import tech.cryptonomic.conseil.directives.EnableCORSDirectives
import tech.cryptonomic.conseil.io.MainOutputs.ConseilOutput
import tech.cryptonomic.conseil.metadata.{AttributeValuesCacheConfiguration, MetadataService, UnitTransformation}
import tech.cryptonomic.conseil.routes._
import tech.cryptonomic.conseil.tezos.{ApiOperations, MetadataCaching, TezosPlatformDiscoveryOperations}
import tech.cryptonomic.conseil.util.RouteUtil

import scala.concurrent.ExecutionContextExecutor
import scala.util.{Failure, Success}

object Conseil
    extends App
    with LazyLogging
    with EnableCORSDirectives
    with ConseilAppConfig
    with FailFastCirceSupport
    with ConseilOutput {

  loadApplicationConfiguration(args) match {
    case Right((server, platforms, securityApi, verbose, metadataOverrides, nautilusCloud)) =>
      implicit val system: ActorSystem = ActorSystem("conseil-system")
      implicit val materializer: ActorMaterializer = ActorMaterializer()
      implicit val executionContext: ExecutionContextExecutor = system.dispatcher
      nautilusCloud.foreach { ncc =>
        system.scheduler.schedule(ncc.delay, ncc.interval)(Security.updateKeys(ncc))
      }

      val validateApiKey: Directive[Tuple1[String]] = headerValueByName("apikey").tflatMap[Tuple1[String]] {
        case Tuple1(apiKey) =>
          onComplete(securityApi.validateApiKey(apiKey)).flatMap {
            case Success(true) => provide(apiKey)
            case _ => complete((Unauthorized, "Incorrect API key"))
          }
        case _ =>
          complete((Unauthorized, "Missing API key"))
      }
      val tezosDispatcher = system.dispatchers.lookup("akka.tezos-dispatcher")

      // This part is a temporary middle ground between current implementation and moving code to use IO
      implicit val contextShift: ContextShift[IO] = IO.contextShift(executionContext)
      val metadataCaching = MetadataCaching.empty[IO].unsafeRunSync()

      lazy val transformation = new UnitTransformation(metadataOverrides)
      lazy val cacheOverrides = new AttributeValuesCacheConfiguration(metadataOverrides)
      lazy val routeUtil = new RouteUtil()


      lazy val tezosPlatformDiscoveryOperations =
        TezosPlatformDiscoveryOperations(ApiOperations, metadataCaching, cacheOverrides, server.cacheTTL)(
          executionContext,
          contextShift
        )

      tezosPlatformDiscoveryOperations.init().onComplete {
        case Failure(exception) => logger.error("Pre-caching metadata failed", exception)
        case Success(_) => logger.info("Pre-caching successful!")
      }

      tezosPlatformDiscoveryOperations.initAttributesCache.onComplete {
        case Failure(exception) => logger.error("Pre-caching attributes failed", exception)
        case Success(_) => logger.info("Pre-caching attributes successful!")
      }

      // this val is not lazy to force to fetch metadata and trigger logging at the start of the application
      val metadataService =
        new MetadataService(platforms, transformation, cacheOverrides, tezosPlatformDiscoveryOperations)
      lazy val platformDiscovery = PlatformDiscovery(metadataService)(tezosDispatcher)
      lazy val data = Data(platforms, metadataService, server)(tezosDispatcher)


      import routeUtil._
      val route = cors() {
        enableCORS {
          implicit val correlationId: UUID = UUID.randomUUID()
          handleExceptions(loggingExceptionHandler) {
            extractClientIP { ip =>
              recordResponseValues(ip)(materializer, correlationId) {
                timeoutHandler {
                  validateApiKey { _ =>
                    logRequest("Conseil", Logging.DebugLevel) {
                      AppInfo.route
                    } ~
                      logRequest("Metadata Route", Logging.DebugLevel) {
                        platformDiscovery.route
                      } ~
                      logRequest("Data Route", Logging.DebugLevel) {
                        data.getRoute ~ data.postRoute
                      }
                  } ~
                    options {
                      // Support for CORS pre-flight checks.
                      complete("Supported methods : GET and POST.")
                    }
                }
              }
            }

          } ~
            pathPrefix("docs") {
              pathEndOrSingleSlash {
                getFromResource("web/index.html")
              }
            } ~
            pathPrefix("swagger-ui") {
              getFromResourceDirectory("web/swagger-ui/")
            } ~
            Docs.route
        }
      }

      val bindingFuture = Http().bindAndHandle(route, server.hostname, server.port)
      displayInfo(server)
      if (verbose.on) displayConfiguration(platforms)

      sys.addShutdownHook {
        bindingFuture
          .flatMap(_.unbind().andThen { case _ => logger.info("Server stopped...") })
          .flatMap(_ => system.terminate())
          .onComplete(_ => logger.info("We're done here, nothing else to see"))
      }

    case Left(errors) =>
    //nothing to do
  }

}