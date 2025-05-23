package org.bitcoins.core.protocol.script

import org.bitcoins.core.protocol.CompactSizeUInt
import org.bitcoins.core.protocol.script.LeafVersion.UnknownLeafVersion
import org.bitcoins.core.script.constant.{OP_0, ScriptNumberOperation}
import org.bitcoins.core.serializers.script.{
  RawScriptWitnessParser,
  ScriptParser
}
import org.bitcoins.core.util.{BitcoinScriptUtil, BytesUtil}
import org.bitcoins.crypto.*
import scodec.bits.ByteVector

/** Created by chris on 11/10/16. The witness used to evaluate a
  * [[RawScriptPubKey]] inside of Bitcoin
  * [[https://github.com/bitcoin/bitcoin/blob/57b34599b2deb179ff1bd97ffeab91ec9f904d85/src/script/script.h#L648-L660]]
  */
sealed abstract class ScriptWitness extends NetworkElement {

  /** The byte vectors that are placed on to the stack when evaluating a witness
    * program
    */
  val stack: Seq[ByteVector]

  // When comparing two witnesses we should compare the
  // serialization as our types can have overlap
  override def equals(other: Any): Boolean =
    other match {
      case p: ScriptWitness => this.bytes == p.bytes
      case _                => other.equals(this)
    }

}

case object EmptyScriptWitness extends ScriptWitness {
  override val stack: Seq[ByteVector] = Vector.empty

  override val bytes: ByteVector = ByteVector.low(1)
}

sealed abstract class ScriptWitnessV0 extends ScriptWitness {
  override val bytes: ByteVector = RawScriptWitnessParser.write(this)
}

/** Represents a [[org.bitcoins.core.protocol.script.ScriptWitness]] that is
  * needed to spend a [[P2WPKHWitnessV0]] scriptPubKey
  * [[https://github.com/bitcoin/bips/blob/master/bip-0141.mediawiki#p2wpkh-nested-in-bip16-p2sh]]
  * Format: <pubKey> <signature>
  */
sealed abstract class P2WPKHWitnessV0 extends ScriptWitnessV0 {
  def pubKey: ECPublicKeyBytes = ECPublicKeyBytes(stack.head)

  def signature: ECDigitalSignature =
    stack(1) match {
      case ByteVector.empty     => ECDigitalSignature.empty
      case nonEmpty: ByteVector => ECDigitalSignature(nonEmpty)
    }

  override def toString =
    s"P2WPKHWitnessV0(${stack.map(BytesUtil.encodeHex(_)).toString})"
}

object P2WPKHWitnessV0 {

  private case class P2WPKHWitnessV0Impl(stack: Seq[ByteVector])
      extends P2WPKHWitnessV0

  private def apply(stack: Seq[ByteVector]): P2WPKHWitnessV0 =
    P2WPKHWitnessV0Impl(stack)

  private[bitcoins] def apply(
      pubKeyBytes: ECPublicKeyBytes): P2WPKHWitnessV0 = {
    P2WPKHWitnessV0(pubKeyBytes, ECDigitalSignature.empty)
  }

  def apply(pubKey: ECPublicKey): P2WPKHWitnessV0 = {
    P2WPKHWitnessV0(pubKey, ECDigitalSignature.empty)
  }

  private[bitcoins] def apply(
      publicKeyBytes: ECPublicKeyBytes,
      signature: ECDigitalSignature): P2WPKHWitnessV0 = {
    P2WPKHWitnessV0(Seq(publicKeyBytes.bytes, signature.bytes))
  }

  def apply(
      publicKey: ECPublicKey,
      signature: ECDigitalSignature): P2WPKHWitnessV0 = {
    P2WPKHWitnessV0(publicKey.toPublicKeyBytes(), signature)
  }

