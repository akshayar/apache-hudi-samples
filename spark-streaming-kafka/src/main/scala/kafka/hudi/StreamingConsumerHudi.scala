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

/**
 * The file consumes messages pushed to Kinesis. The message content look like
 * {
 * "tradeId":"211124204181756",
 * "symbol":"GOOGL",
 * "quantity":"39",
 * "price":"39",
 * "timestamp":1637766663,
 * "description":"Traded on Wed Nov 24 20:41:03 IST 2021",
 * "traderName":"GOOGL trader",
 * "traderFirm":"GOOGL firm"
 * }
 * The parameters expected are -
 * <BUCKET_NAME> s3_bucket  Ex. <akshaya-firehose-test>
 * <KAFKA_BOOTSTRAP_SERVER> Kakfa Boot strap server Ex. ip-10-192-11-254.ap-south-1.compute.internal:29092
 * <TOPIC> Ex.data-stream-ingest
 * <COW/MOR> Table Type Ex. COW
 * <TABLE_NAME> Tale Name Ex. equity_trade_records
 * <DB_NAME> Database Name  Ex. demohudi
 * <STARTING_POS earliest/lates> Starting Position of iteration Ex. earliest
 *
 */
object StreamingConsumerHudi {

  def main(args: Array[String]): Unit = {

    val applicationName="SparkConsumerHudiKafka"

    val spark = (SparkSession
      .builder
      .appName(applicationName)
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .enableHiveSupport()
      .getOrCreate())

    // Spark Shell ---start
    import spark.implicits._
    // Spark Shell -- hardcode these parameters
    var s3_bucket = "akshaya-hudi-experiments"
    var kafkaBootstrap="ip-10-192-11-254.ap-south-1.compute.internal:9092"
    var topic = "data-stream-ingest-json"
    var tableType = "COW"
    var hudiTableNamePrefix = "equity_trade_records_kafka_test"
    var hudiTableName = hudiTableNamePrefix + "_cow"
    var hudiDatabaseName = "demohudi"
    var startingPosition = "LATEST"
    // Spark Shell ---end

    if (!Option(args).isEmpty) {
      s3_bucket = args(0)
      kafkaBootstrap =args(1)
      topic = args(2)
      tableType = args(3)
      hudiTableNamePrefix = args(4)
      hudiTableName = hudiTableNamePrefix + "_cow"
      hudiDatabaseName = args(5)
      startingPosition = args(6)
    }

    // Spark Shell ---start

    var dsWriteOptionType = DataSourceWriteOptions.COW_STORAGE_TYPE_OPT_VAL
    if (tableType.equals("COW")) {
      hudiTableName = hudiTableNamePrefix + "_cow"
      dsWriteOptionType = DataSourceWriteOptions.COW_STORAGE_TYPE_OPT_VAL
    } else if (tableType.equals("MOR")) {
      hudiTableName = hudiTableNamePrefix + "_mor"
      dsWriteOptionType = DataSourceWriteOptions.MOR_STORAGE_TYPE_OPT_VAL
    }

    val hudiTableRecordKey = "record_key"
    val hudiTablePrecombineKey = "trade_datetime"
    val hudiTablePath = s"s3://$s3_bucket/demo/hudi/" + hudiTableName
    val hudiHiveTablePartitionKey = "month,day,hour"
    val checkpoint_path = s"s3://$s3_bucket/demo/kafka-stream-data-checkpoint/" + hudiTableName + "/"

    println(s"hudiDatabaseName.hudiTableName:$hudiDatabaseName+.$hudiTableName")
    println("hudiTableRecordKey:" + hudiTableRecordKey)
    println("hudiTablePrecombineKey:" + hudiTablePrecombineKey)
    println("hudiTablePath:" + hudiTablePath)
    println("hudiHiveTablePartitionKey:" + hudiHiveTablePartitionKey)
    println("checkpoint_path:" + checkpoint_path)

    val streamingInputDF = (spark
      .readStream.format("kafka")
      .option("kafka.bootstrap.servers", kafkaBootstrap)
      .option("subscribe", topic)
      .option("startingOffsets", startingPosition).load())


    val decimalType = DataTypes.createDecimalType(38, 10)
    val dataSchema = StructType(Array(
      StructField("tradeId", StringType, true),
      StructField("symbol", StringType, true),
      StructField("quantity", StringType, true),
      StructField("price", StringType, true),
      StructField("timestamp", StringType, true),
      StructField("description", StringType, true),
      StructField("traderName", StringType, true),
      StructField("traderFirm", StringType, true),
      StructField("orderId", StringType, true),
      StructField("portfolioId", StringType, true),
      StructField("customerId", StringType, true),
      StructField("buy", BooleanType, true),
      StructField("orderTimestamp", StringType, true),
      StructField("currentPosition", StringType, true),
      StructField("profit", StringType, true),
      StructField("buyPrice", StringType, true),
      StructField("sellPrice", StringType, true)
    ))


    val jsonDF=(streamingInputDF
      .selectExpr("CAST(value AS STRING)").as[(String)]
      .withColumn("jsonData",from_json(col("value"),dataSchema))
      .select(col("jsonData.*")))


    jsonDF.printSchema()
    val parDF = (jsonDF.select(jsonDF.columns.map(x => col(x).as(x.toLowerCase)): _*)
      .filter(jsonDF.col("tradeId").isNotNull)
      .withColumn(hudiTableRecordKey, col("tradeId")))
    parDF.printSchema()

    val dataFramWithTime = parDF.withColumn("trade_datetime", from_unixtime(parDF.col("timestamp")))


    val finalDf = (dataFramWithTime.filter(dataFramWithTime.col("trade_datetime").isNotNull)
      .withColumn("month", month($"trade_datetime").cast(StringType))
      .withColumn("day", dayofmonth($"trade_datetime").cast(StringType))
      .withColumn("hour", hour($"trade_datetime").cast(StringType)))

    finalDf.printSchema()


    val query = (finalDf.writeStream.format("hudi")
      .options(getQuickstartWriteConfigs)
      .option(TABLE_TYPE_OPT_KEY, dsWriteOptionType)
      .option(TBL_NAME.key(), hudiTableName)
      .option(RECORDKEY_FIELD_OPT_KEY, hudiTableRecordKey)
      .option(PARTITIONPATH_FIELD_OPT_KEY, hudiHiveTablePartitionKey)
      .option(PRECOMBINE_FIELD_OPT_KEY, hudiTablePrecombineKey)
      .option(KEYGENERATOR_CLASS_OPT_KEY, classOf[ComplexKeyGenerator].getName)
      .option(HIVE_STYLE_PARTITIONING_OPT_KEY, "true")
      .option(HIVE_SYNC_ENABLED_OPT_KEY, "true")
      .option(HIVE_TABLE_OPT_KEY, hudiTableName)
      .option(HIVE_PARTITION_FIELDS_OPT_KEY, hudiHiveTablePartitionKey)
      .option(HIVE_PARTITION_EXTRACTOR_CLASS_OPT_KEY, classOf[MultiPartKeysValueExtractor].getName)
      .option(HIVE_DATABASE_OPT_KEY, hudiDatabaseName)
      .option("hoodie.metadata.enable", "false")
      .option("hoodie.index.type", "GLOBAL_BLOOM")
      .option("checkpointLocation", checkpoint_path)
      .outputMode("append")
      .trigger(Trigger.ProcessingTime(3, TimeUnit.MINUTES))
      .start(hudiTablePath));

    val printNullTimeQuery = (dataFramWithTime
      .filter(dataFramWithTime.col("trade_datetime").isNull)
      .writeStream.format("console")
      .outputMode("append")
      .trigger(Trigger.ProcessingTime(3, TimeUnit.MINUTES))
      .start(hudiTablePath))


    spark.streams.addListener(new StreamingQueryListener() {
      override def onQueryStarted(queryStarted: QueryStartedEvent): Unit = {
        println("Query started: " + queryStarted.id)
      }

      override def onQueryTerminated(queryTerminated: QueryTerminatedEvent): Unit = {
        println("Query terminated: " + queryTerminated.id)
      }

      override def onQueryProgress(queryProgress: QueryProgressEvent): Unit = {
        println("Query made progress: " + queryProgress.progress)
      }
    })

    // Spark Shell ---end
    query.awaitTermination()
    //parDF.writeStream.format("console").outputMode("append").trigger(Trigger.ProcessingTime(3, TimeUnit.MINUTES)).start(hudiTablePath));
    //query.stop

  }

}
