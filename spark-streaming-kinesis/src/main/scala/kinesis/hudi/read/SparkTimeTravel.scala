package kinesis.hudi.read

// Spark Shell ---start

import org.apache.hudi.config.HoodieWriteConfig
import org.apache.hudi.config.HoodieWriteConfig._
import org.apache.hudi.hive.MultiPartKeysValueExtractor
import org.apache.hudi.DataSourceWriteOptions
import org.apache.hudi.DataSourceWriteOptions._
import org.apache.hudi.DataSourceReadOptions
import org.apache.hudi.DataSourceReadOptions._
import org.apache.hudi.QuickstartUtils._
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
import org.apache.hudi.keygen._
import java.util._

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
 * sourceHudiTableNamePrefix Ex. <hudi_trade_info>
 *
 */
object SparkTimeTravel {

  def epochToDate(epochMillis: String): Date = {
    new Date(epochMillis.toLong)
  }

  def main(args: Array[String]): Unit = {

    val spark = SparkSession
      .builder
      .appName("SparkHudi")
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .config("spark.default.parallelism", 9)
      .config("spark.sql.shuffle.partitions", 9)
      .enableHiveSupport()
      .getOrCreate()

    // Spark Shell ---start 
    import spark.implicits._
    // Spark Shell -- hardcode these parameters
    var s3_bucket = "akshaya-hudi-experiments"
    var sourceHudiTableName =  "equity_trade_records_cow"
    var targetTableType = "COW"
    var targetTableNamePrefix = "hudi_trade_info_derived"
    var targetTableName = targetTableNamePrefix + "_cow"
    var hudiDatabaseName = "demohudi"
    // Spark Shell ---end 

    if (!Option(args).isEmpty) {
      s3_bucket = args(0) //"akshaya-firehose-test"//
      sourceHudiTableName = args(1) //"hudi_trade_info_cow"//
      targetTableType=args(2) 
      targetTableNamePrefix=args(3)
      hudiDatabaseName = args(5)
    }

    // Spark Shell ---start 
    
    var dsWriteOptionType = DataSourceWriteOptions.COW_STORAGE_TYPE_OPT_VAL
    if (targetTableType.equals("COW")) {
      targetTableName = targetTableNamePrefix + "_cow"
      dsWriteOptionType = DataSourceWriteOptions.COW_STORAGE_TYPE_OPT_VAL
    } else if (targetTableType.equals("MOR")) {
      targetTableName = targetTableNamePrefix + "_mor"
      dsWriteOptionType = DataSourceWriteOptions.MOR_STORAGE_TYPE_OPT_VAL
    }

    
    val sourceHudiTablePath = s"s3://$s3_bucket/demo/hudi/" + sourceHudiTableName
       
    println("sourceHudiTableName:" + sourceHudiTableName)

    

    // spark-shell
    // reload data
    val format = new SimpleDateFormat("yyyyMMddhhmmss")
    val date=format.format(new Date)
    spark.read.format("hudi").load(sourceHudiTablePath).createOrReplaceTempView(sourceHudiTableName)

    val sql=(s"select max(_hoodie_commit_time) as commitTime from  $sourceHudiTableName where  _hoodie_commit_time < date_format(cast(current_timestamp as TIMESTAMP) - INTERVAL 20 minutes,'yyyyMMddhhmmss') ")
    val commits = spark.sql(sql).map(k => k.getString(0)).take(10)
    //commits.show()
    val beginTime = Optional.ofNullable(commits(0) ).orElse(date)
    println("beginTime:"+beginTime)
    
    val hudiTableRecordKey = "record_key"
    val hudiTablePrecombineKey = "trade_datetime"
    val hudiHiveTablePartitionKey = "symbol,day,hour"
    val targetHudiTablePath = s"s3://$s3_bucket/demo/hudi/" + targetTableName
    

    println("hudiDatabaseName.targetTableName:" + hudiDatabaseName+"."+targetTableName)
    println("targetHudiTablePath:" + targetHudiTablePath)
    println("hudiTablePrecombineKey:" + hudiTablePrecombineKey)    
    println("hudiHiveTablePartitionKey:" + hudiHiveTablePartitionKey)

    val incrementalDF = (spark.read.format("hudi")
        .option(QUERY_TYPE_OPT_KEY, QUERY_TYPE_INCREMENTAL_OPT_VAL)
        .option(BEGIN_INSTANTTIME_OPT_KEY, beginTime)
        .load(sourceHudiTablePath))
    incrementalDF.show()

    var result = (incrementalDF.write.format("hudi")
      .options(getQuickstartWriteConfigs)
      .option(TABLE_TYPE_OPT_KEY, dsWriteOptionType)
      .option("hoodie.table.name", targetTableName)
      .option(RECORDKEY_FIELD_OPT_KEY, hudiTableRecordKey)
      .option(PARTITIONPATH_FIELD_OPT_KEY,hudiHiveTablePartitionKey)
      .option(PRECOMBINE_FIELD_OPT_KEY, hudiTablePrecombineKey)
      .option(KEYGENERATOR_CLASS_OPT_KEY, classOf[ComplexKeyGenerator].getName)     
      .option(HIVE_STYLE_PARTITIONING_OPT_KEY, "true")
      .option(HIVE_SYNC_ENABLED_OPT_KEY, "true")
      .option(HIVE_TABLE_OPT_KEY, targetTableName)
      .option(HIVE_PARTITION_FIELDS_OPT_KEY, hudiHiveTablePartitionKey)
      .option(HIVE_PARTITION_EXTRACTOR_CLASS_OPT_KEY, classOf[MultiPartKeysValueExtractor].getName)
      .option(HIVE_DATABASE_OPT_KEY, hudiDatabaseName)
      .option("hoodie.metadata.enable", "true")
      .option("hoodie.index.type","GLOBAL_BLOOM")
      .mode("append")
      .save(targetHudiTablePath));

     println("Saved data "+targetHudiTablePath+":"+result)
    

    // Spark Shell ---end

  }

}
