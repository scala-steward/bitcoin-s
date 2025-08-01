package org.bitcoins.testkit.rpc

import org.apache.pekko.actor.ActorSystem
import org.bitcoins.asyncutil.AsyncUtil
import org.bitcoins.commons.jsonmodels.bitcoind.RpcOpts.AddNodeArgument
import org.bitcoins.commons.jsonmodels.bitcoind.{
  GetBlockWithTransactionsResult,
  GetTransactionResult,
  RpcOpts,
  SignRawTransactionResult
}
import org.bitcoins.commons.rpc.BitcoindException
import org.bitcoins.commons.util.BitcoinSLogger
import org.bitcoins.core.config.RegTest
import org.bitcoins.core.currency.Bitcoins
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.script.ScriptSignature
import org.bitcoins.core.protocol.transaction.{
  Transaction,
  TransactionInput,
  TransactionOutPoint
}
import org.bitcoins.core.util.EnvUtil
import org.bitcoins.crypto.{
  DoubleSha256Digest,
  DoubleSha256DigestBE,
  ECPublicKey
}
import org.bitcoins.rpc.client.common.BitcoindVersion.{V27, V28, Unknown}
import org.bitcoins.rpc.client.common.{BitcoindRpcClient, BitcoindVersion}
import org.bitcoins.rpc.client.v27.BitcoindV27RpcClient
import org.bitcoins.rpc.client.v28.BitcoindV28RpcClient
import org.bitcoins.rpc.config.*
import org.bitcoins.rpc.util.{NodePair, RpcUtil}
import org.bitcoins.testkit.util.{BitcoindRpcTestClient, FileUtil, TorUtil}
import org.bitcoins.util.ListUtil

import java.io.File
import java.net.{InetSocketAddress, URI}
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.concurrent.*
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.*

//noinspection AccessorLikeMethodIsEmptyParen
trait BitcoindRpcTestUtil extends BitcoinSLogger {

  lazy val network: RegTest.type = RegTest

  type RpcClientAccum =
    mutable.Builder[BitcoindRpcClient, Vector[BitcoindRpcClient]]

  private def newUri: URI = new URI(s"http://localhost:${RpcUtil.randomPort}")

  private def newInetSocketAddres: InetSocketAddress = {
    new InetSocketAddress(RpcUtil.randomPort)
  }

  /** Standard config used for testing purposes
    */
  def standardConfig: BitcoindConfig = {

    val hashBlock = newInetSocketAddres
    val hashTx = newInetSocketAddres
    val rawBlock = newInetSocketAddres
    val rawTx = newInetSocketAddres
    val zmqConfig = ZmqConfig(
      hashBlock = Some(hashBlock),
      rawBlock = Some(rawBlock),
      hashTx = Some(hashTx),
      rawTx = Some(rawTx)
    )
    config(
      uri = newUri,
      rpcUri = newUri,
      zmqConfig = zmqConfig,
      pruneMode = false
    )
  }

  def config(
      uri: URI,
      rpcUri: URI,
      zmqConfig: ZmqConfig,
      pruneMode: Boolean,
      blockFilterIndex: Boolean = false
  ): BitcoindConfig = {
    val pass = FileUtil.randomDirName
    val username = "random_user_name"

    /* pruning and txindex are not compatible */
    val txindex = if (pruneMode) 0 else 1
    // windows environments don't allow the -daemon flag
    // see: https://github.com/bitcoin-s/bitcoin-s/issues/3684
    val isDaemon = if (EnvUtil.isWindows) 0 else 1
    // if bitcoind is not a daemon, we get a ton of logs
    // from bitcoind written to stdout, so turn off debug if we are not a daemon
    val isDebug = if (isDaemon == 1) 1 else 0
    val conf = s"""
                  |regtest=1
                  |server=1
                  |daemon=$isDaemon
                  |[regtest]
                  |rpcuser=$username
                  |rpcpassword=$pass
                  |rpcport=${rpcUri.getPort}
                  |bind=127.0.0.1:${uri.getPort}
                  |debug=$isDebug
                  |walletbroadcast=1
                  |mempoolfullrbf=1
                  |peerbloomfilters=1
                  |fallbackfee=0.0002
                  |txindex=$txindex
                  |zmqpubhashtx=tcp://${zmqConfig.hashTx.get.getHostString}:${zmqConfig.hashTx.get.getPort}
                  |zmqpubhashblock=tcp://${zmqConfig.hashBlock.get.getHostString}:${zmqConfig.hashBlock.get.getPort}
                  |zmqpubrawtx=tcp://${zmqConfig.rawTx.get.getHostString}:${zmqConfig.rawTx.get.getPort}
                  |zmqpubrawblock=tcp://${zmqConfig.rawBlock.get.getHostString}:${zmqConfig.rawBlock.get.getPort}
                  |prune=${if (pruneMode) 1 else 0}
    """.stripMargin
    val config =
      if (blockFilterIndex) {
        conf + """
                 |blockfilterindex=1
                 |peerblockfilters=1
                 |""".stripMargin
      } else {
        conf
      }

    val configTor = if (TorUtil.torEnabled) {
      config +
        """
          |[regtest]
          |proxy=127.0.0.1:9050
          |listen=1
          |bind=127.0.0.1
          |""".stripMargin
    } else {
      config
    }
    BitcoindConfig(config = configTor, datadir = FileUtil.tmpDir())
  }

