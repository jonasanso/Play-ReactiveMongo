/*
 * Copyright 2012-2013 Stephane Godbillon (@sgodbillon)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package play.modules.reactivemongo

import java.util.UUID

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import play.api.http.HttpEntity
import play.api.libs.iteratee.Enumeratee
import play.api.libs.json.{JsObject, JsValue, Json, Reads}
import play.api.libs.streams.{Accumulator, Streams}
import play.api.mvc.MultipartFormData.FilePart
import play.api.mvc._
import play.core.parsers.Multipart.FileInfo
import reactivemongo.api.gridfs.{DefaultFileToSave, FileToSave, GridFS, ReadFile}
import reactivemongo.play.json._

import scala.concurrent.{ExecutionContext, Future}

/** A JSON implementation of `FileToSave`. */
class JSONFileToSave(
  val filename: Option[String] = None,
  val contentType: Option[String] = None,
  val uploadDate: Option[Long] = None,
  val metadata: JsObject = Json.obj(),
  val id: JsValue = Json.toJson(UUID.randomUUID().toString))
    extends FileToSave[JSONSerializationPack.type, JsValue] {
  val pack = JSONSerializationPack
}

/** Factory of [[JSONFileToSave]]. */
object JSONFileToSave {
  def apply[N](filename: N,
               contentType: Option[String] = None,
               uploadDate: Option[Long] = None,
               metadata: JsObject = Json.obj(),
               id: JsValue = Json.toJson(UUID.randomUUID().toString))(implicit naming: DefaultFileToSave.FileName[N]): JSONFileToSave = new JSONFileToSave(naming(filename), contentType, uploadDate, metadata, id)

}

object MongoController {
  import play.api.libs.json.{JsError, JsResult, JsSuccess}
  import reactivemongo.play.json.BSONFormats
  import BSONFormats.{BSONDateTimeFormat, BSONDocumentFormat}

  implicit def readFileReads[Id <: JsValue](implicit r: Reads[Id]): Reads[ReadFile[JSONSerializationPack.type, Id]] = new Reads[ReadFile[JSONSerializationPack.type, Id]] {
    def reads(json: JsValue): JsResult[ReadFile[JSONSerializationPack.type, Id]] = json match {
      case obj: JsObject => for {
        doc <- BSONDocumentFormat.partialReads(obj)
        _id <- (obj \ "_id").validate[Id]
        ct <- readOpt[String](obj \ "contentType")
        fn <- (obj \ "filename").toOption.fold[JsResult[Option[String]]](
          JsSuccess(Option.empty[String])) { jsVal =>
            BSONStringFormat.partialReads(jsVal).map(s => Some(s.value))
          }
        ud <- (obj \ "uploadDate").toOption.fold[JsResult[Option[Long]]](
          JsSuccess(Option.empty[Long])) { jsVal =>
            BSONDateTimeFormat.partialReads(jsVal).map(d => Some(d.value))
          }
        ck <- (obj \ "chunkSize").validate[Int]
        len <- (obj \ "length").validate[Long]
        m5 <- readOpt[String](obj \ "md5")
        mt <- readOpt[JsObject](obj \ "metadata")
      } yield new ReadFile[JSONSerializationPack.type, Id] {
        val pack = JSONSerializationPack
        val id = _id
        val contentType = ct
        val filename = fn
        val uploadDate = ud
        val chunkSize = ck
        val length = len
        val md5 = m5
        val metadata = mt.getOrElse(Json.obj())
        val original = doc
      }

      case js => JsError(s"object is expected: $js")
    }
  }
}

/** A mixin for controllers that will provide MongoDB actions. */
trait MongoController extends Controller { self: ReactiveMongoComponents =>

  import play.core.parsers.Multipart
  import reactivemongo.api.Cursor

  /** Returns the current instance of the driver. */
  def driver = reactiveMongoApi.driver

  /** Returns the current MongoConnection instance (the connection pool manager). */
  def connection = reactiveMongoApi.connection

  @deprecated(message = "Use [[database]]", since = "0.12.0")
  def db = reactiveMongoApi.db

  /** Returns the default database (as specified in `application.conf`). */
  def database = reactiveMongoApi.database

  val CONTENT_DISPOSITION_ATTACHMENT = "attachment"
  val CONTENT_DISPOSITION_INLINE = "inline"

