package rww.auth

import java.net.{MalformedURLException, URL}
import java.util.Base64

import akka.http.scaladsl.model.HttpHeader
import akka.http.scaladsl.model.HttpHeader.ParsingResult.Ok
import akka.http.scaladsl.model.headers.{Authorization, GenericHttpCredentials}
import com.typesafe.scalalogging.slf4j.Logging
import org.w3.banana.RDF
import play.api.mvc.RequestHeader
import rww.ldp.LDPExceptions.{HttpAuthException, SignatureRequestException,
SignatureVerificationException}
import rww.ldp.auth.WebKeyVerifier
import rww.play.auth.{AuthN, Subject}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}


/**
 *
 */
object HttpAuthorization

object SigInfo {
  def SigFail(errorMsg: String) = Failure(SignatureRequestException(errorMsg))

  def SigVFail(errorMsg: String, sigInfo: SigInfo) =
    Failure(SignatureVerificationException(errorMsg, sigInfo))

  val base64decoder = Base64.getDecoder
}

class SigInfo (
  val sigText: String,
  val keyId: URL,
  val algorithm: String,
  val sigbytes: Array[Byte] //how can I force this to be immutable, without duplication?
) {
  def sig = scala.collection.immutable.IndexedSeq(sigbytes)
}


/**
 * implements a number of Http Authorization methods
 * currently only WebKeyVerification as defined by
 *    https://tools.ietf.org/html/draft-cavage-http-signatures-05
 * @param verifier
 * @param base
 * @tparam Rdf
 */
class HttpAuthorization[Rdf <: RDF](
  verifier: WebKeyVerifier[Rdf], base: URL
)(implicit
  ec: ExecutionContext
) extends AuthN with Logging {

  import org.w3.banana.TryW
  import SigInfo._

  def apply(req: RequestHeader): Future[Subject] = {
    val auths = for (auth <- req.headers.getAll("Authorization"))
      yield HttpHeader.parse("Authorization", auth)

    val seqOfFuturePrincipals = auths.collect {
      case Ok(Authorization(GenericHttpCredentials("Signature", _, params)), _) =>
        parseSignatureInfo(req, params).asFuture.flatMap(verifier.verify(_))
    }
    val seqOfFutureTrys = seqOfFuturePrincipals.map(futureToFutureTry)
    Future.sequence(seqOfFutureTrys).map { seqTryPrincipals =>
      val grouped = seqTryPrincipals.groupBy {
        case Success(x) => "success"
        case Failure(e) => "failure"
      }
      Subject(
        grouped("success").collect { case Success(p) => p }.toList,
        grouped("failure").collect { case Failure(e: HttpAuthException) => e }.toList
      )
    }
  }

  def futureToFutureTry[T](f: Future[T]): Future[Try[T]] =
    f.map(Success(_)).recover { case x => Failure(x) }


  /**
   *
   * @param req The Request
   * @param params from the parsed Authorization header
   * @return
   * //todo: use the akka Uri class
   */
  def parseSignatureInfo(req: RequestHeader, params: Map[String, String]): Try[SigInfo] = {
    import SigInfo._
    for {
      keyUrl <- params.get("keyId")
          .fold[Try[URL]](SigFail("no keyId attribute")){ id=>
            Try ( new URL(base,id) ) recoverWith {
              case e: MalformedURLException => SigFail("could not transform keyId to URL")
            }
          }
      algo <- params.get("algorithm")
        .fold[Try[String]](SigFail("algorithm was not specified")){
          //java standard names http://docs.oracle
          // .com/javase/8/docs/technotes/guides/security/StandardNames.html
          case "rsa-sha256" => Success("SHA256withRSA")  //sadly java does not provide a typed
          // non mutable Signature object
          case algo => SigFail(s"algorithm '$algo' not known")
        }
      signature <- params.get("signature")
        .fold[Try[Array[Byte]]](SigFail("no signature was sent!")){ sig =>
           Try(base64decoder.decode(sig)).recoverWith {
             case e: IllegalArgumentException => SigFail("signature is not a base64 encoding")
           }
        }
      sigText <- buildSignatureText(req, params.get("headers").getOrElse("Date"))
    } yield new SigInfo(sigText, keyUrl, algo, signature)
  }


  def buildSignatureText(req: RequestHeader, headerList: String): Try[String] = try {
    Success(headerList.split(" ").map {
      case rt@"(request-target)" =>
        rt + ":" + req.method.toLowerCase + " " + req.path + {
          if (req.rawQueryString != "")
            "?" + req.rawQueryString
          else ""
        }
      case name =>
        val values = req.headers.getAll(name)
        if (values.isEmpty)
          throw SignatureRequestException(s"found no header for $name in ${req.headers}")
        else name.toLowerCase + ":" + values.mkString(",")
    }.mkString("\n")
    )
  } catch {
    //for discussion on this type of control flow see:
    //   http://stackoverflow.com/questions/2742719/how-do-i-break-out-of-a-loop-in-scala
    //   http://stackoverflow.com/questions/12892701/abort-early-in-a-fold
    case e: SignatureVerificationException => Failure(e)
  }


}