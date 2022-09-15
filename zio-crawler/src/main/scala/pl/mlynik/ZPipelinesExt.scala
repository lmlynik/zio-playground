package pl.mlynik

import zio.stream.{ ZChannel, ZPipeline, ZStream }
import zio.{ Chunk, Trace }

object ZPipelinesExt {
  def mapStream[Env, Err, In, Out](
    f: In => ZStream[Env, Err, Out]
  )(implicit trace: Trace): ZPipeline[Env, Err, In, Out] =
    ZPipeline.fromChannel(
      ZChannel.identity[Nothing, Chunk[In], Any].concatMap(_.map(f).map(_.channel).fold(ZChannel.unit)(_ *> _))
    )

  def without[T](exclude: Set[T]): ZPipeline[Any, Nothing, T, T] =
    ZPipeline.filter(f => !exclude.contains(f))
}
