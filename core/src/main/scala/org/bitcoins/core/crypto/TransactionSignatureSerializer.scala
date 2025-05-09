package org.bitcoins.core.crypto

import org.bitcoins.core.number.{Int32, UInt32}
import org.bitcoins.core.policy.Policy
import org.bitcoins.core.protocol.CompactSizeUInt
import org.bitcoins.core.protocol.script.*
import org.bitcoins.core.protocol.transaction.*
import org.bitcoins.core.script.constant.ScriptToken
import org.bitcoins.core.script.crypto.*
import org.bitcoins.core.serializers.transaction.RawTransactionOutputParser
import org.bitcoins.core.util.{BitcoinScriptUtil, BytesUtil}
import org.bitcoins.core.wallet.utxo.{InputInfo, InputSigningInfo}
import org.bitcoins.crypto.*
import scodec.bits.ByteVector

/** Created by chris on 2/16/16. Wrapper that serializes like Transaction, but
  * with the modifications required for the signature hash done
  * [[https://github.com/bitcoin/bitcoin/blob/93c85d458ac3e2c496c1a053e1f5925f55e29100/src/script/interpreter.cpp#L1016-L1105]]
  * bitcoinj version of this
  * [[https://github.com/bitcoinj/bitcoinj/blob/master/core/src/main/java/org/bitcoinj/core/Transaction.java#L924-L1008]]
  */
sealed abstract class TransactionSignatureSerializer {

  /** Bitcoin Core's bug is that SignatureHash was supposed to return a hash and
    * on this codepath it actually returns the constant "1" to indicate an error
    */
  private lazy val errorHash: DoubleSha256Digest = DoubleSha256Digest(
    BytesUtil.decodeHex(
      "0100000000000000000000000000000000000000000000000000000000000000"))

  /** Implements the signature serialization algorithim that Satoshi Nakamoto
    * originally created and the new signature serialization algorithm as
    * specified by
    * [[https://github.com/bitcoin/bips/blob/master/bip-0143.mediawiki BIP143]].
    * [[https://github.com/bitcoin/bitcoin/blob/f8528134fc188abc5c7175a19680206964a8fade/src/script/interpreter.cpp#L1113]]
    */
  def serializeForSignature(
      txSigComponent: TxSigComponent,
      hashType: HashType,
      taprootOptions: TaprootSerializationOptions): ByteVector = {
    val spendingTransaction = txSigComponent.transaction
    val inputIndex = txSigComponent.inputIndex
    val output = txSigComponent.fundingOutput
    val script = BitcoinScriptUtil.calculateScriptForSigning(
      txSigComponent,
      output.scriptPubKey.asm)

    txSigComponent match {
      case _: BaseTxSigComponent | _: WitnessTxSigComponentRaw |
          _: WitnessTxSigComponentRebuilt =>
        serializeForSignature(spendingTransaction,
                              inputIndex,
                              hashType,
                              Vector(output),
                              script,
                              txSigComponent.sigVersion,
                              taprootOptions)
      case t: TaprootTxSigComponent =>
        serializeForSignature(spendingTransaction,
                              inputIndex,
                              hashType,
                              t.fundingOutputs,
                              script,
                              txSigComponent.sigVersion,
                              taprootOptions)
    }

  }

