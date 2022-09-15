package pl.mlynik

import pl.mlynik.Server.{ Implementation, Packet, Socket }
import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.model.Uri
import zio.stream.ZStream
import zio.*
import zio.Console

object Main extends ZIOAppDefault:

  case class DummyPacket(msg: String = "wow") extends Packet
  val app                                                           = for {
    node1 <- Server.make(
               "node1",
               new Implementation {
                 override def run(packet: Server.Packet): UIO[Unit] = Console.printLine(s"wow $packet").orDie
               }
             )
    _     <- node1.start.debug("node1 start")
    _     <- node1.connect("stream", Socket(ZStream.repeat(DummyPacket()))).debug("connect stream")
    _     <- ZIO.sleep(15.seconds).debug("disconnecting")
    _ <- node1.disconnect("stream")
  } yield ()
  override def run: ZIO[Environment & ZIOAppArgs & Scope, Any, Any] =
    app *> ZIO.never