  /** Creates a `bitcoind` config within the system temp directory, writes the
    * file and returns the written file
    */
  def writtenConfig(
      uri: URI,
      rpcUri: URI,
      zmqConfig: ZmqConfig,
      pruneMode: Boolean,
      blockFilterIndex: Boolean = false
  ): Path = {
    val conf = config(
      uri = uri,
      rpcUri = rpcUri,
      zmqConfig = zmqConfig,
      pruneMode = pruneMode,
      blockFilterIndex = blockFilterIndex
    )

    val datadir = conf.datadir
    val written = BitcoindConfig.writeConfigToFile(conf, datadir)
    logger.debug(s"Wrote conf to $written")
    written
  }

  def newestBitcoindBinary: File = getBinary(BitcoindVersion.newest)

  def getBinary(
      version: BitcoindVersion,
      binaryDirectory: Path = BitcoindRpcTestClient.sbtBinaryDirectory
  ): File =
    version match {
      // default to newest version
      case Unknown => getBinary(BitcoindVersion.newest, binaryDirectory)
      case known @ (V27 | V28) =>
        val fileList: List[(Path, String)] = Files
          .list(binaryDirectory)
          .iterator()
          .asScala
          .toList
          .filter(f => Files.isDirectory(f))
          .map(p => (p, p.toString.split("-").last))
        // drop leading 'v'
        val version = known.toString.drop(1)
        val exactMatchOpt = fileList.find { case (_, versionStr) =>
          // try matching the version exactly
          versionStr == version
        }

        val versionFolder: Path = exactMatchOpt match {
          case Some((p, _)) =>
            p
          case None =>
            val filtered = fileList.filter(f => f.toString.contains(version))
            if (filtered.isEmpty)
              throw new RuntimeException(
                s"bitcoind ${known.toString} is not installed in $binaryDirectory. Run `sbt downloadBitcoind`"
              )

            // might be multiple versions downloaded for
            // each major version, i.e. 0.16.2 and 0.16.3
            val versionFolder = filtered.max
            versionFolder._1
        }

        versionFolder
          .resolve("bin")
          .resolve(if (Properties.isWin) "bitcoind.exe" else "bitcoind")
          .toFile
    }

  /** Creates a `bitcoind` instance within the user temporary directory */
  def instance(
      port: Int = RpcUtil.randomPort,
      rpcPort: Int = RpcUtil.randomPort,
      zmqConfig: ZmqConfig = RpcUtil.zmqConfig,
      pruneMode: Boolean = false,
      versionOpt: Option[BitcoindVersion] = None,
      binaryDirectory: Path = BitcoindRpcTestClient.sbtBinaryDirectory,
      enableNeutrino: Boolean = true
  )(implicit system: ActorSystem): BitcoindInstanceLocal = {
    val uri = new URI("http://localhost:" + port)
    val rpcUri = new URI("http://localhost:" + rpcPort)
    val configFile =
      writtenConfig(
        uri,
        rpcUri,
        zmqConfig,
        pruneMode,
        blockFilterIndex = enableNeutrino
      )
    val conf = BitcoindConfig(configFile)
    val binary: File = versionOpt match {
      case Some(version) => getBinary(version)
      case None =>
        if (Files.exists(binaryDirectory)) {
          newestBitcoindBinary
        } else {
          throw new RuntimeException(
            "Could not locate bitcoind. Make sure it is installed on your PATH, or if working with Bitcoin-S " +
              "directly, try running 'sbt downloadBitcoind'"
          )
        }

    }

    BitcoindInstanceLocal.fromConfig(conf, binary)
  }

  def v27Instance(
      port: Int = RpcUtil.randomPort,
      rpcPort: Int = RpcUtil.randomPort,
      zmqConfig: ZmqConfig = RpcUtil.zmqConfig,
      pruneMode: Boolean = false,
      binaryDirectory: Path = BitcoindRpcTestClient.sbtBinaryDirectory
  )(implicit system: ActorSystem): BitcoindInstanceLocal =
    instance(
      port = port,
      rpcPort = rpcPort,
      zmqConfig = zmqConfig,
      pruneMode = pruneMode,
      versionOpt = Some(BitcoindVersion.V27),
      binaryDirectory = binaryDirectory
    )

