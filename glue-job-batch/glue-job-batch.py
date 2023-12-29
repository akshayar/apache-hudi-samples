from datetime import datetime
import sys
import json
from pyspark.sql.functions import col, isnan, when, count
from awsglue.utils import getResolvedOptions
from pyspark.conf import SparkConf
from pyspark.context import SparkContext
from awsglue.context import GlueContext
from pyspark.sql import functions as F
from pyspark.sql.types import StringType,StructType


HUDI_FORMAT = "org.apache.hudi"
target_path="s3://akshaya-hudi/schema-evolution/"


def create_savepoint(spark, logger):


    db_name = 'hudi'
    tbl_name = 'hudi_schema_evolution_2'


    show_commits =  f"""call show_commits(table => '{db_name}.{tbl_name}', limit => 10);
                """
    logger.info(show_commits)

    show_commits_out = spark.sql(show_commits)

    delete_savepoint= f"""call delete_savepoint(table => '{db_name}.{tbl_name}', instant_time => '20230214082213821');
                """
    spark.sql(delete_savepoint)

    query =  f"""call create_savepoint(table => '{db_name}.{tbl_name}', commit_time => '20230214082213821');
               """
    logger.info(query)

    df_schema_evol = spark.sql(query)

def bootstrap(spark, logger):
    sourceDataPath = "s3://hudi-dms-s3-dmss3stack-jq07on48olk9-dmss3bucket-gc19ae5ijmj4/dmsdata/mydb/trade_info_v2/"
    hudiTablePath = "s3://akshaya-emr-on-eks/hudi/trade_info_v2_hudi/"


    hudi_options = {'hoodie.table.name': 'noaa_gsod_pds_es311c_aggregate_hudi',
                    'hoodie.datasource.write.recordkey.field': 'tradeid',
                    'hoodie.datasource.write.operation': 'bootstrap',
                    'hoodie.bootstrap.base.path': sourceDataPath,
                    'hoodie.bootstrap.keygen.class': 'org.apache.hudi.keygen.NonpartitionedKeyGenerator',
                    'hoodie.bootstrap.extractor_class': 'org.apache.hudi.extractor.NonpartitionedExtractor',
                    'hoodie.datasource.hive_sync.enable':'true',
                    'hoodie.datasource.hive_sync.table':'trade_info_v2_hudi',
                    'hoodie.datasource.hive_sync.database':'hudi2',
                    'hoodie.datasource.hive_sync.mode':'hms',
                    'hoodie.bootstrap.mode_selector_class': 'org.apache.hudi.client.bootstrap.selector.MetadataOnlyBootstrapModeSelector',}

    bootstrapDF = spark.createDataFrame([], StructType([]))
    bootstrapDF.write.format("hudi").options(**hudi_options).mode("overwrite").save(hudiTablePath)


def read(spark, logger):
    hudiTablePath = "s3://akshaya-emr-on-eks/hudi/trade_info_v2_hudi/"
    df=spark.read.format("hudi").load(hudiTablePath)
    df.show()


def start_spark(log_level):
    conf =  SparkConf()
    conf.set("spark.sql.legacy.parquet.int96RebaseModeInRead", "CORRECTED")
    conf.set("spark.sql.legacy.parquet.int96RebaseModeInWrite", "CORRECTED")
    conf.set("spark.sql.legacy.parquet.datetimeRebaseModeInRead", "CORRECTED")
    conf.set("spark.sql.legacy.parquet.datetimeRebaseModeInWrite", "CORRECTED")

    conf.set("spark.sql.extensions", "org.apache.spark.sql.hudi.HoodieSparkSessionExtension")
    conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    conf.set("spark.sql.hive.convertMetastoreParquet", "false")

    sc = SparkContext(conf=conf)
    sc.setLogLevel(log_level)
    glueContext = GlueContext(sc)
    logger = glueContext.get_logger()
    spark = glueContext.spark_session
    return spark, logger


try:
    spark, logger = start_spark('INFO')
    bootstrap(spark, logger)
    read(spark, logger)
    #inital_run(spark, logger)
    #create_savepoint(spark, logger)


    logger.info("Job finished successfully!")

except Exception as ex:
    raise ex