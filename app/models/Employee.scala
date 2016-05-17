package models

import reactivemongo.bson.BSONObjectID
import java.util.Date

case class Employee(_id: BSONObjectID,
                    name: String,
                    address: String,
                    dob: Date,
                    joiningDate: Date,
                    designation: String)

object JsonFormats {
  import play.api.libs.json.Json
  import play.api.data._
  import play.api.data.Forms._
  import play.modules.reactivemongo.json.BSONFormats._

  // Generates Writes and Reads for Feed and User thanks to Json Macros
  implicit val employeeFormat = Json.format[Employee]
}