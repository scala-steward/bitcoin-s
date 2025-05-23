package org.bitcoins.testkit.eclair.rpc

import org.apache.pekko.actor.ActorSystem
import com.typesafe.config.{Config, ConfigFactory}
import org.bitcoins.commons.jsonmodels.eclair.{
  IncomingPaymentStatus,
  OutgoingPayment,
  OutgoingPaymentStatus,
  PaymentId
}
import org.bitcoins.commons.util.BitcoinSLogger
import org.bitcoins.core.currency.{Bitcoins, CurrencyUnit, Satoshis}
import org.bitcoins.core.protocol.ln.channel.{
  ChannelId,
  ChannelState,
  FundedChannelId
}
import org.bitcoins.core.protocol.ln.currency.MilliSatoshis
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.crypto.Sha256Digest
import org.bitcoins.eclair.rpc.api.*
import org.bitcoins.eclair.rpc.client.EclairRpcClient
import org.bitcoins.eclair.rpc.config.EclairInstanceLocal
import org.bitcoins.rpc.client.common.{BitcoindRpcClient, BitcoindVersion}
import org.bitcoins.rpc.config.{
  BitcoindAuthCredentials,
  BitcoindInstance,
  BitcoindInstanceLocal,
  BitcoindInstanceRemote,
  BitcoindRpcAppConfig,
  ZmqConfig
}
import org.bitcoins.rpc.util.RpcUtil
import org.bitcoins.testkit.async.TestAsyncUtil
import org.bitcoins.testkit.rpc.BitcoindRpcTestUtil
import org.bitcoins.testkit.util.{EclairRpcTestClient, FileUtil}

import java.io.{File, PrintWriter}
import java.net.URI
import java.nio.file.Path
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.MapHasAsJava
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

/** @define nodeLinkDoc
  *   Creates four Eclair nodes that are connected in the following manner:
  *   {{{
  *   node1 <-> node2 <-> node3 <-> node4
  *   }}}
  *
  * Each double sided arrow represents a P2P connection as well as a funded
  * channel
  */
trait EclairRpcTestUtil extends BitcoinSLogger {

  def randomEclairDatadir(): File =
    new File(s"/tmp/eclair-test/${FileUtil.randomDirName}/.eclair/")

  def cannonicalDatadir = new File(s"${System.getenv("HOME")}/.reg_eclair/")

  /** Makes a best effort to get a 0.16 bitcoind instance
    */
  def startedBitcoindRpcClient(
      instanceOpt: Option[BitcoindInstanceLocal] = None
  )(implicit actorSystem: ActorSystem): Future[BitcoindRpcClient] = {
    // need to do something with the Vector.newBuilder presumably?
    val instance = instanceOpt.getOrElse(bitcoindInstance())
    BitcoindRpcTestUtil.startedBitcoindRpcClient(
      Some(instance),
      Vector.newBuilder
    )
  }

  /** Creates a bitcoind instance with the given parameters */
  def bitcoindInstance(
      port: Int = RpcUtil.randomPort,
      rpcPort: Int = RpcUtil.randomPort,
      zmqConfig: ZmqConfig = RpcUtil.zmqConfig,
      bitcoindV: BitcoindVersion = EclairRpcClient.bitcoindV
  )(implicit system: ActorSystem): BitcoindInstanceLocal = {
    BitcoindRpcTestUtil.getInstance(
      bitcoindVersion = bitcoindV,
      port = port,
      rpcPort = rpcPort,
      zmqConfig = zmqConfig
    )
  }

