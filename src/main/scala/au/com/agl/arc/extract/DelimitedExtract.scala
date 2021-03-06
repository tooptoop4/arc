package au.com.agl.arc.extract

import java.lang._
import java.net.URI
import scala.collection.JavaConverters._

import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.storage.StorageLevel

import au.com.agl.arc.api._
import au.com.agl.arc.api.API._
import au.com.agl.arc.util._

object DelimitedExtract {

  def extract(extract: DelimitedExtract)(implicit spark: SparkSession, logger: au.com.agl.arc.util.log.logger.Logger): Option[DataFrame] = {
    import spark.implicits._
    val startTime = System.currentTimeMillis() 
    val stageDetail = new java.util.HashMap[String, Object]()
    val contiguousIndex = extract.contiguousIndex.getOrElse(true)
    stageDetail.put("type", extract.getType)
    stageDetail.put("name", extract.name)
    stageDetail.put("persist", Boolean.valueOf(extract.persist))
    stageDetail.put("outputView", extract.outputView)
    stageDetail.put("contiguousIndex", Boolean.valueOf(contiguousIndex))

    val options: Map[String, String] = Delimited.toSparkOptions(extract.settings)

    val inputValue = extract.input match {
      case Right(glob) => glob
      case Left(view) => view
    }

    stageDetail.put("input", inputValue)  
    stageDetail.put("options", options.asJava)

    logger.info()
      .field("event", "enter")
      .map("stage", stageDetail)      
      .log()    
    
    val df = try {
      extract.input match {
        case Right(glob) =>
          CloudUtils.setHadoopConfiguration(extract.authentication)

          try {
            spark.read.options(options).csv(glob)
          } catch {
            case e: AnalysisException if (e.getMessage == "Unable to infer schema for CSV. It must be specified manually.;") || (e.getMessage.contains("Path does not exist")) => 
              spark.emptyDataFrame
            case e: Exception => throw e
          }
        case Left(view) => spark.read.options(options).csv(spark.table(view).as[String])
      }         
    } catch { 
      case e: Exception => throw new Exception(e) with DetailException {
        override val detail = stageDetail          
      }    
    }

    // if incoming dataset has 0 columns then create empty dataset with correct schema
    val emptyDataframeHandlerDF = if (df.schema.length == 0) {
      val schema = extract.cols match {
        case Nil => throw new Exception(s"DelimitedExtract has produced 0 columns and no schema has been provided to create an empty dataframe.") with DetailException {
          override val detail = stageDetail          
        }
        case cols => Extract.toStructType(cols)
      }
      spark.createDataFrame(spark.sparkContext.emptyRDD[Row], schema)
    } else {
      df
    }        

    // add source data including index
    val sourceEnrichedDF = ExtractUtils.addSourceMetadata(emptyDataframeHandlerDF, contiguousIndex)

    // set column metadata if exists
    val enrichedDF = extract.cols match {
      case Nil => sourceEnrichedDF
      case cols => MetadataUtils.setMetadata(sourceEnrichedDF, Extract.toStructType(cols))
    }
         
    // repartition to distribute rows evenly
    val repartitionedDF = extract.partitionBy match {
      case Nil => { 
        extract.numPartitions match {
          case Some(numPartitions) => enrichedDF.repartition(numPartitions)
          case None => enrichedDF
        }   
      }
      case partitionBy => {
        // create a column array for repartitioning
        val partitionCols = partitionBy.map(col => df(col))
        extract.numPartitions match {
          case Some(numPartitions) => enrichedDF.repartition(numPartitions, partitionCols:_*)
          case None => enrichedDF.repartition(partitionCols:_*)
        }
      }
    } 
    repartitionedDF.createOrReplaceTempView(extract.outputView)

    stageDetail.put("inputFiles", Integer.valueOf(repartitionedDF.inputFiles.length))
    stageDetail.put("outputColumns", Integer.valueOf(repartitionedDF.schema.length))
    stageDetail.put("numPartitions", Integer.valueOf(repartitionedDF.rdd.partitions.length))

    if (extract.persist) {
      repartitionedDF.persist(StorageLevel.MEMORY_AND_DISK_SER)
      stageDetail.put("records", Long.valueOf(repartitionedDF.count)) 
    }    

    logger.info()
      .field("event", "exit")
      .field("duration", System.currentTimeMillis() - startTime)
      .map("stage", stageDetail)      
      .log()

    Option(repartitionedDF)
  }

}