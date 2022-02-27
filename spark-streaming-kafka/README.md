
# Prerequisites
## EMR Prerequisites
1. Create EMR cluster with Spark, Hive and Hadoop enabled. Refer EMRSparkHudiCluster in the template at [cloudformation/hudi-workshop-emr-spark.yaml](../cloudformation/hudi-workshop-emr-spark.yaml).
2. Alternatively create the cluser with following command 
```shell
export BUCKET_NAME=<>
aws emr create-cluster --applications Name=Hadoop Name=Hive Name=Pig Name=Spark Name=Livy \
--ec2-attributes '{"KeyName":"aksh-ubuntu","InstanceProfile":"EMR_EC2_DefaultRole","SubnetId":"subnet-0301ebbbf20e76b3e","EmrManagedSlaveSecurityGroup":"sg-0c85d5d70ee9f1216","EmrManagedMasterSecurityGroup":"sg-087228adb4725d549"}' \
--release-label emr-6.5.0 \
--log-uri 's3n://'${BUCKET_NAME}'/elasticmapreduce/' \
--instance-groups '[{"InstanceCount":1,"EbsConfiguration":{"EbsBlockDeviceConfigs":[{"VolumeSpecification":{"SizeInGB":64,"VolumeType":"gp2"},"VolumesPerInstance":4}]},"InstanceGroupType":"MASTER","InstanceType":"m5.4xlarge","Name":"Master - 1"}]' \
--configurations '[{"Classification":"hive-site","Properties":{"hive.metastore.client.factory.class":"com.amazonaws.glue.catalog.metastore.AWSGlueDataCatalogHiveClientFactory"}},{"Classification":"spark-hive-site","Properties":{"hive.metastore.client.factory.class":"com.amazonaws.glue.catalog.metastore.AWSGlueDataCatalogHiveClientFactory"}}]' \
--auto-scaling-role EMR_AutoScaling_DefaultRole \
--ebs-root-volume-size 100 \
--service-role EMR_DefaultRole \
--enable-debugging \
--auto-termination-policy '{"IdleTimeout":3600}' \
--name 'EMR65' \
--scale-down-behavior TERMINATE_AT_TASK_COMPLETION \
--region ap-south-1
```
3. SSH to master node and execute command to update log level to [log4j.rootCategory=WARN,console] --this is an optional step

```
sudo vi /etc/spark/conf/log4j.properties 

```
3. Ensure that EMR role has permission and S3.
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
# Use Case 1 
Data Published to kafka. Spark Hudi consumes and ingests the data. 
## Message Content pushed to the topic
```shell
{
   "profit":27.720000000000006,
   "orderTimestamp":"1645960913",
   "traderName":"INFY Trader",
   "orderId":"order5257590795",
   "currentPosition":"4035",
   "buyPrice":46.01,
   "symbol":"INFY",
   "quantity":63.85,
   "buy":false,
   "price":20.19,
   "tradeId":"5257590795",
   "timestamp":1645960913,
   "portfolioId":"port5257590795",
   "sellPrice":73.73,
   "description":"INFY Description of trade",
   "customerId":"cust5257590795",
   "traderFirm":"INFY Trader Firm"
}
```
## Spark Scala Code
[kafka.hudi.StreamingConsumerHudi](src/main/scala/kafka/hudi/StreamingConsumerHudi.scala)

