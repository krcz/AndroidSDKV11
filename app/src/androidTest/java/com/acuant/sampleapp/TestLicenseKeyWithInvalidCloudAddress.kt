package com.acuant.sampleapp

import android.support.test.rule.ActivityTestRule

import com.acuant.acuantmobilesdk.Controller

import org.junit.Assert
import org.junit.Rule
import org.junit.Test

import java.util.concurrent.CountDownLatch

/**
 * Created by tapasbehera on 4/19/18.
 */

class TestLicenseKeyWithInvalidCloudAddress {
    @get:Rule
    public var mActivityRule = ActivityTestRule(
            TestMainActivity::class.java)

    /**
     * Test With a valid license key and invalid url to expect return false
     */
    @Test
    fun test() {
        try {
            val signal = CountDownLatch(1)
            val licenseKeyCredentials = LicenseKeyCredential()
            licenseKeyCredentials.licenseKey = "71F86FD1E789"
            licenseKeyCredentials.endpoint = "iamwrong.com"
            Controller.init(licenseKeyCredentials) { error ->
                Assert.assertNotNull(error)
                val sdk = Controller.getInstance()
                sdk?.cleanup()

                signal.countDown()

            }
            signal.await()
            mActivityRule.finishActivity()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

    }
}