package com.twitter.server.handler

import com.twitter.conversions.time._
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.Service
import com.twitter.io.Buf
import com.twitter.jvm.CpuProfile
import com.twitter.server.util.HttpUtils.{newResponse, parse}
import com.twitter.util.{Duration, Future, Return, Throw}
import java.io.ByteArrayOutputStream
import com.twitter.util.logging.Logger

class ProfileResourceHandler(which: Thread.State) extends Service[Request, Response] {
  private[this] val log = Logger[ProfileResourceHandler]

  case class Params(pause: Duration, frequency: Int)

  def apply(req: Request): Future[Response] = {
    val params = parse(req.uri)._2.foldLeft(Params(10.seconds, 100)) {
      case (parameters, ("seconds", Seq(pauseVal))) =>
        parameters.copy(pause = pauseVal.toInt.seconds)
      case (parameters, ("hz", Seq(hz))) =>
        parameters.copy(frequency = hz.toInt)
      case (parameters, _) =>
        parameters
    }

    log.info(
      s"[${req.uri}] collecting CPU profile ($which) for ${params.pause} seconds at ${params.frequency}Hz"
    )

    CpuProfile.recordInThread(params.pause, params.frequency, which) transform {
      case Return(prof) =>
        // Write out the profile verbatim. It's a pprof "raw" profile.
        val bos = new ByteArrayOutputStream

        prof.writeGoogleProfile(bos)
        newResponse(
          contentType = "pprof/raw",
          content = Buf.ByteArray.Owned(bos.toByteArray)
        )

      case Throw(exc) =>
        newResponse(
          status = Status.InternalServerError,
          contentType = "text/plain;charset=UTF-8",
          content = Buf.Utf8(exc.toString)
        )
    }
  }
}