  // cribbed from https://github.com/Christewart/eclair/blob/bad02e2c0e8bd039336998d318a861736edfa0ad/eclair-core/src/test/scala/fr/acinq/eclair/integration/IntegrationSpec.scala#L140-L153
  private[rpc] def commonConfig(
      bitcoindInstance: BitcoindInstance,
      port: Int = RpcUtil.randomPort,
      apiPort: Int = RpcUtil.randomPort
  ): Config = {
    val configMap = {
      val hashBlock = bitcoindInstance.zmqConfig.hashBlock.get
      val rawTx = bitcoindInstance.zmqConfig.rawTx.get
      Map[String, Any](
        "eclair.chain" -> "regtest",
        "eclair.spv" -> false,
        "eclair.server.public-ips.1" -> "127.0.0.1",
        "eclair.server.public-ips.2" -> "of7husrflx7sforh3fw6yqlpwstee3wg5imvvmkp4bz6rbjxtg5nljad.onion",
        "eclair.server.binding-ip" -> "0.0.0.0",
        "eclair.server.port" -> port,
        "eclair.bitcoind.rpcuser" -> bitcoindInstance.authCredentials
          .asInstanceOf[BitcoindAuthCredentials.PasswordBased]
          .username,
        "eclair.bitcoind.rpcpassword" -> bitcoindInstance.authCredentials
          .asInstanceOf[BitcoindAuthCredentials.PasswordBased]
          .password,
        "eclair.bitcoind.rpcport" -> bitcoindInstance.rpcUri.getPort,
        "eclair.bitcoind.zmqblock" -> s"tcp://${hashBlock.getHostName}:${hashBlock.getPort}",
        "eclair.bitcoind.zmqtx" -> s"tcp://${rawTx.getHostName}:${rawTx.getPort}",
        "eclair.api.enabled" -> true,
        "eclair.api.binding-ip" -> "127.0.0.1",
        "eclair.api.password" -> "abc123",
        "eclair.api.port" -> apiPort,
        "eclair.channel.mindepth-blocks" -> 2,
        "eclair.channel.max-htlc-value-in-flight-msat" -> 100000000000L,
        "eclair.router.broadcast-interval" -> "2 second",
        "eclair.auto-reconnect" -> false,
        "eclair.channel.to-remote-delay-blocks" -> 144,
        "eclair.db.regtest.url" -> "jdbc:sqlite:regtest/",
        "eclair.max-payment-fee" -> 10, // avoid complaints about too high fees
        "eclair.alias" -> "suredbits",
        "eclair.channel.fulfill-safety-before-timeout-blocks" -> 1,
        "eclair.channel.min-final-expiry-delta-blocks" -> 2,
        "eclair.features.keysend" -> "optional",
        // for some reason dual funded channels causes tests to fail on CI
        // but not locally on my laptop, disable them for now
        "eclair.features.option_dual_fund" -> "disabled"
      )
    }
    val c = ConfigFactory.parseMap(configMap.asJava)
    c
  }

  def eclairDataDir(
      bitcoindRpcClient: BitcoindRpcClient,
      isCannonical: Boolean
  ): File = {
    val bitcoindInstance = bitcoindRpcClient.instance
    if (isCannonical) {
      // assumes that the ${HOME}/.eclair/eclair.conf file is created AND a bitcoind instance is running
      cannonicalDatadir
    } else {
      // creates a random eclair datadir, but still assumes that a bitcoind instance is running right now
      val datadir = randomEclairDatadir()
      datadir.mkdirs()
      logger.trace(s"Creating temp eclair dir ${datadir.getAbsolutePath}")

      val config = commonConfig(bitcoindInstance)

      new PrintWriter(new File(datadir, "eclair.conf")) {
        write(config.root().render())
        close
      }
      datadir
    }
  }

  /** Assumes bitcoind is running already and you have specified correct
    * bindings in eclair.conf
    */
  def cannonicalEclairInstance(
      logbackXml: Option[String] = None
  ): EclairInstanceLocal = {
    val datadir = cannonicalDatadir
    eclairInstance(datadir, logbackXml)
  }

  def eclairInstance(
      datadir: File,
      logbackXml: Option[String]
  ): EclairInstanceLocal = {
    val instance = EclairInstanceLocal.fromDatadir(datadir, logbackXml, None)
    instance
  }

  /** Starts the given bitcoind instance and then starts the eclair instance */
  def eclairInstance(
      bitcoindRpc: BitcoindRpcClient,
      logbackXml: Option[String] = None
  ): EclairInstanceLocal = {
    val datadir = eclairDataDir(bitcoindRpc, false)
    eclairInstance(datadir, logbackXml)
  }

