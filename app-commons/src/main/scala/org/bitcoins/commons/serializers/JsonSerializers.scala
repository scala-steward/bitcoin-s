package org.bitcoins.commons.serializers

import org.bitcoins.commons.jsonmodels.SerializedTransaction.tokenToString
import org.bitcoins.commons.jsonmodels._
import org.bitcoins.commons.jsonmodels.bitcoind.RpcOpts.AddressType
import org.bitcoins.commons.jsonmodels.bitcoind.{GetRawMempoolVerboseResult, _}
import org.bitcoins.commons.jsonmodels.clightning.CLightningJsonModels._
import org.bitcoins.commons.jsonmodels.wallet._
import org.bitcoins.commons.serializers.JsonReaders._
import org.bitcoins.commons.serializers.JsonWriters._
import org.bitcoins.core.crypto._
import org.bitcoins.core.currency.{Bitcoins, Satoshis}
import org.bitcoins.core.hd.BIP32Path
import org.bitcoins.core.number.{Int32, UInt32, UInt64}
import org.bitcoins.core.protocol._
import org.bitcoins.core.protocol.blockchain.{Block, BlockHeader, MerkleBlock}
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt._
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.script.constant.ScriptToken
import org.bitcoins.core.wallet.fee._
import org.bitcoins.crypto._
import play.api.libs.functional.syntax._
import play.api.libs.json._
import scodec.bits.ByteVector

import java.io.File
import java.net.{InetAddress, URI}
import java.time.LocalDateTime
import scala.concurrent.duration.DurationLong

object JsonSerializers {

  implicit val bigIntReads: Reads[BigInt] = BigIntReads
  implicit val localDateTimeReads: Reads[LocalDateTime] = LocalDateTimeReads

  // Internal Types
  implicit val doubleSha256DigestReads: Reads[DoubleSha256Digest] =
    DoubleSha256DigestReads

  implicit val doubleSha256DigestBEReads: Reads[DoubleSha256DigestBE] =
    DoubleSha256DigestBEReads

  implicit val sha256DigestReads: Reads[Sha256Digest] = Sha256DigestReads

  implicit val ripeMd160DigestReads: Reads[RipeMd160Digest] =
    RipeMd160DigestReads

  implicit val ripeMd160DigestBEReads: Reads[RipeMd160DigestBE] =
    RipeMd160DigestBEReads
  implicit val bitcoinsReads: Reads[Bitcoins] = BitcoinsReads
  implicit val satoshisReads: Reads[Satoshis] = SatoshisReads
  implicit val blockHeaderReads: Reads[BlockHeader] = BlockHeaderReads
  implicit val int32Reads: Reads[Int32] = Int32Reads
  implicit val uInt32Reads: Reads[UInt32] = UInt32Reads
  implicit val uInt64Reads: Reads[UInt64] = UInt64Reads
  implicit val addressReads: Reads[Address] = AddressReads
  implicit val unitReads: Reads[Unit] = UnitReads
  implicit val inetAddressReads: Reads[InetAddress] = InetAddressReads
  implicit val scriptPubKeyReads: Reads[ScriptPubKey] = ScriptPubKeyReads
  implicit val blockReads: Reads[Block] = BlockReads

  implicit val sha256Hash160DigestReads: Reads[Sha256Hash160Digest] =
    Sha256Hash160DigestReads
  implicit val eCPublicKeyReads: Reads[ECPublicKey] = ECPublicKeyReads

  implicit val eCPublicKeyBytesReads: Reads[ECPublicKeyBytes] =
    ECPublicKeyBytesReads
  implicit val p2PKHAddressReads: Reads[P2PKHAddress] = P2PKHAddressReads
  implicit val p2SHAddressReads: Reads[P2SHAddress] = P2SHAddressReads

  implicit val transactionInputReads: Reads[TransactionInput] =
    TransactionInputReads
  implicit val bitcoinAddressReads: Reads[BitcoinAddress] = BitcoinAddressReads
  implicit val merkleBlockReads: Reads[MerkleBlock] = MerkleBlockReads
  implicit val transactionReads: Reads[Transaction] = TransactionReads
  implicit val psbtReads: Reads[PSBT] = PSBTReads

  implicit val transactionOutPointReads: Reads[TransactionOutPoint] =
    TransactionOutPointReads
  implicit val bitcoinFeeUnitReads: Reads[BitcoinFeeUnit] = BitcoinFeeUnitReads
  implicit val fileReads: Reads[File] = FileReads
  implicit val uRIReads: Reads[URI] = URIReads

  implicit val scriptSignatureReads: Reads[ScriptSignature] =
    ScriptSignatureReads

  implicit val addressTypeWrites: Writes[AddressType] = AddressTypeWrites
  implicit val bitcoinsWrites: Writes[Bitcoins] = BitcoinsWrites

  implicit val bitcoinAddressWrites: Writes[BitcoinAddress] =
    BitcoinAddressWrites

  implicit val doubleSha256DigestWrites: Writes[DoubleSha256Digest] =
    DoubleSha256DigestWrites
  implicit val scriptPubKeyWrites: Writes[ScriptPubKey] = ScriptPubKeyWrites

  implicit val witnessScriptPubKeyWrites: Writes[WitnessScriptPubKey] =
    WitnessScriptPubKeyWrites

  implicit val transactionInputWrites: Writes[TransactionInput] =
    TransactionInputWrites
  implicit val uInt32Writes: Writes[UInt32] = UInt32Writes
  implicit val transactionWrites: Writes[Transaction] = TransactionWrites

