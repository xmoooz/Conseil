package tech.cryptonomic.conseil.api.routes.platform.data

import java.time.Instant

import io.circe.{Encoder, Json}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.cryptonomic.conseil.common.generic.chain.DataTypes.{OutputType, QueryResponse, QueryResponseWithOutput}

class ApiDataHelpersTest extends AnyWordSpec with Matchers {

  private case class TestEntity(value: String)

  private val sut = new ApiCirceJsonSchema {
    override protected def customAnyEncoder: PartialFunction[Any, Json] = {
      case x: TestEntity => Json.fromString(x.value)
    }
  }

  "ApiDataHelpers" should {
      val encodeAny: Encoder[Any] = sut.anySchema.encoder
      val encodeResponse: Encoder[QueryResponse] = sut.queryResponseSchema.encoder
      val encodeResponseWithType: Encoder[QueryResponseWithOutput] = sut.queryResponseSchemaWithOutputType.encoder

      "encode 'Any' for default (common) data types conversion properly firstly" in {
        encodeAny(java.lang.Integer.valueOf(1)) shouldBe Json.fromInt(1)
        encodeAny(java.lang.Long.valueOf(1L)) shouldBe Json.fromLong(1)
        encodeAny(java.math.BigDecimal.ZERO) shouldBe Json.fromBigDecimal(0)
        encodeAny("a") shouldBe Json.fromString("a")
        encodeAny(true) shouldBe Json.True
        encodeAny(Vector(1, 2, 3)) shouldBe Json.fromValues(
          List(Json.fromInt(1), Json.fromInt(2), Json.fromInt(3))
        )
        val instant = Instant.parse("2007-12-03T10:15:30.00Z").toEpochMilli
        encodeAny(new java.sql.Timestamp(instant)) shouldBe Json.fromLong(1196676930000L)
      }
      "convert 'Any' for custom data types conversion properly secondly" in {
        encodeAny(TestEntity("a")) shouldBe Json.fromString("a")
      }
      "fallback to default value if previous conversions are not satisfying" in {
        encodeAny(java.lang.Byte.MAX_VALUE) shouldBe Json.fromString("127")
        encodeAny(java.lang.Double.MAX_VALUE) shouldBe Json.fromString("1.7976931348623157E308")
      }
      "convert 'QueryResponse' entity to 'Json' properly" in {
        encodeResponse(
          Map(
            "field1" -> None,
            "field2" -> Some(true),
            "field3" -> Some(1)
          )
        ) shouldBe Json.fromFields(
          List(
            "field1" -> Json.Null,
            "field2" -> Json.True,
            "field3" -> Json.fromInt(1)
          )
        )
      }
      "convert 'QueryResponseWithOutput' entity to 'Json' properly" in {
        encodeResponseWithType(
          QueryResponseWithOutput(
            List(
              Map(
                "field1" -> None,
                "field2" -> Some(true),
                "field3" -> Some(1)
              )
            ),
            OutputType.json
          )
        ) shouldBe Json.fromValues(
          List(
            Json.fromFields(
              List(
                "field1" -> Json.Null,
                "field2" -> Json.True,
                "field3" -> Json.fromInt(1)
              )
            )
          )
        )
      }
    }
}