  def randomEclairInstance(
      bitcoindRpc: BitcoindRpcClient,
      logbackXml: Option[String] = None
  ): EclairInstanceLocal = {
    val datadir = eclairDataDir(bitcoindRpc, false)
    eclairInstance(datadir, logbackXml)
  }

  def randomEclairClient(
      bitcoindRpcOpt: Option[BitcoindRpcClient] = None,
      eclairVersionOpt: Option[String] = None,
      eclairCommitOpt: Option[String] = None
  )(implicit system: ActorSystem): Future[EclairRpcClient] = {
    val eclairRpcTestClient = EclairRpcTestClient.fromSbtDownload(
      eclairVersionOpt = eclairVersionOpt,
      eclairCommitOpt = eclairCommitOpt,
      bitcoindRpcClientOpt = bitcoindRpcOpt
    )

    eclairRpcTestClient.start()
  }

  def cannonicalEclairClient(
      eclairVersionOpt: Option[String] = None,
      eclairCommitOpt: Option[String] = None,
      binaryDirectory: Path = EclairRpcTestClient.sbtBinaryDirectory
  )(implicit system: ActorSystem): EclairRpcClient = {
    val inst = cannonicalEclairInstance()
    new EclairRpcClient(
      inst,
      EclairRpcTestClient.getBinary(
        eclairVersionOpt = eclairVersionOpt,
        eclairCommitOpt = eclairCommitOpt,
        binaryDirectory = binaryDirectory
      )
    )
  }

  /** Doesn't return until the given channelId is in the
    * [[org.bitcoins.core.protocol.ln.channel.ChannelState ChannelState.NORMAL]]
    * for this
    * [[org.bitcoins.eclair.rpc.client.EclairRpcClient EclairRpcClient]]
    * @param client
    * @param chanId
    */
  def awaitUntilChannelNormal(client: EclairApi, chanId: ChannelId)(implicit
      system: ActorSystem
  ): Future[Unit] = {
    awaitUntilChannelState(client, chanId, ChannelState.NORMAL)
  }

  def awaitUntilChannelClosing(client: EclairApi, chanId: ChannelId)(implicit
      system: ActorSystem
  ): Future[Unit] = {
    awaitUntilChannelState(client, chanId, ChannelState.CLOSING)
  }

  private def awaitUntilChannelState(
      client: EclairApi,
      chanId: ChannelId,
      state: ChannelState
  )(implicit system: ActorSystem): Future[Unit] = {
    import system.dispatcher
    logger.debug(s"Awaiting ${chanId} to enter ${state} state")
    def isState(): Future[Boolean] = {
      val chanF = client.channel(chanId)
      chanF.map { chan =>
        if (!(chan.state == state)) {
          logger.trace(
            s"ChanId ${chanId} has not entered ${state} yet. Currently in ${chan.state}"
          )
        }
        chan.state == state
      }(system.dispatcher)
    }

    TestAsyncUtil.retryUntilSatisfiedF(
      conditionF = () => isState(),
      interval = 1.seconds
    )
  }

  def awaitUntilPaymentSucceeded(
      client: EclairApi,
      paymentId: PaymentId,
      duration: FiniteDuration = 1.second,
      maxTries: Int = 60,
      failFast: Boolean = true
  )(implicit ec: ExecutionContext): Future[Unit] = {
    awaitUntilOutgoingPaymentStatus[OutgoingPaymentStatus.Succeeded](
      client,
      paymentId,
      duration,
      maxTries,
      failFast
    )
  }

  def awaitUntilPaymentFailed(
      client: EclairApi,
      paymentId: PaymentId,
      duration: FiniteDuration = 1.second,
      maxTries: Int = 60,
      failFast: Boolean = false
  )(implicit ec: ExecutionContext): Future[Unit] = {
    awaitUntilOutgoingPaymentStatus[OutgoingPaymentStatus.Failed](
      client,
      paymentId,
      duration,
      maxTries,
      failFast
    )
  }