  implicit val xpubFormat: Format[ExtPublicKey] = new Format[ExtPublicKey] {

    override def reads(json: JsValue): JsResult[ExtPublicKey] =
      SerializerUtil.processJsStringOpt(ExtPublicKey.fromStringOpt(_))(json)

    override def writes(key: ExtPublicKey): JsValue = JsString(key.toString)
  }

  implicit val xprivForamt: Format[ExtPrivateKey] = new Format[ExtPrivateKey] {

    override def reads(json: JsValue): JsResult[ExtPrivateKey] =
      SerializerUtil.processJsStringOpt(ExtPrivateKey.fromStringOpt(_))(json)
    override def writes(key: ExtPrivateKey): JsValue = JsString(key.toString)
  }

  // Transaction Models
  implicit val rpcScriptPubKeyPreV22Reads: Reads[RpcScriptPubKeyPreV22] =
    ((__ \ "asm").read[String] and
      (__ \ "hex").read[String] and
      (__ \ "reqSigs").readNullable[Int] and
      (__ \ "type").read[ScriptType] and
      (__ \ "addresses")
        .readNullable[Vector[BitcoinAddress]])(RpcScriptPubKeyPreV22.apply)

  implicit val rpcScriptPubKeyPostV22Reads: Reads[RpcScriptPubKeyPostV22] =
    Json.reads[RpcScriptPubKeyPostV22]

  implicit val rpcTransactionOutputPreV22Reads
      : Reads[RpcTransactionOutputPreV22] =
    Json.reads[RpcTransactionOutputPreV22]

  implicit val rpcTransactionOutputV22Reads: Reads[RpcTransactionOutputV22] =
    Json.reads[RpcTransactionOutputV22]

  implicit val rpcTransactionV22Reads: Reads[RpcTransactionV22] =
    Json.reads[RpcTransactionV22]

  implicit val decodeScriptResultV22Reads: Reads[DecodeScriptResultV22] = {
    Json.reads[DecodeScriptResultV22]
  }

  implicit val fundRawTransactionResultReads: Reads[FundRawTransactionResult] =
    Json.reads[FundRawTransactionResult]

  implicit val signRawTransactionWithWalletResultReads
      : Reads[SignRawTransactionWithWalletResult] =
    Json.reads[SignRawTransactionWithWalletResult]

  implicit val getRawTransactionScriptSigReads
      : Reads[GetRawTransactionScriptSig] =
    Json.reads[GetRawTransactionScriptSig]

  implicit val getRawTransactionVinReads: Reads[GetRawTransactionVin] =
    Json.reads[GetRawTransactionVin]

  implicit val getRawTransactionResultV22Reads
      : Reads[GetRawTransactionResultV22] =
    Json.reads[GetRawTransactionResultV22]

  implicit val signRawTransactionErrorReads: Reads[SignRawTransactionError] =
    Json.reads[SignRawTransactionError]

  implicit val signRawTransactionResultReads: Reads[SignRawTransactionResult] =
    Json.reads[SignRawTransactionResult]

  // Network Models
  implicit val nodeAddressReads: Reads[NodeAddress] = Json.reads[NodeAddress]
  implicit val nodeReads: Reads[Node] = Json.reads[Node]

  implicit val netTargetReads: Reads[NetTarget] = Json.reads[NetTarget]

  implicit val getNetTotalsResultReads: Reads[GetNetTotalsResult] =
    Json.reads[GetNetTotalsResult]

  implicit val networkReads: Reads[Network] = Json.reads[Network]

  implicit val networkAddressReads: Reads[NetworkAddress] =
    Json.reads[NetworkAddress]

  implicit val getNetworkInfoV28Reads: Reads[GetNetworkInfoResultV28] =
    Json.reads[GetNetworkInfoResultV28]

  implicit val getNetworkInfoPostV21Reads: Reads[GetNetworkInfoResultPostV21] =
    Json.reads[GetNetworkInfoResultPostV21]

  implicit val getNetworkInfoReads: Reads[GetNetworkInfoResult] = {
    Json.reads[GetNetworkInfoResult]
  }

  implicit val satsPerKbReads: Reads[SatoshisPerKiloByte] =
    new Reads[SatoshisPerKiloByte] {

      def reads(json: JsValue): JsResult[SatoshisPerKiloByte] =
        SerializerUtil.processJsNumber(num =>
          SatoshisPerKiloByte(Satoshis(num.toBigInt)))(json)
    }

  implicit val satsPerVBReads: Reads[SatoshisPerVirtualByte] =
    new Reads[SatoshisPerVirtualByte] {

      def reads(json: JsValue): JsResult[SatoshisPerVirtualByte] =
        SerializerUtil.processJsNumber(num =>
          SatoshisPerVirtualByte(Satoshis(num.toBigInt)))(json)
    }

  implicit val TransactionOutputReads: Reads[TransactionOutput] =
    Json.reads[TransactionOutput]

  implicit val TransactionOutputWrites: OWrites[TransactionOutput] =
    Json.writes[TransactionOutput]

  implicit val transactionOutPointWrites: OWrites[TransactionOutPoint] =
    TransactionOutPointWrites

  implicit val OutputReferenceReads: Reads[OutputReference] =
    Json.reads[OutputReference]

  implicit val OutputReferenceWrites: OWrites[OutputReference] =
    Json.writes[OutputReference]

