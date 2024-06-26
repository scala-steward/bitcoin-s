package org.bitcoins.rpc.client.common

import org.bitcoins.commons.jsonmodels.bitcoind.{
  GetMemoryInfoResult,
  GetNodeAddressesResultPostV22
}
import org.bitcoins.core.number.UInt32
import org.bitcoins.commons.serializers.JsonReaders
import org.bitcoins.commons.serializers.JsonSerializers._
import play.api.libs.json._

import scala.concurrent.Future

/** RPC calls related to administration of a given node
  */
trait NodeRpc { self: Client =>

  def abortRescan(): Future[Unit] = {
    bitcoindCall[Unit]("abortrescan")
  }

  private def logging(
      include: Option[Vector[String]],
      exclude: Option[Vector[String]]
  ): Future[Map[String, Boolean]] = {
    val params = List(
      Json.toJson(include.getOrElse(Vector.empty)),
      Json.toJson(exclude.getOrElse(Vector.empty))
    )

    /** Bitcoin Core v0.16 returns a map of 1/0s, v0.17 returns proper booleans
      */
    object IntOrBoolReads extends Reads[Boolean] {
      override def reads(json: JsValue): JsResult[Boolean] =
        json
          .validate[Boolean]
          .orElse(json.validate[Int].flatMap {
            case 0          => JsSuccess(false)
            case 1          => JsSuccess(true)
            case other: Int => JsError(s"$other is not a boolean, 1 or 0")
          })

    }

    object LoggingReads extends Reads[Map[String, Boolean]] {
      override def reads(json: JsValue): JsResult[Map[String, Boolean]] =
        JsonReaders.mapReads(json)(implicitly[Reads[String]], IntOrBoolReads)
    }

    // if we're just compiling for Scala 2.12 we could have converted the 20 lines
    // above into a one-liner, but Play Json for 2.11 isn't quite clever enough
    bitcoindCall[Map[String, Boolean]]("logging", params)(LoggingReads)

  }
  def logging: Future[Map[String, Boolean]] = logging(None, None)

  def logging(
      include: Vector[String] = Vector.empty,
      exclude: Vector[String] = Vector.empty
  ): Future[Map[String, Boolean]] = {
    val inc = if (include.nonEmpty) Some(include) else None
    val exc = if (exclude.nonEmpty) Some(exclude) else None
    logging(inc, exc)
  }

  def uptime: Future[UInt32] = {
    bitcoindCall[UInt32]("uptime")
  }

  def getMemoryInfo: Future[GetMemoryInfoResult] = {
    bitcoindCall[GetMemoryInfoResult]("getmemoryinfo")
  }

  def help(rpcName: String = ""): Future[String] = {
    bitcoindCall[String]("help", List(JsString(rpcName)))
  }

  private def getNodeAddresses(
      count: Option[Int]
  ): Future[Vector[GetNodeAddressesResultPostV22]] = {
    bitcoindCall[Vector[GetNodeAddressesResultPostV22]](
      "getnodeaddresses",
      List(Json.toJson(count))
    )
  }

  def getNodeAddresses(
      network: String,
      count: Int
  ): Future[Vector[GetNodeAddressesResultPostV22]] = {
    bitcoindCall[Vector[GetNodeAddressesResultPostV22]](
      "getnodeaddresses",
      List(Json.toJson(count), Json.toJson(network))
    )
  }

  def getNodeAddresses(
      count: Int
  ): Future[Vector[GetNodeAddressesResultPostV22]] =
    getNodeAddresses(Some(count))

  def getNodeAddresses(): Future[Vector[GetNodeAddressesResultPostV22]] =
    getNodeAddresses(None)
}