  private def awaitUntilOutgoingPaymentStatus[T <: OutgoingPaymentStatus](
      client: EclairApi,
      paymentId: PaymentId,
      interval: FiniteDuration,
      maxTries: Int,
      failFast: Boolean
  )(implicit ec: ExecutionContext, tag: ClassTag[T]): Future[Unit] = {
    logger.debug(
      s"Awaiting payment ${paymentId} to enter ${tag.runtimeClass.getName} state"
    )

    def isFailed(status: OutgoingPaymentStatus): Boolean =
      status match {
        case _: OutgoingPaymentStatus.Failed => true
        case _: OutgoingPaymentStatus        => false
      }

    def isInState(): Future[Boolean] = {

      val sentInfoF: Future[Vector[OutgoingPayment]] =
        client.getSentInfo(paymentId)
      sentInfoF.map { payment =>
        if (failFast && payment.exists(result => isFailed(result.status))) {
          throw new RuntimeException(
            s"Payment ${paymentId} has failed: $payment"
          )
        }
        if (
          !payment.exists(result => tag.runtimeClass == result.status.getClass)
        ) {
          logger.trace(
            s"Payment ${paymentId} has not entered ${tag.runtimeClass.getName} yet. Currently in ${payment.map(_.status).mkString(",")}"
          )
          false
        } else {
          true
        }
      }
    }

    TestAsyncUtil.retryUntilSatisfiedF(
      conditionF = () => isInState(),
      interval = interval,
      maxTries = maxTries
    )
  }

  def awaitUntilIncomingPaymentStatus[T <: IncomingPaymentStatus](
      client: EclairApi,
      paymentHash: Sha256Digest,
      interval: FiniteDuration = 1.second,
      maxTries: Int = 60
  )(implicit system: ActorSystem, tag: ClassTag[T]): Future[Unit] = {
    import system.dispatcher
    logger.debug(
      s"Awaiting payment ${paymentHash} to enter ${tag.runtimeClass.getName} state"
    )

    def isInState(): Future[Boolean] = {

      client
        .getReceivedInfo(paymentHash)
        .map { payment =>
          if (
            !payment
              .exists(result => tag.runtimeClass == result.status.getClass)
          ) {
            logger.trace(
              s"Payment ${paymentHash} has not entered ${tag.runtimeClass.getName} yet. Currently in ${payment.map(_.status).mkString(",")}"
            )
            false
          } else {
            true
          }
        }(system.dispatcher)
    }

    TestAsyncUtil.retryUntilSatisfiedF(
      conditionF = () => isInState(),
      interval = interval,
      maxTries = maxTries
    )
  }

  private def createNodeLink(
      bitcoindRpcClient: Option[BitcoindRpcClient],
      channelAmount: MilliSatoshis
  )(implicit actorSystem: ActorSystem): Future[EclairNodes4] = {
    implicit val ec: ExecutionContext = actorSystem.dispatcher
    val internalBitcoindF = {
      if (bitcoindRpcClient.isDefined) {
        Future.successful(bitcoindRpcClient.get)
      } else {
        EclairRpcTestUtil.startedBitcoindRpcClient()
      }
    }

    val pair1: Future[(EclairRpcClient, EclairRpcClient)] = {
      internalBitcoindF.flatMap(b => createNodePair(Some(b)))
    }

    val pair2: Future[(EclairRpcClient, EclairRpcClient)] = {
      internalBitcoindF.flatMap(b => createNodePair(Some(b)))
    }

    def open(
        c1: EclairRpcClient,
        c2: EclairRpcClient
    ): Future[FundedChannelId] = {
      openChannel(
        n1 = c1,
        n2 = c2,
        amt = channelAmount.toSatoshis,
        pushMSat = MilliSatoshis(channelAmount.toLong / 2)
      )
    }

    val nodeVecF: Future[Vector[EclairRpcClient]] = {
      pair1.flatMap { case (first, second) =>
        pair2.flatMap { case (third, fourth) =>
          // we need to make sure the second and third nodes are connected
          val connected = EclairRpcTestUtil.connectLNNodes(second, third)
          connected.map { _ =>
            Vector(first, second, third, fourth)
          }

        }
      }
    }

    val openChannelsFNested: Future[List[Future[FundedChannelId]]] = {
      nodeVecF.map { nodeVec =>
        List(
          open(nodeVec.head, nodeVec(1)),
          open(nodeVec(1), nodeVec(2)),
          open(nodeVec(2), nodeVec(3))
        )

      }
    }

    val openChannelsF: Future[List[FundedChannelId]] = {
      openChannelsFNested.flatMap(Future.sequence(_))
    }

    val genBlocksF = openChannelsF.flatMap { _ =>
      internalBitcoindF.flatMap(client => client.generate(3))
    }

    genBlocksF.flatMap { _ =>
      nodeVecF.map { nodeVec =>
        EclairNodes4(nodeVec.head, nodeVec(1), nodeVec(2), nodeVec(3))
      }
    }
  }