  implicit val peerNetworkInfoPreV21Reads: Reads[PeerNetworkInfoPreV21] =
    Json.reads[PeerNetworkInfoPreV21]

  implicit val peerNetworkInfoPostV21Reads: Reads[PeerNetworkInfoPostV21] =
    Json.reads[PeerNetworkInfoPostV21]

  implicit val peerPostV25Reads: Reads[PeerInfoResponseV25] =
    ((__ \ "id").read[Int] and
      __.read[PeerNetworkInfoPostV21] and
      (__ \ "version").read[Int] and
      (__ \ "subver").read[String] and
      (__ \ "inbound").read[Boolean] and
      (__ \ "connection_type").read[String] and
      (__ \ "startingheight").read[Int] and
      (__ \ "synced_headers").read[Int] and
      (__ \ "synced_blocks").read[Int] and
      (__ \ "inflight").read[Vector[Int]] and
      (__ \ "bytessent_per_msg").read[Map[String, Int]] and
      (__ \ "bytesrecv_per_msg").read[Map[String, Int]] and
      (__ \ "minfeefilter").readNullable[SatoshisPerKiloByte] and
      (__ \ "bip152_hb_to").read[Boolean] and
      (__ \ "bip152_hb_from").read[Boolean] and
      (__ \ "permissions").read[Vector[String]] and
      (__ \ "transport_protocol_type").read[String] and
      (__ \ "session_id").read[String])(PeerInfoResponseV25.apply)

  implicit val nodeBanPostV22Reads: Reads[NodeBanPostV22] =
    Json.reads[NodeBanPostV22]

  implicit val nodeBanPreV20Reads: Reads[NodeBanPreV20] =
    Json.reads[NodeBanPreV20]

  // Blockchain Models
  implicit val dumpTxOutSetResultReads: Reads[DumpTxOutSetResult] =
    Json.reads[DumpTxOutSetResult]

  implicit val loadTxOutSetResultReads: Reads[LoadTxOutSetResult] = {
    Json.reads[LoadTxOutSetResult]
  }

  implicit val getBlockResultReads: Reads[GetBlockResult] =
    Json.reads[GetBlockResult]

  implicit val getBlockWithTransactionsResultV22Reads
      : Reads[GetBlockWithTransactionsResultV22] =
    Json.reads[GetBlockWithTransactionsResultV22]

  implicit val softforkProgressPreV19Reads: Reads[SoftforkProgressPreV19] =
    Json.reads[SoftforkProgressPreV19]

  implicit val softforkPreV19Reads: Reads[SoftforkPreV19] =
    Json.reads[SoftforkPreV19]

  implicit val bip9SoftforkPreV19Reads: Reads[Bip9SoftforkPreV19] =
    Json.reads[Bip9SoftforkPreV19]

  implicit val bip9SoftforkDetailsReads: Reads[Bip9SoftforkDetails] =
    Json.reads[Bip9SoftforkDetails]

  implicit val softforkPostV19Reads: Reads[SoftforkPostV19] =
    Reads[SoftforkPostV19] { json =>
      (json \ "type").validate[String].flatMap {
        case "bip9" => Json.reads[Bip9SoftforkPostV19].reads(json)
        case _      => Json.reads[BuriedSoftforkPostV19].reads(json)
      }
    }

  implicit val getBlockChainInfoResultPostV23Reads
      : Reads[GetBlockChainInfoResultPostV23] =
    Json.reads[GetBlockChainInfoResultPostV23]

  implicit val getBlockChainInfoResultPost27Reads
      : Reads[GetBlockChainInfoResultPostV27] = {
    Json.reads[GetBlockChainInfoResultPostV27]
  }

  implicit val getBlockchainInfoResult: Reads[GetBlockChainInfoResult] = {
    Json.reads[GetBlockChainInfoResult]
  }

  implicit val blockHeaderFormattedReads: Reads[GetBlockHeaderResult] =
    Json.reads[GetBlockHeaderResult]

  implicit val chainTipReads: Reads[ChainTip] = Json.reads[ChainTip]

  implicit val getChainTxStatsResultReads: Reads[GetChainTxStatsResult] =
    Json.reads[GetChainTxStatsResult]

  implicit val feeInfoReads: Reads[FeeInfo] = Json.reads[FeeInfo]

  implicit val getMemPoolResultPreV19Reads: Reads[GetMemPoolResultPreV19] =
    Json.reads[GetMemPoolResultPreV19]

  implicit val getMemPoolResultPostV23Reads: Reads[GetMemPoolResultPostV23] =
    Json.reads[GetMemPoolResultPostV23]

  implicit val getMemPoolEntryResultPreV19Reads
      : Reads[GetMemPoolEntryResultPreV19] =
    Json.reads[GetMemPoolEntryResultPreV19]

  implicit val getMemPoolEntryResultPostV23Reads
      : Reads[GetMemPoolEntryResultPostV23] =
    Json.reads[GetMemPoolEntryResultPostV23]

  implicit val getMemPoolInfoResultReads: Reads[GetMemPoolInfoResult] =
    Json.reads[GetMemPoolInfoResult]

  implicit val getRawMempoolVerboseResultReads
      : Reads[GetRawMempoolVerboseResult] =
    Json.reads[GetRawMempoolVerboseResult]