  def fromP2PKHScriptSig(scriptSig: ScriptSignature): P2WPKHWitnessV0 =
    scriptSig match {
      case p2pkh: P2PKHScriptSignature =>
        P2WPKHWitnessV0(p2pkh.publicKey, p2pkh.signature)
      case x @ (_: LockTimeScriptSignature | _: MultiSignatureScriptSignature |
          _: ConditionalScriptSignature | _: NonStandardScriptSignature |
          _: P2PKScriptSignature | _: P2SHScriptSignature |
          TrivialTrueScriptSignature | EmptyScriptSignature) =>
        throw new IllegalArgumentException(
          s"Expected P2PKHScriptSignature, got $x")
    }
}

/** Represents a [[ScriptWitness]] that is needed to spend a [[P2WSHWitnessV0]]
  * scriptPubKey
  * [[https://github.com/bitcoin/bips/blob/master/bip-0141.mediawiki#p2wsh]]
  * Format: <redeem script> <scriptSig data1> <scriptSig data2> ... <scriptSig
  * dataN>
  */
sealed abstract class P2WSHWitnessV0 extends ScriptWitnessV0 {

  lazy val redeemScript: RawScriptPubKey = {
    val cmpct = CompactSizeUInt.calc(stack.head)
    RawScriptPubKey.fromBytes(cmpct.bytes ++ stack.head)
  }

  lazy val signatures: Vector[ECDigitalSignature] = {
    // ECDigital signatures are between 71 and 73 bytes long
    // with a exponential decay on the probability of smaller sigs
    // [[https://en.bitcoin.it/wiki/Elliptic_Curve_Digital_Signature_Algorithm]]
    val relevantStack = stack.toVector.tail.filter(bytes =>
      bytes.length >= 67 && bytes.length <= 73)

    relevantStack.map(ECDigitalSignature.fromBytes)
  }

  // Note that this is not guaranteed to work for non-standard script signatures
  lazy val scriptSignature: ScriptSignature = {
    val asm = stack.toVector.tail.reverse.flatMap { bytes =>
      if (bytes.isEmpty) {
        Vector(OP_0)
      } else if (bytes.length == 1 && bytes.head <= 16 && bytes.head >= -1) {
        ScriptNumberOperation.fromNumber(bytes.head.toLong).toVector
      } else {
        ScriptParser.fromBytes(CompactSizeUInt.calc(bytes).bytes ++ bytes)
      }
    }

    ScriptSignature.fromAsm(asm)
  }

  override def toString =
    s"P2WSHWitnessV0(${stack.map(BytesUtil.encodeHex(_)).toString})"
}

object P2WSHWitnessV0 {

  private case class P2WSHWitnessV0Impl(stack: Seq[ByteVector])
      extends P2WSHWitnessV0

  def apply(spk: RawScriptPubKey): P2WSHWitnessV0 = {
    P2WSHWitnessV0(spk, EmptyScriptSignature)
  }

  def apply(
      spk: RawScriptPubKey,
      scriptSig: ScriptSignature): P2WSHWitnessV0 = {
    // need to remove the OP_0 or OP_1 and replace it with ScriptNumber.zero / ScriptNumber.one since witnesses are *not* run through the interpreter
    // remove pushops from scriptSig
    val minimalIf = BitcoinScriptUtil.minimalIfOp(scriptSig.asm)
    val noPushOps = BitcoinScriptUtil.filterPushOps(minimalIf)
    val minimalDummy = BitcoinScriptUtil.minimalDummy(noPushOps).reverse
    val stack: Seq[ByteVector] = spk.asmBytes +: minimalDummy.map(_.bytes)
    P2WSHWitnessV0(stack)
  }

  private def apply(stack: Seq[ByteVector]): P2WSHWitnessV0 = {
    P2WSHWitnessV0Impl(stack)
  }

  def apply(spk: RawScriptPubKey, stack: Seq[ByteVector]): P2WSHWitnessV0 = {
    val fullStack: Seq[ByteVector] = spk.asmBytes +: stack
    P2WSHWitnessV0(fullStack)
  }
}

object ScriptWitness extends Factory[ScriptWitness] {