  def openAndConfirmChannel(
      client1: EclairRpcClient,
      client2: EclairRpcClient,
      amount: CurrencyUnit = Satoshis(10000000)
  )(implicit system: ActorSystem): Future[ChannelId] = {
    import system.dispatcher
    val bitcoind = EclairRpcTestUtil.getBitcoindRpc(client1)

    val nodeId2F: Future[NodeId] = client2.getInfo.map(_.nodeId)

    val channelIdF: Future[ChannelId] = {
      nodeId2F.flatMap { nid2 =>
        client1.open(nid2, amount)
      }
    }

    // confirm the funding tx
    val genF = for {
      _ <- channelIdF
      address <- bitcoind.getNewAddress
      headers <- bitcoind.generateToAddress(6, address)
    } yield headers

    channelIdF.flatMap { cid =>
      genF.flatMap { _ =>
        // wait until channel is in
        // NORMAL state on both sides so we can route payments bidirectionally
        for {
          _ <- EclairRpcTestUtil.awaitUntilChannelNormal(client1, cid)
          _ <- EclairRpcTestUtil.awaitUntilChannelNormal(client2, cid)
        } yield cid
      }
    }
  }

  /** $nodeLinkDoc
    * @note
    *   Blocks the current thread
    * @return
    *   A 4-tuple of the created nodes' respective
    *   [[org.bitcoins.eclair.rpc.client.EclairRpcClient EclairRpcClient]]
    */
  def createNodeLink(
      bitcoindRpcClient: BitcoindRpcClient
  )(implicit actorSystem: ActorSystem): Future[EclairNodes4] = {
    createNodeLink(Some(bitcoindRpcClient), DEFAULT_CHANNEL_MSAT_AMT)
  }

  /** $nodeLinkDoc
    * @note
    *   Blocks the current thread
    * @return
    *   A 4-tuple of the created nodes' respective
    *   [[org.bitcoins.eclair.rpc.client.EclairRpcClient EclairRpcClient]]
    */
  def createNodeLink(
      bitcoindRpcClient: BitcoindRpcClient,
      channelAmount: MilliSatoshis
  )(implicit actorSystem: ActorSystem): Future[EclairNodes4] = {
    createNodeLink(Some(bitcoindRpcClient), channelAmount)
  }

  /** $nodeLinkDoc
    * @note
    *   Blocks the current thread
    * @return
    *   A 4-tuple of the created nodes' respective
    *   [[org.bitcoins.eclair.rpc.client.EclairRpcClient EclairRpcClient]]
    */
  def createNodeLink()(implicit
      actorSystem: ActorSystem
  ): Future[EclairNodes4] = {
    createNodeLink(None, DEFAULT_CHANNEL_MSAT_AMT)
  }

  /** $nodeLinkDoc
    * @note
    *   Blocks the current thread
    * @return
    *   A 4-tuple of the created nodes' respective
    *   [[org.bitcoins.eclair.rpc.client.EclairRpcClient EclairRpcClient]]
    */
  def createNodeLink(
      channelAmount: MilliSatoshis
  )(implicit actorSystem: ActorSystem): Future[EclairNodes4] = {
    createNodeLink(None, channelAmount)
  }

