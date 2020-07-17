package tech.cryptonomic.conseil.api.routes.platform.data.ethereum

import akka.http.scaladsl.server.Directives.concat
import akka.http.scaladsl.server.Route
import cats.instances.either._
import cats.instances.future._
import cats.syntax.bitraverse._
import com.typesafe.scalalogging.LazyLogging
import tech.cryptonomic.conseil.api.metadata.MetadataService
import tech.cryptonomic.conseil.api.routes.platform.data.ApiDataTypes.ApiQuery
import tech.cryptonomic.conseil.api.routes.platform.data.{ApiDataOperations, ApiDataRoutes}
import tech.cryptonomic.conseil.common.config.MetadataConfiguration
import tech.cryptonomic.conseil.common.ethereum.EthereumTypes.EthereumBlockHash
import tech.cryptonomic.conseil.common.generic.chain.DataTypes.{ApiPredicate, OperationType, QueryResponseWithOutput}
import tech.cryptonomic.conseil.common.metadata
import tech.cryptonomic.conseil.common.metadata.{EntityPath, NetworkPath, PlatformPath}

import scala.concurrent.{ExecutionContext, Future}

case class EthereumDataRoutes(
    metadataService: MetadataService,
    metadataConfiguration: MetadataConfiguration,
    operations: ApiDataOperations,
    maxQueryResultSize: Int
)(
    implicit apiExecutionContext: ExecutionContext
) extends EthereumDataHelpers
    with ApiDataRoutes
    with LazyLogging {

  private val platformPath = PlatformPath("ethereum")
  private val dataQueries = new EthereumDataQueries(operations)

  /** V2 Route implementation for query endpoint */
  override val postRoute: Route = queryEndpoint.implementedByAsync {
    case ((platform, network, entity), apiQuery, _) =>
      val path = EntityPath(entity, NetworkPath(network, PlatformPath(platform)))
      pathValidation(path) {
        apiQuery
          .withNetwork(network)
          .validate(path, metadataService, metadataConfiguration)
          .flatMap { validationResult =>
            validationResult.map { validQuery =>
              operations.queryWithPredicates(platform, entity, validQuery.withLimitCap(maxQueryResultSize)).map {
                queryResponses =>
                  QueryResponseWithOutput(queryResponses, validQuery.output)
              }
            }.left.map(Future.successful).bisequence
          }
          .map(Some(_))
      }
  }

  /** V2 Route implementation for blocks endpoint */
  private val blocksRoute: Route = ethereumBlocksEndpoint.implementedByAsync {
    case ((network, filter), _) =>
      platformNetworkValidation(network) {
        dataQueries.fetchBlocks(filter.toQuery(network).withLimitCap(maxQueryResultSize))
      }
  }

  /** V2 Route implementation for blocks head endpoint */
  private val blocksHeadRoute: Route = ethereumBlocksHeadEndpoint.implementedByAsync {
    case (network, _) =>
      platformNetworkValidation(network) {
        dataQueries.fetchBlocksHead(network)
      }
  }

  /** V2 Route implementation for blocks by hash endpoint */
  private val blockByHashRoute: Route = ethereumBlockByHashEndpoint.implementedByAsync {
    case ((network, hash), _) =>
      platformNetworkValidation(network) {
        dataQueries.fetchBlockByHash(network, EthereumBlockHash(hash))
      }
  }

  /** V2 Route implementation for transactions endpoint */
  private val transactionsRoute: Route = ethereumTransactionsEndpoint.implementedByAsync {
    case ((network, filter), _) =>
      platformNetworkValidation(network) {
        dataQueries.fetchTransactions(filter.toQuery(network).withLimitCap(maxQueryResultSize))
      }
  }

  /** V2 Route implementation for transaction by id endpoint */
  private val transactionByIdRoute: Route = ethereumTransactionByIdEndpoint.implementedByAsync {
    case ((network, id), _) =>
      platformNetworkValidation(network) {
        dataQueries.fetchTransactionById(network, id)
      }
  }

  /** V2 concatenated routes */
  override val getRoute: Route =
    concat(
      blocksHeadRoute,
      blockByHashRoute,
      blocksRoute,
      transactionsRoute,
      transactionByIdRoute
    )

  /** Function for validation of the platform and network with flatten */
  private def platformNetworkValidation[A](network: String)(
      operation: => Future[Option[A]]
  ): Future[Option[A]] =
    pathValidation(NetworkPath(network, platformPath))(operation)

  private def pathValidation[A](path: metadata.Path)(
      operation: => Future[Option[A]]
  ): Future[Option[A]] =
    if (metadataService.exists(path))
      operation
    else
      Future.successful(None)

  implicit private class ApiQueryOps(q: ApiQuery) {
    def withNetwork(network: String): ApiQuery = {
      val predicate = ApiPredicate("network", OperationType.eq, Some(List(network)))
      q.copy(predicates = Some(q.predicates.getOrElse(List.empty) :+ predicate))
    }

  }

}