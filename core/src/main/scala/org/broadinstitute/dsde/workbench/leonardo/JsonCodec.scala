package org.broadinstitute.dsde.workbench.leonardo

import java.net.URL
import java.time.Instant

import cats.implicits._
import io.circe.syntax._
import io.circe.{Decoder, DecodingFailure, Encoder}
import org.broadinstitute.dsde.workbench.google2.ClusterName
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GcsObjectName, GcsPath, GoogleProject, parseGcsPath}

object JsonCodec {
  val negativeNumberDecodingFailure = DecodingFailure("Negative number is not allowed", List.empty)
  val oneWorkerSpecifiedDecodingFailure = DecodingFailure("Google Dataproc does not support clusters with 1 non-preemptible worker. Must be 0, 2 or more.", List.empty)
  val emptyMasterMachineType = DecodingFailure("masterMachineType can not be an empty string", List.empty)

  implicit val gcsBucketNameEncoder: Encoder[GcsBucketName] = Encoder.encodeString.contramap(_.value)
  implicit val workbenchEmailEncoder: Encoder[WorkbenchEmail] = Encoder.encodeString.contramap(_.value)
  implicit val gcsObjectNameEncoder: Encoder[GcsObjectName] = Encoder.encodeString.contramap(_.value)
  implicit val googleProjectEncoder: Encoder[GoogleProject] = Encoder.encodeString.contramap(_.value)
  implicit val gcsPathEncoder: Encoder[GcsPath] = Encoder.encodeString.contramap(_.toUri)
  implicit val userScriptPathEncoder: Encoder[UserScriptPath] = Encoder.encodeString.contramap(_.asString)
  implicit val machineTypeEncoder: Encoder[MachineType] = Encoder.encodeString.contramap(_.value)
  implicit val cloudServiceEncoder: Encoder[CloudService] = Encoder.encodeString.contramap(_.asString)
  implicit val dataprocConfigEncoder: Encoder[RuntimeConfig.DataprocConfig] = Encoder.forProduct8(
    "numberOfWorkers",
    "masterMachineType",
    "masterDiskSize",
    // worker settings are None when numberOfWorkers is 0
    "workerMachineType",
   "workerDiskSize",
   "numberOfWorkerLocalSSDs",
   "numberOfPreemptibleWorkers",
    "cloudService"
  )(x => (x.numberOfWorkers, x.machineType, x.diskSize, x.workerMachineType, x.workerDiskSize, x.numberOfWorkerLocalSSDs, x.numberOfPreemptibleWorkers, x.cloudService))
  implicit val gceRuntimConfigEncoder: Encoder[RuntimeConfig.GceConfig] = Encoder.forProduct3(
  "machineType",
    "diskSize",
    "cloudService"
  )(x => (x.machineType, x.diskSize, x.cloudService))
  implicit val userJupyterExtensionConfigEncoder: Encoder[UserJupyterExtensionConfig] = Encoder.forProduct4(
    "nbExtensions",
    "serverExtensions",
    "combinedExtensions",
    "labExtensions"
  )(x => UserJupyterExtensionConfig.unapply(x).get)
  implicit val auditInfoEncoder: Encoder[AuditInfo] = Encoder.forProduct5(
    "creator",
    "createdDate",
    "destroyedDate",
    "dateAccessed",
    "kernelFoundBusyDate",
  )(x => AuditInfo.unapply(x).get)
  implicit val clusterImageTypeEncoder: Encoder[ClusterImageType] = Encoder.encodeString.contramap(_.toString)
  implicit val clusterImageEncoder: Encoder[ClusterImage] = Encoder.forProduct3(
    "imageType",
    "imageUrl",
    "timestamp"
  )(x => ClusterImage.unapply(x).get)

  implicit val runtimeConfigEncoder: Encoder[RuntimeConfig] = Encoder.instance(
    x =>
      x match {
        case x: RuntimeConfig.DataprocConfig => x.asJson
        case x: RuntimeConfig.GceConfig => x.asJson
      }
  )
  implicit val containerImageEncoder: Encoder[ContainerImage] = Encoder.encodeString.contramap(_.imageUrl)
  implicit val serviceAccountInfoEncoder: Encoder[ServiceAccountInfo] = Encoder.forProduct2(
    "clusterServiceAccount",
    "notebookServiceAccount"
  )(x => ServiceAccountInfo.unapply(x).get)

