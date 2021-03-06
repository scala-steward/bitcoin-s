package org.bitcoins.core.protocol.dlc

import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.policy.Policy
import org.bitcoins.core.protocol.dlc.DLCMessage._
import org.bitcoins.core.protocol.script.P2WSHWitnessV0
import org.bitcoins.core.protocol.transaction.{
  NonWitnessTransaction,
  Transaction,
  WitnessTransaction
}
import org.bitcoins.core.wallet.fee.FeeUnit
import org.bitcoins.crypto._
import scodec.bits.ByteVector

sealed trait DLCStatus {

  /** The flipped sha256 hash of oracleInfo ++ contractInfo ++ timeoutes */
  def paramHash: Sha256DigestBE
  def isInitiator: Boolean
  def state: DLCState
  def tempContractId: Sha256Digest
  def contractInfo: ContractInfo
  def oracleInfo: OracleInfo = contractInfo.oracleInfo
  def timeouts: DLCTimeouts
  def feeRate: FeeUnit
  def totalCollateral: CurrencyUnit
  def localCollateral: CurrencyUnit
  def remoteCollateral: CurrencyUnit = totalCollateral - localCollateral

  lazy val statusString: String = state.toString
}

sealed trait AcceptedDLCStatus extends DLCStatus {
  def contractId: ByteVector
}

sealed trait BroadcastedDLCStatus extends AcceptedDLCStatus {
  def fundingTxId: DoubleSha256DigestBE
}

sealed trait ClosedDLCStatus extends BroadcastedDLCStatus {
  def closingTxId: DoubleSha256DigestBE
}

sealed trait ClaimedDLCStatus extends ClosedDLCStatus {
  def oracleOutcome: OracleOutcome
  def oracleSigs: Vector[SchnorrDigitalSignature]
}

object DLCStatus {

  case class Offered(
      paramHash: Sha256DigestBE,
      isInitiator: Boolean,
      tempContractId: Sha256Digest,
      contractInfo: ContractInfo,
      timeouts: DLCTimeouts,
      feeRate: FeeUnit,
      totalCollateral: CurrencyUnit,
      localCollateral: CurrencyUnit
  ) extends DLCStatus {
    override val state: DLCState.Offered.type = DLCState.Offered
  }

  case class Accepted(
      paramHash: Sha256DigestBE,
      isInitiator: Boolean,
      tempContractId: Sha256Digest,
      contractId: ByteVector,
      contractInfo: ContractInfo,
      timeouts: DLCTimeouts,
      feeRate: FeeUnit,
      totalCollateral: CurrencyUnit,
      localCollateral: CurrencyUnit)
      extends AcceptedDLCStatus {
    override val state: DLCState.Accepted.type = DLCState.Accepted
  }

  case class Signed(
      paramHash: Sha256DigestBE,
      isInitiator: Boolean,
      tempContractId: Sha256Digest,
      contractId: ByteVector,
      contractInfo: ContractInfo,
      timeouts: DLCTimeouts,
      feeRate: FeeUnit,
      totalCollateral: CurrencyUnit,
      localCollateral: CurrencyUnit)
      extends AcceptedDLCStatus {
    override val state: DLCState.Signed.type = DLCState.Signed
  }

  case class Broadcasted(
      paramHash: Sha256DigestBE,
      isInitiator: Boolean,
      tempContractId: Sha256Digest,
      contractId: ByteVector,
      contractInfo: ContractInfo,
      timeouts: DLCTimeouts,
      feeRate: FeeUnit,
      totalCollateral: CurrencyUnit,
      localCollateral: CurrencyUnit,
      fundingTxId: DoubleSha256DigestBE)
      extends BroadcastedDLCStatus {
    override val state: DLCState.Broadcasted.type = DLCState.Broadcasted
  }

  case class Confirmed(
      paramHash: Sha256DigestBE,
      isInitiator: Boolean,
      tempContractId: Sha256Digest,
      contractId: ByteVector,
      contractInfo: ContractInfo,
      timeouts: DLCTimeouts,
      feeRate: FeeUnit,
      totalCollateral: CurrencyUnit,
      localCollateral: CurrencyUnit,
      fundingTxId: DoubleSha256DigestBE)
      extends BroadcastedDLCStatus {
    override val state: DLCState.Confirmed.type = DLCState.Confirmed
  }

  case class Claimed(
      paramHash: Sha256DigestBE,
      isInitiator: Boolean,
      tempContractId: Sha256Digest,
      contractId: ByteVector,
      contractInfo: ContractInfo,
      timeouts: DLCTimeouts,
      feeRate: FeeUnit,
      totalCollateral: CurrencyUnit,
      localCollateral: CurrencyUnit,
      fundingTxId: DoubleSha256DigestBE,
      closingTxId: DoubleSha256DigestBE,
      oracleSigs: Vector[SchnorrDigitalSignature],
      oracleOutcome: OracleOutcome)
      extends ClaimedDLCStatus {
    override val state: DLCState.Claimed.type = DLCState.Claimed
  }

  case class RemoteClaimed(
      paramHash: Sha256DigestBE,
      isInitiator: Boolean,
      tempContractId: Sha256Digest,
      contractId: ByteVector,
      contractInfo: ContractInfo,
      timeouts: DLCTimeouts,
      feeRate: FeeUnit,
      totalCollateral: CurrencyUnit,
      localCollateral: CurrencyUnit,
      fundingTxId: DoubleSha256DigestBE,
      closingTxId: DoubleSha256DigestBE,
      oracleSig: SchnorrDigitalSignature,
      oracleOutcome: OracleOutcome)
      extends ClaimedDLCStatus {
    override val state: DLCState.RemoteClaimed.type = DLCState.RemoteClaimed
    override val oracleSigs: Vector[SchnorrDigitalSignature] = Vector(oracleSig)
  }

