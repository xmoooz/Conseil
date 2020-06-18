package tech.cryptonomic.conseil.indexer.tezos

import tech.cryptonomic.conseil.common.tezos.TezosTypes._
import tech.cryptonomic.conseil.common.tezos.VotingOperations._
import scala.concurrent.{ExecutionContext, Future}
import com.typesafe.scalalogging.LazyLogging
import slick.jdbc.PostgresProfile.api.Database

/** Process blocks and voting data to compute details for
  * the governance-related cycles
  */
object TezosGovernanceOperations extends LazyLogging {

  /** Collects all relevant data for the governance
    * voting process
    *
    * @param hash identifies a specific block
    * @param metadata the block metadata
    * @param proposalId a specific proposal under evaluation
    * @param allRolls ballot rolls up to this block for the proposal
    * @param rollsPerLevel ballot rolls included in this specific block
    * @param ballotsPerCycle how many ballots for a whole cycle
    * @param ballotsPerLevel how many ballots for the single block level
    */
  case class GovernanceAggregate(
      hash: BlockHash,
      metadata: BlockHeaderMetadata,
      proposalId: Option[ProtocolId],
      allRolls: VoteRollsCounts,
      rollsPerLevel: VoteRollsCounts,
      ballotsPerCycle: Option[Voting.BallotCounts],
      ballotsPerLevel: Option[Voting.BallotCounts]
  )

  //wrappings to make the distinction between two different counts
  private case class BallotsPerCycle(counts: Voting.BallotCounts) extends AnyVal
  private case class BallotsPerLevel(counts: Voting.BallotCounts) extends AnyVal

  /** Collects rolls of votes counted during ballots
    *
    * @param yay how many yays
    * @param nay how many nays
    * @param pass how many passes
    */
  case class VoteRollsCounts(yay: Int, nay: Int, pass: Int)

  /** Extracts and aggregates the information relative to different
    * governance periods, restricted to a selection of blocks.
    * We use the input voting rolls and additional data retrieved both
    * from the tezos node, for the currently examined blocks, and from
    * previously indexed voting data entries, extracted from db.
    * The input keys identifies all blocks we're interested in, independently
    * from having any actual rolls data in the associated map value.
    *
    * @param db the reference database
    * @param nodeOperator the operator to get information from the remore tezos node
    * @param bakerRollsByBlock blocks of interest, with any rolls data available
    * @return the computed aggregate data
    */
  def extractGovernanceAggregations(
      db: Database,
      nodeOperator: TezosNodeOperator
  )(
      bakerRollsByBlock: Map[Block, List[Voting.BakerRolls]]
  )(implicit ec: ExecutionContext): Future[List[GovernanceAggregate]] = {
    // DANGER Will Robinson!
    // We assume in the rest of the code that we won't be handling the genesis metadata.
    // The original arguments must not be re-used directly to ensure such guarantee
    val (blocks, bakerRollsByLevel) = {
      val nonGenesis = bakerRollsByBlock.filterNot { case (block, _) => block.data.metadata == GenesisMetadata }
      (nonGenesis.keys.toList, nonGenesis.map { case (block, rolls) => block.data.header.level -> rolls })
    }

    /* Optimize the remote query for proposals by pre-selecting the blocks */
    val blocksInActiveVotingPeriod = blocks.filter(votingPeriodIn(activePeriods)).map(_.data)

    /* Here we retrieve node data needed to collect any voting period's interesting numbers.
     * We want proposals to identify blocks during a voting period, and votes cast in the blocks.
     * From those we create data aggregates with break-down information per level and
     * convert those into database rows to be stored.
     */
    for {
      activeProposals <- nodeOperator.getActiveProposals(blocksInActiveVotingPeriod)
      proposalsMap = activeProposals.toMap
      //pair blocks and proposals
      blocksAndProposals = blocks.map(block => block -> proposalsMap.getOrElse(block.data.hash, None)).toMap
      ballots <- nodeOperator.getVotes(blocksAndProposals.keys.toList)
      aggregates <- aggregateData(db)(
        blocksAndProposals,
        ballots.toMap,
        bakerRollsByLevel
      )
    } yield aggregates

  }