  val empty: EmptyScriptWitness.type = EmptyScriptWitness

  override def fromBytes(bytes: ByteVector): ScriptWitness = {
    RawScriptWitnessParser.read(bytes)
  }

  def apply(stack: Seq[ByteVector]): ScriptWitness = {
    // TODO: eventually only compressed public keys will be allowed in v0 scripts
    // https://github.com/bitcoin/bips/blob/master/bip-0143.mediawiki#restrictions-on-public-key-type
    val isPubKey = {
      stack.nonEmpty && (stack.head.size == 33 && (stack.head.head == 0x02 || stack.head.head == 0x03)
        || (stack.head.size == 65 && stack.head.head == 0x04 && CryptoUtil
          .isValidPubKey(ECPublicKeyBytes(stack.head))))
    }

    if (stack.isEmpty) {
      EmptyScriptWitness
    } else if (TaprootKeyPath.isValid(stack.toVector)) {
      // taproot key path spend
      TaprootKeyPath.fromStack(stack.toVector)
    } else if (isPubKey && stack.size == 1) {
      val pubKey = ECPublicKeyBytes(stack.head)
      P2WPKHWitnessV0(pubKey)
    } else if (TaprootScriptPath.isValid(stack.toVector)) {
      TaprootScriptPath.fromStack(stack.toVector)
    } else if (isPubKey && stack.size == 2) {
      val pubKey = ECPublicKeyBytes(stack.head)
      val sig = ECDigitalSignature(stack(1))
      P2WPKHWitnessV0(pubKey, sig)
    } else {
      // wont match a Vector if I don't convert to list
      val s = stack.toList
      s match {
        case Nil =>
          EmptyScriptWitness
        case h :: t =>
          val cmpct = CompactSizeUInt.calc(h)
          val spk = RawScriptPubKey(cmpct.bytes ++ h)
          P2WSHWitnessV0(spk, t)
      }
    }
  }
}

sealed trait TaprootWitness extends ScriptWitness {
  override def bytes: ByteVector = RawScriptWitnessParser.write(this)

  def annexOpt: Option[Annex]

  /** As per bip341 the SHA256 of (compact_size(size of annex) || annex), where
    * annex includes the mandatory 0x50 prefix.
    * @see
    *   https://github.com/bitcoin/bips/blob/master/bip-0341.mediawiki#signature-validation-rules
    */
  def annexHashOpt: Option[Sha256Digest] = {
    annexOpt.map { annex =>
      val cmpct = CompactSizeUInt.calc(annex.bytes)
      CryptoUtil.sha256(cmpct.bytes ++ annex.bytes)
    }
  }

  def sigVersion: SigVersionTaproot
}

object TaprootWitness extends Factory[TaprootWitness] {

  def fromStack(stack: Vector[ByteVector]): TaprootWitness = {
    val hasAnnex = TaprootScriptPath.hasAnnex(stack)

    if ((hasAnnex && stack.length == 2) || stack.length == 1) {
      TaprootKeyPath.fromStack(stack)
    } else {
      TaprootScriptPath(stack)
    }
  }

  override def fromBytes(bytes: ByteVector): TaprootWitness = {
    RawScriptWitnessParser.read(bytes) match {
      case w: TaprootWitness => w
      case x @ (_: ScriptWitnessV0 | EmptyScriptWitness) =>
        sys.error(s"Could not parse taproot witness, got=$x")
    }
  }

}

/** Spending a taproot output via the key path spend */
case class TaprootKeyPath(
    signature: SchnorrDigitalSignature,
    annexOpt: Option[Annex])
    extends TaprootWitness {

  val hashType: HashType =
    signature.hashTypeOpt.getOrElse(HashType.sigHashDefault)

  override val stack: Vector[ByteVector] = {
    annexOpt match {
      case Some(annex) =>
        annex.bytes +: Vector(signature.bytes)
      case None =>
        Vector(signature.bytes)
    }
  }

  override def sigVersion: SigVersionTaprootKeySpend.type =
    SigVersionTaprootKeySpend
}

