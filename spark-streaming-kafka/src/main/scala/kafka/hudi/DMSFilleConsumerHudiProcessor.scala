package kafka.hudi

import org.apache.hudi.config.HoodieWriteConfig
import org.apache.hudi.config.HoodieWriteConfig._
import org.apache.hudi.hive.MultiPartKeysValueExtractor
import org.apache.hudi.DataSourceWriteOptions
import org.apache.hudi.DataSourceWriteOptions._
import org.apache.hudi.QuickstartUtils._
import org.apache.spark.sql._
import org.apache.spark.sql.types._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{LongType, StringType, StructField, StructType}
import org.apache.spark.sql.SparkSession
import org.apache.log4j.Logger
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming._


import java.util.Date
import java.text.SimpleDateFormat
import org.apache.spark.sql.streaming._
import org.apache.spark.sql.streaming.StreamingQueryListener
import org.apache.spark.sql.streaming.StreamingQueryListener._

import java.util.concurrent.TimeUnit
import org.apache.hudi.keygen._

object DMSFilleConsumerHudiProcessor {

  def main(args: Array[String]): Unit = {
    
    val spark = (SparkSession
          .builder
          .appName("SparkHudi")
          //.master("local[*]")
          .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
          .config("spark.default.parallelism", 9)
          .config("spark.sql.shuffle.partitions", 9)
          .enableHiveSupport()
          .getOrCreate())
    
    import spark.implicits._

    var s3_bucket="akshaya-hudi-experiments"
    var kafkaBootstrap="ip-10-192-11-254.ap-south-1.compute.internal:9092"
    var topics =Array("data-stream-ingest-json")
    var tableType = "COW"
    var hudiTableNamePrefix = "equity_trade_records_kafka_test"
    var hudiTableName = hudiTableNamePrefix + "_cow"
    var hudiDatabaseName = "demohudi"
    var startingPosition = "LATEST"

    if (!Option(args).isEmpty) {
      s3_bucket = args(0)
      kafkaBootstrap =args(1)
      topics = Array(args(2))
      tableType = args(3)
      hudiTableNamePrefix = args(4)
      hudiTableName = hudiTableNamePrefix + "_cow"
      hudiDatabaseName = args(5)
      startingPosition = args(6)
    }


    val hudiTableRecordKey = "record_key"
    val hudiTablePartitionKey = "partition_key"
    val hudiTablePrecombineKey = "order_date"
    val hudiTablePath = s"s3://$s3_bucket/hudi/" + hudiTableName
    val hudiHiveTablePartitionKey = "year,month"
    val checkpoint_path = s"s3://$s3_bucket/demo/kafka-stream-data-checkpoint/" + hudiTableName + "/"

  
    val streamingInputDF = spark.readStream.format("kafka").option("kafka.bootstrap.servers", kafkaBootstrap).option("subscribe", "s3_event_stream").option("startingOffsets", "earliest").load()

    val schema= StructType(Array(StructField("filePath",StringType,true)))
    val jsonDF=streamingInputDF.selectExpr("CAST(value AS STRING)").withColumn("jsonData",from_json(col("value"),schema)).select(col("jsonData.filePath"))

    val query = jsonDF.writeStream.foreachBatch{ (batchDF: DataFrame, _: Long) => {
            print(batchDF)
            batchDF.collect().foreach( s=> { 
                print(s)
                var parDF=spark.read.format("parquet").load(s.getString(0));
                parDF=parDF.drop("Op")
                parDF=parDF.select(parDF.columns.map(x => col(x).as(x.toLowerCase)): _*)
                parDF = parDF.withColumn(hudiTableRecordKey, concat(col("order_id"), lit("#"), col("line_id")))
                parDF = parDF.withColumn("order_date", parDF("order_date").cast(DateType))
                parDF = parDF.withColumn("year",year($"order_date").cast(StringType)).withColumn("month",month($"order_date").cast(StringType))
                parDF = parDF.withColumn(hudiTablePartitionKey,concat(lit("year="),$"year",lit("/month="),$"month"))
                parDF.printSchema()
                parDF.select("month","record_key","quantity").show()
                parDF.write.format("org.apache.hudi")
                      .options(getQuickstartWriteConfigs)
                      .option("hoodie.datasource.write.table.type", DataSourceWriteOptions.MOR_STORAGE_TYPE_OPT_VAL)
                      .option(DataSourceWriteOptions.RECORDKEY_FIELD_OPT_KEY, hudiTableRecordKey)
                      .option(DataSourceWriteOptions.PARTITIONPATH_FIELD_OPT_KEY, hudiTablePartitionKey)
                      .option(TBL_NAME.key(), hudiTableName)
                      .option(DataSourceWriteOptions.OPERATION_OPT_KEY, DataSourceWriteOptions.UPSERT_OPERATION_OPT_VAL)
                      .option(DataSourceWriteOptions.PRECOMBINE_FIELD_OPT_KEY, hudiTablePrecombineKey)
                      .option(DataSourceWriteOptions.HIVE_SYNC_ENABLED_OPT_KEY, "true")
                      .option(DataSourceWriteOptions.HIVE_TABLE_OPT_KEY,hudiTableName)
                      .option(DataSourceWriteOptions.HIVE_PARTITION_FIELDS_OPT_KEY, hudiHiveTablePartitionKey)
                      .option("hoodie.datasource.hive_sync.assume_date_partitioning", "false")
                      .option(DataSourceWriteOptions.HIVE_PARTITION_EXTRACTOR_CLASS_OPT_KEY, classOf[MultiPartKeysValueExtractor].getName)
                      .mode("append")
                      .save(hudiTablePath);
            })
    }}.option("checkpointLocation", checkpoint_path).start()
   

    query.awaitTermination()

  }

}