  def v28Instance(
      port: Int = RpcUtil.randomPort,
      rpcPort: Int = RpcUtil.randomPort,
      zmqConfig: ZmqConfig = RpcUtil.zmqConfig,
      pruneMode: Boolean = false,
      binaryDirectory: Path = BitcoindRpcTestClient.sbtBinaryDirectory
  )(implicit system: ActorSystem): BitcoindInstanceLocal =
    instance(
      port = port,
      rpcPort = rpcPort,
      zmqConfig = zmqConfig,
      pruneMode = pruneMode,
      versionOpt = Some(BitcoindVersion.V28),
      binaryDirectory = binaryDirectory
    )

  /** Gets an instance of bitcoind with the given version */
  def getInstance(
      bitcoindVersion: BitcoindVersion,
      port: Int = RpcUtil.randomPort,
      rpcPort: Int = RpcUtil.randomPort,
      zmqConfig: ZmqConfig = RpcUtil.zmqConfig,
      pruneMode: Boolean = false,
      binaryDirectory: Path = BitcoindRpcTestClient.sbtBinaryDirectory
  )(implicit system: ActorSystem): BitcoindInstanceLocal = {
    bitcoindVersion match {
      case BitcoindVersion.V27 =>
        BitcoindRpcTestUtil.v27Instance(
          port,
          rpcPort,
          zmqConfig,
          pruneMode,
          binaryDirectory = binaryDirectory
        )
      case BitcoindVersion.V28 =>
        BitcoindRpcTestUtil.v28Instance(
          port,
          rpcPort,
          zmqConfig,
          pruneMode,
          binaryDirectory = binaryDirectory
        )
      case BitcoindVersion.Unknown =>
        sys.error(
          s"Could not create a bitcoind version with version=${BitcoindVersion.Unknown}"
        )
    }
  }

  def startServers(
      servers: Vector[BitcoindRpcClient]
  )(implicit ec: ExecutionContext): Future[Unit] = {
    val startedServersF = Future.traverse(servers) { server =>
      server.start().flatMap { res =>
        val createWalletF = for {
          _ <- res.createWallet(BitcoindRpcClient.DEFAULT_WALLET_NAME)
        } yield res

        createWalletF
      }
    }
    startedServersF.map(_ => ())
  }

  /** Stops the given servers and deletes their data directories
    */
  def stopServers(
      servers: Vector[BitcoindRpcClient]
  )(implicit system: ActorSystem): Future[Unit] = {
    implicit val ec: ExecutionContextExecutor = system.getDispatcher
    logger.info(s"Shutting down ${servers.length} bitcoinds")
    val serverStopsF = Future.traverse(servers) { s =>
      val stopF = s.stop()
      stopF.onComplete {
        case Failure(exception) =>
          logger.error(s"Could not shut down bitcoind server: $exception")
        case Success(_) =>
      }
      for {
        _ <- stopF
        _ <- awaitStopped(s)
        _ <- removeDataDirectory(s)
      } yield ()
    }
    serverStopsF.map { _ =>
      logger.info(s"Done shutting down ${servers.length} bitcoinds")
      ()
    }
  }

  /** Stops the given server and deletes its data directory
    */
  def stopServer(
      server: BitcoindRpcClient
  )(implicit system: ActorSystem): Future[Unit] = {
    stopServers(Vector(server))
  }

  /** Awaits non-blockingly until the provided clients are connected
    */
  def awaitConnection(
      from: BitcoindRpcClient,
      to: BitcoindRpcClient,
      interval: FiniteDuration = 100.milliseconds,
      maxTries: Int = 50
  )(implicit system: ActorSystem): Future[Unit] = {
    import system.dispatcher

    val isConnected: () => Future[Boolean] = () => {
      from
        .getAddedNodeInfo(to.getDaemon.uri)
        .map { info =>
          info.nonEmpty && info.head.connected.contains(true)
        }
    }

    AsyncUtil.retryUntilSatisfiedF(
      conditionF = isConnected,
      interval = interval,
      maxTries = maxTries
    )
  }

  /** Return index of output of TX `txid` with value `amount`
    *
    * @see
    *   function we're mimicking in
    *   [[https://github.com/bitcoin/bitcoin/blob/master/test/functional/test_framework/util.py#L410 Core test suite]]
    */
  def findOutput(
      client: BitcoindRpcClient,
      txid: DoubleSha256DigestBE,
      amount: Bitcoins,
      blockhash: Option[DoubleSha256DigestBE] = None
  )(implicit executionContext: ExecutionContext): Future[UInt32] = {
    client.getRawTransaction(txid, blockhash).map { tx =>
      tx.vout.zipWithIndex
        .find { case (output, _) =>
          output.value == amount
        }
        .map { case (_, i) => UInt32(i) }
        .getOrElse(
          throw new RuntimeException(
            s"Could not find output for $amount in TX ${txid.hex}"
          )
        )
    }
  }

