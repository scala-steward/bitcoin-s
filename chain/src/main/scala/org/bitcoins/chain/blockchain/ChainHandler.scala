package org.bitcoins.chain.blockchain

import org.bitcoins.chain.ChainVerificationLogger
import org.bitcoins.chain.config.ChainAppConfig
import org.bitcoins.chain.models._
import org.bitcoins.chain.pow.Pow
import org.bitcoins.core.api.chain.ChainQueryApi.FilterResponse
import org.bitcoins.core.api.chain.db._
import org.bitcoins.core.api.chain.{ChainApi, FilterSyncMarker}
import org.bitcoins.core.gcs.FilterHeader
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.p2p.CompactFilterMessage
import org.bitcoins.core.protocol.BlockStamp
import org.bitcoins.core.protocol.blockchain.BlockHeader
import org.bitcoins.core.util.{FutureUtil, NetworkUtil}
import org.bitcoins.crypto.{CryptoUtil, DoubleSha256DigestBE}

import scala.annotation.tailrec
import scala.concurrent._

/** Chain Handler is meant to be the reference implementation of
  * [[ChainApi ChainApi]], this is the entry point in to the chain project.
  *
  * This implementation of [[ChainApi]] reads all values directly from the
  * database. If you want an optimized version that caches headers locally
  * please see [[ChainHandlerCached]]
  *
  * @param blockHeaderDAO
  *   block header DB
  * @param filterHeaderDAO
  *   filter header DB
  * @param filterDAO
  *   filter DB
  * @param blockFilterCheckpoints
  *   compact filter checkpoints for filter header verification in form of a map
  *   (block header hash -> filter header hash)
  * @param chainConfig
  *   config file
  */
