package tech.cryptonomic.conseil.tezos

import java.sql.Timestamp
import java.time.{LocalDate, ZoneOffset}

import com.typesafe.scalalogging.LazyLogging
import org.scalamock.scalatest.MockFactory
import org.scalatest.{Matchers, WordSpec, OptionValues}
import org.scalatest.concurrent.ScalaFutures
import slick.jdbc.H2Profile.api._
import tech.cryptonomic.conseil.tezos.Tables.{BlocksRow, OperationsRow, OperationGroupsRow}
import tech.cryptonomic.conseil.tezos.TezosTypes._
import tech.cryptonomic.conseil.tezos.FeeOperations.AverageFees

import scala.util.Random

/* use this to make random generation implicit but deterministic */
case class RandomSeed(seed: Long) extends AnyVal with Product with Serializable {
  def +(extra: Long): RandomSeed = RandomSeed(seed + extra)
}

class TezosDatabaseOperationsTest
  extends WordSpec
    with MockFactory
    with InMemoryDatabase
    with Matchers
    with ScalaFutures
    with OptionValues
    with LazyLogging {

  "The database api" should {

    //needed for most tezos-db operations
    import scala.concurrent.ExecutionContext.Implicits.global

    val sut = TezosDatabaseOperations

    "write fees" in {
      implicit val randomSeed = RandomSeed(testReferenceTime.getTime)

      val expected = 5
      val generatedFees = generateFees(expected, testReferenceTime)

      dbHandler.run(sut.writeFees(generatedFees)).futureValue.value shouldBe expected

      //read and check what's on db
      val dbFees = dbHandler.run(Tables.Fees.result).futureValue

      dbFees should have size expected

      import org.scalatest.Inspectors._

      forAll(dbFees zip generatedFees) { case (row, fee) => 
        row.low shouldEqual fee.low
        row.medium shouldEqual fee.medium
        row.high shouldEqual fee.high
        row.timestamp shouldEqual fee.timestamp
        row.kind shouldEqual fee.kind
      }
    }

    "write blocks" in {
      implicit val randomSeed = RandomSeed(testReferenceTime.getTime)

      val basicBlocks = generateBlocks(5, testReferenceTime)
      val generatedBlocks = basicBlocks.zipWithIndex map {
        case (block, idx) =>
          //need to use different seeds to generate unique hashes for groups
          val group = generateOperationGroup(block, operations = 1)(randomSeed + idx)
          block.copy(operationGroups = List(group))
      }

      whenReady(dbHandler.run(sut.writeBlocks(generatedBlocks))) {
        _ =>
          //read and check what's on db
          val dbBlocks = dbHandler.run(Tables.Blocks.result).futureValue

          dbBlocks should have size (generatedBlocks.size)

          import org.scalatest.Inspectors._

          forAll(dbBlocks zip generatedBlocks) {
            case (row, block) =>
              row.level shouldEqual block.metadata.header.level
              row.proto shouldEqual block.metadata.header.proto
              row.predecessor shouldEqual block.metadata.header.predecessor
              row.timestamp shouldEqual block.metadata.header.timestamp
              row.validationPass shouldEqual block.metadata.header.validationPass
              row.fitness shouldEqual block.metadata.header.fitness.mkString(",")
              row.context.value shouldEqual block.metadata.header.context
              row.signature shouldEqual block.metadata.header.signature
              row.protocol shouldEqual block.metadata.protocol
              row.chainId shouldEqual block.metadata.chain_id
              row.hash shouldEqual block.metadata.hash
              row.operationsHash shouldEqual block.metadata.header.operations_hash
          }

          val dbBlocksAndGroups = 
            dbHandler.run {
              val query = for {
                g <- Tables.OperationGroups
                b <- g.blocksFk
              } yield (b, g)
              query.result
            }.futureValue

          dbBlocksAndGroups should have size (generatedBlocks.size)

          forAll(dbBlocksAndGroups) {
            case (blockRow, groupRow) =>
              val blockForGroup = generatedBlocks.find(_.metadata.hash == blockRow.hash).value
              val group = blockForGroup.operationGroups.head
              groupRow.hash shouldEqual group.hash
              groupRow.blockId shouldEqual blockForGroup.metadata.hash
              groupRow.chainId shouldEqual group.chain_id
              groupRow.branch shouldEqual group.branch
              groupRow.signature shouldEqual group.signature
          }

          val dbOperations =
           dbHandler.run {
             val query = for {
              o <- Tables.Operations
              g <- o.operationGroupsFk
            } yield (g, o)
            query.result
           }.futureValue

          val generatedGroups = generatedBlocks.map(_.operationGroups.head)

          dbOperations should have size (generatedGroups.map(_.contents.map(_.size).getOrElse(0)).sum)

          forAll(dbOperations) {
            case (groupRow, opRow) =>
              val operationBlock = generatedBlocks.find(_.operationGroups.head.hash == groupRow.hash).value
              val operationGroup = generatedGroups.find(_.hash == groupRow.hash).value
              val operation = operationGroup.contents.value.head
              opRow.kind shouldEqual operation.kind
              opRow.source shouldEqual operation.source
              opRow.fee shouldEqual operation.fee
              opRow.storageLimit shouldEqual operation.storageLimit
              opRow.gasLimit shouldEqual operation.gasLimit
              opRow.amount shouldEqual operation.amount
              opRow.destination shouldEqual operation.destination
              opRow.pkh shouldEqual operation.pkh
              opRow.delegate shouldEqual operation.delegate
              opRow.balance shouldEqual operation.balance
              opRow.operationGroupHash shouldEqual operationGroup.hash
              opRow.blockHash shouldEqual operationBlock.metadata.hash
              opRow.blockLevel shouldEqual operationBlock.metadata.header.level
              opRow.timestamp shouldEqual operationBlock.metadata.header.timestamp
          }
      }
      

    }

    "write accounts" in pending

    "fetch nothing if looking up a non-existent operation group by hash" in {
      dbHandler.run(sut.operationsForGroup("no-group-here")).futureValue shouldBe None
    }

    "fetch existing operations with their group on a existing hash" in {
      implicit val randomSeed = RandomSeed(testReferenceTime.getTime)

      val block = generateBlockRows(1, testReferenceTime).head
      val group = generateOperationGroupRows(block).head
      val ops = generateOperationRowsForGroup(block, group)

      val populateAndFetch = for {
        _ <- Tables.Blocks += block
        _ <- Tables.OperationGroups += group
        ids <- Tables.Operations returning Tables.Operations.map(_.operationId) ++= ops
        result <- sut.operationsForGroup(group.hash)
      } yield (result, ids)

      val (Some((groupRow, operationRows)), operationIds) = dbHandler.run(populateAndFetch).futureValue

      groupRow.hash shouldEqual group.hash
      operationRows should have size ops.size
      operationRows.map(_.operationId).toList should contain theSameElementsAs operationIds

    }

    "compute correct average fees from stored operations" in {
      //generate data
      implicit val randomSeed = RandomSeed(testReferenceTime.getTime)
      val block = generateBlockRows(1, testReferenceTime).head
      val group = generateOperationGroupRows(block).head

      // mu = 152.59625
      // std-dev = 331.4
      // the sample std-dev should be 354.3, using correction formula
      val fees = Seq(
        Some("35.23"), Some("12.01"), Some("2.22"), Some("150.01"), None, Some("1020.30"), Some("1.00"), None
      )
      val ops = wrapFeesWithOperationRows(fees, block, group)

      val populate = for {
        _ <- Tables.Blocks += block
        _ <- Tables.OperationGroups += group
        ids <- Tables.Operations returning Tables.Operations.map(_.operationId) ++= ops
      } yield ids

      dbHandler.run(populate).futureValue should have size (fees.size)

      //expectations
      val (mu, sigma) = (153, 332)
      val latest = new Timestamp(ops.map(_.timestamp.getTime).max)

      val expected = AverageFees(
        low = 0,
        medium = mu,
        high = mu + sigma,
        timestamp = latest,
        kind = ops.head.kind
      )

      //check
      val feesCalculation = sut.calculateAverageFees(ops.head.kind)

      dbHandler.run(feesCalculation).futureValue.value shouldEqual expected

    }

    "return None when computing average fees for a kind with no data" in {
      //generate data
      implicit val randomSeed = RandomSeed(testReferenceTime.getTime)
      val block = generateBlockRows(1, testReferenceTime).head
      val group = generateOperationGroupRows(block).head

      val fees = Seq.fill(3)(Some("1.0"))
      val ops = wrapFeesWithOperationRows(fees, block, group)

      val populate = for {
        _ <- Tables.Blocks += block
        _ <- Tables.OperationGroups += group
        ids <- Tables.Operations returning Tables.Operations.map(_.operationId) ++= ops
      } yield ids

      dbHandler.run(populate).futureValue should have size (fees.size)

      //check
      val feesCalculation = sut.calculateAverageFees("undefined")

      dbHandler.run(feesCalculation).futureValue shouldBe None

    }

    "compute average fees only using the selected operation kinds" in {
      //generate data
      implicit val randomSeed = RandomSeed(testReferenceTime.getTime)
      val block = generateBlockRows(1, testReferenceTime).head
      val group = generateOperationGroupRows(block).head

      val (selectedFee, ignoredFee) = (Some("1.0"), Some("1000.0"))

      val fees = Seq(selectedFee, selectedFee, ignoredFee, ignoredFee)

      //change kind for fees we want to ignore
      val ops = wrapFeesWithOperationRows(fees, block, group).map {
        case op if op.fee == ignoredFee => op.copy(kind = op.kind + "ignore")
        case op => op
      }

      val selection = ops.filter(_.fee == selectedFee)

      val populate = for {
        _ <- Tables.Blocks += block
        _ <- Tables.OperationGroups += group
        ids <- Tables.Operations returning Tables.Operations.map(_.operationId) ++= ops
      } yield ids

      dbHandler.run(populate).futureValue should have size (fees.size)

      //expectations
      val mu = 1
      val latest = new Timestamp(selection.map(_.timestamp.getTime).max)

      val expected = AverageFees(
        low = mu,
        medium = mu,
        high = mu,
        timestamp = latest,
        kind = ops.head.kind
      )
      //check
      val feesCalculation = sut.calculateAverageFees(selection.head.kind)

      dbHandler.run(feesCalculation).futureValue.value shouldEqual expected

    }

    "return the default when fetching the latest block level and there's no block" in {
      val expected = -1
      val maxLevel = dbHandler.run(
        sut.fetchMaxBlockLevel
      ).futureValue

      maxLevel should equal(expected)
    }

    "fetch the latest block level when blocks are available" in {
      implicit val randomSeed = RandomSeed(testReferenceTime.getTime)

      val expected = 5
      val populateAndFetch = for {
        _ <- Tables.Blocks ++= generateBlockRows(expected, testReferenceTime)
        result <- sut.fetchMaxBlockLevel
      } yield result

      val maxLevel = dbHandler.run(populateAndFetch.transactionally).futureValue

      maxLevel should equal(expected)
    }

    "test accounts max block level" in pending

    "test old accounts purging" in pending

    "correctly verify when a block exists" in {
      implicit val randomSeed = RandomSeed(testReferenceTime.getTime)

      val blocks = generateBlockRows(1, testReferenceTime)
      val opGroups = generateOperationGroupRows(blocks: _*)
      val testHash = blocks.last.hash

      val populateAndTest = for {
        _ <- Tables.Blocks ++= blocks
        _ <- Tables.OperationGroups ++= opGroups
        existing <- sut.blockExists(testHash)
        nonExisting <- sut.blockExists("bogus-hash")
      } yield (existing, nonExisting)

      val (hit, miss) = dbHandler.run(populateAndTest.transactionally).futureValue

      hit shouldBe true
      miss shouldBe false

    }

    "say a block doesn't exist if it has no associated operation group" in {
      implicit val randomSeed = RandomSeed(testReferenceTime.getTime)

      val blocks = generateBlockRows(1, testReferenceTime)
      val testHash = blocks.last.hash

      val populateAndTest = for {
        _ <- Tables.Blocks ++= blocks
        found <- sut.blockExists(testHash)
      } yield found

      val exists = dbHandler.run(populateAndTest.transactionally).futureValue
      exists shouldBe false

    }
  }

  //a stable timestamp reference if needed
  private lazy val testReferenceTime =
    new Timestamp(
      LocalDate.of(2018, 1, 1)
        .atStartOfDay
        .toEpochSecond(ZoneOffset.UTC)
    )

  private def generateFees(howMany: Int, startAt: Timestamp)(implicit randomSeed: RandomSeed): List[AverageFees] = {
    require(howMany > 0, "the test can generates a positive number of fees, you asked for a non positive value")

    val rnd = new Random(randomSeed.seed)

    (1 to howMany).map {
      current =>
        val low = rnd.nextInt(10)
        val medium = rnd.nextInt(10) + 10
        val high = rnd.nextInt(10) + 20
        AverageFees(
          low = low,
          medium = medium,
          high = high,
          timestamp = new Timestamp(startAt.getTime + current),
          kind = "kind"
        )
    }.toList
  }

  /* randomly populate a number of blocks based on a level range */
  private def generateBlocks(toLevel: Int, startAt: Timestamp)(implicit randomSeed: RandomSeed): List[Block] = {
    require(toLevel > 0, "the test can generate blocks up to a positive chain level, you asked for a non positive value")

    //custom hash generator with predictable seed
    val generateHash: Int => String = alphaNumericGenerator(new Random(randomSeed.seed))

    //same for all blocks
    val chainHash = generateHash(5)

    val startMillis = startAt.getTime

    def generateOne(level: Int, predecessorHash: String): Block =
      Block(
        BlockMetadata(
          "protocol",
          Some(chainHash),
          generateHash(10),
          BlockHeader(
            level = level, 
            proto = 1,
            predecessor = predecessorHash,
            timestamp = new Timestamp(startMillis + level),
            validationPass = 0,
            operations_hash = None,
            fitness = Seq.empty,
            context = s"context$level",
            signature = Some(s"sig${generateHash(10)}")
          )),
        operationGroups = List.empty
      )

    //we need a block to start
    val genesis = generateOne(0, "genesis")

    //use a fold to pass the predecessor hash, to keep a plausibility of sort
    (1 to toLevel).foldLeft(List(genesis)) {
      case (chain, lvl) => 
        val currentBlock = generateOne(lvl, chain.head.metadata.hash)
        currentBlock :: chain
    }.reverse
    
  }


  /* randomly populate a number of blocks based on a level range */
  private def generateBlockRows(toLevel: Int, startAt: Timestamp)(implicit randomSeed: RandomSeed): List[Tables.BlocksRow] = {
    require(toLevel > 0, "the test can generate blocks up to a positive chain level, you asked for a non positive value")

    //custom hash generator with predictable seed
    val generateHash: Int => String = alphaNumericGenerator(new Random(randomSeed.seed))

    //same for all blocks
    val chainHash = generateHash(5)

    val startMillis = startAt.getTime

    def generateOne(level: Int, predecessorHash: String): BlocksRow =
      BlocksRow(
      level = level,
      proto = 1,
      predecessor = predecessorHash,
      timestamp = new Timestamp(startMillis + level),
      validationPass = 0,
      fitness = "fitness",
      protocol = "protocol",
      context = Some(s"context$level"),
      signature = Some(s"sig${generateHash(10)}"),
      chainId = Some(chainHash),
      hash = generateHash(10)
    )

    //we need somewhere to start with
    val genesis = generateOne(0, "genesis")
  
    //use a fold to pass the predecessor hash, to keep a plausibility of sort
    (1 to toLevel).foldLeft(List(genesis)) {
      case (chain, lvl) =>
        val currentBlock = generateOne(lvl, chain.head.hash)
        currentBlock :: chain
    }.reverse

  }

  /* create an operation group for each block passed in, using random values, with the requested copies of operations */
  private def generateOperationGroup(block: Block, operations: Int)(implicit randomSeed: RandomSeed): OperationGroup = {
    require(operations >= 0, "the test won't generate a negative number of operations")

    //custom hash generator with predictable seed
    val generateHash: Int => String = alphaNumericGenerator(new Random(randomSeed.seed))

    def fillOperations(): Option[List[Operation]] = {
      val ops = List.fill(operations) {
        Operation(
          kind = "kind",
          block = Some(block.metadata.hash),
          level = Some(block.metadata.header.level),
          slots = Some(List(0)),
          nonce = Some(generateHash(10)),
          op1 = None,
          op2 = None,
          bh1 = None,
          bh2 = None,
          pkh = Some("pkh"),
          secret = Some("secret"),
          proposals = Some(List("proposal")),
          period = Some("period"),
          source = Some("source"),
          proposal = Some("proposal"),
          ballot = Some("ballot"),
          fee = Some("fee"),
          counter = Some(0),
          gasLimit = Some("gasLimit"),
          storageLimit = Some("storageLimit"),
          publicKey = Some("publicKey"),
          amount = Some("amount"),
          destination = Some("destination"),
          parameters = Some("parameters"),
          managerPubKey = Some("managerPubKey"),
          balance = Some("balance"),
          spendable = Some(true),
          delegatable = Some(true),
          delegate = Some("delegate")
        )
      }
      if (ops.isEmpty) None else Some(ops)
    }


    OperationGroup(
      protocol = "protocol",
      chain_id = block.metadata.chain_id,
      hash = generateHash(10),
      branch = generateHash(10),
      signature = Some(s"sig${generateHash(10)}"),
      contents = fillOperations()
    )
  }


  /* create an empty operation group for each block passed in, using random values */
  private def generateOperationGroupRows(blocks: BlocksRow*)(implicit randomSeed: RandomSeed): List[Tables.OperationGroupsRow] = {
    require(blocks.nonEmpty, "the test won't generate any operation group without a block to start with")

    //custom hash generator with predictable seed
    val generateHash: Int => String = alphaNumericGenerator(new Random(randomSeed.seed))

    blocks.map(
      block =>
        Tables.OperationGroupsRow(
          protocol = "protocol",
          chainId = block.chainId,
          hash = generateHash(10),
          branch = generateHash(10),
          signature = Some(s"sig${generateHash(10)}"),
          blockId = block.hash
        )
    ).toList
  }

  /* create operations related to a specific group, with random data */
  private def generateOperationRowsForGroup(block: BlocksRow, group: OperationGroupsRow, howMany: Int = 3): List[Tables.OperationsRow] =
   if (howMany > 0) {

     (1 to howMany).map {
       counting =>
         Tables.OperationsRow(
           kind = "operation-kind",
           operationGroupHash = group.hash,
           operationId = -1,
           blockHash = block.hash,
           timestamp = block.timestamp,
           blockLevel = block.level
         )
     }.toList
   } else List.empty

  /* create operation rows to hold the given fees */
  private def wrapFeesWithOperationRows(
    fees: Seq[Option[String]],
    block: BlocksRow,
    group: OperationGroupsRow)(implicit randomSeed: RandomSeed) = {
    //custom hash generator with predictable seed
    val generateHash: Int => String = alphaNumericGenerator(new Random(randomSeed.seed))

    fees.zipWithIndex.map {
      case (fee, index) =>
        OperationsRow(
          kind = "kind",
          operationGroupHash = group.hash,
          operationId = -1,
          fee = fee,
          blockHash = block.hash,
          timestamp = new Timestamp(block.timestamp.getTime + index),
          blockLevel = block.level
        )
    }

  }

  private val alphaNumericGenerator =
    (random: Random) => random.alphanumeric.take(_: Int).mkString

  /*
  //CHANGE TESTS TO ACCOMOMODATE ZERONET TABLE CHANGES!!!

  "accountsToDatabaseRows" should "Turn account objects into rows ready to be inserted into a database" in {
    val block_hash: String = "BLEis22RCG4PZWvUZM78aUkgiQ2CwfKwutALe8SED2fBmYN3Qc2"
    val account_id : String = "TZ1igUcqJAVr8WQm1X8cpx31TxgcmmuDFSWo"
    val manager: String = "tz1NqkMf682968ukjGox8qaTYhjQTwKJ5E6B"
    val balance: scala.math.BigDecimal = 500.0
    val spendable: Boolean = false
    val delegateValue: Option[String] = None
    val delegate: AccountDelegate = AccountDelegate(setable = false, delegateValue)
    val script: Option[Any] = None
    val counter: Int = 0
    val account: Account = Account(manager, balance, spendable, delegate, script, counter)
    val accounts: Map[String, Account] = Map(account_id ->  account)

    val accountsInfo : AccountsWithBlockHash = AccountsWithBlockHash(block_hash, accounts)

    val block_id : String = "BLEis22RCG4PZWvUZM78aUkgiQ2CwfKwutALe8SED2fBmYN3Qc2"
    val accountsRow: AccountsRow =
      AccountsRow(account_id, block_id, manager, spendable, delegateSetable = false, delegateValue, balance, counter)
    val listAccountsRow: List[AccountsRow] = List(accountsRow)

    accountsToDatabaseRows(accountsInfo) should be (listAccountsRow)
  }
  /*
  "blockToDatabaseRow" should "Turn block objects into rows ready to be inserted into a database" in {
    val hash: String = "BM5KdTsHWqBftdHto4ipDS3DXBkkbZPKWUiRgut6yL34ZBNDATo"
    val net_id: String = "NetXj4yEEKnjaK8"
    val operations: Seq[Seq[BlockOperationMetadata]] = Seq(Seq())
    val protocol: String = "ProtoALphaALphaALphaALphaALphaALphaALphaALphaDdp3zK"
    val level: Int = 108175
    val proto: Int = 1
    val predecessor: String = "BLVRDgFJmYZNjqmCACrAgbsaYuguTEK5dNV9gcYMbHuemijDFTA"
    val timestamp: java.sql.Timestamp = Timestamp.valueOf("2018-03-19 13:06:20.0")
    val validation_pass: Int = 1
    val operations_hash: String = "LLoZsHM7p1HHBPvWV9aj5tYjJZVJEEwtsxUkVRtVWMhxXQ3brHEsp"
    val fitness: Seq[String] = Seq("00,00000000000e0730")
    val data: String =
      "0000cae1342613c0d3eac61fe48bb9cc4ffbf4d31bd97c23b90a91034138486796aaf01af53f218d222b4cbe15cd47" +
        "ef640b83255777e6feb49c4718e58e80096aed8edb3b453d980aec5c0784a30fd2ec1d6b3f285d577a85d5ab576c" +
        "92447b76f43dea943c6e35cb06"

    val metaData: BlockMetadata =
      BlockMetadata(hash, net_id, operations, protocol, level, proto, predecessor,
        timestamp, validation_pass, operations_hash, fitness, data)
    val operationGroups: List[OperationGroup] = List()
    val block: Block = Block(metaData, operationGroups)

    val blocksRow: BlocksRow =
      BlocksRow(net_id, protocol, level, proto, predecessor, validation_pass, operations_hash, data, hash,
        timestamp, fitness.head)
    blockToDatabaseRow(block) should be (blocksRow)
  }
  */
  "operationGroupToDatabaseRow" should "Turn Block objects into operationGroup rows ready to be inserted " +
    "into a database" in {
    //Block data
    val hash: String = "BKimLo1EJGd1eY9u6siaszunatAKsSYxzcedq2tG6Vni95dmH8n"
    val net_id: String = "NetXj4yEEKnjaK8"
    val operations: Seq[Seq[BlockOperationMetadata]] = Seq(Seq())
    val protocol: String = "ProtoALphaALphaALphaALphaALphaALphaALphaALphaDdp3zK"
    val level: Int = 7
    val proto: Int = 1
    val predecessor: String = "BMdK2ymoBh9AjPS1WUvGs33NEcmkZrW2JbtFQH3F6KdDWSgsfeP"
    val timestamp: java.sql.Timestamp = Timestamp.valueOf("2017-11-20 01:52:22.0")
    val validation_pass: Int = 1
    val operations_hash: String = "LLoauvJSk6ugA3kjb5xW8P2nLNY9Gb3K2XsoXwH9tjfgmfZZJx9aY"
    val fitness: Seq[String] = Seq("00,000000000000000e")
    val data: String = "0002d423a235a78e707d42dfdacf4b916189c7c35f66f96cff111798c0e972ce4" +
      "93b11e4b05629fe273057cc7b562c50380fc379240a5850a57917eb22e760f46b360a1a8752e1ff126" +
      "b0de43b3534e4a18109c24db6e0734fad3c697b207fd601645d56bf6c9cbc1a0a"

    val metaData: BlockMetadata =
      BlockMetadata(hash, net_id, operations, protocol, level, proto, predecessor,
        timestamp, validation_pass, operations_hash, fitness, data)

    //OperationGroup Data
    val operation_group_hash: String = "ooMSv8WNLcbeWJoQfS1CBxPMfQGUZ2WM2n4d8KEV3VxTyn6Fu56"
    val block_id: String = "BKimLo1EJGd1eY9u6siaszunatAKsSYxzcedq2tG6Vni95dmH8n"
    val branch: String = "BMdK2ymoBh9AjPS1WUvGs33NEcmkZrW2JbtFQH3F6KdDWSgsfeP"
    val source: Option[String] = Some("edpkuq1nqG9ahchLjdb7XxkCE79o8FanAj3naNmN7cC9Dv6QUn4uTe")
    val signature: Option[String] =
      Some("edsigtj9HTXuqP4CousdDbAskwa3CccGtTy3DtVrL6kyZgaNJHKVurAFpBemmLfYUDj4DP87nNtAUA1sQfXQT1pdUQwLTeVMPY7")
    val operationList: List[Operation] = List()
    val operationGroup: OperationGroup =
      OperationGroup(operation_group_hash, branch, source, operationList, signature)
    val operationGroups: List[OperationGroup] = List(operationGroup)
    val block: Block = Block(metaData, operationGroups)


    val operationGroupsRow: List[OperationGroupsRow] =
      List(OperationGroupsRow(operation_group_hash, block_id, branch, source, signature))

    operationGroupToDatabaseRow(block) should be (operationGroupsRow)
  }

  "transactionsToDatabaseRows" should "Turn Block objects into transaction rows ready to be inserted " +
    "into a database" in {
    //Block Data
    val hash: String = "BKyDmcLAeWcXmt4GP7KAnvbivrpSA6qubN3j8U6ug9NCXGBZMqs"
    val net_id: String = "NetXj4yEEKnjaK8"
    val operations: Seq[Seq[BlockOperationMetadata]] = Seq(Seq())
    val protocol: String = "ProtoALphaALphaALphaALphaALphaALphaALphaALphaDdp3zK"
    val level: Int = 8
    val proto: Int = 1
    val predecessor: String = "BKimLo1EJGd1eY9u6siaszunatAKsSYxzcedq2tG6Vni95dmH8n"
    val timestamp: java.sql.Timestamp = Timestamp.valueOf("2017-11-20 01:53:22.0")
    val validation_pass: Int = 1
    val operations_hash: String = "LLoZmFbwe84SHgDf6nteyF2cUcgSs1evgdBwNH3aRp6fEfESVc3Br"
    val fitness: Seq[String] = Seq("00,0000000000000015")
    val data: String = "0000ee951de821e94af3cea3de3e9a89584d300dca553087aeb579fce9fb00813" +
      "a546b6d80a5c96dfe765aaa10b1540eef5fa8f3d8fb1e9f60de53c8dd94f085071969c474365cc571cc16" +
      "21a7e0bc6e8f54c1aa8e8218af93300199623be7b3173add65ff600e92bc07"

    val metaData: BlockMetadata =
      BlockMetadata(hash, net_id, operations, protocol, level, proto, predecessor,
        timestamp, validation_pass, operations_hash, fitness, data)

    //OperationGroup Data
    val operation_group_hash: String = "opFV5Nm9CMvKGRjt3FnjXHwySZ9LgqvTQkJMCzSZiwzCPda228u"
    val branch: String = "BKimLo1EJGd1eY9u6siaszunatAKsSYxzcedq2tG6Vni95dmH8n"
    val source: Option[String] = Some("TZ1o3c8L4LbC7odsnifWHddqDkUMq6qBGBj8")
    val signature: Option[String] =
      Some("edsigtdQMe3vmywciFCBqWgJGGzZ9zMCBhCjGUGDaqx8ENaL2sxDEumXxKXrtyDTgmrdcVMQENXHhCQLoYgx8wVnCMyyvpsdw7u")

    //Transaction Data
    val transactionId: Int = 0 //1 in database, but function puts it as 0
    val operationGroupHash: String = "opFV5Nm9CMvKGRjt3FnjXHwySZ9LgqvTQkJMCzSZiwzCPda228u"
    val amount: scala.math.BigDecimal = 10000000
    val destination: Option[String] = Some("tz1SCwR1SuTGeyzzsaKd2JehCNJ4FjWwTe8t")
    val parameters: Option[String] = None

    val operation: Operation =
      Operation(Some("transaction"), Some(amount), destination, None, None, None,
        None, None, None, None, None, None, None, None, None, None)

    val operationList: List[Operation] = List(operation)
    val operationGroup: OperationGroup =
      OperationGroup(operation_group_hash, branch, source, operationList, signature)
    val operationGroups: List[OperationGroup] = List(operationGroup)
    val block: Block = Block(metaData, operationGroups)

    val transactionsRows: List[TransactionsRow] =
      List(TransactionsRow(transactionId, operationGroupHash, amount, destination, parameters))

    transactionsToDatabaseRows(block) should be (transactionsRows)
  }


  "endorsementsToDatabaseRows" should "Turn Block objects into endorsement rows ready to be inserted " in {
    //Block Data
    val hash: String = "BKimLo1EJGd1eY9u6siaszunatAKsSYxzcedq2tG6Vni95dmH8n"
    val net_id: String = "NetXj4yEEKnjaK8"
    val operations: Seq[Seq[BlockOperationMetadata]] = Seq(Seq())
    val protocol: String = "ProtoALphaALphaALphaALphaALphaALphaALphaALphaDdp3zK"
    val level: Int = 7
    val proto: Int = 1
    val predecessor: String = "BMdK2ymoBh9AjPS1WUvGs33NEcmkZrW2JbtFQH3F6KdDWSgsfeP"
    val timestamp: java.sql.Timestamp = Timestamp.valueOf("2017-11-20 01:52:22.0")
    val validation_pass: Int = 1
    val operations_hash: String = "LLoauvJSk6ugA3kjb5xW8P2nLNY9Gb3K2XsoXwH9tjfgmfZZJx9aY"
    val fitness: Seq[String] = Seq("00,000000000000000e")
    val data: String = "0002d423a235a78e707d42dfdacf4b916189c7c35f66f96cff111798c0e972ce4" +
      "93b11e4b05629fe273057cc7b562c50380fc379240a5850a57917eb22e760f46b360a1a8752e1ff126" +
      "b0de43b3534e4a18109c24db6e0734fad3c697b207fd601645d56bf6c9cbc1a0a"

    val metaData: BlockMetadata =
      BlockMetadata(hash, net_id, operations, protocol, level, proto, predecessor,
        timestamp, validation_pass, operations_hash, fitness, data)

    //OperationGroup Data
    val operation_group_hash: String = "ooMSv8WNLcbeWJoQfS1CBxPMfQGUZ2WM2n4d8KEV3VxTyn6Fu56"
    val branch: String = "BMdK2ymoBh9AjPS1WUvGs33NEcmkZrW2JbtFQH3F6KdDWSgsfeP"
    val source: Option[String] = Some("edpkuq1nqG9ahchLjdb7XxkCE79o8FanAj3naNmN7cC9Dv6QUn4uTe")
    val signature: Option[String] =
      Some("edsigtj9HTXuqP4CousdDbAskwa3CccGtTy3DtVrL6kyZgaNJHKVurAFpBemmLfYUDj4DP87nNtAUA1sQfXQT1pdUQwLTeVMPY7")

    //Endorsement Data
    val endorsement_id: Int = 0 //1 in database, but function sets it as 0
    val operationGroupHash: String = "ooMSv8WNLcbeWJoQfS1CBxPMfQGUZ2WM2n4d8KEV3VxTyn6Fu56"
    val op_block_id: String = "BMdK2ymoBh9AjPS1WUvGs33NEcmkZrW2JbtFQH3F6KdDWSgsfeP"
    val slot: Int = 7

    val operation: Operation =
      Operation(Some("endorsement"), None, None, None, None, None,
        None, None, Some(op_block_id), Some(slot), None, None, None, None, None, None)

    val operationList: List[Operation] = List(operation)
    val operationGroup: OperationGroup =
      OperationGroup(operation_group_hash, branch, source, operationList, signature)
    val operationGroups: List[OperationGroup] = List(operationGroup)
    val block: Block = Block(metaData, operationGroups)

    val endorsementsRows: List[EndorsementsRow] =
      List(EndorsementsRow(endorsement_id, operationGroupHash, op_block_id, slot))

    endorsementsToDatabaseRows(block) should be (endorsementsRows)
  }


// From this point on, edit test descriptions
  "originationsToDatabaseRows" should "Turn Block objects into operationGroup rows ready to be inserted " in {
    //Block Data
    val hash: String = "BMSkzjD9wefNhUje4WVXogeAx1g9w4ohBET4RGreqcBSembgLyw"
    val net_id: String = "NetXj4yEEKnjaK8"
    val operations: Seq[Seq[BlockOperationMetadata]] = Seq(Seq())
    val protocol: String = "ProtoALphaALphaALphaALphaALphaALphaALphaALphaDdp3zK"
    val level: Int = 248
    val proto: Int = 1
    val predecessor: String = "BLyTfMV1LMcRjbYf1xZYKoYbMRVf1FkQMmR7M57ndzAm6BQ3KYV"
    val timestamp: java.sql.Timestamp = Timestamp.valueOf("2017-11-20 06:08:02.0")
    val validation_pass: Int = 1
    val operations_hash: String = "LLoZoVz5kJaYxueyxkTSEtEzqJKTvTneDUa9MfWwugjTKmergY7Bz"
    val fitness: Seq[String] = Seq("00,0000000000000d82")
    val data: String = "00001de2d22347c5f4721dacd1d4d744b90d8341381b07430851eab8eac8963647" +
      "f8ff836539add4594ff5337a91299ba4fba8ac7b36da9187ea9afc2bffff3f466828d7641ee81079546" +
      "e208d54f1e7a9af7c7eb67ff2b1cc9ce357d38773a2a14024ed7038c192af0f"

    val metaData: BlockMetadata =
      BlockMetadata(hash, net_id, operations, protocol, level, proto, predecessor,
        timestamp, validation_pass, operations_hash, fitness, data)

    //OperationGroup Data
    val operation_group_hash: String = "ooiu9pA1B7V87GSZEDXdNreya4QUPECEnNyDTGZ4sM2ByyUNbdY"
    val branch: String = "BLyTfMV1LMcRjbYf1xZYKoYbMRVf1FkQMmR7M57ndzAm6BQ3KYV"
    val source: Option[String] = Some("TZ1aXm5nJanNrgcsQMK9wzFQKR4v2k1enZU6")
    val signature: Option[String] =
      Some("edsigtireVXWTaXBHMbEGBaYUrvSC9MJTD2FsMCjd7j7Trb1uX1D7Di3Lfv3Hiq1nnxccTbLDuP2ogX9PATtKySVUJ2RVuqqoyc")

    //Origination Data
    val originationId: Int = 0
    val operationGroupHash: String = "ooiu9pA1B7V87GSZEDXdNreya4QUPECEnNyDTGZ4sM2ByyUNbdY"
    val managerpubkey: Option[String] = None
    val balance: Option[BigDecimal] = Some(201)
    val spendable: Option[Boolean] = Some(false)
    val delegatable: Option[Boolean] = Some(false)
    val delegate: Option[String] = None
    val script: Option[String] = None

    val operation: Operation =
      Operation(Some("origination"), None, None, managerpubkey, balance, spendable,
        delegatable, delegate, None, None, None, None, None, None, None, None)

    val operationList: List[Operation] = List(operation)
    val operationGroup: OperationGroup =
      OperationGroup(operation_group_hash, branch, source, operationList, signature)
    val operationGroups: List[OperationGroup] = List(operationGroup)
    val block: Block = Block(metaData, operationGroups)

    val originationsRow: List[OriginationsRow] =
      List(OriginationsRow(originationId, operationGroupHash, managerpubkey, balance, spendable, delegatable, delegate, script))

    originationsToDatabaseRows(block) should be (originationsRow)
  }

  "delegationsToDatabaseRows" should "Turn Block objects into operationGroup rows ready to be inserted " in {
    //Block Data
    val hash: String = "BLb4QcuPtGrsHRZ8X4MqKXyXVYkeCWKNvehM4ULZ7XS4qGh2mBW"
    val net_id: String = "NetXj4yEEKnjaK8"
    val operations: Seq[Seq[BlockOperationMetadata]] = Seq(Seq())
    val protocol: String = "ProtoALphaALphaALphaALphaALphaALphaALphaALphaDdp3zK"
    val level: Int = 37
    val proto: Int = 1
    val predecessor: String = "BLVJDTuQF8s75TETjv3W1Xy3J9i1MUEZ8iuiqiBgbmcPtYgDmiU"
    val timestamp: java.sql.Timestamp = Timestamp.valueOf("2017-11-20 02:26:22.0")
    val validation_pass: Int = 1
    val operations_hash: String = "LLoa38aoeJa7gjg8emVNuKAwrGi4Fo8q1uhRLEbFG7Dj6WWNkG1m6"
    val fitness: Seq[String] = Seq("00,000000000000018d")
    val data: String = "0001275ff1ed1b6995524fe440b16b72373a05c0f0b010d7cff9733a1216054212" +
      "ec4f64ad693a691a7e76ef119ff42338bf429c0addfd2ab0c624daefe87f02ea1a218fe11eee5ef50df" +
      "9b0ea6623a52ad8f66b882d6b01ac114a59abcdd248304db5d5b247b1f3f800"

    val metaData: BlockMetadata =
      BlockMetadata(hash, net_id, operations, protocol, level, proto, predecessor,
        timestamp, validation_pass, operations_hash, fitness, data)

    //OperationGroup Data
    val operation_group_hash: String = "ooXEViqLfVZcrZsk8u5trC3rBHycwWGKZoEgFL7Yh3FrT9UBPmJ"
    val branch: String = "BLVJDTuQF8s75TETjv3W1Xy3J9i1MUEZ8iuiqiBgbmcPtYgDmiU"
    val source: Option[String] = Some("TZ1hnn5ix9r4B36cwxSKseQZomfqrxXpj1Az")
    val signature: Option[String] =
      Some("edsigtdTiwJa7wozqtPCzmJmwswZQTv9v3CScX9jJgS62QGHUkD3oPkBJ8XfhDR1LQfHYWBKFmP97wfaXE3N1t9sxj7LHG7kzJn")

    //Delegation Data
    val delegation_id: Int = 0
    val delegate: Option[String] = Some("tz1gator8pjfx4PGm6vaUPVqY1fsvZ2hbo32")

    val operation: Operation =
      Operation(Some("delegation"), None, None, None, None, None,
        None, delegate, None, None, None, None, None, None, None, None)

    val operationList: List[Operation] = List(operation)
    val operationGroup: OperationGroup =
      OperationGroup(operation_group_hash, branch, source, operationList, signature)
    val operationGroups: List[OperationGroup] = List(operationGroup)
    val block: Block = Block(metaData, operationGroups)

    val delegationsRow: List[DelegationsRow] =
      List(DelegationsRow(delegation_id, operation_group_hash, delegate.get))

    delegationsToDatabaseRows(block) should be (delegationsRow)
  }

  /* NO RECORDS FOUND IN DATABASE LINKING BLOCKS TO OPERATIONGROUPS TO PROPOSALS
  "proposalsToDatabaseRows" should "Turn Block objects into operationGroup rows ready to be inserted " in {
    val hash: String = "BKimLo1EJGd1eY9u6siaszunatAKsSYxzcedq2tG6Vni95dmH8n"
    val net_id: String = "NetXj4yEEKnjaK8"
    val operations: Seq[Seq[BlockOperationMetadata]] = Seq(Seq())
    val protocol: String = "ProtoALphaALphaALphaALphaALphaALphaALphaALphaDdp3zK"
    val level: Int = 7
    val proto: Int = 1
    val predecessor: String = "BMdK2ymoBh9AjPS1WUvGs33NEcmkZrW2JbtFQH3F6KdDWSgsfeP"
    val timestamp: java.sql.Timestamp = Timestamp.valueOf("2017-11-20 01:52:22.0")
    val validation_pass: Int = 1
    val operations_hash: String = "LLoauvJSk6ugA3kjb5xW8P2nLNY9Gb3K2XsoXwH9tjfgmfZZJx9aY"
    val fitness: Seq[String] = Seq("00,000000000000000e")
    val data: String = "0002d423a235a78e707d42dfdacf4b916189c7c35f66f96cff111798c0e972ce4" +
      "93b11e4b05629fe273057cc7b562c50380fc379240a5850a57917eb22e760f46b360a1a8752e1ff126" +
      "b0de43b3534e4a18109c24db6e0734fad3c697b207fd601645d56bf6c9cbc1a0a"

    val metaData: BlockMetadata =
      BlockMetadata(hash, net_id, operations, protocol, level, proto, predecessor,
        timestamp, validation_pass, operations_hash, fitness, data)
    val operationGroups: List[OperationGroup] = List()
    val block: Block = Block(metaData, operationGroups)

    val operation_group_hash: String = ""
    val block_id: String = ""
    val branch: String = ""
    val source: Option[String]
    val signature: Option[String]
    val operationGroupsRow: OperationGroupsRow =
      OperationGroupsRow(operation_group_hash, block_id, branch, source, signature)
  }
  */

  /*No records linking blocks to OperationGroups to ballots
  "ballotsToDatabaseRows" should "Turn Block objects into operationGroup rows ready to be inserted " in {
    val hash: String = "BKimLo1EJGd1eY9u6siaszunatAKsSYxzcedq2tG6Vni95dmH8n"
    val net_id: String = "NetXj4yEEKnjaK8"
    val operations: Seq[Seq[BlockOperationMetadata]] = Seq(Seq())
    val protocol: String = "ProtoALphaALphaALphaALphaALphaALphaALphaALphaDdp3zK"
    val level: Int = 7
    val proto: Int = 1
    val predecessor: String = "BMdK2ymoBh9AjPS1WUvGs33NEcmkZrW2JbtFQH3F6KdDWSgsfeP"
    val timestamp: java.sql.Timestamp = Timestamp.valueOf("2017-11-20 01:52:22.0")
    val validation_pass: Int = 1
    val operations_hash: String = "LLoauvJSk6ugA3kjb5xW8P2nLNY9Gb3K2XsoXwH9tjfgmfZZJx9aY"
    val fitness: Seq[String] = Seq("00,000000000000000e")
    val data: String = "0002d423a235a78e707d42dfdacf4b916189c7c35f66f96cff111798c0e972ce4" +
      "93b11e4b05629fe273057cc7b562c50380fc379240a5850a57917eb22e760f46b360a1a8752e1ff126" +
      "b0de43b3534e4a18109c24db6e0734fad3c697b207fd601645d56bf6c9cbc1a0a"

    val metaData: BlockMetadata =
      BlockMetadata(hash, net_id, operations, protocol, level, proto, predecessor,
        timestamp, validation_pass, operations_hash, fitness, data)
    val operationGroups: List[OperationGroup] = List()
    val block: Block = Block(metaData, operationGroups)

    val operation_group_hash: String = ""
    val block_id: String = ""
    val branch: String = ""
    val source: Option[String]
    val signature: Option[String]
    val operationGroupsRow: OperationGroupsRow =
      OperationGroupsRow(operation_group_hash, block_id, branch, source, signature)
  }
  */


  "seedNonceRevelationsToDatabaseRows" should "Turn Block objects into operationGroup rows ready to be inserted " in {
    //Block Data
    val hash: String = "BMKRdov5EykVPdyZ984Kk5vwLVkFf8ZMxXGW7CWri6DB5JT73Zk"
    val net_id: String = "NetXj4yEEKnjaK8"
    val operations: Seq[Seq[BlockOperationMetadata]] = Seq(Seq())
    val protocol: String = "ProtoALphaALphaALphaALphaALphaALphaALphaALphaDdp3zK"
    val level: Int = 66
    val proto: Int = 1
    val predecessor: String = "BMEcLg8QoDeTagYJ1E6zwFF4CjMAroFv9vnbfayhsRsu81J9JBb"
    val timestamp: java.sql.Timestamp = Timestamp.valueOf("2017-11-20 02:59:02.0")
    val validation_pass: Int = 1
    val operations_hash: String = "LLoZfLdu4hHXWcyytTwSEGN5rfLMca3PYUL4SZXpfcAhn31LrJToa"
    val fitness: Seq[String] = Seq("00,00000000000002fb")
    val data: String = "00015eb5ccf53225dc65e340d4f492412c2500fa080e0529227461ba303ca9bf" +
      "be5b91fc369f82504c3bc503b995e797abd2bd51752460490a4c3dced23121833e16189cfe177dd3f" +
      "bf398b5f4441a9e83bebb7788751e5b4596ce1903f9e1732dd89fc80c2c635be004"

    val metaData: BlockMetadata =
      BlockMetadata(hash, net_id, operations, protocol, level, proto, predecessor,
        timestamp, validation_pass, operations_hash, fitness, data)

    //OperationGroup Data
    val operation_group_hash: String = "oo2CjLxKkzUQnczWsXupKwwK3y8E9tmai4eGqAN9egjvXkCtFTU"
    val block_id: String = "BMKRdov5EykVPdyZ984Kk5vwLVkFf8ZMxXGW7CWri6DB5JT73Zk"
    val branch: String = "BMEcLg8QoDeTagYJ1E6zwFF4CjMAroFv9vnbfayhsRsu81J9JBb"
    val source: Option[String] = None
    val signature: Option[String] = None

    //Seed Nonce Revelation Data
    val seedNonceRevealationId: Int = 0
    val seed_level: Int = 54
    val nonce: String = "a5411c5ae1c1893031ea06b9f13acbf1728d11a3ab52c0fdc3298d35080ffcb0"

    val operation: Operation =
      Operation(Some("seed_nonce_revelation"), None, None, None, None, None,
        None, None, None, None, None, None, None, Some(seed_level), Some(nonce), None)

    val operationList: List[Operation] = List(operation)
    val operationGroup: OperationGroup =
      OperationGroup(operation_group_hash, branch, source, operationList, signature)
    val operationGroups: List[OperationGroup] = List(operationGroup)
    val block: Block = Block(metaData, operationGroups)

    val seedNonceRevealationsRow: List[SeedNonceRevealationsRow] =
      List(SeedNonceRevealationsRow(seedNonceRevealationId, operation_group_hash, seed_level, nonce))

    seedNonceRevelationsToDatabaseRows(block) should be (seedNonceRevealationsRow)
  }

  "faucetTransactionsToDatabaseRows" should "Turn Block objects into operationGroup rows ready to be inserted " in {
    //Block Data
    val hash: String = "BMdK2ymoBh9AjPS1WUvGs33NEcmkZrW2JbtFQH3F6KdDWSgsfeP"
    val net_id: String = "NetXj4yEEKnjaK8"
    val operations: Seq[Seq[BlockOperationMetadata]] = Seq(Seq())
    val protocol: String = "ProtoALphaALphaALphaALphaALphaALphaALphaALphaDdp3zK"
    val level: Int = 6
    val proto: Int = 1
    val predecessor: String = "BLvBnCtHDE1idazjqeSpVqRP9uXt8oKyqTAKbmDVrgQmxUNFaNC"
    val timestamp: java.sql.Timestamp = Timestamp.valueOf("2017-11-20 01:50:22.0")
    val validation_pass: Int = 1
    val operations_hash: String = "LLob4UkjgPFWKsKMkUgb5czPHdEzr37XVX3inLZeNonhUVLzvx4ob"
    val fitness: Seq[String] = Seq("00,0000000000000006")
    val data: String = "00011f6c9ba9fa7ec33266b5a05312985286766b952d83c2ab99c3728cc2f0fb" +
      "91f5ef4f4e55dad4582ba8230f4f8df926966f82e4ce0cc638a60c0dc57f4bb9ffef54d086f18c0d9" +
      "946b50293093fa69ebf8623b9a866295e36f25845aa2be7a5255af0c2b565935305"

    val metaData: BlockMetadata =
      BlockMetadata(hash, net_id, operations, protocol, level, proto, predecessor,
        timestamp, validation_pass, operations_hash, fitness, data)

    //OperationGroup Data
    val operation_group_hash: String = "opVp1CQsZpHaU4rqvFbSVsTLBiEkTZKfW1t3h9jHjqS4kBTKwUr"
    val block_id: String = "BMdK2ymoBh9AjPS1WUvGs33NEcmkZrW2JbtFQH3F6KdDWSgsfeP"
    val branch: String = "BLvBnCtHDE1idazjqeSpVqRP9uXt8oKyqTAKbmDVrgQmxUNFaNC"
    val source: Option[String] = None
    val signature: Option[String] = None

    //Faucet Transactions Data
    val faucetTransactionId = 0
    val id: String = "tz1ZMgbteMHU3gqC5iFmNdWRxR91AfsU1rjk"
    val nonce: String = "8bebc0be39c42c76a87cc33307a85306"

    val operation: Operation =
      Operation(Some("faucet"), None, None, None, None, None,
        None, None, None, None, None, None, None, None, Some(nonce), Some(id))

    val operationList: List[Operation] = List(operation)
    val operationGroup: OperationGroup =
      OperationGroup(operation_group_hash, branch, source, operationList, signature)
    val operationGroups: List[OperationGroup] = List(operationGroup)
    val block: Block = Block(metaData, operationGroups)

    val faucetTransactionsRow: List[FaucetTransactionsRow] =
      List(FaucetTransactionsRow(faucetTransactionId, operation_group_hash, id, nonce))

    faucetTransactionsToDatabaseRows(block) should be (faucetTransactionsRow)
  }
  */
}
