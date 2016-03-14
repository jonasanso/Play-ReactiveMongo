/*
 * Copyright 2012 Pascal Voitot
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

import play.api.Application
import reactivemongo.api._

import scala.concurrent.{ ExecutionContext, Future }

private[reactivemongo] case class ReactiveMongoHelper(parsedURI: MongoConnection.ParsedURI, app: Application)(implicit ec: ExecutionContext) {
  lazy val driver = new MongoDriver(Option(app.configuration.underlying))
  lazy val connection = driver.connection(parsedURI)
  lazy val db = DB(parsedURI.db.get, connection)

  @deprecated("Experimental", "0.12.0")
  lazy val database: Future[DefaultDB] =
    Future(parsedURI.db.get).flatMap(connection.database(_))
}