  private def serializeForSignature(
      spendingTransaction: Transaction,
      inputIndex: UInt32,
      hashType: HashType,
      outputs: Vector[TransactionOutput],
      script: Seq[ScriptToken],
      sigVersion: SignatureVersion,
      taprootOptions: TaprootSerializationOptions): ByteVector = {
    val keyVersion: Byte = 0.toByte

    sigVersion match {
      case SigVersionBase =>
        require(
          outputs.length == 1,
          s"Only one output needed for the original satoshis signature algorithm, got=$outputs")
        // Clear input scripts in preparation for signing. If we're signing a fresh
        // CScript's inside the Bitcoin Core codebase retain their compactSizeUInt
        // while clearing out all of the actual asm operations in the CScript
        val inputSigsRemoved = for {
          input <- spendingTransaction.inputs
          s = input.scriptSignature
        } yield TransactionInput(
          input.previousOutput,
          NonStandardScriptSignature(s.compactSizeUInt.hex),
          input.sequence)

        // make sure all scriptSigs have empty asm
        inputSigsRemoved.foreach(input =>
          require(input.scriptSignature.asm.isEmpty,
                  "Input asm was not empty " + input.scriptSignature.asm))

        // This step has no purpose beyond being synchronized with Bitcoin Core's bugs. OP_CODESEPARATOR
        // is a legacy holdover from a previous, broken design of executing scripts that shipped in Bitcoin 0.1.
        // It was seriously flawed and would have let anyone take anyone elses money. Later versions switched to
        // the design we use today where scripts are executed independently but share a stack. This left the
        // OP_CODESEPARATOR instruction having no purpose as it was only meant to be used internally, not actually
        // ever put into scripts. Deleting OP_CODESEPARATOR is a step that should never be required but if we don't
        // do it, we could split off the main chain.
        val scriptWithOpCodeSeparatorsRemoved: Seq[ScriptToken] =
          removeOpCodeSeparators(script)

        val inputToSign = inputSigsRemoved(inputIndex.toInt)

        // Set the input to the script of its output. Bitcoin Core does this but the step has no obvious purpose as
        // the signature covers the hash of the prevout transaction which obviously includes the output script
        // already. Perhaps it felt safer to him in some way, or is another leftover from how the code was written.
        val scriptSig =
          ScriptSignature.fromAsm(scriptWithOpCodeSeparatorsRemoved)
        val inputWithConnectedScript = TransactionInput(
          inputToSign.previousOutput,
          scriptSig,
          inputToSign.sequence)

        // update the input at index i with inputWithConnectScript
        val updatedInputs = for {
          (input, index) <- inputSigsRemoved.zipWithIndex
        } yield {
          if (UInt32(index) == inputIndex) {
            inputWithConnectedScript
          } else input
        }

        val txWithInputSigsRemoved = BaseTransaction(
          spendingTransaction.version,
          updatedInputs,
          spendingTransaction.outputs,
          spendingTransaction.lockTime)
        val sigHashBytes = Int32(hashType.num).bytes.reverse

        hashType match {
          case _: SIGHASH_NONE =>
            val sigHashNoneTx: Transaction =
              sigHashNone(txWithInputSigsRemoved, inputIndex)
            sigHashNoneTx.bytes ++ sigHashBytes

          case _: SIGHASH_SINGLE =>
            if (inputIndex >= UInt32(spendingTransaction.outputs.size)) {
              // comment copied from bitcoinj
              // The input index is beyond the number of outputs, it's a buggy signature made by a broken
              // Bitcoin implementation. Bitcoin Core also contains a bug in handling this case:
              // any transaction output that is signed in this case will result in both the signed output
              // and any future outputs to this public key being steal-able by anyone who has
              // the resulting signature and the public key (both of which are part of the signed tx input).

              // Bitcoin Core's bug is that SignatureHash was supposed to return a hash and on this codepath it
              // actually returns the constant "1" to indicate an error, which is never checked for. Oops.
              errorHash.bytes
            } else {
              val sigHashSingleTx =
                sigHashSingle(txWithInputSigsRemoved, inputIndex)
              sigHashSingleTx.bytes ++ sigHashBytes
            }

          case _: SIGHASH_ALL | SIGHASH_DEFAULT =>
            val sigHashAllTx: Transaction = sigHashAll(txWithInputSigsRemoved)
            sigHashAllTx.bytes ++ sigHashBytes

          case _: SIGHASH_ANYONECANPAY =>
            val txWithInputsRemoved = sigHashAnyoneCanPay(
              txWithInputSigsRemoved,
              inputWithConnectedScript)
            txWithInputsRemoved.bytes ++ sigHashBytes

          case _: SIGHASH_ALL_ANYONECANPAY =>
            val sigHashAllTx = sigHashAll(txWithInputSigsRemoved)
            val sigHashAllAnyoneCanPayTx =
              sigHashAnyoneCanPay(sigHashAllTx, inputWithConnectedScript)
            sigHashAllAnyoneCanPayTx.bytes ++ sigHashBytes

          case _: SIGHASH_NONE_ANYONECANPAY =>
            val sigHashNoneTx = sigHashNone(txWithInputSigsRemoved, inputIndex)
            val sigHashNoneAnyoneCanPay =
              sigHashAnyoneCanPay(sigHashNoneTx, inputWithConnectedScript)
            sigHashNoneAnyoneCanPay.bytes ++ sigHashBytes

          case _: SIGHASH_SINGLE_ANYONECANPAY =>
            val sigHashSingleTx =
              sigHashSingle(txWithInputSigsRemoved, inputIndex)
            val sigHashSingleAnyoneCanPay =
              sigHashAnyoneCanPay(sigHashSingleTx, inputWithConnectedScript)
            sigHashSingleAnyoneCanPay.bytes ++ sigHashBytes
        }
      case SigVersionWitnessV0 =>
        require(
          outputs.length == 1,
          s"Only one output needed for the original satoshis signature algorithm, got=$outputs")
        val output = outputs.head
        val amount = output.value
        val isNotAnyoneCanPay = !HashType.isAnyoneCanPay(hashType)
        val isNotSigHashSingle = !HashType.isSigHashSingle(hashType.num)
        val isNotSigHashNone = !HashType.isSigHashNone(hashType.num)
        val inputIndexInt = inputIndex.toInt
        val emptyHash = DoubleSha256Digest.empty

        val outPointHash: ByteVector = if (isNotAnyoneCanPay) {
          val prevOuts = spendingTransaction.inputs.map(_.previousOutput)
          val bytes: ByteVector = BytesUtil.toByteVector(prevOuts)
          CryptoUtil.doubleSHA256(bytes).bytes
        } else emptyHash.bytes

        val sequenceHash: ByteVector =
          if (isNotAnyoneCanPay && isNotSigHashNone && isNotSigHashSingle) {
            val sequences = spendingTransaction.inputs.map(_.sequence)
            val littleEndianSeq =
              sequences.foldLeft(ByteVector.empty)(_ ++ _.bytes.reverse)
            CryptoUtil.doubleSHA256(littleEndianSeq).bytes
          } else emptyHash.bytes

        val outputHash: ByteVector =
          if (isNotSigHashSingle && isNotSigHashNone) {
            val outputs = spendingTransaction.outputs
            val bytes = BytesUtil.toByteVector(outputs)
            CryptoUtil.doubleSHA256(bytes).bytes
          } else if (
            HashType.isSigHashSingle(hashType.num) &&
            inputIndex < UInt32(spendingTransaction.outputs.size)
          ) {
            val output = spendingTransaction.outputs(inputIndexInt)
            val bytes = CryptoUtil
              .doubleSHA256(RawTransactionOutputParser.write(output))
              .bytes
            bytes
          } else emptyHash.bytes

        val scriptBytes = BytesUtil.toByteVector(script)

        val i = spendingTransaction.inputs(inputIndexInt)
        val serializationForSig: ByteVector =
          spendingTransaction.version.bytes.reverse ++ outPointHash ++ sequenceHash ++
            i.previousOutput.bytes ++ CompactSizeUInt.calc(scriptBytes).bytes ++
            scriptBytes ++ amount.bytes ++ i.sequence.bytes.reverse ++
            outputHash ++ spendingTransaction.lockTime.bytes.reverse ++ Int32(
              hashType.num).bytes.reverse
        serializationForSig

      case taprootSigVersion: SigVersionTaproot =>
        val isNotAnyoneCanPay = !HashType.isAnyoneCanPay(hashType)
        val isNotSigHashSingle = !HashType.isSigHashSingle(hashType.num)
        val isNotSigHashNone = !HashType.isSigHashNone(hashType.num)
        val isSigHashAllAnyoneCanPay =
          HashType.isSigHashAllAnyoneCanPay(hashType.num)

        val extFlag = taprootSigVersion match {
          case SigVersionTaprootKeySpend => 0.toByte
          case SigVersionTapscript       => 1.toByte
        }
        val epoch = ByteVector.fromByte(0)

        val version = spendingTransaction.version.bytes.reverse
        val locktimeBytes = spendingTransaction.lockTime.bytes.reverse
        val outPointHash: ByteVector = if (isNotAnyoneCanPay) {
          val prevOuts = spendingTransaction.inputs.map(_.previousOutput)
          val bytes: ByteVector = BytesUtil.toByteVector(prevOuts)
          CryptoUtil.sha256(bytes).bytes
        } else {
          val b = spendingTransaction.inputs(inputIndex.toInt).previousOutput
          b.bytes
        }

        val amounts = {
          if (isNotAnyoneCanPay) {
            val b = BytesUtil.toByteVector(outputs.map(_.value))
            CryptoUtil.sha256(b).bytes
          } else {
            ByteVector.empty
          }
        }
        val spentSPKs = {
          if (isNotAnyoneCanPay) {
            val b = BytesUtil.toByteVector(outputs.map(_.scriptPubKey))
            CryptoUtil.sha256(b).bytes
          } else {
            val b = outputs(inputIndex.toInt)
            b.bytes
          }
        }

        val sequenceHash: ByteVector =
          if (isNotAnyoneCanPay) {
            val sequences = spendingTransaction.inputs.map(_.sequence)
            val littleEndianSeq =
              sequences.foldLeft(ByteVector.empty)(_ ++ _.bytes.reverse)
            CryptoUtil.sha256(littleEndianSeq).bytes
          } else if (!isNotAnyoneCanPay) {
            val b = spendingTransaction
              .inputs(inputIndex.toInt)
              .sequence
              .bytes
              .reverse
            b
          } else {
            ByteVector.empty
          }
        val outputHash: ByteVector =
          if (isNotSigHashSingle && isNotSigHashNone) {
            val outputs = spendingTransaction.outputs
            val bytes = BytesUtil.toByteVector(outputs)
            CryptoUtil.sha256(bytes).bytes
          } else if (
            HashType.isSigHashSingle(hashType.num) &&
            inputIndex < UInt32(spendingTransaction.outputs.size)
          ) {
            val output = spendingTransaction.outputs(inputIndex.toInt)
            val hash = CryptoUtil
              .sha256(output.bytes)
              .bytes
            hash
          } else ByteVector.empty

        val haveAnnex: Boolean = taprootOptions.haveAnnex

        val annexByte = if (haveAnnex) 1.toByte else 0.toByte

        val spendType: Byte = ((extFlag << 1) + annexByte).toByte

        val inputIndexBytes = inputIndex.bytes.reverse

        val codeSeparatorPos: UInt32 = taprootOptions.codeSeparatorPos

        val tapScriptBytes = {
          if (sigVersion == SigVersionTapscript) {
            require(taprootOptions.tapLeafHashOpt.isDefined,
                    "Must have a tapleaf hash to verify a tapscript")
            taprootOptions.tapLeafHashOpt.get.bytes ++
              ByteVector.fromByte(keyVersion) ++
              codeSeparatorPos.bytes.reverse
          } else {
            ByteVector.empty
          }
        }

        val annexBytes = if (haveAnnex) {
          taprootOptions.annexHashOpt.get.bytes
        } else {
          ByteVector.empty
        }

        val result = {
          if (isNotAnyoneCanPay) {
            if (!isNotSigHashSingle) {
              epoch ++ ByteVector.fromByte(
                hashType.byte) ++ version ++ locktimeBytes ++
                outPointHash ++ amounts ++ spentSPKs ++
                sequenceHash ++ ByteVector.fromByte(
                  spendType) ++ inputIndexBytes ++
                annexBytes ++ outputHash ++ tapScriptBytes
            } else {
              epoch ++ ByteVector.fromByte(
                hashType.byte) ++ version ++ locktimeBytes ++
                outPointHash ++ amounts ++ spentSPKs ++
                sequenceHash ++ outputHash ++
                ByteVector.fromByte(spendType) ++
                inputIndexBytes ++ annexBytes ++ tapScriptBytes
            }

          } else {
            if (isSigHashAllAnyoneCanPay) {
              // different ordering if we use SIGHASH_ANYONECANPAY
              epoch ++ ByteVector
                .fromByte(
                  hashType.byte) ++ version ++ locktimeBytes ++ outputHash ++
                ByteVector.fromByte(
                  spendType) ++ outPointHash ++ amounts ++ spentSPKs ++
                sequenceHash ++ annexBytes ++ tapScriptBytes
            } else {
              // different ordering if we use SIGHASH_ANYONECANPAY
              epoch ++ ByteVector.fromByte(
                hashType.byte) ++ version ++ locktimeBytes ++ ByteVector
                .fromByte(spendType) ++
                outPointHash ++ amounts ++ spentSPKs ++
                sequenceHash ++ annexBytes ++ outputHash ++ tapScriptBytes
            }
          }
        }
        result
    }
  }

