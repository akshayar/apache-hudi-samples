package kinesis.parquet.latefile

// Spark Shell ---start

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
import java.util.Date
import java.text.SimpleDateFormat
import org.apache.spark.sql.streaming._
import org.apache.spark.sql.streaming.StreamingQueryListener
import org.apache.spark.sql.streaming.StreamingQueryListener._
import java.util.concurrent.TimeUnit
// Spark Shell ---end 
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
 * s3_bucket  Ex. <akshaya-firehose-test>
 * streamName Ex. <hudi-stream-ingest>
 * region Ex. <us-west-2>
 * tableType Ex. <COW/MOR>
 * tableName Ex. <parquet_trade_info>
 *
 */
object SparkKinesisConsumerParquetProcessor {

  def epochToDate(epochMillis: String): Date = {
    new Date(epochMillis.toLong)
  }

  def main(args: Array[String]): Unit = {

    val spark = SparkSession
      .builder
      .appName("SparkParquet")
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .config("spark.default.parallelism", 9)
      .config("spark.sql.shuffle.partitions", 9)
      .config("hive.metastore.client.factory.class",
        "com.amazonaws.glue.catalog.metastore.AWSGlueDataCatalogHiveClientFactory")
      .enableHiveSupport()
      .getOrCreate()

    // Spark Shell ---start 
    import spark.implicits._
    // Spark Shell -- hardcode these parameters
    var s3_bucket = "akshaya-firehose-test"
    var streamName = "data-stream-ingest"
    var region = "ap-south-1"
    var tableName = "parquet_trade_info"
    //Access Key and Secret key are needed when running the job on
    //EMR Serverless. It worked for EMR.
    var ACCESS_KEY = ""
    var SECRET_KEY = ""
    var SESSION_TOKEN=""

 
    // Spark Shell ---end 

    if (!Option(args).isEmpty) {
      s3_bucket = args(0) //"akshaya-firehose-test"//
      streamName = args(1) //"hudi-stream-ingest"//
      region = args(2) //"us-west-2"//
      tableName = args(4) //"parquet_trade_info"//
      ACCESS_KEY=args(5)
      SECRET_KEY=args(6)
      SESSION_TOKEN=args(7)
    }

    // Spark Shell ---start 
    var databaseName = "parquet"
    

    val recordKey = "record_key"
    val tablePartitionKey = "partition_key"
    val tablePrecombineKey = "trade_datetime"
    val tablePath = s"s3://$s3_bucket/parquet/" + tableName
    val checkpoint_path = s"s3://$s3_bucket/kinesis-stream-data-checkpoint/" + tableName + "/"
    val endpointUrl = s"https://kinesis.$region.amazonaws.com"

    println("recordKey:" + recordKey)
    println("tablePrecombineKey:" + tablePrecombineKey)
    println("tablePath:" + tablePath)
    println("checkpoint_path:" + checkpoint_path)
    println("endpointUrl:" + endpointUrl)

    val streamingInputDF = (spark
      .readStream.format("kinesis")
      .option("streamName", streamName)
      .option("startingposition", "TRIM_HORIZON")
      .option("endpointUrl", endpointUrl)
      .option("awsSTSRoleARN", ACCESS_KEY)
      .option("awsSTSSessionName", SECRET_KEY)
      //.option("sessiontoken", SESSION_TOKEN)
      .load())

    val decimalType = DataTypes.createDecimalType(38, 10)
    val dataSchema = StructType(Array(
      StructField("tradeId", StringType, true),
      StructField("symbol", StringType, true),
      StructField("quantity", StringType, true),
      StructField("price", StringType, true),
      StructField("timestamp", StringType, true),
      StructField("description", StringType, true),
      StructField("traderName", StringType, true),
      StructField("traderFirm", StringType, true)
    ))


    val jsonDF = (streamingInputDF.selectExpr("CAST(data AS STRING)").as[(String)]
      .withColumn("jsonData", from_json(col("data"), dataSchema))
      .select(col("jsonData.*")))


    jsonDF.printSchema()
    var parDF = jsonDF
    parDF = parDF.select(parDF.columns.map(x => col(x).as(x.toLowerCase)): _*)
    parDF = parDF.filter(parDF.col("tradeId").isNotNull)
    parDF.printSchema()
    parDF = parDF.withColumn(recordKey, concat(col("tradeId"), lit("#"), col("timestamp")))

    parDF = parDF.withColumn("trade_datetime", from_unixtime(parDF.col("timestamp")))
    parDF = parDF.withColumn("day", dayofmonth($"trade_datetime").cast(StringType)).withColumn("hour", hour($"trade_datetime").cast(StringType))
    parDF = parDF.withColumn(tablePartitionKey, concat(lit("day="), $"day", lit("/hour="), $"hour"))
    parDF.printSchema()

    var query = (parDF.writeStream.format("parquet")
      .option("checkpointLocation", checkpoint_path)
      .option("path", tablePath)
      .partitionBy("day","hour")  
      .outputMode("append")
      .trigger(Trigger.ProcessingTime(1, TimeUnit.MINUTES))
      .start());

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

  }

}