object TaprootKeyPath extends Factory[TaprootKeyPath] {
  val dummy: TaprootKeyPath = TaprootKeyPath(SchnorrDigitalSignature.dummy)
  override def fromBytes(bytes: ByteVector): TaprootKeyPath = {
    RawScriptWitnessParser.read(bytes) match {
      case keypath: TaprootKeyPath => keypath
      case x @ (_: TaprootScriptPath | _: TaprootUnknownPath |
          _: ScriptWitnessV0 | EmptyScriptWitness) =>
        sys.error(s"Could not parse taproot keypath, got=$x")
    }
  }

  def apply(signature: SchnorrDigitalSignature): TaprootKeyPath = {
    TaprootKeyPath(signature, None)
  }

  def fromStack(vec: Vector[ByteVector]): TaprootKeyPath = {
    val hasAnnex = TaprootScriptPath.hasAnnex(vec)
    require(
      vec.length == 1 || (hasAnnex && vec.length == 2),
      s"Taproot keypath can only have at most 2 stack elements, got=${vec.length}")

    val annexOpt = {
      if (hasAnnex) {
        Some(Annex.fromBytes(vec.head))
      } else {
        None
      }
    }
    val sigBytes = {
      if (hasAnnex) {
        vec(1)
      } else {
        vec.head
      }
    }

    val keyPath = if (sigBytes.length == 64 || sigBytes.length == 65) {
      // means SIGHASH_DEFAULT is implicitly encoded
      // see: https://github.com/bitcoin/bips/blob/master/bip-0341.mediawiki#Common_signature_message
      val sig = SchnorrDigitalSignature.fromBytes(sigBytes)
      new TaprootKeyPath(sig, annexOpt)
    } else {
      sys.error(
        s"Unknown sig bytes length, should be 64 or 65, got=${sigBytes.length}")
    }

    keyPath
  }

  def isValid(stack: Vector[ByteVector]): Boolean = {
    val noAnnex =
      stack.length == 1 && (stack.head.length == 64 || stack.head.length == 65) &&
        SchnorrPublicKey
          .fromBytesT(stack.head.take(32))
          .isSuccess && checkHashType(stack.head)
    val annex =
      stack.length == 2 && TaprootScriptPath.hasAnnex(stack) &&
        (stack(1).length == 64 || stack(1).length == 65) &&
        SchnorrPublicKey
          .fromBytesT(stack(1).take(32))
          .isSuccess && checkHashType(stack(1))
    noAnnex || annex
  }

  private def checkHashType(bytes: ByteVector): Boolean = {
    require(bytes.length == 64 || bytes.length == 65)
    if (bytes.length == 64) true
    else {
      val h = HashType.fromByte(bytes(64))
      HashType.checkTaprootHashType(h)
    }
  }
}