```shell
spark-submit \
--conf 'spark.serializer=org.apache.spark.serializer.KryoSerializer' \
--conf 'spark.sql.hive.convertMetastoreParquet=false' \
--conf "spark.dynamicAllocation.maxExecutors=10" \
--jars /usr/lib/hudi/hudi-spark-bundle.jar,/usr/lib/spark/external/lib/spark-avro.jar,/usr/lib/spark/jars/httpclient-4.5.9.jar \
--packages org.apache.spark:spark-sql-kafka-0-10_2.12:3.1.1,org.apache.spark:spark-streaming-kafka-0-10_2.12:3.1.1 \
--class kafka.hudi.StreamingConsumerHudi spark-structured-streaming-kafka-hudi_2.12-1.0.jar \
 <BUCKET_NAME> <KAFKA_BOOTSTRAP_SERVER> <TOPIC> <COW/MOR> <TABLE_NAME_PREFIX> <DB_NAME> <STARTING_POS earliest/latest>
 

spark-submit \
--conf 'spark.serializer=org.apache.spark.serializer.KryoSerializer' \
--conf 'spark.sql.hive.convertMetastoreParquet=false' \
--conf "spark.dynamicAllocation.maxExecutors=10" \
--jars /usr/lib/hudi/hudi-spark-bundle.jar,/usr/lib/spark/external/lib/spark-avro.jar,/usr/lib/spark/jars/httpclient-4.5.9.jar \
--packages org.apache.spark:spark-sql-kafka-0-10_2.12:3.1.1,org.apache.spark:spark-streaming-kafka-0-10_2.12:3.1.1 \
--class kafka.hudi.StreamingConsumerHudi spark-structured-streaming-kafka-hudi_2.12-1.0.jar \
akshaya-hudi-experiments ip-10-192-11-254.ap-south-1.compute.internal:9092 data-stream-ingest-json COW equity_trade_records_kafka_ss demohudi latest
```
## Spark Shell
Run the shell with command below and copy paste code from  [kafka.hudi.StreamingConsumerHudi](src/main/scala/kafka/hudi/StreamingConsumerHudi.scala). The code that needs to be copied is between  (Spark Shell ---Start ) and (Spark Shell ---End ). Also ensure that the you hard code the paremeters like s3_bucket, streamName, region ,tableType and hudiTableNamePrefix.

```shell             
spark-shell \
--conf 'spark.serializer=org.apache.spark.serializer.KryoSerializer' \
--conf 'spark.sql.hive.convertMetastoreParquet=false' \
--conf "spark.dynamicAllocation.maxExecutors=10" \
--jars /usr/lib/hudi/hudi-spark-bundle.jar,/usr/lib/spark/external/lib/spark-avro.jar,/usr/lib/spark/jars/httpclient-4.5.9.jar \
--packages org.apache.spark:spark-sql-kafka-0-10_2.12:3.1.1,org.apache.spark:spark-streaming-kafka-0-10_2.12:3.1.1

```
# Use Case 2 
CDC event Published to S3 by DMS. S3 event triggered Lambda pushes file path to Kafka.
## Message Content pushed to the topic
The filePath here is the path to the file which got added to S3 by DMS. An S3 event gets published which is consumed by Lambda. The lambda then pushes the event below to the Kafka stream which the file path of the file that got ingested.
```
{
    "filePath": "s3://<bucket-name>/dms-full-load-path/salesdb/SALES_ORDER_DETAIL/20211118-100428844.parquet"
}
```
## Spark Scala Code
[kafka.hudi.DMSFilleConsumerHudiProcessor](src/main/scala/kafka/hudi/DMSFilleConsumerHudiProcessor.scala)

## Spark Submit


```
spark-submit \
--conf "spark.serializer=org.apache.spark.serializer.KryoSerializer" \
--conf "spark.sql.hive.convertMetastoreParquet=false" \
--conf "spk.dynamicAllocation.maxExecutors=10" \
--jars /usr/lib/hudi/hudi-spark-bundle.jar,/usr/lib/spark/external/lib/spark-avro.jar \
--packages org.apache.spark:spark-streaming-kinesis-asl_2.12:3.1.1,com.qubole.spark:spark-sql-kinesis_2.12:1.2.0_spark-3.0 \
--class kafka.hudi.DMSFilleConsumerHudiProcessor spark-structured-streaming-kinesis-hudi_2.12-1.0.jar \
<BUCKET_NAME> <KAFKA_BOOTSTRAP_SERVER> <TOPIC> <COW/MOR> <TABLE_NAME_PREFIX> <DB_NAME> <STARTING_POS earliest/latest>
	

```

## Spark Shell

Run the shell with command below and copy paste code from  [kafka.hudi.DMSFilleConsumerHudiProcessor](src/main/scala/kafka/hudi/DMSFilleConsumerHudiProcessor.scala). The code that needs to be copied is between  (Spark Shell ---Start ) and (Spark Shell ---End ). Also ensure that the you hard code the paremeters like s3_bucket, streamName, region ,tableType and hudiTableNamePrefix.

```shell             
spark-shell \
--conf 'spark.serializer=org.apache.spark.serializer.KryoSerializer' \
--conf 'spark.sql.hive.convertMetastoreParquet=false' \
--conf "spark.dynamicAllocation.maxExecutors=10" \
--jars /usr/lib/hudi/hudi-spark-bundle.jar,/usr/lib/spark/external/lib/spark-avro.jar,/usr/lib/spark/jars/httpclient-4.5.9.jar \
--packages org.apache.spark:spark-sql-kafka-0-10_2.12:3.1.1,org.apache.spark:spark-streaming-kafka-0-10_2.12:3.1.1
```



