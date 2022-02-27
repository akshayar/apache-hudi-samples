package kinesis.hudi.cdc

// Copy to run from Spark Shell ---start

import org.apache.hudi.config.HoodieWriteConfig
import org.apache.hudi.hive.MultiPartKeysValueExtractor
import org.apache.hudi.DataSourceWriteOptions
import org.apache.spark.sql.types._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, Row, SaveMode}
import org.apache.spark.sql.catalyst.encoders.RowEncoder
import org.apache.spark.sql.types.{LongType, StringType, StructField, StructType}
import org.apache.spark.sql.SparkSession
import org.apache.log4j.Logger
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.kinesis.KinesisInputDStream
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.streaming.kinesis.KinesisInitialPositions
// Copy to run from Spark Shell ---end 
/**
 * The file consumes messages that contains S3 path to CDC pushed to S3 from DMS. The message content look like
 * {
 * "filePath": "s3://<bucket-name>/dms-full-load-path/salesdb/SALES_ORDER_DETAIL/20211118-100428844.parquet"
 * }
 * The parameters expected are -
 * s3_bucket  Ex. <akshaya-firehose-test>
 * streamName Ex. <hudi-stream-ingest>
 * region Ex. <us-west-2>
 * tableType Ex. <COW/MOR>
 * hudiTableNamePrefix Ex. <hudi_trade_info>
 *
 */
object SparkKinesisFilePathConsumerHudiProcessor {

  def main(args: Array[String]): Unit = {

    val spark = SparkSession
      .builder
      .appName("SparkHudi")
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .config("spark.default.parallelism", 9)
      .config("spark.sql.shuffle.partitions", 9)
      .enableHiveSupport()
      .getOrCreate()
    // Copy to run from Spark Shell ---start 
    import spark.implicits._
    // For Spark Shell -- hardcode these parameters


    var s3_bucket = "akshaya-firehose-test"
    var streamName = "hudi-stream-ingest"
    var region = "us-west-2"
    var tableType = "COW"
    var hudiTableNamePrefix = "sales_order_detail_hudi"
    var hudiTableName = hudiTableNamePrefix + "_cow"
    // Spark Shell ---end 

    if (!Option(args).isEmpty) {
      s3_bucket = args(0) //"akshaya-firehose-test"//
      streamName = args(1) //"hudi-stream-ingest"//
      region = args(2) //"us-west-2"//
      tableType = args(3) //"COW"//
      hudiTableNamePrefix = args(4) //"hudi_trade_info"//
      hudiTableName = hudiTableNamePrefix + "_cow"
    }

    // Spark Shell ---start 
    var hudiDatabaseName = "hudi"
    var dsWriteOptionType = DataSourceWriteOptions.COW_STORAGE_TYPE_OPT_VAL
    if (tableType.equals("COW")) {
      hudiTableName = hudiTableNamePrefix + "_cow"
      dsWriteOptionType = DataSourceWriteOptions.COW_STORAGE_TYPE_OPT_VAL
    } else if (tableType.equals("MOR")) {
      hudiTableName = hudiTableNamePrefix + "_mor"
      dsWriteOptionType = DataSourceWriteOptions.MOR_STORAGE_TYPE_OPT_VAL
    }

    val dataPath = s"s3://$s3_bucket/dms-full-load-path/salesdb/SALES_ORDER_DETAIL/LOAD*"
    val hudiTableRecordKey = "record_key"
    val hudiTablePartitionKey = "partition_key"
    val hudiTablePrecombineKey = "order_date"
    val hudiTablePath = s"s3://$s3_bucket/hudi/" + hudiTableName
    val hudiHiveTablePartitionKey = "year,month"
    val checkpoint_path = s"s3://$s3_bucket/kinesis-stream-data-checkpoint/" + hudiTableName + "/"
    val endpointUrl = s"https://kinesis.$region.amazonaws.com"

    println("hudiTableRecordKey:" + hudiTableRecordKey)
    println("hudiTablePartitionKey:" + hudiTablePartitionKey)
    println("hudiTablePrecombineKey:" + hudiTablePrecombineKey)
    println("hudiTablePath:" + hudiTablePath)
    println("hudiHiveTablePartitionKey:" + hudiHiveTablePartitionKey)
    println("checkpoint_path:" + checkpoint_path)
    println("endpointUrl:" + endpointUrl)


    val streamingInputDF = (spark
      .readStream.format("kinesis")
      .option("streamName", "hudi-stream")
      .option("startingposition", "TRIM_HORIZON")
      .option("endpointUrl", endpointUrl)
      .load())


    val schema = StructType(Array(StructField("filePath", StringType, true)))


    val jsonDF = (streamingInputDF.selectExpr("CAST(data AS STRING)").as[(String)]
      .withColumn("jsonData", from_json(col("data"), schema))
      .select(col("jsonData.filePath")))


    jsonDF.printSchema()
    val query = (jsonDF.writeStream.foreachBatch { (batchDF: DataFrame, _: Long) => {
      print(batchDF)
      batchDF.collect().foreach(s => {
        print(s)
        var parDF = spark.read.format("parquet").load(s.getString(0));
        parDF = parDF.drop("Op")
        parDF = parDF.select(parDF.columns.map(x => col(x).as(x.toLowerCase)): _*)
        parDF = parDF.withColumn(hudiTableRecordKey, concat(col("order_id"), lit("#"), col("line_id")))
        parDF = parDF.withColumn("order_date", parDF("order_date").cast(DateType))
        parDF = parDF.withColumn("year", year($"order_date").cast(StringType)).withColumn("month", month($"order_date").cast(StringType))
        parDF = parDF.withColumn(hudiTablePartitionKey, concat(lit("year="), $"year", lit("/month="), $"month"))
        parDF.printSchema()
        parDF.select("month", "record_key", "quantity").show()
        parDF.write.format("org.apache.hudi")
          .option("hoodie.datasource.write.table.type", dsWriteOptionType)
          .option(DataSourceWriteOptions.RECORDKEY_FIELD_OPT_KEY, hudiTableRecordKey)
          .option(DataSourceWriteOptions.PARTITIONPATH_FIELD_OPT_KEY, hudiTablePartitionKey)
          .option(HoodieWriteConfig.TABLE_NAME, hudiTableName)
          .option(DataSourceWriteOptions.OPERATION_OPT_KEY, DataSourceWriteOptions.UPSERT_OPERATION_OPT_VAL)
          .option(DataSourceWriteOptions.PRECOMBINE_FIELD_OPT_KEY, hudiTablePrecombineKey)
          .option(DataSourceWriteOptions.HIVE_SYNC_ENABLED_OPT_KEY, "true")
          .option(DataSourceWriteOptions.HIVE_TABLE_OPT_KEY, hudiTableName)
          .option(DataSourceWriteOptions.HIVE_PARTITION_FIELDS_OPT_KEY, hudiHiveTablePartitionKey)
          .option("hoodie.datasource.hive_sync.assume_date_partitioning", "false")
          .option(DataSourceWriteOptions.HIVE_PARTITION_EXTRACTOR_CLASS_OPT_KEY, classOf[MultiPartKeysValueExtractor].getName)
          .option(DataSourceWriteOptions.HIVE_DATABASE_OPT_KEY, hudiDatabaseName)
          .mode("append")
          .save(hudiTablePath);
      })
    }
    }.option("checkpointLocation", checkpoint_path).start())
    // Copy to run from Spark Shell ---end

    query.awaitTermination()

  }

}
