package org.bitcoins.commons.serializers

import org.bitcoins.commons.jsonmodels.bitcoind.RpcOpts.*
import org.bitcoins.core.crypto.{ExtKey, ExtPrivateKey, ExtPublicKey}
import org.bitcoins.core.currency.*
import org.bitcoins.core.number.*
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.ln.LnInvoice
import org.bitcoins.core.protocol.ln.currency.MilliSatoshis
import org.bitcoins.core.protocol.script.*
import org.bitcoins.core.protocol.script.descriptor.Descriptor
import org.bitcoins.core.protocol.transaction.*
import org.bitcoins.core.psbt.*
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.serializers.PicklerKeys
import org.bitcoins.core.util.BytesUtil
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto.*
import play.api.libs.json.*

import java.net.URL
import scala.collection.mutable

// for mapWrites below
import scala.language.implicitConversions

object JsonWriters {

  implicit val urlWrites: Writes[URL] = (url: URL) => JsString(url.toString)

  implicit object HashTypeWrites extends Writes[HashType] {

    override def writes(hash: HashType): JsValue =
      hash match {
        case SIGHASH_DEFAULT                => JsString("DEFAULT")
        case _: SIGHASH_ALL                 => JsString("ALL")
        case _: SIGHASH_NONE                => JsString("NONE")
        case _: SIGHASH_SINGLE              => JsString("SINGLE")
        case _: SIGHASH_ALL_ANYONECANPAY    => JsString("ALL|ANYONECANPAY")
        case _: SIGHASH_NONE_ANYONECANPAY   => JsString("NONE|ANYONECANPAY")
        case _: SIGHASH_SINGLE_ANYONECANPAY => JsString("SINGLE|ANYONECANPAY")
        case _: SIGHASH_ANYONECANPAY =>
          throw new IllegalArgumentException(
            "SIGHHASH_ANYONECANPAY is not supported by the bitcoind RPC interface"
          )
      }
  }

  implicit object CurrencyUnitWrites extends Writes[CurrencyUnit] {
    override def writes(o: CurrencyUnit): JsValue = JsNumber(o.satoshis.toLong)
  }

  implicit object SchnorrPublicKeyWrites extends Writes[SchnorrPublicKey] {
    override def writes(o: SchnorrPublicKey): JsValue = JsString(o.hex)
  }

  implicit object SchnorrNonceWrites extends Writes[SchnorrNonce] {
    override def writes(o: SchnorrNonce): JsValue = JsString(o.hex)
  }

  implicit object FieldElementWrites extends Writes[FieldElement] {
    override def writes(o: FieldElement): JsValue = JsString(o.hex)
  }

  implicit object SchnorrDigitalSignatureWrites
      extends Writes[SchnorrDigitalSignature] {
    override def writes(o: SchnorrDigitalSignature): JsValue = JsString(o.hex)
  }

  implicit object ScriptWitnessWrites extends Writes[ScriptWitness] {
    override def writes(o: ScriptWitness): JsValue = JsString(o.hex)
  }

  implicit object ScriptTypeWrites extends Writes[ScriptType] {
    override def writes(o: ScriptType): JsValue = JsString(o.toString)
  }

  implicit object BitcoinsWrites extends Writes[Bitcoins] {
    override def writes(o: Bitcoins): JsValue = JsNumber(o.toBigDecimal)
  }

  implicit object BitcoinAddressWrites extends Writes[BitcoinAddress] {
    override def writes(o: BitcoinAddress): JsValue = JsString(o.value)
  }

  implicit object DoubleSha256DigestWrites extends Writes[DoubleSha256Digest] {
    override def writes(o: DoubleSha256Digest): JsValue = JsString(o.hex)
  }

  implicit object DoubleSha256DigestBEWrites
      extends Writes[DoubleSha256DigestBE] {
    override def writes(o: DoubleSha256DigestBE): JsValue = JsString(o.hex)
  }

  implicit object Sha256DigestWrites extends Writes[Sha256Digest] {
    override def writes(o: Sha256Digest): JsValue = JsString(o.hex)
  }

  implicit object Sha256DigestBEWrites extends Writes[Sha256DigestBE] {
    override def writes(o: Sha256DigestBE): JsValue = JsString(o.hex)
  }

  implicit object ScriptPubKeyWrites extends Writes[ScriptPubKey] {

    override def writes(o: ScriptPubKey): JsValue =
      JsString(BytesUtil.encodeHex(o.asmBytes))
  }

  implicit object WitnessScriptPubKeyWrites
      extends Writes[WitnessScriptPubKey] {

    override def writes(o: WitnessScriptPubKey): JsValue =
      ScriptPubKeyWrites.writes(o)
  }

  implicit object DescriptorWrites extends Writes[Descriptor] {

    override def writes(d: Descriptor): JsValue = {
      JsString(d.toString)
    }
  }

  implicit object ExtKeyWrites extends Writes[ExtKey] {
    override def writes(key: ExtKey): JsValue = {
      val str = key match {
        case xpub: ExtPublicKey  => JsString(xpub.toString)
        case xprv: ExtPrivateKey => JsString(xprv.toStringSensitive)
      }
      str
    }
  }

  implicit object TransactionInputWrites extends Writes[TransactionInput] {

    override def writes(o: TransactionInput): JsValue =
      JsObject(
        Seq(
          ("txid", JsString(o.previousOutput.txIdBE.hex)),
          ("vout", JsNumber(o.previousOutput.vout.toLong)),
          ("sequence", JsNumber(o.sequence.toLong))
        )
      )
  }

