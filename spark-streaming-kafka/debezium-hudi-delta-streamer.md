### Create EMR Cluster
```shell
aws emr create-cluster --os-release-label 2.0.20220606.1 \
--applications Name=Spark Name=Livy Name=Hive \
--ec2-attributes '{"KeyName":"key-us-east","InstanceProfile":"EMR_EC2_DefaultRole","SubnetId":"subnet-0029398405889ec7e","EmrManagedSlaveSecurityGroup":"sg-0e5ebd7ddc9f987e7","EmrManagedMasterSecurityGroup":"sg-0ccba99de55639417"}' \
--release-label emr-6.7.0 --log-uri 's3n://aws-logs-ACCOUNT-us-east-1/elasticmapreduce/' \
--instance-groups '[{"InstanceCount":2,"EbsConfiguration":{"EbsBlockDeviceConfigs":[{"VolumeSpecification":{"SizeInGB":32,"VolumeType":"gp2"},"VolumesPerInstance":2}]},"InstanceGroupType":"CORE","InstanceType":"m5.xlarge","Name":"Core - 2"},{"InstanceCount":1,"EbsConfiguration":{"EbsBlockDeviceConfigs":[{"VolumeSpecification":{"SizeInGB":32,"VolumeType":"gp2"},"VolumesPerInstance":2}]},"InstanceGroupType":"MASTER","InstanceType":"m5.xlarge","Name":"Master - 1"}]' \
--configurations '[{"Classification":"hive-site","Properties":{"hive.metastore.client.factory.class":"com.amazonaws.glue.catalog.metastore.AWSGlueDataCatalogHiveClientFactory"}},{"Classification":"spark-hive-site","Properties":{"hive.metastore.client.factory.class":"com.amazonaws.glue.catalog.metastore.AWSGlueDataCatalogHiveClientFactory"}}]' \
--auto-scaling-role EMR_AutoScaling_DefaultRole \
--ebs-root-volume-size 40 \
--service-role EMR_DefaultRole \
--enable-debugging \
--auto-termination-policy '{"IdleTimeout":3600}' \
--name 'My cluster6.7' \
--scale-down-behavior TERMINATE_AT_TASK_COMPLETION \
--region us-east-1
```
### Create MSK
### Create Kafka Connect
```
docker-compose -f docker-compose-confluent-schema.yaml up 
```
```
docker-compose -f docker-compose-confluent-schema.yaml down
```
### Create Connector
```
curl -i -X POST -H "Accept:application/json"     -H  "Content-Type:application/json"     http://localhost:8083/connectors/ -d @register-mysql-confluent-registry.json 
curl http://localhost:8083/connectors
curl http://localhost:8083/connectors/rdsmysql-dev-connector-confluent-avro
curl -X DELETE http://localhost:8083/connectors/rdsmysql-dev-connector-confluent-avro
```
### Update/Insert into table

### Submit Hudi Delta Streamer Job
```shell
spark-submit \
--class  org.apache.hudi.utilities.deltastreamer.HoodieDeltaStreamer \
--jars `ls /usr/lib/hudi/hudi-utilities-bundle*.jar ` \
--table-type COPY_ON_WRITE --op UPSERT \
--target-base-path s3://akshaya-hudi/deltastreamer/mpower_trade_info_v2/table \
--target-table mpower_trade_info_v2  --continuous \
--min-sync-interval-seconds 60 \
--source-class org.apache.hudi.utilities.sources.debezium.MysqlDebeziumSource \
--source-ordering-field update_timestamp \
--payload-class org.apache.hudi.common.model.debezium.MySqlDebeziumAvroPayload \
--hoodie-conf schema.registry.url=http://ip-10-0-1-38.ec2.internal:8081 \
--hoodie-conf hoodie.deltastreamer.schemaprovider.registry.url=http://ip-10-0-1-38.ec2.internal:8081/subjects/mpower.test2.trade_info_v2-value/versions/latest \
--hoodie-conf hoodie.deltastreamer.source.kafka.value.deserializer.class=io.confluent.kafka.serializers.KafkaAvroDeserializer \
--hoodie-conf hoodie.deltastreamer.source.kafka.topic=mpower.test2.trade_info_v2 \
--hoodie-conf bootstrap.servers=b-1.mskclustermskconnectl.kor5wp.c23.kafka.us-east-1.amazonaws.com:9092,b-2.mskclustermskconnectl.kor5wp.c23.kafka.us-east-1.amazonaws.com:9092,b-3.mskclustermskconnectl.kor5wp.c23.kafka.us-east-1.amazonaws.com:9092 \
--hoodie-conf auto.offset.reset=earliest \
--hoodie-conf hoodie.datasource.write.recordkey.field=tradeid \
--hoodie-conf hoodie.datasource.write.partitionpath.field=symbol \
--enable-hive-sync \
--hoodie-conf hoodie.datasource.hive_sync.partition_extractor_class=org.apache.hudi.hive.MultiPartKeysValueExtractor \
--hoodie-conf hoodie.datasource.write.hive_style_partitioning=true \
--hoodie-conf hoodie.datasource.hive_sync.database=hudi \
--hoodie-conf hoodie.datasource.hive_sync.table=mpower_trade_info_v2 \
--hoodie-conf hoodie.datasource.hive_sync.partition_fields=symbol 
```