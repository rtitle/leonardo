package org.broadinstitute.dsde.workbench.leonardo
package http
package service

import java.time.Instant
import java.util.UUID

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import cats.effect.IO
import cats.mtl.ApplicativeAsk
import org.broadinstitute.dsde.workbench.google2.mock.FakeGoogleStorageInterpreter
import org.broadinstitute.dsde.workbench.leonardo.CommonTestData._
import org.broadinstitute.dsde.workbench.leonardo.config.Config
import org.broadinstitute.dsde.workbench.leonardo.dao.MockDockerDAO
import org.broadinstitute.dsde.workbench.leonardo.db.{clusterQuery, labelQuery, RuntimeConfigQueries, TestComponent}
import org.broadinstitute.dsde.workbench.leonardo.http.api.{CreateRuntime2Request, RuntimeServiceContext}
import org.broadinstitute.dsde.workbench.leonardo.monitor.LeoPubsubMessage.{
  CreateRuntimeMessage,
  DeleteRuntimeMessage,
  StartRuntimeMessage,
  StopRuntimeMessage
}
import org.broadinstitute.dsde.workbench.leonardo.util.QueueFactory
import org.broadinstitute.dsde.workbench.model
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.model.{UserInfo, WorkbenchEmail, WorkbenchUserId}
import org.scalatest.FlatSpec

import scala.concurrent.ExecutionContext.Implicits.global

class RuntimeServiceInterpSpec extends FlatSpec with LeonardoTestSuite with TestComponent {
  val publisherQueue = QueueFactory.makePublisherQueue()
  val runtimeService = new RuntimeServiceInterp(
    blocker,
    semaphore,
    RuntimeServiceConfig(Config.proxyConfig.proxyUrlBase,
                         imageConfig,
                         autoFreezeConfig,
                         dataprocConfig,
                         Config.gceConfig),
    whitelistAuthProvider,
    serviceAccountProvider,
    new MockDockerDAO,
    FakeGoogleStorageInterpreter,
    publisherQueue
  )
  val emptyCreateRuntimeReq = CreateRuntime2Request(
    Map.empty,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    Set.empty,
    Map.empty
  )

  implicit val ctx: ApplicativeAsk[IO, RuntimeServiceContext] = ApplicativeAsk.const[IO, RuntimeServiceContext](
    RuntimeServiceContext(model.TraceId("traceId"), Instant.now())
  )

  "RuntimeService" should "fail with AuthorizationError if user doesn't have project level permission" in {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("email"), 0)
    val googleProject = GoogleProject("googleProject")