  implicit object GetRawMempoolTxIdsReads extends Reads[GetRawMempoolTxIds] {
    override def reads(json: JsValue): JsResult[GetRawMempoolTxIds] = {
      json match {
        case arr: JsArray =>
          arr
            .validate[Vector[DoubleSha256DigestBE]]
            .map(GetRawMempoolTxIds.apply)
        case x @ (_: JsNumber | _: JsString | JsNull | _: JsObject |
            _: JsBoolean) =>
          JsError(s"Invalid json for getrawmempool, got=$x")
      }
    }
  }
  implicit object GetRawMempoolVerboseReads
      extends Reads[GetRawMempoolVerbose] {
    override def reads(json: JsValue): JsResult[GetRawMempoolVerbose] = {
      json
        .validate[Map[DoubleSha256DigestBE, GetRawMempoolVerboseResult]]
        .map(GetRawMempoolVerbose.apply)
    }
  }

  implicit val getRawMempoolResultReads: Reads[GetRawMempoolResult] =
    Json.reads[GetRawMempoolResult]

  implicit val getTxOutResultV22Reads: Reads[GetTxOutResultV22] =
    Json.reads[GetTxOutResultV22]

  implicit val getTxOutResultV22OptReads: Reads[Option[GetTxOutResultV22]] = {
    case JsNull => JsSuccess(None) // Handle null case
    case json: JsValue =>
      getTxOutResultV22Reads.reads(json).map(Some(_)) // Handle valid object
  }

  implicit val getTxOutSetInfoResultReads: Reads[GetTxOutSetInfoResult] =
    Json.reads[GetTxOutSetInfoResult]

  implicit val GetTxSpendingPrevOutResultReads
      : Reads[GetTxSpendingPrevOutResult] =
    Json.reads[GetTxSpendingPrevOutResult]

  implicit val SimulateRawTransactionResultReads
      : Reads[SimulateRawTransactionResult] =
    Json.reads[SimulateRawTransactionResult]

  implicit object Bip32PathFormats extends Format[BIP32Path] {

    override def reads(json: JsValue): JsResult[BIP32Path] =
      json.validate[String].map(BIP32Path.fromString)

    override def writes(o: BIP32Path): JsValue =
      JsString(o.toString)
  }

  implicit val multiSigPostV20Reads: Reads[MultiSigResultPostV20] =
    Json.reads[MultiSigResultPostV20]

  implicit val bumpFeeReads: Reads[BumpFeeResult] = Json.reads[BumpFeeResult]

  implicit val setWalletFlagResultReads: Reads[SetWalletFlagResult] =
    Json.reads[SetWalletFlagResult]

  implicit val balanceInfoReads: Reads[BalanceInfo] = Json.reads[BalanceInfo]

  implicit val getBalancesResultReads: Reads[GetBalancesResult] =
    Json.reads[GetBalancesResult]

  implicit val TransactionDetailsReads: Reads[TransactionDetails] =
    Json.reads[TransactionDetails]

  implicit val hdKeyDescriptor: Reads[HDKeyDescriptor] =
    Json.reads[HDKeyDescriptor]
  implicit val getHDKeysReads: Reads[GetHDKeysResult] = {
    Json.reads[GetHDKeysResult]
  }
  implicit val getTranasctionResultPreV28: Reads[GetTransactionResultPreV28] = {
    Json.reads[GetTransactionResultPreV28]
  }
  implicit val getTransactionResultV28: Reads[GetTransactionResultV28] = {
    Json.reads[GetTransactionResultV28]
  }

  implicit val getTransactionResultReads: Reads[GetTransactionResult] = {
    Json.reads[GetTransactionResult]
  }

  implicit val getWalletInfoResultReadsPostV22
      : Reads[GetWalletInfoResultPostV22] =
    Json.reads[GetWalletInfoResultPostV22]

  implicit val importMultiErrorReads: Reads[ImportMultiError] =
    Json.reads[ImportMultiError]

  implicit val importMultiResultReads: Reads[ImportMultiResult] =
    Json.reads[ImportMultiResult]

  implicit val rpcAddressReads: Reads[RpcAddress] = RpcAddressReads

  implicit val rpcAccoutReads: Reads[RpcAccount] = Json.reads[RpcAccount]

  implicit val loadWalletResultReads: Reads[LoadWalletResult] =
    Json.reads[LoadWalletResult]

  implicit val rescanBlockChainResultReads: Reads[RescanBlockChainResult] =
    Json.reads[RescanBlockChainResult]

  implicit val receivedAddressReads: Reads[ReceivedAddress] =
    Json.reads[ReceivedAddress]

  implicit val receivedAccountReads: Reads[ReceivedAccount] =
    Json.reads[ReceivedAccount]

  implicit val labelResult: Reads[LabelResult] =
    Json.reads[LabelResult]

  implicit val paymentReads: Reads[Payment] =
    ((__ \ "involvesWatchonly").readNullable[Boolean] and
      (__ \ "account").readNullable[String] and
      (__ \ "address").readNullable[BitcoinAddress] and
      (__ \ "category").read[String] and
      (__ \ "amount").read[Bitcoins] and
      (__ \ "vout").read[Int] and
      (__ \ "fee").readNullable[Bitcoins] and
      (__ \ "confirmations").read[Int] and
      (__ \ "generated").readNullable[Boolean] and
      (__ \ "blockhash").readNullable[DoubleSha256DigestBE] and
      (__ \ "blockindex").readNullable[Int] and
      (__ \ "blocktime").readNullable[UInt32] and
      (__ \ "txid").read[DoubleSha256DigestBE] and
      (__ \ "walletconflicts").read[Vector[DoubleSha256DigestBE]] and
      (__ \ "time").read[UInt32] and
      (__ \ "timereceived").read[UInt32] and
      (__ \ "bip125-replaceable").read[String] and
      (__ \ "comment").readNullable[String] and
      (__ \ "to").readNullable[String])(Payment.apply)