/** Spending a taproot output via the script path */
case class TaprootScriptPath(stack: Vector[ByteVector]) extends TaprootWitness {
  require(TaprootScriptPath.isValid(stack),
          s"Invalid witness stack for TaprootScriptPath, got=$stack")

  val controlBlock: TapscriptControlBlock = {
    if (TaprootScriptPath.hasAnnex(stack)) {
      // If there are at least two witness elements, and the first byte of the last element is 0x50[4],
      // this last element is called annex a[5] and is removed from the witness stack.
      // The annex (or the lack of thereof) is always covered by the signature and contributes to transaction weight,
      // but is otherwise ignored during taproot validation.
      // see: https://github.com/bitcoin/bips/blob/master/bip-0341.mediawiki#script-validation-rules
      TapscriptControlBlock.fromBytes(stack(1))
    } else {
      TapscriptControlBlock.fromBytes(stack.head)
    }
  }

  /** If there are at least two witness elements, and the first byte of the last
    * element is 0x50[4], this last element is called annex a[5] and is removed
    * from the witness stack. The annex (or the lack of thereof) is always
    * covered by the signature and contributes to transaction weight, but is
    * otherwise ignored during taproot validation.
    */
  override def annexOpt: Option[Annex] = {
    if (TaprootScriptPath.hasAnnex(stack)) {
      Some(Annex.fromBytes(stack.head))
    } else {
      None
    }
  }

  /** Call the second-to-last stack element s, the script
    */
  def script: ScriptPubKey = {
    annexOpt match {
      case Some(_) =>
        ScriptPubKey.fromAsmBytes(stack(2))
      case None =>
        val spk = ScriptPubKey.fromAsmBytes(stack(1))
        spk
    }
  }

  /** Let p = c[1:33] and let P = lift_x(int(p)) where lift_x and [:] are
    * defined as in BIP340. Fail if this point is not on the curve.
    */
  def p: XOnlyPubKey = controlBlock.p

  def leafVersion: LeafVersion = controlBlock.leafVersion

  override def sigVersion: SigVersionTapscript.type = leafVersion match {
    case LeafVersion.Tapscript => SigVersionTapscript
    case UnknownLeafVersion(toByte) =>
      sys.error(s"Unknown leaf version=$toByte, cannot determine sigVersion")
  }
}

object TaprootScriptPath extends Factory[TaprootScriptPath] {

  final val annex: Byte = 0x50

  final val annexOpt: Option[Byte] = Some(annex)

  final val TAPROOT_CONTROL_BASE_SIZE: Byte = 33
  final val TAPROOT_CONTROL_NODE_SIZE: Byte = 32
  final val TAPROOT_CONTROL_MAX_NODE_COUNT: Int = 128

  final val TAPROOT_CONTROL_MAX_SIZE: Int = {
    TAPROOT_CONTROL_BASE_SIZE + TAPROOT_CONTROL_NODE_SIZE * TAPROOT_CONTROL_MAX_NODE_COUNT
  }
  override def fromBytes(bytes: ByteVector): TaprootScriptPath = {
    RawScriptWitnessParser.read(bytes) match {
      case t: TaprootScriptPath => t
      case x => sys.error(s"Could not parse taproot scriptpath, got=$x")

    }
  }

  def isValid(stack: Vector[ByteVector]): Boolean = {
    if (stack.length >= 2) {
      val controlBlock = {
        if (TaprootScriptPath.hasAnnex(stack)) {
          // If there are at least two witness elements, and the first byte of the last element is 0x50[4],
          // this last element is called annex a[5] and is removed from the witness stack.
          // The annex (or the lack of thereof) is always covered by the signature and contributes to transaction weight,
          // but is otherwise ignored during taproot validation.
          // see: https://github.com/bitcoin/bips/blob/master/bip-0341.mediawiki#script-validation-rules
          stack(1)
        } else {
          stack.head
        }
      }
      TapscriptControlBlock.isValid(controlBlock)
    } else {
      false
    }
  }

  def fromStack(stack: Vector[ByteVector]): TaprootScriptPath =
    TaprootScriptPath(stack)

  def apply(
      controlBlock: TapscriptControlBlock,
      annexOpt: Option[Annex],
      spk: RawScriptPubKey): TaprootScriptPath = {
    annexOpt match {
      case Some(annexBytes) =>
        fromStack(Vector(annexBytes.bytes, controlBlock.bytes, spk.asmBytes))
      case None =>
        fromStack(Vector(controlBlock.bytes, spk.asmBytes))
    }
  }

  def verifyTaprootCommitment(
      controlBlock: ControlBlock,
      program: TaprootScriptPubKey,
      tapLeafHash: Sha256Digest): Boolean = {
    val internalPubKey = controlBlock.p
    val merkleRoot = computeTaprootMerkleRoot(controlBlock, tapLeafHash)

    val parity = (controlBlock.bytes.head & 1) == 1
    program.pubKey.checkTapTweak(internal = internalPubKey,
                                 merkleRootOpt = Some(merkleRoot),
                                 parity = parity)
  }

