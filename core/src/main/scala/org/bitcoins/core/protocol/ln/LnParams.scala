package org.bitcoins.core.protocol.ln

import org.bitcoins.core.config._
import org.bitcoins.core.protocol.blockchain.ChainParams

sealed abstract class LnParams {
  def chain: ChainParams = network.chainParams

  def network: NetworkParameters

  def lnRpcPort: Int

  def lnPort: Int

  /** The prefix for generating invoices for a Lightning Invoice. See
    * [[https://github.com/lightningnetwork/lightning-rfc/blob/master/11-payment-encoding.md BOLT11]]
    * for more details
    */
  def invoicePrefix: String
}

object LnParams {

  case object LnBitcoinMainNet extends LnParams {
    override def network: MainNet.type = MainNet

    override def lnRpcPort = 8080

    override def lnPort = 9735

    override val invoicePrefix: String = "lnbc"
  }

  case object LnBitcoinTestNet extends LnParams {
    override def network: TestNet3.type = TestNet3

    override def lnRpcPort = 8080

    override def lnPort = 9735

    override val invoicePrefix: String = "lntb"
  }

  case object LnBitcoinSigNet extends LnParams {
    override def network: SigNet.type = SigNet

    override def lnRpcPort = 8080

    override def lnPort = 9735

    override val invoicePrefix: String = "lntbs"
  }

  case object LnBitcoinRegTest extends LnParams {
    override def network: RegTest.type = RegTest

    override def lnRpcPort = 8080

    override def lnPort = 9735

    override val invoicePrefix: String = "lnbcrt"
  }

  def fromNetworkParameters(np: NetworkParameters): LnParams =
    np match {
      case MainNet             => LnBitcoinMainNet
      case TestNet3 | TestNet4 => LnBitcoinTestNet
      case RegTest             => LnBitcoinRegTest
      case SigNet              => LnBitcoinSigNet
    }

  val allNetworks: Vector[LnParams] =
    Vector(LnBitcoinMainNet,
           LnBitcoinTestNet,
           LnBitcoinSigNet,
           LnBitcoinRegTest)

  private val prefixes: Map[String, LnParams] = {
    val vec: Vector[(String, LnParams)] = {
      allNetworks.map { network =>
        (network.invoicePrefix, network)
      }
    }
    vec.toMap
  }

  /** Returns a [[org.bitcoins.core.protocol.ln.LnParams LnParams]] whose
    * network prefix matches the given string. See
    * [[https://github.com/lightningnetwork/lightning-rfc/blob/master/11-payment-encoding.md#human-readable-part BOLT11]]
    * for more details on prefixes.
    */
  def fromPrefixString(str: String): Option[LnParams] = {
    prefixes.get(str)
  }
}