  implicit val listSinceBlockResultReads: Reads[ListSinceBlockResult] =
    Json.reads[ListSinceBlockResult]

  implicit val listTransactionsResultReads: Reads[ListTransactionsResult] =
    Reads[ListTransactionsResult] { js =>
      for {
        account <- (js \ "account").validateOpt[String]
        address <- (js \ "address").validateOpt[BitcoinAddress]
        category <- (js \ "category").validate[String]
        amount <- (js \ "amount").validate[Bitcoins]
        label <- (js \ "label").validateOpt[String]
        vout <- (js \ "vout").validateOpt[Int]
        fee <- (js \ "fee").validateOpt[Bitcoins]
        confirmations <- (js \ "confirmations").validateOpt[Int]
        trusted <- (js \ "trusted").validateOpt[Boolean]
        generated <- (js \ "generated").validateOpt[Boolean]
        blockhash <- (js \ "blockhash").validateOpt[DoubleSha256DigestBE]
        blockheight <- (js \ "blockheight").validateOpt[Int]
        blockindex <- (js \ "blockindex").validateOpt[Int]
        blocktime <- (js \ "blocktime").validateOpt[UInt32]
        txid <- (js \ "txid").validateOpt[DoubleSha256DigestBE]
        walletconflicts <- (js \ "walletconflicts")
          .validateOpt[Vector[DoubleSha256DigestBE]]
        time <- (js \ "time").validate[UInt32]
        timereceived <- (js \ "timereceived").validateOpt[UInt32]
        comment <- (js \ "comment").validateOpt[String]
        to <- (js \ "to").validateOpt[String]
        otheraccount <- (js \ "otheraccount").validateOpt[String]
        bip125_replaceable <- (js \ "bip125-replaceable").validate[String]
        abandoned <- (js \ "abandoned").validateOpt[Boolean]
      } yield ListTransactionsResult(
        account = account,
        address = address,
        category = category,
        amount = amount,
        label = label,
        vout = vout,
        fee = fee,
        confirmations = confirmations,
        trusted = trusted,
        generated = generated,
        blockhash = blockhash,
        blockheight = blockheight,
        blockindex = blockindex,
        blocktime = blocktime,
        txid = txid,
        walletconflicts = walletconflicts,
        time = time,
        timereceived = timereceived,
        comment = comment,
        to = to,
        otheraccount = otheraccount,
        bip125_replaceable = bip125_replaceable,
        abandoned = abandoned
      )
    }

  implicit val descriptorsResultReads: Reads[DescriptorsResult] =
    Json.reads[DescriptorsResult]

  implicit val descriptorResultWrites: Writes[DescriptorsResult] =
    Json.writes[DescriptorsResult]

  implicit val listDescriptorsReads: Reads[ListDescriptorsResult] =
    Json.reads[ListDescriptorsResult]

  implicit val unspentOutputReads: Reads[UnspentOutput] =
    Json.reads[UnspentOutput]

  // Other Models
  implicit val blockTransactionReads: Reads[BlockTransaction] =
    Json.reads[BlockTransaction]

  implicit val getBlockTemplateResultReads: Reads[GetBlockTemplateResult] =
    Json.reads[GetBlockTemplateResult]

  implicit val miningInfoResultPre28: Reads[GetMiningInfoResultPre28] = {
    Json.reads[GetMiningInfoResultPre28]
  }
  implicit val miningInfoResultV28: Reads[GetMiningInfoResultV28] = {
    Json.reads[GetMiningInfoResultV28]
  }
  implicit val miningInfoReads: Reads[GetMiningInfoResult] =
    Json.reads[GetMiningInfoResult]

  implicit val memoryManagerReads: Reads[MemoryManager] =
    Json.reads[MemoryManager]

  implicit val getMemoryInfoResultReads: Reads[GetMemoryInfoResult] =
    Json.reads[GetMemoryInfoResult]

  implicit val GenerateBlockResultReads: Reads[GenerateBlockResult] =
    Json.reads[GenerateBlockResult]

  implicit val validateAddressResultReads: Reads[ValidateAddressResultImpl] =
    Json.reads[ValidateAddressResultImpl]

  implicit val embeddedResultReads: Reads[EmbeddedResult] =
    Json.reads[EmbeddedResult]

  implicit val addressInfoResultPostV21Reads
      : Reads[AddressInfoResultPostV21] = {
    Reads[AddressInfoResultPostV21] { json =>
      for {
        isProps <-
          Json.reads[AddressInfoResultPostV21.AddressInfoIsProps].reads(json)
        infoWithoutProps <-
          Json
            .reads[
              AddressInfoResultPostV21.AddressInfoResultPostV21WithoutIsProps
            ]
            .reads(json)
      } yield {
        AddressInfoResultPostV21(infoWithoutProps, isProps)
      }
    }
  }

  implicit val receivedLabelReads: Reads[ReceivedLabel] =
    Json.reads[ReceivedLabel]

  implicit val estimateSmartFeeResultReads: Reads[EstimateSmartFeeResult] =
    Json.reads[EstimateSmartFeeResult]

