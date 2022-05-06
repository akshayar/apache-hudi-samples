name := "Spark-Structured-Streaming-Kinesis-Hudi"

version := "1.0"
val sparkVersion = "3.1.1"
val scala_tool_version="2.12"

resolvers += Resolver.mavenLocal

libraryDependencies += "log4j" % "log4j" % "1.2.14"

libraryDependencies += "org.apache.spark" %% "spark-core" % sparkVersion
libraryDependencies += "org.apache.spark" %% "spark-sql" % sparkVersion



libraryDependencies += "com.qubole.spark" % "spark-sql-kinesis_2.12" % "1.2.1_spark-3.0-SNAPSHOT"
libraryDependencies += "org.apache.hudi" % "hudi-spark3-bundle_2.12" % "0.9.0"
libraryDependencies += "com.amazonaws" % "aws-java-sdk-ssm" % "1.12.1"
libraryDependencies += "org.apache.spark" % "spark-streaming-kinesis-asl_2.12" % "3.1.1"


fork in run := true

assemblyMergeStrategy in assembly := {
  case "META-INF/services/org.apache.spark.sql.sources.DataSourceRegister" => MergeStrategy.concat
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case x => MergeStrategy.first
}

assemblyExcludedJars in assembly := {
    val cp = (fullClasspath in assembly).value
    cp filter { f =>
      f.data.getName.contains("spark-core")
    }
  }