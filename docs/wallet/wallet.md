---
title: Wallet
id: wallet
---

## Bitcoin-s wallet
Bitcoin-s comes bundled with a rudimentary Bitcoin wallet. This wallet
is capable of managing private keys, generating addresses, constructing
and signing transactions, among other things. It is BIP32/BIP44/BIP49/BIP84
compatible.

This wallet is currently only released as a library, and not as a binary.
This is because it (nor the documentation) is not deemed production
ready. Use at your own risk, and without too much money depending on it.

### How is the bitcoin-s wallet implemented

The bitcoin-s wallet is a scalable way for individuals up to large bitcoin exchanges to safely and securely store their bitcoin in a scalable way.

All key interactions are delegated to the [key-manager](../key-manager/key-manager.md) which is a minimal dependency library to store and use key material.

By default, we store the encrypted root key in `$HOME/.bitcoin-s/seeds/encrypted-bitcoin-s-seed.json`. This is the seed that is used for each of the wallets on each bitcoin network.
Multiple wallet seeds can be saved using the `bitcoin-s.wallet.walletName` config option.
You can read more in the [key manager docs](../key-manager/server-key-manager.md).

The wallet itself is used to manage the utxo life cycle, create transactions, and update wallet balances to show how much money you have the on a bitcoin network.

We use [slick](https://scala-slick.org/doc/3.3.1/) as middleware to support different database types. Depending on your use case, you can use something as simple as sqlite, or something much more scalable like postgres.


### Example

This guide shows how to create a Bitcoin-s wallet and then
peer it with a `bitcoind` instance that relays
information about what is happening on the blockchain
through the P2P network.

This is useful if you want more flexible signing procedures in
the JVM ecosystem and more granular control over your
UTXOs with popular database like Postgres, SQLite, etc.

This code snippet you have a running `bitcoind` instance, locally
on regtest.

```scala mdoc:invisible

import org.bitcoins.chain.blockchain.ChainHandler
import org.bitcoins.chain.blockchain.sync.ChainSync
import org.bitcoins.chain.config.ChainAppConfig
import org.bitcoins.core.api.chain.ChainApi
import org.bitcoins.chain.models._

import org.bitcoins.core.api._
import chain._
import chain.ChainQueryApi.FilterResponse
import node._
import org.bitcoins.crypto._
import org.bitcoins.core.protocol._
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.currency._
import org.bitcoins.core.wallet.fee._
import org.bitcoins.feeprovider._
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.bitcoins.rpc.config._
import org.bitcoins.wallet.config.WalletAppConfig
import org.bitcoins.core.api.wallet.WalletApi
import org.bitcoins.core.util.BlockHashWithConfs
import org.bitcoins.wallet.Wallet
import org.bitcoins.wallet.util.WalletUtil

import com.typesafe.config.ConfigFactory
import java.nio.file.Files
import scala.concurrent._
import org.apache.pekko.actor.ActorSystem

val chainApi = new ChainQueryApi {
    override def epochSecondToBlockHeight(time: Long): Future[Int] = Future.successful(0)
    override def getBlockHeight(blockHash: DoubleSha256DigestBE): Future[Option[Int]] = Future.successful(None)
    override def getBestBlockHash(): Future[DoubleSha256DigestBE] = Future.successful(DoubleSha256DigestBE.empty)
    override def getNumberOfConfirmations(blockHashOpt: DoubleSha256DigestBE): Future[Option[Int]] = Future.successful(None)
    override def getFilterCount(): Future[Int] = Future.successful(0)
    override def getHeightByBlockStamp(blockStamp: BlockStamp): Future[Int] = Future.successful(0)
    override def getFiltersBetweenHeights(startHeight: Int, endHeight: Int): Future[Vector[FilterResponse]] = Future.successful(Vector.empty)
    override def getMedianTimePast(): Future[Long] = Future.successful(0L)
  }
```

```scala mdoc:compile-only
implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global
implicit val system: ActorSystem = ActorSystem("System")

val config = ConfigFactory.parseString {
    """
    | bitcoin-s {
    |   network = regtest
    | }
    """.stripMargin
}


val datadir = Files.createTempDirectory("bitcoin-s-wallet")


implicit val walletConfig: WalletAppConfig = WalletAppConfig(datadir, Vector(config))

// we also need to store chain state for syncing purposes
implicit val chainConfig: ChainAppConfig = ChainAppConfig(datadir, Vector(config))

// when this future completes, we have
// created the necessary directories and
// databases for managing both chain state
// and wallet state
val configF: Future[Unit] = for {
    _ <- walletConfig.start()
    _ <- chainConfig.start()
} yield ()

val bitcoindInstance = BitcoindInstanceLocal.fromDatadir()

val bitcoind = BitcoindRpcClient(bitcoindInstance)

// when this future completes, we have
// synced our chain handler to our bitcoind
// peer
val syncF: Future[ChainApi] = configF.flatMap { _ =>
    val getBestBlockHashFunc = { () =>
        bitcoind.getBestBlockHash()
    }

    
    val getBlockHeaderFunc = { (hash: DoubleSha256DigestBE) =>
        bitcoind.getBlockHeader(hash).map(_.blockHeader)
    }

    val blockHeaderDAO = BlockHeaderDAO()
    val compactFilterHeaderDAO = CompactFilterHeaderDAO()
    val compactFilterDAO = CompactFilterDAO()
    val stateDAO = ChainStateDescriptorDAO()
    val chainHandler = ChainHandler(
        blockHeaderDAO,
        compactFilterHeaderDAO,
        compactFilterDAO,
        stateDAO,
        blockFilterCheckpoints = Map.empty)

    ChainSync.sync(chainHandler, getBlockHeaderFunc, getBestBlockHashFunc)
}

// once this future completes, we have a initialized
// wallet
val wallet = Wallet(new NodeApi {
    override def broadcastTransactions(txs: Vector[Transaction]): Future[Unit] = Future.successful(())
    override def downloadBlocks(blockHashes: Vector[DoubleSha256DigestBE]): Future[Unit] = Future.successful(())
    override def getConnectionCount: Future[Int] = Future.successful(0)
  }, chainApi)
val walletF: Future[WalletApi] = configF.flatMap { _ =>
  Wallet.initialize(wallet, wallet.accountHandling, None)
}

// when this future completes, ww have sent a transaction
// from bitcoind to the Bitcoin-S wallet
val transactionF: Future[(Transaction, Option[BlockHashWithConfs])] = for {
    wallet <- walletF
    address <- wallet.getNewAddress()
    txid <- bitcoind.sendToAddress(address, 3.bitcoin)
    transaction <- bitcoind.getRawTransaction(txid)
    blockHashWithConfs <- WalletUtil.getBlockHashWithConfs(bitcoind,transaction.blockhash)
} yield (transaction.hex, blockHashWithConfs)

// when this future completes, we have processed
// the transaction from bitcoind, and we have
// queried our balance for the current balance
val balanceF: Future[CurrencyUnit] = for {
    wallet <- walletF
    (tx, blockhash) <- transactionF
    _ <- wallet.transactionProcessing.processTransaction(tx, blockhash)
    balance <- wallet.getBalance()
} yield balance

balanceF.foreach { balance =>
    println(s"Bitcoin-S wallet balance: $balance")
}
```