  implicit val walletProcessPsbtResultReads: Reads[WalletProcessPsbtResult] =
    Json.reads[WalletProcessPsbtResult]

  implicit val finalizedPsbtReads: Reads[FinalizedPsbt] = FinalizedPsbtReads

  implicit val nonFinalizedPsbtReads: Reads[NonFinalizedPsbt] =
    NonFinalizedPsbtReads

  implicit val finalizePsbtResultReads: Reads[FinalizePsbtResult] =
    FinalizePsbtResultReads

  implicit val rpcPsbtOutputReads: Reads[RpcPsbtOutput] = RpcPsbtOutputReads

  implicit val psbtBIP32DerivsReads: Reads[PsbtBIP32Deriv] =
    PsbtBIP32DerivsReads

  implicit val rpcPsbtScriptReads: Reads[RpcPsbtScript] = RpcPsbtScriptReads

  implicit val psbtWitnessUtxoInputReads: Reads[PsbtWitnessUtxoInput] =
    Json.reads[PsbtWitnessUtxoInput]

  implicit val mapPubKeySignatureReads
      : Reads[Map[ECPublicKey, ECDigitalSignature]] = MapPubKeySignatureReads

  implicit val rpcPsbtInputV22Reads: Reads[RpcPsbtInputV22] =
    RpcPsbtInputV22Reads

  implicit val decodePsbtResultV22Reads: Reads[DecodePsbtResultV22] =
    Json.reads[DecodePsbtResultV22]

  implicit val psbtMissingDataReads: Reads[PsbtMissingData] =
    Json.reads[PsbtMissingData]

  implicit val analyzePsbtInputReads: Reads[AnalyzePsbtInput] =
    Json.reads[AnalyzePsbtInput]

  implicit val analyzePsbtResultReads: Reads[AnalyzePsbtResult] =
    Json.reads[AnalyzePsbtResult]

  implicit val getNodeAddressesPostV22Reads
      : Reads[GetNodeAddressesResultPostV22] =
    Reads[GetNodeAddressesResultPostV22] { js =>
      for {
        time <- (js \ "time").validate[Long].map(_.seconds)
        services <- (js \ "services").validate[Int]
        address <- (js \ "address").validate[URI]
        port <- (js \ "port").validate[Int]
        network <- (js \ "network").validate[String]
      } yield GetNodeAddressesResultPostV22(
        time,
        services,
        address,
        port,
        network
      )
    }

  implicit val rgetpcCommandsReads: Reads[RpcCommands] = Reads[RpcCommands] {
    js =>
      for {
        method <- (js \ "method").validate[String]
        duration <- (js \ "duration").validate[Long].map(_.microseconds)
      } yield RpcCommands(method, duration)
  }

  implicit val getRpcInfoResultReads: Reads[GetRpcInfoResult] =
    Json.reads[GetRpcInfoResult]

  implicit val arrayOfWalletsInputReads: Reads[ArrayOfWalletsInput] =
    Json.reads[ArrayOfWalletsInput]

  implicit val listWalletsDirResultReads: Reads[ListWalletDirResult] =
    Json.reads[ListWalletDirResult]

  implicit val submitHeaderResultReads: Reads[SubmitHeaderResult] =
    Json.reads[SubmitHeaderResult]

  implicit val getDescriptorInfoResultReads: Reads[GetDescriptorInfoResult] =
    Json.reads[GetDescriptorInfoResult]

  implicit val walletCreateFundedPsbtResultReads
      : Reads[WalletCreateFundedPsbtResult] =
    Json.reads[WalletCreateFundedPsbtResult]

  implicit val scriptTypeReads: Reads[ScriptType] = ScriptTypeReads

  implicit val testMempoolAcceptResultReads: Reads[TestMempoolAcceptResult] =
    TestMempoolAcceptResultReads

  implicit object FeeInfoTwoReads extends Reads[FeeInfoTwo] {
    override def reads(json: JsValue): JsResult[FeeInfoTwo] =
      for {
        base <- (json \ "base").validate[BitcoinFeeUnit]
        effective_feerate <- (json \ "effective-feerate")
          .validate[BigDecimal] // BTC/kvb, wtf?
        effective_includes <- (json \ "effective-includes")
          .validate[Vector[DoubleSha256DigestBE]]
      } yield FeeInfoTwo(base = base,
                         effective_feerate = effective_feerate,
                         effective_includes = effective_includes)
  }

  implicit val testMempoolAcceptResultReadsPostV22
      : Reads[TestMempoolAcceptResultPostV24] =
    Json.reads[TestMempoolAcceptResultPostV24]

  implicit val indexInfoResultReads: Reads[IndexInfoResult] =
    Json.reads[IndexInfoResult]

  implicit val createWalletResultReads: Reads[CreateWalletResult] =
    Json.reads[CreateWalletResult]

  implicit val bitcoinerLiveEstimateReads: Reads[BitcoinerLiveEstimate] =
    Json.reads[BitcoinerLiveEstimate]

  implicit val bitcoinerLiveResultReads: Reads[BitcoinerLiveResult] =
    Json.reads[BitcoinerLiveResult]

  implicit val bitGoResultReads: Reads[BitGoResult] =
    Json.reads[BitGoResult]

  implicit val mempoolSpaceResultReads: Reads[MempoolSpaceResult] =
    Json.reads[MempoolSpaceResult]

  implicit val cLightningAddressReads: Reads[CLightningAddress] =
    Json.reads[CLightningAddress]

