package org.bitcoins.core.api.chain.db

import org.bitcoins.core.gcs.{BlockFilter, FilterType, GolombFilter}
import org.bitcoins.crypto.{CryptoUtil, DoubleSha256DigestBE}
import scodec.bits.ByteVector

case class CompactFilterDb(
    hashBE: DoubleSha256DigestBE,
    filterType: FilterType,
    bytes: ByteVector,
    height: Int,
    blockHashBE: DoubleSha256DigestBE) {
  require(
    CryptoUtil.doubleSHA256(bytes).flip == hashBE,
    s"Bytes must hash to hashBE! It looks like you didn't construct CompactFilterDb correctly")

  def golombFilter: GolombFilter =
    filterType match {
      case FilterType.Basic => BlockFilter.fromBytes(bytes, blockHashBE.flip)
    }

  override def toString: String = {
    s"CompactFilterDb(hashBE=$hashBE,filterType=$filterType,height=$height,blockHashBE=$blockHashBE,bytes=${bytes})"
  }
}

object CompactFilterDbHelper {

  def fromGolombFilter(
      golombFilter: GolombFilter,
      blockHash: DoubleSha256DigestBE,
      height: Int): CompactFilterDb =
    fromFilterBytes(golombFilter.bytes, blockHash, height)

  def fromFilterBytes(
      filterBytes: ByteVector,
      blockHash: DoubleSha256DigestBE,
      height: Int): CompactFilterDb =
    CompactFilterDb(CryptoUtil.doubleSHA256(filterBytes).flip,
                    FilterType.Basic,
                    filterBytes,
                    height,
                    blockHash)
}