  /** We take basic governance data and the blocks storing that
    * with the goal of deriving more detailed aggregate data on
    * the voting process.
    * The computation needs to access the database to fetch essential
    * ballot data
    *
    * SAFETY NOTE: make sure that the blocks passed to this method have
    *              have a proper metadata value, different from the genesis one
    *
    * @param db needed to fetch previously stored voting data
    * @param activeProposalsBlocks blocks we want to have governance data for, associated to the protocol of the proposal
    * @param activeProposalsBallots votes cast on the blocks of interest,
    *                         the keys should be a superset of the `activeProposalsBlocks` keys,
    *                         so that the value is available for each of those blocks.
    * @param rollsByLevel listings of baker rolls involved, indexed per level, we expect all levels for the
    *                         `proposalsBlocks` to be available, plus the level immediately before this batch.
    *                          ideally this would be a `Level => List[Voting.BakerRolls]` (i.e. a total function)
    * @param ec needed to compose concurrent operations
    * @return aggregated data
    */
  def aggregateData(db: Database)(
      activeProposalsBlocks: Map[Block, Option[ProtocolId]],
      activeProposalsBallots: Map[Block, List[Voting.Ballot]],
      rollsByLevel: Map[Int, List[Voting.BakerRolls]]
  )(
      implicit ec: ExecutionContext
  ): Future[List[GovernanceAggregate]] = {

    //local functions, should simplify reading the calling code
    def countBallotsPerLevel(block: Block) =
      TezosDatabaseOperations.getBallotOperationsForLevel(block.data.header.level)

    def countBallotsPerCycle(cycle: Int) =
      TezosDatabaseOperations.getBallotOperationsForCycle(cycle)

    // as stated, this is a runtime failure if the block contains the wrong metadata
    def proposalHashesPerCycle(block: Block) = block.data.metadata match {
      case BlockHeaderMetadata(_, _, _, _, _, level) =>
        TezosDatabaseOperations.getProposalOperationHashesByCycle(level.cycle)
    }

    logger.info("Searching for governance data in voting period...")

    //find blocks for a specific proposal under scrutiny, relevant for counting ballots
    val votingBlocks = activeProposalsBlocks.collect { case (block, Some(protocol)) => block }.toList

    logger.info(
      "There are {} blocks related to testing vote and proposal vote periods.",
      if (votingBlocks.nonEmpty) String.valueOf(votingBlocks.size) else "no"
    )

    //main algorithm
    val cycles = activeProposalsBlocks.keys
      .filter(votingPeriodIn(ballotPeriods))
      .map(_.data.metadata)
      .collect {
        case md: BlockHeaderMetadata => md.level.cycle
      }
      .toList
      .distinct

    val cycleBallotCountsResult =
      Future
        .traverse(cycles) { cycle =>
          db.run(countBallotsPerCycle(cycle)).map(cycle -> BallotsPerCycle(_))
        }
        .map(_.toMap)

    val levelBallotCountsResult =
      Future
        .traverse(
          activeProposalsBlocks.keys.filter(votingPeriodIn(ballotPeriods))
        ) { block =>
          db.run(countBallotsPerLevel(block)).map(block -> BallotsPerLevel(_))
        }
        .map(_.toMap)

    val proposalCountsResult = Future.traverse(
      activeProposalsBlocks.keys.filter(votingPeriodIs(VotingPeriod.proposal))
    ) { block =>
      db.run(proposalHashesPerCycle(block).map(block -> _))
    }

    for {
      levelCountsMap <- levelBallotCountsResult
      cycleCountsMap <- cycleBallotCountsResult
      proposalCounts <- proposalCountsResult
    } yield
      fillAggregates(
        activeProposalsBlocks,
        rollsByLevel,
        activeProposalsBallots,
        levelCountsMap,
        cycleCountsMap,
        proposalCounts.toMap
      )
  }

