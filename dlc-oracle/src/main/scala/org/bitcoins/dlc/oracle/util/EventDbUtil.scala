package org.bitcoins.dlc.oracle.util

import org.bitcoins.core.api.dlcoracle.db._
import org.bitcoins.core.api.dlcoracle._
import org.bitcoins.core.protocol.dlc.SigningVersion
import org.bitcoins.core.protocol.tlv._
import org.bitcoins.crypto.SchnorrNonce

trait EventDbUtil {

  /** Takes in a [[EventDescriptorTLV]] and nonces and creates [[EventOutcomeDb]] from them
    * that can be inserted into the database.
    */
  def toEventOutcomeDbs(
      descriptor: EventDescriptorTLV,
      nonces: Vector[SchnorrNonce],
      signingVersion: SigningVersion): Vector[EventOutcomeDb] = {
    descriptor match {
      case enum: EnumEventDescriptorV0TLV =>
        require(nonces.size == 1, "Enum events should only have one R value")
        val nonce = nonces.head
        enum.outcomes.map { outcome =>
          val attestationType = EnumAttestation(outcome)
          val hash =
            signingVersion.calcOutcomeHash(enum, attestationType.bytes)
          EventOutcomeDb(nonce, outcome, hash)
        }
      case decomp: DigitDecompositionEventDescriptorV0TLV =>
        val signDbs = decomp match {
          case _: SignedDigitDecompositionEventDescriptor =>
            val plusHash = signingVersion.calcOutcomeHash(decomp, "+")
            val minusHash = signingVersion.calcOutcomeHash(decomp, "-")
            Vector(EventOutcomeDb(nonces.head, "+", plusHash),
                   EventOutcomeDb(nonces.head, "-", minusHash))
          case _: UnsignedDigitDecompositionEventDescriptor =>
            Vector.empty
        }

        val digitNonces = decomp match {
          case _: UnsignedDigitDecompositionEventDescriptor =>
            nonces
          case _: SignedDigitDecompositionEventDescriptor =>
            nonces.tail
        }

        val digitDbs = digitNonces.flatMap { nonce =>
          0.until(decomp.base.toInt).map { num =>
            val attestationType = DigitDecompositionAttestation(num)
            val hash =
              signingVersion.calcOutcomeHash(decomp, attestationType.bytes)
            EventOutcomeDb(nonce, num.toString, hash)
          }
        }
        signDbs ++ digitDbs
    }
  }

  def toEventOutcomeDbs(
      oracleAnnouncementV0TLV: OracleAnnouncementV0TLV,
      signingVersion: SigningVersion = SigningVersion.latest): Vector[
    EventOutcomeDb] = {
    toEventOutcomeDbs(descriptor =
                        oracleAnnouncementV0TLV.eventTLV.eventDescriptor,
                      nonces = oracleAnnouncementV0TLV.eventTLV.nonces,
                      signingVersion = signingVersion)
  }

  def toEventDbs(
      oracleAnnouncementV0TLV: OracleAnnouncementV0TLV,
      eventName: String,
      signingVersion: SigningVersion = SigningVersion.latest): Vector[
    EventDb] = {
    val nonces = oracleAnnouncementV0TLV.eventTLV.nonces
    nonces.zipWithIndex.map { case (nonce, index) =>
      EventDb(
        nonce = nonce,
        pubkey = oracleAnnouncementV0TLV.publicKey,
        nonceIndex = index,
        eventName = eventName,
        numOutcomes = nonces.size,
        signingVersion = signingVersion,
        maturationTime = oracleAnnouncementV0TLV.eventTLV.maturation,
        attestationOpt = None,
        outcomeOpt = None,
        announcementSignature = oracleAnnouncementV0TLV.announcementSignature,
        eventDescriptorTLV = oracleAnnouncementV0TLV.eventTLV.eventDescriptor
      )
    }
  }
}

object EventDbUtil extends EventDbUtil
