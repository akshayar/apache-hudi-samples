# Setup DMS and RDS MySQL
0. Detailed steps following the blog https://aws.amazon.com/blogs/big-data/submitting-user-applications-with-spark-submit/ 
1. Checkout the code. 
```
git clone https://github.com/akshayar/apache-hudi-samples.git
cd apache-hudi-samples
```
2. Create RDS, DMS and setup DMS task. 
```
export S3_BUCKET_NAME=<>
cd hudi-delta-streamer/s3/cfn-templates
aws s3 cp --recursive . s3://${S3_BUCKET_NAME}/cfn-template/

aws cloudformation deploy --template-file mysql-cdc.yaml \
--stack-name hudi-dms-s3 \
--region ap-southeast-1 \
--capabilities CAPABILITY_NAMED_IAM \
--parameter-overrides DBUsername=<username> DBPassword=<password> MySQlVPC=<VPC> \
MySQlSubnetB=<private-subnet1-for-dms-replica-instance> MySQlSubnetA=<private-subnet2-for-dms-replica-instance> \
MySQlSubnetPublicB=<subnet1-for-rds> MySQlSubnetPublicA=<subnet2-for-rds> \
EC2KeyName=<keyname> DMSTarget=s3

```
3. Create the table.
```
CREATE TABLE `trade_info_v2` (
  `tradeid` varchar(20) NOT NULL,
  `orderid` varchar(20) DEFAULT NULL,
  `customerid` varchar(20) DEFAULT NULL,
  `portfolioid` varchar(20) DEFAULT NULL,
  `buy` varchar(5) DEFAULT NULL,
  `price` decimal(6,2) DEFAULT NULL,
  `quantity` decimal(6,2) DEFAULT NULL,
  `symbol` varchar(5) DEFAULT NULL,
  `description` varchar(20) DEFAULT NULL,
  `tradername` varchar(20) DEFAULT NULL,
  `traderfirm` varchar(20) DEFAULT NULL,
  `orderfimestamp` datetime DEFAULT NULL,
  `update_timestamp` datetime DEFAULT NULL,
  `updated_by` varchar(20) DEFAULT NULL,
  PRIMARY KEY (`tradeid`)
) ENGINE=InnoDB;

```
4. Start the repliation task. 
5. The task will copy full load and CDC files in the same path on S3. 
# Full Load
1. Copy full load files to a separate location. This is required as full
```
export S3_BUCKET=<>
aws s3 mv s3://${S3_BUCKET}/dmsdata/mydb/trade_info_v2/ \
 s3://${S3_BUCKET}/dmsdata/data-full/mydb/trade_info_v2/ \
 --exclude "*" --include "LOAD*.parquet" --recursive

```
2. Copy the schema files from schema folder to S3.
```
export S3_BUCKET=<>
aws s3 cp --recursive schema s3://${S3_BUCKET}/schema
```
3. Create EMR cluster for version emr-6.5.0 with Spark and Hive enabled. Following is a sample CLI command.  
```
aws emr create-cluster --applications Name=Hive Name=Spark  \
--ec2-attributes '{"KeyName":"sin-isen-key","InstanceProfile":"EMR_EC2_DefaultRole","SubnetId":"subnet-020efe5c4105e1ab3","EmrManagedSlaveSecurityGroup":"sg-0b7bb151af4cd820a","EmrManagedMasterSecurityGroup":"sg-044cff46f29169cac"}' \
--release-label emr-6.5.0 \
--log-uri 's3n://<S3-BUCKET>/elasticmapreduce/' \
--instance-groups '[{"InstanceCount":2,"EbsConfiguration":{"EbsBlockDeviceConfigs":[{"VolumeSpecification":{"SizeInGB":32,"VolumeType":"gp2"},"VolumesPerInstance":2}]},"InstanceGroupType":"CORE","InstanceType":"m5.xlarge","Name":"Core - 2"},{"InstanceCount":1,"EbsConfiguration":{"EbsBlockDeviceConfigs":[{"VolumeSpecification":{"SizeInGB":32,"VolumeType":"gp2"},"VolumesPerInstance":2}]},"InstanceGroupType":"MASTER","InstanceType":"m5.xlarge","Name":"Master - 1"}]' \
--configurations '[{"Classification":"hive-site","Properties":{"hive.metastore.client.factory.class":"com.amazonaws.glue.catalog.metastore.AWSGlueDataCatalogHiveClientFactory"}},{"Classification":"spark-hive-site","Properties":{"hive.metastore.client.factory.class":"com.amazonaws.glue.catalog.metastore.AWSGlueDataCatalogHiveClientFactory"}}]' \
--auto-scaling-role EMR_AutoScaling_DefaultRole \
--ebs-root-volume-size 50 \
--service-role EMR_DefaultRole \
--enable-debugging \
--auto-termination-policy '{"IdleTimeout":14400}' \
--name 'emr6.5' --scale-down-behavior TERMINATE_AT_TASK_COMPLETION \
--region ap-southeast-1
```   
4. SSH to the master node and run rest of the commands. 
5. Set up parameters. 
```
export HUDI_TABLE_NAME=trade_info_v4
export SOURCE_S3_PATH=s3://${S3_BUCKET}/dmsdata/data-full/mydb/trade_info_v2/
export TARGET_S3_PATH=s3://${S3_BUCKET}/deltastreamer/${HUDI_TABLE_NAME}/table
export GLUE_DB=hudiblogdb
export GLUE_TABLE=trade_info_v4
export SOURCE_SCHEMA_PATH=s3://${S3_BUCKET}/schema/source-full.avsc
export TARGET_SCHEMA_PATH=s3://${S3_BUCKET}/schema/target.avsc
export DATA_EXTRACTION_QUERY="select dms_received_ts,tradeid,orderid,customerid,portfolioid,buy,price,quantity,symbol,description,tradername,traderfirm, cast(orderfimestamp as string) as orderfimestamp,cast(update_timestamp as string) as update_timestamp ,updated_by,'I' as Op from <SRC>"
#export DATA_EXTRACTION_QUERY="select dms_received_ts,tradeid,orderid,customerid,portfolioid,buy,price,quantity,symbol,description,tradername,traderfirm, cast(orderfimestamp as string) as orderfimestamp,cast(to_date(update_timestamp,'yyyy-MM-dd') as string) as update_timestamp ,updated_by,'I' as Op from <SRC>"
export PARTITION_BY_COLS=symbol

```
6. Submit spark job for  Delta Streamer .
```
spark-submit \
--class  org.apache.hudi.utilities.deltastreamer.HoodieDeltaStreamer \
--jars `ls /usr/lib/hudi/hudi-utilities-bundle*.jar ` \
--table-type COPY_ON_WRITE --op UPSERT \
--target-base-path ${TARGET_S3_PATH} \
--target-table ${HUDI_TABLE_NAME}  \
--min-sync-interval-seconds 60 \
--source-class org.apache.hudi.utilities.sources.ParquetDFSSource \
--source-ordering-field dms_received_ts \
--transformer-class org.apache.hudi.utilities.transform.SqlQueryBasedTransformer \
--payload-class org.apache.hudi.common.model.AWSDmsAvroPayload \
--schemaprovider-class org.apache.hudi.utilities.schema.FilebasedSchemaProvider \
--hoodie-conf hoodie.deltastreamer.source.dfs.root=${SOURCE_S3_PATH} \
--hoodie-conf hoodie.deltastreamer.schemaprovider.source.schema.file=${SOURCE_SCHEMA_PATH} \
--hoodie-conf hoodie.deltastreamer.schemaprovider.target.schema.file=${TARGET_SCHEMA_PATH} \
--hoodie-conf hoodie.deltastreamer.transformer.sql="${DATA_EXTRACTION_QUERY}" \
--hoodie-conf hoodie.datasource.write.recordkey.field=tradeid \
--hoodie-conf hoodie.datasource.hive_sync.partition_fields="${PARTITION_BY_COLS}" \
--hoodie-conf hoodie.datasource.write.partitionpath.field="${PARTITION_BY_COLS}" \
--enable-hive-sync \
--hoodie-conf hoodie.datasource.hive_sync.partition_extractor_class=org.apache.hudi.hive.MultiPartKeysValueExtractor \
--hoodie-conf hoodie.datasource.write.hive_style_partitioning=true \
--hoodie-conf hoodie.datasource.hive_sync.database=${GLUE_DB} \
--hoodie-conf hoodie.datasource.hive_sync.table=${GLUE_TABLE} \
--hoodie-conf hoodie.datasource.hive_sync.assume_date_partitioning=false \
--hoodie-conf hoodie.parquet.small.file.limit=134217728 \
--hoodie-conf hoodie.parquet.max.file.size=268435456 \
--hoodie-conf hoodie.cleaner.commits.retained=3 \
--hoodie-conf hoodie.upsert.shuffle.parallelism=2 \
--hoodie-conf hoodie.insert.shuffle.parallelism=2 \
--hoodie-conf hoodie.bulkinsert.shuffle.parallelism=2
```

