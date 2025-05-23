package org.bitcoins.rpc.config

import com.typesafe.config.Config
import org.apache.pekko.actor.ActorSystem
import org.bitcoins.asyncutil.AsyncUtil
import org.bitcoins.commons.config.{AppConfig, ConfigOps}
import org.bitcoins.core.api.CallbackConfig
import org.bitcoins.core.api.callback.CallbackFactory
import org.bitcoins.core.api.tor.Socks5ProxyParams
import org.bitcoins.commons.rpc.BitcoindException.InWarmUp
import org.bitcoins.rpc.callback.BitcoindCallbacks
import org.bitcoins.rpc.client.common.{BitcoindRpcClient, BitcoindVersion}
import org.bitcoins.rpc.util.AppConfigFactoryActorSystem
import org.bitcoins.tor.config.TorAppConfig

import java.io.File
import java.net.{InetSocketAddress, URI}
import java.nio.file.*
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Future, Promise}

/** Configuration for a BitcoindRpcClient
  * @param directory
  *   The data directory of the Bitcoin-S instance
  * @param confs
  *   Optional sequence of configuration overrides
  */
case class BitcoindRpcAppConfig(
    baseDatadir: Path,
    configOverrides: Vector[Config],
    authCredentinalsOpt: Option[BitcoindAuthCredentials]
)(implicit val system: ActorSystem)
    extends AppConfig
    with CallbackConfig[BitcoindCallbacks] {

  import system.dispatcher

  override protected[bitcoins] def moduleName: String =
    BitcoindRpcAppConfig.moduleName

  override protected[bitcoins] type ConfigType = BitcoindRpcAppConfig

  override protected[bitcoins] def newConfigOfType(
      configs: Vector[Config]
  ): BitcoindRpcAppConfig =
    BitcoindRpcAppConfig(baseDatadir,
                         configs,
                         authCredentinalsOpt = authCredentinalsOpt)

  override def start(): Future[Unit] = Future.unit

  override def stop(): Future[Unit] = Future.unit

  lazy val DEFAULT_BINARY_PATH: Option[File] =
    BitcoindInstanceLocal.DEFAULT_BITCOIND_LOCATION

  lazy val binaryOpt: Option[File] =
    config.getStringOrNone("bitcoin-s.bitcoind-rpc.binary").map(new File(_))

  lazy val bitcoindDataDir = new File(
    config.getStringOrElse(
      "bitcoin-s.bitcoind-rpc.datadir",
      BitcoindConfig.DEFAULT_DATADIR.toString
    )
  )

  lazy val host = new URI({
    val baseUrl = {
      config.getStringOrNone("bitcoin-s.bitcoind-rpc.connect") match {
        case Some(rpcconnect) => rpcconnect
        case None             => "localhost"
      }
    }
    if (baseUrl.startsWith("http")) baseUrl
    else "http://" + baseUrl
  })

  lazy val port: Int =
    config.getIntOrElse("bitcoin-s.bitcoind-rpc.port", network.port)

  lazy val uri: URI = new URI(s"$host:$port")

  lazy val rpcHost = new URI({
    val baseUrl = {
      config.getStringOrNone("bitcoin-s.bitcoind-rpc.rpcconnect") match {
        case Some(rpcconnect) => rpcconnect
        case None             => "localhost"
      }
    }
    if (baseUrl.startsWith("http")) baseUrl
    else "http://" + baseUrl
  })

  lazy val rpcPort: Int =
    config.getIntOrElse("bitcoin-s.bitcoind-rpc.rpcport", network.rpcPort)

  lazy val rpcUri: URI = {
    val u = new URI(s"$rpcHost:$rpcPort")
    u
  }

  lazy val rpcUser: Option[String] = {
    authCredentinalsOpt.map(_.username).orElse {
      config.getStringOrNone("bitcoin-s.bitcoind-rpc.rpcuser")
    }
  }

  lazy val rpcPassword: Option[String] = {
    authCredentinalsOpt.map(_.password).orElse {
      config.getStringOrNone("bitcoin-s.bitcoind-rpc.rpcpassword")
    }
  }

  lazy val torConf: TorAppConfig =
    TorAppConfig(baseDatadir, Some(moduleName), configOverrides)

  lazy val socks5ProxyParams: Option[Socks5ProxyParams] =
    torConf.socks5ProxyParams

  lazy val versionOpt: Option[BitcoindVersion] =
    config
      .getStringOrNone("bitcoin-s.bitcoind-rpc.version")
      .map(BitcoindVersion.fromString)

  lazy val isRemote: Boolean =
    config.getBooleanOrElse("bitcoin-s.bitcoind-rpc.remote", default = false)

  lazy val authCredentials: BitcoindAuthCredentials = {
    authCredentinalsOpt.getOrElse {
      rpcUser match {
        case Some(rpcUser) => {
          rpcPassword match {
            case Some(rpcPassword) =>
              BitcoindAuthCredentials.PasswordBased(rpcUser, rpcPassword)
            case None =>
              BitcoindAuthCredentials.CookieBased(network)
          }
        }
        case None => BitcoindAuthCredentials.CookieBased(network)
      }
    }
  }

  lazy val zmqRawBlock: Option[InetSocketAddress] =
    config.getStringOrNone("bitcoin-s.bitcoind-rpc.zmqpubrawblock").map { str =>
      val uri = URI.create(str)
      new InetSocketAddress(uri.getHost, uri.getPort)
    }

  lazy val zmqRawTx: Option[InetSocketAddress] =
    config.getStringOrNone("bitcoin-s.bitcoind-rpc.zmqpubrawtx").map { str =>
      val uri = URI.create(str)
      new InetSocketAddress(uri.getHost, uri.getPort)
    }

  lazy val zmqHashBlock: Option[InetSocketAddress] =
    config.getStringOrNone("bitcoin-s.bitcoind-rpc.zmqpubashblock").map { str =>
      val uri = URI.create(str)
      new InetSocketAddress(uri.getHost, uri.getPort)
    }

  lazy val zmqHashTx: Option[InetSocketAddress] =
    config.getStringOrNone("bitcoin-s.bitcoind-rpc.zmqpubashtx").map { str =>
      val uri = URI.create(str)
      new InetSocketAddress(uri.getHost, uri.getPort)
    }

  lazy val zmqConfig: ZmqConfig =
    ZmqConfig(zmqHashBlock, zmqRawBlock, zmqHashTx, zmqRawTx)

  lazy val bitcoindInstance: BitcoindInstance = binaryOpt match {
    case Some(file) =>
      BitcoindInstanceLocal.apply(
        network = network,
        uri = uri,
        rpcUri = rpcUri,
        zmqConfig = zmqConfig,
        binary = file,
        bitcoindDatadir = bitcoindDataDir
      )(system, this)

    case None =>
      BitcoindInstanceRemote(
        network = network,
        uri = uri,
        rpcUri = rpcUri,
        zmqConfig = zmqConfig,
        proxyParams = socks5ProxyParams
      )(system, this)
  }

  /** Creates a bitcoind rpc client based on the [[bitcoindInstance]] configured
    */
  lazy val clientF: Future[BitcoindRpcClient] = {
    bitcoindInstance match {
      case local: BitcoindInstanceLocal =>
        val version = versionOpt.getOrElse(local.getVersion)
        val client =
          BitcoindRpcClient.fromVersion(version, local)
        Future.successful(client)
      case remote: BitcoindInstanceRemote =>
        // first get a generic rpc client so we can retrieve
        // the proper version of the remote running bitcoind
        val noVersionRpc = new BitcoindRpcClient(remote)(system)
        val versionF = getBitcoindVersion(noVersionRpc)

        // if we don't retrieve the proper version, we can
        // end up with exceptions on an rpc client that actually supports
        // specific features that are not supported across all versions of bitcoind
        // such as blockfilters
        // see: https://github.com/bitcoin-s/bitcoin-s/issues/3695#issuecomment-929492945
        versionF.map { version =>
          BitcoindRpcClient.fromVersion(version, instance = remote)(system)
        }
    }
  }

  private def getBitcoindVersion(
      client: BitcoindRpcClient
  ): Future[BitcoindVersion] = {
    val promise = Promise[BitcoindVersion]()
    val interval = 1.second
    val maxTries = 300 // 5 minutes
    for {
      _ <- AsyncUtil.retryUntilSatisfiedF(
        conditionF = { () =>
          val infoF = client.version
          val res = infoF.map(promise.success).map(_ => true)
          res.recover { case _: InWarmUp =>
            logger.info(s"Bitcoind still in warmup, trying again in $interval")
            false

          }
        },
        // retry for approximately 5 minutes
        mode = AsyncUtil.Linear,
        interval = interval,
        maxTries = maxTries
      )
      version <- promise.future
    } yield {
      logger.info(s"Retrieved bitcoind version=$version")
      version
    }
  }

  override def callbackFactory: CallbackFactory[BitcoindCallbacks] =
    BitcoindCallbacks
}

object BitcoindRpcAppConfig
    extends AppConfigFactoryActorSystem[BitcoindRpcAppConfig] {

  override val moduleName: String = "bitcoind"

  /** Constructs a node configuration from the default Bitcoin-S data directory
    * and given list of configuration overrides.
    */

  override def fromDatadir(datadir: Path, confs: Vector[Config])(implicit
      system: ActorSystem
  ): BitcoindRpcAppConfig =
    BitcoindRpcAppConfig(datadir, confs, authCredentinalsOpt = None)

}