  implicit val cLightningInfoReads: Reads[CLightningInfo] =
    Json.reads[CLightningInfo]

  implicit val cLightningNewAddressResultReads: Reads[NewAddressResult] =
    Json.reads[NewAddressResult]

  implicit val cLightningOutputReads: Reads[Output] = Json.reads[Output]
  implicit val cLightningChannelReads: Reads[Channel] = Json.reads[Channel]

  implicit val cLightningListChannelsResultReads: Reads[ListChannelsResult] =
    Json.reads[ListChannelsResult]

  implicit val cLightningChannelFundsReads: Reads[ChannelFunds] =
    Json.reads[ChannelFunds]

  implicit val cLightningListFundsResultReads: Reads[ListFundsResult] =
    Json.reads[ListFundsResult]

  implicit val cLightningConnectResultReads: Reads[ConnectResult] =
    Json.reads[ConnectResult]

  implicit val cLightningPeerChannelReads: Reads[CLightningPeerChannel] =
    Json.reads[CLightningPeerChannel]

  implicit val cLightningPeerReads: Reads[CLightningPeer] =
    Json.reads[CLightningPeer]

  implicit val cLightningPeersReads: Reads[CLightningPeers] =
    Json.reads[CLightningPeers]

  implicit val cLightningFundChannelResultReads: Reads[FundChannelResult] =
    Json.reads[FundChannelResult]

  implicit val cLightningInvoiceResultReads: Reads[CLightningInvoiceResult] =
    Json.reads[CLightningInvoiceResult]

  implicit val cLightningLookupInvoiceResultReads
      : Reads[CLightningLookupInvoiceResult] =
    Json.reads[CLightningLookupInvoiceResult]

  implicit val cLightningListInvoicesResultReads
      : Reads[CLightningListInvoicesResult] =
    Json.reads[CLightningListInvoicesResult]

  implicit val cLightningPayResultReads: Reads[CLightningPayResult] =
    Json.reads[CLightningPayResult]

  implicit val cLightningPsbtResultReads: Reads[CLightningPsbtResult] =
    Json.reads[CLightningPsbtResult]

  implicit val cLightningInputReservationReads: Reads[InputReservation] =
    Json.reads[InputReservation]

  implicit val cLightningInputReservationsReads: Reads[InputReservations] =
    Json.reads[InputReservations]

  implicit val cLightningCloseChannelResultReads: Reads[CloseChannelResult] =
    Json.reads[CloseChannelResult]

  implicit val cLightningWithdrawResultReads: Reads[WithdrawResult] =
    Json.reads[WithdrawResult]

  implicit val cLightningFundChannelStartResultReads
      : Reads[FundChannelStartResult] =
    Json.reads[FundChannelStartResult]

  implicit val cLightningFundChannelCompleteResultReads
      : Reads[FundChannelCompleteResult] =
    Json.reads[FundChannelCompleteResult]

  implicit val cLightningFundChannelCancelResultReads
      : Reads[FundChannelCancelResult] =
    Json.reads[FundChannelCancelResult]

  implicit val CLightningTransactionReads: Reads[CLightningTransaction] =
    Json.reads[CLightningTransaction]

  implicit val CLightningListTransactionsResultsReads
      : Reads[ListTransactionsResults] =
    Json.reads[ListTransactionsResults]

  implicit val SendCustomMessageResultReads: Reads[SendCustomMessageResult] =
    Json.reads[SendCustomMessageResult]

  implicit val byteVectorWrites: Writes[ByteVector] =
    Writes[ByteVector](bytes => JsString(bytes.toHex))

  implicit val ecDigitalSignatureWrites: Writes[ECDigitalSignature] =
    Writes[ECDigitalSignature](sig => JsString(sig.hex))

  implicit val ecPublicKeyWrites: Writes[ECPublicKey] =
    Writes[ECPublicKey](pubKey => JsString(pubKey.hex))

  implicit val ecPublicKeyBytesWrites: Writes[ECPublicKeyBytes] =
    Writes[ECPublicKeyBytes](pubKey => JsString(pubKey.hex))

  implicit val scriptTokenWrites: Writes[ScriptToken] =
    Writes[ScriptToken](token => JsString(tokenToString(token)))

  implicit val doubleSha256DigestBEWrites: Writes[DoubleSha256DigestBE] =
    Writes[DoubleSha256DigestBE](hash => JsString(hash.hex))

  implicit val int32Writes: Writes[Int32] =
    Writes[Int32](num => JsNumber(num.toLong))

  implicit val serializedTransactionWitnessWrites
      : Writes[SerializedTransactionWitness] =
    Json.writes[SerializedTransactionWitness]

  implicit val serializedTransactionInputWrites
      : Writes[SerializedTransactionInput] =
    Json.writes[SerializedTransactionInput]

  implicit val serializedTransactionOutputWrites
      : Writes[SerializedTransactionOutput] =
    Json.writes[SerializedTransactionOutput]

  implicit val serializedTransactionWrites: Writes[SerializedTransaction] =
    Json.writes[SerializedTransaction]

  implicit val unknownPSBTGlobalWrites: Writes[GlobalPSBTRecord.Unknown] =
    GlobalPSBTRecordUnknownWrites

  implicit val unknownPSBTInputWrites: Writes[InputPSBTRecord.Unknown] =
    InputPSBTRecordUnknownWrites

  implicit val unknownPSBTOutputWrites: Writes[OutputPSBTRecord.Unknown] =
    OutputPSBTRecordUnknownWrites