  /** Hashes a [[org.bitcoins.core.crypto.TxSigComponent TxSigComponent]] to
    * give the value that needs to be signed by a [[Sign Sign]] to produce a
    * valid [[ECDigitalSignature ECDigitalSignature]] for a transaction
    */
  def hashForSignature(
      txSigComponent: TxSigComponent,
      hashType: HashType,
      taprootOptions: TaprootSerializationOptions): HashDigest = {
    val spendingTransaction = txSigComponent.transaction
    val inputIndex = txSigComponent.inputIndex
    if (
      inputIndex >= UInt32(spendingTransaction.inputs.size) &&
      txSigComponent.sigVersion != SigVersionWitnessV0
    ) {
      errorHash
    } else if (
      (hashType.isInstanceOf[SIGHASH_SINGLE] || hashType
        .isInstanceOf[SIGHASH_SINGLE_ANYONECANPAY]) &&
      inputIndex >= UInt32(spendingTransaction.outputs.size) &&
      txSigComponent.sigVersion != SigVersionWitnessV0
    ) {
      errorHash
    } else {
      val serializedTxForSignature =
        serializeForSignature(txSigComponent = txSigComponent,
                              hashType = hashType,
                              taprootOptions = taprootOptions)

      val hash = txSigComponent.sigVersion match {
        case _: SigVersionTaproot =>
          CryptoUtil.taggedSha256(serializedTxForSignature, "TapSighash")
        case SigVersionBase | SigVersionWitnessV0 =>
          CryptoUtil.doubleSHA256(serializedTxForSignature)
      }
      hash
    }
  }

