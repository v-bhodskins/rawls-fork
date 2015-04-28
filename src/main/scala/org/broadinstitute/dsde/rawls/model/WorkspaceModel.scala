package org.broadinstitute.dsde.rawls.model

import com.wordnik.swagger.annotations.{ApiModelProperty, ApiModel}
import org.joda.time.DateTime
import org.joda.time.format.{ISODateTimeFormat, DateTimeFormatter}
import spray.json._

import scala.annotation.meta.field

/**
 * Created by dvoet on 4/24/15.
 */
@ApiModel(value = "Workspace")
case class Workspace(
                      @(ApiModelProperty@field)(required = true, value = "The namespace the workspace belongs to")
                      namespace: String,
                      @(ApiModelProperty@field)(required = true, value = "The name of the workspace")
                      name: String,
                      @(ApiModelProperty@field)(required = true, value = "The date the workspace was created in yyyy-MM-dd'T'HH:mm:ssZZ format")
                      createdDate: DateTime,
                      @(ApiModelProperty@field)(required = true, value = "The user who created the workspace")
                      createdBy: String,
                      @(ApiModelProperty@field)(required = true, value = "Entities in the workspace, first key: entity type, second key: entity name")
                      entities: Map[String, Map[String, Entity]])

@ApiModel(value = "Entity")
case class Entity(
                   @(ApiModelProperty@field)(required = true, value = "The name of the entity")
                   name: String,
                   @(ApiModelProperty@field)(required = true, value = "The attributes of the entity")
                   attributes: Map[String, Attribute])

trait Attribute

case class AttributeString(val value: String) extends Attribute
case class AttributeNumber(val value: BigDecimal) extends Attribute
case class AttributeBoolean(val value: Boolean) extends Attribute
case class AttributeList(val value: Seq[Attribute]) extends Attribute
case class AttributeReference(val entityType: String, val entityName: String) extends Attribute {
  def resolve(context: Workspace): Option[Entity] = {
    context.entities.getOrElse(entityType, Map.empty).get(entityName)
  }
}


object WorkspaceJsonSupport extends DefaultJsonProtocol {
  implicit object AttributeFormat extends RootJsonFormat[Attribute] {

    override def write(obj: Attribute): JsValue = obj match {
      case AttributeBoolean(b) => JsBoolean(b)
      case AttributeNumber(n) => JsNumber(n)
      case AttributeString(s) => JsString(s)
      case AttributeList(l) => JsArray(l.map(write(_)):_*)
      case AttributeReference(entityType, entityName) => JsObject(Map("entityType" -> JsString(entityType), "entityName" -> JsString(entityName)))
    }

    override def read(json: JsValue) : Attribute = json match {
      case JsString(s) => AttributeString(s)
      case JsBoolean(b) => AttributeBoolean(b)
      case JsNumber(n) => AttributeNumber(n)
      case JsArray(a) => AttributeList(a.map(read(_)))
      case JsObject(members) => AttributeReference(members("entityType").asInstanceOf[JsString].value, members("entityName").asInstanceOf[JsString].value)

      case _ => throw new DeserializationException("unexpected json type")
    }
  }

  implicit val EntityFormat = jsonFormat2(Entity)

  implicit object DateJsonFormat extends RootJsonFormat[DateTime] {

    private val parserISO : DateTimeFormatter = ISODateTimeFormat.dateTimeNoMillis();

    override def write(obj: DateTime) = JsString(parserISO.print(obj))

    override def read(json: JsValue) : DateTime = json match {
      case JsString(s) => parserISO.parseDateTime(s)
      case _ => throw new DeserializationException("only string supported")
    }
  }

  implicit val WorkspaceFormat = jsonFormat5(Workspace)
}
