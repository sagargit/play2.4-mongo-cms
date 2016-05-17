package forms

import models.{SearchParams, Employee}
import play.api.data.Form
import play.api.data.Forms._
import reactivemongo.bson.BSONObjectID

/**
 * Created by admin on 11/13/2015.
 */
object FormFactory {

  val employeeForm = Form(
    mapping(
      "id" -> ignored(BSONObjectID.generate: BSONObjectID),
      "name" -> nonEmptyText,
      "address" -> nonEmptyText,
      "dob" -> date("yyyy-MM-dd"),
      "joiningDate" -> date("yyyy-MM-dd"),
      "designation" -> nonEmptyText)(Employee.apply)(Employee.unapply))

  val searchParams = Form(
    mapping(
      "fieldName" -> nonEmptyText,
      "fieldValue" -> nonEmptyText)(SearchParams.apply)(SearchParams.unapply)
  )


}
