package pl.mlynik

import pl.mlynik.HttpService.*
import sttp.client3.*
import sttp.client3.asynchttpclient.zio.AsyncHttpClientZioBackend
import sttp.model.Uri
import zio.*

trait HttpService {
  def request(request: RunRequest): UIO[HttpService.Result]
}

object HttpService {

  type Http = SttpBackend[Task, Any]

  case class RunRequest(uri: Uri, depth: Int)

  enum Result {
    case Success(content: String) extends Result
    case Error(ex: Throwable) extends Result
  }

  val live: ZLayer[Http, Any, HttpService] = ZLayer.fromZIO {
    for {
      sttpBackend <- ZIO.service[Http]
    } yield new HttpService {
      override def request(request: RunRequest): UIO[Result] =
        sttpBackend
          .send(basicRequest.response(asStringAlways).get(request.uri))
          .fold(
            ex => Result.Error(ex),
            content => Result.Success(content.body)
          )
    }
  }
}
