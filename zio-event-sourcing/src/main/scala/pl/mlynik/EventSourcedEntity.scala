package pl.mlynik

import zio.*
object EventSourcedEntity {

  trait EntityRef[COMMAND, STATE] {
    def state: UIO[STATE]

    def send(command: COMMAND): UIO[Unit]

    def passivate: UIO[Unit]
  }

  enum Effect[+EVENT] {
    case Persist(event: EVENT)
    case None
  }

  object Effect {
    def persist[EVENT](event: EVENT) = ZIO.succeed(Persist(event))

    def none = ZIO.succeed(None)
  }

  enum LoadState {
    case Loading
    case Hot
  }

  def apply[COMMAND, EVENT: Tag, STATE](
    persistenceId: String,
    emptyState: STATE,
    commandHandler: (STATE, COMMAND) => URIO[Journal[EVENT], Effect[EVENT]],
    eventHandler: (STATE, EVENT) => URIO[Journal[EVENT], STATE]
  ): ZIO[Journal[EVENT], Journal.LoadError, EntityRef[COMMAND, STATE]] = {

    case class State(offset: Int, entity: STATE, loadState: LoadState) {
      def updateState(entity: STATE): State = this.copy(offset = offset + 1, entity = entity)

      def changeLoadState(loadState: LoadState): State = this.copy(loadState = loadState)
    }

    def journalPlayback(
      persistenceId: String,
      journal: Journal[EVENT],
      currentState: Ref[State]
    ) =
      currentState.get.flatMap { st =>
        journal
          .load(persistenceId, st.offset)
          .runFoldZIO(st) { case (state, (offset, event)) =>
            eventHandler(state.entity, event).flatMap(stateD =>
              currentState.updateAndGet(_.copy(offset = offset, entity = stateD))
            )
          }
      } *> currentState.updateAndGet(_.changeLoadState(LoadState.Hot))

    def commandDispatch(
      persistenceId: String,
      queue: Queue[COMMAND],
      journal: Journal[EVENT],
      currentState: Ref[State]
    ) =
      currentState.get.flatMap { st =>
        ZIO.when(st.loadState == LoadState.Hot) {
          queue.take
            .flatMap(cmd => commandHandler(st.entity, cmd))
            .flatMap { effect =>
              effect match
                case Effect.Persist(event) => journal.persist(persistenceId, event) *> eventHandler(st.entity, event)
                case Effect.None           => ZIO.succeed(st.entity)
            }
            .tap(stateD =>
              currentState.update { state =>
                state.updateState(stateD)
              }
            )
        }
      }.forever.fork

    for {
      journal      <- ZIO.service[Journal[EVENT]]
      currentState <- Ref.make(State(0, emptyState, LoadState.Loading))
      _            <- journalPlayback(persistenceId, journal, currentState)
      _            <- ZIO.logInfo(s"Loaded $persistenceId")
      commandQueue <- Queue.bounded[COMMAND](1024)
      commandFiber <- commandDispatch(persistenceId, commandQueue, journal, currentState)

    } yield new EntityRef[COMMAND, STATE] {
      override def state: UIO[STATE] = currentState.get.map(_._2)

      override def send(command: COMMAND): UIO[Unit] = commandQueue.offer(command).unit

      override def passivate: UIO[Unit] = (commandFiber.interrupt).unit
    }
  }
}