  case class Refunded(
      paramHash: Sha256DigestBE,
      isInitiator: Boolean,
      tempContractId: Sha256Digest,
      contractId: ByteVector,
      contractInfo: ContractInfo,
      timeouts: DLCTimeouts,
      feeRate: FeeUnit,
      totalCollateral: CurrencyUnit,
      localCollateral: CurrencyUnit,
      fundingTxId: DoubleSha256DigestBE,
      closingTxId: DoubleSha256DigestBE)
      extends ClosedDLCStatus {
    override val state: DLCState.Refunded.type = DLCState.Refunded
  }

  def getContractId(status: DLCStatus): Option[ByteVector] = {
    status match {
      case status: AcceptedDLCStatus =>
        Some(status.contractId)
      case _: Offered | _: Accepted =>
        None
    }
  }

  def getFundingTxId(status: DLCStatus): Option[DoubleSha256DigestBE] = {
    status match {
      case status: BroadcastedDLCStatus =>
        Some(status.fundingTxId)
      case _: Offered | _: Accepted | _: Signed =>
        None
    }
  }

  def getClosingTxId(status: DLCStatus): Option[DoubleSha256DigestBE] = {
    status match {
      case status: ClosedDLCStatus =>
        Some(status.closingTxId)
      case _: Offered | _: AcceptedDLCStatus =>
        None
    }
  }

  def getOracleSignatures(
      status: DLCStatus): Option[Vector[SchnorrDigitalSignature]] = {
    status match {
      case claimed: ClaimedDLCStatus =>
        Some(claimed.oracleSigs)
      case _: Offered | _: Accepted | _: Signed | _: BroadcastedDLCStatus |
          _: Refunded =>
        None
    }
  }

  def calculateOutcomeAndSig(
      isInitiator: Boolean,
      offer: DLCOffer,
      accept: DLCAccept,
      sign: DLCSign,
      cet: Transaction): (SchnorrDigitalSignature, OracleOutcome) = {
    val wCET = cet match {
      case wtx: WitnessTransaction => wtx
      case _: NonWitnessTransaction =>
        throw new IllegalArgumentException(s"Expected Witness CET: $cet")
    }

    val cetSigs = wCET.witness.head
      .asInstanceOf[P2WSHWitnessV0]
      .signatures

    require(cetSigs.size == 2,
            s"There must be only 2 signatures, got ${cetSigs.size}")

    /** Extracts an adaptor secret from cetSig assuming it is the completion
      * adaptorSig (which it may not be) and returns the oracle signature if
      * and only if adaptorSig does correspond to cetSig.
      *
      * This method is used to search through possible cetSigs until the correct
      * one is found by validating the returned signature.
      *
      * @param outcome A potential outcome that could have been executed for
      * @param adaptorSig The adaptor signature corresponding to outcome
      * @param cetSig The actual signature for local's key found on-chain on a CET
      */
    def sigFromOutcomeAndSigs(
        outcome: OracleOutcome,
        adaptorSig: ECAdaptorSignature,
        cetSig: ECDigitalSignature): SchnorrDigitalSignature = {
      val sigPubKey = outcome.sigPoint

      // This value is either the oracle signature S value or it is
      // useless garbage, but we don't know in this scope, the caller
      // must do further work to check this.
      val possibleOracleS =
        sigPubKey
          .extractAdaptorSecret(adaptorSig,
                                ECDigitalSignature(cetSig.bytes.init))
          .fieldElement

      SchnorrDigitalSignature(outcome.aggregateNonce, possibleOracleS)
    }

    val outcomeValues = wCET.outputs.map(_.value).sorted
    val totalCollateral = offer.contractInfo.totalCollateral

    val possibleOutcomes = offer.contractInfo.allOutcomesAndPayouts
      .filter { case (_, amt) =>
        val amts = Vector(amt, totalCollateral - amt)
          .filter(_ >= Policy.dustThreshold)
          .sorted

        // Only messages within 1 satoshi of the on-chain CET's value
        // should be considered.
        // Off-by-one is okay because both parties round to the nearest
        // Satoshi for fees and if both round up they could be off-by-one.
        Math.abs((amts.head - outcomeValues.head).satoshis.toLong) <= 1 && Math
          .abs((amts.last - outcomeValues.last).satoshis.toLong) <= 1
      }
      .map(_._1)

    val (offerCETSig, acceptCETSig) =
      if (
        offer.pubKeys.fundingKey.hex.compareTo(
          accept.pubKeys.fundingKey.hex) > 0
      ) {
        (cetSigs.last, cetSigs.head)
      } else {
        (cetSigs.head, cetSigs.last)
      }

    val (cetSig, outcomeSigs) = if (isInitiator) {
      val possibleOutcomeSigs = sign.cetSigs.outcomeSigs.filter {
        case (outcome, _) => possibleOutcomes.contains(outcome)
      }
      (acceptCETSig, possibleOutcomeSigs)
    } else {
      val possibleOutcomeSigs = accept.cetSigs.outcomeSigs.filter {
        case (outcome, _) => possibleOutcomes.contains(outcome)
      }
      (offerCETSig, possibleOutcomeSigs)
    }

    val sigOpt = outcomeSigs.find { case (outcome, adaptorSig) =>
      val possibleOracleSig =
        sigFromOutcomeAndSigs(outcome, adaptorSig, cetSig)
      possibleOracleSig.sig.getPublicKey == outcome.sigPoint
    }

    sigOpt match {
      case Some((outcome, adaptorSig)) =>
        (sigFromOutcomeAndSigs(outcome, adaptorSig, cetSig), outcome)
      case None =>
        throw new IllegalArgumentException("No Oracle Signature found from CET")
    }
  }
}