class ChainHandler(
    val blockHeaderDAO: BlockHeaderDAO,
    val filterHeaderDAO: CompactFilterHeaderDAO,
    val filterDAO: CompactFilterDAO,
    val stateDAO: ChainStateDescriptorDAO,
    val blockFilterCheckpoints: Map[DoubleSha256DigestBE, DoubleSha256DigestBE]
)(implicit val chainConfig: ChainAppConfig, executionContext: ExecutionContext)
    extends ChainApi
    with ChainVerificationLogger {

  /** @inheritdoc */
  override def getBlockCount(): Future[Int] = {
    blockHeaderDAO.bestHeight.map { height =>
      height
    }
  }

  /** Given a set of blockchains, determines which one has the best header */
  protected def getBestBlockHeaderHelper(
      chains: Vector[Blockchain]
  ): BlockHeaderDb = {
    logger.trace(
      s"Finding best block hash out of chains.length=${chains.length}"
    )
    // https://bitcoin.org/en/glossary/block-chain
    val groupedChains = chains.groupBy(_.tip.chainWork)
    val maxWork = groupedChains.keys.max
    val chainsByWork = groupedChains(maxWork)

    val bestHeader: BlockHeaderDb = {
      if (chainsByWork.isEmpty) {
        // This should never happen
        val errMsg = s"Did not find blockchain with work $maxWork"
        logger.error(errMsg)
        throw new RuntimeException(errMsg)
      } else if (chainsByWork.length == 1) {
        chainsByWork.head.tip
      } else {
        val tips = chainsByWork
          .map(_.tip.hashBE.hex)
          .mkString(", ")
        logger.warn(
          s"We have multiple competing blockchains with same work, selecting by time: $tips"
        )
        // since we have same chainwork, just take the oldest tip
        // as that's "more likely" to have been propagated first
        // and had more miners building on top of it
        chainsByWork.minBy(_.tip.time).tip
      }
    }
    bestHeader
  }

  override def getBestBlockHeader(): Future[BlockHeaderDb] = {
    val tipsF: Future[Vector[BlockHeaderDb]] = getBestChainTips()
    for {
      tips <- tipsF
      chains = tips.map(t => Blockchain.fromHeaders(Vector(t)))
    } yield {
      getBestBlockHeaderHelper(chains)
    }
  }

  /** @inheritdoc */
  override def getHeader(
      hash: DoubleSha256DigestBE
  ): Future[Option[BlockHeaderDb]] = {
    getHeaders(Vector(hash)).map(_.head)
  }

  override def getHeaders(
      hashes: Vector[DoubleSha256DigestBE]
  ): Future[Vector[Option[BlockHeaderDb]]] = {
    blockHeaderDAO.findByHashes(hashes)
  }

  protected def processHeadersWithChains(
      headers: Vector[BlockHeader],
      blockchains: Vector[Blockchain]
  ): Future[ChainApi] = {
    if (headers.isEmpty) {
      Future.successful(this)
    } else {
      val headersWeAlreadyHave = blockchains.flatMap(_.headers)

      // if we already have the header don't process it again
      val filteredHeaders = headers.filterNot(h =>
        headersWeAlreadyHave.exists(_.hashBE == h.hashBE))

      if (filteredHeaders.isEmpty) {
        return Future.failed(
          DuplicateHeaders(s"Received duplicate block headers.")
        )
      }

      val blockchainUpdates: Vector[BlockchainUpdate] = {
        Blockchain.connectHeadersToChains(
          headers = filteredHeaders,
          blockchains = blockchains,
          chainParams = chainConfig.chain
        )
      }

      val successfullyValidatedHeaders = blockchainUpdates
        .flatMap(_.successfulHeaders)

      val headersToBeCreated: Vector[BlockHeaderDb] = {
        // During reorgs, we can be sent a header twice
        successfullyValidatedHeaders.distinct
      }

      if (headersToBeCreated.isEmpty) {
        // this means we are given zero headers that were valid.
        // Return a failure in this case to avoid issue 2365
        // https://github.com/bitcoin-s/bitcoin-s/issues/2365
        Future.failed(
          InvalidBlockHeader(
            s"Failed to connect any headers to our internal chain state, failures=$blockchainUpdates"
          )
        )
      } else {
        val chains = blockchainUpdates.map(_.blockchain)

        val createdF = blockHeaderDAO.createAll(headersToBeCreated)

        val newChainHandler = ChainHandler(
          blockHeaderDAO,
          filterHeaderDAO,
          filterDAO,
          stateDAO,
          blockFilterCheckpoints = blockFilterCheckpoints
        )

        createdF.map { headers =>
          if (chainConfig.callBacks.onBlockHeaderConnected.nonEmpty) {
            val headersWithHeight: Vector[(Int, BlockHeader)] = {
              headersToBeCreated.reverseIterator.map(h =>
                (h.height, h.blockHeader))
            }.toVector

            chainConfig.callBacks
              .executeOnBlockHeaderConnectedCallbacks(headersWithHeight)
          }
          chains.foreach { c =>
            logger.info(
              s"Processed headers from height=${c.height - headers.length} to ${c.height}. Best hash=${c.tip.hashBE.hex}"
            )
          }
          newChainHandler
        }
      }
    }
  }

  /** @inheritdoc */
  override def processHeaders(
      headers: Vector[BlockHeader]
  ): Future[ChainApi] = {
    val blockchainsF = blockHeaderDAO.getBlockchains()
    val resultF = for {
      blockchains <- blockchainsF
      newChainApi <-
        processHeadersWithChains(headers = headers, blockchains = blockchains)
    } yield newChainApi

    resultF
  }

  /** @inheritdoc
    */
  override def getBestBlockHash(): Future[DoubleSha256DigestBE] = {
    getBestBlockHeader().map(_.hashBE)
  }

  override def getBestChainTips(): Future[Vector[BlockHeaderDb]] =
    blockHeaderDAO.getBestChainTips

  /** @inheritdoc */
  override def nextBlockHeaderBatchRange(
      prevStopHash: DoubleSha256DigestBE,
      stopHash: DoubleSha256DigestBE,
      batchSize: Int
  ): Future[Option[FilterSyncMarker]] = {
    if (prevStopHash == DoubleSha256DigestBE.empty) {
      getHeadersAtHeight(batchSize - 1).map { headers =>
        if (headers.length == 1) {
          val fsm = FilterSyncMarker(0, headers.head.hash)
          Some(fsm)
        } else {
          logger.warn(
            s"ChainHandler.nextBlockHeaderBatchRange() did not find a single header, got zero or multiple=$headers"
          )
          None
        }
      }
    } else if (prevStopHash == stopHash) {
      // means are in sync
      Future.successful(None)
    } else {
      val candidateStartHeadersF = getImmediateChildren(prevStopHash)
      val stopBlockHeaderOptF = getHeader(stopHash)

      for {
        candidateStartHeaders <- candidateStartHeadersF
        stopBlockHeaderOpt <- stopBlockHeaderOptF
        stopBlockHeader = {
          stopBlockHeaderOpt.getOrElse {
            sys.error(
              s"Could not find block header associated with stopHash=$stopHash"
            )
          }
        }
        fsmOptVec <- {
          Future.traverse(candidateStartHeaders) { candidateHeader =>
            getFilterSyncStopHash(
              candidateStartHeader = candidateHeader,
              stopBlockHeader = stopBlockHeader,
              batchSize = batchSize
            )
          }
        }
      } yield {
        val flatten = fsmOptVec.flatten
        if (flatten.length > 1) {
          logger.warn(
            s"Multiple filter sync makers!!! choosing first one fsmOptVec=$fsmOptVec"
          )
          flatten.headOption
        } else {
          flatten.headOption
        }
      }
    }
  }

  /** Retrieves immediately children of the given blockHash */
  private def getImmediateChildren(
      blockHashBE: DoubleSha256DigestBE
  ): Future[Vector[BlockHeaderDb]] = {
    getHeader(blockHashBE).flatMap {
      case Some(header) =>
        getHeadersAtHeight(header.height + 1)
          .map(_.filter(_.previousBlockHashBE == blockHashBE))
      case None => Future.successful(Vector.empty)
    }
  }

  /** Retrieves a [[FilterSyncMarker]] respecting the batchSize parameter. If
    * the stopBlockHeader is not within the batchSize parameter we walk
    * backwards until we find a header within the batchSize limit
    */
  private def getFilterSyncStopHash(
      candidateStartHeader: BlockHeaderDb,
      stopBlockHeader: BlockHeaderDb,
      batchSize: Int
  ): Future[Option[FilterSyncMarker]] = {

    val isInBatchSize =
      stopBlockHeader.height - candidateStartHeader.height <= batchSize
    val stopHeaderWithinBatchSizeF = if (isInBatchSize) {
      Future.successful(stopBlockHeader)
    } else {
      // as an optimization only fetch the last blockheader
      // within candidateStartHeight + batchSize , we don't have a way to guarantee
      // this hash ultimately ends up connected to stopBlockHeaderDb though
      // we have to assume its buried under enough work a reorg is unlikely
      getHeadersAtHeight(candidateStartHeader.height + batchSize)
        .map(_.head)
    }

    val blockchainOptF = {
      for {
        stopHeaderWithinBatchSize <- stopHeaderWithinBatchSizeF
        blockchainOpt <- blockHeaderDAO.getBlockchainFrom(
          header = stopHeaderWithinBatchSize,
          startHeight = candidateStartHeader.height
        )
      } yield {
        blockchainOpt
      }
    }
    for {
      stopHeaderWithinBatchSize <- stopHeaderWithinBatchSizeF
      blockchainOpt <- blockchainOptF
      fsmOpt <- {
        blockchainOpt match {
          case Some(blockchain) =>
            val isConnected = hasBothBlockHeaderHashes(
              blockchain = blockchain,
              prevBlockHeaderHashBE = candidateStartHeader.hashBE,
              stopBlockHeaderHashBE = stopHeaderWithinBatchSize.hashBE
            )
            if (isConnected) {
              findNextHeader(
                candidateStartHeader = candidateStartHeader,
                stopBlockHeaderDb = stopHeaderWithinBatchSize,
                batchSize = batchSize,
                blockchain = blockchain
              )
            } else {
              Future.successful(None)
            }
          case None =>
            val exn = new RuntimeException(
              s"Could not form a blockchain with stopHash=$stopBlockHeader.hashBE"
            )
            Future.failed(exn)
        }
      }
    } yield fsmOpt
  }

  /** Finds the next stop hash for a filter sync marker.
    * @param candidateStartHeader
    *   the first block header whose height will be used in the FilterSyncMarker
    */
  private def findNextHeader(
      candidateStartHeader: BlockHeaderDb,
      stopBlockHeaderDb: BlockHeaderDb,
      batchSize: Int,
      blockchain: Blockchain
  ): Future[Option[FilterSyncMarker]] = {
    val hasBothHashes = {
      hasBothBlockHeaderHashes(
        blockchain = blockchain,
        prevBlockHeaderHashBE = candidateStartHeader.hashBE,
        stopBlockHeaderHashBE = stopBlockHeaderDb.hashBE
      )
    }
    require(
      hasBothHashes,
      s"Blockchain did not contain candidate & stopBlockHeader candidate=${candidateStartHeader.hashBE} stopBlockHeader=${stopBlockHeaderDb.hashBE}"
    )

    val candidateStartHeaderHeight = candidateStartHeader.height
    val isInBatchSize =
      (stopBlockHeaderDb.height - candidateStartHeaderHeight) < batchSize

    val startHeight = candidateStartHeaderHeight

    val nextBlockHeaderOpt = {
      if (isInBatchSize) {
        Some(FilterSyncMarker(startHeight, stopBlockHeaderDb.hash))
      } else {
        blockchain.findAtHeight(startHeight + batchSize - 1).map { h =>
          FilterSyncMarker(startHeight, h.hash)
        }
      }
    }
    val resultOpt = nextBlockHeaderOpt

    Future.successful(resultOpt)
  }

  private def hasBothBlockHeaderHashes(
      blockchain: Blockchain,
      prevBlockHeaderHashBE: DoubleSha256DigestBE,
      stopBlockHeaderHashBE: DoubleSha256DigestBE
  ): Boolean = {
    if (prevBlockHeaderHashBE == DoubleSha256DigestBE.empty) {
      // carve out here in the case of genesis header,
      // blockchains don't contain a block header with hash 0x000..0000
      blockchain.exists(_.hashBE == stopBlockHeaderHashBE)
    } else {
      val hasHash1 =
        blockchain.exists(_.hashBE == prevBlockHeaderHashBE)
      val hasHash2 =
        blockchain.exists(_.hashBE == stopBlockHeaderHashBE)
      hasHash1 && hasHash2
    }

  }

  /** @inheritdoc */
  override def nextFilterHeaderBatchRange(
      stopBlockHash: DoubleSha256DigestBE,
      batchSize: Int,
      startHeightOpt: Option[Int]
  ): Future[Option[FilterSyncMarker]] = {
    val stopBlockHeaderDbOptF = getHeader(stopBlockHash)

    for {
      stopBlockHeaderDbOpt <- stopBlockHeaderDbOptF
      fsmOpt <- {
        stopBlockHeaderDbOpt match {
          case Some(stopBlockHeaderDb) =>
            getFilterSyncMarkerFromStopBlockHeader(
              stopBlockHeaderDb = stopBlockHeaderDb,
              startHeightOpt = startHeightOpt,
              batchSize = batchSize
            )
          case None =>
            val exn = new RuntimeException(
              s"Could not find stopBlockHeaderHash=$stopBlockHash in chaindb"
            )
            Future.failed(exn)
        }
      }
    } yield fsmOpt
  }

  /** @param stopBlockHeaderDb
    *   the block header we are stopping, we walk the blockchain backwards from
    *   this blockheader
    * @param candidateStartHeadersOpt
    *   possible start headers that connect with [[stopBlockHeaderDb]]
    * @param batchSize
    * @return
    */
  private def getFilterSyncMarkerFromStopBlockHeader(
      stopBlockHeaderDb: BlockHeaderDb,
      startHeightOpt: Option[Int],
      batchSize: Int
  ): Future[Option[FilterSyncMarker]] = {
    val candidateStartHeadersF: Future[Vector[BlockHeaderDb]] =
      startHeightOpt match {
        case Some(height) => getHeadersAtHeight(height)
        case None =>
          for {
            bestFilterOpt <- getBestFilter()
            candidateHeaders <- {
              bestFilterOpt match {
                case Some(filter) =>
                  getHeadersAtHeight(filter.height + 1).flatMap { headers =>
                    if (headers.isEmpty) {
                      // if we have no headers at height + 1
                      // we must be in a reorg scenario
                      getHeadersAtHeight(filter.height)
                    } else {
                      // remove the bestFilter's block header
                      val filtered =
                        headers.filter(_.hashBE != filter.blockHashBE)
                      Future.successful(filtered)
                    }
                  }
                case None => getHeadersAtHeight(0)
              }
            }
          } yield {
            candidateHeaders
          }
      }

    for {
      candidateStartHeaders <- candidateStartHeadersF
      fsmOptVec <- Future.traverse(candidateStartHeaders)(h =>
        getFilterSyncStopHash(h, stopBlockHeaderDb, batchSize))
    } yield {
      val flatten = fsmOptVec.flatten
      if (flatten.length > 1) {
        logger.warn(
          s"Multiple filter sync makers!!! choosing first one fsmOptVec=$fsmOptVec"
        )
        flatten.headOption
      } else {
        flatten.headOption
      }
    }
  }

  /** @inheritdoc */
  override def processFilterHeaders(
      filterHeaders: Vector[FilterHeader],
      stopHash: DoubleSha256DigestBE
  ): Future[ChainApi] = {
    // find filter headers we have seen before
    val duplicateFilterHeadersF: Future[Vector[CompactFilterHeaderDb]] = {
      filterHeaderDAO.findByHashes(filterHeaders.map(_.hash.flip))
    }

    // only add new filter headers to our database
    val newFilterHeadersF = for {
      duplicates <- duplicateFilterHeadersF
    } yield {
      filterHeaders.filterNot(f => duplicates.exists(_.hashBE == f.hash.flip))
    }

    val filterHeadersToCreateF: Future[Vector[CompactFilterHeaderDb]] = for {
      newFilterHeaders <- newFilterHeadersF
      blockHeaders <- {
        if (newFilterHeaders.length <= 0) {
          Future.successful(Vector.empty)
        } else {
          blockHeaderDAO
            .getNAncestors(childHash = stopHash, n = newFilterHeaders.size - 1)
            .map(_.sortBy(_.height))
        }
      }

    } yield {
      if (blockHeaders.size != newFilterHeaders.size) {
        throw UnknownBlockHash(
          s"Filter header batch size does not match block header batch size newFilterHeaders=${newFilterHeaders.size} != blockHeaders=${blockHeaders.size}"
        )
      }
      blockHeaders.indices.toVector.map { i =>
        val blockHeader = blockHeaders(i)
        val filterHeader = newFilterHeaders(i)
        CompactFilterHeaderDbHelper.fromFilterHeader(
          filterHeader,
          blockHeader.hashBE,
          blockHeader.height
        )
      }
    }

    for {
      filterHeadersToCreate <- filterHeadersToCreateF
      _ <- verifyFilterHeaders(filterHeadersToCreate)
      _ <- filterHeaderDAO.createAll(filterHeadersToCreate)
      _ <- chainConfig.callBacks.executeOnCompactFilterHeaderConnectedCallbacks(
        filterHeadersToCreate
      )
    } yield {
      val minHeightOpt = filterHeadersToCreate.minByOption(_.height)
      val maxHeightOpt = filterHeadersToCreate.maxByOption(_.height)

      (minHeightOpt, maxHeightOpt) match {
        case (Some(minHeight), Some(maxHeight)) =>
          logger.info(
            s"Processed filters headers from height=${minHeight.height} to ${maxHeight.height}. Best filterheader.blockHash=${maxHeight.blockHashBE.hex}"
          )
          this
        // Should never have the case where we have (Some, None) or (None, Some) because that means the vec would be both empty and non empty
        case (_, _) =>
          logger.debug("Processed 0 filter headers")
          this
      }
    }
  }

  /** @inheritdoc */
  override def processFilters(
      messages: Vector[CompactFilterMessage]
  ): Future[ChainApi] = {
    // find filters we have seen before
    val duplicateFiltersF: Future[Vector[CompactFilterDb]] = {
      filterDAO.findByBlockHashes(messages.map(_.blockHash.flip))
    }

    // only add new filters to our database
    val newFiltersF = for {
      duplicates <- duplicateFiltersF
    } yield messages.filterNot(f =>
      duplicates.exists(_.blockHashBE == f.blockHash.flip))

    logger.debug(
      s"processFilters: len=${messages.length} messages.blockHash=${messages
          .map(_.blockHash.flip)}"
    )
    val filterHeadersF = {
      for {
        newFilters <- newFiltersF
        filterHeaders <- filterHeaderDAO
          .findAllByBlockHashes(newFilters.map(_.blockHash.flip))
      } yield filterHeaders
    }

    val filtersByBlockHashF
        : Future[Map[DoubleSha256DigestBE, CompactFilterMessage]] = {
      for {
        newFilters <- newFiltersF
        result = newFilters.groupBy(_.blockHash.flip).map {
          case (blockHash, messages) =>
            if (messages.size > 1) {
              Future.failed(
                DuplicateFilters(
                  s"Attempt to process ${messages.length} duplicate filters for blockHashBE=$blockHash"
                )
              )
            } else {
              Future.successful((blockHash, messages.head))
            }
        }
        iter <- Future.sequence(result)
      } yield {
        iter.toMap
      }
    }

    for {
      filterHeaders <- filterHeadersF
      filtersByBlockHash <- filtersByBlockHashF
      _ = require(
        filterHeaders.size == filtersByBlockHash.values.size,
        s"Filter batch size does not match filter header batch size ${filtersByBlockHash.values.size} != ${filterHeaders.size}"
      )
      compactFilterDbs <- FutureUtil.makeAsync { () =>
        filterHeaders.map { filterHeader =>
          findFilterDbFromMessage(filterHeader, filtersByBlockHash)
        }
      }
      _ <- filterDAO.createAll(compactFilterDbs)
      _ <- chainConfig.callBacks.executeOnCompactFilterConnectedCallbacks(
        compactFilterDbs
      )
    } yield {
      val minHeightOpt = compactFilterDbs.minByOption(_.height)
      val maxHeightOpt = compactFilterDbs.maxByOption(_.height)

      (minHeightOpt, maxHeightOpt) match {
        case (Some(minHeight), Some(maxHeight)) =>
          logger.info(
            s"Processed filters from height=${minHeight.height} to ${maxHeight.height}. Best filter.blockHash=${maxHeight.blockHashBE.hex}"
          )
          this
        // Should never have the case where we have (Some, None) or (None, Some) because that means the vec would be both empty and non empty
        case (_, _) =>
          logger.warn(
            s"Was unable to process any filters minHeightOpt=$minHeightOpt maxHeightOpt=$maxHeightOpt compactFilterDbs.length=${compactFilterDbs.length} filterHeaders.length=${filterHeaders.length}"
          )
          this
      }
    }
  }

  /** Verifies if the previous headers exist either in the batch
    * [[filterHeaders]] or in the database, throws if it doesn't
    */
  def verifyFilterHeaders(
      filterHeaders: Vector[CompactFilterHeaderDb]
  ): Future[Unit] = {
    val byHash = filterHeaders.foldLeft(
      Map.empty[DoubleSha256DigestBE, CompactFilterHeaderDb]
    )((acc, fh) => acc.updated(fh.hashBE, fh))
    val verify = checkFilterHeader(byHash)(_)
    FutureUtil.sequentially(filterHeaders)(verify).map(_ => ())
  }

  private def checkFilterHeader(
      filtersByHash: Map[DoubleSha256DigestBE, CompactFilterHeaderDb]
  )(filterHeader: CompactFilterHeaderDb): Future[Unit] = {

    def checkHeight(
        filterHeader: CompactFilterHeaderDb,
        prevHeader: CompactFilterHeaderDb
    ): Unit = {
      require(
        prevHeader.height == filterHeader.height - 1,
        s"Unexpected previous filter header's height: ${prevHeader.height} != ${filterHeader.height - 1}"
      )
    }

    if (filterHeader.hashBE == filterHeader.previousFilterHeaderBE) {
      Future.failed(
        new IllegalArgumentException(
          s"Filter header cannot reference to itself: ${filterHeader}"
        )
      )
    } else if (filterHeader.height == 0) {
      Future {
        require(
          filterHeader.previousFilterHeaderBE == DoubleSha256DigestBE.empty,
          s"Previous filter header hash for the genesis block must be empty: ${filterHeader}"
        )
      }
    } else {
      if (filterHeader.previousFilterHeaderBE == DoubleSha256DigestBE.empty) {
        Future.failed(
          new IllegalArgumentException(
            s"Previous filter header hash for a regular block must not be empty: ${filterHeader}"
          )
        )
      } else {
        filtersByHash.get(filterHeader.previousFilterHeaderBE) match {
          case Some(prevHeader) =>
            FutureUtil.makeAsync { () =>
              checkHeight(filterHeader, prevHeader)
            }
          case None =>
            val filterHashFOpt = filterHeaderDAO
              .findByHash(filterHeader.previousFilterHeaderBE)
            filterHashFOpt.map {
              case Some(prevHeader) =>
                checkHeight(filterHeader, prevHeader)
              case None =>
                throw new IllegalArgumentException(
                  s"Previous filter header does not exist: $filterHeader"
                )
            }
        }
      }
    }
  }

  private def findFilterDbFromMessage(
      filterHeader: CompactFilterHeaderDb,
      messagesByBlockHash: Map[DoubleSha256DigestBE, CompactFilterMessage]
  ): CompactFilterDb = {
    messagesByBlockHash.get(filterHeader.blockHashBE) match {
      case Some(message) =>
        val filterHashBE = CryptoUtil.doubleSHA256(message.filterBytes).flip
        if (filterHashBE != filterHeader.filterHashBE) {
          val errMsg =
            s"Filter hash does not match filter header hash: ${filterHashBE} != ${filterHeader.filterHashBE}\n" +
              s"filter=${message.filterBytes.toHex}\nblock hash=${message.blockHash}\nfilterHeader=${filterHeader}"
          logger.warn(errMsg)
          throw UnknownFilterHash(errMsg)
        }
        val filter =
          CompactFilterDbHelper.fromFilterBytes(
            message.filterBytes,
            filterHeader.blockHashBE,
            filterHeader.height
          )
        filter
      case None =>
        throw UnknownBlockHash(
          s"Unknown block hash ${filterHeader.blockHashBE}"
        )
    }
  }

  /** @inheritdoc */
  override def processCheckpoints(
      checkpoints: Vector[DoubleSha256DigestBE],
      blockHash: DoubleSha256DigestBE
  ): Future[ChainApi] = {
    val blockHeadersF: Future[Seq[BlockHeaderDb]] = Future
      .traverse(checkpoints.indices.toVector) { i =>
        blockHeaderDAO.getAtHeight(i * 1000)
      }
      .map(headers => headers.map(_.head))

    for {
      blockHeaders <- blockHeadersF
    } yield {
      val checkpointsWithBlocks = checkpoints.zip(blockHeaders)

      val updatedCheckpoints =
        checkpointsWithBlocks.foldLeft(blockFilterCheckpoints) { (res, pair) =>
          val (filterHeaderHash, blockHeader) = pair
          res.updated(blockHeader.hashBE, filterHeaderHash)
        }

      ChainHandler(
        blockHeaderDAO = blockHeaderDAO,
        filterHeaderDAO = filterHeaderDAO,
        filterDAO = filterDAO,
        stateDAO = stateDAO,
        blockFilterCheckpoints = updatedCheckpoints
      )
    }
  }

  /** @inheritdoc */
  override def getFilter(
      blockHash: DoubleSha256DigestBE
  ): Future[Option[CompactFilterDb]] = {
    filterDAO.findByBlockHash(blockHash)
  }

  /** @inheritdoc */
  override def getHeadersAtHeight(height: Int): Future[Vector[BlockHeaderDb]] =
    blockHeaderDAO.getAtHeight(height)

  /** @inheritdoc */
  override def getFilterHeaderCount(): Future[Int] = {
    filterHeaderDAO.getBestFilterHeaderHeight
  }

  /** @inheritdoc */
  override def getFilterHeadersAtHeight(
      height: Int
  ): Future[Vector[CompactFilterHeaderDb]] =
    filterHeaderDAO.getAtHeight(height)

  protected def getBestFilterHeaderWithChains(
      blockchains: Vector[Blockchain]
  ): Future[Option[CompactFilterHeaderDb]] = {
    val bestFilterHeadersInChain: Future[Option[CompactFilterHeaderDb]] = {
      val bestChainOpt = blockchains.maxByOption(_.tip.chainWork)
      bestChainOpt match {
        case Some(bestChain) =>
          filterHeaderDAO.getBestFilterHeaderForHeaders(bestChain.toVector)
        case None => Future.successful(None)
      }
    }

    for {
      filterHeaderOpt <- bestFilterHeadersInChain
      result <-
        if (filterHeaderOpt.isEmpty) {
          bestFilterHeaderSearch()
        } else {
          Future.successful(filterHeaderOpt)
        }
    } yield {
      result
    }
  }

  /** @inheritdoc */
  override def getBestFilterHeader(): Future[Option[CompactFilterHeaderDb]] = {
    val blockchainsF = blockHeaderDAO.getBlockchains()
    for {
      blockchains <- blockchainsF
      filterHeaderOpt <- getBestFilterHeaderWithChains(blockchains)
    } yield {
      filterHeaderOpt
    }
  }

  override def getBestFilter(): Future[Option[CompactFilterDb]] = {
    filterDAO.getBestFilter
  }

  /** This method retrieves the best [[CompactFilterHeaderDb]] from the database
    * without any blockchain context, and then uses the
    * [[CompactFilterHeaderDb.blockHashBE]] to query our block headers database
    * looking for a filter header that is in the best chain
    * @return
    */
  private def bestFilterHeaderSearch()
      : Future[Option[CompactFilterHeaderDb]] = {
    val bestFilterHeaderOptF = filterHeaderDAO.getBestFilterHeader

    // get best blockchain around our latest filter header
    val blockchainOptF: Future[Option[Blockchain]] = {
      for {
        bestFilterHeaderOpt <- bestFilterHeaderOptF
        blockchains <- {
          bestFilterHeaderOpt match {
            case Some(bestFilterHeader) =>
              // get blockchains from our current best filter header to
              // the next POW of interval, this should be enough to determine
              // what is the best chain!
              blockHeaderDAO.getBlockchainsBetweenHeights(
                from =
                  bestFilterHeader.height - chainConfig.chain.difficultyChangeInterval,
                to =
                  bestFilterHeader.height + chainConfig.chain.difficultyChangeInterval
              )
            case None =>
              Future.successful(Vector.empty)
          }
        }
      } yield {
        if (blockchains.isEmpty) {
          None
        } else {
          blockchains.maxByOption(_.tip.chainWork)
        }
      }
    }

    val filterHeadersOptF: Future[Option[CompactFilterHeaderDb]] = {
      for {
        blockchainOpt <- blockchainOptF
        bestHeadersForChainFOpt = {
          blockchainOpt.map(b =>
            filterHeaderDAO.getBestFilterHeaderForHeaders(b.toVector))
        }
        bestHeadersForChain <- bestHeadersForChainFOpt match {
          case Some(f) => f
          case None    => Future.successful(None)
        }
      } yield bestHeadersForChain
    }

    filterHeadersOptF
  }

  /** @inheritdoc */
  override def getFilterHeader(
      blockHash: DoubleSha256DigestBE
  ): Future[Option[CompactFilterHeaderDb]] =
    filterHeaderDAO.findByBlockHash(blockHash)

  /** @inheritdoc */
  override def getFilterCount(): Future[Int] = {
    filterDAO.getBestFilterHeight
  }

  /** @inheritdoc */
  override def getFiltersAtHeight(
      height: Int
  ): Future[Vector[CompactFilterDb]] =
    filterDAO.getAtHeight(height)

  /** @inheritdoc */
  override def getHeightByBlockStamp(blockStamp: BlockStamp): Future[Int] =
    blockStamp match {
      case blockHeight: BlockStamp.BlockHeight =>
        Future.successful(blockHeight.height)
      case blockHash: BlockStamp.BlockHash =>
        getHeader(blockHash.hash).map { header =>
          header
            .map(_.height)
            .getOrElse(
              throw UnknownBlockHash(s"Unknown block hash ${blockHash.hash}")
            )
        }
      case blockTime: BlockStamp.BlockTime =>
        Future.failed(new RuntimeException(s"Not implemented: $blockTime"))
    }

  override def epochSecondToBlockHeight(time: Long): Future[Int] =
    blockHeaderDAO.findClosestToTime(time = UInt32(time)).map(_.height)

  /** @inheritdoc */
  override def getBlockHeight(
      blockHash: DoubleSha256DigestBE
  ): Future[Option[Int]] =
    getHeader(blockHash).map(_.map(_.height))

  /** @inheritdoc */
  override def getNumberOfConfirmations(
      blockHash: DoubleSha256DigestBE
  ): Future[Option[Int]] = {
    getBlockHeight(blockHash).flatMap {
      case None => FutureUtil.none
      case Some(blockHeight) =>
        for {
          tips <- getBestChainTips()
          ancestorChains <- Future.traverse(tips) { tip =>
            blockHeaderDAO.getNAncestors(tip.hashBE, tip.height - blockHeight)
          }
        } yield {
          val confs = ancestorChains.flatMap { chain =>
            if (chain.last.hashBE == blockHash) {
              Some(chain.head.height - blockHeight + 1)
            } else None
          }

          if (confs.nonEmpty) {
            Some(confs.max)
          } else None
        }
    }
  }

  override def getFiltersBetweenHeights(
      startHeight: Int,
      endHeight: Int
  ): Future[Vector[FilterResponse]] =
    filterDAO
      .getBetweenHeights(startHeight, endHeight)
      .map(dbos =>
        dbos.map(dbo =>
          FilterResponse(dbo.golombFilter, dbo.blockHashBE, dbo.height)))

  /** @inheritdoc */
  override def getHeadersBetween(
      from: BlockHeaderDb,
      to: BlockHeaderDb
  ): Future[Vector[BlockHeaderDb]] = {
    logger.debug(s"Finding headers from=$from to=$to")
    def loop(
        currentF: Future[BlockHeaderDb],
        accum: Vector[BlockHeaderDb]
    ): Future[Vector[BlockHeaderDb]] = {
      currentF.flatMap { current =>
        if (current.hashBE == from.hashBE) {
          Future.successful(current +: accum)
        } else {
          val nextOptF = getHeader(current.previousBlockHashBE)
          val nextF = nextOptF.map(
            _.getOrElse(
              sys.error(s"Could not find header=${current.previousBlockHashBE}")
            )
          )
          loop(nextF, current +: accum)
        }
      }
    }
    loop(Future.successful(to), Vector.empty)
  }

  def isMissingChainWork: Future[Boolean] = {
    for {
      first100 <- blockHeaderDAO.getBetweenHeights(0, 100)
      first100MissingWork =
        first100.nonEmpty && first100.exists(_.chainWork == BigInt(0))
      isMissingWork <- {
        if (first100MissingWork) {
          Future.successful(true)
        } else {
          for {
            height <- blockHeaderDAO.maxHeight
            last100 <- blockHeaderDAO.getBetweenHeights(height - 100, height)
            last100MissingWork =
              last100.nonEmpty && last100.exists(_.chainWork == BigInt(0))
          } yield last100MissingWork
        }
      }

    } yield isMissingWork
  }

  @tailrec
  private def calcChainWork(
      remainingHeaders: Vector[BlockHeaderDb],
      accum: Vector[BlockHeaderDb],
      lastHeaderWithWorkInDb: BlockHeaderDb
  ): Vector[BlockHeaderDb] = {
    if (remainingHeaders.isEmpty) {
      accum
    } else {
      val header = remainingHeaders.head

      val currentChainWork = {
        accum.lastOption.map(_.chainWork) match {
          case Some(prevWork) =>
            prevWork
          case None =>
            // this should be the case where the accum is
            // empty, so this header is the last one we have
            // stored in the database
            lastHeaderWithWorkInDb.chainWork
        }
      }
      val newChainWork =
        currentChainWork + Pow.getBlockProof(header.blockHeader)
      val newHeader = header.copy(chainWork = newChainWork)
      calcChainWork(
        remainingHeaders.tail,
        accum :+ newHeader,
        lastHeaderWithWorkInDb
      )
    }
  }

  private def getBatchForRecalc(
      startHeight: Int,
      maxHeight: Int,
      batchSize: Int
  ): Future[Vector[Blockchain]] = {
    val batchEndHeight = Math.min(maxHeight, startHeight + batchSize - 1)
    val headersToCalcF = {
      logger.trace(s"Fetching from=$startHeight to=$batchEndHeight")
      blockHeaderDAO.getBlockchainsBetweenHeights(
        from = startHeight,
        to = batchEndHeight
      )
    }

    headersToCalcF
  }

  /** Creates [[numBatches]] of requests to the database fetching [[batchSize]]
    * headers starting at [[batchStartHeight]]. These are executed in parallel.
    * After all are fetched we join them into one future and return it.
    */
  private def batchAndGetBlockchains(
      batchSize: Int,
      batchStartHeight: Int,
      maxHeight: Int,
      numBatches: Int
  ): Future[Vector[Blockchain]] = {
    var counter = batchStartHeight
    val range = 0.until(numBatches)
    val batchesNested: Vector[Future[Vector[Blockchain]]] = range.map { _ =>
      val f =
        if (counter <= maxHeight) {
          getBatchForRecalc(
            startHeight = counter,
            maxHeight = maxHeight,
            batchSize = batchSize
          )
        } else {
          Future.successful(Vector.empty)
        }
      counter += batchSize
      f
    }.toVector

    Future
      .sequence(batchesNested)
      .map(_.flatten)
  }

  private def runRecalculateChainWork(
      maxHeight: Int,
      lastHeader: BlockHeaderDb
  ): Future[Vector[BlockHeaderDb]] = {
    val currentHeight = lastHeader.height
    val numBatches = 1
    val batchSize =
      chainConfig.appConfig.chain.difficultyChangeInterval / numBatches
    if (currentHeight >= maxHeight) {
      Future.successful(Vector.empty)
    } else {
      val batchStartHeight = currentHeight + 1

      val headersToCalcF = batchAndGetBlockchains(
        batchSize = batchSize,
        batchStartHeight = batchStartHeight,
        maxHeight = maxHeight,
        numBatches = numBatches
      )

      for {
        headersToCalc <- headersToCalcF
        _ = headersToCalc.headOption.map { h =>
          logger.trace(
            s"Recalculating chain work... current height: ${h.height} maxHeight=$maxHeight"
          )
        }
        headersWithWork = {
          headersToCalc.flatMap { chain =>
            calcChainWork(
              remainingHeaders = chain.headers.sortBy(_.height),
              accum = Vector.empty,
              lastHeaderWithWorkInDb = lastHeader
            )
          }
        }

        // unfortunately on sqlite there is a bottle neck here
        // sqlite allows you to read in parallel but only write
        // sequentially https://stackoverflow.com/a/23350768/967713
        // so while it looks like we are executing in parallel
        // in reality there is only one thread that can write to the db
        // at a single time
        _ =
          logger.trace(
            s"Upserting from height=${headersWithWork.headOption
                .map(_.height)} to height=${headersWithWork.lastOption.map(_.height)}"
          )
        _ <- FutureUtil.batchExecute(
          headersWithWork,
          blockHeaderDAO.upsertAll,
          Vector.empty,
          batchSize
        )
        _ = logger.trace(s"Done upserting from height=${headersWithWork.headOption
            .map(_.height)} to height=${headersWithWork.lastOption.map(_.height)}")
        next <- runRecalculateChainWork(maxHeight, headersWithWork.last)
      } yield {
        next
      }
    }
  }

  def recalculateChainWork: Future[ChainHandler] = {
    logger.trace("Calculating chain work for previous blocks")

    val maxHeightF = blockHeaderDAO.maxHeight
    val startHeightF = blockHeaderDAO.getLowestNoWorkHeight
    val startF = for {
      startHeight <- startHeightF
      headers <- {
        if (startHeight == 0) {
          val genesisHeaderF = blockHeaderDAO.getAtHeight(0)
          genesisHeaderF.flatMap { h =>
            require(h.length == 1, s"Should only have one genesis header!")
            calculateChainWorkGenesisBlock(h.head)
              .map(Vector(_))
          }
        } else {
          blockHeaderDAO.getAtHeight(startHeight - 1)
        }
      }
    } yield headers

    val resultF = for {
      maxHeight <- maxHeightF
      start <- startF
      _ <- runRecalculateChainWork(maxHeight, start.head)
    } yield {
      logger.trace("Finished calculating chain work")
      ChainHandler(
        blockHeaderDAO = blockHeaderDAO,
        filterHeaderDAO = filterHeaderDAO,
        filterDAO = filterDAO,
        stateDAO = stateDAO,
        blockFilterCheckpoints = blockFilterCheckpoints
      )
    }

    resultF.failed.foreach { err =>
      logger.error(s"Failed to recalculate chain work", err)
    }

    resultF
  }

  /** Calculates the chain work for the genesis header */
  private def calculateChainWorkGenesisBlock(
      genesisHeader: BlockHeaderDb
  ): Future[BlockHeaderDb] = {
    val expectedWork = Pow.getBlockProof(genesisHeader.blockHeader)
    val genesisWithWork = genesisHeader.copy(chainWork = expectedWork)
    blockHeaderDAO.update(genesisWithWork)
  }

  def copyWith(
      blockHeaderDAO: BlockHeaderDAO = blockHeaderDAO,
      filterHeaderDAO: CompactFilterHeaderDAO = filterHeaderDAO,
      filterDAO: CompactFilterDAO = filterDAO,
      stateDAO: ChainStateDescriptorDAO = stateDAO,
      blockFilterCheckpoints: Map[DoubleSha256DigestBE, DoubleSha256DigestBE] =
        blockFilterCheckpoints
  ): ChainHandler = {
    new ChainHandler(
      blockHeaderDAO = blockHeaderDAO,
      filterHeaderDAO = filterHeaderDAO,
      filterDAO = filterDAO,
      stateDAO = stateDAO,
      blockFilterCheckpoints = blockFilterCheckpoints
    )
  }

  def toChainHandlerCached: Future[ChainHandlerCached] = {
    ChainHandler.toChainHandlerCached(this)
  }

  /** calculates the median time passed */
  override def getMedianTimePast(): Future[Long] = {
    /*
        static constexpr int nMedianTimeSpan = 11;

    int64_t GetMedianTimePast() const
    {
        int64_t pmedian[nMedianTimeSpan];
        int64_t* pbegin = &pmedian[nMedianTimeSpan];
        int64_t* pend = &pmedian[nMedianTimeSpan];

        const CBlockIndex* pindex = this;
        for (int i = 0; i < nMedianTimeSpan && pindex; i++, pindex = pindex->pprev)
     *(--pbegin) = pindex->GetBlockTime();

        std::sort(pbegin, pend);
        return pbegin[(pend - pbegin)/2];
    }
     */
    val nMedianTimeSpan = 11

    @tailrec
    def getNTopHeaders(
        n: Int,
        acc: Vector[Future[Option[BlockHeaderDb]]]
    ): Vector[Future[Option[BlockHeaderDb]]] = {
      if (n == 1)
        acc
      else {
        val prev: Future[Option[BlockHeaderDb]] = acc.last.flatMap {
          case None       => Future.successful(None)
          case Some(last) => getHeader(last.previousBlockHashBE)
        }
        getNTopHeaders(n - 1, acc :+ prev)
      }
    }

    val top11 = getNTopHeaders(
      nMedianTimeSpan,
      Vector(getBestBlockHeader().map(Option.apply))
    )

    Future
      .sequence(top11)
      .map(_.collect { case Some(header) =>
        header.time.toLong
      })
      .map { times =>
        times.sorted.apply(times.size / 2)
      }
  }

  override def isSyncing(): Future[Boolean] = {
    stateDAO.isSyncing
  }

  override def isIBD(): Future[Boolean] = {
    stateDAO.getIsIBD().map {
      case Some(ibd) =>
        ibd.isIBDRunning
      case None =>
        // if we do not have the state descriptor in the database, default to true on IBD
        true
    }
  }

  override def setSyncing(value: Boolean): Future[ChainApi] = {
    val isSyncingF = stateDAO.isSyncing
    for {
      isSyncing <- isSyncingF
      _ <- {
        if (isSyncing == value) {
          // do nothing as we are already at this state
          Future.unit
        } else {
          updateSyncingAndExecuteCallback(value)
        }
      }
    } yield {
      this
    }
  }

  override def isTipStale(): Future[Boolean] = {
    getBestBlockHeader().map { blockHeaderDb =>
      NetworkUtil.isBlockHeaderStale(
        blockHeaderDb.blockHeader,
        chainConfig.chain
      )
    }
  }

  override def setIBD(value: Boolean): Future[ChainApi] = {
    val isIBDF: Future[Option[IsInitialBlockDownload]] = stateDAO.getIsIBD()
    for {
      isIBDOpt <- isIBDF
      _ <- {
        if (isIBDOpt.isDefined && isIBDOpt.get.isIBDRunning == value) {
          // do nothing as we are already at this state
          Future.unit
        } else if (isIBDOpt.isDefined && !isIBDOpt.get.isIBDRunning && value) {
          logger.warn(
            s"Can only do IBD once, cannot set flag to true when database flag is false."
          )
          Future.unit
        } else {
          stateDAO.updateIsIbd(value)
        }
      }
    } yield {
      this
    }
  }

  private def updateSyncingAndExecuteCallback(value: Boolean): Future[Unit] = {
    for {
      changed <- stateDAO.updateSyncing(value)
      _ <- {
        if (changed && chainConfig.callBacks.onSyncFlagChanged.nonEmpty) {
          chainConfig.callBacks.executeOnSyncFlagChanged(value)
        } else {
          Future.unit
        }
      }
    } yield {
      ()
    }
  }
}

