
# Prerequisites
## EMR Prerequisites
1. Create EMR cluster with Spark, Hive and Hadoop enabled. Refer EMRSparkHudiCluster in the template at [cloudformation/hudi-workshop-emr-spark.yaml](../cloudformation/hudi-workshop-emr-spark.yaml).
2. SSH to master node and execute command to update log level to [log4j.rootCategory=WARN,console] --this is an optional step 

```
sudo vi /etc/spark/conf/log4j.properties 

```
3. Ensure that EMR role has permission on Kinesis and S3. 
## Spark Submit Prerequisite
1.  Build Environment
```
java --version
openjdk 15.0.2 2021-01-19
OpenJDK Runtime Environment Corretto-15.0.2.7.1 (build 15.0.2+7)
OpenJDK 64-Bit Server VM Corretto-15.0.2.7.1 (build 15.0.2+7, mixed mode, sharing)

sbt --version
sbt version in this project: 1.5.5
sbt script version: 1.5.5

```

3. Build and copy jar by running spark-streaming-kinesis/build.sh. 
```
./build.sh <S3-Bucket-Name>
```

2. SSH to master node and copy jar which was pushed to S3.
    
```
   aws s3 cp s3://<S3-Bucket-Name>/spark-structured-streaming-kinesis-hudi_2.12-1.0.jar .   
```

# Use Case 1 - Events Published to Kinesis with simulation of late arriving events
## Message Content pushed to the topic
timestamp has epoch value in seconds. 

```
{
   "tradeId":"211124204181756",
   "symbol":"GOOGL",
   "quantity":"39",
   "price":"39",
   "timestamp":1637766663,
   "description":"Traded on Wed Nov 24 20:41:03 IST 2021",
   "traderName":"GOOGL trader",
   "traderFirm":"GOOGL firm"
}

```
## Spark Scala Code
[kinesis.hudi.latefile.SparkKinesisConsumerHudiProcessor](src/main/scala/kinesis/hudi/latefile/SparkKinesisConsumerHudiProcessor.scala)

## Spark Submit 
SSH to master node and then run the spark submit command.

```
spark-submit \
--conf "spark.serializer=org.apache.spark.serializer.KryoSerializer" \
--conf "spark.sql.hive.convertMetastoreParquet=false" \
--conf "spk.dynamicAllocation.maxExecutors=10" \
--jars /usr/lib/hudi/hudi-spark-bundle.jar,/usr/lib/spark/external/lib/spark-avro.jar \
--packages org.apache.spark:spark-streaming-kinesis-asl_2.12:3.1.1,com.qubole.spark:spark-sql-kinesis_2.12:1.2.0_spark-3.0 \
--class kinesis.hudi.latefile.SparkKinesisConsumerHudiProcessor spark-structured-streaming-kinesis-hudi_2.12-1.0.jar \
<bucket-name>  <stream-name> <region> <COW/MOR> <table_name> <database> <LATEST/TRIM_HORIZON>
```
Example
```
sudo spark-submit \
--deploy-mode cluster \
--master yarn \
--conf "spark.serializer=org.apache.spark.serializer.KryoSerializer" \
--conf "spark.sql.hive.convertMetastoreParquet=false" \
--conf "spk.dynamicAllocation.maxExecutors=4" \
--jars /usr/lib/hudi/hudi-spark-bundle.jar,/usr/lib/spark/external/lib/spark-avro.jar \
--packages org.apache.spark:spark-streaming-kinesis-asl_2.12:3.1.1,com.qubole.spark:spark-sql-kinesis_2.12:1.2.0_spark-3.0 \
--class kinesis.hudi.latefile.SparkKinesisConsumerHudiProcessor spark-structured-streaming-kinesis-hudi_2.12-1.0.jar \
akshaya-hudi-experiments data-stream-ingest ap-south-1 COW equity_trade_records demohudi LATEST	
	
```

## Spark Shell
Run the shell with command below and copy paste code from   [kinesis.hudi.latefile.SparkKinesisConsumerHudiProcessor](src/main/scala/kinesis/hudi/latefile/SparkKinesisConsumerHudiProcessor.scala). The code that needs to be copied is between  (Spark Shell ---Start ) and (Spark Shell ---End ). Also ensure that the you hard code the paremeters like s3_bucket, streamName, region ,tableType and hudiTableNamePrefix.  

```
spark-shell \
--conf 'spark.serializer=org.apache.spark.serializer.KryoSerializer' \
--conf 'spark.sql.hive.convertMetastoreParquet=false' \
--jars /usr/lib/hudi/hudi-spark-bundle.jar,/usr/lib/spark/external/lib/spark-avro.jar \
--packages org.apache.spark:spark-streaming-kinesis-asl_2.12:3.1.1,com.qubole.spark:spark-sql-kinesis_2.12:1.2.0_spark-3.0
```

# Use Case 2 - Consume CDC events Published to Kinesis by DMS
    
## Message Content pushed to the topic
DMS publishes the changes to Kineiss 
```
{
		"data": {
		"LINE_ID": 144611,
		"LINE_NUMBER": 1,
		"ORDER_ID": 11363,
		"PRODUCT_ID": 927,
		"QUANTITY": 142,
		"UNIT_PRICE": 36,
		"DISCOUNT": 3,
		"SUPPLY_COST": 15,
		"TAX": 0,
		"ORDER_DATE": "2015-10-17"
		},
		"metadata": {
		"timestamp": "2021-11-19T13:24:43.297344Z",
		"record-type": "data",
		"operation": "update",
		"partition-key-type": "schema-table",
		"schema-name": "salesdb",
		"table-name": "SALES_ORDER_DETAIL",
		"transaction-id": 47330445004
		}
} 
```
## Spark Scala Code
[kinesis.hudi.cdc.SparkKinesisConsumerHudiProcessor](src/main/scala/kinesis/hudi/cdc/SparkKinesisConsumerHudiProcessor.scala)

