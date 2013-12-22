package org.bigbluebutton.endpoint.redis

import akka.actor.{Actor, ActorRef, ActorLogging, Props}
import spray.json.{JsObject, JsValue, DefaultJsonProtocol, JsonParser, DeserializationException}
import org.parboiled.errors.ParsingException
import org.bigbluebutton.apps.protocol.HeaderAndPayloadJsonSupport._
import org.bigbluebutton.apps.protocol._
import scala.util.{Try, Success, Failure}
import org.bigbluebutton.apps.models.Session
import org.bigbluebutton.apps.users.unmarshalling.UsersMessageUnmarshaller

object MessageUnmarshallingActor {
  def props(bbbAppsActor: ActorRef, pubsubActor: ActorRef): Props =  
        Props(classOf[MessageUnmarshallingActor], bbbAppsActor, pubsubActor)
}

class MessageUnmarshallingActor private (val bbbAppsActor: ActorRef, val pubsubActor: ActorRef) extends Actor 
         with ActorLogging with UsersMessageUnmarshaller {

  def receive = {
    case msg: String => handleMessage(msg)
    case badMsg => log.error("Unhandled message: [{}", badMsg)
  }
  
  def handleMessage(msg: String) = {
    unmarshall(msg) match {
      case Success(validMsg) => forwardMessage(validMsg)
      case Failure(ex) => log.error("Unhandled message: [{}]", ex)
    }
  }

  def forwardMessage(msg: HeaderAndPayload) = {
    msg.header.event.name match {
      case InMsgNameConst.user_join      => handleUserJoin(msg)
      case InMsgNameConst.user_leave     => handleUserLeave(msg)
      case InMsgNameConst.get_users      => handleGetUsers(msg)
      case InMsgNameConst.assign_presenter      => handleAssignPresenter(msg)
      
	  case _ => 
	    log.error("Unknown message name: [{}]", msg.header.event.name)
	}    
  }
    
  def header(msg: JsObject):Header = {
    try {
      msg.fields.get("header") match {
        case Some(header) => header.convertTo[Header]
        case None => throw MessageProcessException("Cannot get header: [" + msg + "]")
     }
    } catch {
      case e: DeserializationException =>
        throw MessageProcessException("Failed to deserialize header: [" + msg + "]")
    }
  }
 
  def payload(msg: JsObject):JsObject = {
    msg.fields.get("payload") match {
      case Some(payload) => payload.asJsObject
      case None => throw MessageProcessException("Cannot get payload information: [" + msg + "]")
    } 
  }
  
  def toJsObject(msg: String):JsObject = {
    log.debug("Converting to json : {}", msg)    
    try {
      JsonParser(msg).asJsObject
    } catch {
      case e: ParsingException => {
        log.error("Cannot parse message: {}", msg)
        throw MessageProcessException("Cannot parse JSON message: [" + msg + "]")
      }
    }
  }

  def toHeaderAndPayload(header: Header, payload:JsObject):HeaderAndPayload = {
    HeaderAndPayload(header, payload)
  }
    
  def unmarshall(jsonMsg: String):Try[HeaderAndPayload] = {
    for {
      jsonObj <- Try(toJsObject(jsonMsg))
      header <- Try(header(jsonObj))
      payload <- Try(payload(jsonObj))
      message = toHeaderAndPayload(header, payload)
    } yield message
  }
  
  def toSession(header: Header):Option[Session] = {
    for {
      sessionId <- header.meeting.session
      session = Session(sessionId, header.meeting.id, header.meeting.name)
    } yield session
  }
}