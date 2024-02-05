package ai.starlake.job.ingest.strategies

import ai.starlake.config.Settings.JdbcEngine
import ai.starlake.schema.model.{MergeOn, StrategyOptions, StrategyType}
import ai.starlake.sql.SQLUtils

class JdbcStrategiesBuilder extends StrategiesBuilder {

  def buildSQLForStrategy(
    strategy: StrategyOptions,
    selectStatement: String,
    fullTableName: String,
    targetTableExists: Boolean,
    targetTableColumns: List[String],
    truncate: Boolean,
    materializedView: Boolean,
    jdbcEngine: JdbcEngine
  ): String = {

    val viewPrefix = jdbcEngine.viewPrefix
    val (sourceTable, tempTable) =
      (
        s"${jdbcEngine.viewPrefix}SL_INCOMING",
        List(s"${createTemporaryView("SL_INCOMING", jdbcEngine)} AS ($selectStatement);")
      )

    val result: String =
      strategy.`type` match {
        case StrategyType.APPEND | StrategyType.OVERWRITE =>
          val quote = jdbcEngine.quote
          val targetColumnsAsSelectString =
            SQLUtils.targetColumnsForSelectSql(targetTableColumns, quote)

          buildMainSql(
            s"SELECT $targetColumnsAsSelectString FROM $sourceTable",
            strategy,
            materializedView,
            targetTableExists,
            truncate,
            fullTableName
          ).mkString(";\n")

        case StrategyType.UPSERT_BY_KEY =>
          buildSqlForMergeByKey(
            sourceTable,
            fullTableName,
            targetTableExists,
            targetTableColumns,
            strategy,
            truncate,
            materializedView,
            jdbcEngine
          )
        case StrategyType.UPSERT_BY_KEY_AND_TIMESTAMP =>
          buildSqlForMergeByKeyAndTimestamp(
            sourceTable,
            fullTableName,
            targetTableExists,
            targetTableColumns,
            strategy,
            truncate,
            materializedView,
            jdbcEngine
          )
        case StrategyType.SCD2 =>
          buildSqlForSC2(
            sourceTable,
            fullTableName,
            targetTableExists,
            targetTableColumns,
            strategy,
            truncate,
            materializedView,
            jdbcEngine
          )
        case StrategyType.OVERWRITE_BY_PARTITION =>
          ""
        case unknownStrategy =>
          throw new Exception(s"Unknown strategy $unknownStrategy")
      }

    val extraPreActions = tempTable.mkString
    s"$extraPreActions\n$result"
  }

