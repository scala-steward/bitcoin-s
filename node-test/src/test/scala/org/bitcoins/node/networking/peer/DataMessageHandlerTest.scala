package org.bitcoins.node.networking.peer

import org.bitcoins.core.config.SigNet
import org.bitcoins.core.currency._
import org.bitcoins.core.gcs.{FilterType, GolombFilter}
import org.bitcoins.core.p2p._
import org.bitcoins.core.protocol.CompactSizeUInt
import org.bitcoins.core.protocol.blockchain.{Block, BlockHeader, MerkleBlock}
import org.bitcoins.core.protocol.transaction.Transaction
import org.bitcoins.crypto.DoubleSha256Digest
import org.bitcoins.node._
import org.bitcoins.server.BitcoinSAppConfig
import org.bitcoins.testkit.BitcoinSTestAppConfig
import org.bitcoins.testkit.node.NodeUnitTest
import org.bitcoins.testkit.node.fixture.SpvNodeConnectedWithBitcoindV19
import org.scalatest.FutureOutcome

import scala.concurrent.{Future, Promise}

class DataMessageHandlerTest extends NodeUnitTest {

  /** Wallet config with data directory set to user temp directory */
  implicit override protected def getFreshConfig: BitcoinSAppConfig =
    BitcoinSTestAppConfig.getSpvWithEmbeddedDbTestConfig(pgUrl)

  override type FixtureParam = SpvNodeConnectedWithBitcoindV19

  override def withFixture(test: OneArgAsyncTest): FutureOutcome =
    withSpvNodeConnectedToBitcoindV19(test)

  it must "catch errors and not fail when processing an invalid payload" in {
    param: SpvNodeConnectedWithBitcoindV19 =>
      val SpvNodeConnectedWithBitcoindV19(spv, _) = param

      for {
        sender <- spv.peerMsgSenderF
        chainApi <- spv.chainApiFromDb()
        dataMessageHandler = DataMessageHandler(chainApi)(spv.executionContext,
                                                          spv.nodeAppConfig,
                                                          spv.chainConfig)

        // Use signet genesis block header, this should be invalid for regtest
        invalidPayload =
          HeadersMessage(Vector(SigNet.chainParams.genesisBlock.blockHeader))

        // Validate that it causes a failure
        _ <- recoverToSucceededIf[RuntimeException](
          chainApi.processHeaders(invalidPayload.headers))

        // Verify we handle the payload correctly
        _ <- dataMessageHandler.handleDataPayload(invalidPayload, sender)
      } yield succeed
  }

  it must "verify OnMerkleBlock callbacks are executed" in {
    param: FixtureParam =>
      val SpvNodeConnectedWithBitcoindV19(spv, bitcoind) = param

      val resultP: Promise[(MerkleBlock, Vector[Transaction])] = Promise()

      val callback: OnMerkleBlockReceived = {
        (merkle: MerkleBlock, txs: Vector[Transaction]) =>
          Future {
            resultP.success((merkle, txs))
            ()
          }
      }

      for {
        sender <- spv.peerMsgSenderF

        txId <- bitcoind.sendToAddress(junkAddress, 1.bitcoin)
        tx <- bitcoind.getRawTransactionRaw(txId)
        _ <- bitcoind.generateToAddress(blocks = 1, junkAddress)
        merkleBlock <- bitcoind.getTxOutProof(Vector(txId))

        payload1 = MerkleBlockMessage(merkleBlock)
        payload2 = TransactionMessage(tx)

        nodeCallbacks = NodeCallbacks(onMerkleBlockReceived = Vector(callback))
        _ = spv.nodeAppConfig.addCallbacks(nodeCallbacks)

        dataMessageHandler =
          DataMessageHandler(genesisChainApi)(spv.executionContext,
                                              spv.nodeAppConfig,
                                              spv.chainConfig)
        _ <- dataMessageHandler.handleDataPayload(payload1, sender)
        _ <- dataMessageHandler.handleDataPayload(payload2, sender)
        result <- resultP.future
      } yield assert(result == ((merkleBlock, Vector(tx))))
  }

  it must "verify OnBlockReceived callbacks are executed" in {
    param: FixtureParam =>
      val SpvNodeConnectedWithBitcoindV19(spv, bitcoind) = param

      val resultP: Promise[Block] = Promise()

      val callback: OnBlockReceived = (block: Block) => {
        Future {
          resultP.success(block)
          ()
        }
      }
      for {
        sender <- spv.peerMsgSenderF

        hash <- bitcoind.generateToAddress(blocks = 1, junkAddress).map(_.head)
        block <- bitcoind.getBlockRaw(hash)

        payload = BlockMessage(block)

        nodeCallbacks = NodeCallbacks.onBlockReceived(callback)
        _ = spv.nodeAppConfig.addCallbacks(nodeCallbacks)

        dataMessageHandler =
          DataMessageHandler(genesisChainApi)(spv.executionContext,
                                              spv.nodeAppConfig,
                                              spv.chainConfig)
        _ <- dataMessageHandler.handleDataPayload(payload, sender)
        result <- resultP.future
      } yield assert(result == block)
  }

  it must "verify OnBlockHeadersReceived callbacks are executed" in {
    param: FixtureParam =>
      val SpvNodeConnectedWithBitcoindV19(spv, bitcoind) = param

      val resultP: Promise[Vector[BlockHeader]] = Promise()

      val callback: OnBlockHeadersReceived = (headers: Vector[BlockHeader]) => {
        Future {
          if (!resultP.isCompleted) {
            resultP.success(headers)
          }
          ()
        }
      }

      for {
        sender <- spv.peerMsgSenderF

        hash <- bitcoind.generateToAddress(blocks = 1, junkAddress).map(_.head)
        header <- bitcoind.getBlockHeaderRaw(hash)

        payload = HeadersMessage(CompactSizeUInt.one, Vector(header))

        callbacks = NodeCallbacks.onBlockHeadersReceived(callback)

        _ = spv.nodeAppConfig.addCallbacks(callbacks)
        dataMessageHandler =
          DataMessageHandler(genesisChainApi)(spv.executionContext,
                                              spv.nodeAppConfig,
                                              spv.chainConfig)

        _ <- dataMessageHandler.handleDataPayload(payload, sender)
        result <- resultP.future
      } yield assert(result == Vector(header))
  }

  it must "verify OnCompactFilterReceived callbacks are executed" in {
    param: FixtureParam =>
      val SpvNodeConnectedWithBitcoindV19(spv, bitcoind) = param

      val resultP: Promise[Vector[(DoubleSha256Digest, GolombFilter)]] =
        Promise()
      val callback: OnCompactFiltersReceived = {
        (filters: Vector[(DoubleSha256Digest, GolombFilter)]) =>
          Future {
            resultP.success(filters)
            ()
          }
      }
      for {
        sender <- spv.peerMsgSenderF

        hash <- bitcoind.generateToAddress(blocks = 1, junkAddress).map(_.head)
        filter <- bitcoind.getBlockFilter(hash, FilterType.Basic)

        payload =
          CompactFilterMessage(FilterType.Basic, hash.flip, filter.filter.bytes)

        nodeCallbacks = NodeCallbacks.onCompactFilterReceived(callback)
        _ = spv.nodeAppConfig.addCallbacks(nodeCallbacks)
        dataMessageHandler =
          DataMessageHandler(genesisChainApi)(spv.executionContext,
                                              spv.nodeAppConfig,
                                              spv.chainConfig)

        _ <- dataMessageHandler.handleDataPayload(payload, sender)
        result <- resultP.future
      } yield assert(result == Vector((hash.flip, filter.filter)))
  }
}
