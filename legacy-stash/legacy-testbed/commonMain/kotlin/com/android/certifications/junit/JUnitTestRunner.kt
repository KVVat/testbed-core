package com.android.certifications.junit

import com.android.certifications.junit.xmlreport.AntXmlRunListener
import com.android.certifications.test.utils.output_path
import org.junit.internal.TextListener
import org.junit.runner.Computer
import org.junit.runner.JUnitCore
import org.junit.runner.notification.RunListener
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.file.Paths

//https://github.com/google/j2objc/blob/master/testing/junit_ext/src/java/com/google/j2objc/testing/JUnitTestRunner.java#L65
class JUnitTestRunner(classes_: Array<Class<*>?>,listener_:RunListener?=null) : Thread() {

    private val output:PrintStream = System.err
    private val classes: Array<Class<*>?> = classes_;
    private var listener: RunListener? = listener_;

    override fun run() {
        if(listener == null) listener = newRunListener();
        run_(classes);
    }
    /**
     * Runs the test classes given in {@param classes}.
     * @returns Zero if all tests pass, non-zero otherwise.
     */
    fun run_(classes: Array<Class<*>?> ): Int {

        val junitCore = JUnitCore()
        junitCore.addListener(listener)
        
        var hasError = false
        for (c in classes) {
            //ParallelComputer.methods()
            val result = junitCore.run(Computer.serial(),c)
            hasError = hasError || !result.wasSuccessful()
        }
        return if (hasError) 1 else 0
    }

    /**
     * Returns a new [RunListener] instance for the given {@param outputFormat}.
     */
    private fun newRunListener(): RunListener {

        return TextListener(output)
    }

}