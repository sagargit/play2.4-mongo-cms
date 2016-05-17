package controllers

import java.util.concurrent.TimeoutException
import javax.inject.Inject
import forms.FormFactory
import reactivemongo.api.gridfs.ReadFile
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import play.api.Logger
import play.api.i18n.MessagesApi
import play.api.mvc.{ Action, Controller }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{JsObject, JsString, Json}, Json.toJsFieldJsValueWrapper
import play.modules.reactivemongo.{
  MongoController, ReactiveMongoApi, ReactiveMongoComponents
}
import play.modules.reactivemongo.json._, collection.JSONCollection
import reactivemongo.bson.BSONObjectID
import models.{SearchParams, Employee, JsonFormats}, JsonFormats.employeeFormat
import views.html

class Application @Inject() (
  val reactiveMongoApi: ReactiveMongoApi,
  val messagesApi: MessagesApi)
    extends Controller with MongoController with ReactiveMongoComponents {

  implicit val timeout = 10.seconds

  import java.util.UUID
  import MongoController.readFileReads

  type JSONReadFile = ReadFile[JSONSerializationPack.type, JsString]

  private val gridFs = reactiveMongoApi.gridFS
  /*
   * Get a JSONCollection (a Collection implementation that is designed to work
   * with JsObject, Reads and Writes.)
   * Note that the `collection` is not a `val`, but a `def`. We do _not_ store
   * the collection reference to avoid potential problems in development with
   * Play hot-reloading.
   */
  def collection: JSONCollection = db.collection[JSONCollection]("employees")

  import play.api.data.Form
  import models._
  import models.JsonFormats._

  /**
   * Handle default path requests, redirect to employee list
   */

  def index = Action { Home }

  /**
   * This result directly redirect to the application home.
   */
  val Home = Redirect(routes.Application.list())

  /**
   * Display the paginated list of employees.
   */

  def list = Action.async { implicit request =>
    val futurePage = collection.genericQueryBuilder.cursor[Employee]().collect[List]()

    futurePage.map({ employees =>

      implicit val msg = messagesApi.preferred(request)

      Ok(html.list(employees))
    }).recover {
      case t: TimeoutException =>
        Logger.error("Problem found in employee list process")
        InternalServerError(t.getMessage)
    }
  }

  def search = Action.async {
    implicit request =>
      implicit val msg = messagesApi.preferred(request)
      val list = FormFactory.employeeForm.data.keys.toList.filterNot(k => !k.equalsIgnoreCase("id"))
      Future.successful(Ok(html.advanceSearch(list,FormFactory.searchParams)))
  }

  def advanceSearchResults  = Action.async { implicit request =>
    FormFactory.searchParams.bindFromRequest.fold(
    {
      formWithErrors =>
        implicit val msg = messagesApi.preferred(request)
        val list = FormFactory.employeeForm.data.keys.toList.filterNot(k => !k.equalsIgnoreCase("id"))
        Future.successful(BadRequest(html.advanceSearch(list,formWithErrors)))
    },
    searchParam => {
      val fieldName = searchParam.fieldName
      val fieldValue = searchParam.fieldValue

      val futurePage = collection.find(Json.obj(fieldName -> fieldValue)).cursor[Employee]().collect[List]()

      futurePage.map(
      {
        employees =>
          implicit val msg = messagesApi.preferred(request)
          Ok(html.advanceSearchResults(employees))
      }
      ).recover{
        case t: TimeoutException =>
          Logger.error("Problem found in employee search process")
          InternalServerError(t.getMessage)
      }
    }
    )
  }

  /**
   * Display the 'edit form' of a existing Employee.
   *
   * @param id Id of the employee to edit
   */
  def edit(id: String) = Action.async { request =>
  //  val futureEmpList = collection.find(Json.obj("_id" -> Json.obj("$oid" -> id))).cursor[Employee]().collect[List]() for getting list of employees
    val futureEmp = collection.find(Json.obj("_id" -> Json.obj("$oid" -> id))).one[Employee]
    futureEmp.flatMap {
        employeeOption =>
          employeeOption.map{
            employee =>
              // search for the matching attachments
              // find(...).toList returns a future list of documents (here, a future list of ReadFileEntry)
              gridFs.find[JsObject, JSONReadFile](Json.obj("employee" -> employee._id)).collect[List]().map { files =>
                val filesWithId = files.map { file =>
                  file.id -> file
                }
                implicit val messages = messagesApi.preferred(request)
                Ok(views.html.editForm(Some(id),FormFactory.employeeForm.fill(employee), Some(filesWithId)))
              }
          }.getOrElse(Future.successful(NotFound))
    }.recover {
      case t: TimeoutException =>
        Logger.error("Problem found in employee edit process")
        InternalServerError(t.getMessage)
    }
  }

  /**
   * Handle the 'edit form' submission
   *
   * @param id Id of the employee to edit
   */
  def update(id: String) = Action.async { implicit request =>
    FormFactory.employeeForm.bindFromRequest.fold(
      { formWithErrors =>
        implicit val msg = messagesApi.preferred(request)
        Future.successful(BadRequest(html.editForm(Some(id), formWithErrors,None)))
      },
      employee => {
        val futureUpdateEmp = collection.update(Json.obj("_id" -> Json.obj("$oid" -> id)), employee.copy(_id = BSONObjectID(id)))
        futureUpdateEmp.map { result =>
          Home.flashing("success" -> s"Employee ${employee.name} has been updated") // used in the view for flash
        }.recover {
          case t: TimeoutException =>
            Logger.error("Problem found in employee update process")
            InternalServerError(t.getMessage)
        }
      })
  }

  /**
   * Display the 'new employee form'.
   */
  def create = Action { request =>
    implicit val msg = messagesApi.preferred(request)
    Ok(html.createForm(FormFactory.employeeForm))
  }

  /**
   * Handle the 'new employee form' submission.
   */
  def save = Action.async { implicit request =>
    FormFactory.employeeForm.bindFromRequest.fold(
      { formWithErrors =>
        implicit val msg = messagesApi.preferred(request)
        Future.successful(BadRequest(html.createForm(formWithErrors)))
      },
      employee => {
        val futureUpdateEmp = collection.insert(employee.copy(_id = BSONObjectID.generate))
        futureUpdateEmp.map { result =>
          Home.flashing("success" -> s"Employee ${employee.name} has been created") // used in the view for flash
        }.recover {
          case t: TimeoutException =>
            Logger.error("Problem found in employee update process")
            InternalServerError(t.getMessage)
        }
      })
  }

  /**
   * Handle employee deletion.
   */
  def delete(id: String) = Action.async {
    val futureInt = collection.remove(Json.obj("_id" -> Json.obj("$oid" -> id)), firstMatchOnly = true)
    futureInt.map(i => Home.flashing("success" -> "Employee has been deleted")).recover {  // used in the view for flash
      case t: TimeoutException =>
        Logger.error("Problem deleting employee")
        InternalServerError(t.getMessage)
    }
  }

  // save the uploaded file as an attachment of the employee with the given id
  def saveAttachment(id: String) =
    Action.async(gridFSBodyParser(gridFs)) { request =>
      // here is the future file!
      val futureFile = request.body.files.head.ref

      futureFile.onFailure {
        case err => err.printStackTrace()
      }

      // when the upload is complete, we add the employee id to the file entry (in order to find the attachments of the employee)
      val futureUpdate = for {
        file <- { println("_0"); futureFile }
        // here, the file is completely uploaded, so it is time to update the article
        updateResult <- {
          println("_1")
          gridFs.files.update(
            Json.obj("_id" -> file.id),
            Json.obj("$set" -> Json.obj("employee" -> id)))
        }
      } yield updateResult

      futureUpdate.map { _ =>
        Redirect(routes.Application.edit(id))
      }.recover {
        case e => InternalServerError(e.getMessage())
      }
    }

  def getAttachment(id: String) = Action.async { request =>
    // find the matching attachment, if any, and streams it to the client
    val file = gridFs.find[JsObject, JSONReadFile](Json.obj("_id" -> id))

    request.getQueryString("inline") match {
      case Some("true") =>
        serve[JsString, JSONReadFile](gridFs)(file, CONTENT_DISPOSITION_INLINE)

      case _            => serve[JsString, JSONReadFile](gridFs)(file)
    }
  }

  def removeAttachment(id: String) = Action.async {
    gridFs.remove(Json toJson id).map(_ => Ok).
      recover { case _ => InternalServerError }
  }

}