  /** Implements the signature serialization algorithm that Satoshi Nakamoto
    * originally created and the new signature serialization algorithm as
    * specified by
    * [[https://github.com/bitcoin/bips/blob/master/bip-0143.mediawiki BIP143]].
    * [[https://github.com/bitcoin/bitcoin/blob/f8528134fc188abc5c7175a19680206964a8fade/src/script/interpreter.cpp#L1113]]
    */
  def serializeForSignature(
      spendingTransaction: Transaction,
      signingInfo: InputSigningInfo[InputInfo],
      hashType: HashType,
      taprootOptions: TaprootSerializationOptions): ByteVector = {
    val idx = TxUtil.inputIndex(signingInfo.inputInfo, spendingTransaction)

    require(
      signingInfo.prevTransaction != EmptyTransaction,
      "prevTransaction can only be an EmptyTransaction when dummy signing")

    val inputIndex = UInt32(idx)
    val output = signingInfo.output
    val script = BitcoinScriptUtil.calculateScriptForSigning(
      spendingTransaction,
      signingInfo,
      output.scriptPubKey.asm)

    serializeForSignature(spendingTransaction,
                          inputIndex,
                          hashType,
                          Vector(output),
                          script,
                          signingInfo.sigVersion,
                          taprootOptions = taprootOptions)
  }

