package au.com.agl.arc.util

import java.time.LocalTime

import scala.collection.JavaConverters._

import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.databind.node._

import au.com.agl.arc.api.API._

object MetadataSchema {

  type ParseResult = Either[List[String], List[ExtractColumn]]

  def parseJsonMetadata(sourceJson: String): ParseResult = {

    val objectMapper = new ObjectMapper()
    val rootNode = objectMapper.readTree(sourceJson)

    val res: ParseResult = if (!rootNode.isArray) {
      Left("Expected Json Array" :: Nil)
    } else {
      val elements = rootNode.elements().asScala.toList 
      val cols: List[Either[String, ExtractColumn]] = for (n <- elements) yield {
        if (n.isObject) {
          // attributes
          val id = n.get("id").textValue
          val name = n.get("name").textValue
          val description = if (n.has("description")) Option(n.get("description").textValue) else None
          
          // behaviours
          val trim = if(n.has("trim")) n.get("trim").asBoolean else false
          val nullable = n.get("nullable").asBoolean
          val nullReplacementValue = if(n.has("nullReplacementValue")) Option(n.get("nullReplacementValue").textValue) else None
          val nullableValues = {
            if (n.has("nullableValues")) {
              val nullableValueElements = n.get("nullableValues")
              assert(nullableValueElements.isArray, s"Expected Json String Array for nullableValues for column $name")

              nullableValueElements.elements.asScala.filter( _.isTextual ).map(_.textValue).toList
            } else {
              Nil
            }
          }

          val metadata: Either[String, Option[String]] = if( n.has("metadata") ) {
            val valid = validateMetadata(name, n.get("metadata"))
            val isValid = valid.filter(_.isLeft).size == 0
            if (isValid) {
              Right(Option(objectMapper.writeValueAsString(n.get("metadata"))))
            } else {
              Left(valid.collect { case Left(error) => error }.mkString(", "))
            }
          } else {
            Right(None)
          }

          

          metadata match {
            case Left(errors) => Left("invalid metadata: " + errors)
            case Right(metadata) => 

              n.get("type").textValue match {
                case "string" => {
                  val length = if (n.has("length")) Option(n.get("length").asInt) else None
                  Right(StringColumn(id, name, description, nullable, nullReplacementValue, trim, nullableValues, metadata))
                }
                case "integer" => Right(IntegerColumn(id, name, description, nullable, nullReplacementValue, trim, nullableValues, metadata))
                case "long" => Right(LongColumn(id, name, description, nullable, nullReplacementValue, trim, nullableValues, metadata))
                case "double" => Right(DoubleColumn(id, name, description, nullable, nullReplacementValue, trim, nullableValues, metadata))
                case "decimal" => {
                  val precision = n.get("precision").asInt
                  val scale = n.get("scale").asInt
                  val formatter = if (n.has("formatter")) Option(n.get("formatter").textValue) else None
                  Right(DecimalColumn(id, name, description, nullable, nullReplacementValue, trim, nullableValues, precision, scale, formatter, metadata))
                }
                case "boolean" => {
                  val trueValues = asStringArray(n.get("trueValues"))
                  val falseValues = asStringArray(n.get("falseValues"))
                  Right(BooleanColumn(id, name, description, nullable, nullReplacementValue, trim, nullableValues, trueValues, falseValues, metadata))
                }
                case "timestamp" => {
                  val formatters = asStringArray(n.get("formatters"))
                  val tz = n.get("timezoneId").textValue
                  val time = n.has("time") match {
                    case true => {
                      val timeConfig = n.get("time")
                      Option(LocalTime.of(timeConfig.get("hour").asInt, timeConfig.get("minute").asInt, timeConfig.get("second").asInt, timeConfig.get("nano").asInt))
                    }
                    case false => None
                  }
                  
                  Right(TimestampColumn(id, name, description, nullable, nullReplacementValue, trim, nullableValues, tz, formatters, time, metadata))
                }
                case "date" => {
                  val formatters = asStringArray(n.get("formatters"))
                  Right(DateColumn(id, name, description, nullable, nullReplacementValue, trim, nullableValues, formatters, metadata))
                }
                case "time" => {
                  val formatters = asStringArray(n.get("formatters"))
                  Right(TimeColumn(id, name, description, nullable, nullReplacementValue, trim, nullableValues, formatters, metadata))
                }            
                case unknown => Left(s"Unknown type '${unknown}' for column '${name}''")
              }
          }
        } else {
          Left("expected Json object at: " + elements.indexOf(n))
        }
      }

      val (errors, validCols) = cols.foldLeft[(List[String], List[ExtractColumn])](Nil, Nil){ case ((errors, validCols), c) =>
        c match {
          case Left(err) => (err :: errors, validCols)
          case Right(vc) => (errors, vc :: validCols)
        }
      }

      if (!errors.isEmpty) {
        throw new IllegalArgumentException(s"""schema error: ${errors.mkString(",")}""")
      }

      if (!errors.isEmpty) Left(errors.toList) else Right(validCols.toList.reverse)
    }

    res
  }

  def asStringArray(node: JsonNode): List[String] = {
    node.elements.asScala.filter( _.isTextual ).map( _.textValue ).toList
  }

  def validateMetadata(name: String, metadata: JsonNode): List[Either[String, Boolean]]  = {
    metadata.fields.asScala.toList.map(node => {
      if (node.getKey == name) {
        Left(s"Metadata in field '${name}' cannot contain key with same name as column.")
      } else {
        node.getValue.getNodeType match {
          case JsonNodeType.NULL => Left(s"Metadata in field '${name}' cannot contain `null` values.")
          case JsonNodeType.OBJECT => Left(s"Metadata in field '${name}' cannot contain nested `objects`.")
          case JsonNodeType.BINARY => Left(s"Metadata in field '${name}' cannot contain `binary` values.")
          case JsonNodeType.ARRAY => {
            if (node.getValue.size == 0) {
              Left(s"Metadata in field '${name}' cannot contain empty `arrays`.")
            } else {
              val nodeList = node.getValue.asScala.toList
              val nodeType = nodeList(0).getNodeType
              nodeType match {
                case JsonNodeType.NULL => Left(s"Metadata in field '${name}' cannot contain `null` values.")
                case JsonNodeType.OBJECT => Left(s"Metadata in field '${name}' cannot contain nested `objects`.")
                case JsonNodeType.ARRAY => Left(s"Metadata in field '${name}' cannot contain nested `arrays`.")  
                case _ => {
                  if (nodeList.forall(_.getNodeType == nodeType)) {
                    nodeType match {
                      case JsonNodeType.NUMBER => {
                        val numberType = nodeList(0).numberType
                        if (nodeList.forall(_.numberType == numberType)) {
                          Right(true)
                        } else {
                          Left(s"Metadata in field '${name}' cannot contain `number` arrays of different types (all must be integers or all doubles).")  
                        }                      
                      } 
                      case _ => Right(true)
                    }
                  } else {
                    Left(s"Metadata in field '${name}' cannot contain arrays of different types.")  
                  }
                }
              }
            }
          }
          case _ => Right(true)
        }
      }
    })
  }
}
