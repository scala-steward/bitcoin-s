package org.bitcoins.lnd.rpc.internal

import lnrpc.Failure.FailureCode.INCORRECT_OR_UNKNOWN_PAYMENT_DETAILS
import lnrpc.{
  HTLCAttempt,
  HopHint,
  MPPRecord,
  QueryRoutesRequest,
  QueryRoutesResponse,
  Route,
  RouteHint
}
import org.bitcoins.core.currency._
import org.bitcoins.core.number._
import org.bitcoins.core.protocol.ln.{LnInvoice, PaymentSecret}
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.ln.routing.LnRoute
import org.bitcoins.core.util.FutureUtil
import org.bitcoins.crypto._
import org.bitcoins.lnd.rpc.LndRpcClient
import routerrpc._

import scala.concurrent.Future

trait LndRouterClient { self: LndRpcClient =>

  def queryRoutes(
      amount: CurrencyUnit,
      node: NodeId,
      routeHints: Vector[LnRoute]
  ): Future[QueryRoutesResponse] = {
    val hopHints = routeHints.map { hint =>
      HopHint(
        hint.pubkey.hex,
        hint.shortChannelID.u64,
        UInt32(hint.feeBaseMsat.msat.toLong),
        hint.feePropMilli.u32,
        UInt32(hint.cltvExpiryDelta)
      )
    }
    val request =
      QueryRoutesRequest(
        pubKey = node.pubKey.hex,
        amt = amount.satoshis.toLong,
        finalCltvDelta = 80,
        useMissionControl = true,
        routeHints = Vector(RouteHint(hopHints))
      )

    queryRoutes(request)
  }

  def queryRoutes(request: QueryRoutesRequest): Future[QueryRoutesResponse] = {
    logger.trace("lnd calling queryroutes")

    lnd.queryRoutes(request)
  }

  def probe(invoice: LnInvoice): Future[Vector[Route]] = {
    val amount = invoice.amount.map(_.toSatoshis).getOrElse(Satoshis.zero)
    val hints = invoice.lnTags.routingInfo.map(_.routes).getOrElse(Vector.empty)
    probe(amount, invoice.nodeId, hints)
  }

  def sendToRoute(hash: Sha256Digest, route: Route): Future[HTLCAttempt] = {
    val request = SendToRouteRequest(paymentHash = hash.bytes, Some(route))

    sendToRoute(request)
  }

  def sendToRoute(request: SendToRouteRequest): Future[HTLCAttempt] = {
    logger.trace("lnd calling sendtoroute")

    router.sendToRouteV2(request)
  }

  def probe(
      amount: Satoshis,
      node: NodeId,
      routeHints: Vector[LnRoute]
  ): Future[Vector[Route]] = {
    queryRoutes(amount, node, routeHints).map(_.routes).flatMap { routes =>
      val fs = Future.traverse(routes.toVector) { route =>
        val fakeHash = CryptoUtil.sha256(ECPrivateKey.freshPrivateKey.bytes)
        sendToRoute(fakeHash, route).map(t => (route, t))
      }

      fs.map { results =>
        results
          .filter(
            _._2.failure.exists(_.code == INCORRECT_OR_UNKNOWN_PAYMENT_DETAILS)
          )
          .map(_._1)
      }
    }
  }

  def sendToRoute(invoice: LnInvoice, route: Route): Future[HTLCAttempt] = {
    sendToRoute(
      invoice.lnTags.paymentHash.hash,
      route,
      invoice.lnTags.secret.map(_.secret)
    )
  }

  def sendToRoute(
      paymentHash: Sha256Digest,
      route: Route,
      secretOpt: Option[PaymentSecret]
  ): Future[HTLCAttempt] = {
    val updatedRoute = secretOpt match {
      case Some(secret) =>
        val last = route.hops.last
        val mpp = MPPRecord(
          paymentAddr = secret.bytes,
          totalAmtMsat = route.totalAmtMsat
        )
        val update = last.copy(mppRecord = Some(mpp), tlvPayload = true)
        val updatedHops = route.hops.init :+ update

        route.copy(hops = updatedHops)
      case None => route
    }

    val request =
      SendToRouteRequest(
        paymentHash = paymentHash.bytes,
        route = Some(updatedRoute)
      )

    sendToRoute(request)
  }

  def probeAndPay(invoice: LnInvoice): Future[Option[HTLCAttempt]] = {
    probe(invoice).flatMap { routes =>
      val sorted = routes.sortBy(_.totalFeesMsat)
      attemptToPayRoutes(invoice, sorted)
    }
  }

  def attemptToPayRoutes(
      invoice: LnInvoice,
      routes: Vector[Route]
  ): Future[Option[HTLCAttempt]] = {
    val init: Option[HTLCAttempt] = None
    FutureUtil.foldLeftAsync(init, routes) { case (ret, route) =>
      ret match {
        case Some(value) =>
          value.failure match {
            case Some(_) => sendToRoute(invoice, route).map(Some(_))
            case None    => Future.successful(Some(value))
          }
        case None => sendToRoute(invoice, route).map(Some(_))
      }
    }
  }
}