  /** Creates two Eclair nodes that are connected together and returns their
    * respective
    * [[org.bitcoins.eclair.rpc.client.EclairRpcClient EclairRpcClient]]s
    */
  def createNodePair(
      bitcoindRpcClientOpt: Option[BitcoindRpcClient],
      eclairVersionOpt1: Option[String] = None,
      eclairCommitOpt1: Option[String] = None,
      eclairVersionOpt2: Option[String] = None,
      eclairCommitOpt2: Option[String] = None,
      binaryDirectory: Path = EclairRpcTestClient.sbtBinaryDirectory
  )(implicit
      system: ActorSystem
  ): Future[(EclairRpcClient, EclairRpcClient)] = {
    import system.dispatcher
    val bitcoindRpcClientF: Future[BitcoindRpcClient] = {

      if (bitcoindRpcClientOpt.isDefined) {
        Future.successful(bitcoindRpcClientOpt.get)
      } else {
        EclairRpcTestUtil.startedBitcoindRpcClient()
      }
    }

    val e1InstanceF =
      bitcoindRpcClientF.map(EclairRpcTestUtil.eclairInstance(_))
    val e2InstanceF =
      bitcoindRpcClientF.map(EclairRpcTestUtil.eclairInstance(_))

    val clientF = e1InstanceF.flatMap { e1 =>
      val e =
        new EclairRpcClient(
          e1,
          EclairRpcTestClient.getBinary(
            eclairVersionOpt1,
            eclairCommitOpt1,
            binaryDirectory
          )
        )
      logger.debug(
        s"Temp eclair directory created ${e.getDaemon.authCredentials.datadir}"
      )
      e.start().map(_ => e)
    }
    val otherClientF = e2InstanceF.flatMap { e2 =>
      val e =
        new EclairRpcClient(
          e2,
          EclairRpcTestClient.getBinary(
            eclairVersionOpt2,
            eclairCommitOpt2,
            binaryDirectory
          )
        )
      logger.debug(
        s"Temp eclair directory created ${e.getDaemon.authCredentials.datadir}"
      )
      e.start().map(_ => e)
    }

    val connectedLnF: Future[(EclairRpcClient, EclairRpcClient)] =
      clientF.flatMap { c1 =>
        otherClientF.flatMap { c2 =>
          val connectedF = connectLNNodes(c1, c2)
          connectedF.map { _ =>
            (c1, c2)
          }
        }
      }

    connectedLnF
  }

  def connectLNNodes(client: EclairApi, otherClient: EclairApi)(implicit
      system: ActorSystem
  ): Future[Unit] = {
    implicit val dispatcher = system.dispatcher
    val infoF = otherClient.getInfo
    val nodeIdF = infoF.map(_.nodeId)
    val connection: Future[Unit] = infoF.flatMap { info =>
      client.connect(info.nodeId, info.publicAddresses.head)
    }

    def isConnected(): Future[Boolean] = {

      nodeIdF.flatMap { nodeId =>
        connection.flatMap { _ =>
          val connected: Future[Boolean] = client.isConnected(nodeId)
          connected
        }
      }
    }

    logger.debug(s"Awaiting connection between clients")
    val connected = TestAsyncUtil.retryUntilSatisfiedF(
      conditionF = () => isConnected(),
      interval = 1.second
    )

    connected.map(_ => logger.debug(s"Successfully connected two clients"))

    connected

  }

  /** Sends `numPayments` between `c1` and `c2`. No aspect of the payment (size,
    * description, etc) should be assumed to have a certain value, this method
    * is just for populating channel update history with <i>something<i/>.
    */
  def sendPayments(c1: EclairApi, c2: EclairApi, numPayments: Int = 5)(implicit
      ec: ExecutionContext
  ): Future[Vector[PaymentId]] = {
    val range = 1.to(numPayments).toVector
    val amounts = range.map(MilliSatoshis(_))
    val resultF =
      Future.traverse(amounts)(sats =>
        c1.createInvoice(s"this is a note for $sats")
          .flatMap(invoice => c2.payInvoice(invoice, sats)))
    resultF.onComplete {
      case Success(_) =>
      case Failure(_) =>
        val nodeId1F = c1.nodeId()
        val nodeId2F = c2.nodeId()
        nodeId1F.flatMap { nid1 =>
          nodeId2F.map { nid2 =>
            logger.error(
              s"Failed to send payments from ${nid1.hex} -> ${nid2.hex}"
            )
          }
        }
    }

    resultF
  }

  private val DEFAULT_CHANNEL_MSAT_AMT = MilliSatoshis(Bitcoins.one.satoshis)