  /** Hashes a
    * [[org.bitcoins.core.wallet.utxo.InputSigningInfo InputSigningInfo]] to
    * give the value that needs to be signed by a
    * [[org.bitcoins.crypto.Sign Sign]] to produce a valid
    * [[org.bitcoins.crypto.ECDigitalSignature ECDigitalSignature]] for a
    * transaction
    */
  def hashForSignature(
      spendingTransaction: Transaction,
      signingInfo: InputSigningInfo[InputInfo],
      hashType: HashType,
      taprootOptions: TaprootSerializationOptions): HashDigest = {
    val txSigComponent = TxSigComponent(
      inputInfo = signingInfo.inputInfo,
      unsignedTx = spendingTransaction,
      outputMap = signingInfo.inputInfo.previousOutputMap,
      flags = Policy.standardFlags)
    hashForSignature(txSigComponent, hashType, taprootOptions)
  }

  /** Sets the input's sequence number to zero EXCEPT for the input at
    * inputIndex.
    */
  private def setSequenceNumbersZero(
      inputs: Vector[TransactionInput],
      inputIndex: UInt32): Vector[TransactionInput] =
    for {
      (input, index) <- inputs.zipWithIndex
    } yield {
      if (UInt32(index) == inputIndex) input
      else
        TransactionInput(input.previousOutput,
                         input.scriptSignature,
                         UInt32.zero)
    }

