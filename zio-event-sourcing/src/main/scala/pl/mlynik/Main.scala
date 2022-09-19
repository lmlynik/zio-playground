package pl.mlynik

import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.model.Uri
import zio.stream.ZStream
import zio.*
import zio.Console

import java.io.IOException

class InMemoryJournal[EVENT](storage: Ref[List[(Int, EVENT)]]) extends Journal[EVENT] {

  case class LoadIOException(io: Throwable) extends Journal.LoadError
  def persist(id: String, event: EVENT): IO[Journal.PersistError, Unit] =
    storage.update { mem =>
      val newOffset = if (mem.isEmpty) 0 else mem.last._1 + 1
      mem :+ (newOffset -> event)
    }

  def load(id: String, loadFrom: Int): ZStream[Any, Journal.LoadError, (Int, EVENT)] =
    ZStream.unwrap(storage.get.map { mem =>
      ZStream.fromIterator(mem.iterator).filter(_._1 >= loadFrom).mapError(t => LoadIOException(t))
    })
}

object InMemoryJournal {
  def live[EVENT: Tag]: ZLayer[Any, Nothing, InMemoryJournal[EVENT]] = ZLayer.fromZIO(for {
    ref    <- Ref.make[List[(Int, EVENT)]](Nil)
    journal = new InMemoryJournal(ref)
  } yield journal)
}

object MyPersistentBehavior {

  import EventSourcedEntity.*
  enum Command {
    case NextNumber(value: Long)
    case Clear
    case Get(promise: Promise[Nothing, List[Long]])
  }

  enum Event {
    case NextNumberAdded(value: Long)
    case Cleared
  }

  final case class State(numbers: List[Long] = Nil)

  def apply(id: String): ZIO[Journal[Event], Journal.LoadError, EntityRef[Command, State]] =
    EventSourcedEntity[Command, Event, State](
      persistenceId = id,
      emptyState = State(),
      commandHandler = (state, cmd) =>
        cmd match
          case Command.NextNumber(value) => Effect.persist(Event.NextNumberAdded(value))
          case Command.Clear             => Effect.persist(Event.Cleared)
          case Command.Get(promise)      =>
            promise.succeed(state.numbers) *> ZIO.log("Getting") *> Effect.none
      ,
      eventHandler = (state, evt) =>
        evt match
          case Event.NextNumberAdded(value) =>
            ZIO
              .succeed(state.copy(numbers = state.numbers :+ value))
          case Event.Cleared                => ZIO.succeed(State())
    )
}

object Main extends ZIOAppDefault:

  private def getNumbers(
    entity: EventSourcedEntity.EntityRef[MyPersistentBehavior.Command, MyPersistentBehavior.State]
  ) = for {
    promise <- Promise.make[Nothing, List[Long]]
    _       <- entity.send(MyPersistentBehavior.Command.Get(promise))
    resp    <- promise.await
    _       <- ZIO.log("Got")
  } yield resp

  val app = for {
    entity   <- MyPersistentBehavior("fib1")
    f1       <- ZIO.foreach(1 to 100)(n => entity.send(MyPersistentBehavior.Command.NextNumber(n))).fork
    f2       <- ZIO.foreach(1 to 100)(n => entity.send(MyPersistentBehavior.Command.NextNumber(n))).fork
    f3       <- ZIO.foreach(1 to 100)(n => entity.send(MyPersistentBehavior.Command.NextNumber(n))).fork
    _        <- f1.join *> f2.join *> f3.join
    numbers  <- getNumbers(entity)
    _        <- ZIO.log(s"State 1: $numbers")
    _        <- entity.passivate
    entity2  <- MyPersistentBehavior("fib1")
    _        <- ZIO.foreach(100 to 1 by -1)(n => entity2.send(MyPersistentBehavior.Command.NextNumber(n)))
    numbers2 <- getNumbers(entity2)
    _        <- ZIO.log(s"State 2: $numbers2")
  } yield ()

  override def run: ZIO[Environment & ZIOAppArgs & Scope, Any, Any] =
    app.provide(InMemoryJournal.live[MyPersistentBehavior.Event])
