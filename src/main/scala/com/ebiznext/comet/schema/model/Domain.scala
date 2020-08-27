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

package com.ebiznext.comet.schema.model

import java.util.regex.Pattern

import com.ebiznext.comet.config.{DatasetArea, Settings}
import com.ebiznext.comet.schema.handlers.SchemaHandler
import org.apache.hadoop.fs.Path

import scala.collection.mutable
import scala.tools.nsc.doc.doclet.Generator

/**
  * Let's say you are willing to import customers and orders from your Sales system.
  * Sales is therefore the domain and customer & order are your datasets.
  * In a DBMS, A Domain would be implemented by a DBMS  schema and a dataset by a DBMS table.
  * In BigQuery, The domain name would be the Big Query dataset name and the dataset would be implemented by a Big Query table.
  *
  * @param name       Domain name. Make sure you use a name that may be used as a folder name on the target storage.
  *                   - When using HDFS or Cloud Storage,  files once ingested are stored in a sub-directory named after the domain name.
  *                   - When used with BigQuery, files are ingested and sorted in tables under a dataset named after the domain name.
  *
  *
  * @param directory  : Folder on the local filesystem where incoming files are stored.
  *                   This folder will be scanned regurlaly to move the dataset to the cluster
  * @param metadata   : Default Schema meta data.
  * @param schemas    : List of schema for each dataset in this domain
  * @param comment    : Free text
  * @param extensions : recognized filename extensions (json, csv, dsv, psv) are recognized by default
  * @param ack        : Ack extension used for each file
  */
case class Domain(
  name: String,
  directory: String,
  metadata: Option[Metadata] = None,
  schemas: List[Schema] = Nil,
  comment: Option[String] = None,
  extensions: Option[List[String]] = None,
  ack: Option[String] = None
) {

  /**
    * Get schema from filename
    * Schema are matched against filenames using filename patterns.
    * The schema pattern thats matches the filename is returned
    *
    * @param filename : dataset filename
    * @return
    */
  def findSchema(filename: String): Option[Schema] = {
    schemas.find(_.pattern.matcher(filename).matches())
  }

  /**
    * Load Elasticsearch template file if it exist
    *
    * @param schema : Schema name to map to an elasticsearch index
    * @return ES template with optinnaly the __PROPERTIES__ string
    *         that will be replaced by the schema attributes dynamically
    *         computed mappings
    */
  def mapping(
    schema: Schema
  )(implicit settings: Settings): Option[String] = {
    val template = new Path(new Path(DatasetArea.mapping, this.name), schema.name + ".json")
    if (settings.storageHandler.exists(template))
      Some(settings.storageHandler.read(template))
    else
      None
  }

  /**
    * List of file extensions to scan for in the domain directory
    *
    * @return the list of extensions of teh default ones : ".json", ".csv", ".dsv", ".psv"
    */
  def getExtensions(): List[String] = {
    extensions.getOrElse(List("json", "csv", "dsv", "psv")).map("." + _)
  }

  /**
    * Ack file should be present for each file to ingest.
    *
    * @return the ack attribute or ".ack" by default
    */
  def getAck(): String = ack.map(ack => if (ack.nonEmpty) "." + ack else ack).getOrElse(".ack")

  /**
    * Is this Domain valid ? A domain is valid if :
    *   - The domain name is a valid attribute
    *   - all the schemas defined in this domain are valid
    *   - No schema is defined twice
    *   - Partitions columns are valid columns
    *   - The input directory is a valid path
    *
    * @return
    */
  def checkValidity(
    schemaHandler: SchemaHandler
  )(implicit settings: Settings): Either[List[String], Boolean] = {
    val errorList: mutable.MutableList[String] = mutable.MutableList.empty

    // Check Domain name validity
    val dbNamePattern = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{1,100}")
    if (!dbNamePattern.matcher(name).matches())
      errorList += s"Domain with name $name should respect the pattern ${dbNamePattern.pattern()}"

    // Check Schemas validity
    schemas.foreach { schema =>
      for (errors <- schema.checkValidity(this.metadata, schemaHandler).left) {
        errorList ++= errors
      }
    }

    // Check Metadata validity
    metadata.foreach { metadata =>
      for (errors <- metadata.checkValidity(schemaHandler).left) {
        errorList ++= errors
      }
    }

    val duplicatesErrorMessage =
      "%s is defined %d times. A schema can only be defined once."
    for (errors <- duplicates(schemas.map(_.name), duplicatesErrorMessage).left) {
      errorList ++= errors
    }

    // TODO Check partition columns

    // TODO Validate directory
    val inputDir = new Path(this.directory)
    if (!settings.storageHandler.exists(inputDir)) {
      errorList += s"$directory not found"
    }
    if (errorList.nonEmpty)
      Left(errorList.toList)
    else
      Right(true)
  }
}
