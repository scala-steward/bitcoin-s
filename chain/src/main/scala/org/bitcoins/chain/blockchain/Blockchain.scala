package org.bitcoins.chain.blockchain

import org.bitcoins.core.api.chain.db.BlockHeaderDb

/** @inheritdoc */
case class Blockchain(headers: Vector[BlockHeaderDb]) extends BaseBlockChain

object Blockchain extends BaseBlockChainCompObject {

  override def fromHeaders(
      headers: scala.collection.immutable.Seq[BlockHeaderDb]
  ): Blockchain =
    Blockchain(headers.toVector)
}