## Spark Submit 

SSH to master node and then run the spark submit command.
```
spark-submit \
--conf "spark.serializer=org.apache.spark.serializer.KryoSerializer" \
--conf "spark.sql.hive.convertMetastoreParquet=false" \
--conf "spk.dynamicAllocation.maxExecutors=10" \
--jars /usr/lib/hudi/hudi-spark-bundle.jar,/usr/lib/spark/external/lib/spark-avro.jar \
--packages org.apache.spark:spark-streaming-kinesis-asl_2.12:3.1.1,com.qubole.spark:spark-sql-kinesis_2.12:1.2.0_spark-3.0 \
--class kinesis.hudi.SparkKinesisConsumerHudiProcessor spark-structured-streaming-kinesis-hudi_2.12-1.0.jar \
<bucket-name>  <stream-name> <region> <COW/MOR> <table_name>
	

```
## Spark Shell
Run the shell with command below and copy paste code from   [kinesis.hudi.SparkKinesisConsumerHudiProcessor](src/main/scala/kinesis/hudi/SparkKinesisConsumerHudiProcessor.scala). The code that needs to be copied is between  (Spark Shell ---Start ) and (Spark Shell ---End ). Also ensure that the you hard code the paremeters like s3_bucket, streamName, region ,tableType and hudiTableNamePrefix.  

```
spark-shell \
--conf 'spark.serializer=org.apache.spark.serializer.KryoSerializer' \
--conf 'spark.sql.hive.convertMetastoreParquet=false' \
--jars /usr/lib/hudi/hudi-spark-bundle.jar,/usr/lib/spark/external/lib/spark-avro.jar \
--packages org.apache.spark:spark-streaming-kinesis-asl_2.12:3.1.1,com.qubole.spark:spark-sql-kinesis_2.12:1.2.0_spark-3.0

```


# Use Case 3 - CDC event Published to S3 by DMS. S3 event triggered Lambda pushes file path to Kinesis. 
## Message Content pushed to the topic
The filePath here is the path to the file which got added to S3 by DMS. An S3 event gets published which is consumed by Lambda. The lambda then pushes the event below to the Kinesis stream which the file path of the file that got ingested. 
```
{
    "filePath": "s3://<bucket-name>/dms-full-load-path/salesdb/SALES_ORDER_DETAIL/20211118-100428844.parquet"
}
```
## Spark Scala Code
[kinesis.hudi.cdc.SparkKinesisFilePathConsumerHudiProcessor](src/main/scala/kinesis/hudi/cdc/SparkKinesisFilePathConsumerHudiProcessor.scala)

## Spark Submit 
    
    
```
spark-submit \
--conf "spark.serializer=org.apache.spark.serializer.KryoSerializer" \
--conf "spark.sql.hive.convertMetastoreParquet=false" \
--conf "spk.dynamicAllocation.maxExecutors=10" \
--jars /usr/lib/hudi/hudi-spark-bundle.jar,/usr/lib/spark/external/lib/spark-avro.jar \
--packages org.apache.spark:spark-streaming-kinesis-asl_2.12:3.1.1,com.qubole.spark:spark-sql-kinesis_2.12:1.2.0_spark-3.0 \
--class kinesis.hudi.SparkKinesisFilePathConsumerHudiProcessor spark-structured-streaming-kinesis-hudi_2.12-1.0.jar \
<bucket-name>  <stream-name> <region> <COW/MOR> <table_name>
	

```

## Spark Shell

Run the shell with command below and copy paste code from   [kinesis.hudi.SparkKinesisFilePathConsumerHudiProcessor](src/main/scala/kinesis/hudi/SparkKinesisFilePathConsumerHudiProcessor.scala). The code that needs to be copied is between  (Spark Shell ---Start ) and (Spark Shell ---End ). Also ensure that the you hard code the paremeters like s3_bucket, streamName, region ,tableType and hudiTableNamePrefix.  

```

spark-shell \
--conf 'spark.serializer=org.apache.spark.serializer.KryoSerializer' \
--conf 'spark.sql.hive.convertMetastoreParquet=false' \
--jars /usr/lib/hudi/hudi-spark-bundle.jar,/usr/lib/spark/external/lib/spark-avro.jar \
--packages org.apache.spark:spark-streaming-kinesis-asl_2.12:3.1.1,com.qubole.spark:spark-sql-kinesis_2.12:1.2.0_spark-3.0
```
# Possible Issues 
1.  Could not open client transport with JDBC Uri: jdbc:hive2://localhost:10000: java.net.ConnectException: Connection refused (Connection refused) --- The error message could be distracting. Since Glue Catelog integration is enabled , the job should not connect to Hive. In my case the error only happened in Mumbai region while it worked in Virginia ang Oregon. I had "Lake Formation" enabled in which case a role reading/writing to Glue table should have permission granted on Lake Formation. I granted EMR_EC2_DefaultRole role permission to create table, read and write on "default" database in Lake Formation. default database since without explicitely specifying the table with HIVE_DATABASE_OPT_KEY , HUDI writes to default database. 
2. 
