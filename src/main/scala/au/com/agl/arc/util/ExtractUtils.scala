package au.com.agl.arc.util

import java.net.URI
import java.time.Instant

import org.apache.spark.sql._
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.storage.StorageLevel

object ExtractUtils {

  def addSourceMetadata(input: DataFrame, contiguousIndex: Boolean): DataFrame = {
    // add meta columns including sequential index
    // if schema already has metadata any columns ignore
    if (!input.schema.map(_.name).intersect(List("_index","_monotonically_increasing_id")).nonEmpty) {
      val window = Window.partitionBy("_filename").orderBy("_monotonically_increasing_id")
      if (contiguousIndex) {
        input
          .withColumn("_monotonically_increasing_id", monotonically_increasing_id())
          .withColumn("_filename", input_file_name().as("_filename", new MetadataBuilder().putBoolean("internal", true).build()))
          .withColumn("_index", row_number().over(window).as("_index", new MetadataBuilder().putBoolean("internal", true).build()))
          .drop("_monotonically_increasing_id")
      } else {
        input
          .withColumn("_monotonically_increasing_id", monotonically_increasing_id().as("_monotonically_increasing_id", new MetadataBuilder().putBoolean("internal", true).build()))
          .withColumn("_filename", input_file_name().as("_filename", new MetadataBuilder().putBoolean("internal", true).build()))
      }
    } else {
      input
    }
  }
}
