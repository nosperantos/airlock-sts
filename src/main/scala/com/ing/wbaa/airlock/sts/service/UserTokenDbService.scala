package com.ing.wbaa.airlock.sts.service

import java.time.Instant

import com.ing.wbaa.airlock.sts.data.aws._
import com.ing.wbaa.airlock.sts.data.{ STSUserInfo, UserGroup, UserName }
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }

trait UserTokenDbService extends LazyLogging with TokenGeneration {

  implicit protected[this] def executionContext: ExecutionContext

  protected[this] def getAwsCredential(userName: UserName): Future[Option[AwsCredential]]

  protected[this] def getUserSecretKeyAndIsNPA(awsAccessKey: AwsAccessKey): Future[Option[(UserName, AwsSecretKey, Boolean, Set[UserGroup])]]

  protected[this] def insertAwsCredentials(username: UserName, awsCredential: AwsCredential, isNpa: Boolean): Future[Boolean]

  protected[this] def getToken(awsSessionToken: AwsSessionToken, userName: UserName): Future[Option[(UserName, AwsSessionTokenExpiration)]]

  protected[this] def insertToken(awsSessionToken: AwsSessionToken, username: UserName, expirationDate: AwsSessionTokenExpiration): Future[Boolean]

  protected[this] def doesUsernameNotExistAndAccessKeyExist(userName: UserName, awsAccessKey: AwsAccessKey): Future[Boolean]

  protected[this] def insertUserGroups(userName: UserName, userGroups: Set[UserGroup]): Future[Boolean]

  /**
   * Retrieve or generate Credentials and generate a new Session
   *
   * @param userName      the username
   * @param userGroups    the user groups
   * @param duration      optional: the duration of the session, if duration is not given then it defaults to the application application default
   * @return
   */
  def getAwsCredentialWithToken(userName: UserName, userGroups: Set[UserGroup], duration: Option[Duration]): Future[AwsCredentialWithToken] =
    for {
      awsCredential <- getOrGenerateAwsCredential(userName)
      awsSession <- getNewAwsSession(userName, duration)
      _ <- insertUserGroups(userName, userGroups)
    } yield AwsCredentialWithToken(
      awsCredential,
      awsSession
    )

  /**
   * Check whether the token given is active for the accesskey and potential sessiontoken
   *
   * When a session token is not provided; this user has to be an NPA to be allowed access
   */
  def isCredentialActive(awsAccessKey: AwsAccessKey, awsSessionToken: Option[AwsSessionToken]): Future[Option[STSUserInfo]] =
    getUserSecretKeyAndIsNPA(awsAccessKey) flatMap {
      case Some((userName, awsSecretKey, isNPA, groups)) =>
        awsSessionToken match {
          case Some(sessionToken) =>
            isTokenActive(sessionToken, userName).flatMap {
              case true =>
                getToken(sessionToken, userName)
                  .map(_ => Some(STSUserInfo(userName, groups, awsAccessKey, awsSecretKey)))
              case false => Future.successful(None)
            }

          case None if isNPA =>
            Future.successful(Some(STSUserInfo(userName, Set.empty, awsAccessKey, awsSecretKey)))

          case None if !isNPA =>
            logger.warn(s"User validation failed. No sessionToken provided while user is not an NPA " +
              s"(username: $userName, accessKey: $awsAccessKey)")
            Future.successful(None)
        }

      case None =>
        logger.warn(s"User could not be retrieved with accesskey: $awsAccessKey")
        Future.successful(None)
    }

  /**
   * Retrieve a new Aws Session
   * @param userName
   * @param duration
   * @param generationTriesLeft Number of times to retry token generation, in case it collides
   * @return
   */
  private[this] def getNewAwsSession(userName: UserName, duration: Option[Duration], generationTriesLeft: Int = 3): Future[AwsSession] = {
    val newAwsSession = generateAwsSession(duration)
    insertToken(newAwsSession.sessionToken, userName, newAwsSession.expiration)
      .flatMap {
        case true => Future.successful(newAwsSession)
        case false =>
          if (generationTriesLeft <= 0) Future.failed(new Exception("Token generation failed, keys collided"))
          else {
            logger.debug(s"Generated token collided with existing token in DB, generating a new one ... (tries left: $generationTriesLeft)")
            getNewAwsSession(userName, duration, generationTriesLeft - 1)
          }
      }
  }

  /**
   * Adds a user to the DB with aws credentials generated for it.
   * In case the user already exists, it returns the already existing credentials.
   */
  private[this] def getOrGenerateAwsCredential(userName: UserName): Future[AwsCredential] =
    getAwsCredential(userName)
      .flatMap {
        case Some(awsCredential) => Future.successful(awsCredential)
        case None                => getNewAwsCredential(userName)
      }

  private[this] def getNewAwsCredential(userName: UserName): Future[AwsCredential] = {
    val newAwsCredential = generateAwsCredential
    insertAwsCredentials(userName, newAwsCredential, isNpa = false)
      .flatMap {
        case true => Future.successful(newAwsCredential)
        case false =>
          //If this failed it can be due to the access key or the username being duplicate.
          // A check is done to see if it was due to the access key, if so generate another one else fail as user already exists.
          doesUsernameNotExistAndAccessKeyExist(userName, newAwsCredential.accessKey)
            .flatMap {
              case true  => getNewAwsCredential(userName)
              case false => Future.failed(new Exception(s"Username: $userName already exists "))
            }

      }
  }

  private[this] def isTokenActive(awsSessionToken: AwsSessionToken, userName: UserName): Future[Boolean] =
    getToken(awsSessionToken, userName)
      .map(_.map(_._2))
      .map {
        case Some(tokenExpiration) =>
          val isExpired = Instant.now().isAfter(tokenExpiration.value)
          if (isExpired) logger.warn(s"Sessiontoken provided has expired at: ${tokenExpiration.value} " +
            s"for token: '${awsSessionToken.value}'")
          !isExpired

        case None =>
          logger.error("Token doesn't have any expiration time associated with it.")
          false
      }
}