# Incremental  load
1. Set up parameters.
```
export SOURCE_S3_PATH=s3://${S3_BUCKET}/dmsdata/mydb/trade_info_v2/
export SOURCE_SCHEMA_PATH=s3://burner-data-aksh/schema/source-incremental.avsc
export TARGET_SCHEMA_PATH=s3://burner-data-aksh/schema/target.avsc
export DATA_EXTRACTION_QUERY="select dms_received_ts,tradeid,orderid,customerid,portfolioid,buy,price,quantity,symbol,description,tradername,traderfirm, cast(orderfimestamp as string) as orderfimestamp,cast(update_timestamp as string) as update_timestamp ,updated_by,Op from <SRC>"

```
2. Submit the spark job. 
```
spark-submit \
--class  org.apache.hudi.utilities.deltastreamer.HoodieDeltaStreamer \
--jars `ls /usr/lib/hudi/hudi-utilities-bundle*.jar ` \
--table-type COPY_ON_WRITE --op UPSERT \
--target-base-path  ${TARGET_S3_PATH} \
--target-table ${HUDI_TABLE_NAME}  \
--min-sync-interval-seconds 60 \
--source-class org.apache.hudi.utilities.sources.ParquetDFSSource \
--source-ordering-field dms_received_ts \
--transformer-class org.apache.hudi.utilities.transform.SqlQueryBasedTransformer \
--payload-class org.apache.hudi.common.model.AWSDmsAvroPayload \
--schemaprovider-class org.apache.hudi.utilities.schema.FilebasedSchemaProvider \
--hoodie-conf hoodie.datasource.write.recordkey.field=tradeid \
--hoodie-conf hoodie.datasource.hive_sync.partition_fields="${PARTITION_BY_COLS}" \
--hoodie-conf hoodie.datasource.write.partitionpath.field="${PARTITION_BY_COLS}" \
--enable-hive-sync \
--continuous \
--hoodie-conf hoodie.deltastreamer.source.dfs.root=${SOURCE_S3_PATH} \
--hoodie-conf hoodie.deltastreamer.schemaprovider.source.schema.file=${SOURCE_SCHEMA_PATH} \
--hoodie-conf hoodie.deltastreamer.schemaprovider.target.schema.file=${TARGET_SCHEMA_PATH} \
--hoodie-conf hoodie.deltastreamer.transformer.sql="${DATA_EXTRACTION_QUERY}" \
--hoodie-conf hoodie.datasource.hive_sync.partition_extractor_class=org.apache.hudi.hive.MultiPartKeysValueExtractor \
--hoodie-conf hoodie.datasource.write.hive_style_partitioning=true \
--hoodie-conf hoodie.datasource.hive_sync.database=${GLUE_DB} \
--hoodie-conf hoodie.datasource.hive_sync.table=${GLUE_TABLE} \
--hoodie-conf hoodie.datasource.hive_sync.assume_date_partitioning=false \
--hoodie-conf hoodie.parquet.small.file.limit=134217728 \
--hoodie-conf hoodie.parquet.max.file.size=268435456 \
--hoodie-conf hoodie.cleaner.commits.retained=3 \
--hoodie-conf hoodie.upsert.shuffle.parallelism=2 \
--hoodie-conf hoodie.insert.shuffle.parallelism=2 \
--hoodie-conf hoodie.bulkinsert.shuffle.parallelism=2
```
