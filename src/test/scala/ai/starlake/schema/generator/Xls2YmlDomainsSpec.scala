package ai.starlake.schema.generator

import ai.starlake.TestHelper
import ai.starlake.config.DatasetArea
import ai.starlake.schema.model.{BigQuerySink, Domain, Format, Schema}
import ai.starlake.utils.YamlSerializer
import better.files.File

import scala.util.{Failure, Success}

class Xls2YmlDomainsSpec extends TestHelper {
  new WithSettings() {
    Xls2Yml.writeDomainsAsYaml(
      File(getClass.getResource("/sample/SomeDomainTemplate.xls")).pathAsString
    )
    val outputPath = File(DatasetArea.load.toString + "/someDomain/_config.comet.yml")
    val schema1Path = File(DatasetArea.load.toString + "/someDomain/SCHEMA1.comet.yml")
    val schema2Path = File(DatasetArea.load.toString + "/someDomain/SCHEMA2.comet.yml")

    val result: Domain = YamlSerializer
      .deserializeDomain(outputPath.contentAsString, outputPath.pathAsString) match {
      case Success(value)     => value
      case Failure(exception) => throw exception
    }

    val schema1: Schema = YamlSerializer
      .deserializeSchemaRefs(schema1Path.contentAsString, schema1Path.pathAsString)
      .tables
      .head

    val schema2: Schema = YamlSerializer
      .deserializeSchemaRefs(schema2Path.contentAsString, schema2Path.pathAsString)
      .tables
      .head

    "Parsing a sample xlsx file" should "generate a yml file" in {
      outputPath.exists shouldBe true
      result.name shouldBe "someDomain"
    }

    it should "take into account the index col of a schema" in {
      val sink = for {
        schema   <- Some(schema1)
        metadata <- schema.metadata
        sink     <- metadata.sink
      } yield sink

      sink.map(_.getSink()) shouldBe Some(
        BigQuerySink(Some("BQ"), None, None, None, None, None, None, None)
      )
    }

    "All configured schemas" should "have all declared attributes correctly set" in {
      schema1.metadata.flatMap(_.format) shouldBe Some(Format.POSITION)
      schema1.metadata.flatMap(_.encoding) shouldBe Some("UTF-8")
      schema1.attributes.size shouldBe 19
      schema1.merge.flatMap(_.timestamp) shouldBe Some("ATTRIBUTE_1")
      schema1.merge.map(_.key) shouldBe Some(List("ID1", "ID2"))

      val s1MaybePartitions = schema1.metadata.flatMap(_.partition)
      s1MaybePartitions
        .map(_.attributes)
        .get
        .sorted shouldEqual List("comet_day", "comet_hour", "comet_month", "comet_year")
      s1MaybePartitions
        .flatMap(_.sampling)
        .get shouldEqual 10.0

      schema2.metadata.flatMap(_.format) shouldBe Some(Format.DSV)
      schema2.metadata.flatMap(_.encoding) shouldBe Some("ISO-8859-1")
      schema2.attributes.size shouldBe 19

      val s2MaybePartitions = schema2.metadata.flatMap(_.partition)
      s2MaybePartitions
        .map(_.attributes)
        .get
        .sorted shouldEqual List("RENAME_ATTRIBUTE_8", "RENAME_ATTRIBUTE_9")
      s2MaybePartitions
        .flatMap(_.sampling)
        .get shouldEqual 0.0
    }

    val reader = new XlsDomainReader(
      InputPath(getClass.getResource("/sample/SomeDomainTemplate.xls").getPath)
    )
    val domainOpt = reader.getDomain()

    "a complex XLS (aka JSON/XML)" should "produce the correct schema" in {
      val complexReader =
        new XlsDomainReader(
          InputPath(
            File(getClass.getResource("/sample/SomeComplexDomainTemplate.xls")).pathAsString
          )
        )
      val xlsTable = complexReader.getDomain().get.tables.head
      val domainAsYaml = YamlSerializer.serialize(complexReader.getDomain().get)
      val yamlPath =
        File(getClass.getResource("/sample/SomeComplexDomainTemplate.comet.yml"))

      val yamlTable = YamlSerializer
        .deserializeDomain(yamlPath.contentAsString, yamlPath.pathAsString)
        .getOrElse(throw new Exception(s"Invalid file name $yamlPath"))
        .tables
        .head

      xlsTable.attributes.length shouldBe yamlTable.attributes.length

      deepEquals(xlsTable.attributes, yamlTable.attributes)
    }

    "a preEncryption domain" should "have only string types" in {
      domainOpt shouldBe defined
      val preEncrypt = Xls2Yml.genPreEncryptionDomain(domainOpt.get, Nil)
      preEncrypt.tables.flatMap(_.attributes).filter(_.`type` != "string") shouldBe empty
    }

    "Merge and Partition elements" should "only be present in Post-Encryption domain" in {
      domainOpt shouldBe defined
      val preEncrypt = Xls2Yml.genPreEncryptionDomain(domainOpt.get, Nil)
      preEncrypt.tables.flatMap(_.metadata.map(_.partition)).forall(p => p.isEmpty) shouldBe true
      preEncrypt.tables.map(_.merge).forall(m => m.isEmpty) shouldBe true
      val postEncrypt = Xls2Yml.genPostEncryptionDomain(domainOpt.get, None, Nil)
      postEncrypt.tables
        .flatMap(_.metadata.map(_.partition))
        .forall(p => p.isDefined) shouldBe true
      postEncrypt.tables.map(_.merge).forall(m => m.isDefined) shouldBe true

    }

    "Column Description in schema" should "be present" in {
      domainOpt shouldBe defined
      domainOpt.get.tables.flatMap(_.comment) should have length 1
    }

    private def validCount(domain: Domain, algo: String, count: Int) =
      domain.tables
        .flatMap(_.attributes)
        .filter(_.getPrivacy().toString == algo) should have length count

    "SHA1 & HIDE privacy policies" should "be applied in the pre-encrypt step " in {
      domainOpt shouldBe defined
      val preEncrypt = Xls2Yml.genPreEncryptionDomain(domainOpt.get, List("HIDE", "SHA1"))
      validCount(preEncrypt, "HIDE", 2)
      validCount(preEncrypt, "MD5", 0)
      validCount(preEncrypt, "SHA1", 1)
    }
    "All privacy policies" should "be applied in the pre-encrypt step " in {
      domainOpt shouldBe defined
      val preEncrypt = Xls2Yml.genPreEncryptionDomain(domainOpt.get, Nil)
      validCount(preEncrypt, "HIDE", 2)
      validCount(preEncrypt, "MD5", 2)
      validCount(preEncrypt, "SHA1", 1)
    }
    "In prestep Attributes" should "not be renamed" in {
      domainOpt shouldBe defined
      val preEncrypt = Xls2Yml.genPreEncryptionDomain(domainOpt.get, Nil)
      val schemaOpt = preEncrypt.tables.find(_.name == "SCHEMA1")
      schemaOpt shouldBe defined
      val attrOpt = schemaOpt.get.attributes.find(_.name == "ATTRIBUTE_6")
      attrOpt shouldBe defined
      attrOpt.get.rename shouldBe None
    }

    "In poststep Attributes" should "keep renaming strategy" in {
      domainOpt shouldBe defined
      val postEncrypt = Xls2Yml.genPostEncryptionDomain(domainOpt.get, Some("µ"), Nil)
      val schemaOpt = postEncrypt.tables.find(_.name == "SCHEMA1")
      schemaOpt shouldBe defined
      val attrOpt = schemaOpt.get.attributes.find(_.name == "ATTRIBUTE_6")
      attrOpt shouldBe defined
      attrOpt.get.rename shouldBe defined
      attrOpt.get.rename.get shouldBe "RENAME_ATTRIBUTE_6"

    }
    "No privacy policies" should "be applied in the post-encrypt step " in {
      domainOpt shouldBe defined
      val postEncrypt = Xls2Yml.genPostEncryptionDomain(domainOpt.get, Some("µ"), Nil)
      validCount(postEncrypt, "HIDE", 0)
      validCount(postEncrypt, "MD5", 0)
      validCount(postEncrypt, "SHA1", 0)
    }

    "a preEncryption domain" should " not have required attributes" in {
      domainOpt shouldBe defined
      val preEncrypt = Xls2Yml.genPreEncryptionDomain(domainOpt.get, Nil)
      preEncrypt.tables.flatMap(_.attributes).filter(_.required) shouldBe empty
    }

    "a postEncryption domain" should "have not have POSITION schemas" in {
      domainOpt shouldBe defined
      domainOpt.get.tables
        .flatMap(_.metadata)
        .count(_.format.contains(Format.POSITION)) shouldBe 1
      val postEncrypt =
        Xls2Yml.genPostEncryptionDomain(domainOpt.get, Some("µ"), List("HIDE", "SHA1"))
      postEncrypt.tables
        .flatMap(_.metadata)
        .filter(_.format.contains(Format.POSITION)) shouldBe empty
      validCount(postEncrypt, "HIDE", 0)
      validCount(postEncrypt, "MD5", 2)
      validCount(postEncrypt, "SHA1", 0)
    }
    "a custom separator" should "be generated" in {
      domainOpt shouldBe defined
      domainOpt.get.tables
        .flatMap(_.metadata)
        .count(_.format.contains(Format.POSITION)) shouldBe 1
      val postEncrypt = Xls2Yml.genPostEncryptionDomain(domainOpt.get, Some(","), Nil)
      postEncrypt.tables
        .flatMap(_.metadata)
        .filterNot(_.separator.contains(",")) shouldBe empty
    }

    "a scripted attribute" should "be generated" in {
      domainOpt shouldBe defined
      domainOpt
        .flatMap(_.tables.find(_.name == "SCHEMA1"))
        .flatMap(_.attributes.find(_.name == "ATTRIBUTE_4").flatMap(_.script)) shouldBe Some(
        "current_date()"
      )
    }

    "All SchemaGen Config" should "be known and taken  into account" in {
      val rendered = Xls2YmlConfig.usage()
      val expected =
        """
          |Usage: starlake xls2yml [options]
          |
          |  --files <value>       List of Excel files describing Domains & Schemas OR Jobs
          |  --encryption <value>  If true generate pre and post encryption YML
          |  --iamPolicyTagsFile <value>
          |                        If true generate IAM PolicyTags YML
          |  --delimiter <value>   CSV delimiter to use in post-encrypt YML.
          |  --privacy <value>     What privacy policies should be applied in the pre-encryption phase ?
          | All privacy policies are applied by default.
          |  --outputPath <value>  Path for saving the resulting YAML file(s).
          | Comet domains path is used by default.
          |  --policyFile <value>  Optional File for centralising ACL & RLS definition.
          |  --job <value>         If true generate YML for a Job.
          |""".stripMargin
      rendered.substring(rendered.indexOf("Usage:")).replaceAll("\\s", "") shouldEqual expected
        .replaceAll("\\s", "")

    }
  }

}