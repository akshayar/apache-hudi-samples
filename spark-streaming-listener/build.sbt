name := "spark-structured-streaming-listener"

version := "1.0"
val sparkVersion = "3.3.1"
val scala_tool_version="2.12"

libraryDependencies += "org.apache.spark" %% "spark-core" % sparkVersion
libraryDependencies += "org.apache.spark" %% "spark-sql" % sparkVersion
libraryDependencies += "org.apache.spark" % "spark-sql-kafka-0-10_2.12" % sparkVersion
libraryDependencies += "org.apache.spark" % "spark-streaming-kafka-0-10_2.12" % sparkVersion


fork in run := true