  /* Having all data ready, we can process per block,
   * extract the numbers returning a collector object.
   */
  private def fillAggregates(
      proposalsBlocks: Map[Block, Option[ProtocolId]],
      rollsByLevel: Map[Int, List[Voting.BakerRolls]],
      ballots: Map[Block, List[Voting.Ballot]],
      ballotCountsPerLevel: Map[Block, BallotsPerLevel],
      ballotCountsPerCycle: Map[Int, BallotsPerCycle],
      proposalCountsByBlock: Map[Block, Map[ProtocolId, Int]] //comes from individual operations on the block
  ): List[GovernanceAggregate] =
    proposalsBlocks.toList.flatMap {
      case (block, currentProposal) =>
        val rollsAtLevel = rollsByLevel.getOrElse(block.data.header.level, List.empty)
        val rollsAtPreviousLevel = rollsByLevel.getOrElse(block.data.header.level - 1, List.empty)
        val rollsForBlockLevel = rollsAtLevel.diff(rollsAtPreviousLevel)
        val ballot = ballots.getOrElse(block, List.empty)
        val ballotCountPerCycle = block.data.metadata match {
          case md: BlockHeaderMetadata => ballotCountsPerCycle.get(md.level.cycle).map(_.counts)
          case GenesisMetadata => None
        }
        val ballotCountPerLevel = ballotCountsPerLevel.get(block).map(_.counts)
        val proposalCounts = proposalCountsByBlock.getOrElse(block, Map.empty).toList

        val allRolls = countRolls(rollsAtLevel, ballot)
        val levelRolls = countRolls(rollsForBlockLevel, ballot)

        /* Here we collect a row for the block being considered
         * to get voting data for the periods with a specific proposal
         * under scrutiny: testing vote, testing, promotion.
         * In addition, we have many rows appended that comes
         * from operations during the proposal period, contained
         * in the block, which is now assumed to have no current proposal.
         * The previous considerations would make it impossible to have
         * the same proposal protocol and block hash for the two kind
         * of entries just described. We only have one or the other, by
         * construction, as the chain would reject any proposal with the
         * same protocol as the one under voting during the ballots phases.
         */
        block.data.metadata match {
          case metadata: BlockHeaderMetadata =>
            val activeProposalAggregate = currentProposal.map(
              proposal =>
                GovernanceAggregate(
                  block.data.hash,
                  metadata,
                  Some(proposal),
                  allRolls,
                  levelRolls,
                  ballotCountPerCycle,
                  ballotCountPerLevel
                )
            )
            val proposalOperationsAggregates =
              proposalCounts //these come from all individual proposal operations during the proposal period
              .filterNot {
                /* this should never be the case: the proposal currently under evaluation
                 * should not be proposed in operations of the same block
                 */
                case (proposal, _) => currentProposal.contains(proposal)
              }.map {
                case (proposalProtocol, count) =>
                  GovernanceAggregate(
                    block.data.hash,
                    metadata.copy(voting_period_kind = VotingPeriod.proposal), //we know these are from operations
                    Some(proposalProtocol),
                    allRolls,
                    levelRolls,
                    Some(Voting.BallotCounts(count, 0, 0)),
                    ballotCountPerLevel
                  )
              }
            activeProposalAggregate.toList ::: proposalOperationsAggregates
          case GenesisMetadata =>
            //case handled to satisfy the compiler, should never run by design
            List.empty[GovernanceAggregate]
        }

    }

  /* Will scan the ballots to count all rolls associated with each vote outcome */
  private def countRolls(listings: List[Voting.BakerRolls], ballots: List[Voting.Ballot]): VoteRollsCounts = {
    val (yays, nays, passes) = ballots.foldLeft((0, 0, 0)) {
      case ((yays, nays, passes), votingBallot) =>
        val rolls = listings.find(_.pkh == votingBallot.pkh).fold(0)(_.rolls)
        votingBallot.ballot match {
          case Voting.Vote("yay") => (yays + rolls, nays, passes)
          case Voting.Vote("nay") => (yays, nays + rolls, passes)
          case Voting.Vote("pass") => (yays, nays, passes + rolls)
          case Voting.Vote(notSupported) =>
            logger.error("Not supported vote type {}", notSupported)
            (yays, nays, passes)
        }
    }
    VoteRollsCounts(yays, nays, passes)
  }
}