## Hudi DeltStreamer
1. The instructions below work for EMR Version 6.5.0.
There is following error with EMR Version 6.3.0
```shell
Caused by: java.lang.NoSuchMethodError: org.apache.hadoop.hive.ql.Driver.close()V
at org.apache.hudi.hive.HoodieHiveClient.updateHiveSQLs(HoodieHiveClient.java:417)
at org.apache.hudi.hive.HoodieHiveClient.updateHiveSQLUsingHiveDriver(HoodieHiveClient.java:384)
at org.apache.hudi.hive.HoodieHiveClient.updateHiveSQL(HoodieHiveClient.java:374)
at org.apache.hudi.hive.HiveSyncTool.syncHoodieTable(HiveSyncTool.java:122)
at org.apache.hudi.hive.HiveSyncTool.syncHoodieTable(HiveSyncTool.java:94)
```
2. Run following command for help on DeltaStreamer.
```shell
spark-submit \
--class org.apache.hudi.utilities.deltastreamer.HoodieDeltaStreamer \
--jars `ls /usr/lib/hudi/hudi-utilities-bundle*.jar` \
--help
```   
3. Copy Jars
```shell
wget https://repo1.maven.org/maven2/org/apache/calcite/calcite-core/1.29.0/calcite-core-1.29.0.jar .
wget https://repo1.maven.org/maven2/org/apache/thrift/libfb303/0.9.3/libfb303-0.9.3.jar .
sudo cp calcite-core-1.29.0.jar /usr/lib/spark/jars
sudo cp libfb303-0.9.3.jar /usr/lib/spark/jars
```
4. Copy properties file and schema file to HDFS from where the program will read it. Copy of schema is required if you are using FilebasedSchemaProvider.
   
   <br>JSON Data , File Schema [hudi-delta-streamer/hudi-deltastreamer-schema-file-json.properties](hudi-delta-streamer/hudi-deltastreamer-schema-file-json.properties)
   <br>Schema File [hudi-delta-streamer/TradeData.avsc](hudi-delta-streamer/TradeData.avsc)   
   
   <br>AVRO Data , Schema Registry [hudi-delta-streamer/hudi-deltastreamer-schema-registry-avro.properties](hudi-delta-streamer/hudi-deltastreamer-schema-registry-avro.properties)
   

```shell
hdfs dfs -copyFromLocal -f TradeData.avsc /
hdfs dfs -copyFromLocal -f hudi-deltastreamer-schema-file-json.properties /
```
5. Run Spark Submit program. 
   JSON data on Kafka, Schema read from a local files on HDFS. The table is being synched with Glue Catalog

```shell

spark-submit \
--class  org.apache.hudi.utilities.deltastreamer.HoodieDeltaStreamer \
--jars `ls /usr/lib/hudi/hudi-utilities-bundle*.jar ` \
--checkpoint s3://akshaya-hudi-experiments/demo/kafka-stream-data-checkpoint/table_delta_streamer_cow_2/ \
--continuous  \
--enable-hive-sync \
--schemaprovider-class org.apache.hudi.utilities.schema.FilebasedSchemaProvider \
--source-class org.apache.hudi.utilities.sources.JsonKafkaSource \
--spark-master yarn \
--table-type COPY_ON_WRITE \
--target-base-path s3://akshaya-hudi-experiments/demo/hudi/table_delta_streamer_cow_2 \
--target-table table_delta_streamer_cow_2 \
--op UPSERT \
--source-ordering-field tradeId \
--props  hdfs:///hudi-deltastreamer-schema-file-json.properties

```

7. Run Spark Submit program. 
   AVRO data on Kafka, Schema read from schema registery. The table is being synched with Glue Catalog

```shell

spark-submit \
--class  org.apache.hudi.utilities.deltastreamer.HoodieDeltaStreamer \
--jars `ls /usr/lib/hudi/hudi-utilities-bundle*.jar ` \
--checkpoint s3://akshaya-hudi-experiments/demo/kafka-stream-data-checkpoint/table_delta_streamer_cow_2_avro/ \
--continuous  \
--enable-hive-sync \
--schemaprovider-class org.apache.hudi.utilities.schema.SchemaRegistryProvider \
--source-class org.apache.hudi.utilities.sources.AvroKafkaSource \
--spark-master yarn \
--table-type COPY_ON_WRITE \
--target-base-path s3://akshaya-hudi-experiments/demo/hudi/table_delta_streamer_cow_2 \
--target-table table_delta_streamer_cow_2 \
--op UPSERT \
--source-ordering-field tradeId \
--props  hdfs:///hudi-deltastreamer-schema-registry-avro.properties

```

