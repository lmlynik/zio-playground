package pl.mlynik

import pl.mlynik.LinksVisitedRegistry.*
import sttp.client3.asynchttpclient.zio.AsyncHttpClientZioBackend
import zio.*
import zio.concurrent.ConcurrentMap

import java.net.URI

class LinksVisitedRegistry(concurrentMap: ConcurrentMap[String, Int]) {
  def markVisited(links: Set[String]): UIO[Unit] = {
    val re = ZIO.foreach(links) { link =>
      for {
        _ <- concurrentMap.compute(
               link,
               {
                 case (_, None) => Some(1)
                 case (_, v)    => v.map(_ + 1)
               }
             )
      } yield ()
    }

    re.unit
  }

  def contains(link: String): UIO[Boolean] =
    concurrentMap.get(link).map(_.isDefined)

  def visited: UIO[Set[String]] =
    concurrentMap.toList.map(_.map(_._1).toSet)
}

object LinksVisitedRegistry {
  val live: ZLayer[Any, Any, LinksVisitedRegistry] =
    ZLayer.fromZIO(ConcurrentMap.empty[String, Int].map(mp => new LinksVisitedRegistry(mp)))
}
