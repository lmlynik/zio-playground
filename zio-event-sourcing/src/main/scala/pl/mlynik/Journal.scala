package pl.mlynik
import zio.*
import zio.stream.*
import Journal.*
object Journal {
  trait LoadError
  trait PersistError
}

trait Journal[EVENT] {
  def persist(id: String, event: EVENT): IO[PersistError, Unit]

  def load(id: String, loadFrom: Int): ZStream[Any, LoadError, (Int, EVENT)]
}