  /** Generates the specified amount of blocks with all provided clients and
    * waits until they are synced.
    *
    * @return
    *   Vector of Blockhashes of generated blocks, with index corresponding to
    *   the list of provided clients
    */
  def generateAllAndSync(clients: Vector[BitcoindRpcClient], blocks: Int = 6)(
      implicit system: ActorSystem
  ): Future[Vector[Vector[DoubleSha256DigestBE]]] = {
    import system.dispatcher

    val sliding: Vector[Vector[BitcoindRpcClient]] =
      ListUtil.rotateHead(clients)

    val initF = Future.successful(Vector.empty[Vector[DoubleSha256DigestBE]])

    val genereratedHashesF = sliding
      .foldLeft(initF) { (accumHashesF, clients) =>
        accumHashesF.flatMap { accumHashes =>
          val hashesF = generateAndSync(clients, blocks)
          hashesF.map(hashes => hashes +: accumHashes)
        }
      }

    genereratedHashesF.map(_.reverse.toVector)
  }

  /** Generates the specified amount of blocks and waits until the provided
    * clients are synced.
    *
    * @return
    *   Blockhashes of generated blocks
    */
  def generateAndSync(clients: Vector[BitcoindRpcClient], blocks: Int = 6)(
      implicit system: ActorSystem
  ): Future[Vector[DoubleSha256DigestBE]] = {
    require(clients.length > 1, "Can't sync less than 2 nodes")

    import system.dispatcher

    for {
      address <- clients.head.getNewAddress
      hashes <- clients.head.generateToAddress(blocks, address)
      _ <- {
        val pairs = ListUtil.uniquePairs(clients)
        val syncFuts = Future.traverse(pairs) { case (first, second) =>
          awaitSynced(first, second)
        }
        syncFuts
      }
    } yield hashes
  }

  def awaitSynced(
      client1: BitcoindRpcClient,
      client2: BitcoindRpcClient,
      interval: FiniteDuration = BitcoindRpcTestUtil.DEFAULT_LONG_INTERVAL,
      maxTries: Int = 50
  )(implicit system: ActorSystem): Future[Unit] = {
    implicit val ec: ExecutionContextExecutor = system.dispatcher

    def isSynced(): Future[Boolean] = {
      client1.getBestBlockHash().flatMap { hash1 =>
        client2.getBestBlockHash().map { hash2 =>
          hash1 == hash2
        }
      }
    }

    AsyncUtil.retryUntilSatisfiedF(
      conditionF = () => isSynced(),
      interval = interval,
      maxTries = maxTries
    )
  }

  def awaitSameBlockHeight(
      client1: BitcoindRpcClient,
      client2: BitcoindRpcClient,
      interval: FiniteDuration = BitcoindRpcTestUtil.DEFAULT_LONG_INTERVAL,
      maxTries: Int = 50
  )(implicit system: ActorSystem): Future[Unit] = {
    import system.dispatcher

    def isSameBlockHeight(): Future[Boolean] = {
      client1.getBlockCount().flatMap { count1 =>
        client2.getBlockCount().map { count2 =>
          count1 == count2
        }
      }
    }

    AsyncUtil.retryUntilSatisfiedF(
      conditionF = () => isSameBlockHeight(),
      interval = interval,
      maxTries = maxTries
    )
  }

  def awaitDisconnected(
      from: BitcoindRpcClient,
      to: BitcoindRpcClient,
      interval: FiniteDuration = 100.milliseconds,
      maxTries: Int = 50
  )(implicit system: ActorSystem): Future[Unit] = {
    import system.dispatcher

    def isDisconnected(): Future[Boolean] = {
      from
        .getAddedNodeInfo(to.getDaemon.uri)
        .map(info => info.isEmpty || info.head.connected.contains(false))
        .recoverWith {
          case exception: BitcoindException
              if exception.getMessage().contains("Node has not been added") =>
            from.getPeerInfo.map(
              _.forall(_.networkInfo.addr != to.instance.uri)
            )
        }

    }

    AsyncUtil.retryUntilSatisfiedF(
      conditionF = () => isDisconnected(),
      interval = interval,
      maxTries = maxTries
    )
  }

  def awaitStopped(
      client: BitcoindRpcClient,
      interval: FiniteDuration = 100.milliseconds,
      maxTries: Int = 50
  )(implicit system: ActorSystem): Future[Unit] = {
    import system.dispatcher
    AsyncUtil.retryUntilSatisfiedF(
      conditionF = { () => client.isStoppedF },
      interval = interval,
      maxTries = maxTries
    )
  }

