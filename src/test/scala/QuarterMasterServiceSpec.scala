import org.scalatest.{FlatSpec, Matchers}

import scala.collection.mutable.{Map => MutableMap}

/**
 * Created by greg on 19/09/14.
 */
class QuarterMasterTests extends FlatSpec with QuarterMasterService with Matchers {

  val vars = MutableMap[String,Int]()
  var result = 0

  Given("""^a variable ([a-z]+) with value (\d+)$"""){ (varName:String, value:Int) =>
    vars += varName -> value
  }
  When("""^I multiply ([a-z]+) \* ([a-z]+)$"""){ (var1:String, var2:String) =>
    result = vars(var1) * vars(var2)
  }
  Then("""^I get (\d+)$"""){ (expectedResult:Int) =>
    assert(result === expectedResult)
  }
}

import scala.language.{implicitConversions, postfixOps}



