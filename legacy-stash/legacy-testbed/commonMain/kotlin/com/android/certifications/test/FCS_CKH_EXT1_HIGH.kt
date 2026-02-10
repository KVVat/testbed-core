package com.android.certifications.test

import com.android.certifications.test.utils.SFR
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import logging

@SFR("FCS_CKH.1/High", """
  FCS_CKH.1.1/Low The TSF shall support a key hierarchy for the data encryption key(s) 
  for Low user data assets.
  
  FCS_CKH.1.2/Low The TSF shall ensure that all keys in the key hierarchy are derived and/or 
  generated according to [assignment: description of how each key in the hierarchy is derived and/or
  generated, with which key lengths and according to which standards] ensuring that the key hierarchy
  uses the DUK directly or indirectly in the derivation of the data encryption key(s) for Low user 
  data assets. 
  ""","FCS_CKH_EXT1_HIGH")
class FCS_CKH_EXT1_HIGH {

    @Before
    fun setUp()
    {
        runBlocking {

        }
    }
    @After
    fun teardown() {
        runBlocking {

        }
    }

    @Test
    fun testOutput() {
        runBlocking{
        }
        logging("ClassName"+this.javaClass.canonicalName);
    }
}