  /** Computes the hash of the script that is a leaf in the tapscript tree
    * @param leafVersion
    * @param spk
    * @see
    *   https://github.com/bitcoin/bitcoin/blob/37633d2f61697fc719390767aae740ece978b074/src/script/interpreter.cpp#L1828
    * @return
    */
  def computeTapleafHash(leaf: TapLeaf): Sha256Digest = {
    val bytes =
      ByteVector.fromInt(i = leaf.leafVersion.toByte,
                         size = 1) ++ leaf.spk.bytes
    CryptoUtil.tapLeafHash(bytes)
  }

  /** Computes the merkle root of a tapscript tree
    * @param controlBlock
    * @param tapLeafHash
    * @see
    *   https://github.com/bitcoin/bitcoin/blob/37633d2f61697fc719390767aae740ece978b074/src/script/interpreter.cpp#L1833
    * @return
    */
  def computeTaprootMerkleRoot(
      controlBlock: ControlBlock,
      tapLeafHash: Sha256Digest): Sha256Digest = {
    val pathLen =
      (controlBlock.bytes.size - TaprootScriptPath.TAPROOT_CONTROL_BASE_SIZE) / TaprootScriptPath.TAPROOT_CONTROL_NODE_SIZE
    var k = tapLeafHash
    var i = 0
    while (i < pathLen) {
      val startIdx =
        TaprootScriptPath.TAPROOT_CONTROL_BASE_SIZE + (TaprootScriptPath.TAPROOT_CONTROL_NODE_SIZE * i)
      val endIdx = startIdx + TaprootScriptPath.TAPROOT_CONTROL_NODE_SIZE
      val node = controlBlock.bytes.slice(startIdx, endIdx)
      if (k.hex.compareTo(node.toHex) < 0) {
        k = hashTapBranch(k.bytes ++ node)
      } else {
        k = hashTapBranch(node ++ k.bytes)
      }
      i += 1
    }
    k
  }

  def computeFullTreeMerkleRoot(tree: TapscriptTree): Sha256Digest = {
    tree match {
      case branch: TapBranch =>
        val left = computeFullTreeMerkleRoot(branch.tree1)
        val right = computeFullTreeMerkleRoot(branch.tree2)
        val sorted = if (left.bytes.compare(right.bytes) <= 0) {
          left.bytes ++ right.bytes
        } else {
          right.bytes ++ left.bytes
        }
        CryptoUtil.tapBranchHash(sorted)
      case l: TapLeaf =>
        l.sha256
    }

  }

  /** Checks the witness stack has an annex in it */
  def hasAnnex(stack: Vector[ByteVector]): Boolean = {
    stack.length > 1 && stack.headOption
      .map(_.headOption == annexOpt)
      .getOrElse(false)
  }

  private def hashTapBranch(bytes: ByteVector): Sha256Digest = {
    CryptoUtil.tapBranchHash(bytes)
  }
}

case class TaprootUnknownPath(stack: Vector[ByteVector])
    extends TaprootWitness {

  val controlBlock: UnknownControlBlock = {
    if (TaprootScriptPath.hasAnnex(stack)) {
      // If there are at least two witness elements, and the first byte of the last element is 0x50[4],
      // this last element is called annex a[5] and is removed from the witness stack.
      // The annex (or the lack of thereof) is always covered by the signature and contributes to transaction weight,
      // but is otherwise ignored during taproot validation.
      // see: https://github.com/bitcoin/bips/blob/master/bip-0341.mediawiki#script-validation-rules
      UnknownControlBlock.fromBytes(stack(1))
    } else {
      UnknownControlBlock.fromBytes(stack.head)
    }
  }

  override def annexOpt: Option[Annex] = {
    if (TaprootScriptPath.hasAnnex(stack)) {
      Some(Annex.fromBytes(stack.head))
    } else {
      None
    }
  }

  override def sigVersion: SigVersionTaproot = {
    // default to tapscript for unknown leafver and sigversion?
    SigVersionTapscript
  }
}
