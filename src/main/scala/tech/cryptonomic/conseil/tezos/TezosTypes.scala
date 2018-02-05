package tech.cryptonomic.conseil.tezos

/**
  * Classes used for deserializing Tezos node RPC results.
  */
object TezosTypes {

  case class BlockMetadata(
                            hash: String,
                            net_id: String,
                            operations: Seq[Seq[BlockOperationMetadata]],
                            protocol: String,
                            level: Int,
                            proto: Int,
                            predecessor: String,
                            timestamp: java.sql.Timestamp,
                            validation_pass: Int,
                            operations_hash: String,
                            fitness: Seq[String],
                            data: String
                  )

  case class BlockOperationMetadata(
                           hash: String,
                           branch: String,
                           data: String
                           )

  case class TezosScriptExpression(
                                  prim: Option[String],
                                  args: Option[List[String]]
                                  )

  case class Operation(
                      kind: Option[String],
                      amount: Option[scala.math.BigDecimal],
                      destination: Option[String],
                      //parameters: Option[Object],
                      managerPubKey: Option[String],
                      balance: Option[BigDecimal],
                      spendable: Option[Boolean],
                      delegatable: Option[Boolean],
                      delegate: Option[String],
                      //script: Option[Any],
                      block: Option[String],
                      slot: Option[Int],
                      period: Option[Int],
                      proposal: Option[String],
                      ballot: Option[String],
                      level: Option[Int],
                      nonce: Option[String],
                      id: Option[String]
                      )

  case class OperationGroup(
                           hash: String,
                           branch: String,
                           source: Option[String],
                           operations: List[Operation],
                           signature: Option[String]
                           )

  case class OperationGroupContainer(
                                    ok: List[List[OperationGroup]]
                                    )

  case class AccountDelegate(
                            setable: Boolean,
                            value: Option[String]
                            )

  case class Account(
                    manager: String,
                    balance: scala.math.BigDecimal,
                    spendable: Boolean,
                    delegate: AccountDelegate,
                    script: Option[Any],
                    counter: Int
                    )

  case class AccountContainer(
                             ok: Account
                             )

  case class AccountsContainer(
                     ok: List[String]
                     )

  case class AccountsWithBlockHash(
                                    block_hash: String,
                                    accounts: Map[String, Account]
                                  )

  case class Block(
                    metadata: BlockMetadata,
                    operationGroups: List[OperationGroup]
                  )

  case class ForgedOperationContainer(ok: Option[SuccessfulForgedOperation], error: Option[Any])

  case class SuccessfulForgedOperation(operation: String)

  case class AppliedOperationContainer(ok: Option[AppliedOperation], error: Option[Any])

  case class AppliedOperation(contracts: Array[String])

  case class InjectedOperationContainer(ok: Option[InjectedOperation], error: Option[Any])

  case class InjectedOperation(injectedOperation: String)

}