  def removeDataDirectory(
      client: BitcoindRpcClient,
      interval: FiniteDuration = 100.milliseconds,
      maxTries: Int = 50
  )(implicit system: ActorSystem): Future[Unit] = {
    implicit val ec = system.dispatcher
    AsyncUtil
      .retryUntilSatisfiedF(
        conditionF = { () =>
          Future {
            val dir = client.getDaemon match {
              case _: BitcoindInstanceRemote =>
                sys.error(s"Cannot have remote bitcoind instance in testkit")
              case local: BitcoindInstanceLocal => local.datadir
            }
            FileUtil.deleteTmpDir(dir)
            !dir.exists()
          }
        },
        interval = interval,
        maxTries = maxTries
      )
  }

  /** Returns a pair of unconnected
    * [[org.bitcoins.rpc.client.common.BitcoindRpcClient BitcoindRpcClient]]s
    * with no blocks
    */
  def createUnconnectedNodePair(
      clientAccum: RpcClientAccum = Vector.newBuilder
  )(implicit
      system: ActorSystem
  ): Future[(BitcoindRpcClient, BitcoindRpcClient)] = {
    implicit val ec: ExecutionContextExecutor = system.getDispatcher
    val instance1 = instance()
    val instance2 = instance()
    val client1: BitcoindRpcClient =
      BitcoindRpcClient(instance1)
    val client2: BitcoindRpcClient =
      BitcoindRpcClient(instance2)

    startServers(Vector(client1, client2)).map { _ =>
      clientAccum ++= List(client1, client2)
      (client1, client2)
    }
  }

  def syncPairs(
      pairs: Vector[(BitcoindRpcClient, BitcoindRpcClient)]
  )(implicit system: ActorSystem): Future[Unit] = {
    import system.dispatcher
    val futures = Future.traverse(pairs) { case (first, second) =>
      BitcoindRpcTestUtil.awaitSynced(first, second)
    }
    futures.map(_ => ())
  }

  /** Connects and waits non-blockingly until all the provided pairs of clients
    * are connected
    */
  def connectPairs(
      pairs: Vector[(BitcoindRpcClient, BitcoindRpcClient)]
  )(implicit system: ActorSystem): Future[Unit] = {
    import system.dispatcher
    val addNodesF: Future[Vector[Unit]] = {
      val addedF = Future.traverse(pairs) { case (first, second) =>
        first.addNode(second.getDaemon.uri, AddNodeArgument.Add)
      }
      addedF
    }

    val connectedPairsF = addNodesF.flatMap { _ =>
      val futures = Future.traverse(pairs) { case (first, second) =>
        BitcoindRpcTestUtil
          .awaitConnection(first, second, interval = 1.second)
      }
      futures
    }

    connectedPairsF.map(_ => ())
  }

  private def createNodeSequence[T <: BitcoindRpcClient](
      numNodes: Int,
      version: BitcoindVersion
  )(implicit system: ActorSystem): Future[Vector[T]] = {
    import system.dispatcher

    val clients: Vector[T] = (0 until numNodes).map { _ =>
      val rpc = version match {
        case BitcoindVersion.Unknown =>
          val instance = BitcoindRpcTestUtil.instance()
          BitcoindRpcClient(instance)
        case BitcoindVersion.V27 =>
          val instance = BitcoindRpcTestUtil.v27Instance()
          BitcoindV27RpcClient(instance)
        case BitcoindVersion.V28 =>
          val instance = BitcoindRpcTestUtil.v28Instance()
          BitcoindV28RpcClient(instance)
      }

      // this is safe as long as this method is never
      // exposed as a public method, and that all public
      // methods calling this make sure that the version
      // arg and the type arg matches up
      val rpcT = rpc.asInstanceOf[T]
      rpcT
    }.toVector

    val startF = BitcoindRpcTestUtil.startServers(clients)

    val pairsF = startF.map { _ =>
      ListUtil.uniquePairs(clients)
    }

    for {
      pairs <- pairsF
      _ <- connectPairs(pairs)
      _ <- BitcoindRpcTestUtil.generateAllAndSync(clients, blocks = 101)
    } yield clients
  }

  private def createNodePairInternal[T <: BitcoindRpcClient](
      version: BitcoindVersion
  )(implicit system: ActorSystem): Future[(T, T)] = {
    import system.dispatcher

    createNodeSequence[T](numNodes = 2, version).map {
      case first +: second +: _ => (first, second)
      case _: Vector[BitcoindRpcClient] =>
        throw new RuntimeException("Did not get two clients!")
    }
  }

  /** Returns a pair of
    * [[org.bitcoins.rpc.client.common.BitcoindRpcClient BitcoindRpcClient]]
    * that are connected with some blocks in the chain
    */
  def createNodePair[T <: BitcoindRpcClient](
      clientAccum: RpcClientAccum = Vector.newBuilder
  )(implicit
      system: ActorSystem
  ): Future[(BitcoindRpcClient, BitcoindRpcClient)] =
    createNodePair[T](BitcoindVersion.newest).map { pair =>
      clientAccum.++=(Vector(pair._1, pair._2))
      pair
    }(system.dispatcher)

