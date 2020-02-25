/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one or more
 *  * contributor license agreements.  See the NOTICE file distributed with
 *  * this work for additional information regarding copyright ownership.
 *  * The ASF licenses this file to You under the Apache License, Version 2.0
 *  * (the "License"); you may not use this file except in compliance with
 *  * the License.  You may obtain a copy of the License at
 *  *
 *  *    http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 *
 */

package com.ebiznext.comet

import java.io.{File, InputStream}
import java.nio.file.Files
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

import com.ebiznext.comet.config.{DatasetArea, Settings}
import com.ebiznext.comet.schema.handlers.{SchemaHandler, SimpleLauncher}
import com.ebiznext.comet.schema.model._
import com.ebiznext.comet.utils.TextSubstitutionEngine
import com.ebiznext.comet.workflow.IngestionWorkflow
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.DefaultSerializerProvider
import com.fasterxml.jackson.databind.{InjectableValues, ObjectMapper}
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.typesafe.config.{
  Config,
  ConfigFactory,
  ConfigParseOptions,
  ConfigResolveOptions,
  ConfigValueFactory
}
import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.TrueFileFilter
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.scalatest.BeforeAndAfterAll

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.io.{Codec, Source}
import scala.util.Try
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

trait TestHelper extends AnyFlatSpec with Matchers with BeforeAndAfterAll with StrictLogging {

  private lazy val cometTestPrefix: String = s"comet-test-${TestHelper.runtimeId}"
  private lazy val cometTestInstanceId: String =
    s"${this.getClass.getSimpleName}-${java.util.UUID.randomUUID()}"

  lazy val cometTestId: String = s"${cometTestPrefix}-${cometTestInstanceId}"
  lazy val cometTestRoot: String = Files.createTempDirectory(cometTestId).toAbsolutePath.toString
  lazy val cometDatasetsPath: String = cometTestRoot + "/datasets"
  lazy val cometMetadataPath: String = cometTestRoot + "/metadata"

  def testConfiguration: Config = {
    val rootConfig = ConfigFactory.parseString(
      s"""
        |COMET_ROOT="${cometTestRoot}"
        |COMET_TEST_ID="${cometTestId}"
        |COMET_DATASETS="${cometDatasetsPath}"
        |COMET_METADATA="${cometMetadataPath}"
        |COMET_TMPDIR="${cometTestRoot}/tmp"
        |COMET_LOCK_PATH="${cometTestRoot}/locks"
        |COMET_METRICS_PATH="${cometTestRoot}/metrics/{domain}/{schema}"
        |COMET_AUDIT_PATH="${cometTestRoot}/audit"
        |
        |include required("application-test.conf")
        |""".stripMargin,
      ConfigParseOptions.defaults().setAllowMissing(false)
    )
    val testConfig =
      ConfigFactory
        .load(rootConfig, ConfigResolveOptions.noSystem())
        .withValue("lock.poll-time", ConfigValueFactory.fromAnyRef("5 ms")) // in local mode we don't need to wait quite as much as we do on a real cluster

    testConfig
  }

  implicit lazy val settings: Settings = Settings(testConfiguration)

  def versionSuffix: String = TestHelperAux.versionSuffix

  val allTypes: List[TypeToImport] = List(
    TypeToImport(
      "default.yml",
      "/sample/default.yml"
    ),
    TypeToImport(
      "types.yml",
      "/sample/types.yml"
    )
  )

  import TestHelperAux.using
  private def readSourceContentAsString(source: Source): String = source.getLines().mkString("\n")

  def loadFile(filename: String)(implicit codec: Codec): String = {
    val stream: InputStream = getClass.getResourceAsStream(filename)
    using(Source.fromInputStream(stream))(readSourceContentAsString)
  }

  def readFileContent(path: String): String =
    using(Source.fromFile(path))(readSourceContentAsString)

  def readFileContent(path: Path): String = readFileContent(path.toUri.getPath)

  /** substitution patterns for test sample file resources.
    *
    */
  private val testResourceSubstitutionEngine = TextSubstitutionEngine(
    "COMET_TEST_ROOT" -> cometTestRoot
  )

  def applyTestFileSubstitutions(fileContent: String): String = {
    testResourceSubstitutionEngine.apply(fileContent)
  }

