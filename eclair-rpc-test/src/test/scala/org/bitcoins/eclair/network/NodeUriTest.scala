package org.bitcoins.eclair.network

import org.bitcoins.core.protocol.ln.node.NodeUri
import org.bitcoins.testkit.util.BitcoinSAsyncTest

class NodeUriTest extends BitcoinSAsyncTest {

  behavior of "NodeUri"

  it must "read the suredbits node uri" in {
    val sb =
      "0338f57e4e20abf4d5c86b71b59e995ce4378e373b021a7b6f41dabb42d3aad069@ln.test.suredbits.com:9735"
    val uriT = NodeUri.fromStringT(sb)
    assert(uriT.isSuccess == true)

    assert(uriT.get.toString == sb)
  }

  it must "read the suredbits node uri without the port" in {
    val sb =
      "0338f57e4e20abf4d5c86b71b59e995ce4378e373b021a7b6f41dabb42d3aad069@ln.test.suredbits.com"
    val uriT = NodeUri.fromStringNoPort(sb)
    assert(uriT.isSuccess == true)

    assert(uriT.get.toString == (sb + ":9735"))
  }

  it must "read a node uri with a ip address" in {
    val sb =
      "0338f57e4e20abf4d5c86b71b59e995ce4378e373b021a7b6f41dabb42d3aad069@127.0.0.1:9735"

    val uriT = NodeUri.fromStringT(sb)

    assert(uriT.isSuccess)

    assert(uriT.get.toString == sb)
  }

  it must "fail to read a node uri without a nodeId" in {
    val sb = "@ln.test.suredbits.com"
    val uriT = NodeUri.fromStringT(sb)
    assert(uriT.isFailure == true)
  }

  it must "fail to read a node uri with a invalid port" in {
    val sb =
      "0338f57e4e20abf4d5c86b71b59e995ce4378e373b021a7b6f41dabb42d3aad069@ln.test.suredbits.com:abc2"
    val uriT = NodeUri.fromStringT(sb)
    assert(uriT.isFailure == true)
  }
}
