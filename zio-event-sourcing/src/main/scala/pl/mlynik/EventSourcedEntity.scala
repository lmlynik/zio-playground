package pl.mlynik

import zio.*
object EventSourcedEntity {

  trait EntityRef[COMMAND, STATE] {
    def state: UIO[STATE]

    def send(command: COMMAND): UIO[Unit]
  }

  enum Effect[+EVENT] {
    case Persist(event: EVENT)
    case None
  }

  object Effect {
    def persist[EVENT](event: EVENT): UIO[Effect.Persist[EVENT]] = ZIO.succeed(Persist(event))

    val none: UIO[Effect[Nothing]] = ZIO.succeed(None)
  }

  def apply[COMMAND, EVENT: Tag, STATE](
    persistenceId: String,
    emptyState: STATE,
    commandHandler: (STATE, COMMAND) => URIO[Journal[EVENT], Effect[EVENT]],
    eventHandler: (STATE, EVENT) => URIO[Journal[EVENT], STATE]
  ): ZIO[Journal[EVENT] & Scope, Journal.LoadError, EntityRef[COMMAND, STATE]] = {

    case class State(offset: Int, entity: STATE) {
      def updateState(entity: STATE): State = this.copy(offset = offset + 1, entity = entity)
    }

    def journalPlayback(
      journal: Journal[EVENT],
      currentState: Ref.Synchronized[State]
    ) =
      currentState.updateAndGetZIO { st =>
        journal
          .load(persistenceId, st.offset)
          .runFoldZIO(st) { case (state, (offset, event)) =>
            eventHandler(state.entity, event).map(stateD => st.copy(offset = offset, entity = stateD))
          }
      }

    def handleEffect(journal: Journal[EVENT], state: State)(effect: Effect[EVENT]) =
      effect match
        case Effect.Persist(event) => journal.persist(persistenceId, event) *> eventHandler(state.entity, event)
        case Effect.None           => ZIO.succeed(state.entity)

    def commandDispatch(
      queue: Queue[COMMAND],
      journal: Journal[EVENT],
      currentState: Ref.Synchronized[State]
    ) =
      currentState.updateAndGetZIO { st =>
        queue.take
          .flatMap(cmd => commandHandler(st.entity, cmd))
          .flatMap(handleEffect(journal, st))
          .map(st.updateState)
      }.forever.fork

    for {
      journal      <- ZIO.service[Journal[EVENT]]
      currentState <- Ref.Synchronized.make(State(0, emptyState))
      _            <- journalPlayback(journal, currentState)
      _            <- ZIO.logInfo(s"Loaded $persistenceId")
      commandQueue <- Queue.bounded[COMMAND](1024)
      _            <- commandDispatch(commandQueue, journal, currentState)

    } yield new EntityRef[COMMAND, STATE] {
      override def state: UIO[STATE] = currentState.get.map(_._2)

      override def send(command: COMMAND): UIO[Unit] = commandQueue.offer(command).unit
    }
  }
}
