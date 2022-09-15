package pl.mlynik

import pl.mlynik.HttpService.RunRequest
import pl.mlynik.Main.{ Environment, Link }
import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.model.Uri
import zio.stream.ZStream
import zio.{ Console, Scope, Task, ZIO, ZIOAppArgs, ZIOAppDefault }

object Main extends ZIOAppDefault:

  enum Link {

    def link: Uri

    case Root(link: Uri) extends Link
    case Node(link: Uri, parent: Link) extends Link
  }

  override def run: ZIO[Environment & ZIOAppArgs & Scope, Any, Any] = {
    def resolveLink(p: Link): Task[Uri] =
      p match
        case Link.Root(link)         => ZIO.succeed(link)
        case Link.Node(link, parent) =>
          resolveLink(parent).map { p =>
            p.resolve(link)
          }

    def resolveHostNode(p: Link): Task[Link] =
      p match
        case r: Link.Root                              => ZIO.succeed(r)
        case n @ Link.Node(link, _) if link.isAbsolute => ZIO.succeed(n)
        case Link.Node(link, parent)                   =>
          for {
            p  <- resolveHostNode(parent)
            uri = p.link.resolve(link)
          } yield Link.Node(uri, p)

    def runCrawler(uri: Uri) = {
      def runCrawlerIter(link: Link, depth: Int): ZStream[HttpService & LinksVisitedRegistry, Throwable, Link] = {
        def nextIter(linkP: Link): ZStream[HttpService & LinksVisitedRegistry, Throwable, Link] = ZStream.unwrap {
          resolveLink(linkP).map { rr =>
            runCrawlerIter(
              Link.Node(rr, link),
              depth - 1
            )
          }
        }.tapError(r => Console.printLineError(r.toString))
          .catchAll(_ => ZStream.empty)

        val iter = for {
          linksVisitedRegistry <- ZIO.service[LinksVisitedRegistry]
          visited              <- linksVisitedRegistry.visited
        } yield
          if (depth > 0) {
            val html = for {
              service  <- ZIO.service[HttpService]
              response <- service.request(RunRequest(link.link, depth))
            } yield response

            val filteredStream = ZStream
              .fromZIO(html)
              .collect { case HttpService.Result.Success(content) =>
                content
              }
              .via(HrefExtractor.parseAndGetLinks)
              .via(ZPipelinesExt.without(visited))
              .mapZIO(r => ZIO.fromEither(Uri.parse(r)).mapError(r => new Error(r)))
              .mapZIO(r => resolveHostNode(Link.Node(r, link)))

            filteredStream concat filteredStream
              .tap(link => linksVisitedRegistry.markVisited(Set(link.link.toString)))
              .flatMapPar(16)(nextIter)
          } else {
            ZStream.empty
          }

        ZStream.unwrap(iter)
      }

      runCrawlerIter(Link.Root(uri), 2)
    }

    runCrawler(Uri.parse("https://lmlynik.github.io/").toOption.get).foreach { link =>
      Console.printLine(link)
    }.debug("sink")
      .provide(
        LinksVisitedRegistry.live,
        HttpService.live,
        HttpClientZioBackend.layer()
      )
  }
