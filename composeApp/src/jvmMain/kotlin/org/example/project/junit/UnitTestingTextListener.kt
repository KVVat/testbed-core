package com.android.certifications.junit
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener

class UnitTestingTextListener(cbPrint:(line:String)->Unit, private val finishHandler: () -> Unit) : RunListener() {
    private var numTests = 0
    private var numFailures = 0
    private var numSkipped = 0 // ★追加: スキップ数をカウント
    private val numUnexpected = 0
    private var testFailure: Failure? = null
    private var testAssumptionFailure: Failure? = null // ★追加: Assume失敗の保持
    private var testStartTime = 0.0

    private val print_=cbPrint;

    private fun printf(format: String, vararg args: Any) {
        // Avoid using printf() or println() because they will be flushed in pieces and cause
        // interleaving with logger messages.
        print_(String.format(format, *args))
    }

    @Throws(java.lang.Exception::class)
    override fun testStarted(description: Description) {

        numTests++
        testFailure = null
        testAssumptionFailure = null
        testStartTime = System.currentTimeMillis().toDouble()
        printf("Test Case '-[%s]' started.", parseDescription(description))
    }

    @Throws(java.lang.Exception::class)
    override fun testRunFinished(result: Result?){
        printf(
            "Executed %d tests, with %d failures and %d skipped (%d unexpected)", numTests, numFailures,numSkipped,
            numUnexpected
        )
        finishHandler()
    }


    @Throws(java.lang.Exception::class)
    override fun testFinished(description: Description) {
        val testEndTime = System.currentTimeMillis().toDouble()
        val elapsedSeconds = 0.001 * (testEndTime - testStartTime)

        var statusMessage = "passed"
        if (testFailure != null) {
            statusMessage = "failed"
            print_(testFailure!!.trace)
        }else if (testAssumptionFailure != null) {
            // ★追加: Assumeに引っかかった場合の表示
            statusMessage = "skipped (Assume Failed)"
            // Assumeのメッセージ（例: 【スキップ】10秒以内に有効なADBデバイスが...）をコンソールに出力
            printf("[SKIPPED REASON] %s", testAssumptionFailure!!.message ?: "Unknown assumption failure")
        }
        printf(
            "Test Case '-[%s]' %s (%.3f seconds).", parseDescription(description),
            statusMessage, elapsedSeconds
        )
    }

    @Throws(java.lang.Exception::class)
    override fun testFailure(failure: Failure?) {
        testFailure = failure
        numFailures++
    }

    @Throws(java.lang.Exception::class)
    override fun testAssumptionFailure(failure: Failure?) {
        testAssumptionFailure = failure
        numSkipped++
    }

    @Throws(java.lang.Exception::class)
    override fun testIgnored(description: Description?) {
        numSkipped++
        printf("Test Case '-[%s]' ignored by @Ignore annotation.", description?.let { parseDescription(it) } ?: "Unknown")
    }
    private fun parseDescription(description: Description): String {
        val displayName: String = description.getDisplayName()
        val p1 = displayName.indexOf("(")
        val p2 = displayName.indexOf(")")
        if (p1 < 0 || p2 < 0 || p2 <= p1) {
            return displayName
        }
        val methodName = displayName.substring(0, p1)
        val className = displayName.substring(p1 + 1, p2)
        return className+" "+ methodName
    }


}