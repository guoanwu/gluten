/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.glutenproject.execution

import io.glutenproject.GlutenConfig
import io.glutenproject.utils.UTSystemParameters

import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.execution.adaptive.AdaptiveSparkPlanHelper
import org.apache.spark.sql.execution.datasources.v2.clickhouse.ClickHouseLog
import org.apache.spark.sql.hive.HiveTableScanExecTransformer

import org.apache.commons.io.FileUtils
import org.apache.hadoop.fs.Path

import java.io.File
import java.sql.Timestamp

case class AllDataTypesWithComplextType(
    string_field: String = null,
    int_field: java.lang.Integer = null,
    long_field: java.lang.Long = null,
    float_field: java.lang.Float = null,
    double_field: java.lang.Double = null,
    short_field: java.lang.Short = null,
    byte_field: java.lang.Byte = null,
    boolean_field: java.lang.Boolean = null,
    decimal_field: java.math.BigDecimal = null,
    date_field: java.sql.Date = null,
    timestamp_field: java.sql.Timestamp = null,
    array: Seq[Int] = null,
    arrayContainsNull: Seq[Option[Int]] = null,
    map: Map[Int, Long] = null,
    mapValueContainsNull: Map[Int, Option[Long]] = null
)

class GlutenClickHouseHiveTableSuite()
  extends GlutenClickHouseTPCHAbstractSuite
  with AdaptiveSparkPlanHelper {

  private var _hiveSpark: SparkSession = _

  override protected val resourcePath: String =
    "../../../../gluten-core/src/test/resources/tpch-data"

  override protected val tablesPath: String = basePath + "/tpch-data"
  override protected val tpchQueries: String =
    rootPath + "../../../../gluten-core/src/test/resources/tpch-queries"
  override protected val queriesResults: String = rootPath + "queries-output"
  override protected def createTPCHNullableTables(): Unit = {}

  override protected def createTPCHNotNullTables(): Unit = {}

  override protected def sparkConf: SparkConf = {
    new SparkConf()
      .set("spark.plugins", "io.glutenproject.GlutenPlugin")
      .set("spark.memory.offHeap.enabled", "true")
      .set("spark.memory.offHeap.size", "536870912")
      .set("spark.sql.catalogImplementation", "hive")
      .set("spark.sql.adaptive.enabled", "true")
      .set("spark.sql.files.maxPartitionBytes", "1g")
      .set("spark.serializer", "org.apache.spark.serializer.JavaSerializer")
      .set("spark.sql.shuffle.partitions", "5")
      .set("spark.sql.adaptive.enabled", "false")
      .set("spark.sql.files.minPartitionNum", "1")
      .set(
        "spark.sql.catalog.spark_catalog",
        "org.apache.spark.sql.execution.datasources.v2.clickhouse.ClickHouseSparkCatalog")
      .set("spark.databricks.delta.maxSnapshotLineageLength", "20")
      .set("spark.databricks.delta.snapshotPartitions", "1")
      .set("spark.databricks.delta.properties.defaults.checkpointInterval", "5")
      .set("spark.databricks.delta.stalenessLimit", "3600000")
      .set("spark.gluten.sql.columnar.columnartorow", "true")
      .set("spark.gluten.sql.columnar.backend.ch.worker.id", "1")
      .set(GlutenConfig.GLUTEN_LIB_PATH, UTSystemParameters.getClickHouseLibPath())
      .set("spark.gluten.sql.columnar.iterator", "true")
      .set("spark.gluten.sql.columnar.hashagg.enablefinal", "true")
      .set("spark.gluten.sql.enable.native.validation", "false")
      .set("spark.gluten.sql.columnar.forceshuffledhashjoin", "true")
      .set(
        "spark.sql.warehouse.dir",
        getClass.getResource("/").getPath + "unit-tests-working-home/spark-warehouse")
      .set("spark.hive.exec.dynamic.partition.mode", "nonstrict")
      .setMaster("local[*]")
  }

  override protected def spark: SparkSession = _hiveSpark

  override protected def initializeSession(): Unit = {
    if (_hiveSpark == null) {
      val hiveMetaStoreDB = metaStorePathAbsolute + "/metastore_db"
      _hiveSpark = SparkSession
        .builder()
        .config(sparkConf)
        .enableHiveSupport()
        .config(
          "javax.jdo.option.ConnectionURL",
          s"jdbc:derby:;databaseName=$hiveMetaStoreDB;create=true")
        .getOrCreate()
    }
  }

  private val txt_table_name = "hive_txt_test"
  private val txt_user_define_input = "hive_txt_user_define_input"
  private val json_table_name = "hive_json_test"

  private val txt_table_create_sql = "create table if not exists %s (".format(txt_table_name) +
    "string_field string," +
    "int_field int," +
    "long_field long," +
    "float_field float," +
    "double_field double," +
    "short_field short," +
    "byte_field byte," +
    "bool_field boolean," +
    "decimal_field decimal(23, 12)," +
    "date_field date," +
    "timestamp_field timestamp," +
    "array_field array<int>," +
    "array_field_with_null array<int>," +
    "map_field map<int, long>," +
    "map_field_with_null map<int, long>) stored as textfile"
  private val json_table_create_sql = "create table if not exists %s (".format(json_table_name) +
    "string_field string," +
    "int_field int," +
    "long_field long," +
    "float_field float," +
    "double_field double," +
    "short_field short," +
    "byte_field byte," +
    "bool_field boolean," +
    "decimal_field decimal(23, 12)," +
    "date_field date," +
    "timestamp_field timestamp," +
    "array_field array<int>," +
    "array_field_with_null array<int>," +
    "map_field map<int,long>," +
    "map_field_with_null map<int,long>, " +
    "day string) partitioned by(day)" +
    "ROW FORMAT SERDE 'org.apache.hive.hcatalog.data.JsonSerDe'" +
    "STORED AS INPUTFORMAT 'org.apache.hadoop.mapred.TextInputFormat'" +
    "OUTPUTFORMAT 'org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat'"
  private val txt_table_user_define_create_sql =
    "create table if not exists %s (".format(txt_user_define_input) +
      "string_field string," +
      "int_field int," +
      "long_field long," +
      "float_field float," +
      "double_field double," +
      "short_field short," +
      "byte_field byte," +
      "bool_field boolean," +
      "decimal_field decimal(23, 12)," +
      "date_field date," +
      "timestamp_field timestamp," +
      "array_field array<int>," +
      "array_field_with_null array<int>," +
      "map_field map<int, long>," +
      "map_field_with_null map<int, long>) " +
      "STORED AS INPUTFORMAT 'org.apache.hadoop.mapred.UserDefineTextInputFormat'" +
      "OUTPUTFORMAT 'org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat'"

  def genTestData(): Seq[AllDataTypesWithComplextType] = {
    (0 to 199).map {
      i =>
        if (i % 100 == 1) {
          AllDataTypesWithComplextType()
        } else {
          AllDataTypesWithComplextType(
            s"$i",
            i,
            i.toLong,
            i.toFloat,
            i.toDouble,
            i.toShort,
            i.toByte,
            i % 2 == 0,
            new java.math.BigDecimal(i + ".56"),
            new java.sql.Date(System.currentTimeMillis()),
            new Timestamp(System.currentTimeMillis()),
            Seq.apply(i + 1, i + 2, i + 3),
            Seq.apply(Option.apply(i + 1), Option.empty, Option.apply(i + 3)),
            Map.apply((i + 1, i + 2), (i + 3, i + 4)),
            Map.empty
          )
        }
    }
  }

  protected def initializeTable(
      table_name: String,
      table_create_sql: String,
      partition: String): Unit = {
    spark.createDataFrame(genTestData()).createOrReplaceTempView("tmp_t")
    val truncate_sql = "truncate table %s".format(table_name)
    val drop_sql = "drop table if exists %s".format(table_name)
    spark.sql(drop_sql)
    spark.sql(table_create_sql)
    spark.sql(truncate_sql)
    if (partition != null) {
      spark.sql("insert into %s select *, %s from tmp_t".format(table_name, partition))
    } else {
      spark.sql("insert into %s select * from tmp_t".format(table_name))
    }
  }

  override def beforeAll(): Unit = {
    // prepare working paths
    val basePathDir = new File(basePath)
    if (basePathDir.exists()) {
      FileUtils.forceDelete(basePathDir)
    }
    FileUtils.forceMkdir(basePathDir)
    FileUtils.forceMkdir(new File(warehouse))
    FileUtils.forceMkdir(new File(metaStorePathAbsolute))
    FileUtils.copyDirectory(new File(rootPath + resourcePath), new File(tablesPath))
    super.beforeAll()
    initializeTable(txt_table_name, txt_table_create_sql, null)
    initializeTable(txt_user_define_input, txt_table_user_define_create_sql, null)
    initializeTable(json_table_name, json_table_create_sql, "2023-06-05")
  }

  override protected def afterAll(): Unit = {
    ClickHouseLog.clearCache()

    try {
      super.afterAll()
    } finally {
      try {
        if (_hiveSpark != null) {
          try {
            _hiveSpark.sessionState.catalog.reset()
          } finally {
            _hiveSpark.stop()
            _hiveSpark = null
          }
        }
      } finally {
        SparkSession.clearActiveSession()
        SparkSession.clearDefaultSession()
      }
    }
  }

  test("test hive text table") {
    val sql =
      s"""
         | select string_field,
         |        sum(int_field),
         |        avg(long_field),
         |        min(float_field),
         |        max(double_field),
         |        sum(short_field),
         |        sum(decimal_field)
         | from $txt_table_name
         | group by string_field
         | order by string_field
         |""".stripMargin
    compareResultsAgainstVanillaSpark(
      sql,
      true,
      df => {
        val txtFileScan = collect(df.queryExecution.executedPlan) {
          case l: HiveTableScanExecTransformer => l
        }
        assert(txtFileScan.size == 1)
      })
  }

  test("test hive text table using user define input format") {
    val sql =
      s"""
         | select string_field,
         |        sum(int_field),
         |        avg(long_field),
         |        min(float_field),
         |        max(double_field),
         |        sum(short_field),
         |        sum(decimal_field)
         | from $txt_user_define_input
         | group by string_field
         | order by string_field
         |""".stripMargin
    compareResultsAgainstVanillaSpark(
      sql,
      true,
      df => {
        val txtFileScan = collect(df.queryExecution.executedPlan) {
          case l: HiveTableScanExecTransformer => l
        }
        assert(txtFileScan.size == 1)
      })
  }

  test("test hive text table with unordered columns") {
    val sql = "select decimal_field, short_field, double_field, float_field, long_field, " +
      s"int_field, string_field from $txt_table_name order by string_field"
    compareResultsAgainstVanillaSpark(
      sql,
      true,
      df => {
        val txtFileScan = collect(df.queryExecution.executedPlan) {
          case l: HiveTableScanExecTransformer => l
        }
        assert(txtFileScan.size == 1)
      })
  }

  test("test hive text table with count(1)/count(*)") {
    val sql1 = s"select count(1), count(*) from $txt_table_name"
    compareResultsAgainstVanillaSpark(
      sql1,
      true,
      df => {
        val txtFileScan = collect(df.queryExecution.executedPlan) {
          case l: HiveTableScanExecTransformer => l
        }
        assert(txtFileScan.size == 1)
      })

    val sql2 = s"select count(*) from $txt_table_name where int_field >= 100"
    compareResultsAgainstVanillaSpark(
      sql2,
      true,
      df => {
        val txtFileScan = collect(df.queryExecution.executedPlan) {
          case l: HiveTableScanExecTransformer => l
        }
        assert(txtFileScan.size == 1)
      })
  }

  test("fix bug: https://github.com/oap-project/gluten/issues/2022") {
    spark.sql(
      "create table if not exists test_empty_partitions" +
        "(uid string, mac string, country string) partitioned by (day string) stored as textfile")

    var sql = "select * from test_empty_partitions where day = '2023-01-01';"
    compareResultsAgainstVanillaSpark(
      sql,
      true,
      df => {
        val txtFileScan = collect(df.queryExecution.executedPlan) {
          case l: HiveTableScanExecTransformer => l
        }
        assert(txtFileScan.size == 1)
      })
  }

  test("fix bug: hive text table limit with fallback") {
    val sql =
      s"""
         | select string_field
         | from $txt_table_name
         | order by string_field
         | limit 10
         |""".stripMargin
    compareResultsAgainstVanillaSpark(
      sql,
      true,
      df => {
        val txtFileScan = collect(df.queryExecution.executedPlan) {
          case l: HiveTableScanExecTransformer => l
        }
        assert(txtFileScan.size == 1)
      })
  }

  test("hive text table select complex type columns with fallback") {
    val sql = s"select int_field, array_field, map_field from $txt_table_name order by int_field"
    compareResultsAgainstVanillaSpark(sql, true, { _ => }, false)
  }

  test("hive text table case-insensitive column matching") {
    val sql = s"select SHORT_FIELD, int_field, LONG_field from $txt_table_name order by int_field"
    compareResultsAgainstVanillaSpark(
      sql,
      true,
      df => {
        val txtFileScan = collect(df.queryExecution.executedPlan) {
          case l: HiveTableScanExecTransformer => l
        }
        assert(txtFileScan.size == 1)
      })
  }

  test("test hive json table") {
    val sql =
      s"""
         | select string_field,
         |        sum(int_field),
         |        avg(long_field),
         |        min(float_field),
         |        max(double_field),
         |        sum(short_field),
         |        sum(decimal_field)
         | from $json_table_name
         | group by string_field
         | order by string_field
         |""".stripMargin
    compareResultsAgainstVanillaSpark(
      sql,
      true,
      df => {
        val jsonFileScan = collect(df.queryExecution.executedPlan) {
          case l: HiveTableScanExecTransformer => l
        }
        assert(jsonFileScan.size == 1)
      })
  }

  test("test hive json table complex data type") {
    val sql =
      s"""
         | select array_field, map_field from $json_table_name
         | where string_field != '' and int_field > 0
         |""".stripMargin
    compareResultsAgainstVanillaSpark(
      sql,
      true,
      df => {
        val jsonFileScan = collect(df.queryExecution.executedPlan) {
          case l: HiveTableScanExecTransformer => l
        }
        assert(jsonFileScan.size == 1)
      }
    )

  }

  test("GLUTEN-2019: Bug fix not allow quotes") {
    val default_quote_table_name = "test_2019_default"
    val allow_double_quote_table_name = "test_2019_allow_double"
    val allow_single_quote_table_name = "test_2019_allow_single"
    val default_data_path = getClass.getResource("/").getPath + "/text-data/default"
    val allow_double_data_path = getClass.getResource("/").getPath + "/text-data/double-quote"
    val allow_single_data_path = getClass.getResource("/").getPath + "/text-data/single-quote"
    val drop_default_table_sql = "drop table if exists %s".format(default_quote_table_name)
    val drop_double_quote_table_sql =
      "drop table if exists %s".format(allow_double_quote_table_name)
    val drop_single_quote_table_sql =
      "drop table if exists %s".format(allow_single_quote_table_name)
    val create_default_table_sql =
      "create table if not exists %s (".format(default_quote_table_name) +
        "a string," +
        "b string, " +
        "c string)" +
        " row format delimited fields terminated by ',' stored as textfile LOCATION \"%s\""
          .format(default_data_path)
    val create_double_quote_table_sql =
      "create table if not exists %s (".format(allow_double_quote_table_name) +
        "a string," +
        "b string, " +
        "c string)" +
        "ROW FORMAT SERDE 'org.apache.hadoop.hive.serde2.OpenCSVSerde'" +
        " WITH SERDEPROPERTIES ('separatorChar' = ',', 'quoteChar' = '\"')" +
        " stored as textfile LOCATION \"%s\"".format(allow_double_data_path)
    val create_single_quote_table_sql =
      "create table if not exists %s (".format(allow_single_quote_table_name) +
        "a string," +
        "b string, " +
        "c string)" +
        "ROW FORMAT SERDE 'org.apache.hadoop.hive.serde2.OpenCSVSerde'" +
        " WITH SERDEPROPERTIES (\"separatorChar\" = \",\", \"quoteChar\" = \"\'\")" +
        " stored as textfile LOCATION \"%s\"".format(allow_single_data_path)

    spark.sql(drop_default_table_sql)
    spark.sql(drop_double_quote_table_sql)
    spark.sql(drop_single_quote_table_sql)
    spark.sql(create_default_table_sql)
    spark.sql(create_double_quote_table_sql)
    spark.sql(create_single_quote_table_sql)

    val sql1 = "select * from " + default_quote_table_name
    val sql2 = "select * from " + allow_double_quote_table_name
    val sql3 = "select * from " + allow_single_quote_table_name
    compareResultsAgainstVanillaSpark(
      sql1,
      true,
      df => {
        val txtFileScan =
          collect(df.queryExecution.executedPlan) { case l: HiveTableScanExecTransformer => l }
        assert(txtFileScan.size == 1)
      })
    compareResultsAgainstVanillaSpark(
      sql2,
      true,
      df => {
        val txtFileScan =
          collect(df.queryExecution.executedPlan) { case l: HiveTableScanExecTransformer => l }
        assert(txtFileScan.size == 1)
      })
    compareResultsAgainstVanillaSpark(
      sql3,
      true,
      df => {
        val txtFileScan =
          collect(df.queryExecution.executedPlan) { case l: HiveTableScanExecTransformer => l }
        assert(txtFileScan.size == 1)
      })
  }

  test("text hive table with space/tab delimiter") {
    val txt_table_name_space_delimiter = "hive_txt_table_space_delimiter"
    val txt_table_name_tab_delimiter = "hive_txt_table_tab_delimiter"
    val drop_space_table_sql = "drop table if exists %s".format(txt_table_name_space_delimiter)
    val drop_tab_table_sql = "drop table if exists %s".format(txt_table_name_tab_delimiter)
    val create_space_table_sql =
      "create table if not exists %s (".format(txt_table_name_space_delimiter) +
        "int_field int," +
        "string_field string" +
        ") row format delimited fields terminated by ' ' stored as textfile"
    val create_tab_table_sql =
      "create table if not exists %s (".format(txt_table_name_tab_delimiter) +
        "int_field int," +
        "string_field string" +
        ") row format delimited fields terminated by '\t' stored as textfile"
    spark.sql(drop_space_table_sql)
    spark.sql(drop_tab_table_sql)
    spark.sql(create_space_table_sql)
    spark.sql(create_tab_table_sql)
    spark.sql("insert into %s values(1, 'ab')".format(txt_table_name_space_delimiter))
    spark.sql("insert into %s values(1, 'ab')".format(txt_table_name_tab_delimiter))
    val sql1 =
      s"""
         | select * from $txt_table_name_space_delimiter where int_field > 0
         |""".stripMargin
    val sql2 =
      s"""
         | select * from $txt_table_name_tab_delimiter where int_field > 0
         |""".stripMargin

    compareResultsAgainstVanillaSpark(
      sql1,
      true,
      df => {
        val txtFileScan = collect(df.queryExecution.executedPlan) {
          case l: HiveTableScanExecTransformer => l
        }
        assert(txtFileScan.size == 1)
      })
    compareResultsAgainstVanillaSpark(
      sql2,
      true,
      df => {
        val txtFileScan = collect(df.queryExecution.executedPlan) {
          case l: HiveTableScanExecTransformer => l
        }
        assert(txtFileScan.size == 1)
      })
  }

  test("test hive table with illegal partition path") {
    val path = new Path(sparkConf.get("spark.sql.warehouse.dir"))
    val fs = path.getFileSystem(spark.sessionState.newHadoopConf())
    val tablePath = path.toUri.getPath + "/" + json_table_name
    val partitionPath = tablePath + "/" + "_temp_day=2023_06_05kids"
    val succ = fs.mkdirs(new Path(partitionPath))
    assert(succ, true)
    val partitionDataFilePath = partitionPath + "/abc.txt"
    val createSucc = fs.createNewFile(new Path(partitionDataFilePath))
    assert(createSucc, true)
    val sql =
      s"""
         | select string_field, day, count(*) from $json_table_name group by string_field, day
         |""".stripMargin
    compareResultsAgainstVanillaSpark(
      sql,
      true,
      df => {
        val txtFileScan = collect(df.queryExecution.executedPlan) {
          case l: HiveTableScanExecTransformer => l
        }
        assert(txtFileScan.size == 1)
      }
    )
  }

  test("test hive compressed txt table") {
    val txt_compressed_table_name = "hive_compressed_txt_test"
    val drop_table_sql = "drop table if exists %s".format(txt_compressed_table_name)
    val create_table_sql =
      "create table if not exists %s (".format(txt_compressed_table_name) +
        "id bigint," +
        "name string," +
        "sex string) stored as textfile"
    spark.sql(drop_table_sql)
    spark.sql(create_table_sql)
    spark.sql("SET hive.exec.compress.output=true")
    spark.sql("SET mapred.output.compress=true")
    spark.sql("SET mapred.output.compression.codec=org.apache.hadoop.io.compress.DefaultCodec")
    val insert_sql =
      s"""
         | insert into $txt_compressed_table_name values(1, "a", "b")
         |""".stripMargin
    spark.sql(insert_sql)

    val sql = "select * from " + txt_compressed_table_name
    compareResultsAgainstVanillaSpark(
      sql,
      true,
      df => {
        val txtFileScan =
          collect(df.queryExecution.executedPlan) { case l: HiveTableScanExecTransformer => l }
        assert(txtFileScan.size == 1)
      })
  }

  test("text hive txt table with multiple compressed method") {
    val compressed_txt_table_name = "compressed_hive_txt_test"
    val compressed_txt_data_path = getClass.getResource("/").getPath + "/text-data/compressed"
    val drop_table_sql = "drop table if exists %s".format(compressed_txt_table_name)
    val create_table_sql =
      "create table if not exists %s (".format(compressed_txt_table_name) +
        "id bigint," +
        "name string," +
        "sex string) stored as textfile LOCATION \"%s\"".format(compressed_txt_data_path)
    spark.sql(drop_table_sql)
    spark.sql(create_table_sql)
    val sql = "select * from " + compressed_txt_table_name
    compareResultsAgainstVanillaSpark(
      sql,
      true,
      df => {
        val txtFileScan =
          collect(df.queryExecution.executedPlan) { case l: HiveTableScanExecTransformer => l }
        assert(txtFileScan.size == 1)
      })
  }

  test("test orc/parquet table with null complex type values") {
    val create_template =
      """
        | CREATE TABLE test_%s(
        |   id INT,
        |   info STRUCT<name:STRING, age:INT>,
        |   data MAP<STRING, INT>,
        |   values ARRAY<INT>
        | ) stored as %s;
        |""".stripMargin
    val insert_template =
      """
        | INSERT OVERWRITE test_%s VALUES
        |   (1, struct('John', 25), map('A', 10, 'B', 20), array(1.0, 2.0, 3.0)),
        |   (2, struct('Alice', 30), map('C', 15, 'D', 25), array(4.0, 5.0, 6.0)),
        |   (3, struct('Bob', 35), map('E', 12, 'F', 18), array(7.0, 8.0, 9.0)),
        |   (4, struct('Jane', 40), map('G', 22, 'H', 30), array(10.0, 11.0, 12.0)),
        |   (5, struct('Kate', 45), map('I', 17, 'J', 28), array(13.0, 14.0, 15.0)),
        |   (6, null, null, null),
        |   (7, struct('Tank', 20), map('X', null, 'Y', null), array(1.0, 2.0, 3.0));
        |""".stripMargin

    val select1_template = "select id, info, info.age, data, values from test_%s"
    val select2_template = "select id, info.name from test_%s"
    val select3_template = "select id from test_%s where info.name = 'Bob'"

    val drop_template = "DROP TABLE test_%s"

    val formats = Array("orc", "parquet")
    for (format <- formats) {
      val create_sql = create_template.format(format, format)
      val insert_sql = insert_template.format(format)
      val select1_sql = select1_template.format(format)
      val select2_sql = select2_template.format(format)
      val select3_sql = select3_template.format(format)
      val drop_sql = drop_template.format(format)

      spark.sql(create_sql)
      spark.sql(insert_sql)

      compareResultsAgainstVanillaSpark(select1_sql, true, _ => {})
      compareResultsAgainstVanillaSpark(select2_sql, true, _ => {})
      compareResultsAgainstVanillaSpark(select3_sql, true, _ => {})

      spark.sql(drop_sql)
    }
  }

  test("Gluten-2582: Fix crash in array<struct>") {
    val create_table_sql =
      """
        | create table test_tbl_2582(
        | id bigint,
        | d1 array<struct<a:int, b:string>>,
        | d2 map<string, struct<a:int, b:string>>) stored as parquet
        |""".stripMargin

    val insert_data_sql =
      """
        | insert into test_tbl_2582 values
        | (1, array(named_struct('a', 1, 'b', 'b1'), named_struct('a', 2, 'b', 'c1')),
        | map('a', named_struct('a', 1, 'b', 'b1'))),
        | (2, null, map('b', named_struct('a', 2, 'b','b2'))),
        | (3, array(null, named_struct('a', 3, 'b', 'b3')),
        |  map('c', named_struct('a', 3, 'b', 'b3'))),
        | (4, array(named_struct('a', 4, 'b', 'b4')), null),
        | (5, array(named_struct('a', 5, 'b', 'b5')),
        |  map('c', null, 'd', named_struct('a', 5, 'b', 'b5')))
        |""".stripMargin

    val select_sql_1 = "select * from test_tbl_2582 where d1[0].a = 1"
    val select_sql_2 = "select element_at(d1, 1).a from test_tbl_2582 where id = 1"
    val select_sql_3 = "select d2['a'].a from test_tbl_2582 where id = 1"
    val select_sql_4 = "select count(1) from test_tbl_2582 where d1[0].a = 1"
    val select_sql_5 = "select count(1) from test_tbl_2582 where (id = 2 or id = 3) and d1[0].a = 1"
    val select_sql_6 =
      "select count(1) from test_tbl_2582 where (id = 4 or id = 5) and d2['c'].a = 1"

    spark.sql(create_table_sql)
    spark.sql(insert_data_sql)

    compareResultsAgainstVanillaSpark(select_sql_1, true, _ => {})
    compareResultsAgainstVanillaSpark(select_sql_2, true, _ => {})
    compareResultsAgainstVanillaSpark(select_sql_3, true, _ => {})
    compareResultsAgainstVanillaSpark(select_sql_4, true, _ => {})
    compareResultsAgainstVanillaSpark(select_sql_5, true, _ => {})
    compareResultsAgainstVanillaSpark(select_sql_6, true, _ => {})
  }
}
