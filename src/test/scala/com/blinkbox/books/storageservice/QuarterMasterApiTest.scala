import java.net.URL

import akka.actor.ActorRefFactory
import com.blinkbox.books.config.ApiConfig
import com.blinkbox.books.spray.v2
import com.blinkbox.books.storageservice.AppConfig
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{Matchers, FlatSpec}
import spray.routing.HttpService
import spray.testkit.ScalatestRouteTest

class QuarterMasterApiTest extends FlatSpec with ScalatestRouteTest with Matchers with MockitoSugar with v2.JsonSupport {

  class TestFixture extends HttpService {
    val apiConfig = mock[ApiConfig]
    val appConfig = mock[AppConfig]
    when(apiConfig.localUrl).thenReturn(new URL("http://localhost"))
    when(appConfig.api).thenReturn(apiConfig)


    override implicit def actorRefFactory: ActorRefFactory = ???
  }
}