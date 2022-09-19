package pl.mlynik

import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.model.Uri
import zio.stream.ZStream
import zio.*
import zio.Console

import scala.compiletime.*
import scala.quoted.*

trait Journal[EVENT] {
  def persist(id: String, event: EVENT): UIO[Unit]

  def load(id: String, loadFrom: Int): ZStream[Any, Throwable, (Int, EVENT)]
}

class InMemoryJournal[EVENT](storage: Ref[List[(Int, EVENT)]]) extends Journal[EVENT] {

  def persist(id: String, event: EVENT): UIO[Unit]                           =
    storage.update { mem =>
      val newOffset = if (mem.isEmpty) 0 else mem.last._1 + 1
      mem :+ (newOffset -> event)
    }
  def load(id: String, loadFrom: Int): ZStream[Any, Throwable, (Int, EVENT)] =
    ZStream.unwrap(storage.get.map { mem =>
      ZStream.fromIterator(mem.iterator).filter(_._1 > loadFrom)
    })
}

object InMemoryJournal {
  def live[EVENT: Tag] = ZLayer.fromZIO(for {
    ref    <- Ref.make[List[(Int, EVENT)]](Nil)
    journal = new InMemoryJournal(ref)
  } yield journal)
}

trait EntityRef[COMMAND, STATE] {
  def state: UIO[STATE]

  def send(command: COMMAND): UIO[Unit]

  def passivate: UIO[Unit]
}
object EventSourcedEntity       {

  private def journalPlayback[EVENT, STATE](
    persistenceId: String,
    eventHandler: (STATE, EVENT) => URIO[Journal[EVENT], STATE],
    journal: Journal[EVENT],
    currentState: Ref[(Int, STATE)]
  ): ZIO[Journal[EVENT], Throwable, STATE] =
    currentState.get.flatMap { st =>
      journal
        .load(persistenceId, st._1)
        .runFoldZIO(st._2) { case (state, (offset, event)) =>
          eventHandler(state, event).tap(state => currentState.set(offset -> state))
        }
    }

  private def commandDispatch[COMMAND, EVENT, STATE](
    persistenceId: String,
    queue: Queue[COMMAND],
    commandHandler: (STATE, COMMAND) => URIO[Journal[EVENT], EVENT],
    eventHandler: (STATE, EVENT) => URIO[Journal[EVENT], STATE],
    journal: Journal[EVENT],
    currentState: Ref[(Int, STATE)]
  ) =
    currentState.get.flatMap { st =>
      queue.take
        .flatMap(cmd => commandHandler(st._2, cmd))
        .flatMap(event => journal.persist(persistenceId, event) *> eventHandler(st._2, event))
        .tap(f =>
          currentState.update { case (offset, _) =>
            (offset + 1, f)
          }
        )
    }.forever.fork

  def apply[COMMAND, EVENT: Tag, STATE](
    persistenceId: String,
    emptyState: STATE,
    commandHandler: (STATE, COMMAND) => URIO[Journal[EVENT], EVENT],
    eventHandler: (STATE, EVENT) => URIO[Journal[EVENT], STATE]
  ): URIO[Journal[EVENT], EntityRef[COMMAND, STATE]] =
    for {
      journal      <- ZIO.service[Journal[EVENT]]
      currentState <- Ref.make(0 -> emptyState)
      commandQueue <- Queue.bounded[COMMAND](1024)
      commandFiber <- commandDispatch(persistenceId, commandQueue, commandHandler, eventHandler, journal, currentState)
      _            <- journalPlayback(persistenceId, eventHandler, journal, currentState).orDie.debug(
                        s"Loaded entity ${persistenceId}"
                      )
    } yield new EntityRef[COMMAND, STATE] {
      override def state: UIO[STATE] = currentState.get.map(_._2)

      override def send(command: COMMAND): UIO[Unit] = commandQueue.offer(command).unit

      override def passivate: UIO[Unit] = (commandFiber.interrupt).unit
    }
}

object MyPersistentBehavior {
  enum Command {
    case Add(data: String) extends Command
    case Clear extends Command
  }
  enum Event   {
    case Added(data: String) extends Event
    case Cleared extends Event
  }

  final case class State(history: List[String] = Nil)

  def apply(): URIO[Journal[Event], EntityRef[Command, State]] =
    EventSourcedEntity[Command, Event, State](
      persistenceId = """abc""",
      emptyState = State(),
      commandHandler = (state, cmd) =>
        cmd match
          case Command.Add(data) => ZIO.succeed(Event.Added(data))
          case Command.Clear     => ZIO.succeed(Event.Cleared)
      ,
      eventHandler = (state, evt) =>
        evt match
          case Event.Added(data) => ZIO.succeed(state.copy(history = state.history :+ data))
          case Event.Cleared     => ZIO.succeed(State())
    )
}

object Main extends ZIOAppDefault:

  val app                                                           = for {
    entity  <- MyPersistentBehavior()
    _       <- entity.send(MyPersistentBehavior.Command.Add("hello world")).repeatN(10000).fork
    _       <- entity.state.repeatWhile(f => f.history.length < 10000).map(f => f.history.size).timed.debug("State 1:")
    _       <- entity.passivate
    entity2 <- MyPersistentBehavior()
    _       <- entity2.state.repeatWhile(f => f.history.length < 10000).map(f => f.history.size).timed.debug("State 2:")
    _       <- entity2.send(MyPersistentBehavior.Command.Clear)
    _       <- entity2.send(MyPersistentBehavior.Command.Add("clear slate"))
    _       <- ZIO.sleep(200.milli)
    _       <- entity2.state.debug("State 3:")
  } yield ()
  override def run: ZIO[Environment & ZIOAppArgs & Scope, Any, Any] =
    app.provide(InMemoryJournal.live[MyPersistentBehavior.Event])
