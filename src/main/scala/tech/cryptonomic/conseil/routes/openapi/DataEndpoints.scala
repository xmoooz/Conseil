package tech.cryptonomic.conseil.routes.openapi

import endpoints.algebra
import tech.cryptonomic.conseil.generic.chain.DataTypes.{ApiQuery, QueryValidationError}
import tech.cryptonomic.conseil.tezos.ApiOperations.Filter
import tech.cryptonomic.conseil.tezos.FeeOperations.AverageFees
import tech.cryptonomic.conseil.tezos.Tables
import tech.cryptonomic.conseil.tezos.Tables.BlocksRow


trait DataEndpoints
  extends algebra.Endpoints
    with JsonSchemas
    with EndpointsHelpers {

  private val commonPath = path / "v2" / "data" / segment[String](name = "platform") / segment[String](name = "network")

  def queryEndpoint: Endpoint[((String, String, String), ApiQuery, String), Option[Either[List[QueryValidationError], List[Map[String, Option[Any]]]]]] =
    endpoint(
      request = post(url = commonPath / segment[String](name = "entity"),
        entity = jsonRequest[ApiQuery](),
        headers = header("apiKey")),
      response = validated(
        response = jsonResponse[List[Map[String, Option[Any]]]](docs = Some("Query endpoint")),
        invalidDocs = Some("Can't query - invalid entity!")
      ).orNotFound(Some("Not found"))
    )

  def blocksEndpoint: Endpoint[((String, String, Filter), String), Option[List[Map[String, Option[Any]]]]] =
    endpoint(
      request = get(
        url = commonPath / "blocks" /? myQueryStringParams,
        headers = header("apiKey")),
      response = jsonResponse[List[Map[String, Option[Any]]]](docs = Some("Query compatibility endpoint for blocks")).orNotFound(Some("Not found"))
    )

  def blocksHeadEndpoint: Endpoint[(String, String, String), Option[Tables.BlocksRow]] =
    endpoint(
      request = get(
        url = commonPath / "blocks" / "head",
        headers = header("apiKey")),
      response = jsonResponse[BlocksRow](docs = Some("Query compatibility endpoint for blocks head")).orNotFound(Some("Not found"))
    )

  def blockByHashEndpoint: Endpoint[((String, String, String), String), Option[Map[String, Any]]] =
    endpoint(
      request = get(
        url = commonPath / "blocks" / segment[String](name = "hash"),
        headers = header("apiKey")),
      response = jsonResponse[Map[String, Any]](docs = Some("Query compatibility endpoint for block")).orNotFound(Some("Not found"))
    )

  def accountsEndpoint: Endpoint[((String, String, Filter), String), Option[List[Map[String, Option[Any]]]]] =
    endpoint(
      request = get(
        url = commonPath / "accounts" /? myQueryStringParams,
        headers = header("apiKey")),
      response = jsonResponse[List[Map[String, Option[Any]]]](docs = Some("Query compatibility endpoint for accounts")).orNotFound(Some("Not found"))
    )

  def accountByIdEndpoint: Endpoint[((String, String, String), String), Option[Map[String, Any]]] =
    endpoint(
      request = get(
        url = path / "v2" / "data" / segment[String](name = "platform") / segment[String](name = "network") / "accounts" / segment[String](name = "accountId"),
        headers = header("apiKey")),
      response = jsonResponse[Map[String, Any]](docs = Some("Query compatibility endpoint for account")).orNotFound(Some("Not found"))
    )

  def operationGroupsEndpoint: Endpoint[((String, String, Filter), String), Option[List[Map[String, Option[Any]]]]] =
    endpoint(
      request = get(
        url = commonPath / "operation_groups" /? myQueryStringParams,
        headers = header("apiKey")),
      response = jsonResponse[List[Map[String, Option[Any]]]](docs = Some("Query compatibility endpoint for operation groups")).orNotFound(Some("Not found"))
    )

  def operationGroupByIdEndpoint: Endpoint[((String, String, String), String), Option[Map[String, Any]]] =
    endpoint(
      request = get(
        url = commonPath / "operation_groups" / segment[String](name = "operationGroupId"),
        headers = header("apiKey")),
      response = jsonResponse[Map[String, Any]](docs = Some("Query compatibility endpoint for operation group")).orNotFound(Some("Not found"))
    )

  def avgFeesEndpoint: Endpoint[((String, String, Filter), String), Option[AverageFees]] =
    endpoint(
      request = get(
        url = commonPath / "operations" / "avgFees" /? myQueryStringParams,
        headers = header("apiKey")),
      response = jsonResponse[AverageFees](docs = Some("Query compatibility endpoint for average fees")).orNotFound(Some("Not found"))
    )

  def operationsEndpoint: Endpoint[((String, String, Filter), String), Option[List[Map[String, Option[Any]]]]] =
    endpoint(
      request = get(
        url = commonPath / "operations" /? myQueryStringParams,
        headers = header("apiKey")),
      response = jsonResponse[List[Map[String, Option[Any]]]](docs = Some("Query compatibility endpoint for operations")).orNotFound(Some("Not found"))
    )

}
