package pl.mlynik

import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.model.Uri
import zio.stream.ZStream
import zio.*
import zio.Console

class InMemoryJournal[EVENT](storage: Ref[List[(Int, EVENT)]]) extends Journal[EVENT] {

  def persist(id: String, event: EVENT): UIO[Unit] =
    storage.update { mem =>
      val newOffset = if (mem.isEmpty) 0 else mem.last._1 + 1
      mem :+ (newOffset -> event)
    }

  def load(id: String, loadFrom: Int): ZStream[Any, Throwable, (Int, EVENT)] =
    ZStream.unwrap(storage.get.map { mem =>
      ZStream.fromIterator(mem.iterator).filter(_._1 >= loadFrom)
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
    case NextFib
    case Clear
    case Get(promise: Promise[Nothing, List[Long]])
  }

  enum Event {
    case NextFibAdded
    case Cleared
  }

  final case class State(numbers: List[Long] = Nil)

  def apply(id: String): URIO[Journal[Event], EntityRef[Command, State]] =
    EventSourcedEntity[Command, Event, State](
      persistenceId = id,
      emptyState = State(),
      commandHandler = (state, cmd) =>
        cmd match
          case Command.NextFib      => Effect.persist(Event.NextFibAdded)
          case Command.Clear        => Effect.persist(Event.Cleared)
          case Command.Get(promise) => promise.succeed(state.numbers) *> Effect.none
      ,
      eventHandler = (state, evt) =>
        evt match
          case Event.NextFibAdded =>
            val nextNum = state.numbers.reverse.take(2) match {
              case head :: next :: _ => head + next
              case head :: Nil       => head
              case Nil               => 1
            }
            ZIO
              .succeed(state.copy(numbers = state.numbers :+ nextNum))
          case Event.Cleared      => ZIO.succeed(State())
    )
}

object Main extends ZIOAppDefault:

  private def getNumbers(
    entity: EventSourcedEntity.EntityRef[MyPersistentBehavior.Command, MyPersistentBehavior.State]
  ) = for {
    promise <- Promise.make[Nothing, List[Long]]
    _       <- entity.send(MyPersistentBehavior.Command.Get(promise))
    resp    <- promise.await
  } yield resp

  val app = for {
    entity  <- MyPersistentBehavior("id")
    _       <- entity.send(MyPersistentBehavior.Command.NextFib).repeatN(100)
    _       <- entity.passivate
    entity2 <- MyPersistentBehavior("id")
    _       <- entity2.state.timed.debug("State 2:")
    _       <- getNumbers(entity2).debug("numbers")
    _       <- entity2.send(MyPersistentBehavior.Command.Clear)
    _       <- entity2.send(MyPersistentBehavior.Command.NextFib)
    _       <- entity2.send(MyPersistentBehavior.Command.NextFib)
    _       <- entity2.send(MyPersistentBehavior.Command.NextFib)

    _ <- ZIO.sleep(200.milli)
    _ <- entity2.state.debug("State 3:")
  } yield ()

  override def run: ZIO[Environment & ZIOAppArgs & Scope, Any, Any] =
    app.provide(InMemoryJournal.live[MyPersistentBehavior.Event])
