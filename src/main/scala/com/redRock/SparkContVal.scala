package com.redRock

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import org.apache.spark.sql.{SQLContext, DataFrame}

object SparkContVal 
{
	val conf = new SparkConf()
    //conf.setMaster(masterNode)
    conf.setAppName(Config.appName)
    conf.set("spark.scheduler.mode", "FAIR")

    val sc = new SparkContext(conf)
    val sqlContext = new SQLContext(sc)
}