  implicit val serializedPSBTGlobalWrites: Writes[SerializedPSBTGlobalMap] =
    Json.writes[SerializedPSBTGlobalMap]

  implicit val serializedPSBTInputWrites: Writes[SerializedPSBTInputMap] = {
    import JsonWriters.PartialSignatureWrites
    Json.writes[SerializedPSBTInputMap]
  }

  implicit val serializedPSBTOutputWrites: Writes[SerializedPSBTOutputMap] =
    Json.writes[SerializedPSBTOutputMap]

  implicit val serializedPSBTWrites: Writes[SerializedPSBT] =
    Json.writes[SerializedPSBT]

  // Map stuff
  implicit def mapDoubleSha256DigestReadsPreV19
      : Reads[Map[DoubleSha256Digest, GetMemPoolResultPreV19]] =
    Reads.mapReads[DoubleSha256Digest, GetMemPoolResultPreV19](s =>
      JsSuccess(DoubleSha256Digest.fromHex(s)))

  implicit def mapDoubleSha256DigestReadsPostV23
      : Reads[Map[DoubleSha256Digest, GetMemPoolResultPostV23]] =
    Reads.mapReads[DoubleSha256Digest, GetMemPoolResultPostV23](s =>
      JsSuccess(DoubleSha256Digest.fromHex(s)))

  implicit def mapDoubleSha256DigestBEReadsPreV19
      : Reads[Map[DoubleSha256DigestBE, GetMemPoolResultPreV19]] =
    Reads.mapReads[DoubleSha256DigestBE, GetMemPoolResultPreV19](s =>
      JsSuccess(DoubleSha256DigestBE.fromHex(s)))

  implicit def mapDoubleSha256DigestBEReadsPostV23
      : Reads[Map[DoubleSha256DigestBE, GetMemPoolResultPostV23]] =
    Reads.mapReads[DoubleSha256DigestBE, GetMemPoolResultPostV23](s =>
      JsSuccess(DoubleSha256DigestBE.fromHex(s)))

  implicit def mapAddressesByLabelReads
      : Reads[Map[BitcoinAddress, LabelResult]] =
    Reads.mapReads[BitcoinAddress, LabelResult](s =>
      JsSuccess(BitcoinAddress.fromString(s)))

  implicit def mapSatsPerKByteByIntReads: Reads[Map[Int, SatoshisPerKiloByte]] =
    Reads.mapReads[Int, SatoshisPerKiloByte](s => JsSuccess(s.toInt))

  implicit def mapBitcoinerLiveEstimateReads
      : Reads[Map[Int, BitcoinerLiveEstimate]] =
    Reads.mapReads[Int, BitcoinerLiveEstimate](s => JsSuccess(s.toInt))

  implicit val outputMapWrites: Writes[Map[BitcoinAddress, Bitcoins]] =
    mapWrites[BitcoinAddress, Bitcoins](_.value)

  implicit object DeriveAddressResults extends Reads[DeriveAddressesResult] {

    override def reads(json: JsValue): JsResult[DeriveAddressesResult] = {
      json match {
        case str: JsString =>
          bitcoinAddressReads
            .reads(str)
            .map(addr => DeriveAddressesResult(Vector(addr)))
        case arr: JsArray =>
          val addresses: Vector[BitcoinAddress] =
            arr.as[Vector[BitcoinAddress]]
          JsSuccess(DeriveAddressesResult(addresses))
        case x =>
          JsError(s"Invalid response for deriveaddresses rpc, got=$x")
      }
    }
  }

  implicit val importDescriptorResultReads: Reads[ImportDescriptorResult] = {
    Json.reads[ImportDescriptorResult]
  }

  implicit val scanBlocksStartResultReads: Reads[ScanBlocksStartResult] =
    Json.reads[ScanBlocksStartResult]
  implicit val scanInProgressReads: Reads[ScanInProgress] =
    Json.reads[ScanInProgress]
  implicit val noScanInProgress: Reads[NoScanInProgress.type] =
    Json.reads[NoScanInProgress.type]

  implicit object ScanBlocksResultReads extends Reads[ScanBlocksResult] {
    override def reads(json: JsValue): JsResult[ScanBlocksResult] = {
      json match {
        case JsNull             => JsSuccess(NoScanInProgress)
        case JsBoolean(aborted) => JsSuccess(ScanBlocksAbortResult(aborted))
        case x =>
          scanInProgressReads.reads(x) match {
            case s: JsSuccess[_] => s
            case _: JsError      => scanBlocksStartResultReads.reads(x)
          }
      }
    }
  }

  implicit val scanTxoutSetUTXO: Reads[ScanTxoutSetUTXO] =
    Json.reads[ScanTxoutSetUTXO]

  implicit val scanTxoutSetResultReads: Reads[ScanTxoutSetResult] =
    Json.reads[ScanTxoutSetResult]

  implicit val chainStateReads: Reads[ChainState] = Json.reads[ChainState]

  implicit val chainStateResultReads: Reads[ChainStateResult] =
    Json.reads[ChainStateResult]

  implicit val prioritisedTransactionReads: Reads[PrioritisedTransaction] = {
    Json.reads[PrioritisedTransaction]
  }

  implicit val addrManInfoReads: Reads[AddrManInfo] = Json.reads[AddrManInfo]
  implicit val getAddrmanInfoResponseReads: Reads[GetAddrmanInfoResponse] = {
    Json.reads[GetAddrmanInfoResponse]
  }
}