object ChainHandler {

  def apply(
      blockHeaderDAO: BlockHeaderDAO,
      filterHeaderDAO: CompactFilterHeaderDAO,
      filterDAO: CompactFilterDAO,
      stateDAO: ChainStateDescriptorDAO,
      blockFilterCheckpoints: Map[DoubleSha256DigestBE, DoubleSha256DigestBE]
  )(implicit
      ec: ExecutionContext,
      chainAppConfig: ChainAppConfig
  ): ChainHandler = {
    new ChainHandler(
      blockHeaderDAO,
      filterHeaderDAO,
      filterDAO,
      stateDAO,
      blockFilterCheckpoints
    )
  }

  def fromChainHandlerCached(
      cached: ChainHandlerCached
  )(implicit ec: ExecutionContext): ChainHandler = {
    new ChainHandler(
      blockHeaderDAO = cached.blockHeaderDAO,
      filterHeaderDAO = cached.filterHeaderDAO,
      filterDAO = cached.filterDAO,
      stateDAO = cached.stateDAO,
      blockFilterCheckpoints = Map.empty
    )(cached.chainConfig, ec)
  }

  /** Constructs a [[ChainHandler chain handler]] from the state in the database
    * This gives us the guaranteed latest state we have in the database
    */
  def fromDatabase(
      blockHeaderDAO: BlockHeaderDAO,
      filterHeaderDAO: CompactFilterHeaderDAO,
      filterDAO: CompactFilterDAO,
      stateDAO: ChainStateDescriptorDAO
  )(implicit
      ec: ExecutionContext,
      chainConfig: ChainAppConfig
  ): ChainHandler = {
    new ChainHandler(
      blockHeaderDAO = blockHeaderDAO,
      filterHeaderDAO = filterHeaderDAO,
      filterDAO = filterDAO,
      stateDAO = stateDAO,
      blockFilterCheckpoints = Map.empty
    )
  }