  def createNodePair[T <: BitcoindRpcClient](
      version: BitcoindVersion
  )(implicit system: ActorSystem): Future[(T, T)] = {
    createNodePairInternal(version)
  }

  /** Returns a pair of
    * [[org.bitcoins.rpc.client.common.BitcoindRpcClient BitcoindRpcClient]]
    * that are not connected but have the same blocks in the chain
    */
  def createUnconnectedNodePairWithBlocks[T <: BitcoindRpcClient](
      clientAccum: RpcClientAccum = Vector.newBuilder
  )(implicit
      system: ActorSystem
  ): Future[(BitcoindRpcClient, BitcoindRpcClient)] = {
    import system.dispatcher
    for {
      (first, second) <- createNodePair(clientAccum)
      _ <- first.addNode(second.getDaemon.uri, AddNodeArgument.Remove)
      _ <- first.disconnectNode(second.getDaemon.uri)
      _ <- awaitDisconnected(first, second)
      _ <- awaitDisconnected(second, first)
    } yield {
      (first, second)
    }
  }

  def connectNodes(
      first: BitcoindRpcClient,
      second: BitcoindRpcClient): Future[Unit] = {
    first.addNode(second.getDaemon.uri, AddNodeArgument.Add)
  }

  def connectNodes[T <: BitcoindRpcClient](pair: NodePair[T]): Future[Unit] = {
    connectNodes(pair.node1, pair.node2)
  }

  def disconnectNodes(first: BitcoindRpcClient, second: BitcoindRpcClient)(
      implicit system: ActorSystem): Future[Unit] = {
    import system.dispatcher
    val disconnectF = first.disconnectNode(second.getDaemon.uri)
    for {
      _ <- disconnectF
      _ <- awaitDisconnected(first, second)
    } yield ()
  }

  def disconnectNodes[T <: BitcoindRpcClient](nodePair: NodePair[T])(implicit
      system: ActorSystem): Future[Unit] = {
    disconnectNodes(nodePair.node1, nodePair.node2)
  }

  def isConnected(first: BitcoindRpcClient, second: BitcoindRpcClient)(implicit
      ec: ExecutionContext): Future[Boolean] = {
    first.getPeerInfo.map(_.exists(_.networkInfo.addr == second.getDaemon.uri))
  }

  def isConnected[T <: BitcoindRpcClient](nodePair: NodePair[T])(implicit
      ec: ExecutionContext): Future[Boolean] = {
    isConnected(nodePair.node1, nodePair.node2)
  }

  def isNodeAdded(first: BitcoindRpcClient, second: BitcoindRpcClient)(implicit
      ec: ExecutionContext): Future[Boolean] = {
    first.getAddedNodeInfo.map(_.exists(_.addednode == second.getDaemon.uri))
  }

  def isNodeAdded[T <: BitcoindRpcClient](nodePair: NodePair[T])(implicit
      ec: ExecutionContext): Future[Boolean] = {
    isNodeAdded(nodePair.node1, nodePair.node2)
  }

  /** Returns a triple of
    * [[org.bitcoins.rpc.client.common.BitcoindRpcClient BitcoindRpcClient]]
    * that are connected with some blocks in the chain
    */
  private def createNodeTripleInternal[T <: BitcoindRpcClient](
      version: BitcoindVersion,
      clientAccum: RpcClientAccum
  )(implicit system: ActorSystem): Future[(T, T, T)] = {
    import system.dispatcher

    createNodeTripleInternal[T](version).map { nodes =>
      clientAccum.+=(nodes._1)
      clientAccum.+=(nodes._2)
      clientAccum.+=(nodes._3)
      nodes
    }
  }

  /** Returns a triple of
    * [[org.bitcoins.rpc.client.common.BitcoindRpcClient BitcoindRpcClient]]
    * that are connected with some blocks in the chain
    */
  private def createNodeTripleInternal[T <: BitcoindRpcClient](
      version: BitcoindVersion
  )(implicit system: ActorSystem): Future[(T, T, T)] = {
    import system.dispatcher

    createNodeSequence[T](numNodes = 3, version).map {
      case first +: second +: third +: _ => (first, second, third)
      case _: Vector[T] =>
        throw new RuntimeException("Did not get three clients!")
    }
  }

  /** Returns a triple of org.bitcoins.rpc.client.common.BitcoindRpcClient
    * BitcoindRpcClient that are connected with some blocks in the chain
    */
  def createNodeTriple(
      clientAccum: RpcClientAccum
  )(implicit
      system: ActorSystem
  ): Future[(BitcoindRpcClient, BitcoindRpcClient, BitcoindRpcClient)] = {
    createNodeTripleInternal(BitcoindVersion.Unknown, clientAccum)
  }