    val res = for {
      r <- runtimeService
        .createRuntime(
          userInfo,
          googleProject,
          RuntimeName("clusterName1"),
          emptyCreateRuntimeReq
        )
        .attempt
    } yield {
      r shouldBe (Left(AuthorizationError(Some(userInfo.userEmail))))
    }
    res.unsafeRunSync()
  }

  it should "successfully create a GCE runtime when no runtime is specified" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed
    val googleProject = GoogleProject("googleProject")
    val runtimeName = RuntimeName("clusterName1")

    val res = for {
      context <- ctx.ask
      r <- runtimeService
        .createRuntime(
          userInfo,
          googleProject,
          runtimeName,
          emptyCreateRuntimeReq
        )
        .attempt
      clusterOpt <- clusterQuery.getActiveClusterByNameMinimal(googleProject, runtimeName).transaction
      cluster = clusterOpt.get
      runtimeConfig <- RuntimeConfigQueries.getRuntimeConfig(cluster.runtimeConfigId).transaction
      message <- publisherQueue.dequeue1
    } yield {
      r shouldBe Right(())
      runtimeConfig shouldBe (Config.gceConfig.runtimeConfigDefaults)
      cluster.googleProject shouldBe (googleProject)
      cluster.runtimeName shouldBe (runtimeName)
      val expectedMessage = CreateRuntimeMessage
        .fromRuntime(cluster, runtimeConfig, Some(context.traceId))
        .copy(
          runtimeImages = Set(
            RuntimeImage(RuntimeImageType.Jupyter, Config.imageConfig.jupyterImage.imageUrl, context.now),
            RuntimeImage(RuntimeImageType.Welder, Config.imageConfig.welderImage.imageUrl, context.now),
            RuntimeImage(RuntimeImageType.Proxy, Config.imageConfig.proxyImage.imageUrl, context.now)
          ),
          scopes = Config.dataprocConfig.defaultScopes
        )
      message shouldBe expectedMessage
    }
    res.unsafeRunSync()
  }

  it should "successfully create a dataproc runtime when explicitly told so when numberOfWorkers is 0" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed
    val googleProject = GoogleProject("googleProject")
    val runtimeName = RuntimeName("clusterName1")
    val req = emptyCreateRuntimeReq.copy(
      runtimeConfig = Some(
        RuntimeConfigRequest.DataprocConfig(
          None,
          None,
          None,
          None,
          None,
          None,
          None,
          Map.empty
        )
      )
    )

    val res = for {
      context <- ctx.ask
      _ <- runtimeService
        .createRuntime(
          userInfo,
          googleProject,
          runtimeName,
          req
        )
        .attempt
      clusterOpt <- clusterQuery.getActiveClusterByNameMinimal(googleProject, runtimeName).transaction
      cluster = clusterOpt.get
      runtimeConfig <- RuntimeConfigQueries.getRuntimeConfig(cluster.runtimeConfigId).transaction
      message <- publisherQueue.dequeue1
    } yield {
      // Default worker is 0, hence all worker configs are None
      val expectedRuntimeConfig = Config.dataprocConfig.runtimeConfigDefaults.copy(
        workerMachineType = None,
        workerDiskSize = None,
        numberOfWorkerLocalSSDs = None,
        numberOfPreemptibleWorkers = None
      )
      runtimeConfig shouldBe expectedRuntimeConfig
      val expectedMessage = CreateRuntimeMessage
        .fromRuntime(cluster, runtimeConfig, Some(context.traceId))
        .copy(
          runtimeImages = Set(
            RuntimeImage(RuntimeImageType.Jupyter, Config.imageConfig.jupyterImage.imageUrl, context.now),
            RuntimeImage(RuntimeImageType.Welder, Config.imageConfig.welderImage.imageUrl, context.now),
            RuntimeImage(RuntimeImageType.Proxy, Config.imageConfig.proxyImage.imageUrl, context.now)
          ),
          scopes = Config.dataprocConfig.defaultScopes
        )
      message shouldBe expectedMessage
    }
    res.unsafeRunSync()
  }

  it should "successfully create a dataproc runtime when explicitly told so when numberOfWorkers is more than 0" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed
    val googleProject = GoogleProject("googleProject")
    val runtimeName = RuntimeName("clusterName1")
    val req = emptyCreateRuntimeReq.copy(
      runtimeConfig = Some(
        RuntimeConfigRequest.DataprocConfig(
          Some(2),
          None,
          None,
          None,
          None,
          None,
          None,
          Map.empty
        )
      )
    )

    val res = for {
      context <- ctx.ask
      _ <- runtimeService
        .createRuntime(
          userInfo,
          googleProject,
          runtimeName,
          req
        )
        .attempt
      clusterOpt <- clusterQuery.getActiveClusterByNameMinimal(googleProject, runtimeName).transaction
      cluster = clusterOpt.get
      runtimeConfig <- RuntimeConfigQueries.getRuntimeConfig(cluster.runtimeConfigId).transaction
      message <- publisherQueue.dequeue1
    } yield {
      runtimeConfig shouldBe Config.dataprocConfig.runtimeConfigDefaults.copy(numberOfWorkers = 2)
      val expectedMessage = CreateRuntimeMessage
        .fromRuntime(cluster, runtimeConfig, Some(context.traceId))
        .copy(
          runtimeImages = Set(
            RuntimeImage(RuntimeImageType.Jupyter, Config.imageConfig.jupyterImage.imageUrl, context.now),
            RuntimeImage(RuntimeImageType.Welder, Config.imageConfig.welderImage.imageUrl, context.now),
            RuntimeImage(RuntimeImageType.Proxy, Config.imageConfig.proxyImage.imageUrl, context.now)
          ),
          scopes = Config.dataprocConfig.defaultScopes
        )
      message shouldBe expectedMessage
    }
    res.unsafeRunSync()
  }

  it should "get a runtime" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed

    val res = for {
      internalId <- IO(RuntimeInternalId(UUID.randomUUID.toString))
      testRuntime <- IO(makeCluster(1).copy(internalId = internalId).save())
      getResponse <- runtimeService.getRuntime(userInfo, testRuntime.googleProject, testRuntime.runtimeName)
    } yield {
      getResponse.internalId shouldBe testRuntime.internalId
    }
    res.unsafeRunSync()
  }

  it should "list runtimes" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed

    val res = for {
      internalId1 <- IO(RuntimeInternalId(UUID.randomUUID.toString))
      internalId2 <- IO(RuntimeInternalId(UUID.randomUUID.toString))
      _ <- IO(makeCluster(1).copy(internalId = internalId1).save())
      _ <- IO(makeCluster(2).copy(internalId = internalId2).save())
      listResponse <- runtimeService.listRuntimes(userInfo, None, Map.empty)
    } yield {
      listResponse.map(_.internalId).toSet shouldBe Set(internalId1, internalId2)
    }

    res.unsafeRunSync()
  }

  it should "list runtimes with a project" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed

    val res = for {
      internalId1 <- IO(RuntimeInternalId(UUID.randomUUID.toString))
      internalId2 <- IO(RuntimeInternalId(UUID.randomUUID.toString))
      _ <- IO(makeCluster(1).copy(internalId = internalId1).save())
      _ <- IO(makeCluster(2).copy(internalId = internalId2).save())
      listResponse <- runtimeService.listRuntimes(userInfo, Some(project), Map.empty)
    } yield {
      listResponse.map(_.internalId).toSet shouldBe Set(internalId1, internalId2)
    }

    res.unsafeRunSync()
  }

  it should "list runtimes with parameters" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed

    val res = for {
      internalId1 <- IO(RuntimeInternalId(UUID.randomUUID.toString))
      internalId2 <- IO(RuntimeInternalId(UUID.randomUUID.toString))
      runtime1 <- IO(makeCluster(1).copy(internalId = internalId1).save())
      _ <- IO(makeCluster(2).copy(internalId = internalId2).save())
      _ <- labelQuery.save(runtime1.id, "foo", "bar").transaction
      listResponse <- runtimeService.listRuntimes(userInfo, None, Map("foo" -> "bar"))
    } yield {
      listResponse.map(_.internalId).toSet shouldBe Set(internalId1)
    }

    res.unsafeRunSync()
  }

  it should "delete a runtime" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed

    val res = for {
      context <- ctx.ask
      internalId <- IO(RuntimeInternalId(UUID.randomUUID.toString))
      testRuntime <- IO(makeCluster(1).copy(internalId = internalId).save())

      _ <- runtimeService.deleteRuntime(userInfo, testRuntime.googleProject, testRuntime.runtimeName)
      dbRuntimeOpt <- clusterQuery
        .getActiveClusterByNameMinimal(testRuntime.googleProject, testRuntime.runtimeName)
        .transaction
      dbRuntime = dbRuntimeOpt.get
      message <- publisherQueue.dequeue1
    } yield {
      dbRuntime.status shouldBe RuntimeStatus.Deleting
      val expectedMessage = DeleteRuntimeMessage(testRuntime.id, Some(context.traceId))
      message shouldBe expectedMessage
    }

    res.unsafeRunSync()
  }

  it should "stop a runtime" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed

    val res = for {
      context <- ctx.ask
      internalId <- IO(RuntimeInternalId(UUID.randomUUID.toString))
      testRuntime <- IO(makeCluster(1).copy(internalId = internalId).save())

      _ <- runtimeService.stopRuntime(userInfo, testRuntime.googleProject, testRuntime.runtimeName)
      dbRuntimeOpt <- clusterQuery
        .getActiveClusterByNameMinimal(testRuntime.googleProject, testRuntime.runtimeName)
        .transaction
      dbRuntime = dbRuntimeOpt.get
      message <- publisherQueue.dequeue1
    } yield {
      dbRuntime.status shouldBe RuntimeStatus.Stopping
      val expectedMessage = StopRuntimeMessage(testRuntime.id, Some(context.traceId))
      message shouldBe expectedMessage
    }

    res.unsafeRunSync()
  }

  it should "start a runtime" in isolatedDbTest {
    val userInfo = UserInfo(OAuth2BearerToken(""), WorkbenchUserId("userId"), WorkbenchEmail("user1@example.com"), 0) // this email is white listed

    val res = for {
      context <- ctx.ask
      internalId <- IO(RuntimeInternalId(UUID.randomUUID.toString))
      testRuntime <- IO(makeCluster(1).copy(internalId = internalId, status = RuntimeStatus.Stopped).save())

      _ <- runtimeService.startRuntime(userInfo, testRuntime.googleProject, testRuntime.runtimeName)
      dbRuntimeOpt <- clusterQuery
        .getActiveClusterByNameMinimal(testRuntime.googleProject, testRuntime.runtimeName)
        .transaction
      dbRuntime = dbRuntimeOpt.get
      message <- publisherQueue.dequeue1
    } yield {
      dbRuntime.status shouldBe RuntimeStatus.Starting
      val expectedMessage = StartRuntimeMessage(testRuntime.id, Some(context.traceId))
      message shouldBe expectedMessage
    }

    res.unsafeRunSync()
  }
}