  protected def buildSqlForMergeByKey(
    sourceTable: String,
    targetTableFullName: String,
    targetTableExists: Boolean,
    targetTableColumns: List[String],
    strategy: StrategyOptions,
    truncate: Boolean,
    materializedView: Boolean,
    jdbcEngine: JdbcEngine
  ): String = {
    val mergeOn = strategy.on.getOrElse(MergeOn.SOURCE_AND_TARGET)
    val quote = jdbcEngine.quote
    val viewPrefix = jdbcEngine.viewPrefix

    val targetColumnsAsSelectString =
      SQLUtils.targetColumnsForSelectSql(targetTableColumns, quote)

    val mergeKeys =
      strategy.key
        .map(key => s"$quote$key$quote")
        .mkString(",")

    (targetTableExists, mergeOn) match {
      case (false, MergeOn.TARGET) =>
        /*
            The table does not exist, we can just insert the data
         */
        val mainSql = s"""SELECT  $targetColumnsAsSelectString
           |FROM $sourceTable
            """.stripMargin

        buildMainSql(
          mainSql,
          strategy,
          materializedView,
          targetTableExists,
          truncate,
          targetTableFullName
        ).mkString(";\n")

      case (false, MergeOn.SOURCE_AND_TARGET) =>
        /*
            The table does not exist, but we are asked to deduplicate the data from teh input
            We create a temporary table with a row number and select the first row for each partition
            And then we insert the data
         */
        val mainSql = buildMainSql(
          s"SELECT  $targetColumnsAsSelectString  FROM ${viewPrefix}SL_VIEW_WITH_ROWNUM WHERE SL_SEQ = 1",
          strategy,
          materializedView,
          targetTableExists,
          truncate,
          targetTableFullName
        ).mkString(";\n")

        s"""
           |${createTemporaryView("SL_VIEW_WITH_ROWNUM", jdbcEngine)} AS
           |  SELECT  $targetColumnsAsSelectString,
           |          ROW_NUMBER() OVER (PARTITION BY $mergeKeys ORDER BY (select 0)) AS SL_SEQ
           |  FROM $sourceTable;
           |$mainSql
            """.stripMargin
      case (true, MergeOn.TARGET) =>
        /*
            The table exists, we can merge the data
         */
        val paramsForInsertSql = {
          val targetColumns = SQLUtils.targetColumnsForSelectSql(targetTableColumns, quote)
          val sourceColumns =
            SQLUtils.incomingColumnsForSelectSql("SL_INCOMING", targetTableColumns, quote)
          s"""($targetColumns) VALUES ($sourceColumns)"""
        }
        val mergeKeyJoinCondition =
          strategy.key
            .map(key => s"SL_INCOMING.$quote$key$quote = $targetTableFullName.$quote$key$quote")
            .mkString(" AND ")

        val paramsForUpdateSql = SQLUtils.setForUpdateSql("SL_INCOMING", targetTableColumns, quote)

        s"""
           |MERGE INTO $targetTableFullName USING $sourceTable AS SL_INCOMING ON ($mergeKeyJoinCondition)
           |WHEN MATCHED THEN UPDATE $paramsForUpdateSql
           |WHEN NOT MATCHED THEN INSERT $paramsForInsertSql
           |""".stripMargin

      case (true, MergeOn.SOURCE_AND_TARGET) =>
        /*
            The table exists, We deduplicated the data from the input and we merge the data
         */
        val paramsForInsertSql = {
          val targetColumns = SQLUtils.targetColumnsForSelectSql(targetTableColumns, quote)
          val sourceColumns =
            SQLUtils.incomingColumnsForSelectSql(
              s"SL_DEDUP",
              targetTableColumns,
              quote
            )
          s"""($targetColumns) VALUES ($sourceColumns)"""
        }
        val mergeKeyJoinCondition =
          strategy.key
            .map(key => s"SL_DEDUP.$quote$key$quote = $targetTableFullName.$quote$key$quote")
            .mkString(" AND ")

        val paramsForUpdateSql =
          SQLUtils.setForUpdateSql(s"SL_DEDUP", targetTableColumns, quote)

        s"""
           |${createTemporaryView("SL_VIEW_WITH_ROWNUM", jdbcEngine)} AS
           |  SELECT  $targetColumnsAsSelectString,
           |          ROW_NUMBER() OVER (PARTITION BY $mergeKeys  ORDER BY (select 0)) AS SL_SEQ
           |  FROM $sourceTable;
           |
           |CREATE TEMPORARY TABLE SL_DEDUP AS
           |  SELECT  $targetColumnsAsSelectString  FROM ${viewPrefix}SL_VIEW_WITH_ROWNUM WHERE SL_SEQ = 1;
           |
           |MERGE INTO $targetTableFullName USING SL_DEDUP ON ($mergeKeyJoinCondition)
           |WHEN MATCHED THEN UPDATE $paramsForUpdateSql
           |WHEN NOT MATCHED THEN INSERT $paramsForInsertSql
           |""".stripMargin
      case (_, MergeOn(_)) =>
        throw new Exception("Should never happen !!!")

    }
  }
  private def buildSqlForMergeByKeyAndTimestamp(
    sourceTable: String,
    targetTableFullName: String,
    targetTableExists: Boolean,
    targetTableColumns: List[String],
    strategy: StrategyOptions,
    truncate: Boolean,
    materializedView: Boolean,
    jdbcEngine: JdbcEngine
  ): String = {
    val mergeTimestampCol = strategy.timestamp
    val mergeOn = strategy.on.getOrElse(MergeOn.SOURCE_AND_TARGET)
    val quote = jdbcEngine.quote
    val canMerge = jdbcEngine.canMerge
    val viewPrefix = jdbcEngine.viewPrefix

    val targetColumnsAsSelectString =
      SQLUtils.targetColumnsForSelectSql(targetTableColumns, quote)

    val mergeKeys =
      strategy.key
        .map(key => s"$quote$key$quote")
        .mkString(",")

    (targetTableExists, mergeTimestampCol, mergeOn) match {
      case (false, Some(_), MergeOn.TARGET) =>
        /*
            The table does not exist, we can just insert the data
         */

        val mainSql = buildMainSql(
          s"""SELECT  $targetColumnsAsSelectString  FROM $sourceTable""",
          strategy,
          materializedView,
          targetTableExists,
          truncate,
          targetTableFullName
        ).mkString(";\n")
        mainSql
      case (true, Some(mergeTimestampCol), MergeOn.TARGET) =>
        /*
            The table exists, we can merge the data by joining on the key and comparing the timestamp
         */
        val mergeKeyJoinCondition =
          SQLUtils.mergeKeyJoinCondition(sourceTable, targetTableFullName, strategy.key, quote)

        if (canMerge) {
          val paramsForInsertSql = {
            val targetColumns = SQLUtils.targetColumnsForSelectSql(targetTableColumns, quote)
            val sourceColumns =
              SQLUtils.incomingColumnsForSelectSql("SL_INCOMING", targetTableColumns, quote)
            s"""($targetColumns) VALUES ($sourceColumns)"""
          }

          val paramsForUpdateSql =
            SQLUtils.setForUpdateSql("SL_INCOMING", targetTableColumns, quote)

          s"""
             |MERGE INTO $targetTableFullName USING $sourceTable AS SL_INCOMING ON ($mergeKeyJoinCondition)
             |WHEN MATCHED AND SL_INCOMING.$mergeTimestampCol > $targetTableFullName.$mergeTimestampCol THEN UPDATE $paramsForUpdateSql
             |WHEN NOT MATCHED THEN INSERT $paramsForInsertSql
             |""".stripMargin
        } else {
          /*
                We cannot merge, we need to update the data first and then insert the new data
                The merge into comment instruct starlake runner to execute the request as it is.
           */
          val nullJoinCondition =
            strategy.key
              .map(key => s"$targetTableFullName.$quote$key$quote IS NULL")
              .mkString(" AND ")

          val paramsForInsertSql =
            s"""($targetColumnsAsSelectString) VALUES ($targetColumnsAsSelectString)"""

          val paramsForUpdateSql =
            SQLUtils.setForUpdateSql("SL_INCOMING", targetTableColumns, quote)

          val sourceCols = SQLUtils.incomingColumnsForSelectSql(
            s"SL_INCOMING",
            targetTableColumns,
            quote
          )
          s"""
             |UPDATE $targetTableFullName $paramsForUpdateSql
             |FROM $sourceTable AS SL_INCOMING
             |WHERE $mergeKeyJoinCondition AND SL_INCOMING.$mergeTimestampCol > $targetTableFullName.$mergeTimestampCol;
             |
             |/* merge into */
             |INSERT INTO $targetTableFullName$paramsForInsertSql
             |SELECT $sourceCols FROM $sourceTable AS SL_INCOMING
             |LEFT JOIN $targetTableFullName ON ($mergeKeyJoinCondition)
             |WHERE $nullJoinCondition
             |
             |""".stripMargin
        }
      case (false, Some(mergeTimestampCol), MergeOn.SOURCE_AND_TARGET) =>
        /*
            The table does not exist
            We create a temporary table with a row number and select the first row for each partition based on the timestamp
            And then we insert the data
         */
        val mainSql = buildMainSql(
          s"""SELECT  $targetColumnsAsSelectString FROM ${viewPrefix}SL_VIEW_WITH_ROWNUM WHERE SL_SEQ = 1""",
          strategy,
          materializedView,
          targetTableExists,
          truncate,
          targetTableFullName
        ).mkString(";\n")

        s"""
           |${createTemporaryView("SL_VIEW_WITH_ROWNUM", jdbcEngine)} AS
           |  SELECT  $targetColumnsAsSelectString,
           |          ROW_NUMBER() OVER (PARTITION BY $mergeKeys ORDER BY $quote$mergeTimestampCol$quote DESC) AS SL_SEQ
           |  FROM $sourceTable;
           |
           |$mainSql
            """.stripMargin

      case (true, Some(mergeTimestampCol), MergeOn.SOURCE_AND_TARGET) =>
        if (canMerge) {
          val mergeKeyJoinCondition =
            SQLUtils.mergeKeyJoinCondition("SL_INCOMING", targetTableFullName, strategy.key, quote)

          val paramsForInsertSql = {
            val targetColumns = SQLUtils.targetColumnsForSelectSql(targetTableColumns, quote)
            val sourceColumns =
              SQLUtils.incomingColumnsForSelectSql("SL_INCOMING", targetTableColumns, quote)
            s"""($targetColumns) VALUES ($sourceColumns)"""
          }

          val paramsForUpdateSql =
            SQLUtils.setForUpdateSql("SL_INCOMING", targetTableColumns, quote)

          s"""
             |${createTemporaryView("SL_VIEW_WITH_ROWNUM", jdbcEngine)} AS
             |  SELECT  $targetColumnsAsSelectString,
             |          ROW_NUMBER() OVER (PARTITION BY $mergeKeys  ORDER BY (select 0)) AS SL_SEQ
             |  FROM $sourceTable;
             |
             |CREATE TEMPORARY TABLE SL_DEDUP AS
             |  SELECT  $targetColumnsAsSelectString
             |  FROM ${viewPrefix}SL_VIEW_WITH_ROWNUM WHERE SL_SEQ = 1;
             |
             |MERGE INTO $targetTableFullName USING SL_DEDUP AS SL_INCOMING ON ($mergeKeyJoinCondition)
             |WHEN MATCHED AND SL_INCOMING.$mergeTimestampCol > $targetTableFullName.$mergeTimestampCol THEN UPDATE $paramsForUpdateSql
             |WHEN NOT MATCHED THEN INSERT $paramsForInsertSql
             |""".stripMargin
        } else {
          val mergeKeyJoinCondition =
            SQLUtils.mergeKeyJoinCondition(
              s"${viewPrefix}SL_DEDUP",
              targetTableFullName,
              strategy.key,
              quote
            )

          val nullJoinCondition =
            strategy.key
              .map(key => s"$targetTableFullName.$quote$key$quote IS NULL")
              .mkString(" AND ")

          val paramsForUpdateSql =
            SQLUtils.setForUpdateSql(s"${viewPrefix}SL_DEDUP", targetTableColumns, quote)

          val viewWithRownumCols = SQLUtils.incomingColumnsForSelectSql(
            s"${viewPrefix}SL_VIEW_WITH_ROWNUM",
            targetTableColumns,
            quote
          )
          val dedupCols = SQLUtils.incomingColumnsForSelectSql(
            s"${viewPrefix}SL_DEDUP",
            targetTableColumns,
            quote
          )
          s"""
             |${createTemporaryView("SL_VIEW_WITH_ROWNUM", jdbcEngine)} AS
             |  SELECT  $targetColumnsAsSelectString,
             |          ROW_NUMBER() OVER (PARTITION BY $mergeKeys ORDER BY $quote$mergeTimestampCol$quote DESC) AS SL_SEQ
             |  FROM $sourceTable;
             |${createTemporaryView("SL_DEDUP", jdbcEngine)} AS
             |  SELECT  $viewWithRownumCols
             |  FROM ${viewPrefix}SL_VIEW_WITH_ROWNUM WHERE SL_SEQ = 1;
             |
             |UPDATE $targetTableFullName $paramsForUpdateSql
             |FROM ${viewPrefix}SL_DEDUP
             |WHERE $mergeKeyJoinCondition AND ${viewPrefix}SL_DEDUP.$mergeTimestampCol > $targetTableFullName.$mergeTimestampCol;
             |
             |/* merge into */
             |INSERT INTO $targetTableFullName($targetColumnsAsSelectString)
             |SELECT $dedupCols FROM ${viewPrefix}SL_DEDUP
             |LEFT JOIN $targetTableFullName ON ($mergeKeyJoinCondition)
             |WHERE $nullJoinCondition
             |""".stripMargin
        }
      case (_, Some(_), MergeOn(_)) | (_, None, MergeOn(_)) =>
        throw new Exception("Should never happen !!!")
    }
  }

