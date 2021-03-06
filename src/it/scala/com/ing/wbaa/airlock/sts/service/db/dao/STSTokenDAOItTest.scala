package com.ing.wbaa.airlock.sts.service.db.dao

import java.time.Instant

import akka.actor.ActorSystem
import com.ing.wbaa.airlock.sts.config.{MariaDBSettings, StsSettings}
import com.ing.wbaa.airlock.sts.data.UserName
import com.ing.wbaa.airlock.sts.data.aws.{AwsCredential, AwsSessionToken, AwsSessionTokenExpiration}
import com.ing.wbaa.airlock.sts.service.TokenGeneration
import com.ing.wbaa.airlock.sts.service.db.MariaDb
import org.scalatest.{Assertion, AsyncWordSpec}

import scala.concurrent.Future
import scala.util.Random

class STSTokenDAOItTest extends AsyncWordSpec with STSTokenDAO with STSUserAndGroupDAO with MariaDb with TokenGeneration {

  val system: ActorSystem = ActorSystem.create("test-system")

  override protected[this] def mariaDBSettings: MariaDBSettings = MariaDBSettings(system)

  override protected[this] def stsSettings: StsSettings = StsSettings(system)

  override lazy val dbExecutionContext = executionContext

  private class TestObject {
    val testAwsSessionToken: AwsSessionToken = AwsSessionToken(Random.alphanumeric.take(32).mkString)
    val userName: UserName = UserName(Random.alphanumeric.take(32).mkString)
    val testExpirationDate: AwsSessionTokenExpiration = AwsSessionTokenExpiration(Instant.now())
    val cred: AwsCredential = generateAwsCredential
  }

  private def withInsertedUser(testCode: UserName => Future[Assertion]): Future[Assertion] = {
    val testObject = new TestObject
    insertAwsCredentials(testObject.userName, testObject.cred, isNpa = false)
    testCode(testObject.userName)
  }

  "STS Token DAO" should {
    "get Token" that {

      "exists" in withInsertedUser { userName =>
        val testObject = new TestObject
        insertToken(testObject.testAwsSessionToken, userName, testObject.testExpirationDate)
        getToken(testObject.testAwsSessionToken, userName).map { o =>
          assert(o.isDefined)
          assert(o.get._1 == userName)
          //is off by milliseconds, because we truncate it, so we match be epoch seconds
          assert(o.get._2.value.getEpochSecond == testObject.testExpirationDate.value.getEpochSecond)
        }
      }

      "doesn't exist" in withInsertedUser { userName =>
        getToken(AwsSessionToken("DOESNTEXIST"), userName).map { o =>
          assert(o.isEmpty)
        }
      }

    }

    "insert Token" that {
      "new to the db" in withInsertedUser { userName =>
        val testObject = new TestObject
        insertToken(testObject.testAwsSessionToken, userName, testObject.testExpirationDate)
          .map(r => assert(r))
      }

      "token with same session token already exists " in withInsertedUser { userName =>
        val testObject = new TestObject
        insertToken(testObject.testAwsSessionToken, userName, testObject.testExpirationDate)
          .map(r => assert(r))

        insertToken(testObject.testAwsSessionToken, userName, testObject.testExpirationDate)
          .map(r => assert(!r))
      }
    }
  }

}
