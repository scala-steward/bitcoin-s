package org.bitcoins.server

import org.bitcoins.asyncutil.AsyncUtil
import org.bitcoins.core.currency.CurrencyUnits
import org.bitcoins.server.util.WalletHolderWithBitcoindLoaderApi
import org.bitcoins.testkit.server.WalletLoaderFixtures
import org.bitcoins.wallet.models.WalletStateDescriptorDAO
import org.scalatest.FutureOutcome

import scala.concurrent.duration.DurationInt

class DLCWalletBitcoindBackendLoaderTest extends WalletLoaderFixtures {

  override type FixtureParam = WalletHolderWithBitcoindLoaderApi

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    withBitcoindBackendLoader(test)
  }

  behavior of "DLCWalletBitcoindBackendLoader"

  it must "load a wallet" in { walletHolderWithLoader =>
    val loader = walletHolderWithLoader.loaderApi
    assert(!loader.isWalletLoaded)

    val loadedWalletF = loader.load(
      walletNameOpt = None,
      aesPasswordOpt = None
    )
    loadedWalletF.map { _ =>
      assert(loader.isWalletLoaded)
    }
  }

  it must "track rescan state accurately" in { walletHolderWithLoader =>
    val loader = walletHolderWithLoader.loaderApi
    val bitcoind = walletHolderWithLoader.bitcoind
    // need some blocks to make rescans last longer for the test case
    val blocksF = bitcoind.generate(250)

    val loadedWalletF = loader.load(walletNameOpt = None, aesPasswordOpt = None)

    val walletConfigF = loadedWalletF.map(_._2)
    val walletF = loadedWalletF.map(_._1)

    // as a hack, set rescanning to true, so next time we load it starts a rescan
    val setRescanF = for {
      _ <- blocksF
      walletConfig <- walletConfigF
      wallet <- walletF
      address <- wallet.getNewAddress()
      // send some funds to get discovered during the rescan
      _ <- bitcoind.generateToAddress(1, address)
      balance <- wallet.getBalance()
      _ = assert(balance == CurrencyUnits.zero)
      descriptorDAO = WalletStateDescriptorDAO()(
        system.dispatcher,
        walletConfig
      )
      set <- descriptorDAO.compareAndSetRescanning(false, true)
    } yield assert(set)

    // now that we have set rescanning, we should see a rescan next time we load wallet
    for {
      _ <- setRescanF
      (loadWallet2, _, _) <- loader.load(
        walletNameOpt = None,
        aesPasswordOpt = None
      ) // load wallet again
      balanceBeforeRescan <- loadWallet2.getBalance()
      _ = assert(balanceBeforeRescan == CurrencyUnits.zero)
      isRescanning <- loadWallet2.isRescanning()
      _ = assert(isRescanning)
      _ = assert(loader.isRescanStateDefined)
      // wait until rescanning is done
      _ <- AsyncUtil.retryUntilSatisfiedF(
        { () =>
          loadWallet2.isRescanning().map(isRescanning => isRescanning == false)
        },
        1.second
      )
      balanceAfterRescan <- loadWallet2.getBalance()
    } yield {
      assert(loader.isRescanStateEmpty)
      assert(balanceAfterRescan != CurrencyUnits.zero)
    }
  }
}