  /** Returns a triple of org.bitcoins.rpc.client.common.BitcoindRpcClient
    * BitcoindRpcClient that are connected with some blocks in the chain
    */
  def createNodeTriple[T <: BitcoindRpcClient](
      version: BitcoindVersion
  )(implicit system: ActorSystem): Future[(T, T, T)] = {
    createNodeTripleInternal(version)
  }

  def createRawCoinbaseTransaction(
      sender: BitcoindRpcClient,
      receiver: BitcoindRpcClient,
      amount: Bitcoins = Bitcoins(1)
  )(implicit executionContext: ExecutionContext): Future[Transaction] = {
    for {
      address <- sender.getNewAddress
      blocks <- sender.generateToAddress(2, address)
      block0 <- sender.getBlock(blocks(0))
      block1 <- sender.getBlock(blocks(1))
      transaction0 <- sender.getTransaction(block0.tx(0))
      transaction1 <- sender.getTransaction(block1.tx(0))
      input0 = TransactionOutPoint(
        transaction0.txid.flip,
        UInt32(transaction0.blockindex.get)
      )
      input1 = TransactionOutPoint(
        transaction1.txid.flip,
        UInt32(transaction1.blockindex.get)
      )
      sig: ScriptSignature = ScriptSignature.empty
      address <- receiver.getNewAddress
      tx <- sender.createRawTransaction(
        Vector(
          TransactionInput(input0, sig, UInt32(1)),
          TransactionInput(input1, sig, UInt32(2))
        ),
        Map(address -> amount)
      )
    } yield tx

  }

  /** Bitcoin Core 0.16 and 0.17 has diffrent APIs for signing raw transactions.
    * This method tries to construct either a
    * [[org.bitcoins.rpc.client.v16.BitcoindV16RpcClient BitcoindV16RpcClient]]
    * or a
    * [[org.bitcoins.rpc.client.v16.BitcoindV16RpcClient BitcoindV16RpcClient]]
    * from the provided `signer`, and then calls the appropriate method on the
    * result.
    *
    * @throws RuntimeException
    *   if no versioned
    *   [[org.bitcoins.rpc.client.common.BitcoindRpcClient BitcoindRpcClient]]
    *   can be constructed.
    */
  def signRawTransaction(
      signer: BitcoindRpcClient,
      transaction: Transaction,
      utxoDeps: Vector[RpcOpts.SignRawTransactionOutputParameter] = Vector.empty
  ): Future[SignRawTransactionResult] =
    signer.signRawTransactionWithWallet(transaction, utxoDeps)

  /** Gets the pubkey (if it exists) asscociated with a given bitcoin address in
    * a version-agnostic manner
    */
  def getPubkey(client: BitcoindRpcClient, address: BitcoinAddress)(implicit
      system: ActorSystem
  ): Future[Option[ECPublicKey]] = {
    import system.dispatcher

    client match {
      case other: BitcoindRpcClient =>
        other.version.flatMap { _ =>
          other.getAddressInfo(address).map(_.pubkey)
        }
    }
  }

  def sendCoinbaseTransaction(
      sender: BitcoindRpcClient,
      receiver: BitcoindRpcClient,
      amount: Bitcoins = Bitcoins(1)
  )(implicit actorSystem: ActorSystem): Future[GetTransactionResult] = {
    implicit val ec: ExecutionContextExecutor = actorSystem.dispatcher
    for {
      rawcoinbasetx <- createRawCoinbaseTransaction(sender, receiver, amount)
      signedtx <- signRawTransaction(sender, rawcoinbasetx)
      addr <- sender.getNewAddress
      _ <- sender.generateToAddress(100, addr)
      // Can't spend coinbase until depth 100
      transactionHash <- sender.sendRawTransaction(signedtx.hex, maxfeerate = 0)
      transaction <- sender.getTransaction(transactionHash)
    } yield transaction
  }

  /** @return
    *   The first block (after genesis) in the given node's blockchain
    */
  def getFirstBlock(node: BitcoindRpcClient)(implicit
      executionContext: ExecutionContext
  ): Future[GetBlockWithTransactionsResult] = {
    node
      .getBlockHash(1)
      .flatMap(node.getBlockWithTransactions)
  }

  /** Mines blocks until the specified block height. */
  def waitUntilBlock(
      blockHeight: Int,
      client: BitcoindRpcClient,
      addressForMining: BitcoinAddress
  )(implicit ec: ExecutionContext): Future[Unit] = {
    for {
      currentCount <- client.getBlockCount()
      blocksToMine = blockHeight - currentCount
      _ <- client.generateToAddress(blocks = blocksToMine, addressForMining)
    } yield ()
  }

