package org.broadinstitute.dsde.workbench.leonardo
package monitor

import cats.effect.{Async, ContextShift, Timer}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.InspectableQueue
import io.chrisdavenport.log4cats.Logger
import org.broadinstitute.dsde.workbench.leonardo.config.AutoFreezeConfig
import org.broadinstitute.dsde.workbench.leonardo.dao.JupyterDAO
import org.broadinstitute.dsde.workbench.leonardo.db._
import org.broadinstitute.dsde.workbench.leonardo.http.{dbioToIO, nowInstant}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics

import scala.concurrent.ExecutionContext

/**
 * This monitor periodically sweeps the Leo database and auto pause clusters that have been running for too long.
 */
class AutopauseMonitor[F[_]: ContextShift: Timer](
  config: AutoFreezeConfig,
  jupyterDAO: JupyterDAO[F],
  publisherQueue: InspectableQueue[F, LeoPubsubMessage]
)(implicit F: Async[F],
  metrics: OpenTelemetryMetrics[F],
  logger: Logger[F],
  dbRef: DbReference[F],
  ec: ExecutionContext) {

  val process: Stream[F, Unit] =
    (Stream.sleep[F](config.autoFreezeCheckInterval) ++ Stream.eval(autoPauseCheck)).repeat

  private[monitor] val autoPauseCheck: F[Unit] =
    for {
      clusters <- clusterQuery.getClustersReadyToAutoFreeze.transaction
      now <- nowInstant
      traceId = TraceId(s"fromAutopause_${now.toEpochMilli}")
      pauseableClusters <- clusters.toList.filterA { cluster =>
        jupyterDAO.isAllKernelsIdle(cluster.googleProject, cluster.runtimeName).attempt.flatMap {
          case Left(t) =>
            logger.error(s"Fail to get kernel status for ${cluster.projectNameString} due to $t").as(true)
          case Right(isIdle) =>
            if (!isIdle) {
              val maxKernelActiveTimeExceeded = cluster.auditInfo.kernelFoundBusyDate match {
                case Some(attemptedDate) =>
                  val maxBusyLimitReached =
                    now.toEpochMilli - attemptedDate.toEpochMilli > config.maxKernelBusyLimit.toMillis
                  F.pure(maxBusyLimitReached)
                case None =>
                  clusterQuery
                    .updateKernelFoundBusyDate(cluster.id, now, now)
                    .transaction
                    .as(false) // max kernel active time has not been exceeded
              }

              maxKernelActiveTimeExceeded.ifM(
                metrics.incrementCounter("autoPause/maxKernelActiveTimeExceeded") >>
                  logger
                    .info(
                      s"Auto pausing ${cluster.googleProject}/${cluster.runtimeName} due to exceeded max kernel active time"
                    )
                    .as(true),
                metrics.incrementCounter("autoPause/activeKernelClusters") >>
                  logger
                    .info(
                      s"Not going to auto pause cluster ${cluster.googleProject}/${cluster.runtimeName} due to active kernels"
                    )
                    .as(false)
              )
            } else F.pure(isIdle)
        }
      }
      _ <- metrics.gauge("autoPause/numOfRuntimes", pauseableClusters.length)
      _ <- pauseableClusters.traverse_ { cl =>
        for {
          _ <- clusterQuery.setToStopping(cl.id, now).transaction
          _ <- logger.info(s"${traceId.asString} | Auto freezing runtime ${cl.projectNameString}")
          _ <- publisherQueue.enqueue1(LeoPubsubMessage.StopRuntimeMessage(cl.id, Some(traceId)))
        } yield ()
      }
    } yield ()
}

object AutopauseMonitor {
  def apply[F[_]: Timer: ContextShift](config: AutoFreezeConfig,
                                       jupyterDAO: JupyterDAO[F],
                                       publisherQueue: InspectableQueue[F, LeoPubsubMessage])(
    implicit F: Async[F],
    metrics: OpenTelemetryMetrics[F],
    logger: Logger[F],
    dbRef: DbReference[F],
    ec: ExecutionContext
  ): AutopauseMonitor[F] =
    new AutopauseMonitor(config, jupyterDAO, publisherQueue)
}
