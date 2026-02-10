package com.android.certifications.test.utils

import java.text.DecimalFormat
import org.junit.rules.TestName
import logging
class TestAssertLogger(name: TestName){
  var inc:Int = 0;
  val name: TestName =name
  fun Msg(desc:String):String?{
    inc++;
    val line = name.methodName + "(" + DecimalFormat("000").format(inc) +"):"+ desc;
    logging(line)
    return line
  }
}