  private def buildSqlForSC2(
    sourceTable: String,
    targetTableFullName: String,
    targetTableExists: Boolean,
    targetTableColumns: List[String],
    strategy: StrategyOptions,
    truncate: Boolean,
    materializedView: Boolean,
    jdbcEngine: JdbcEngine
  ): String = {
    val viewPrefix = jdbcEngine.viewPrefix

    val startTsCol = strategy.start_ts.getOrElse(
      throw new Exception("SCD2 is not supported without a start timestamp column")
    )
    val endTsCol = strategy.end_ts.getOrElse(
      throw new Exception("SCD2 is not supported without an end timestamp column")
    )
    val mergeTimestampCol = strategy.timestamp
    val mergeOn = strategy.on.getOrElse(MergeOn.SOURCE_AND_TARGET)
    val quote = jdbcEngine.quote
    val canMerge = jdbcEngine.canMerge
    val targetColumnsAsSelectString =
      SQLUtils.targetColumnsForSelectSql(targetTableColumns, quote)

    val paramsForInsertSql = {
      val targetColumns = SQLUtils.targetColumnsForSelectSql(targetTableColumns, quote)
      val sourceColumns =
        SQLUtils.incomingColumnsForSelectSql(sourceTable, targetTableColumns, quote)
      s"""($targetColumns) VALUES ($sourceColumns)"""
    }

    val mergeKeys =
      strategy.key
        .map(key => s"$quote$key$quote")
        .mkString(",")

    (targetTableExists, mergeTimestampCol, mergeOn) match {
      case (false, Some(_), MergeOn.TARGET) =>
        /*
            The table does not exist, we can just insert the data
         */
        s"""
           |SELECT  $targetColumnsAsSelectString  FROM $sourceTable
            """.stripMargin
      case (false, Some(mergeTimestampCol), MergeOn.SOURCE_AND_TARGET) =>
        /*
            The table does not exist
            We create a temporary table with a row number and select the first row for each partition based on the timestamp
            And then we insert the data
         */
        s"""
           |${createTemporaryView("SL_VIEW_WITH_ROWNUM", jdbcEngine)} AS
           |  SELECT  $targetColumnsAsSelectString,
           |          ROW_NUMBER() OVER (PARTITION BY $mergeKeys ORDER BY $quote$mergeTimestampCol$quote DESC) AS SL_SEQ
           |  FROM $sourceTable;
           |SELECT  $targetColumnsAsSelectString  FROM ${viewPrefix}SL_VIEW_WITH_ROWNUM WHERE SL_SEQ = 1
            """.stripMargin

      case (true, Some(mergeTimestampCol), MergeOn.TARGET) =>
        /*
            First we insert all new rows
            Then we create a temporary table with the updated rows
            Then we update the end_ts of the old rows
            Then we insert the new rows
                INSERT INTO $targetTable
                SELECT $allAttributesSQL, $mergeTimestampCol AS $startTsCol, NULL AS $endTsCol FROM $sourceTable AS $SL_INTERNAL_TABLE
                WHERE $key IN (SELECT DISTINCT $key FROM SL_UPDATED_RECORDS);
         */
        val mergeKeyJoinCondition =
          SQLUtils.mergeKeyJoinCondition(sourceTable, targetTableFullName, strategy.key, quote)

        val mergeKeyJoinCondition2 =
          SQLUtils.mergeKeyJoinCondition(
            "SL_UPDATED_RECORDS",
            targetTableFullName,
            strategy.key,
            quote
          )

        val nullJoinCondition =
          strategy.key
            .map(key => s"$targetTableFullName.$quote$key$quote IS NULL")
            .mkString(" AND ")

        val paramsForUpdateSql =
          SQLUtils.setForUpdateSql("SL_UPDATED_RECORDS", targetTableColumns, quote)

        s"""
           |INSERT INTO $targetTableFullName
           |SELECT $targetColumnsAsSelectString, NULL AS $startTsCol, NULL AS $endTsCol FROM $sourceTable
           |LEFT JOIN $targetTableFullName ON ($mergeKeyJoinCondition AND $targetTableFullName.$endTsCol IS NULL)
           |WHERE $nullJoinCondition;
           |
           |CREATE TEMPORARY TABLE SL_UPDATED_RECORDS AS
           |SELECT $targetColumnsAsSelectString FROM $sourceTable, $targetTableFullName
           |WHERE $mergeKeyJoinCondition AND $targetTableFullName.$endTsCol IS NULL AND $sourceTable.$mergeTimestampCol > $targetTableFullName.$mergeTimestampCol;
           |
           |MERGE INTO $targetTableFullName USING SL_UPDATED_RECORDS ON ($mergeKeyJoinCondition2)
           |WHEN MATCHED THEN UPDATE $paramsForUpdateSql, $startTsCol = $mergeTimestampCol, $endTsCol = NULL
           |""".stripMargin

      case (true, Some(mergeTimestampCol), MergeOn.SOURCE_AND_TARGET) =>
        /*
            First we insert all new rows
            Then we create a temporary table with the updated rows
            Then we update the end_ts of the old rows
            Then we insert the new rows
                INSERT INTO $targetTable
                SELECT $allAttributesSQL, $mergeTimestampCol AS $startTsCol, NULL AS $endTsCol FROM $sourceTable AS $SL_INTERNAL_TABLE
                WHERE $key IN (SELECT DISTINCT $key FROM SL_UPDATED_RECORDS);
         */
        val mergeKeyJoinCondition =
          SQLUtils.mergeKeyJoinCondition(sourceTable, targetTableFullName, strategy.key, quote)

        val mergeKeyJoinCondition2 =
          SQLUtils.mergeKeyJoinCondition("SL_DEDUP", targetTableFullName, strategy.key, quote)

        val mergeKeyJoinCondition3 =
          SQLUtils.mergeKeyJoinCondition(
            "SL_UPDATED_RECORDS",
            targetTableFullName,
            strategy.key,
            quote
          )

        val nullJoinCondition =
          strategy.key
            .map(key => s"$targetTableFullName.$quote$key$quote IS NULL")
            .mkString(" AND ")

        val paramsForUpdateSql =
          SQLUtils.setForUpdateSql("SL_UPDATED_RECORDS", targetTableColumns, quote)

        s"""
           |INSERT INTO $targetTableFullName
           |SELECT $targetColumnsAsSelectString, NULL AS $startTsCol, NULL AS $endTsCol FROM $sourceTable
           |LEFT JOIN $targetTableFullName ON ($mergeKeyJoinCondition AND $targetTableFullName.$endTsCol IS NULL)
           |WHERE $nullJoinCondition;
           |
           |${createTemporaryView("SL_VIEW_WITH_ROWNUM", jdbcEngine)}  AS
           |  SELECT  $targetColumnsAsSelectString,
           |          ROW_NUMBER() OVER (PARTITION BY $mergeKeys ORDER BY $quote$mergeTimestampCol$quote DESC) AS SL_SEQ
           |  FROM $sourceTable;
           |  
           |${createTemporaryView("SL_DEDUP", jdbcEngine)}  AS
           |SELECT  $targetTableColumns  FROM ${viewPrefix}SL_VIEW_WITH_ROWNUM WHERE SL_SEQ = 1;
           |
           |CREATE TEMPORARY TABLE SL_UPDATED_RECORDS AS
           |SELECT $targetColumnsAsSelectString FROM SL_DEDUP, $targetTableFullName
           |WHERE $mergeKeyJoinCondition2 AND $targetTableFullName.$endTsCol IS NULL AND SL_DEDUP.$mergeTimestampCol > $targetTableFullName.$mergeTimestampCol;
           |
           |MERGE INTO $targetTableFullName USING SL_UPDATED_RECORDS ON ($mergeKeyJoinCondition3)
           |WHEN MATCHED THEN UPDATE $paramsForUpdateSql, $startTsCol = $mergeTimestampCol, $endTsCol = NULL
           |""".stripMargin
      case (_, Some(_), MergeOn(_)) =>
        throw new Exception("Should never happen !!!")
      case (_, None, _) =>
        throw new Exception("SCD2 is not supported without a merge timestamp column")

    }
  }

}
