package pl.mlynik

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import zio.stream.{ZPipeline, ZStream}

object HrefExtractor {

  val parseHTML: ZPipeline[Any, Throwable, String, Document] = ZPipeline.map[String, Document] { input =>
    Jsoup.parse(input, "UTF-8")
  }

  val getLinks: ZPipeline[Any, Throwable, Document, String] = ZPipelinesExt.mapStream { doc =>
    ZStream.fromJavaStream(doc.select("a[href]").stream()).map(_.attributes().get("href"))
  }

  val sanitize: ZPipeline[Any, Throwable, String, String] =
    ZPipeline
      .map[String, String](f => f.trim)
      >>> ZPipeline.filter[String](_ != "/")
      >>> ZPipeline.filter[String](f => !f.startsWith("#"))
      >>> ZPipeline.filter[String](_.nonEmpty)

  val parseAndGetLinks = parseHTML >>> getLinks >>> sanitize
}