  implicit val containerImageDecoder: Decoder[ContainerImage] = Decoder.decodeString.emap(s => ContainerImage.stringToJupyterDockerImage(s).toRight(s"invalid container image ${s}"))
  implicit val cloudServiceDecoder: Decoder[CloudService] = Decoder.decodeString.emap(s => CloudService.withNameOption(s).toRight(s"Unsupported cloud service ${s}"))
  implicit val clusterNameDecoder: Decoder[ClusterName] = Decoder.decodeString.map(ClusterName)
  implicit val clusterInternalIdDecoder: Decoder[ClusterInternalId] = Decoder.decodeString.map(ClusterInternalId)
  implicit val machineTypeDecoder: Decoder[MachineType] = Decoder.decodeString.map(MachineType)
  implicit val instantDecoder: Decoder[Instant] =
    Decoder.decodeString.emap(s => Either.catchNonFatal(Instant.parse(s)).leftMap(_.getMessage))
  implicit val gcsBucketNameDecoder: Decoder[GcsBucketName] = Decoder.decodeString.map(GcsBucketName)
  implicit val googleProjectDecoder: Decoder[GoogleProject] = Decoder.decodeString.map(GoogleProject)
  implicit val urlDecoder: Decoder[URL] = Decoder.decodeString.emap(s => Either.catchNonFatal(new URL(s)).leftMap(_.getMessage))
  implicit val workbenchEmailDecoder: Decoder[WorkbenchEmail] = Decoder.decodeString.map(WorkbenchEmail)
  implicit val clusterImageTypeDecoder: Decoder[ClusterImageType] = Decoder.decodeString.emap(s => ClusterImageType.stringToClusterImageType.get(s).toRight(s"invalid ClusterImageType ${s}"))
  implicit val clusterImageDecoder: Decoder[ClusterImage] = Decoder.forProduct3(
    "imageType",
    "imageUrl",
    "timestamp"
  )(ClusterImage.apply)
  implicit val auditInfoDecoder: Decoder[AuditInfo] = Decoder.forProduct5(
    "creator",
    "createdDate",
    "destroyedDate",
    "dateAccessed",
    "kernelFoundBusyDate",
  )(AuditInfo.apply)
  implicit val rtDataprocConfigDecoder: Decoder[RuntimeConfig.DataprocConfig] = Decoder.instance {
    c =>
      for {
        numberOfWorkers <- c.downField("numberOfWorkers").as[Int]
        masterMachineType <- c.downField("masterMachineType").as[String]
        masterDiskSize <- c.downField("masterDiskSize").as[Int]
        workerMachineType <- c.downField("workerMachineType").as[Option[String]]
        workerDiskSize <- c.downField("workerDiskSize").as[Option[Int]]
        numberOfWorkerLocalSSDs <- c.downField("numberOfWorkerLocalSSDs").as[Option[Int]]
        numberOfPreemptibleWorkers <- c.downField("numberOfPreemptibleWorkers").as[Option[Int]]
      } yield RuntimeConfig.DataprocConfig(numberOfWorkers, masterMachineType, masterDiskSize, workerMachineType, workerDiskSize, numberOfWorkerLocalSSDs, numberOfPreemptibleWorkers)
  }

  implicit val gceConfigDecoder: Decoder[RuntimeConfig.GceConfig] = Decoder.forProduct2(
    "machineType",
    "diskSize"
  )((mt, ds) => RuntimeConfig.GceConfig(mt, ds))

  implicit val runtimeConfigDecoder: Decoder[RuntimeConfig] = Decoder.instance {
    x =>
      for {
        cloudService <- x.downField("cloudService").as[CloudService]
        r <- cloudService match {
          case CloudService.Dataproc =>
            x.as[RuntimeConfig.DataprocConfig]
          case CloudService.GCE =>
            x.as[RuntimeConfig.GceConfig]
        }
      } yield r
  }

  implicit val serviceAccountInfoDecoder: Decoder[ServiceAccountInfo] = Decoder.forProduct2(
    "clusterServiceAccount",
    "notebookServiceAccount"
  )(ServiceAccountInfo.apply)

  implicit val clusterErrorDecoder: Decoder[ClusterError] = Decoder.forProduct3(
    "errorMessage",
    "errorCode",
    "timestamp"
  )(ClusterError.apply)
  implicit val gcsPathDecoder: Decoder[GcsPath] = Decoder.decodeString.emap(s => parseGcsPath(s).leftMap(_.value))
  implicit val userScriptPathDecoder: Decoder[UserScriptPath] = Decoder.decodeString.emap{
    s => UserScriptPath.stringToUserScriptPath(s).leftMap(_.getMessage)
  }
  implicit val userJupyterExtensionConfigDecoder: Decoder[UserJupyterExtensionConfig] = Decoder.forProduct4(
    "nbExtensions",
    "serverExtensions",
    "combinedExtensions",
    "labExtensions"
  )(UserJupyterExtensionConfig.apply)
}