package org.broadinstitute.dsde.rawls.monitor

import akka.actor.ActorSystem
import akka.testkit.TestKit
import cats.effect.{ContextShift, IO}
import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.rawls.dataaccess.SlickDataSource
import org.broadinstitute.dsde.rawls.dataaccess.slick.TestDriverComponent
import org.broadinstitute.dsde.rawls.entities.local.LocalEntityProvider
import org.broadinstitute.dsde.rawls.model.AttributeName.toDelimitedName
import org.broadinstitute.dsde.rawls.model.{Entity, EntityTypeMetadata}
import org.broadinstitute.dsde.rawls.monitor.EntityStatisticsCacheMonitor.{ScheduleDelayedSweep, Sweep}
import org.broadinstitute.dsde.rawls.util
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.sql.Timestamp
import java.util.Calendar
import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.language.postfixOps

class EntityStatisticsCacheMonitorSpec(_system: ActorSystem) extends TestKit(_system) with MockitoSugar with AnyFlatSpecLike with Matchers with TestDriverComponent with BeforeAndAfterAll with Eventually with ScalaFutures {
  import driver.api._

  val defaultExecutionContext: ExecutionContext = executionContext

  val testConf = ConfigFactory.load()

  def this() = this(ActorSystem("EntityStatisticsCacheMonitorSpec"))

  override def beforeAll(): Unit = {
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }


  "EntityStatisticsCacheMonitor" should "schedule a delayed sweep if the previous sweep was empty" in withLocalEntityProviderTestDatabase { slickDataSource: SlickDataSource =>
    val monitor = new EntityStatisticsCacheMonitor {
      override val dataSource: SlickDataSource = slickDataSource
      override implicit val executionContext: ExecutionContext = defaultExecutionContext
      override val standardPollInterval: FiniteDuration = util.toScalaDuration(testConf.getDuration("entityStatisticsCache.standardPollInterval"))
      override val workspaceCooldown: FiniteDuration = util.toScalaDuration(testConf.getDuration("entityStatisticsCache.workspaceCooldown"))
    }

    //Scenario: there is one workspace in the test data set used for this test. The first sweep should return Sweep,
    // and the second sweep should return ScheduleDelayedSweep since it's caught up
    assertResult(Sweep) {
      Await.result(monitor.sweep(), Duration.Inf)
    }

    assertResult(ScheduleDelayedSweep) {
      Await.result(monitor.sweep(), Duration.Inf)
    }
  }

  it should "continue to immediately sweep if the last sweep was not empty" in withLocalEntityProviderTestDatabase { slickDataSource: SlickDataSource =>
    val monitor = new EntityStatisticsCacheMonitor {
      override val dataSource: SlickDataSource = slickDataSource
      override implicit val executionContext: ExecutionContext = defaultExecutionContext
      override val standardPollInterval: FiniteDuration = util.toScalaDuration(testConf.getDuration("entityStatisticsCache.standardPollInterval"))
      override val workspaceCooldown: FiniteDuration = util.toScalaDuration(testConf.getDuration("entityStatisticsCache.workspaceCooldown"))
    }

    //Scenario: there is one workspace in the test data set used for this test. The first time we sweep,
    // the monitor will update that workspace and return Sweep
    assertResult(Sweep) {
      Await.result(monitor.sweep(), Duration.Inf)
    }
  }

  it should "delay the next sweep if the last sweep was empty due to cooldown" in withLocalEntityProviderTestDatabase { slickDataSource: SlickDataSource =>
    // default workspaceCooldown in src/test/reference.conf is 0 minutes; override it here
    // so that the sweep doesn't find a workspace whose last_modified date satisfies the cooldown
    val monitor = new EntityStatisticsCacheMonitor {
      override val dataSource: SlickDataSource = slickDataSource
      override implicit val executionContext: ExecutionContext = defaultExecutionContext
      override val standardPollInterval: FiniteDuration = util.toScalaDuration(testConf.getDuration("entityStatisticsCache.standardPollInterval"))
      override val workspaceCooldown: FiniteDuration = Duration(5, TimeUnit.MINUTES)
    }

    //Scenario: there is one workspace in the test data set used for this test. Because it was saved to the db
    // as part of test setup, its lastModified date is recent, and this sweep will not find it within
    // the 5-minute cooldown we specified.
    assertResult(ScheduleDelayedSweep) {
      Await.result(monitor.sweep(), Duration.Inf)
    }
  }

