/**
 * (C) Copyright IBM Corp. 2015, 2015
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.powertrack

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import org.apache.spark.sql.{SQLContext, DataFrame, SaveMode}
import org.apache.spark.streaming._
import org.apache.spark.streaming.StreamingContext._
import org.apache.spark.streaming.dstream.DStream
import org.slf4j.LoggerFactory
import scala.util.matching.Regex
import scala.concurrent.{Future,future, Await}
import scala.concurrent.ExecutionContext.Implicits.global
import org.apache.spark.rdd.RDD
import java.io._
import org.apache.spark.sql.Row
import org.elasticsearch.spark._ 
import org.elasticsearch.spark.sql._

import org.apache.hadoop.fs.{FileSystem, Path, PathFilter}
import play.api.libs.json._
import org.apache.hadoop.io.{LongWritable, Text}
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat

object PrepareTweets
{
    //Extract file names
    val regExp = "\\b(hdfs:|file:)\\S+".r
    val logger = LoggerFactory.getLogger(this.getClass)

    def startTweetsStreaming() =
    {
        logger.info(s"""Starting Streaming at: ${LoadConf.sparkConf.getString("powertrack.twitterStreamingDataPath")}""")
        logger.info(s"""Partition number: ${LoadConf.sparkConf.getInt("partitionNumber")}""")

        val ssc = createContext()
        logger.info("Starting Streaming")
        ssc.start()
        ssc.awaitTermination()
    }

    def createContext(): StreamingContext = {
        
        logger.info("Creating streaming new context")
        // Create the context with a 1 second batch size
        val ssc = new StreamingContext(ApplicationContext.sparkContext, Seconds(LoadConf.sparkConf.getInt("powertrack.streamingBatchTime")))

        //Filtering file's path in order to avoid _copying files
        val tweetsStreaming = ssc.fileStream[LongWritable, Text, TextInputFormat](LoadConf.sparkConf.getString("powertrack.twitterStreamingDataPath"),
            (p: Path) => {
              if (p.getName().toLowerCase().endsWith(LoadConf.sparkConf.getString("powertrack.fileExtension"))) true else false
            }, true).map(_._2.toString)
        //ssc.textFileStream(LoadConf.sparkConf.getString("powertrack.twitterStreamingDataPath"))
         
        tweetsStreaming.foreachRDD{ (rdd: RDD[String], time: Time) =>
            logger.info(s"========= $time =========")
            if(!rdd.partitions.isEmpty)
            {
                logger.info("Processing File(s):")
                regExp.findAllMatchIn(rdd.toDebugString).foreach((name) => logger.info(name.toString))
                loadJSONExtractInfoWriteToDatabase(rdd)
            }
        }

        return ssc
    }

    def loadJSONExtractInfoWriteToDatabase(rdd: RDD[String]) = 
    {
        try
        {
          /*Get each tweet as one line result*/
          val tweets = rdd.flatMap(file => (Json.parse(file) \ TweetField.jsonPrefix).as[List[JsObject]]).map(tweet => Json.stringify(tweet))
          if(tweets.count() > 0) {
            ApplicationContext.sqlContext.read.json(tweets)
              .filter(s"${TweetField.verb} = 'post' OR ${TweetField.verb} = 'share'")
              .selectExpr(s"${TweetField.tweet_id} as tweet_id",
                s"${TweetField.tweet_created_at} AS created_at",
                s"${TweetField.language} AS language",
                s"${TweetField.tweet_text} AS tweet_text",
                s"${TweetField.user_followers_count} AS user_followers_count",
                s"${TweetField.user_handle} AS user_handle",
                s"${TweetField.user_id} AS user_id",
                s"${TweetField.user_profileImgURL} AS user_image_url",
                s"${TweetField.user_name} user_name",
                s"stringTokenizer(${TweetField.tweet_text}) AS tweet_text_array_tokens")
              .write.mode(SaveMode.Append)
              .format("org.elasticsearch.spark.sql")
              .options(Map("pushdown" -> "true", "es.nodes" -> LoadConf.esConf.getString("bindIP"), "es.port" -> LoadConf.esConf.getString("bindPort")))
              .save( s"""${LoadConf.esConf.getString("powertrackIndexName")}/${LoadConf.esConf.getString("esType")}""")
          }
          else
          {
            logger.warn("###### Empty File ######")
          }

          if (LoadConf.sparkConf.getBoolean("powertrack.deleteProcessedFiles"))
          {
            //Delete files if they where processed
            logger.info("Deleting File(s):")
            regExp.findAllMatchIn(rdd.toDebugString).foreach((name) => deleteFile(name.toString))
          }
        }
        catch {
          case e: Exception => 
          {
            logger.error("Processing Tweets",e)
            logger.error("##### Files not processed #####")
          }
        }
    }

    def deleteFile(fileName: String) =
    {
        val filePath = new Path(fileName)
        if (ApplicationContext.hadoopFS.isDirectory(filePath))
        {
            ApplicationContext.hadoopFS.listStatus(filePath).foreach((status) => {
                                                        logger.info(status.getPath().toString)
                                                        ApplicationContext.hadoopFS.delete(status.getPath(), true)
                                                    })
        }
        else
        {
            logger.info(fileName)
            ApplicationContext.hadoopFS.delete(filePath, true)
        }
    }
}