  /** Opens a channel from n1 -> n2 */
  def openChannel(
      n1: EclairRpcClient,
      n2: EclairRpcClient,
      amt: CurrencyUnit = DEFAULT_CHANNEL_MSAT_AMT.toSatoshis,
      pushMSat: MilliSatoshis = MilliSatoshis(
        DEFAULT_CHANNEL_MSAT_AMT.toLong / 2
      )
  )(implicit system: ActorSystem): Future[FundedChannelId] = {

    val bitcoindRpcClient = getBitcoindRpc(n1)
    implicit val ec = system.dispatcher

    val n1NodeIdF = n1.nodeId()
    val n2NodeIdF = n2.nodeId()

    val nodeIdsF: Future[(NodeId, NodeId)] = {
      n1NodeIdF.flatMap(n1 => n2NodeIdF.map(n2 => (n1, n2)))
    }

    val fundedChannelIdF: Future[FundedChannelId] = {
      nodeIdsF.flatMap { case (nodeId1, nodeId2) =>
        logger.debug(
          s"Opening a channel from ${nodeId1} -> ${nodeId2} with amount ${amt}"
        )
        n1.open(
          nodeId = nodeId2,
          funding = amt,
          pushMsat = Some(pushMSat),
          feerateSatPerByte = None,
          channelFlags = None,
          openTimeout = None
        )
      }
    }

    val gen = for {
      _ <- fundedChannelIdF
      address <- bitcoindRpcClient.getNewAddress
      blocks <- bitcoindRpcClient.generateToAddress(6, address)
    } yield blocks

    val openedF = {
      gen.flatMap { _ =>
        fundedChannelIdF.flatMap { fcid =>
          val chanOpenF = awaitChannelOpened(n1, fcid)
          chanOpenF.map(_ => fcid)
        }
      }
    }

    openedF.flatMap { case _ =>
      nodeIdsF.map { case (nodeId1, nodeId2) =>
        logger.debug(
          s"Channel successfully opened ${nodeId1} -> ${nodeId2} with amount $amt"
        )
      }
    }

    openedF
  }

  def awaitChannelOpened(client1: EclairApi, chanId: ChannelId)(implicit
      system: ActorSystem
  ): Future[Unit] = {
    EclairRpcTestUtil.awaitUntilChannelNormal(client1, chanId)
  }

  def getBitcoindRpc(
      eclairRpcClient: EclairRpcClient,
      bitcoindVersion: BitcoindVersion = EclairRpcClient.bitcoindV
  )(implicit system: ActorSystem): BitcoindRpcClient = {
    val bitcoindRpc = {
      val instance = eclairRpcClient.instance
      val auth = instance.authCredentials
      val bitcoindRpcAppConfig =
        BitcoindRpcAppConfig(
          FileUtil.tmpDir().toPath,
          Vector.empty,
          auth.bitcoinAuthOpt
        )
      val bitcoindInstance = BitcoindInstanceRemote(
        network = instance.network,
        uri = new URI("http://localhost:18333"),
        rpcUri = auth.bitcoindRpcUri,
        zmqConfig = instance.zmqConfig.get
      )(system, bitcoindRpcAppConfig)
      BitcoindRpcClient.fromVersion(bitcoindVersion, bitcoindInstance)
    }
    bitcoindRpc
  }

  /** Returns a `Future` that is completed when both eclair and bitcoind have
    * the same block height Fails the future if they are not sychronized within
    * the given timeout.
    */
  def awaitEclairInSync(eclair: EclairRpcClient, bitcoind: BitcoindRpcClient)(
      implicit system: ActorSystem
  ): Future[Unit] = {
    import system.dispatcher
    TestAsyncUtil.retryUntilSatisfiedF(
      conditionF = () => clientInSync(eclair, bitcoind),
      interval = 1.seconds
    )
  }

  private def clientInSync(
      client: EclairRpcClient,
      bitcoind: BitcoindRpcClient
  )(implicit ec: ExecutionContext): Future[Boolean] =
    for {
      blockCount <- bitcoind.getBlockCount()
      info <- client.getInfo
    } yield info.blockHeight == blockCount