  it should "update the cache for a workspace after Sweeping if the cache was out of date" in withLocalEntityProviderTestDatabase { slickDataSource: SlickDataSource =>
    val monitor = new EntityStatisticsCacheMonitor {
      override val dataSource: SlickDataSource = slickDataSource
      override implicit val executionContext: ExecutionContext = defaultExecutionContext
      override val standardPollInterval: FiniteDuration = util.toScalaDuration(testConf.getDuration("entityStatisticsCache.standardPollInterval"))
      override val workspaceCooldown: FiniteDuration = util.toScalaDuration(testConf.getDuration("entityStatisticsCache.workspaceCooldown"))
    }

    val workspaceContext = runAndWait(slickDataSource.dataAccess.workspaceQuery.findById(localEntityProviderTestData.workspace.workspaceId)).get
    val localEntityProvider = new LocalEntityProvider(workspaceContext, slickDataSource, cacheEnabled = true)

    //Update the entityCacheLastUpdated field to be identical to lastModified, so we can test our scenario of having a fresh cache
    runAndWait(workspaceQuery.updateCacheLastUpdated(workspaceContext.workspaceIdAsUUID, new Timestamp(workspaceContext.lastModified.getMillis)))

    //Load the current cache entries
    val originalCache = Await.result(localEntityProvider.entityTypeMetadata(true), Duration.Inf)

    //Save a new entity to the workspace
    val newEntity = Entity("new-entity-name", "new-entity-tpe", Map.empty)
    Await.result(localEntityProvider.createEntity(newEntity), Duration.Inf)

    //Force the monitor to sweep and update the oldest stale cache
    //In this scenario, it's the only workspace so we know it will be picked up
    assertResult(Sweep) {
      Await.result(monitor.sweep(), Duration.Inf)
    }

    //Load the latest cache
    val newCache = Await.result(localEntityProvider.entityTypeMetadata(true), Duration.Inf)

    //Assert that the new cache is different than the old one and contains the new entity type that we added earlier
    originalCache should not be newCache
    assert(newCache.contains(newEntity.entityType))
  }

  it should "entityMetadata should return the same results before and after the monitor Sweeps to update the cache" in withLocalEntityProviderTestDatabase { slickDataSource: SlickDataSource =>
    val monitor = new EntityStatisticsCacheMonitor {
      override val dataSource: SlickDataSource = slickDataSource
      override implicit val executionContext: ExecutionContext = defaultExecutionContext
      override val standardPollInterval: FiniteDuration = util.toScalaDuration(testConf.getDuration("entityStatisticsCache.standardPollInterval"))
      override val workspaceCooldown: FiniteDuration = util.toScalaDuration(testConf.getDuration("entityStatisticsCache.workspaceCooldown"))
    }

    val workspaceContext = runAndWait(slickDataSource.dataAccess.workspaceQuery.findById(localEntityProviderTestData.workspace.workspaceId)).get
    val localEntityProvider = new LocalEntityProvider(workspaceContext, slickDataSource, cacheEnabled = true)

    //Update the entityCacheLastUpdated field to be older than lastModified, so we can test our scenario of having a stale cache
    runAndWait(workspaceQuery.updateCacheLastUpdated(workspaceContext.workspaceIdAsUUID, new Timestamp(workspaceContext.lastModified.getMillis - 1)))

    //Load the current entityMetadata (which should not use the cache)
    val originalResult = Await.result(localEntityProvider.entityTypeMetadata(true), Duration.Inf)

    //Make sure that the timestamps do not match
    val lastModifiedOriginal = runAndWait(workspaceQuery.findByIdQuery(workspaceContext.workspaceIdAsUUID).result).head.lastModified
    val entityCacheLastUpdatedOriginal = runAndWait(workspaceQuery.findByIdQuery(workspaceContext.workspaceIdAsUUID).result).head.entityCacheLastUpdated

    assert(lastModifiedOriginal.after(entityCacheLastUpdatedOriginal))

    //Force the monitor to sweep and update the oldest stale cache
    //In this scenario, it's the only workspace so we know it will be picked up
    assertResult(Sweep) {
      Await.result(monitor.sweep(), Duration.Inf)
    }

    //Load the latest entityMetadata, which should use the cache
    val latestResult = Await.result(localEntityProvider.entityTypeMetadata(true), Duration.Inf)

    //Assert that the new cache is different than the old one and contains the new entity type that we added earlier
    originalResult shouldBe latestResult

    //Make sure that the timestamps now match
    val lastModified = runAndWait(workspaceQuery.findByIdQuery(workspaceContext.workspaceIdAsUUID).result).head.lastModified
    val entityCacheLastUpdated = runAndWait(workspaceQuery.findByIdQuery(workspaceContext.workspaceIdAsUUID).result).head.entityCacheLastUpdated

    lastModified shouldBe entityCacheLastUpdated
  }

  List(0, 1, 10, 180) foreach { mins =>
    it should s"properly calculate now minus a duration ($mins minutes)" in {
      val monitor = new EntityStatisticsCacheMonitor {
        override val dataSource: SlickDataSource = slickDataSource
        override implicit val executionContext: ExecutionContext = defaultExecutionContext
        override val standardPollInterval: FiniteDuration = util.toScalaDuration(testConf.getDuration("entityStatisticsCache.standardPollInterval"))
        override val workspaceCooldown: FiniteDuration = util.toScalaDuration(testConf.getDuration("entityStatisticsCache.workspaceCooldown"))
      }

      val duration = Duration(mins, TimeUnit.MINUTES)
      val now = Calendar.getInstance.getTime.getTime
      val earlier = monitor.nowMinus(duration).getTime
      val expectedDiff = duration.toSeconds*1000

      // we calculate "now" here in the test, but then again inside the monitor's nowMinus method;
      // allow for a minor time difference of 1 second since those "now" values won't be
      // exactly equal.
      val lenience = 1000

      assert( expectedDiff - (now - earlier) <= lenience,
        s"[CLUE: nowMinus calculated a diff of ${now - earlier} ms, " +
          s"but expected a diff of ${expectedDiff-lenience}-$expectedDiff ms]" )

    }
  }

}