  def apply(
      blockHeaderDAO: BlockHeaderDAO,
      filterHeaderDAO: CompactFilterHeaderDAO,
      filterDAO: CompactFilterDAO,
      stateDAO: ChainStateDescriptorDAO
  )(implicit
      ec: ExecutionContext,
      chainConfig: ChainAppConfig
  ): ChainHandler = {
    new ChainHandler(
      blockHeaderDAO = blockHeaderDAO,
      filterHeaderDAO = filterHeaderDAO,
      filterDAO = filterDAO,
      stateDAO = stateDAO,
      blockFilterCheckpoints = Map.empty
    )
  }

  def fromDatabase()(implicit
      ec: ExecutionContext,
      chainConfig: ChainAppConfig
  ): ChainHandler = {
    lazy val blockHeaderDAO = BlockHeaderDAO()
    lazy val filterHeaderDAO = CompactFilterHeaderDAO()
    lazy val filterDAO = CompactFilterDAO()
    lazy val stateDAO = ChainStateDescriptorDAO()

    ChainHandler.fromDatabase(
      blockHeaderDAO = blockHeaderDAO,
      filterHeaderDAO = filterHeaderDAO,
      filterDAO = filterDAO,
      stateDAO = stateDAO
    )
  }

  /** Converts a [[ChainHandler]] to [[ChainHandlerCached]] by calling
    * [[BlockHeaderDAO.getBlockchains()]]
    */
  def toChainHandlerCached(
      chainHandler: ChainHandler
  )(implicit ec: ExecutionContext): Future[ChainHandlerCached] = {
    val blockchainsF = chainHandler.blockHeaderDAO.getBlockchains()
    for {
      blockchains <- blockchainsF
      cached = ChainHandlerCached(
        blockHeaderDAO = chainHandler.blockHeaderDAO,
        filterHeaderDAO = chainHandler.filterHeaderDAO,
        filterDAO = chainHandler.filterDAO,
        stateDAO = chainHandler.stateDAO,
        blockchains = blockchains,
        blockFilterCheckpoints = chainHandler.blockFilterCheckpoints
      )(chainHandler.chainConfig, ec)
    } yield cached
  }
}
