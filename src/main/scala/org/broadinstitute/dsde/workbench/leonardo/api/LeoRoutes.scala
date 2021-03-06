package org.broadinstitute.dsde.workbench.leonardo.api

import akka.actor.ActorSystem
import akka.event.Logging.LogLevel
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import SprayJsonSupport._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives.{complete, get, handleExceptions, pathEndOrSingleSlash, pathPrefix}
import akka.http.scaladsl.server.{Directive0, ExceptionHandler}
import akka.http.scaladsl.server.RouteResult.Complete
import akka.http.scaladsl.server.directives.{DebuggingDirectives, LogEntry, LoggingMagnet}
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.leonardo.model.ErrorReport

import scala.concurrent.{ExecutionContext, Future}

class LeoRoutes()(implicit val system: ActorSystem, val materializer: Materializer, val executionContext: ExecutionContext)
  extends LazyLogging {

  def leoRoutes: server.Route =
    pathPrefix("ping") {
      pathEndOrSingleSlash {
        get {
          complete {
            StatusCodes.OK
          }
        }
      }
    }

  def route: server.Route = (logRequestResult & handleExceptions(myExceptionHandler)) {
    leoRoutes
  }

  private val myExceptionHandler = {
    import org.broadinstitute.dsde.workbench.leonardo.model.ErrorReportJsonSupport._

    ExceptionHandler {
      //case withErrorReport: WorkbenchExceptionWithErrorReport =>
      //  complete(withErrorReport.errorReport.statusCode.getOrElse(StatusCodes.InternalServerError), withErrorReport.errorReport)
      case e: Throwable =>
        complete(StatusCodes.InternalServerError, ErrorReport(e))
    }
  }

  // basis for logRequestResult lifted from http://stackoverflow.com/questions/32475471/how-does-one-log-akka-http-client-requests
  private def logRequestResult: Directive0 = {
    def entityAsString(entity: HttpEntity): Future[String] = {
      entity.dataBytes
        .map(_.decodeString(entity.contentType.charsetOption.getOrElse(HttpCharsets.`UTF-8`).value))
        .runWith(Sink.head)
    }

    def myLoggingFunction(logger: LoggingAdapter)(req: HttpRequest)(res: Any): Unit = {
      val entry = res match {
        case Complete(resp) =>
          val logLevel: LogLevel = resp.status.intValue / 100 match {
            case 5 => Logging.ErrorLevel
            case _ => Logging.DebugLevel
          }
          entityAsString(resp.entity).map(data => LogEntry(s"${req.method} ${req.uri}: ${resp.status} entity: $data", logLevel))
        case other =>
          Future.successful(LogEntry(s"$other", Logging.DebugLevel)) // I don't really know when this case happens
      }
      entry.map(_.logTo(logger))
    }

    DebuggingDirectives.logRequestResult(LoggingMagnet(log => myLoggingFunction(log)))
  }

  def statusCodeCreated[T](response: T): (StatusCode, T) = (StatusCodes.Created, response)

}