  /** Executes the [[SIGHASH_NONE SIGHASH_NONE]] procedure on a spending
    * transaction for the input specified by inputIndex.
    */
  private def sigHashNone(
      spendingTransaction: Transaction,
      inputIndex: UInt32): Transaction = {
    // following this implementation from bitcoinj
    // [[https://github.com/bitcoinj/bitcoinj/blob/09a2ca64d2134b0dcbb27b1a6eb17dda6087f448/core/src/main/java/org/bitcoinj/core/Transaction.java#L957]]
    // means that no outputs are signed at all
    // set the sequence number of all inputs to 0 EXCEPT the input at inputIndex
    val updatedInputs: Vector[TransactionInput] =
      setSequenceNumbersZero(spendingTransaction.inputs, inputIndex)
    val sigHashNoneTx = BaseTransaction(spendingTransaction.version,
                                        updatedInputs,
                                        Vector.empty,
                                        spendingTransaction.lockTime)
    // append hash type byte onto the end of the tx bytes
    sigHashNoneTx
  }

  /** Executes the [[SIGHASH_SINGLE SIGHASH_SINGLE]] procedure on a spending
    * transaction for the input specified by inputIndex
    */
  private def sigHashSingle(
      spendingTransaction: Transaction,
      inputIndex: UInt32): Transaction = {
    // following this implementation from bitcoinj
    // [[https://github.com/bitcoinj/bitcoinj/blob/09a2ca64d2134b0dcbb27b1a6eb17dda6087f448/core/src/main/java/org/bitcoinj/core/Transaction.java#L964]]
    // In SIGHASH_SINGLE the outputs after the matching input index are deleted, and the outputs before
    // that position are "nulled out". Unintuitively, the value in a "null" transaction is set to -1.
    val updatedOutputsOpt: Vector[Option[TransactionOutput]] = for {
      (output, index) <- spendingTransaction.outputs.zipWithIndex
    } yield {
      if (UInt32(index) < inputIndex) {
        Some(EmptyTransactionOutput)
      } else if (UInt32(index) == inputIndex) Some(output)
      else None
    }
    val updatedOutputs: Vector[TransactionOutput] = updatedOutputsOpt.flatten

    // create blank inputs with sequence numbers set to zero EXCEPT
    // the input at the inputIndex
    val updatedInputs: Vector[TransactionInput] =
      setSequenceNumbersZero(spendingTransaction.inputs, inputIndex)
    val sigHashSingleTx = BaseTransaction(spendingTransaction.version,
                                          updatedInputs,
                                          updatedOutputs,
                                          spendingTransaction.lockTime)
    sigHashSingleTx
  }

  /** Executes the [[SIGHASH_ALL SIGHASH_ALL]] procedure on a spending
    * transaction at inputIndex.
    */
  private def sigHashAll(spendingTransaction: Transaction): Transaction = {
    spendingTransaction
  }

  /** Executes the [[SIGHASH_ANYONECANPAY SIGHASH_ANYONECANPAY]] procedure on a
    * spending transaction at inputIndex.
    */
  private def sigHashAnyoneCanPay(
      spendingTransaction: Transaction,
      input: TransactionInput): Transaction = {
    BaseTransaction(spendingTransaction.version,
                    Vector(input),
                    spendingTransaction.outputs,
                    spendingTransaction.lockTime)
  }

  /** Removes
    * [[org.bitcoins.core.script.crypto.OP_CODESEPARATOR OP_CODESEPARATOR]]
    * operations then returns the script.
    */
  def removeOpCodeSeparators(script: Seq[ScriptToken]): Seq[ScriptToken] = {
    if (script.contains(OP_CODESEPARATOR)) {
      val scriptWithoutOpCodeSeparators: Seq[ScriptToken] =
        script.filterNot(_ == OP_CODESEPARATOR)
      scriptWithoutOpCodeSeparators
    } else script
  }
}

object TransactionSignatureSerializer extends TransactionSignatureSerializer