  /** Shuts down an eclair daemon */
  def shutdown(
      eclairRpcClient: EclairRpcClient
  )(implicit system: ActorSystem): Future[Unit] = {
    import system.dispatcher

    logger.debug(s"shutting down eclair")
    val stopEclairF = eclairRpcClient.stop()
    val iskilled = eclairRpcClient.isStopped

    val shutdownF = for {
      _ <- stopEclairF
      _ <- iskilled
    } yield {
      logger.debug(
        "Successfully shutdown eclair"
      )
    }
    shutdownF.failed.foreach { (err: Throwable) =>
      logger.error(
        s"Could kill eclair process",
        err
      )
    }
    shutdownF
  }

  case class EclairNetwork(
      bitcoind: BitcoindRpcClient,
      testEclairNode: EclairRpcClient,
      networkEclairNodes: Vector[EclairRpcClient],
      channelIds: Vector[FundedChannelId]
  ) {

    def shutdown()(implicit ec: ExecutionContext): Future[Unit] =
      for {
        _ <- Future.traverse(networkEclairNodes)(_.stop())
        _ <- testEclairNode.stop()
        _ <- bitcoind.stop()
      } yield ()
  }

  object EclairNetwork {

    def start(
        testEclairVersion: Option[String],
        testEclairCommit: Option[String],
        senderEclairVersion: Option[String],
        senderEclairCommit: Option[String],
        networkSize: Int,
        channelAmount: MilliSatoshis,
        logbackXml: Option[String],
        binaryDirectory: Path = EclairRpcTestClient.sbtBinaryDirectory
    )(implicit system: ActorSystem): Future[EclairNetwork] = {
      import system.dispatcher
      for {
        bitcoind <- startedBitcoindRpcClient()
        testEclairInstance =
          EclairRpcTestUtil.eclairInstance(bitcoind, logbackXml = logbackXml)
        testEclairNode = new EclairRpcClient(
          testEclairInstance,
          EclairRpcTestClient.getBinary(
            testEclairVersion,
            testEclairCommit,
            binaryDirectory
          )
        )
        _ <- testEclairNode.start()
        _ <- awaitEclairInSync(testEclairNode, bitcoind)
        networkEclairInstances =
          1
            .to(networkSize)
            .toVector
            .map(_ =>
              EclairRpcTestUtil
                .eclairInstance(bitcoind, logbackXml = logbackXml))
        networkEclairNodes = networkEclairInstances.map(
          new EclairRpcClient(
            _,
            EclairRpcTestClient.getBinary(
              senderEclairVersion,
              senderEclairCommit,
              binaryDirectory
            )
          )
        )
        _ <- Future.traverse(networkEclairNodes)(_.start())
        _ <- Future.traverse(networkEclairNodes)(awaitEclairInSync(_, bitcoind))
        _ <- Future.traverse(networkEclairNodes)(
          connectLNNodes(_, testEclairNode))
        channelIds <- networkEclairNodes.foldLeft(
          Future.successful(Vector.empty[FundedChannelId])
        ) { (accF, node) =>
          for {
            acc <- accF
            channelId <- openChannel(
              n1 = node,
              n2 = testEclairNode,
              amt = channelAmount.toSatoshis,
              pushMSat = MilliSatoshis(channelAmount.toLong / 2)
            )
          } yield acc :+ channelId
        }
        _ <-
          Future.sequence(channelIds.map(awaitChannelOpened(testEclairNode, _)))
      } yield EclairNetwork(
        bitcoind,
        testEclairNode,
        networkEclairNodes,
        channelIds
      )
    }

  }
}

object EclairRpcTestUtil extends EclairRpcTestUtil {
  var customConfigMap: Map[String, Any] = Map.empty

  override def commonConfig(
      bitcoindInstance: BitcoindInstance,
      port: Int,
      apiPort: Int
  ): Config =
    super
      .commonConfig(bitcoindInstance, port, apiPort)
      .withFallback(ConfigFactory.parseMap(customConfigMap.asJava))

}

case class EclairNodes4(
    c1: EclairRpcClient,
    c2: EclairRpcClient,
    c3: EclairRpcClient,
    c4: EclairRpcClient
)