  def deliverTestFile(importPath: String, targetPath: Path): Unit = {
    val content = loadFile(importPath)
    val testContent = applyTestFileSubstitutions(content)

    storageHandler.write(testContent, targetPath)

    logger.whenTraceEnabled {
      if (content != testContent) {
        logger.trace(s"delivered ${importPath} to ${targetPath.toString}, WITH substitutions")
      } else {
        logger.trace(s"delivered ${importPath} to ${targetPath.toString}")
      }
    }
  }

  def getResPath(path: String): String = getClass.getResource(path).toURI.getPath

  def prepareDateColumns(df: DataFrame): DataFrame = {
    df.withColumn("comet_date", current_date())
      .withColumn("year", year(col("comet_date")))
      .withColumn("month", month(col("comet_date")))
      .withColumn("day", dayofmonth(col("comet_date")))
      .drop("comet_date")
  }

  def prepareSchema(schema: StructType): StructType =
    StructType(schema.fields.filterNot(f => List("year", "month", "day").contains(f.name)))

  def getTodayPartitionPath: String = {
    val now = LocalDate.now
    s"year=${now.getYear}/month=${now.getMonthValue}/day=${now.getDayOfMonth}"
  }

  def cleanMetadata = Try {
    FileUtils
      .listFiles(
        new File(cometMetadataPath),
        TrueFileFilter.INSTANCE,
        TrueFileFilter.INSTANCE
      )
      .asScala
      .map(_.delete())
  }

  def cleanDatasets =
    Try {
      FileUtils
        .listFiles(
          new File(cometDatasetsPath),
          TrueFileFilter.INSTANCE,
          TrueFileFilter.INSTANCE
        )
        .asScala
        .map(_.delete())
    }

  lazy val mapper: ObjectMapper with ScalaObjectMapper = {
    val mapper = new ObjectMapper(new YAMLFactory()) with ScalaObjectMapper
    // provides all of the Scala goodiness
    mapper.registerModule(DefaultScalaModule)
    //mapper.registerModule(new SimpleModule().setMixInAnnotation(classOf[ObjectMapper], classOf[SchemaHandler.MixinsForObjectMapper]))
    mapper.setInjectableValues({
      val iv = new InjectableValues.Std()
      iv.addValue(classOf[Settings], settings)
      iv: InjectableValues
    })

    mapper
  }

  private val sparkSessionInterest = TestHelper.TestSparkSessionInterest()

  lazy val sparkSession = sparkSessionInterest.get

  def storageHandler = settings.storageHandler

  override protected def beforeAll(): Unit = {
    super.beforeAll()

    // Init
    new File(cometTestRoot).mkdirs()
    new File(cometDatasetsPath).mkdir()
    new File(cometMetadataPath).mkdir()
    new File(cometTestRoot + "/DOMAIN").mkdir()
    new File(cometTestRoot + "/dream").mkdir()
    new File(cometTestRoot + "/json").mkdir()
    new File(cometTestRoot + "/position").mkdir()

    allTypes.foreach { typeToImport =>
      val typesPath = new Path(DatasetArea.types, typeToImport.name)
      deliverTestFile(typeToImport.path, typesPath)
    }

    DatasetArea.init(storageHandler)
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    sparkSessionInterest.close()
  }

  trait SpecTrait {

    def schemaHandler = new SchemaHandler(storageHandler)

    final val domainMetadataRootPath: Path = DatasetArea.domains
    val domainFilename: String
    val sourceDomainPathname: String

    val datasetDomainName: String
    val sourceDatasetPathName: String

    protected def init(): Unit = {
      val domainPath = new Path(domainMetadataRootPath, domainFilename)
      deliverTestFile(sourceDomainPathname, domainPath)

      allTypes.foreach { typeToImport =>
        val typesPath = new Path(DatasetArea.types, typeToImport.name)
        deliverTestFile(typeToImport.path, typesPath)
      }

      DatasetArea.initDomains(storageHandler, schemaHandler.domains.map(_.name))

      DatasetArea.init(storageHandler)

    }

