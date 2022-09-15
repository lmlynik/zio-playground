package pl.mlynik

import zio.*
import zio.stream.*
import zio.concurrent.ConcurrentMap

import java.io.IOException

object Server {

  type ConnectionStream[R] = ZStream[R, Nothing, Packet]

  case class Socket[R](incoming: ConnectionStream[R])

  trait Packet

  trait Implementation {
    def run(packet: Packet): UIO[Unit]
  }

  enum Error {
    case AlreadyConnected extends Error
  }

  class LiveServer[R](
    val name: String,
    impl: Implementation,
    sockets: ConcurrentMap[String, (Socket[R], Fiber.Runtime[Any, Unit])],
    queue: Queue[Packet],
    semaphore: Semaphore
  ) {

    val processingStream: ConnectionStream[R] = ZStream.fromQueue(queue)
    def start: URIO[R, Fiber.Runtime[IOException, Unit]] =
      processingStream.runForeach { packet =>
        Console.printLine(s"Running $packet on $name").orDie *>  semaphore.withPermit(impl.run(packet))
      }.fork

    def connect(name: String, socket: Socket[R]): ZIO[R, Error, Fiber.Runtime[Nothing, Unit]] =
      sockets
        .get(name)
        .flatMap {
          case Some(_) => ZIO.fail(Error.AlreadyConnected)
          case None    =>
            socket.incoming
              .run(ZSink.fromQueue(queue))
              .fork.tap(fork => sockets.put(name, socket -> fork))
        }

    def disconnect(name: String): ZIO[Any, Nothing, Unit] =
      sockets
        .remove(name)
        .flatMap(socket =>
          socket match
            case None        => ZIO.unit
            case Some(value) => value._2.interrupt.unit
        )
  }

  def make[R](name: String, impl: Implementation) = for {
    ref       <- ConcurrentMap.make[String, (Socket[R], Fiber.Runtime[Any, Unit])]()
    queue     <- Queue.bounded[Packet](1024)
    semaphore <- Semaphore.make(1)
  } yield new LiveServer(name, impl, ref, queue, semaphore)
}