  /**
   * Returns a future Result that serves the first matched file, or NotFound.
   */
  def serve[Id <: JsValue, T <: ReadFile[JSONSerializationPack.type, Id]](gfs: GridFS[JSONSerializationPack.type])(foundFile: Cursor[T], dispositionMode: String = CONTENT_DISPOSITION_ATTACHMENT)(implicit ec: ExecutionContext): Future[Result] = {
    def transform(file: T): Source[ByteString, NotUsed] = {
      Source.fromPublisher(Streams.enumeratorToPublisher(gfs.enumerate(file))).map(ByteString.fromArray)
    }

    foundFile.headOption.filter(_.isDefined).map(_.get).map { file =>
      val filename = file.filename.getOrElse("file.bin")

      Result(header = ResponseHeader(OK), body = HttpEntity.Streamed(transform(file), Some(file.length), file.contentType)).
        withHeaders(CONTENT_DISPOSITION -> (s"""$dispositionMode; filename="$filename"; filename*=UTF-8''""" + java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20")))

    }.recover {
      case _ => NotFound
    }
  }

  def toByteString(implicit ec: ExecutionContext): Enumeratee[ByteString, Array[Byte]] = Enumeratee.map[ByteString]{ s:ByteString =>
    s.toArray[Byte]
  }

  /** Gets a body parser that will save a file sent with multipart/form-data into the given GridFS store. */
  @deprecated(message = "Use `gridFSBodyParser` with `Future[GridFS]`",
    since = "0.12.0")
  def gridFSBodyParser(gfs: GridFS[JSONSerializationPack.type])
                      (implicit readFileReader: Reads[ReadFile[JSONSerializationPack.type, JsValue]], ec: ExecutionContext): BodyParser[MultipartFormData[Future[ReadFile[JSONSerializationPack.type, JsValue]]]] = {
    import BodyParsers.parse.multipartFormData

    multipartFormData(new Multipart.FilePartHandler[Future[ReadFile[JSONSerializationPack.type, JsValue]]] {
      override def apply(info: FileInfo): Accumulator[ByteString, FilePart[Future[ReadFile[JSONSerializationPack.type, JsValue]]]] = {

        val iteratee = toByteString.transform(gfs.iteratee(JSONFileToSave(Some(info.fileName), info.contentType)))


        Streams.iterateeToAccumulator(iteratee).map(FilePart(info.partName, info.fileName, info.contentType, _))

      }
    })
  }

  /** Gets a body parser that will save a file sent with multipart/form-data into the given GridFS store. */
  def gridFSBodyParser(gfs: Future[GridFS[JSONSerializationPack.type]])
                      (implicit readFileReader: Reads[ReadFile[JSONSerializationPack.type, JsValue]], ec: ExecutionContext, mat: Materializer): BodyParser[MultipartFormData[Future[ReadFile[JSONSerializationPack.type, JsValue]]]] = {
    new AsyncBodyParser(gfs.map(gridFSBodyParser(_)))
  }

  /** Gets a body parser that will save a file sent with multipart/form-data into the given GridFS store. */
  @deprecated(message = "Use `gridFSBodyParser` with `Future[GridFS]`",
    since = "0.12.0")
  def gridFSBodyParser[Id <: JsValue](gfs: GridFS[JSONSerializationPack.type], fileToSave: (String, Option[String]) => FileToSave[JSONSerializationPack.type, Id])(implicit readFileReader: Reads[ReadFile[JSONSerializationPack.type, Id]], ec: ExecutionContext, ir: Reads[Id]): BodyParser[MultipartFormData[Future[ReadFile[JSONSerializationPack.type, Id]]]] = {
    import BodyParsers.parse.multipartFormData

    multipartFormData(new Multipart.FilePartHandler[Future[ReadFile[JSONSerializationPack.type, Id]]] {
      override def apply(info: FileInfo): Accumulator[ByteString, FilePart[Future[ReadFile[JSONSerializationPack.type, Id]]]] = {

        val iteratee = toByteString.transform(gfs.iteratee(fileToSave(info.fileName, info.contentType)))


        Streams.iterateeToAccumulator(iteratee).map(FilePart(info.partName, info.fileName, info.contentType, _))

      }
    })
  }

  /** Gets a body parser that will save a file sent with multipart/form-data into the given GridFS store. */
  def gridFSBodyParser[Id <: JsValue](gfs: Future[GridFS[JSONSerializationPack.type]], fileToSave: (String, Option[String]) => FileToSave[JSONSerializationPack.type, Id])(
    implicit readFileReader: Reads[ReadFile[JSONSerializationPack.type, Id]], ec: ExecutionContext, ir: Reads[Id], mat: Materializer): BodyParser[MultipartFormData[Future[ReadFile[JSONSerializationPack.type, Id]]]] = {
    new AsyncBodyParser(gfs.map(gridFSBodyParser(_, fileToSave)))
  }

  class AsyncBodyParser[A](fut: Future[BodyParser[A]])(implicit ec: ExecutionContext, val mat: Materializer) extends BodyParser[A] {
      def apply(rh :RequestHeader) : Accumulator[ByteString, Either[Result, A]]  = {
            Accumulator.flatten(fut.map(_.apply(rh)))
    }
  }
}