  implicit object TransactionOutPointWrites
      extends OWrites[TransactionOutPoint] {

    override def writes(o: TransactionOutPoint): JsObject = {
      Json.obj(
        PicklerKeys.txIdKey -> o.txIdBE.hex,
        PicklerKeys.voutKey -> o.vout.toLong
      )
    }
  }

  implicit object UInt32Writes extends Writes[UInt32] {
    override def writes(o: UInt32): JsValue = JsNumber(o.toLong)
  }

  implicit object UInt64Writes extends Writes[UInt64] {
    override def writes(o: UInt64): JsValue = JsNumber(o.toLong)
  }

  implicit object TransactionWrites extends Writes[Transaction] {
    override def writes(o: Transaction): JsValue = JsString(o.hex)
  }

  implicit object PSBTWrites extends Writes[PSBT] {
    override def writes(o: PSBT): JsValue = JsString(o.base64)
  }

  implicit def mapWrites[K, V](
      keyString: K => String
  )(implicit vWrites: Writes[V]): Writes[Map[K, V]] =
    new Writes[Map[K, V]] {

      override def writes(o: Map[K, V]): JsValue =
        Json.toJson(o.map { case (k, v) => (keyString(k), v) })
    }

  implicit object SatoshisPerVByteWrites
      extends Writes[SatoshisPerVirtualByte] {
    override def writes(o: SatoshisPerVirtualByte): JsValue = JsNumber(o.toLong)
  }

  implicit object SatoshisWrites extends Writes[Satoshis] {
    override def writes(o: Satoshis): JsValue = JsNumber(o.toBigDecimal)
  }

  implicit object MilliSatoshisWrites extends Writes[MilliSatoshis] {
    override def writes(o: MilliSatoshis): JsValue = JsNumber(o.toBigDecimal)
  }

  implicit object AddressTypeWrites extends Writes[AddressType] {
    override def writes(addr: AddressType): JsValue = JsString(addr.toString)
  }

  implicit object LnInvoiceWrites extends Writes[LnInvoice] {

    override def writes(invoice: LnInvoice): JsValue = JsString(
      invoice.toString
    )
  }

  implicit object WalletCreateFundedPsbtOptionsWrites
      extends Writes[WalletCreateFundedPsbtOptions] {

    override def writes(opts: WalletCreateFundedPsbtOptions): JsValue = {
      val jsOpts: mutable.Map[String, JsValue] = mutable.Map(
        "includeWatching" -> JsBoolean(opts.includeWatching),
        "lockUnspents" -> JsBoolean(opts.lockUnspents),
        "replaceable" -> JsBoolean(opts.replaceable),
        "estimate_mode" -> JsString(opts.estimateMode.toString)
      )

      def addToMapIfDefined[T](key: String, opt: Option[T])(implicit
          writes: Writes[T]
      ): Unit =
        opt.foreach(o => jsOpts += (key -> Json.toJson(o)))

      addToMapIfDefined("changeAddress", opts.changeAddress)
      addToMapIfDefined("changePosition", opts.changePosition)
      addToMapIfDefined("change_type", opts.changeType)
      addToMapIfDefined("feeRate", opts.feeRate)
      addToMapIfDefined("subtractFeeFromOutputs", opts.subtractFeeFromOutputs)
      addToMapIfDefined("conf_target", opts.confTarget)

      JsObject(jsOpts)
    }
  }

  implicit object GlobalPSBTRecordUnknownWrites
      extends Writes[GlobalPSBTRecord.Unknown] {

    override def writes(o: GlobalPSBTRecord.Unknown): JsValue =
      JsObject(
        Seq(("key", JsString(o.key.toHex)), ("value", JsString(o.value.toHex)))
      )
  }

  implicit object InputPSBTRecordUnknownWrites
      extends Writes[InputPSBTRecord.Unknown] {

    override def writes(o: InputPSBTRecord.Unknown): JsValue =
      JsObject(
        Seq(("key", JsString(o.key.toHex)), ("value", JsString(o.value.toHex)))
      )
  }

  implicit object OutputPSBTRecordUnknownWrites
      extends Writes[OutputPSBTRecord.Unknown] {

    override def writes(o: OutputPSBTRecord.Unknown): JsValue =
      JsObject(
        Seq(("key", JsString(o.key.toHex)), ("value", JsString(o.value.toHex)))
      )
  }

  implicit object PartialSignatureWrites
      extends Writes[InputPSBTRecord.PartialSignature[DigitalSignature]] {

    override def writes(
        o: InputPSBTRecord.PartialSignature[DigitalSignature]): JsValue =
      JsObject(
        Seq(
          ("pubkey", JsString(o.pubKey.hex)),
          ("signature", JsString(o.signature.hex))
        )
      )
  }

  implicit object InputBIP32PathWrites
      extends Writes[InputPSBTRecord.BIP32DerivationPath] {

    override def writes(o: InputPSBTRecord.BIP32DerivationPath): JsValue =
      JsObject(
        Seq(
          ("pubkey", JsString(o.pubKey.hex)),
          ("master_fingerprint", JsString(o.masterFingerprint.toHex)),
          ("path", JsString(o.path.toString))
        )
      )
  }

  implicit object OutputBIP32PathWrites
      extends Writes[OutputPSBTRecord.BIP32DerivationPath] {

    override def writes(o: OutputPSBTRecord.BIP32DerivationPath): JsValue =
      JsObject(
        Seq(
          ("pubkey", JsString(o.pubKey.hex)),
          ("master_fingerprint", JsString(o.masterFingerprint.toHex)),
          ("path", JsString(o.path.toString))
        )
      )
  }
}
