package spray.contrib.socketio

import akka.actor.{ Actor, ActorLogging, ActorRef, PoisonPill }
import akka.io.Tcp
import java.util.UUID
import scala.concurrent.Future
import scala.concurrent.duration._
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.contrib.socketio
import spray.http.HttpRequest
import spray.contrib.socketio.transport.{ WebSocket, XhrPolling, Transport }

/**
 *
 *             +--------------------------------------+
 *             |     +---ConnectionSession(actor)      |
 *             |     |                                | -----------  virtual connection (identified by sessionId etc.)
 *             |     +---ConnectionContext            |
 *             +--------------------------------------+
 *                                |1
 *                                |
 *                                |1..n
 *                   +--------------------------+
 *                   |   transportConnection    |
 *                   +--------------------------+
 *                                |1
 *                                |
 *                    websocket   |   xhr-polling
 *                      +---------+---------+
 *                      |                   |
 *                      |                   |
 *                      |1                  |1..n
 *               Ws Connection       Http Connections
 *
 * ================================================================
 * Check opened tcp connections:
 *
 * For open (established) tcp connections:
 * # netstat -tn
 *
 * To additionally get the associated PID for each connection:
 * # netstat -tnp
 *
 * Check histogram of java object heap; if the "live" suboption is
 * specified, only count live objects (will also force GC):
 * # jmap -histo:live <pid>
 *
 * Force GC (JDK 7+):
 * jcmd <pid> GC.run
 * ================================================================
 */
trait SocketIOServerWorker extends Actor with ActorLogging {
  import context.dispatcher

  def serverConnection: ActorRef
  def sessionRegion: ActorRef
  def sessionIdGenerator: HttpRequest => Future[String] = { req => Future(UUID.randomUUID.toString) } // default one

  var isConnectionSessionClosed = false

  implicit lazy val soConnContext = new socketio.SoConnectingContext(null, sessionIdGenerator, serverConnection, self, sessionRegion, log, context.dispatcher)

  var socketioTransport: Option[Transport] = None

  var isSocketioHandshaked = false

  def readyBehavior = handleSocketioHandshake orElse handleWebsocketConnecting orElse handleXrhpolling orElse genericLogic orElse handleTerminated

  def upgradedBehavior = handleWebsocket orElse handleTerminated

  def preReceive(msg: Any) {}

  def postReceive(msg: Any) {}

  def ready: Receive = {
    case msg =>
      preReceive(msg)
      readyBehavior.applyOrElse(msg, unhandled)
      postReceive(msg)
  }

  def upgraded: Receive = {
    case msg =>
      preReceive(msg)
      upgradedBehavior.applyOrElse(msg, unhandled)
      postReceive(msg)
  }

  def receive: Receive = ready

  def handleSocketioHandshake: Receive = {
    case socketio.HandshakeRequest(ok) =>
      isSocketioHandshaked = true
      log.debug("socketio connection of {} handshaked.", serverConnection.path)
  }

  def handleWebsocketConnecting: Receive = {
    case req @ websocket.HandshakeRequest(state) =>
      state match {
        case wsFailure: websocket.HandshakeFailure =>
          sender() ! wsFailure.response
        case wsContext: websocket.HandshakeContext =>
          sender() ! UHttp.UpgradeServer(websocket.pipelineStage(self, wsContext), wsContext.response)
          socketio.wsConnecting(wsContext.request) foreach { _ =>
            socketioTransport = Some(WebSocket)
            log.debug("socketio connection of {} connected on websocket.", serverConnection.path)
          }
      }

    case UHttp.Upgraded =>
      context.become(upgraded)
      log.info("HTTP connection of {} upgraded to websocket, sessionId: {}.", serverConnection.path, soConnContext.sessionId)
  }

  def handleWebsocket: Receive = {
    case frame @ socketio.WsFrame(ok) =>
      log.debug("Got {}", frame)
  }

  def handleXrhpolling: Receive = {
    case req @ socketio.HttpGet(ok) =>
      socketioTransport = Some(XhrPolling)
      log.debug("socketio connection of {} GET {}", serverConnection.path, req.entity)

    case req @ socketio.HttpPost(ok) =>
      socketioTransport = Some(XhrPolling)
      log.debug("socketio connection of {} POST {}", serverConnection.path, req.entity)
  }

  def handleTerminated: Receive = {
    case x: Http.ConnectionClosed =>
      closeConnectionSession()
      self ! PoisonPill
      log.debug("HTTP connection of {} stopped due to {}.", serverConnection.path, x)
    case Tcp.Closed => // may be triggered by the first socketio handshake http connection, which will always be droped.
      closeConnectionSession()
      self ! PoisonPill
      log.debug("HTTP connection of {} stopped due to Tcp.Closed}.", serverConnection.path)
  }

  def genericLogic: Receive

  override def postStop() {
    log.debug("postStop")
    closeConnectionSession()
  }

  def closeConnectionSession() {
    log.debug("ask to close connectionSession")
    if (soConnContext.sessionId != null && !isConnectionSessionClosed) {
      isConnectionSessionClosed = true
      sessionRegion ! ConnectionSession.Closing(soConnContext.sessionId, soConnContext.serverConnection)
    }
  }

}