    def loadPending(implicit codec: Codec): Unit = {

      init()

      val validator = new IngestionWorkflow(storageHandler, schemaHandler, new SimpleLauncher())

      val targetPath = DatasetArea.path(
        DatasetArea.pending(datasetDomainName),
        new Path(sourceDatasetPathName).getName
      )

      deliverTestFile(sourceDatasetPathName, targetPath)

      validator.loadPending()
    }

  }

  def printDF(df: DataFrame, marker: String) = {
    logger.info(s"Displaying schema for $marker")
    df.printSchema
    logger.info(s"Displaying data for $marker")
    df.show(false)
    logger.info("-----")
  }

}

object TestHelper {

  /**
    * This class manages an interest into having an access to the (effectively global) Test SparkSession
    */
  private case class TestSparkSessionInterest() extends AutoCloseable {
    private val closed = new AtomicBoolean(false)

    TestSparkSession.acquire()

    def get: SparkSession = TestSparkSession.get

    def close(): Unit =
      if (!closed.getAndSet(true)) TestSparkSession.release()
  }

  /**
    * This class manages the lifetime of the SparkSession that is shared among various Suites (instances of TestHelper)
    * that may be running concurrently.
    *
    * @note certain scenarios (such as single-core test execution) can create a window where no TestSparkSessionInterest()
    *       instances exist. In which case, SparkSessions will be closed, destroyed and rebuilt for each Suite.
    */
  private object TestSparkSession extends StrictLogging {

    /**
      * This state machine manages the lifetime of the (effectively global) [[SparkSession]] instance shared between
      * the Suites that inherit from [[TestHelper]].
      *
      * The allowed transitions allow for:
      *   - registration of interest into having access to the SparkSession
      *   - deferred creation of the SparkSession until there is an actual use
      *   - closure of the SparkSession when there is no longer any expressed interest
      *   - re-start of a fresh SparkSession in case additional Suites spin up after closure of the SparkSession
      */
    sealed abstract class State {
      def references: Int
      def acquire: State
      def get: (SparkSession, State)
      def release: State
    }

    object State {
      case object Empty extends State {
        def references: Int = 0

        def acquire: State = Latent(1)

        def release: State =
          throw new IllegalStateException(
            "cannot release a Global Spark Session that was never started"
          )

        override def get: (SparkSession, State) =
          throw new IllegalStateException(
            "cannot get global SparkSession without first acquiring a lease to it"
          ) // can we avoid this?
      }

      final case class Latent(references: Int) extends State {
        def acquire: Latent = Latent(references + 1)
        def release: State = if (references > 1) Latent(references - 1) else Empty

        def get: (SparkSession, Running) = {
          val session =
            SparkSession.builder
              .master("local[*]")
              .getOrCreate

          (session, Running(references, session))
        }
      }

      final case class Running(references: Int, session: SparkSession) extends State {
        override def get: (SparkSession, State) = (session, this)

        override def acquire: State = Running(references + 1, session)
        override def release: State =
          if (references > 1) {
            Running(references - 1, session)
          } else {
            session.close()
            Terminated
          }
      }

      case object Terminated extends State {
        override def references: Int = 0

        override def get: (SparkSession, State) =
          throw new IllegalStateException(
            "cannot get new global SparkSession after one was created then closed"
          )

        override def acquire: State = {
          logger.debug(
            "Terminated SparkInterest sees new acquisition — clearing up old closed SparkSession"
          )
          SparkSession.clearActiveSession()
          SparkSession.clearDefaultSession()

          Empty.acquire
        }

        override def release: State =
          throw new IllegalStateException(
            "cannot release again a Global Spark Session after it was already closed"
          )
      }
    }

    private var state: State = State.Empty

    def get: SparkSession = this.synchronized {
      val (session, nstate) = state.get
      state = nstate
      logger.trace(s"handing out SparkSession instance, now state=${nstate}")
      session
    }

    def acquire(): Unit = this.synchronized {
      val nstate = state.acquire
      logger.trace(s"acquired new interest into SparkSession instance, now state=${nstate}")
      state = nstate
    }

    def release(): Unit = this.synchronized {
      val nstate = state.release
      logger.trace(s"released interest from SparkSession instances, now state=${nstate}")
      state = nstate
    }
  }

  private val runtimeId: String = UUID.randomUUID().toString
}

case class TypeToImport(name: String, path: String)
