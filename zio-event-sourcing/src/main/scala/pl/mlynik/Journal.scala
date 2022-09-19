package pl.mlynik
import zio.*
import zio.stream.*

trait Journal[EVENT] {
  def persist(id: String, event: EVENT): UIO[Unit]

  def load(id: String, loadFrom: Int): ZStream[Any, Throwable, (Int, EVENT)]
}