## Hudi DeltaStreamer with DMS+kafka
```shell
 aws dms create-endpoint --endpoint-identifier s3-target-dms \
 --engine-name s3 --endpoint-type target \
 --s3-settings '{"ServiceAccessRoleArn": "arn:aws:iam::799223504601:role/dms-s3-target-role",  "BucketFolder": "dms/","BucketName": "akshaya-hudi-experiments", "DataFormat": "parquet" }'
```
```shell
spark-submit \
--jars /usr/lib/spark/external/lib/spark-avro.jar \
--class  org.apache.hudi.utilities.deltastreamer.HoodieDeltaStreamer /usr/lib/hudi/hudi-utilities-bundle.jar \
--continuous  \
--enable-hive-sync \
--source-class org.apache.hudi.utilities.sources.ParquetDFSSource \
--spark-master yarn \
--table-type COPY_ON_WRITE \
--target-base-path s3://akshaya-hudi-experiments/demo/hudi/aws_dms_rds \
--target-table aws_dms_rds \
--op UPSERT \
--source-ordering-field lastupdate \
--transformer-class org.apache.hudi.utilities.transform.AWSDmsTransformer \
--payload-class org.apache.hudi.common.model.AWSDmsAvroPayload \
--props  hdfs:///hudi-deltastreamer-dms.properties

```
### Hudi Delta Streamer : To Do 
1. Try with spark.executor.extraClassPath and spark.driver.extraClassPath configuration.
```shell
--conf spark.executor.extraClassPath=/home/hadoop/jars/calcite-core-1.29.0.jar:/home/hadoop/jars/libfb303-0.9.3.jar \
--conf spark.driver.extraClassPath=/home/hadoop/jars/calcite-core-1.29.0.jar:/home/hadoop/jars/libfb303-0.9.3.jar \

```
2. Try with AWS DMS. 

### Hudi Delta Streamer : Errors Faced
1. The error below was thrown when I used hoodie.datasource.hive_sync.use_jdbc=false. This went away after removing this entry. 
```shell

Caused by: NoSuchObjectException(message:Table table_delta_streamer_cow_2 not found. (Service: AWSGlue; Status Code: 400; Error Code: EntityNotFoundException; Request ID: c31d5605-cd58-4147-ae11-f1dde04a5d5a; Proxy: null))
	at com.amazonaws.glue.catalog.converters.BaseCatalogToHiveConverter$1.get(BaseCatalogToHiveConverter.java:90)
	at com.amazonaws.glue.catalog.converters.BaseCatalogToHiveConverter.getHiveException(BaseCatalogToHiveConverter.java:109)
	at com.amazonaws.glue.catalog.converters.BaseCatalogToHiveConverter.wrapInHiveException(BaseCatalogToHiveConverter.java:100)
	at com.amazonaws.glue.catalog.metastore.GlueMetastoreClientDelegate.getCatalogPartitions(GlueMetastoreClientDelegate.java:1129)
	at com.amazonaws.glue.catalog.metastore.GlueMetastoreClientDelegate.access$200(GlueMetastoreClientDelegate.java:162)
	at com.amazonaws.glue.catalog.metastore.GlueMetastoreClientDelegate$3.call(GlueMetastoreClientDelegate.java:1023)
	at com.amazonaws.glue.catalog.metastore.GlueMetastoreClientDelegate$3.call(GlueMetastoreClientDelegate.java:1020)
	at java.util.concurrent.FutureTask.run(FutureTask.java:266)

```
2. The error below happened with EMR Version 6.3.0
```shell
Caused by: java.lang.NoSuchMethodError: org.apache.hadoop.hive.ql.Driver.close()V
at org.apache.hudi.hive.HoodieHiveClient.updateHiveSQLs(HoodieHiveClient.java:417)
at org.apache.hudi.hive.HoodieHiveClient.updateHiveSQLUsingHiveDriver(HoodieHiveClient.java:384)
at org.apache.hudi.hive.HoodieHiveClient.updateHiveSQL(HoodieHiveClient.java:374)
at org.apache.hudi.hive.HiveSyncTool.syncHoodieTable(HiveSyncTool.java:122)
at org.apache.hudi.hive.HiveSyncTool.syncHoodieTable(HiveSyncTool.java:94)
```