  /** Produces a confirmed transaction from `sender` to `address` for `amount`
    */
  def fundBlockChainTransaction(
      sender: BitcoindRpcClient,
      receiver: BitcoindRpcClient,
      address: BitcoinAddress,
      amount: Bitcoins
  )(implicit system: ActorSystem): Future[DoubleSha256DigestBE] = {

    implicit val ec: ExecutionContextExecutor = system.dispatcher

    for {
      txid <- fundMemPoolTransaction(sender, address, amount)
      addr <- sender.getNewAddress
      blockHash <- sender.generateToAddress(1, addr).map(_.head)
      seenBlock <- hasSeenBlock(receiver, blockHash)
      _ <-
        if (seenBlock) {
          Future.unit
        } else {
          sender
            .getBlockRaw(blockHash)
            .flatMap(receiver.submitBlock)
        }
      _ <- AsyncUtil.retryUntilSatisfiedF(() =>
        sender.getTransaction(txid).map(_.confirmations == 1))
    } yield {
      txid
    }
  }

  /** Produces a unconfirmed transaction from `sender` to `address` for `amount`
    */
  def fundMemPoolTransaction(
      sender: BitcoindRpcClient,
      address: BitcoinAddress,
      amount: Bitcoins
  )(implicit system: ActorSystem): Future[DoubleSha256DigestBE] = {
    import system.dispatcher
    sender
      .createRawTransaction(Vector.empty, Map(address -> amount))
      .flatMap(sender.fundRawTransaction)
      .flatMap { fundedTx =>
        signRawTransaction(sender, fundedTx.hex).flatMap { signedTx =>
          sender.sendRawTransaction(signedTx.hex)
        }
      }
  }

  /** Stops the provided nodes and deletes their data directories
    */
  def deleteNodePair(client1: BitcoindRpcClient, client2: BitcoindRpcClient)(
      implicit executionContext: ExecutionContext
  ): Future[Unit] = {
    val stopsF = Future.traverse(List(client1, client2)) { client =>
      implicit val sys = client.system
      for {
        _ <- client.stop()
        _ <- awaitStopped(client)
        _ <- removeDataDirectory(client)
      } yield ()
    }
    stopsF.map(_ => ())
  }

  /** Checks whether the provided client has seen the given block hash
    */
  def hasSeenBlock(client: BitcoindRpcClient, hash: DoubleSha256DigestBE)(
      implicit ec: ExecutionContext
  ): Future[Boolean] = {
    val p = Promise[Boolean]()

    client.getBlock(hash.flip).onComplete {
      case Success(_) => p.success(true)
      case Failure(_) => p.success(false)
    }

    p.future
  }

  def hasSeenBlock(client1: BitcoindRpcClient, hash: DoubleSha256Digest)(
      implicit ec: ExecutionContext
  ): Future[Boolean] = {
    hasSeenBlock(client1, hash.flip)
  }

  /** @param clientAccum
    *   If provided, the generated client is added to this vectorbuilder.
    */
  def startedBitcoindRpcClient(
      instanceOpt: Option[BitcoindInstanceLocal] = None,
      clientAccum: RpcClientAccum
  )(implicit system: ActorSystem): Future[BitcoindRpcClient] = {
    implicit val ec: ExecutionContextExecutor = system.dispatcher

    val instance = instanceOpt.getOrElse(BitcoindRpcTestUtil.instance())

    require(
      instance.datadir.getPath.startsWith(Properties.tmpDir),
      s"${instance.datadir} is not in user temp dir! This could lead to bad things happening."
    )

    // start the bitcoind instance so eclair can properly use it
    val rpc = BitcoindRpcClient(instance)
    val startedF = startServers(Vector(rpc))

    val blocksToGenerate = 102
    // fund the wallet by generating 102 blocks, need this to get over coinbase maturity
    val generatedF = startedF.flatMap { _ =>
      clientAccum += rpc
      rpc.generate(blocksToGenerate)
    }

    def areBlocksGenerated(): Future[Boolean] = {
      rpc.getBlockCount().map { count =>
        count >= blocksToGenerate
      }
    }

    val blocksGeneratedF = generatedF.flatMap { _ =>
      AsyncUtil.retryUntilSatisfiedF(
        () => areBlocksGenerated(),
        interval = BitcoindRpcTestUtil.DEFAULT_LONG_INTERVAL
      )
    }

    val result = blocksGeneratedF.map(_ => rpc)

    result
  }
}

object BitcoindRpcTestUtil extends BitcoindRpcTestUtil {

  /** Used for long running async tasks
    */
  val DEFAULT_LONG_INTERVAL = {
    if (EnvUtil.isMac && EnvUtil.isCI) 10.seconds
    else 3.seconds
  }
}
