package spark.listener

import org.apache.spark.streaming._
import org.apache.spark.sql.types._
import org.apache.spark.sql.streaming._
import org.apache.spark.sql.streaming.StreamingQueryListener
import org.apache.spark.sql.streaming.StreamingQueryListener._
import org.apache.spark.sql.{SQLContext, SparkSession}

class StreamingListener(spark: SparkSession) {
  def listen(): Unit = {
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
      }
    )
  }
}
