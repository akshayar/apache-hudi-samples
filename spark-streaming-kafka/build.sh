#!/bin/bash
S3_BUCKET=$1
if [ -z "$S3_BUCKET" ] 
then
  S3_BUCKET=aksh-test-versioning
fi

sbt clean package
#sbt assembly
ls target/scala-2.12/*.jar
aws s3 cp  target/scala-2.12/spark-structured-streaming-kafka-hudi_2.12-1.0.jar s3://$S3_BUCKET/

#aws s3 cp target/scala-2.11/Spark-Structured-Streaming-Hudi-assembly-1.0.jar s3://$S3_BUCKET/